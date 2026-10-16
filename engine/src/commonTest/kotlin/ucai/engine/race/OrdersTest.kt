package ucai.engine.race

import ucai.api.DecisionReason
import ucai.api.Order
import ucai.api.RaceEvent
import ucai.api.RejectReason
import ucai.api.RiderMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrdersTest {
    private fun race(seed: Long = 1) = Race(
        Fixtures.course(10_000.0),
        listOf(1, 2, 3).map { RaceEntrant(it, "A", Fixtures.ROULEUR) } + listOf(4, 5, 6).map { RaceEntrant(it, "B", Fixtures.ROULEUR) },
        seed,
    )

    @Test
    fun validation() {
        val race = race()
        assertEquals(RejectReason.RACE_NOT_RUNNING, race.validate("A", 0, Order(1, RiderMode.PULL))?.reason)
        val dp = race.start()
        assertEquals(0, dp.decisionId)
        assertEquals(listOf(DecisionReason.RACE_STARTED), dp.reasons)
        fun reason(o: Order, team: String = "A", decision: Int? = dp.decisionId) = race.validate(team, decision, o)?.reason
        assertNull(reason(Order(1, RiderMode.PULL, intensity = 0.9)))
        assertEquals(RejectReason.STALE_DECISION, reason(Order(1, RiderMode.PULL), decision = 3))
        assertEquals(RejectReason.MISSING_DECISION, reason(Order(1, RiderMode.PULL), decision = null))
        assertNull(race.validate("A", null, Order(1, RiderMode.PULL), lockstep = false), "decisionId optional in realtime")
        assertEquals(RejectReason.UNKNOWN_RIDER, reason(Order(99, RiderMode.PULL)))
        assertEquals(RejectReason.NOT_OWN_RIDER, reason(Order(4, RiderMode.PULL)))
        assertEquals(RejectReason.INVALID_VALUE, reason(Order(1, RiderMode.PULL, intensity = 3.0)))
        assertEquals(RejectReason.INVALID_VALUE, reason(Order(1, RiderMode.PULL, intensity = Double.NaN)))
        assertEquals(RejectReason.INVALID_VALUE, reason(Order(1, RiderMode.ATTACK, durationS = 0)))
        assertEquals(RejectReason.INVALID_VALUE, reason(Order(1, RiderMode.ATTACK, intensity = 1.2)))
        assertEquals(RejectReason.INVALID_VALUE, reason(Order(1, RiderMode.FOLLOW, targetRiderId = 1)))
        assertEquals(RejectReason.INVALID_VALUE, reason(Order(1, RiderMode.FOLLOW, targetRiderId = 77)))
        assertEquals(RejectReason.INVALID_VALUE, reason(Order(1, RiderMode.FOLLOW, maxIntensity = 0.1)))
        assertNull(reason(Order(1, RiderMode.FOLLOW, targetRiderId = 4, maxIntensity = 1.2)))

        val next = race.runToNextDecision()!!
        assertEquals(RejectReason.STALE_DECISION, race.validate("A", dp.decisionId, Order(1, RiderMode.PULL))?.reason)
        assertNull(race.validate("A", next.decisionId, Order(1, RiderMode.PULL)))
        race.runToFinish()
        assertEquals(RejectReason.RACE_NOT_RUNNING, race.validate("A", next.decisionId, Order(1, RiderMode.PULL))?.reason)
    }

    @Test
    fun lastOrderForARiderWins() {
        val race = race()
        val dp = race.start()
        val res = race.applyOrders(
            dp.decisionId,
            mapOf("A" to listOf(Order(1, RiderMode.PULL, intensity = 0.8), Order(2, RiderMode.PULL), Order(1, RiderMode.RECOVER), Order(4, RiderMode.PULL))),
        )
        assertEquals(3, res.getValue("A").accepted.size)
        assertEquals(listOf(RejectReason.NOT_OWN_RIDER), res.getValue("A").rejected.map { it.reason })
        assertEquals(RiderMode.RECOVER, race.rider(1).mode)
        assertEquals(RiderMode.PULL, race.rider(2).mode)
        assertEquals(RiderMode.FOLLOW, race.rider(4).mode)
        // Same body twice: idempotent.
        race.applyOrders(dp.decisionId, mapOf("A" to listOf(Order(1, RiderMode.RECOVER))))
        assertEquals(RiderMode.RECOVER, race.rider(1).mode)
    }

    /** Canonical order: teamId, then riderId. Arrival order (map order, list order) never matters. */
    @Test
    fun canonicalOrderingIgnoresArrivalOrder() {
        fun run(orders: Map<String, List<Order>>): Race {
            val race = race()
            val dp = race.start()
            race.applyOrders(dp.decisionId, orders)
            race.runToFinish()
            return race
        }
        val a = linkedMapOf(
            "B" to listOf(Order(5, RiderMode.ATTACK, durationS = 20), Order(4, RiderMode.ATTACK, intensity = 0.8, durationS = 20)),
            "A" to listOf(Order(3, RiderMode.ATTACK, durationS = 15), Order(1, RiderMode.PULL)),
        )
        val b = linkedMapOf(
            "A" to listOf(Order(1, RiderMode.PULL), Order(3, RiderMode.ATTACK, durationS = 15)),
            "B" to listOf(Order(4, RiderMode.ATTACK, intensity = 0.8, durationS = 20), Order(5, RiderMode.ATTACK, durationS = 20)),
        )
        val ra = run(a)
        val rb = run(b)
        assertEquals(listOf(3, 4, 5), ra.eventsOf<RaceEvent.Attack>().map { it.riderId })
        assertEquals(ra.events, rb.events)
        assertEquals(ra.results(), rb.results())
    }

    @Test
    fun decisionPointsSpacing() {
        val race = Race(Fixtures.monument(), Fixtures.field(), seed = 4)
        val dps = mutableListOf<DecisionPoint>()
        var dp: DecisionPoint? = race.start()
        while (dp != null) {
            dps += dp
            // Some attacks to create events.
            if (dp.decisionId % 40 == 5) race.applyOrders(dp.decisionId, mapOf("T03" to listOf(Order(24, RiderMode.ATTACK, durationS = 30))))
            dp = race.runToNextDecision()
        }
        dps.zipWithNext().forEach { (x, y) ->
            assertTrue(y.tick - x.tick >= 5, "spacing $x $y")
            assertTrue(y.tick - x.tick <= 30)
            assertEquals(x.decisionId + 1, y.decisionId)
            if (DecisionReason.PERIODIC !in y.reasons) assertTrue(y.events.isNotEmpty() || DecisionReason.TARGET_LOST in y.reasons)
        }
        assertTrue(dps.any { DecisionReason.ATTACK in it.reasons })
        assertTrue(dps.any { DecisionReason.KM_TO_GO in it.reasons })
        // Events since the previous decision point: every non-finish event is delivered exactly once.
        val delivered = dps.flatMap { it.events }
        val expected = race.events.filter { e -> e.tick <= dps.last().tick && e !is RaceEvent.RaceFinished }
        assertEquals(expected.filter { it.tick < dps.last().tick }, delivered.filter { it.tick < dps.last().tick })
        // Team private state only shows the team's own riders.
        val team = race.teamState("T03")
        assertEquals(7, team.size)
        assertNotEquals(0.0, team[0].energyKj)
    }
}
