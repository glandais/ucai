package ucai.engine.race

import ucai.engine.course.Course
import ucai.engine.course.cornerRadii
import ucai.engine.physics.PhysicsConstants
import ucai.engine.physics.Tuning
import ucai.engine.physics.cornerGrip
import ucai.engine.physics.cornerSpeed
import ucai.engine.physics.descentSpeedCap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Max speed (m/s) along a course for a rider's descending skill (DESIGN §3 "Descents and turns"): the straight
 * descent cap ([descentSpeedCap]), the turns ([cornerSpeed] of the radius measured from the course headings,
 * [cornerRadii]) and the braking before them: a rider brakes at [Tuning.BRAKE_GRIP_SHARE] of his grip, less the
 * slope's pull downhill, so the cap a few hundred metres before a hairpin already falls towards the hairpin speed.
 *
 * Profiles are computed once per skill bucket (0.05) and interpolated between the two nearest buckets.
 */
class SpeedLimits(private val course: Course, private val cornersEverywhere: Boolean = Tuning.CORNERS_EVERYWHERE) {
    private val radii = cornerRadii(course)
    private val profiles = arrayOfNulls<DoubleArray>(BUCKETS + 1)

    private fun profile(bucket: Int): DoubleArray = profiles[bucket] ?: build(bucket.toDouble() / BUCKETS).also {
        profiles[bucket] = it
    }

    private fun build(skill: Double): DoubleArray {
        val p = course.points
        val n = p.size
        val v = DoubleArray(n) { i ->
            val g = p[i].gradient
            val corner = if (cornersEverywhere || g <= DESCENT_GRADIENT) cornerSpeed(radii[i] + Tuning.CORNER_LINE_M, skill) else Double.POSITIVE_INFINITY
            min(descentSpeedCap(skill, g), corner)
        }
        val brakeGrip = Tuning.BRAKE_GRIP_SHARE * cornerGrip(skill)
        for (i in n - 2 downTo 0) {
            if (v[i + 1].isInfinite()) continue
            val a = max(MIN_BRAKE, PhysicsConstants.G * (brakeGrip + min(0.0, p[i].gradient)))
            val d = p[i + 1].distM - p[i].distM
            v[i] = min(v[i], sqrt(v[i + 1] * v[i + 1] + 2.0 * a * d))
        }
        return v
    }

    /** Max speed (m/s) at [distM] for a rider of [descending] skill; infinite where nothing limits it. */
    fun at(distM: Double, descending: Double): Double {
        val i = course.segmentAt(distM)
        val x = descending.coerceIn(0.0, 1.0) * BUCKETS
        val lo = x.toInt().coerceAtMost(BUCKETS)
        val hi = min(BUCKETS, lo + 1)
        val a = profile(lo)[i]
        if (lo == hi || a.isInfinite()) return a
        val b = profile(hi)[i]
        if (b.isInfinite()) return a
        return a + (b - a) * (x - lo)
    }

    private companion object {
        const val BUCKETS = 20
        const val DESCENT_GRADIENT = -0.03
        /** Braking deceleration never below this (m/s²), even on the steepest descent. */
        const val MIN_BRAKE = 1.5
    }
}
