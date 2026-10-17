package ucai.runner.calibration

import ucai.api.ApiJson
import ucai.api.RaceEvent
import ucai.api.Replay
import ucai.team.strategy.Role
import java.io.File
import java.util.concurrent.Executors
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
     * The realism targets of DESIGN §8 on seeds 1..8, with bounds at what the calibrated model reaches (a little
     * margin, so a regression fails but noise does not). Where the model is still short of the target the bound is
     * looser than the target and says so (DESIGN §8 "Open").
     */
    @Test
    fun realismTargetsOnSeeds1to8() {
        val failures = mutableListOf<String>()
        fun check(ok: Boolean, msg: () -> String) {
            if (!ok) failures += msg()
        }
        for ((id, ms) in metrics) {
            val t = TARGETS.getValue(id)
            val dist = ms.first().distKm
            for (m in ms) {
                val tag = "$id-${m.seed}"
                check(m.rejected == 0) { "$tag: ${m.rejected} orders rejected" }
                check(m.finished == 70) { "$tag: ${m.finished} of 70 riders finish" }
                check(m.speedKmh in t.speedKmh) { "$tag: winning speed ${m.speedKmh} km/h, expected ${t.speedKmh}" }
                check(m.winnerScore <= 0.5) { "$tag: winner archetype ${m.archetype} does not fit the course" }
                check(m.breakGapS in t.breakGapS) { "$tag: break max gap ${m.breakGapS} s, expected ${t.breakGapS}" }
                check(m.breakWon || m.caughtKm >= t.caughtShare * dist) { "$tag: break caught at km ${m.caughtKm} of $dist" }
                check(m.breakWon || m.caughtKm <= dist - 5.0) { "$tag: break caught at km ${m.caughtKm}, inside the last 5 km" }
                check(m.top10GapS in t.top10GapS) { "$tag: top-10 gap ${m.top10GapS} s, expected ${t.top10GapS}" }
                check(m.lastGapMin <= t.lastFinisherMin) { "$tag: last finisher +${m.lastGapMin} min" }
                check(m.over20 <= t.over20) { "$tag: ${m.over20} riders more than 20 min down" }
                check(m.pelotonAtHalf >= 0.9) { "$tag: ${m.pelotonAtHalf} of the riders in the peloton at half distance" }
                check(m.leaderCpLost in t.leaderCpLost) { "$tag: leaders lose ${m.leaderCpLost} % CP, expected ${t.leaderCpLost}" }
                check(m.domestiqueCpLost in t.domestiqueCpLost) { "$tag: domestiques lose ${m.domestiqueCpLost} % CP, expected ${t.domestiqueCpLost}" }
                check(m.maxKmh <= 90.0) { "$tag: top speed ${m.maxKmh} km/h" }
                check(m.best20WKg <= 6.8) { "$tag: best 20-min power ${m.best20WKg} W/kg" }
                check(m.best60WKg <= 6.0 && m.best60W <= 450.0) { "$tag: best 60-min power ${m.best60W} W, ${m.best60WKg} W/kg" }
            }
            val fits = ms.count { it.winnerScore == 0.0 }
            check(fits >= t.minFit) { "$id: $fits of 8 winners fit the course, expected >= ${t.minFit}" }
            val distinct = ms.map { it.winnerId }.distinct().size
            check(distinct >= 4) { "$id: only $distinct different winners" }
            check(ms.count { it.breakWon } <= 2) { "$id: the break won ${ms.count { it.breakWon }} of 8" }
            check(ms.count { it.favouriteWon } <= 4) { "$id: the favourite won ${ms.count { it.favouriteWon }} of 8" }
            check(ms.map { it.domestiqueCpLost }.average() > ms.map { it.leaderCpLost }.average()) { "$id: domestiques lose less CP than leaders" }
            val score = ms.map { it.score }.average() +
                Score.courseTerms(ms.count { it.breakWon } / 8.0, ms.count { it.favouriteWon } / 8.0).sumOf { it.penalty }
            check(score <= t.maxScore) { "$id: score $score, expected <= ${t.maxScore}" }
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

    private class Bounds(
        val speedKmh: ClosedFloatingPointRange<Double>,
        /** Winners whose archetype fits the course (Score.targets), out of 8; every winner must at least be acceptable. */
        val minFit: Int,
        val breakGapS: ClosedFloatingPointRange<Double>,
        /** The early break is caught after this share of the distance (and before the last 5 km) unless it wins. */
        val caughtShare: Double,
        val top10GapS: ClosedFloatingPointRange<Double>,
        val lastFinisherMin: Double,
        val over20: Int,
        val leaderCpLost: ClosedFloatingPointRange<Double>,
        val domestiqueCpLost: ClosedFloatingPointRange<Double>,
        val maxScore: Double,
    )

    /** What one race of the harness measures (the run itself, with its replay, is too big to keep 24 of). */
    private class Metrics(
        val seed: Long, val distKm: Double, val rejected: Int, val finished: Int, val speedKmh: Double,
        val winnerId: Int, val archetype: String, val winnerScore: Double, val breakGapS: Double, val caughtKm: Double,
        val breakWon: Boolean, val favouriteWon: Boolean, val top10GapS: Double, val lastGapMin: Double, val over20: Int,
        val pelotonAtHalf: Double, val leaderCpLost: Double, val domestiqueCpLost: Double, val maxKmh: Double,
        val best20WKg: Double, val best60WKg: Double, val best60W: Double, val score: Double,
    )

    companion object {
        private const val COURSES = "milano-sanremo,liege-bastogne-liege,il-lombardia"

        /**
         * Targets (DESIGN §8): Sanremo 40–44 km/h, LBL and Lombardia 39–42; top-10 gaps of seconds to two minutes;
         * leaders lose 5–12 % CP, domestiques more. Lombardia's speed bound starts at 38.2: the model rides it at
         * 38.5–38.8 km/h (open gap). LBL's last finishers are 20–45 min down (no abandons in v1), and its top 10 can
         * finish together (0 s) where the score wants at least 5 s.
         */
        private val TARGETS = mapOf(
            "milano-sanremo" to Bounds(
                40.0..44.0, minFit = 7, breakGapS = 180.0..600.0, caughtShare = 0.8, top10GapS = 0.0..30.0,
                lastFinisherMin = 20.0, over20 = 1, leaderCpLost = 6.0..11.0, domestiqueCpLost = 7.0..13.0, maxScore = 0.5,
            ),
            "liege-bastogne-liege" to Bounds(
                39.0..42.0, minFit = 6, breakGapS = 150.0..600.0, caughtShare = 0.65, top10GapS = 0.0..60.0,
                lastFinisherMin = 50.0, over20 = 12, leaderCpLost = 5.0..10.0, domestiqueCpLost = 10.0..16.0, maxScore = 7.0,
            ),
            "il-lombardia" to Bounds(
                38.2..42.0, minFit = 4, breakGapS = 180.0..600.0, caughtShare = 0.7, top10GapS = 5.0..120.0,
                lastFinisherMin = 35.0, over20 = 10, leaderCpLost = 5.0..10.0, domestiqueCpLost = 10.0..16.0, maxScore = 3.5,
            ),
        )

        private fun measure(r: Calibration.Run): Metrics {
            val results = r.race.results()
            val winner = results.first()
            val t = Score.targets(r.course.id)
            val arch = r.field.rider(winner.riderId)!!.archetype
            val m = r.breakaway
            val ids = m.riderIds
            return Metrics(
                seed = r.seed, distKm = r.course.distanceM / 1000.0, rejected = r.rejected.size,
                finished = results.count { it.position != null }, speedKmh = r.course.distanceM / winner.timeS!! * 3.6,
                winnerId = winner.riderId, archetype = arch.name,
                winnerScore = when (arch) {
                    in t.fit -> 0.0
                    in t.acceptable -> 0.5
                    else -> 1.0
                },
                breakGapS = m.maxGapS, caughtKm = m.caughtKm, breakWon = Score.breakWon(r), favouriteWon = Score.favouriteRank(r) == 1,
                top10GapS = results.take(10).maxOf { it.gapS ?: 0.0 }, lastGapMin = r.lastGapS / 60.0, over20 = r.downBy(20),
                pelotonAtHalf = if (m.onRoadAtHalf == 0) 0.0 else m.pelotonAtHalf.toDouble() / m.onRoadAtHalf,
                leaderCpLost = Score.cpLostPct(r, Role.LEADER), domestiqueCpLost = Score.cpLostPct(r, Role.DOMESTIQUE),
                maxKmh = m.maxSpeed * 3.6, best20WKg = ids.maxOf { m.best20WKg(it) }, best60WKg = ids.maxOf { m.best60WKg(it) },
                best60W = ids.maxOf { m.best60W(it) }, score = Score.race(r),
            )
        }

        /** The 24 races (3 courses × seeds 1..8), run in parallel: each race is independent and seeded. */
        private val metrics: Map<String, List<Metrics>> by lazy {
            val courses = COURSES.split(',').map { Calibration.loadCourse(File("races/courses"), it) }
            val pool = Executors.newFixedThreadPool(minOf(4, Runtime.getRuntime().availableProcessors()))
            try {
                val jobs = courses.associate { c -> c.id to (1L..8L).map { seed -> pool.submit<Metrics> { measure(Calibration.run(c, seed)) } } }
                jobs.mapValues { (_, fs) -> fs.map { it.get() } }
            } finally {
                pool.shutdown()
            }
        }
    }
}
