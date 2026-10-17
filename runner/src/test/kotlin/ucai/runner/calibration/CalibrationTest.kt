package ucai.runner.calibration

import ucai.api.ApiJson
import ucai.api.RaceEvent
import ucai.api.Replay
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
     * The realism targets of DESIGN §8 on seeds 1..40 (the tuning set 1..32 and 8 more). Per race, hard sanity
     * limits that no race may break (physics, every rider finishes, the break never caught more than 80 km out); per
     * course, bands that at least [SHARE] of the races must be in, set from the percentiles of the calibrated model
     * with a little margin, so a change that only reshuffles the seeds does not fail but a regression does. Where the
     * model is still short of the target the band is looser than the target and says so (DESIGN §8 "Open").
     */
    @Test
    fun realismTargetsOnSeeds1to40() {
        val failures = mutableListOf<String>()
        fun check(ok: Boolean, msg: () -> String) {
            if (!ok) failures += msg()
        }
        for ((id, ms) in rows) {
            val t = TARGETS.getValue(id)
            val n = ms.size
            for (m in ms) {
                val tag = "$id-${m.seed}"
                check(m.rejected == 0) { "$tag: ${m.rejected} orders rejected" }
                check(m.finished == 70) { "$tag: ${m.finished} of 70 riders finish" }
                check(m.maxKmh <= 90.0) { "$tag: top speed ${m.maxKmh} km/h" }
                check(m.best20WKg <= 6.8) { "$tag: best 20-min power ${m.best20WKg} W/kg" }
                check(m.best60WKg <= 6.0 && m.best60W <= 450.0) { "$tag: best 60-min power ${m.best60W} W, ${m.best60WKg} W/kg" }
                check(m.breakWon || m.caughtToGoKm <= 80.0) { "$tag: break caught ${m.caughtToGoKm} km from the line" }
                check(m.breakWon || m.winnerScore <= 0.5) { "$tag: winner archetype ${m.archetype} does not fit the course" }
                check(m.speedKmh in t.speedSanity) { "$tag: winning speed ${m.speedKmh} km/h, sanity ${t.speedSanity}" }
                check(m.lastGapMin <= 60.0) { "$tag: last finisher +${m.lastGapMin} min" }
                check(m.pelotonAtHalf >= 0.85) { "$tag: ${m.pelotonAtHalf} of the riders in the peloton at half distance" }
            }
            fun share(what: String, ok: (RaceRow) -> Boolean) {
                val bad = ms.filterNot(ok)
                check(n - bad.size >= SHARE * n) { "$id: $what in ${n - bad.size} of $n races (seeds ${bad.map { it.seed }})" }
            }
            share("winning speed in ${t.speedKmh}") { it.speedKmh in t.speedKmh }
            share("break max gap in ${t.breakGapS} s") { it.breakGapS in t.breakGapS }
            share("break caught 5–70 km from the line") { it.breakWon || it.caughtToGoKm in 5.0..70.0 }
            share("top-10 gap in ${t.top10GapS} s") { it.top10GapS in t.top10GapS }
            share("last finisher within ${t.lastFinisherMin} min") { it.lastGapMin <= t.lastFinisherMin }
            share("at most ${t.over20} riders > 20 min") { it.over20 <= t.over20 }
            share("leaders' CP lost in ${t.leaderCpLost} %") { it.leaderCpLost in t.leaderCpLost }
            share("domestiques' CP lost in ${t.domestiqueCpLost} %") { it.domestiqueCpLost in t.domestiqueCpLost }
            val fits = ms.count { it.winnerScore == 0.0 }
            check(fits >= t.minFit * n) { "$id: $fits of $n winners fit the course, expected >= ${t.minFit}" }
            val distinct = ms.map { it.winnerId }.distinct().size
            check(distinct >= 10) { "$id: only $distinct different winners" }
            val breakWins = ms.count { it.breakWon }.toDouble() / n
            val favourite = ms.count { it.favouriteWon }.toDouble() / n
            val selective = ms.count { it.selective }.toDouble() / n
            check(breakWins <= 0.2) { "$id: the break won $breakWins of the races" }
            check(favourite <= 0.5) { "$id: the favourite won $favourite of the races" }
            check(selective in t.selective) { "$id: $selective of the races decided by a small group, expected ${t.selective}" }
            check(ms.map { it.domestiqueCpLost }.average() > ms.map { it.leaderCpLost }.average()) { "$id: domestiques lose less CP than leaders" }
            val score = ms.map { it.score }.average() + Score.courseTerms(id, breakWins, favourite, selective).sumOf { it.penalty }
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
        /** Every race. */
        val speedSanity: ClosedFloatingPointRange<Double>,
        /** The bands below hold for at least [SHARE] of the races. */
        val speedKmh: ClosedFloatingPointRange<Double>,
        val breakGapS: ClosedFloatingPointRange<Double>,
        val top10GapS: ClosedFloatingPointRange<Double>,
        val lastFinisherMin: Double,
        val over20: Int,
        val leaderCpLost: ClosedFloatingPointRange<Double>,
        val domestiqueCpLost: ClosedFloatingPointRange<Double>,
        /** Course: share of the winners whose archetype fits (Score.targets); every winner is at least acceptable. */
        val minFit: Double,
        /** Course: share of the races decided by a small group (Score "selectiveShare"). */
        val selective: ClosedFloatingPointRange<Double>,
        val maxScore: Double,
    )

    companion object {
        private const val COURSES = "milano-sanremo,liege-bastogne-liege,il-lombardia"
        private val SEEDS = 1L..40L
        /** Share of the races that must be inside each band. */
        private const val SHARE = 0.9

        /**
         * Targets (DESIGN §8): Sanremo 40–44 km/h, LBL and Lombardia 39–42; top-10 gaps of seconds to two minutes;
         * leaders lose 5–12 % CP, domestiques more. Looser than the targets where the model is short (DESIGN §8
         * "Open"): Lombardia is ridden at 38–39 km/h; LBL's and Lombardia's last finishers are 15–40 min down (no
         * abandons in v1); Sanremo's domestiques lose 9–10 % CP; the hilly monuments are seldom won solo.
         */
        private val TARGETS = mapOf(
            "milano-sanremo" to Bounds(
                speedSanity = 39.0..45.0, speedKmh = 40.0..44.0, breakGapS = 180.0..600.0, top10GapS = 0.0..30.0,
                lastFinisherMin = 20.0, over20 = 1, leaderCpLost = 6.0..11.0, domestiqueCpLost = 8.0..13.0,
                minFit = 0.85, selective = 0.1..0.6, maxScore = 0.6,
            ),
            "liege-bastogne-liege" to Bounds(
                speedSanity = 38.0..43.0, speedKmh = 39.0..42.0, breakGapS = 180.0..600.0, top10GapS = 0.0..45.0,
                lastFinisherMin = 40.0, over20 = 8, leaderCpLost = 5.0..10.0, domestiqueCpLost = 10.0..16.0,
                minFit = 0.45, selective = 0.15..1.0, maxScore = 6.5,
            ),
            "il-lombardia" to Bounds(
                speedSanity = 37.0..43.0, speedKmh = 38.0..42.0, breakGapS = 180.0..600.0, top10GapS = 5.0..90.0,
                lastFinisherMin = 35.0, over20 = 11, leaderCpLost = 5.0..10.0, domestiqueCpLost = 10.0..16.0,
                minFit = 0.5, selective = 0.2..1.0, maxScore = 6.5,
            ),
        )

        /** The 120 races (3 courses × seeds 1..40), run in parallel: each race is independent and seeded. */
        private val rows: Map<String, List<RaceRow>> by lazy {
            val courses = COURSES.split(',').map { Calibration.loadCourse(File("races/courses"), it) }
            val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
            try {
                val jobs = courses.associate { c ->
                    c.id to SEEDS.map { seed -> pool.submit<RaceRow> { RaceRow.of(Calibration.run(c, seed, record = false)) } }
                }
                jobs.mapValues { (_, fs) -> fs.map { it.get() } }
            } finally {
                pool.shutdown()
            }
        }
    }
}
