package ucai.runner.calibration

import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Calibration entry point: `./gradlew :runner:calibrate --args="--courses milano-sanremo --seeds 1,2,3"`.
 *
 * Options: `--courses a,b,c` (default: the 3 monuments), `--seeds 1,2,3` or `--seeds 1..5` (default 1..3),
 * `--courses-dir races/courses`, `--out replays/` (`--out none`: no replay files), `--threads n` (races run in
 * parallel, default: the processors; each race is independent and seeded, so the output does not depend on it),
 * `--reports false` (summaries only), `--csv file` (one line of metrics per race, [RaceRow]), `--terrain true` (the winner's time by gradient band and on each climb),
 * `--trace true` (a line every `--trace-step` m, default 10 000; `--watch 12,34` adds those riders' state, `--trace-head 15` the riders of the first 2 groups in the last 15 km).
 */
fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
    val courses = (opts["courses"] ?: "milano-sanremo,liege-bastogne-liege,il-lombardia").split(',').map { it.trim() }
    val seeds = parseSeeds(opts["seeds"] ?: "1..3")
    val coursesDir = File(opts["courses-dir"] ?: "races/courses")
    val out = (opts["out"] ?: "replays").takeIf { it != "none" }?.let(::File)
    val reports = opts["reports"] != "false"
    val threads = opts["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
    val csv = opts["csv"]?.let(::File)?.also { it.absoluteFile.parentFile?.mkdirs(); it.writeText(RaceRow.CSV_HEADER + "\n") }

    var failed = false
    val summary = StringBuilder("== Summary\n")
    val scores = LinkedHashMap<String, Double>()
    val pool = Executors.newFixedThreadPool(threads.coerceAtLeast(1))
    try {
        for (id in courses) {
            val course = Calibration.loadCourse(coursesDir, id)
            val jobs = seeds.map { seed ->
                pool.submit<Pair<Calibration.Run, String>> {
                    val trace = if (opts["trace"] == "true") {
                        val field = Calibration.field(seed)
                        val watch = opts["watch"]?.split(',')?.map { it.trim().toInt() }?.toSet().orEmpty()
                        Trace((opts["trace-step"] ?: "10000").toDouble(), watch, (opts["trace-head"] ?: "0").toDouble() * 1000) { rider -> field.rider(rider)!!.profile.cp }
                    } else null
                    val terrain = if (opts["terrain"] == "true") TerrainSplits() else null
                    val observers = listOfNotNull<ucai.engine.race.RaceObserver>(trace, terrain)
                    val observer = if (observers.isEmpty()) null
                    else ucai.engine.race.RaceObserver { race, events -> observers.forEach { it.onTick(race, events) } }
                    val run = Calibration.run(course, seed, observer = observer, record = out != null)
                    val text = StringBuilder()
                    if (reports) {
                        text.append(Calibration.report(run))
                        trace?.let { text.append(it) }
                        terrain?.let { text.append(it.report(run.race, run.race.results().first().riderId)) }
                    }
                    if (out != null) {
                        val file = File(out, "$id-$seed.json.gz")
                        Calibration.writeReplay(run.replay, file)
                        if (reports) text.appendLine("  replay      $file (${file.length() / 1024} KiB)")
                    }
                    // Keep the summary's data, not the replay (a long seed list would not fit in memory).
                    Calibration.Run(
                        run.course, run.seed, run.field, run.race, null, run.strategies, run.rejected, run.decisions,
                        run.ordersSent, run.simMillis, run.breakaway,
                    ) to text.toString()
                }
            }
            val runs = jobs.map { it.get() }.map { (run, text) ->
                print(text)
                if (run.rejected.isNotEmpty()) failed = true
                csv?.appendText(RaceRow.of(run).csv() + "\n")
                run
            }
            summary.append(Calibration.summary(runs))
            scores[id] = Score.course(runs)
        }
    } finally {
        pool.shutdown()
    }
    print(summary)
    println(
        "== Score (lower is better, DESIGN §8): " +
            scores.entries.joinToString("  ") { "${it.key} %.2f".format(Locale.ROOT, it.value) } +
            "  total %.2f".format(Locale.ROOT, scores.values.sum()),
    )
    if (failed) error("some orders were rejected")
}

private fun parseSeeds(s: String): List<Long> =
    if (".." in s) s.split("..").let { (a, b) -> (a.trim().toLong()..b.trim().toLong()).toList() }
    else s.split(',').map { it.trim().toLong() }
