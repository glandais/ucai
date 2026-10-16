package ucai.runner.calibration

import ucai.api.RaceEvent
import ucai.engine.race.RaceObserver
import ucai.engine.race.RaceView

/**
 * Calibration metrics, observed every tick.
 *
 * The early break: the real time gap ([RaceView.gapAt], the engine's `GroupSummary.gapS`) of the peloton (the biggest
 * group) behind the head of the race. A spell is a stretch of ticks with a group ahead of the peloton; the early
 * break is the spell that started in the first [windowM] with the largest gap. [caughtKm] is where the peloton was
 * when that spell ended (the course distance if it never did); [breakRiderIds] the head group at its max gap.
 * [leftPelotonKm]: per rider, where he was last seen in the peloton.
 *
 * Also: the share of riders on the road in the peloton when its head first reaches half distance, the highest
 * speed of any racing rider, and per rider the best 20-min and 60-min mean power ([massKg] gives W/kg).
 */
class RaceMetrics(private val windowM: Double = 80_000.0, private val massKg: (Int) -> Double = { 70.0 }) : RaceObserver {
    class Spell(val startKm: Double, var maxGapS: Double = 0.0, var maxGapKm: Double = 0.0, var caughtKm: Double? = null) {
        var riderIds: List<Int> = emptyList()
    }

    val spells = mutableListOf<Spell>()
    private var open: Spell? = null
    private var finishKm = 0.0

    /** The early break, or null when no group ever got away in the window. */
    val earlyBreak: Spell? get() = spells.filter { it.startKm * 1000.0 <= windowM }.maxByOrNull { it.maxGapS }
    val maxGapS: Double get() = earlyBreak?.maxGapS ?: 0.0
    val caughtKm: Double get() = earlyBreak?.let { it.caughtKm ?: finishKm } ?: 0.0
    val breakRiderIds: List<Int> get() = earlyBreak?.riderIds.orEmpty()

    val leftPelotonKm = HashMap<Int, Double>()

    /** Riders in the peloton / riders on the road when the peloton's head first reached half distance. */
    var pelotonAtHalf: Int = 0
        private set
    var onRoadAtHalf: Int = 0
        private set
    private var halfDone = false

    /** Highest speed of a racing rider (m/s) and where. */
    var maxSpeed = 0.0
        private set
    var maxSpeedKm = 0.0
        private set

    private class Rolling(val windowS: Int) {
        val buf = DoubleArray(windowS)
        var sum = 0.0
        var n = 0
        var best = 0.0
        fun add(p: Double) {
            val i = n % windowS
            sum += p - buf[i]
            buf[i] = p
            n++
            if (n >= windowS) best = maxOf(best, sum / windowS)
        }
    }

    private val p20 = HashMap<Int, Rolling>()
    private val p60 = HashMap<Int, Rolling>()

    /** Best 20-min / 60-min mean power per rider (W). */
    fun best20W(id: Int): Double = p20[id]?.best ?: 0.0
    fun best60W(id: Int): Double = p60[id]?.best ?: 0.0
    fun best20WKg(id: Int): Double = best20W(id) / massKg(id)
    fun best60WKg(id: Int): Double = best60W(id) / massKg(id)
    val riderIds: Set<Int> get() = p20.keys

    override fun onTick(race: RaceView, events: List<RaceEvent>) {
        finishKm = race.course.distanceM / 1000.0
        for (r in race.riders) {
            if (r.finishTimeS != null || !r.status.onRoad) continue
            p20.getOrPut(r.id) { Rolling(20 * 60) }.add(r.power)
            p60.getOrPut(r.id) { Rolling(60 * 60) }.add(r.power)
            if (r.speed > maxSpeed) {
                maxSpeed = r.speed
                maxSpeedKm = r.distM / 1000.0
            }
        }
        val groups = race.groups
        if (groups.isEmpty()) return
        val pel = groups.maxWith(compareBy<ucai.engine.race.GroupView> { it.size }.thenBy { -it.headDistM })
        if (!halfDone && pel.headDistM >= race.course.distanceM / 2) {
            halfDone = true
            pelotonAtHalf = pel.size
            onRoadAtHalf = race.riders.count { it.finishTimeS == null && it.status.onRoad }
        }
        if (race.riders.none { it.finishTimeS != null }) for (id in pel.riderIds) leftPelotonKm[id] = pel.headDistM / 1000.0
        val head = groups.first()
        val pelKm = pel.headDistM / 1000.0
        if (head === pel || head.headDistM <= pel.headDistM) {
            open?.caughtKm = pelKm
            open = null
            return
        }
        val gap = race.gapAt(pel.headDistM)
        val s = open ?: Spell(pelKm).also { spells += it; open = it }
        // Once the field is in pieces (finale) the biggest group is no longer "the peloton": no gap sample.
        val racing = race.riders.count { it.finishTimeS == null }
        if (pel.size * 3 >= racing && gap > s.maxGapS) {
            s.maxGapS = gap
            s.maxGapKm = pelKm
            s.riderIds = head.riderIds.toList()
        }
    }
}
