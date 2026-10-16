package ucai.engine.race

import ucai.api.DecisionReason
import ucai.api.Order
import ucai.api.RaceEvent
import ucai.api.RiderMode
import ucai.api.RiderStatus
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RaceScenarioTest {
    private val attacker = 100

    /**
     * The attack scenarios assume a peloton riding easy (nobody chases): the tuned default rotation rides at
     * threshold, where a lone rider at 0.9 × cpEff cannot gain on an equal bunch, so they pin the batch-1 tempo.
     */
    private val easyPeloton = RaceConfig(defaultIntensity = 0.65, climbIntensity = 0.65)

    @Test
    fun attackStaysAway() {
        val riders = Fixtures.riders(20) + RaceEntrant(attacker, "A", Fixtures.STRONG)
        val race = Race(Fixtures.course(40_000.0), riders, seed = 11, config = easyPeloton)
        var attacked = false
        runScripted(race) { dp, _ ->
            if (!attacked && dp.tick >= 60) {
                attacked = true
                mapOf("A" to listOf(Order(attacker, RiderMode.ATTACK, intensity = 1.0, durationS = 60)))
            } else emptyMap()
        }
        val attack = race.eventsOf<RaceEvent.Attack>().single()
        assertEquals(attacker, attack.riderId)
        val split = race.eventsOf<RaceEvent.GroupSplit>().first()
        assertEquals(listOf(attacker), split.riderIds)
        assertTrue(split.gapS < 0, "the new group is ahead")
        assertTrue(split.tick > attack.tick + 3, "the split only fires after the hysteresis")
        assertTrue(race.eventsOf<RaceEvent.GroupMerge>().isEmpty())
        val res = race.results()
        assertEquals(attacker, res[0].riderId)
        assertTrue(res[1].gapS!! > 60.0, "gap ${res[1].gapS}")
        // UCI group time: the whole peloton gets the same time.
        assertEquals(1, res.drop(1).map { it.gapS }.toSet().size)
        // After the burst the attacker rides on in `pull` at 0.9.
        assertEquals(RiderMode.PULL, race.rider(attacker).mode)
        assertEquals(RiderStatus.FINISHED, race.rider(attacker).status)
    }

    @Test
    fun attackGetsCaught() {
        val riders = Fixtures.riders(15) + RaceEntrant(attacker, "A", Fixtures.WEAK) +
            (201..203).map { RaceEntrant(it, "B", Fixtures.STRONG) }
        val race = Race(Fixtures.course(30_000.0), riders, seed = 12)
        val reasons = mutableListOf<List<DecisionReason>>()
        var phase = 0
        runScripted(race) { dp, _ ->
            reasons += dp.reasons
            when {
                phase == 0 && dp.tick >= 60 -> {
                    phase = 1
                    mapOf("A" to listOf(Order(attacker, RiderMode.ATTACK, intensity = 1.0, durationS = 20)))
                }
                phase == 1 && DecisionReason.GROUP_SPLIT in dp.reasons -> {
                    phase = 2
                    mapOf("B" to (201..203).map { Order(it, RiderMode.PULL, intensity = 1.0) })
                }
                else -> emptyMap()
            }
        }
        assertEquals(2, phase)
        assertTrue(reasons.any { DecisionReason.ATTACK in it })
        val split = race.eventsOf<RaceEvent.GroupSplit>().first()
        assertEquals(listOf(attacker), split.riderIds)
        val merge = race.eventsOf<RaceEvent.GroupMerge>().single()
        assertEquals(split.newGroupId, merge.groupId, "the attacker's group was caught")
        assertEquals(listOf(attacker), merge.riderIds)
        assertTrue(merge.tick > split.tick)
        assertTrue(reasons.any { DecisionReason.GROUP_MERGE in it })
        // Back in the bunch: same group time as the winner.
        assertEquals(0.0, race.results().first { it.riderId == attacker }.gapS)
    }

    @Test
    fun followWithTargetBridgesAcross() {
        val riders = Fixtures.riders(15) + RaceEntrant(attacker, "A", Fixtures.ROULEUR) + RaceEntrant(300, "C", Fixtures.STRONG)
        val race = Race(Fixtures.course(25_000.0), riders, seed = 13, config = easyPeloton)
        runScripted(race) { dp, _ ->
            if (dp.decisionId == 2) mapOf(
                "A" to listOf(Order(attacker, RiderMode.ATTACK, intensity = 0.9, durationS = 40)),
                "C" to listOf(Order(300, RiderMode.FOLLOW, targetRiderId = attacker, maxIntensity = 1.5)),
            ) else emptyMap()
        }
        val split = race.eventsOf<RaceEvent.GroupSplit>().first()
        assertEquals(setOf(attacker, 300), split.riderIds.toSet(), "the follower went with the attack: ${race.events}")
        val res = race.results()
        assertEquals(setOf(attacker, 300), res.take(2).map { it.riderId }.toSet())
    }

    @Test
    fun followCappedByMaxIntensityLosesTheTarget() {
        val riders = Fixtures.riders(15) + RaceEntrant(attacker, "A", Fixtures.STRONG) + RaceEntrant(300, "C", Fixtures.WEAK)
        val race = Race(Fixtures.course(20_000.0), riders, seed = 14)
        val reasons = mutableListOf<DecisionReason>()
        runScripted(race) { dp, _ ->
            reasons += dp.reasons
            if (dp.decisionId == 2) mapOf(
                "A" to listOf(Order(attacker, RiderMode.ATTACK, intensity = 1.0, durationS = 60)),
                "C" to listOf(Order(300, RiderMode.FOLLOW, targetRiderId = attacker, maxIntensity = 0.9)),
            ) else emptyMap()
        }
        assertTrue(DecisionReason.TARGET_LOST in reasons)
        assertEquals(attacker, race.results()[0].riderId)
    }

    /** Sprinters launch at 300 m to go and beat the bunch; the bunch shares one group time. */
    @Test
    fun sprint() {
        val s1 = 101
        val s2 = 102
        val leadOut = 103
        val riders = Fixtures.riders(15) + listOf(
            RaceEntrant(s1, "S1", Fixtures.SPRINTER.copy(pMax = 1650.0)),
            RaceEntrant(s2, "S2", Fixtures.SPRINTER.copy(pMax = 1450.0)),
            RaceEntrant(leadOut, "S2", Fixtures.STRONG),
        )
        val course = Fixtures.course(8_000.0)
        val race = Race(course, riders, seed = 21)
        val log = TickLog().also { race.addObserver(it) }
        var ordered = false
        runScripted(race) { _, r ->
            val head = r.riders.maxOf { it.distM }
            if (!ordered && course.distanceM - head < 1_800) {
                ordered = true
                mapOf(
                    "S1" to listOf(Order(s1, RiderMode.SPRINT)),
                    "S2" to listOf(Order(s2, RiderMode.SPRINT), Order(leadOut, RiderMode.PULL, intensity = 1.1)),
                )
            } else emptyMap()
        }
        assertTrue(ordered)
        val res = race.results()
        assertEquals(setOf(s1, s2), res.take(2).map { it.riderId }.toSet(), "$res")
        // Sprint power: well above CP, near pMax at the launch.
        val peak = log.frames.maxOf { it.power.getValue(s1) }
        assertTrue(peak > 1_300.0, "peak $peak")
        // Launched within the last 300 m only.
        val launch = log.frames.first { it.power.getValue(s1) > 1_000.0 }
        assertTrue(course.distanceM - launch.dist.getValue(s1) <= 300.0 + 20.0, "launched at ${launch.dist[s1]}")
        val bunch = res.filter { it.riderId !in setOf(s1, s2) }
        assertEquals(1, bunch.map { it.gapS }.toSet().size, "bunch group time")
        assertTrue(bunch[0].gapS!! > 1.0)
    }

    /** Finish time interpolated between ticks: t = t0 + (finishM − dist0) / v. */
    @Test
    fun finishInterpolation() {
        val course = Fixtures.course(1_234.5)
        val race = Race(course, listOf(RaceEntrant(1, "A", Fixtures.ROULEUR)), seed = 1)
        val log = TickLog().also { race.addObserver(it) }
        runScripted(race)
        val i = log.frames.indexOfFirst { it.dist.getValue(1) >= course.distanceM }
        val before = log.frames[i - 1]
        val at = log.frames[i]
        val expected = before.tick + (course.distanceM - before.dist.getValue(1)) / at.speed.getValue(1)
        val r = race.results().single()
        assertEquals(1, r.position)
        assertTrue(abs(r.timeS!! - expected) < 1e-9, "${r.timeS} vs $expected")
        assertTrue(r.timeS!! > before.tick && r.timeS!! < at.tick)
        val fin = race.eventsOf<RaceEvent.RiderFinished>().single()
        assertEquals(r.timeS, fin.timeS)
        assertNotNull(race.eventsOf<RaceEvent.RaceFinished>().singleOrNull())
    }

    /** UCI group time: the gap chain (< 1 s) keeps a group together; a bigger gap starts a new group time. */
    @Test
    fun groupTimes() {
        val course = Fixtures.course(15_000.0) { d -> if (d in 8_000.0..11_000.0) 0.06 else 0.0 }
        val riders = Fixtures.riders(6, Fixtures.STRONG, perTeam = 6) + Fixtures.riders(4, Fixtures.WEAK, firstId = 50, perTeam = 4)
            .map { it.copy(teamId = "W") }
        val race = Race(course, riders, seed = 2)
        runScripted(race) { dp, _ -> if (dp.decisionId == 0) mapOf("T0" to listOf(Order(1, RiderMode.PULL, intensity = 1.0))) else emptyMap() }
        val res = race.results()
        val strong = res.filter { it.riderId < 50 }
        val weak = res.filter { it.riderId >= 50 }
        assertTrue(strong.all { it.gapS == 0.0 }, "$strong")
        assertTrue(weak.all { it.gapS!! > 5.0 }, "$weak")
        assertEquals((1..10).toList(), res.map { it.position })
        // Within a group, times are equal or chained by less than 1 s, and group time = first rider's time.
        for (e in weak) assertTrue(e.timeS!! - (weak[0].timeS!!) >= 0)
        val wGroup = weak.groupBy { it.gapS }
        for ((gap, members) in wGroup) {
            assertEquals(gap!!, members[0].timeS!! - res[0].timeS!!, 1e-9)
            members.zipWithNext().forEach { (a, b) -> assertTrue(b.timeS!! - a.timeS!! < 1.0) }
        }
    }
}
