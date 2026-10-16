package ucai.runner

import kotlinx.coroutines.runBlocking
import ucai.api.RaceState
import ucai.runner.calibration.Calibration
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Batch determinism (DESIGN §6, §8): a full course run end to end over HTTP (embedded kernel on a free port,
 * 10 bots with real HTTP + SSE), twice on two fresh kernels: same result hash, and the same race as the
 * calibration harness (same strategies and engine, no HTTP).
 */
class BatchRunnerTest {
    private val course = Calibration.loadCourse(File("races/courses"), "il-lombardia")

    private fun runOnce(seed: Long): RaceRun = runBlocking {
        BatchRunner.start(listOf(course), "test-admin-token").use { it.runRace(course.id, seed) }
    }

    @Test
    fun fullRaceOverHttpHasAStableHash() {
        val a = runOnce(7)
        val b = runOnce(7)

        assertEquals(RaceState.FINISHED, a.detail.state)
        assertTrue(a.results.final)
        assertEquals(70, a.results.entries.size)
        assertTrue(a.results.entries.all { it.position != null }, "every rider finishes")
        assertEquals(10, a.bots.size)
        assertTrue(a.bots.all { it.decisions == a.decisions && it.reconnects == 0 && it.strategyErrors == 0 }, "${a.bots}")
        assertEquals(0, a.ordersRejected)
        assertTrue(a.decisions > 100)
        // Admin (1 create, 10 registrations, 1 start), bots (3 GETs and 1 stream each, 1 POST per decision), runner (3 GETs).
        assertEquals(12L + 10 * 4 + 10L * a.decisions + 3, a.httpRequests)
        assertEquals(a.results.entries, a.replay.results.entries)
        assertEquals(null, a.replay.race.seed)

        assertEquals(a.hash, b.hash, "same seed, same result hash")
        assertEquals(a.decisions, b.decisions)
        assertEquals(a.replay.frames, b.replay.frames)
        assertEquals(a.replay.events, b.replay.events)

        val harness = Calibration.run(course, 7)
        assertEquals(resultHash(harness.race.results()), a.hash, "the calibration harness runs the same race")
        assertEquals(harness.decisions, a.decisions)
        assertEquals(harness.replay.events, a.replay.events)
    }
}
