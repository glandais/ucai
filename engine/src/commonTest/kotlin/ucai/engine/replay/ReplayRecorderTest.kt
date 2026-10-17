package ucai.engine.replay

import ucai.api.ApiJson
import ucai.api.ClockConfig
import ucai.api.ClockMode
import ucai.api.FrameRider
import ucai.api.Order
import ucai.api.PublicRider
import ucai.api.RaceDetail
import ucai.api.RaceEvent
import ucai.api.RaceState
import ucai.api.Replay
import ucai.api.RiderMode
import ucai.api.RiderRatings
import ucai.api.TeamInfo
import ucai.engine.race.Fixtures
import ucai.engine.race.Race
import ucai.engine.race.RaceEntrant
import ucai.engine.race.runScripted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReplayRecorderTest {
    private val attacker = 100
    private val attackTick = 600

    private fun recorded(): Triple<Race, ReplayRecorder, Replay> {
        val riders = Fixtures.riders(14) + RaceEntrant(attacker, "A", Fixtures.STRONG)
        val race = Race(Fixtures.course(20_000.0), riders, seed = 3)
        val rec = ReplayRecorder()
        race.addObserver(rec)
        var attacked = false
        runScripted(race) { dp, _ ->
            if (!attacked && dp.tick >= attackTick) {
                attacked = true
                mapOf("A" to listOf(Order(attacker, RiderMode.ATTACK, intensity = 0.8, durationS = 20)))
            } else emptyMap()
        }
        val ratings = RiderRatings(3, 3, 3, 3, 3)
        // Public riders deliberately in reverse order: the replay puts them in frame order.
        val pub = riders.reversed().map { PublicRider(it.id, "R${it.id}", it.teamId, ucai.api.Archetype.ROULEUR, ratings) }
        val teams = riders.map { it.teamId }.distinct().map { TeamInfo(it, "Team $it", "#123456") }
        val detail = RaceDetail(
            "r1", race.course.id, race.course.name, RaceState.FINISHED, ClockConfig(ClockMode.LOCKSTEP),
            teams = teams, riders = pub, tick = race.tick, seed = 3,
        )
        return Triple(race, rec, rec.replay(detail, race))
    }

    @Test
    fun adaptiveFrameStep() {
        val (race, _, replay) = recorded()
        val ticks = replay.frames.map { it.t }
        assertEquals(0, ticks.first())
        assertEquals(race.tick, ticks.last(), "the last tick of the race is recorded")
        assertEquals(ticks.sorted().distinct(), ticks, "strictly increasing")

        val attack = replay.events.filterIsInstance<RaceEvent.Attack>().single()
        // Head of race within 3 km of the finish: 1 s steps once the leader is past 17 km. Frames keep distances to
        // 0.1 m, so the frame where the head crosses is ambiguous, and the step into it may still be coarse: the
        // frames up to the first one clearly past the mark are not checked.
        val mark = race.course.distanceM - 3_000.0
        val crossFrom = replay.frames.first { f -> f.r.maxOf { it.distM } >= mark - 0.05 }.t
        val fineFrom = replay.frames.first { f -> f.r.maxOf { it.distM } >= mark + 0.05 }.t
        for ((a, b) in ticks.zipWithNext()) {
            val fine = b in (attack.tick + 1)..(attack.tick + 60) || b > fineFrom
            if (b == ticks.last() || b in crossFrom..fineFrom) continue
            if (fine) assertEquals(1, b - a, "1 s step at $b") else {
                assertEquals(0, b % 5, "coarse frames on multiples of 5 s ($b)")
                assertTrue(b - a in 1..5, "coarse step at $b")
            }
        }
        // Coarse most of the time: far fewer frames than ticks.
        assertTrue(ticks.size < race.tick / 2, "${ticks.size} frames for ${race.tick} ticks")
        assertTrue((attack.tick + 1..attack.tick + 60).all { it in ticks })
    }

    @Test
    fun coarseFramesAfterTheWinnerWhileTheRoadIsFarFromTheLine() {
        val solo = 100
        val course = Fixtures.course(25_000.0)
        val riders = Fixtures.riders(6, Fixtures.WEAK, perTeam = 6) + RaceEntrant(solo, "A", Fixtures.STRONG)
        val race = Race(course, riders, seed = 5)
        val rec = ReplayRecorder()
        race.addObserver(rec)
        runScripted(race) { dp, _ ->
            if (dp.decisionId == 0) {
                mapOf("A" to listOf(Order(solo, RiderMode.PULL, intensity = 1.0)), "T0" to riders.filter { it.teamId == "T0" }.map { Order(it.id, RiderMode.RECOVER) })
            } else emptyMap()
        }
        val winTick = race.events.filterIsInstance<RaceEvent.RiderFinished>().first { it.position == 1 }.tick
        val frames = rec.frames
        // Finished riders: standing at the line, out of every group.
        val last = frames.last().r
        race.riders.forEachIndexed { i, r ->
            if (r.finishTimeS != null) {
                assertEquals(FrameRider.FINISHED_GROUP, last[i].groupId)
                assertEquals(0.0, last[i].speed)
            }
        }
        val after = frames.zipWithNext().filter { (a, b) ->
            a.t > winTick && b != frames.last() &&
                b.r.filter { it.groupId != FrameRider.FINISHED_GROUP }.maxOf { it.distM } < course.distanceM - 3_000.0
        }
        assertTrue(after.size > 10, "the winner finishes well ahead (${after.size} frames)")
        for ((a, b) in after) assertEquals(5, b.t - a.t, "5 s step at ${b.t}")
    }

    @Test
    fun contentMatchesRace() {
        val (race, rec, replay) = recorded()
        assertEquals(race.riders.map { it.id }, replay.riders.map { it.id }, "riders in frame order")
        assertEquals(rec.riderIds, replay.riders.map { it.id })
        assertTrue(replay.frames.all { it.r.size == race.riders.size })
        assertEquals(race.events, replay.events)
        assertEquals(race.classification("r1"), replay.results)
        assertTrue(replay.results.final)
        assertEquals(1, replay.version)
        assertEquals(race.course.points.size, replay.course.points.size)

        val last = replay.frames.last().r
        race.riders.forEachIndexed { i, r ->
            assertEquals(r.groupId, last[i].groupId)
            assertEquals(r.rankInGroup, last[i].rankInGroup)
            assertTrue(kotlin.math.abs(r.distM - last[i].distM) <= 0.05)
        }
        // Frame at tick 0: everyone at km 0 in group 1.
        assertTrue(replay.frames.first().r.all { it.distM == 0.0 && it.groupId == 1 })
    }

    @Test
    fun jsonRoundTrip() {
        val (_, _, replay) = recorded()
        val json = ApiJson.encodeToString(Replay.serializer(), replay)
        assertTrue("\"type\":\"attack\"" in json)
        assertTrue("\"r\":[[" in json, "compact frame arrays")
        assertEquals(replay, ApiJson.decodeFromString(Replay.serializer(), json))
    }

    @Test
    fun refusesUnfinishedRaceAndMismatchedRiders() {
        val race = Race(Fixtures.course(5_000.0), Fixtures.riders(3), seed = 1)
        val rec = ReplayRecorder()
        race.addObserver(rec)
        race.start()
        val detail = RaceDetail("x", "test", "test", RaceState.RUNNING, ClockConfig(ClockMode.LOCKSTEP))
        assertFailsWith<IllegalStateException> { rec.replay(detail, race) }
        race.runToFinish()
        val finished = detail.copy(state = RaceState.FINISHED)
        assertFailsWith<IllegalArgumentException> { rec.replay(finished, race) }
    }

    @Test
    fun sameSeedSameReplay() {
        assertEquals(recorded().third, recorded().third)
    }
}
