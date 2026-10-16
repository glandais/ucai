package ucai.runner.calibration

import ucai.api.RaceEvent
import ucai.engine.race.RaceObserver
import ucai.engine.race.RaceView
import java.util.Locale

/**
 * Calibration aid: one line every [stepM] of the head of the race — race time, head speed over the step, the first
 * groups (size and gap), and the front rider of the biggest group (mode, power, CP fraction). `--trace` in [main].
 */
class Trace(private val stepM: Double = 10_000.0, private val cp: (Int) -> Double) : RaceObserver {
    private val sb = StringBuilder()
    private var nextM = stepM
    private var lastT = 0.0
    private var lastM = 0.0

    private var finaleDone = false

    override fun onTick(race: RaceView, events: List<RaceEvent>) {
        val head = race.riders.filter { it.finishTimeS == null }.maxByOrNull { it.distM } ?: return
        if (!finaleDone && race.course.distanceM - head.distM <= 1_000.0) {
            finaleDone = true
            val byId = race.riders.associateBy { it.id }
            for (g in race.groups.take(3)) {
                sb.appendLine(
                    "  1 km to go: group of ${g.size}: " + g.riderIds.take(15).joinToString(" ") { id ->
                        val r = byId.getValue(id)
                        "#$id ${"%.2f".format(Locale.ROOT, r.wPrimeBal / r.wPrimeEff.coerceAtLeast(1.0))}w ${r.mode.name.take(2).lowercase()}"
                    },
                )
            }
        }
        if (head.distM < nextM) return
        val t = race.raceTimeS
        val kmh = (head.distM - lastM) / (t - lastT).coerceAtLeast(1.0) * 3.6
        lastT = t
        lastM = head.distM
        nextM += stepM
        val byId = race.riders.associateBy { it.id }
        val groups = race.groups.take(5).joinToString(" ") { g ->
            val f = byId.getValue(g.riderIds.first())
            val gap = (head.distM - g.headDistM) / f.speed.coerceAtLeast(1.0)
            "${g.size}@${"%.0f".format(Locale.ROOT, gap)}"
        }
        val pel = race.groups.maxByOrNull { it.size }
        val front = pel?.riderIds?.firstOrNull()?.let { byId.getValue(it) }
        val fr = front?.let {
            "${it.mode.name.lowercase()} ${"%.0f".format(Locale.ROOT, it.power)}W ${"%.2f".format(Locale.ROOT, it.power / cp(it.id))}cp " +
                "${"%.1f".format(Locale.ROOT, it.speed * 3.6)}km/h"
        } ?: ""
        sb.appendLine(
            "  km%5.0f %s %5.1f km/h | %s | pel front %s".format(
                Locale.ROOT, head.distM / 1000, fmt(t), kmh, groups, fr,
            ),
        )
    }

    private fun fmt(s: Double) = "%d:%02d".format(Locale.ROOT, (s / 3600).toInt(), ((s % 3600) / 60).toInt())

    override fun toString() = sb.toString()
}
