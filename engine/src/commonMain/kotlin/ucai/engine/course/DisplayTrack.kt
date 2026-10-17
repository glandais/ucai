package ucai.engine.course

import ucai.api.CoursePoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The course geometry for maps: [Course.points] simplified by Douglas-Peucker so no dropped point lies more than
 * [toleranceM] from the line (first and last points kept). Each kept point keeps its own `distM`, so a position
 * interpolated along the track matches the course. Unlike points taken every n, it keeps the shape of hairpins.
 */
fun Course.displayTrack(toleranceM: Double): List<CoursePoint> {
    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.lastIndex] = true
    val stack = ArrayDeque<Pair<Int, Int>>()
    stack.addLast(0 to points.lastIndex)
    while (stack.isNotEmpty()) {
        val (a, b) = stack.removeLast()
        var worst = -1
        var worstM = toleranceM
        for (i in a + 1 until b) {
            val d = distanceToChordM(points[i], points[a], points[b])
            if (d > worstM) {
                worst = i
                worstM = d
            }
        }
        if (worst >= 0) {
            keep[worst] = true
            stack.addLast(a to worst)
            stack.addLast(worst to b)
        }
    }
    return points.filterIndexed { i, _ -> keep[i] }.map { CoursePoint(it.lat, it.lon, it.eleM, it.distM) }
}

/** Distance from [p] to the segment [a]–[b] in meters, on a local flat projection around [a]. */
private fun distanceToChordM(p: TrackPoint, a: TrackPoint, b: TrackPoint): Double {
    val mPerDegLat = EARTH_RADIUS_M * PI / 180.0
    val mPerDegLon = mPerDegLat * cos(a.lat * PI / 180.0)
    val bx = (b.lon - a.lon) * mPerDegLon
    val by = (b.lat - a.lat) * mPerDegLat
    val px = (p.lon - a.lon) * mPerDegLon
    val py = (p.lat - a.lat) * mPerDegLat
    val len2 = bx * bx + by * by
    if (len2 == 0.0) return hypot(px, py)
    val t = ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
    return hypot(px - t * bx, py - t * by)
}
