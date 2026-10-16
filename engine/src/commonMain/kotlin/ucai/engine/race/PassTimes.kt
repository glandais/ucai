package ucai.engine.race

import kotlin.math.max

/**
 * When the head of the race passed each point of the course, at the course resolution (one entry per
 * [ucai.engine.course.Course.points] point, 10 m), so a time gap is real: [gapAt] is how long ago the head of the race
 * passed a distance, not a distance divided by a speed (which inflates gaps on climbs and shrinks them on descents).
 *
 * The head is the furthest rider ever (finished riders included), so the history is monotone: once the winner has
 * finished, gaps are to the winner, like the results.
 */
class PassTimes(private val pointsM: DoubleArray) {
    private val times = DoubleArray(pointsM.size) { Double.NaN }
    /** Last point index passed by the head (−1 before the start). */
    private var passed = -1

    /** Distance of the head of the race (running max). */
    var headM = 0.0
        private set
    /** Time (s) of the last [advance]. */
    var nowS = 0.0
        private set

    init {
        require(pointsM.isNotEmpty())
        advance(0.0, 0.0)
    }

    /** The head of the race is at [headDistM] at [timeS]: records the points passed since the last call (linear). */
    fun advance(timeS: Double, headDistM: Double) {
        val prevM = headM
        val prevT = nowS
        val h = max(headM, headDistM)
        while (passed + 1 < pointsM.size && pointsM[passed + 1] <= h) {
            val p = pointsM[passed + 1]
            times[passed + 1] = if (h > prevM && timeS > prevT && p > prevM) {
                prevT + (p - prevM) / (h - prevM) * (timeS - prevT)
            } else timeS
            passed++
        }
        headM = h
        nowS = timeS
    }

    /** When the head passed [distM] (s); [nowS] at or beyond the head. */
    fun passTimeAt(distM: Double): Double {
        if (distM >= headM || passed < 0) return nowS
        // Last passed point at or before distM (binary search).
        var i = 0
        var lo = 0
        var hi = passed
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (pointsM[mid] <= distM) { i = mid; lo = mid + 1 } else hi = mid - 1
        }
        val d0 = pointsM[i]
        val t0 = times[i]
        val (d1, t1) = if (i + 1 <= passed) pointsM[i + 1] to times[i + 1] else headM to nowS
        if (d1 <= d0) return t0
        return t0 + (distM - d0).coerceAtLeast(0.0) / (d1 - d0) * (t1 - t0)
    }

    /** Real time gap (s) of a rider at [distM] behind the head of the race, ≥ 0. */
    fun gapAt(distM: Double): Double = max(0.0, nowS - passTimeAt(distM))
}
