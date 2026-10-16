package ucai.engine.course

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min

/**
 * Heading change windows (m) used to measure the radius of a turn: the short one sees hairpins, the long ones bends.
 * Nothing shorter than 40 m: course tracks are polylines (OSM nodes every 25–100 m) resampled every 10 m, so a 20 m
 * window turns every node of a smooth bend into a kink of a few metres' radius.
 */
val CORNER_WINDOWS_M = doubleArrayOf(40.0, 80.0, 160.0)

/** Straight road: no radius (the heading changes by less than this over a window, degrees). */
private const val STRAIGHT_DEG = 2.0

/** Smallest angle between two headings, degrees in [0, 180]. */
fun headingChangeDeg(a: Double, b: Double): Double {
    val d = abs(b - a) % 360.0
    return if (d > 180.0) 360.0 - d else d
}

/**
 * Turn radius (m) at each course point from the heading changes of the segments around it: for each window
 * of [CORNER_WINDOWS_M], radius = distance between the midpoints of the segment that starts the window and the one
 * that ends it ÷ their net heading change (radians), centred on the point; the smallest one is kept. A 180° hairpin
 * of 10 m radius measures ≈ 10 m. Net changes cancel GPS zigzags on longer windows. Infinite on straights.
 */
fun cornerRadii(course: Course): DoubleArray {
    val p = course.points
    val n = p.size
    val r = DoubleArray(n) { Double.POSITIVE_INFINITY }
    for (w in CORNER_WINDOWS_M) {
        val k = (w / COURSE_STEP_M / 2).toInt().coerceAtLeast(1)
        for (i in k until n - k) {
            // segment i-k.. starts the window, segment i+k-1 ends it (headings describe the segment from a point)
            val deg = headingChangeDeg(p[i - k].headingDeg, p[min(n - 2, i + k - 1)].headingDeg)
            if (deg < STRAIGHT_DEG) continue
            // between the midpoints of the two segments
            val len = (p[i + k].distM + p[i + k - 1].distM - p[i - k + 1].distM - p[i - k].distM) / 2.0
            r[i] = min(r[i], len / (deg * PI / 180.0))
        }
    }
    return r
}
