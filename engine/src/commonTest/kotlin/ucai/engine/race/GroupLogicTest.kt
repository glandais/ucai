package ucai.engine.race

import ucai.api.Order
import ucai.api.RaceEvent
import ucai.api.RiderMode
import ucai.api.RiderStatus
import ucai.engine.physics.descentSpeedCap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupLogicTest {
    @Test
    fun hysteresisNeedsThreeConsecutiveTicks() {
        var c = 0
        // Hovering around the 3 s threshold never reaches 3 consecutive ticks.
        repeat(20) { i ->
            c = SplitHysteresis.next(c, if (i % 3 == 2) 2.9 else 3.4, 3.0, frozen = false)
            assertFalse(SplitHysteresis.fires(c, 3), "fired at $i")
        }
        c = 0
        c = SplitHysteresis.next(c, 3.1, 3.0, false)
        c = SplitHysteresis.next(c, 3.1, 3.0, false)
        assertFalse(SplitHysteresis.fires(c, 3))
        // Frozen on a descent: neither grows nor resets.
        c = SplitHysteresis.next(c, 9.0, 3.0, frozen = true)
        assertEquals(2, c)
        c = SplitHysteresis.next(c, 3.1, 3.0, false)
        assertTrue(SplitHysteresis.fires(c, 3))
        assertEquals(0, SplitHysteresis.next(c, 3.0, 3.0, false), "exactly 3 s is not above the threshold")
    }

    /** A weak rider on a climb is dropped once, for good: one split, no merge back, no flapping. */
    @Test
    fun droppedOnceWithoutOscillation() {
        val course = Fixtures.course(12_000.0) { d -> if (d in 3_000.0..6_000.0) 0.07 else 0.0 }
        val riders = Fixtures.riders(8, Fixtures.STRONG, perTeam = 8) + RaceEntrant(99, "W", Fixtures.WEAK)
        val race = Race(course, riders, seed = 1)
        val log = TickLog().also { race.addObserver(it) }
        runScripted(race) { dp, _ -> if (dp.decisionId == 0) mapOf("T0" to listOf(Order(1, RiderMode.PULL, intensity = 0.95))) else emptyMap() }

        val splits = race.eventsOf<RaceEvent.GroupSplit>()
        assertEquals(1, splits.size, "splits: $splits")
        assertEquals(listOf(99), splits[0].riderIds)
        assertTrue(splits[0].gapS > 3.0)
        assertTrue(race.eventsOf<RaceEvent.GroupMerge>().isEmpty())
        assertEquals(listOf(99), race.eventsOf<RaceEvent.RiderDropped>().map { it.riderId })
        val groupChanges = log.frames.zipWithNext().count { (a, b) -> a.group[99] != b.group[99] }
        assertEquals(1, groupChanges)
        // Dropped on the climb, and the split fired only after 3 ticks above 3 s.
        assertTrue(splits[0].tick * 1.0 in 0.0..log.frames.size.toDouble())
        val dropDist = race.eventsOf<RaceEvent.RiderDropped>()[0].distM
        assertTrue(dropDist in 3_000.0..6_500.0, "dropped at $dropDist")
        val winnerTime = race.results()[0].timeS!!
        assertTrue(race.results().last().let { it.riderId == 99 && it.gapS!! > 10.0 })
        assertTrue(winnerTime > 0)
    }

    /** No `pull` rider: default rotation, every eligible rider takes seeded 30–60 s turns, recover riders never lead. */
    @Test
    fun defaultRotationWithoutPullRider() {
        val course = Fixtures.course(30_000.0)
        val race = Race(course, Fixtures.riders(10, perTeam = 10), seed = 3)
        val log = TickLog().also { race.addObserver(it) }
        runScripted(race) { dp, _ -> if (dp.decisionId == 0) mapOf("T0" to listOf(Order(10, RiderMode.RECOVER))) else emptyMap() }

        assertTrue(race.eventsOf<RaceEvent.GroupSplit>().isEmpty())
        val leaders = log.frames.drop(1).filter { it.group.size == 10 && it.dist.values.max() < course.distanceM }
            .map { f -> f.rank.entries.first { it.value == 1 }.key }
        val turns = mutableListOf<Pair<Int, Int>>() // leader, length
        for (l in leaders) if (turns.isNotEmpty() && turns.last().first == l) turns[turns.lastIndex] = l to turns.last().second + 1 else turns += l to 1
        assertTrue(turns.size >= 15, "turns: $turns")
        assertTrue(10 !in turns.map { it.first }, "recover rider led")
        assertEquals((1..9).toSet(), turns.map { it.first }.toSet())
        // All complete turns (not the first nor the last) last 30..60 s.
        turns.drop(1).dropLast(1).forEach { assertTrue(it.second in 30..60, "turn $it") }
        // The leader at the front does the default intensity × cpEff, capped by its maxIntensity (1.0 unordered).
        val f = log.frames[100]
        val lead = f.rank.entries.first { it.value == 1 }.key
        val intensity = minOf(race.config.defaultIntensity, 1.0)
        assertTrue(kotlin.math.abs(f.power.getValue(lead) - intensity * race.rider(lead).cpEff) < 15.0, "leader power ${f.power[lead]}")
        f.power.filterKeys { it != lead }.values.forEach { assertTrue(it < f.power.getValue(lead)) }
    }

    /** Stall guard: a rider far too weak for a 22 % ramp crawls up it at the crawl speed instead of stopping. */
    @Test
    fun crawlsUpRampsInsteadOfStalling() {
        val course = Fixtures.course(3_000.0) { d -> if (d in 1_000.0..2_000.0) 0.22 else 0.0 }
        val cooked = Fixtures.WEAK.copy(massKg = 100.0, cp = 150.0, wPrime = 5_000.0)
        val race = Race(course, listOf(RaceEntrant(1, "A", cooked)), seed = 1)
        val log = TickLog().also { race.addObserver(it) }
        runScripted(race) { dp, _ -> if (dp.decisionId == 0) mapOf("A" to listOf(Order(1, RiderMode.RECOVER))) else emptyMap() }
        assertEquals(RiderStatus.FINISHED, race.rider(1).status)
        val onRamp = log.frames.filter { it.dist.getValue(1) in 1_100.0..1_900.0 }
        assertTrue(onRamp.isNotEmpty())
        onRamp.forEach { assertTrue(it.speed.getValue(1) >= race.config.crawlSpeed - 1e-9, "speed ${it.speed[1]}") }
    }

    /** Descents: speed capped by the leader's skill, followers freewheel, no splits even with poor descenders. */
    @Test
    fun descents() {
        val course = Fixtures.course(14_000.0) { d -> if (d in 2_000.0..10_000.0) -0.07 else 0.0 }
        val riders = listOf(
            RaceEntrant(1, "A", Fixtures.ROULEUR.copy(descending = 0.3)),
            RaceEntrant(2, "A", Fixtures.ROULEUR.copy(descending = 0.0)),
            RaceEntrant(3, "B", Fixtures.ROULEUR.copy(descending = 1.0)),
            RaceEntrant(4, "B", Fixtures.WEAK.copy(descending = 0.0)),
        )
        val race = Race(course, riders, seed = 5)
        val log = TickLog().also { race.addObserver(it) }
        runScripted(race)

        assertTrue(race.eventsOf<RaceEvent.GroupSplit>().isEmpty(), "${race.events}")
        var checked = 0
        for (f in log.frames) {
            val front = f.dist.values.max()
            if (front !in 2_100.0..9_900.0) continue
            assertEquals(1, f.groups)
            val lead = f.rank.entries.first { it.value == 1 }.key
            val cap = descentSpeedCap(riders.first { it.id == lead }.profile.descending, -0.07)
            assertTrue(f.speed.getValue(lead) <= cap + 1e-9, "speed ${f.speed[lead]} > cap $cap")
            f.power.filterKeys { it != lead }.values.forEach { assertEquals(0.0, it, "followers freewheel") }
            assertEquals(1, f.speed.values.toSet().size, "one speed for the group")
            checked++
        }
        assertTrue(checked > 300)
        val times = race.results().map { it.timeS!! }.toSet()
        assertEquals(1, times.size)
    }
}
