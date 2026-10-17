package ucai.runner.calibration

import kotlinx.serialization.Serializable
import ucai.api.ApiJson
import ucai.api.Replay
import ucai.engine.race.RaceObserver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The frontend computes its own time gaps from the replay frames (DESIGN §3 "Time gaps"); its test
 * `frontend/src/replay/engineGaps.test.ts` checks them against the engine's `GroupSummary.gapS` on a real race,
 * from the fixture this test owns: a window of Il Lombardia (harness, seed 1) just after the early break was caught,
 * with the gap the engine gives each group at its last frame.
 *
 * The fixture must match the engine: after an engine or strategy change, regenerate it with
 * `UCAI_WRITE_FIXTURES=1 ./gradlew :runner:test --tests '*GapFixtureTest*'`.
 */
class GapFixtureTest {
    @Serializable
    data class GroupGap(val id: Int, val size: Int, val headDistM: Double, val gapS: Double)

    @Serializable
    data class Fixture(val t: Int, val groups: List<GroupGap>, val replay: Replay)

    @Test
    fun fixtureMatchesTheEngine() {
        val json = ApiJson.encodeToString(Fixture.serializer(), build())
        val file = File(FIXTURE)
        if (System.getenv("UCAI_WRITE_FIXTURES") == "1") file.writeText(json + "\n")
        assertTrue(file.exists(), "missing $FIXTURE: run with UCAI_WRITE_FIXTURES=1")
        assertEquals(json, file.readText().trimEnd(), "$FIXTURE is stale: run with UCAI_WRITE_FIXTURES=1")
    }

    private fun build(): Fixture {
        val course = Calibration.loadCourse(File("races/courses"), "il-lombardia")
        // Every group's real gap, each 5 s: the fixture's tick is picked once the break is known.
        val gaps = HashMap<Int, List<GroupGap>>()
        val observer = RaceObserver { race, _ ->
            if (race.tick % STEP_S == 0) {
                gaps[race.tick] = race.groups.map { GroupGap(it.id, it.size, it.headDistM, race.gapAt(it.headDistM)) }
                    .sortedByDescending { it.headDistM }
            }
        }
        val run = Calibration.run(course, 1, observer = observer)
        val replay = run.replay
        // A few km after the early break was caught (a rider who was not in it is first on the road): the groups
        // behind have gaps to the break's passage (the engine's definition), not to the current leader's.
        val breakIdx = run.breakaway.breakRiderIds.map { id -> replay.riders.indexOfFirst { it.id == id } }.toSet()
        fun lead(f: ucai.api.ReplayFrame) =
            breakIdx.maxOf { f.r[it].distM } - f.r.withIndex().filter { it.index !in breakIdx }.maxOf { it.value.distM }
        val away = replay.frames.indexOfFirst { lead(it) > BREAK_AWAY_M }
        val caught = replay.frames.drop(away).first { lead(it) <= 0.0 }
        val caughtM = caught.r.maxOf { it.distM }
        val frameTicks = replay.frames.map { it.t }.toSet()
        // The head is past the catch, and groups still behind the catch point have gaps to the break's passage.
        val t = gaps.keys.sorted().first { tick ->
            val gs = gaps.getValue(tick)
            tick in frameTicks && gs.maxOf { it.headDistM } >= caughtM + AFTER_CATCH_M &&
                gs.count { it.headDistM < caughtM - BEHIND_CATCH_M && it.gapS <= MAX_GAP_S } >= 1
        }
        val groups = gaps.getValue(t).filter { it.gapS <= MAX_GAP_S }
        // A gap only needs the head's passage since the largest gap kept: the frames since then (and a margin).
        val from = t - groups.maxOf { it.gapS }.toInt() - MARGIN_S
        val frames = replay.frames.filter { it.t in from..t }
        val c = replay.course
        val coursePoints = c.points.filterIndexed { i, p -> i == 0 || i == c.points.lastIndex || p.distM % 1_000.0 < 10.0 }
        val small = replay.copy(
            course = c.copy(points = coursePoints),
            frames = frames,
            events = replay.events.filter { it.tick in from..t },
        )
        return Fixture(t, groups, small)
    }

    companion object {
        const val FIXTURE = "frontend/src/replay/engine-gaps.fixture.json"
        private const val STEP_S = 5
        private const val AFTER_CATCH_M = 1_000.0
        private const val BEHIND_CATCH_M = 300.0
        private const val BREAK_AWAY_M = 1_000.0
        private const val MAX_GAP_S = 600.0
        private const val MARGIN_S = 30
    }
}
