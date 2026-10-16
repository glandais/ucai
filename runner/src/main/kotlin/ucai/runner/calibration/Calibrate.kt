package ucai.runner.calibration

import java.io.File

/**
 * Calibration entry point: `./gradlew :runner:calibrate --args="--courses milano-sanremo --seeds 1,2,3"`.
 *
 * Options: `--courses a,b,c` (default: the 3 monuments), `--seeds 1,2,3` or `--seeds 1..5` (default 1..3),
 * `--courses-dir races/courses`, `--out replays/`, `--terrain true` (the winner's time by gradient band and on each climb), `--trace true` (a line every `--trace-step` m, default 10 000; `--watch 12,34` adds those riders' state).
 */
fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
    val courses = (opts["courses"] ?: "milano-sanremo,liege-bastogne-liege,il-lombardia").split(',').map { it.trim() }
    val seeds = parseSeeds(opts["seeds"] ?: "1..3")
    val coursesDir = File(opts["courses-dir"] ?: "races/courses")
    val out = File(opts["out"] ?: "replays")

    var failed = false
    val summary = StringBuilder("== Summary\n")
    val scores = LinkedHashMap<String, Double>()
    for (id in courses) {
        val course = Calibration.loadCourse(coursesDir, id)
        val runs = mutableListOf<Calibration.Run>()
        for (seed in seeds) {
            val trace = if (opts["trace"] == "true") {
                val field = Calibration.field(seed)
                val watch = opts["watch"]?.split(',')?.map { it.trim().toInt() }?.toSet().orEmpty()
                Trace((opts["trace-step"] ?: "10000").toDouble(), watch) { rider -> field.rider(rider)!!.profile.cp }
            } else null
            val terrain = if (opts["terrain"] == "true") TerrainSplits() else null
            val observers = listOfNotNull<ucai.engine.race.RaceObserver>(trace, terrain)
            val observer = if (observers.isEmpty()) null
            else ucai.engine.race.RaceObserver { race, events -> observers.forEach { it.onTick(race, events) } }
            val run = Calibration.run(course, seed, observer = observer)
            val file = File(out, "$id-$seed.json.gz")
            Calibration.writeReplay(run.replay, file)
            print(Calibration.report(run))
            trace?.let { print(it) }
            terrain?.let { print(it.report(run.race, run.race.results().first().riderId)) }
            println("  replay      $file (${file.length() / 1024} KiB)")
            if (run.rejected.isNotEmpty()) failed = true
            runs += run
        }
        summary.append(Calibration.summary(runs))
        scores[id] = Score.course(runs)
    }
    print(summary)
    println(
        "== Score (lower is better, DESIGN §8): " +
            scores.entries.joinToString("  ") { "${it.key} %.2f".format(java.util.Locale.ROOT, it.value) } +
            "  total %.2f".format(java.util.Locale.ROOT, scores.values.sum()),
    )
    if (failed) error("some orders were rejected")
}

private fun parseSeeds(s: String): List<Long> =
    if (".." in s) s.split("..").let { (a, b) -> (a.trim().toLong()..b.trim().toLong()).toList() }
    else s.split(',').map { it.trim().toLong() }
