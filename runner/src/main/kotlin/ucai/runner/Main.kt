package ucai.runner

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import ucai.api.ApiJson
import ucai.api.ApiPaths
import ucai.api.Classification
import ucai.api.RaceEvent
import ucai.engine.course.Course
import ucai.runner.calibration.Calibration
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.system.exitProcess

/**
 * Batch race runner (DESIGN §6):
 *
 * ```
 * ./gradlew :runner:run --args="--courses milano-sanremo,liege-bastogne-liege,il-lombardia --seed 42 --out replays/"
 * ```
 *
 * Options: `--courses a,b,c` (default: the 3 monuments), `--seed 42`, `--out replays/`, `--courses-dir races/courses`,
 * `--compare` (also run the calibration harness on the same course and seed and compare result hashes), `--serve`
 * (keep the kernel up afterwards, on `--port`, default 8080 with `--serve`, else a free port; `--host`, default
 * 127.0.0.1). Admin token from `UCAI_ADMIN_TOKEN`, random when unset.
 *
 * Writes `<out>/<course>-<seed>.json.gz` (the replay as served), `<out>/<course>-<seed>.results.json` (the
 * classification) and `<out>/results.json` (one summary line per race with its hash).
 */
fun main(args: Array<String>) {
    val opts = parseArgs(args)
    val courseIds = (opts["courses"] ?: "milano-sanremo,liege-bastogne-liege,il-lombardia").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val seed = (opts["seed"] ?: "42").toLong()
    val out = File(opts["out"] ?: "replays")
    val serve = opts["serve"] == "true"
    val compare = opts["compare"] == "true"
    val port = (opts["port"] ?: if (serve) "8080" else "0").toInt()
    val host = opts["host"] ?: "127.0.0.1"
    val coursesDir = File(opts["courses-dir"] ?: "races/courses")
    val token = System.getenv(ApiPaths.ADMIN_TOKEN_ENV)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

    val courses = coursesDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        .map { ApiJson.decodeFromString(Course.serializer(), it.readText()) }
    val unknown = courseIds - courses.map { it.id }.toSet()
    if (unknown.isNotEmpty()) {
        System.err.println("unknown courses $unknown in ${coursesDir.path} (have ${courses.map { it.id }})")
        exitProcess(2)
    }

    val failed = runBlocking {
        val runner = BatchRunner.start(courses, token, port, host, wallClock = { Instant.now().toString() })
        println("kernel on ${runner.baseUrl}")
        val runs = mutableListOf<RaceRun>()
        var mismatch = false
        try {
            for (id in courseIds) {
                val run = runner.runRace(id, seed)
                runs += run
                val files = write(run, out)
                print(report(run, courses.first { it.id == id }))
                println("  files       ${files.joinToString { "${it.path} (${it.length() / 1024} KiB)" }}")
                if (compare) {
                    val cal = Calibration.run(courses.first { it.id == id }, seed)
                    val h = resultHash(cal.race.results())
                    val same = h == run.hash
                    if (!same) mismatch = true
                    println("  harness     ${h.take(16)} ${if (same) "identical to the HTTP run" else "DIFFERENT from the HTTP run"}")
                }
            }
        } catch (e: BatchException) {
            System.err.println("FAILED: ${e.message}")
            runner.close()
            return@runBlocking true
        }
        writeSummary(runs, out)
        println("== Batch: ${runs.size} races, seed $seed, ${runs.sumOf { it.wallMillis }} ms")
        runs.forEach { println("  ${it.courseId.padEnd(22)} ${it.hash}") }
        if (serve) {
            println("serving ${runner.baseUrl}${ApiPaths.BASE} (races ${runs.joinToString { it.raceId }}), Ctrl-C to stop")
            awaitCancellation()
        }
        runner.close()
        mismatch
    }
    if (failed) exitProcess(1)
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val opts = LinkedHashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val k = args[i].removePrefix("--")
        val v = args.getOrNull(i + 1)
        if (v == null || v.startsWith("--")) {
            opts[k] = "true"
            i++
        } else {
            opts[k] = v
            i += 2
        }
    }
    return opts
}

@Serializable
private data class RaceSummaryLine(
    val courseId: String,
    val seed: Long,
    val raceId: String,
    val winnerRiderId: Int,
    val winnerName: String,
    val winnerTeam: String,
    val timeS: Double,
    val avgKmh: Double,
    val decisions: Int,
    val httpRequests: Long,
    val wallMillis: Long,
    val hash: String,
)

