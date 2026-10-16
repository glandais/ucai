package ucai.engine.race

import ucai.api.Order
import ucai.api.RiderMode
import ucai.api.Wind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class DeterminismTest {
    /** A scripted strategy: attacks, follows, pulls and sprints keyed on the decision id and the race state. */
    private val script: (DecisionPoint, Race) -> Map<String, List<Order>> = { dp, race ->
        val head = race.riders.maxOf { it.distM }
        val toGo = race.course.distanceM - head
        when {
            dp.decisionId == 3 -> mapOf(
                "T01" to listOf(Order(9, RiderMode.ATTACK, durationS = 40)),
                "T04" to listOf(Order(30, RiderMode.FOLLOW, targetRiderId = 9, maxIntensity = 1.2)),
            )
            dp.decisionId == 20 -> mapOf("T00" to (1..3).map { Order(it, RiderMode.PULL, intensity = 0.95) })
            toGo < 1_500 -> race.teamIds.associateWith { t ->
                race.riders.filter { it.teamId == t && it.id % 7 == 3 && it.status == ucai.api.RiderStatus.RACING && it.mode != RiderMode.SPRINT }.map { Order(it.id, RiderMode.SPRINT) }
            }
            else -> emptyMap()
        }
    }

    private fun run(seed: Long): Race =
        Race(Fixtures.course(60_000.0) { d -> if (d in 40_000.0..43_000.0) 0.06 else 0.0 }, Fixtures.field(), seed, Wind(200.0, 3.0))
            .also { runScripted(it, script) }

    @Test
    fun sameSeedSameOrdersSameRace() {
        val a = run(42)
        val b = run(42)
        assertEquals(a.events, b.events)
        assertEquals(a.results(), b.results())
        assertEquals(a.snapshot(), b.snapshot())
        assertEquals(a.tick, b.tick)
        val c = run(43)
        assertNotEquals(a.results().map { it.timeS }, c.results().map { it.timeS })
    }

    /** 70 riders over a full ~295 km course, no orders: must take well under a few seconds. */
    @Test
    fun performance() {
        val course = Fixtures.monument()
        Race(course, Fixtures.field(), 1).runToFinish() // warm-up
        val mark = TimeSource.Monotonic.markNow()
        val race = Race(course, Fixtures.field(), 2)
        race.runToFinish()
        val elapsed = mark.elapsedNow()
        val winner = race.results()[0]
        println("PERF 70 riders, ${course.distanceM / 1000} km, ${race.tick} ticks: $elapsed; winner avg ${(course.distanceM / winner.timeS!! * 3.6)} km/h")
        assertTrue(elapsed.inWholeMilliseconds < 3_000, "took $elapsed")
        assertEquals(70, race.results().count { it.position != null })
    }
}
