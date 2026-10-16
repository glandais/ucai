package ucai.importer

import java.io.File
import java.util.Locale

/**
 * GPX/KML -> races/courses/<id>.json.
 * Args: `[rawDir] [outDir]` (defaults `races/raw`, `races/courses`, relative to the repo root),
 * or `--profile <id> [stepKm]` to print an elevation profile instead (used to place hand-written climbs).
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--profile") {
        val id = args[1]
        val step = args.getOrNull(2)?.toDouble() ?: 1.0
        val c = importAll(File("races/raw")).single { it.course.id == id }.course
        var km = 0.0
        while (km * 1000 <= c.distanceM) {
            println("%7.1f  %6.0f m  %6.1f %%".format(Locale.ROOT, km, c.elevationAt(km * 1000), c.gradientAt(km * 1000) * 100))
            km += step
        }
        return
    }
    val rawDir = File(args.getOrNull(0) ?: "races/raw")
    val outDir = File(args.getOrNull(1) ?: "races/courses")
    for (imp in importAll(rawDir)) {
        val c = imp.course
        val file = writeCourse(outDir, c)
        println("== ${c.name} -> $file (${file.length() / 1024} KiB)")
        println("   distance %.2f km, elevation gain %.0f m, %d points".format(Locale.ROOT, c.distanceM / 1000, c.elevationGainM, c.points.size))
        println("   km 0: ${imp.cutReason}")
        if (imp.tunnels.isNotEmpty()) {
            println("   tunnels bridged: " + imp.tunnels.joinToString { "km ${fmt1(it.start / 1000)}-${fmt1(it.endInclusive / 1000)}" })
        }
        for (cl in c.climbs) {
            println(
                "   %-45s km %6.1f -> %6.1f  %4.1f km  avg %4.1f %%  max %4.1f %%".format(Locale.ROOT, 
                    cl.name, cl.startM / 1000, cl.endM / 1000, (cl.endM - cl.startM) / 1000, cl.avgGrade * 100, cl.maxGrade * 100,
                ),
            )
        }
    }
}