private fun write(run: RaceRun, out: File): List<File> {
    out.mkdirs()
    val replay = File(out, "${run.courseId}-${run.seed}.json.gz").apply { writeBytes(run.replayGzip) }
    val results = File(out, "${run.courseId}-${run.seed}.results.json")
        .apply { writeText(ApiJson.encodeToString(Classification.serializer(), run.results)) }
    return listOf(replay, results)
}

private fun writeSummary(runs: List<RaceRun>, out: File) {
    val lines = runs.map { r ->
        val w = r.results.entries.first()
        val rider = r.detail.riders.first { it.id == w.riderId }
        RaceSummaryLine(
            r.courseId, r.seed, r.raceId, w.riderId, rider.name, rider.teamId, w.timeS!!,
            r.replay.course.distanceM / w.timeS!! * 3.6, r.decisions, r.httpRequests, r.wallMillis, r.hash,
        )
    }
    File(out, "results.json").writeText(ApiJson.encodeToString(ListSerializer(RaceSummaryLine.serializer()), lines))
}

private fun fmtTime(s: Double) = "%d:%02d:%02d".format(Locale.ROOT, (s / 3600).toInt(), ((s % 3600) / 60).toInt(), (s % 60).toInt())

/** Top 10, key events, run figures and the hash of one race. */
fun report(run: RaceRun, course: Course): String {
    val riders = run.detail.riders.associateBy { it.id }
    val teams = run.detail.teams.associateBy { it.id }
    fun name(id: Int) = riders[id]?.let { "${it.name} (${teams[it.teamId]?.name ?: it.teamId})" } ?: "#$id"
    fun km(m: Double) = "%.0f".format(Locale.ROOT, m / 1000)
    val entries = run.results.entries
    val w = entries.first()
    val speed = course.distanceM / w.timeS!! * 3.6
    val events = run.replay.events
    val sb = StringBuilder()
    sb.appendLine("== ${course.name}, seed ${run.seed} (${run.raceId})")
    sb.appendLine(
        "  winner      ${name(w.riderId)}, ${riders[w.riderId]?.archetype?.name?.lowercase()}, " +
            "${fmtTime(w.timeS!!)}, avg ${"%.2f".format(Locale.ROOT, speed)} km/h",
    )
    sb.appendLine("  top 10")
    for (e in entries.take(10)) {
        val gap = if ((e.gapS ?: 0.0) == 0.0) "s.t." else "+${"%.0f".format(Locale.ROOT, e.gapS)} s"
        sb.appendLine("    ${e.position.toString().padStart(2)}. ${name(e.riderId).padEnd(44)} ${riders[e.riderId]?.archetype?.name?.lowercase()?.padEnd(11)} $gap")
    }
    val attacks = events.filterIsInstance<RaceEvent.Attack>()
    sb.appendLine(
        "  events      ${attacks.size} attacks, ${events.count { it is RaceEvent.GroupSplit }} splits, " +
            "${events.count { it is RaceEvent.GroupMerge }} merges, ${events.count { it is RaceEvent.RiderDropped }} dropped",
    )
    // Key moments: summits of the named climbs, attacks of the last 60 km, and the finish.
    val finale = course.distanceM - 60_000
    val key = events.filter {
        it is RaceEvent.ClimbSummit || (it is RaceEvent.Attack && it.distM >= finale) || it is RaceEvent.RaceFinished
    }
    for (e in key) {
        val line = when (e) {
            is RaceEvent.ClimbSummit -> "km ${km(e.distM)} ${e.climbName}: first ${e.riderIds.firstOrNull()?.let(::name) ?: "-"}"
            is RaceEvent.Attack -> "km ${km(e.distM)} attack by ${name(e.riderId)}"
            is RaceEvent.RaceFinished -> "${fmtTime(e.timeS)} won by ${name(e.winnerRiderId)}"
            else -> continue
        }
        sb.appendLine("    $line")
    }
    sb.appendLine(
        "  run         ${run.wallMillis} ms, ${run.detail.tick} ticks, ${run.decisions} decisions, ${run.ordersSent} orders " +
            "(${run.ordersRejected} rejected), ${run.httpRequests} HTTP requests, ${run.replay.frames.size} frames",
    )
    sb.appendLine("  hash        ${run.hash}")
    return sb.toString()
}
