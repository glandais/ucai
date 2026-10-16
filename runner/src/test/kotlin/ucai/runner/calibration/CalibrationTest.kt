package ucai.runner.calibration

import ucai.api.ApiJson
import ucai.api.RaceEvent
import ucai.api.Replay
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalibrationTest {
    private val course = Calibration.loadCourse(File("races/courses"), "il-lombardia")

    @Test
    fun scriptedRaceRunsToTheFinish() {
        val run = Calibration.run(course, 1)
        assertTrue(run.rejected.isEmpty(), "rejected: ${run.rejected.take(3)}")
        val results = run.race.results()
        assertTrue(results.all { it.position != null }, "every rider finishes")
        assertTrue(run.race.events.any { it is RaceEvent.Attack }, "strategies attack")
        assertTrue(run.ordersSent > 0)
        assertEquals(results, run.replay.results.entries)

        val dir = createTempDirectory("ucai").toFile()
        val file = File(dir, "r.json.gz")
        Calibration.writeReplay(run.replay, file)
        val back = GZIPInputStream(file.inputStream()).bufferedReader().use { ApiJson.decodeFromString(Replay.serializer(), it.readText()) }
        assertEquals(run.replay.frames.size, back.frames.size)
        assertEquals(run.replay.results, back.results)
        dir.deleteRecursively()
        assertTrue(Calibration.report(run).contains("winner"))
    }

    @Test
    fun sameSeedSameRace() {
        val a = Calibration.run(course, 2).race.results()
        val b = Calibration.run(course, 2).race.results()
        assertEquals(a, b)
    }

    /**
     * The early break gets minutes and is caught late; dropped riders ride home together, not an hour down (DESIGN §8).
     * No formal time cut in the engine (no abandons yet): the bound catches stragglers riding home alone.
     */
    @Test
    fun breakawayAndTimeCut() {
        val failures = mutableListOf<String>()
        for (id in listOf("milano-sanremo", "liege-bastogne-liege", "il-lombardia")) {
            val c = Calibration.loadCourse(File("races/courses"), id)
            for (seed in 1L..3L) {
                val run = Calibration.run(c, seed)
                val b = run.breakaway
                val tag = "$id-$seed"
                if (b.maxGapS < 120.0) failures += "$tag: break max gap ${b.maxGapS} s"
                if (b.caughtKm < 0.7 * c.distanceM / 1000.0) failures += "$tag: break caught at km ${b.caughtKm}"
                if (run.lastGapS > 60 * 60.0) failures += "$tag: last finisher +${run.lastGapS} s"
                if (run.downBy(20) > 14) failures += "$tag: ${run.downBy(20)} riders more than 20 min down"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun finishGroupsByGroupTime() {
        val results = Calibration.run(course, 3).race.results()
        val groups = Calibration.finishGroups(results)
        assertEquals(results.size, groups.sumOf { it.size })
        assertTrue(groups.all { g -> g.map { it.gapS }.toSet().size == 1 })
    }
}
