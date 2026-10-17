package ucai.runner.calibration

import ucai.api.RaceEvent
import ucai.engine.race.RaceObserver
import ucai.engine.race.RaceView
import java.util.Locale

/**
 * Calibration aid (`--terrain true`): where a rider's time goes. For every rider, time and distance by gradient
 * band of the road under him (climbs ≥ 3 %, rolling, descents ≤ −3 %), and his time on each listed climb, to compare
 * the winner's splits with real race splits.
 */
class TerrainSplits : RaceObserver {
    private val bands = doubleArrayOf(-0.05, -0.03, 0.03, 0.06)
    private val names = listOf("desc<-5", "desc-3..-5", "rolling", "climb3..6", "climb>6")
    private lateinit var time: Array<DoubleArray>
    private lateinit var dist: Array<DoubleArray>
    private lateinit var lastDist: DoubleArray
    private val climbStart = HashMap<Pair<Int, Int>, Double>()
    private val climbTime = HashMap<Pair<Int, Int>, Double>()
    /** Descents after each summit: summit to the first point where the next 500 m drop less than 1 %. */
    private var descents: List<Pair<Double, Double>> = emptyList()
    private val descentStart = HashMap<Pair<Int, Int>, Double>()
    private val descentTime = HashMap<Pair<Int, Int>, Double>()

    private fun findDescents(race: RaceView): List<Pair<Double, Double>> = race.course.climbs.map { c ->
        var end = c.endM
        while (end + 500 < race.course.distanceM &&
            race.course.elevationAt(end + 500) - race.course.elevationAt(end) < -5.0
        ) end += 50.0
        c.endM to end
    }

    private fun band(g: Double): Int {
        var b = 0
        while (b < bands.size && g >= bands[b]) b++
        return b
    }

    override fun onTick(race: RaceView, events: List<RaceEvent>) {
        val riders = race.riders
        if (!::time.isInitialized) {
            time = Array(riders.size) { DoubleArray(names.size) }
            dist = Array(riders.size) { DoubleArray(names.size) }
            lastDist = DoubleArray(riders.size) { riders[it].distM }
            descents = findDescents(race)
            return
        }
        val climbs = race.course.climbs
        for ((i, r) in riders.withIndex()) {
            val d0 = lastDist[i]
            val d1 = r.distM
            lastDist[i] = d1
            if (d1 <= d0) continue
            val b = band(race.course.gradientAt(d0))
            val dt = if (r.finishTimeS != null && r.finishTimeS!! > race.raceTimeS - 1) r.finishTimeS!! - (race.raceTimeS - 1) else 1.0
            time[i][b] += dt
            dist[i][b] += d1 - d0
            val t0 = race.raceTimeS - 1
            for ((k, ds) in descents.withIndex()) {
                val (a, e) = ds
                if (e - a < 1000) continue
                if (d0 < a && d1 >= a) descentStart[r.id to k] = t0 + (a - d0) / (d1 - d0)
                if (d0 < e && d1 >= e) descentStart[r.id to k]?.let { s -> descentTime[r.id to k] = t0 + (e - d0) / (d1 - d0) - s }
            }
            for ((k, c) in climbs.withIndex()) {
                val t = race.raceTimeS
                if (d0 < c.startM && d1 >= c.startM) climbStart[r.id to k] = t - 1 + (c.startM - d0) / (d1 - d0)
                if (d0 < c.endM && d1 >= c.endM) climbStart[r.id to k]?.let { s ->
                    climbTime[r.id to k] = t - 1 + (c.endM - d0) / (d1 - d0) - s
                }
            }
        }
    }

    fun report(race: RaceView, riderId: Int): String {
        val i = race.riders.indexOfFirst { it.id == riderId }
        val sb = StringBuilder("  terrain     #$riderId:")
        for (b in names.indices) {
            val t = time[i][b]
            if (t <= 0) continue
            sb.append(
                " %s %.1f km %s %.1f km/h;".format(Locale.ROOT, names[b], dist[i][b] / 1000, fmt(t), dist[i][b] / t * 3.6),
            )
        }
        sb.appendLine()
        val cl = race.course.climbs.withIndex().mapNotNull { (k, c) ->
            climbTime[riderId to k]?.let { t ->
                val vam = (race.course.elevationAt(c.endM) - race.course.elevationAt(c.startM)) / t * 3600
                "%s %.1f km %.1f%% %s %.1f km/h VAM %.0f".format(
                    Locale.ROOT, c.name.take(14), (c.endM - c.startM) / 1000, c.avgGrade * 100, fmt(t),
                    (c.endM - c.startM) / t * 3.6, vam,
                )
            }
        }
        sb.appendLine("  climbs      " + cl.joinToString(" | "))
        val ds = descents.withIndex().mapNotNull { (k, d) ->
            descentTime[riderId to k]?.let { t ->
                val drop = race.course.elevationAt(d.first) - race.course.elevationAt(d.second)
                "after %s %.1f km −%.0f m %s %.1f km/h".format(
                    Locale.ROOT, race.course.climbs[k].name.take(14), (d.second - d.first) / 1000, drop, fmt(t),
                    (d.second - d.first) / t * 3.6,
                )
            }
        }
        sb.appendLine("  descents    " + ds.joinToString(" | "))
        return sb.toString()
    }

    private fun fmt(s: Double) = "%d:%02d".format(Locale.ROOT, (s / 60).toInt(), (s % 60).toInt())
}
