package ucai.runner.calibration

import ucai.api.RaceEvent
import ucai.engine.race.RaceObserver
import ucai.engine.race.RaceView

/**
 * Calibration metric: the early break. Every tick, the time gap (s) of the head of the race over the peloton (the
 * biggest group): how long ago the head passed where the peloton is now. A spell is a stretch of ticks with a group ahead of the peloton; the early break is the spell
 * that started in the first [windowM] with the largest gap. [caughtKm] is where the peloton was when that spell
 * ended (the course distance if it never did). [leftPelotonKm]: per rider, where he was last seen in the peloton.
 */
class RaceMetrics(private val windowM: Double = 80_000.0) : RaceObserver {
    class Spell(val startKm: Double, var maxGapS: Double = 0.0, var maxGapKm: Double = 0.0, var caughtKm: Double? = null)

    val spells = mutableListOf<Spell>()
    private var open: Spell? = null
    private var finishKm = 0.0

    /** The early break, or null when no group ever got away in the window. */
    val earlyBreak: Spell? get() = spells.filter { it.startKm * 1000.0 <= windowM }.maxByOrNull { it.maxGapS }
    val maxGapS: Double get() = earlyBreak?.maxGapS ?: 0.0
    val caughtKm: Double get() = earlyBreak?.let { it.caughtKm ?: finishKm } ?: 0.0

    val leftPelotonKm = HashMap<Int, Double>()

    /** Head of the race per tick (non-decreasing distance), for time gaps: when the head passed a point. */
    private val headDist = ArrayList<Double>()

    private fun headTickAt(distM: Double): Double {
        var lo = 0
        var hi = headDist.lastIndex
        if (hi < 0 || distM >= headDist[hi]) return hi.toDouble().coerceAtLeast(0.0)
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (headDist[mid] < distM) lo = mid else hi = mid
        }
        val span = headDist[hi] - headDist[lo]
        return lo + if (span <= 0.0) 0.0 else (distM - headDist[lo]) / span
    }

    override fun onTick(race: RaceView, events: List<RaceEvent>) {
        finishKm = race.course.distanceM / 1000.0
        val groups = race.groups
        if (groups.isEmpty()) return
        val racingHead = race.riders.filter { it.finishTimeS == null }.maxOfOrNull { it.distM } ?: race.course.distanceM
        while (headDist.size <= race.tick) headDist += maxOf(headDist.lastOrNull() ?: 0.0, racingHead)
        val pel = groups.maxWith(compareBy<ucai.engine.race.GroupView> { it.size }.thenBy { -it.headDistM })
        if (race.riders.none { it.finishTimeS != null }) for (id in pel.riderIds) leftPelotonKm[id] = pel.headDistM / 1000.0
        val head = groups.first()
        val pelKm = pel.headDistM / 1000.0
        if (head === pel || head.headDistM <= pel.headDistM) {
            open?.caughtKm = pelKm
            open = null
            return
        }
        val gap = race.tick - headTickAt(pel.headDistM)
        val s = open ?: Spell(pelKm).also { spells += it; open = it }
        // Once the field is in pieces (finale) the biggest group is no longer "the peloton": no gap sample.
        val racing = race.riders.count { it.finishTimeS == null }
        if (pel.size * 3 >= racing && gap > s.maxGapS) {
            s.maxGapS = gap
            s.maxGapKm = pelKm
        }
    }
}
