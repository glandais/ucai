package ucai.team.strategy

import ucai.api.Archetype
import ucai.api.Climb
import ucai.api.CourseDetail
import ucai.api.CoursePoint
import ucai.api.DecisionEvent
import ucai.api.DecisionReason
import ucai.api.GroupSummary
import ucai.api.PrivateRider
import ucai.api.PrivateRiderState
import ucai.api.RaceEvent
import ucai.api.RiderMode
import ucai.api.RiderProfile
import ucai.api.RiderRatings
import ucai.api.RiderStatus
import ucai.api.Snapshot
import ucai.api.SnapshotRider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TeamStrategyTest {
    private val course = CourseDetail(
        "liege-bastogne-liege", "LBL", 250_000.0, 4_000.0,
        points = listOf(CoursePoint(50.0, 5.0, 100.0, 0.0), CoursePoint(50.1, 5.0, 100.0, 250_000.0)),
        climbs = listOf(Climb("A", 200_000.0, 202_000.0, 0.08, 0.1), Climb("B", 236_000.0, 237_500.0, 0.09, 0.11)),
    )

    private fun rider(id: Int, team: String, a: Archetype, r: RiderRatings, cp: Double = 380.0, pMax: Double = 1100.0) =
        PrivateRider(id, "R$id", team, a, r, RiderProfile(70.0, cp, 22_000.0, pMax, 0.32, 0.7, 0.6))

    private fun team(team: String, first: Int) = listOf(
        rider(first, team, Archetype.PUNCHEUR, RiderRatings(4, 5, 3, 4, 3)),
        rider(first + 1, team, Archetype.CLIMBER, RiderRatings(5, 3, 1, 4, 3)),
        rider(first + 2, team, Archetype.SPRINTER, RiderRatings(1, 2, 5, 2, 3), pMax = 1650.0),
        rider(first + 3, team, Archetype.ROULEUR, RiderRatings(2, 2, 3, 3, 3), cp = 430.0),
        rider(first + 4, team, Archetype.DOMESTIQUE, RiderRatings(1, 1, 2, 1, 3)),
        rider(first + 5, team, Archetype.DOMESTIQUE, RiderRatings(1, 1, 2, 1, 3)),
        rider(first + 6, team, Archetype.ALL_ROUNDER, RiderRatings(4, 4, 2, 5, 3)),
    )

    private val mine = team("A", 1)
    private val other = team("B", 8)
    private val field = (mine + other).map { it.toPublic() }

    private fun state(r: PrivateRider, distM: Double) = PrivateRiderState(
        r.id, distM, 11.0, 250.0, 22_000.0, r.profile.cp, 22_000.0, 1000.0, 1, 1, RiderMode.FOLLOW, RiderStatus.RACING,
    )

    /** Everyone in one 14-rider group at [distM]. */
    private fun event(tick: Int, distM: Double, events: List<RaceEvent> = emptyList()) = DecisionEvent(
        decisionId = tick, tick = tick, reasons = listOf(DecisionReason.PERIODIC),
        snapshot = Snapshot(
            tick, tick.toDouble(), (mine + other).map { SnapshotRider(it.id, distM, 11.0, 1, it.id) },
            listOf(GroupSummary(1, distM, 14, 0.0)),
        ),
        team = mine.map { state(it, distM) },
        events = events,
    )

    @Test
    fun roles() {
        val s = TeamStrategy("A", course, field, mine)
        assertEquals(FinaleKind.PUNCHY, s.kind)
        assertEquals(Archetype.PUNCHEUR, s.roles.leader.archetype)
        assertEquals(Archetype.SPRINTER, s.roles.sprinter.archetype)
        assertNotNull(s.roles.lieutenant)
        assertEquals(Archetype.ROULEUR, s.roles.breakaway.archetype, "the strongest domestique goes in the break")
        assertEquals(Role.DOMESTIQUE, s.roles.role(5))
    }

    @Test
    fun ordersOnlyOwnRidersAndOnlyChanges() {
        val s = TeamStrategy("A", course, field, mine)
        val first = s.decide(event(0, 0.0))
        assertTrue(first.all { o -> mine.any { it.id == o.riderId } })
        assertEquals(mine.size, first.size, "everyone gets an initial order")
        assertTrue(s.decide(event(30, 300.0)).isEmpty(), "nothing changed, nothing sent")
    }

    @Test
    fun breakawayRiderAttacksEarly() {
        val s = TeamStrategy("A", course, field, mine, StrategyParams(breakaway = true))
        val orders = (0..60).flatMap { k -> s.decide(event(k * 30, k * 400.0)) }
        val attacks = orders.filter { it.mode == RiderMode.ATTACK }
        assertTrue(attacks.isNotEmpty() && attacks.size <= Tactics.BREAK_ATTEMPTS)
        assertTrue(attacks.all { it.riderId == s.roles.breakaway.id })
        val none = TeamStrategy("A", course, field, mine)
        assertTrue((0..60).flatMap { k -> none.decide(event(k * 30, k * 400.0)) }.none { it.mode == RiderMode.ATTACK })
    }

    @Test
    fun leaderAttacksOnTheLastClimbAndSprintsAtTheEnd() {
        val s = TeamStrategy("A", course, field, mine)
        val byDist = (0..600).map { k -> 230_000.0 + k * 40.0 to s.decide(event(20_000 + k * 30, 230_000.0 + k * 40.0)) }
        val orders = byDist.flatMap { it.second }
        fun leaderAttacks(range: ClosedFloatingPointRange<Double>) = byDist.filter { it.first in range }
            .flatMap { it.second }.count { it.mode == RiderMode.ATTACK && it.riderId == s.roles.leader.id }
        assertEquals(1, leaderAttacks(0.0..course.climbs.last().endM), "one attack on the last climb")
        // After it, at most one late attack in the last km (a leader who would lose the sprint of his group).
        assertTrue(leaderAttacks(course.climbs.last().endM..course.distanceM) <= 1)
        val domestiques = s.roles.domestiques.map { it.id }
        assertTrue(orders.any { it.mode == RiderMode.PULL && it.riderId in domestiques }, "domestiques pull on the climb")
        assertTrue(orders.any { it.mode == RiderMode.SPRINT && it.riderId == s.roles.sprinter.id })
    }

    @Test
    fun leaderFollowsARivalLeaderAttack() {
        val s = TeamStrategy("A", course, field, mine)
        s.decide(event(0, 210_000.0))
        val rival = FieldReading(field, FinaleKind.PUNCHY).leaders.getValue("B")
        val orders = s.decide(event(30, 210_400.0, listOf(RaceEvent.Attack(30, rival.id, 1, 210_400.0))))
        val follow = orders.single { it.targetRiderId != null }
        assertEquals(s.roles.leader.id, follow.riderId)
        assertEquals(rival.id, follow.targetRiderId)
        assertEquals(RiderMode.FOLLOW, follow.mode)
    }

    @Test
    fun deterministic() {
        fun run() = TeamStrategy("A", course, field, mine, StrategyParams(aggressiveness = 0.7, breakaway = true, seed = 4))
            .let { s -> (0..400).flatMap { k -> s.decide(event(k * 30, k * 600.0)) } }
        assertEquals(run(), run())
    }
}
