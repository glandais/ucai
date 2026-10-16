package ucai.runner.calibration

import java.io.File

/**
 * Calibration entry point: `./gradlew :runner:calibrate --args="--courses milano-sanremo --seeds 1,2,3"`.
 *
 * Options: `--courses a,b,c` (default: the 3 monuments), `--seeds 1,2,3` or `--seeds 1..5` (default 1..3),
 * `--courses-dir races/courses`, `--out replays/`.
 */
fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
    val courses = (opts["courses"] ?: "milano-sanremo,liege-bastogne-liege,il-lombardia").split(',').map { it.trim() }
    val seeds = parseSeeds(opts["seeds"] ?: "1..3")
    val coursesDir = File(opts["courses-dir"] ?: "races/courses")
    val out = File(opts["out"] ?: "replays")

    var failed = false
    for (id in courses) {
        val course = Calibration.loadCourse(coursesDir, id)
        for (seed in seeds) {
            val run = Calibration.run(course, seed)
            val file = File(out, "$id-$seed.json.gz")
            Calibration.writeReplay(run.replay, file)
            print(Calibration.report(run))
            println("  replay      $file (${file.length() / 1024} KiB)")
            if (run.rejected.isNotEmpty()) failed = true
        }
    }
    if (failed) error("some orders were rejected")
}

private fun parseSeeds(s: String): List<Long> =
    if (".." in s) s.split("..").let { (a, b) -> (a.trim().toLong()..b.trim().toLong()).toList() }
    else s.split(',').map { it.trim().toLong() }
