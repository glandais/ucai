package ucai.engine.race

import ucai.engine.course.Course
import ucai.engine.course.EARTH_RADIUS_M
import ucai.engine.course.GeoPoint
import ucai.engine.course.buildCourse
import ucai.engine.course.cornerRadii
import ucai.engine.physics.Tuning
import ucai.engine.physics.cornerSpeed
import ucai.engine.physics.descentSpeedCap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeedLimitsTest {
    private val degLatM = EARTH_RADIUS_M * PI / 180.0
    private val degLonM = degLatM * cos(45.0 * PI / 180.0)

    /** 1 km north, a 180° hairpin of [radiusM], 1 km south, all at −7 %. */
    private fun hairpin(radiusM: Double): Course {
        val xy = ArrayList<Pair<Double, Double>>()
        for (k in 0..100) xy += 0.0 to k * 10.0
        val arc = (PI * radiusM / 2.0).toInt().coerceAtLeast(4)
        for (k in 1 until arc) {
            val a = PI * k / arc
            xy += (radiusM - radiusM * cos(a)) to (1_000.0 + radiusM * sin(a))
        }
        for (k in 0..100) xy += 2 * radiusM to 1_000.0 - k * 10.0
        var d = 0.0
        var prev = xy.first()
        val pts = xy.map { (x, y) ->
            d += hypot(x - prev.first, y - prev.second)
            prev = x to y
            GeoPoint(45.0 + y / degLatM, 9.0 + x / degLonM, 1_000.0 - 0.07 * d)
        }
        return buildCourse("hairpin", "hairpin", pts)
    }

    private val apexM = 1_000.0 + PI * 10.0 / 2

    @Test
    fun hairpinRadiusFromHeadings() {
        val c = hairpin(10.0)
        val r = cornerRadii(c)
        val apex = c.segmentAt(apexM)
        val min = r.min()
        println("hairpin R 10 m: measured $min m")
        assertTrue(min in 8.0..12.0, "measured $min")
        assertTrue((apex - 5..apex + 5).any { r[it] == min })
        assertTrue(r[c.segmentAt(300.0)].isInfinite(), "straight")
    }

    @Test
    fun brakingBeforeTheHairpin() {
        val c = hairpin(10.0)
        val lim = SpeedLimits(c)
        val skill = 0.5
        val atApex = lim.at(apexM, skill)
        val straight = descentSpeedCap(skill, -0.07)
        println("hairpin: ${atApex * 3.6} km/h at the apex, ${lim.at(apexM - 30, skill) * 3.6} 30 m before, straight ${straight * 3.6}")
        assertTrue(atApex <= cornerSpeed(10.0 + Tuning.CORNER_LINE_M, skill) * 1.2)
        assertTrue(atApex * 3.6 in 25.0..45.0, "${atApex * 3.6} km/h")
        assertTrue(lim.at(apexM - 30, skill) > atApex && lim.at(apexM - 30, skill) < straight)
        assertEquals(straight, lim.at(200.0, skill), 1e-9)
        // the cap only falls approaching the turn
        val caps = (0..90).map { lim.at(apexM - 900 + it * 10.0, skill) }
        assertEquals(caps.sortedDescending(), caps)
        assertTrue(lim.at(apexM, 1.0) > lim.at(apexM, 0.0), "better descenders corner faster")
    }
}
