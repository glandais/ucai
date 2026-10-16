package ucai.engine.course

import ucai.api.ApiJson
import ucai.api.CourseDetail
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CourseTest {
    // 1 degree of latitude on the 6371 km sphere.
    private val degLatM = EARTH_RADIUS_M * kotlin.math.PI / 180.0

    /** A straight north-bound track of [lengthM] whose elevation follows [ele] (of distance). */
    private fun northTrack(lengthM: Double, stepM: Double = 37.0, ele: (Double) -> Double): List<GeoPoint> {
        val n = (lengthM / stepM).toInt()
        return (0..n).map { k ->
            val d = if (k == n) lengthM else k * stepM
            GeoPoint(45.0 + d / degLatM, 9.0, ele(d))
        }
    }

    private fun assertNear(expected: Double, actual: Double, eps: Double, msg: String = "") =
        assertTrue(abs(expected - actual) <= eps, "$msg expected $expected ± $eps, got $actual")

    @Test
    fun haversineKnownDistances() {
        assertNear(degLatM, haversineM(45.0, 9.0, 46.0, 9.0), 1e-6)
        // Paris -> London, ~ 343.5 km
        assertNear(343_500.0, haversineM(48.8566, 2.3522, 51.5074, -0.1278), 1_000.0)
        assertEquals(0.0, haversineM(45.0, 9.0, 45.0, 9.0))
    }

    @Test
    fun bearings() {
        assertNear(0.0, bearingDeg(45.0, 9.0, 45.1, 9.0), 1e-9)
        assertNear(90.0, bearingDeg(0.0, 9.0, 0.0, 9.1), 1e-9)
        assertNear(180.0, bearingDeg(45.1, 9.0, 45.0, 9.0), 1e-9)
        assertNear(270.0, bearingDeg(0.0, 9.1, 0.0, 9.0), 1e-9)
    }

    @Test
    fun resampleEvery10m() {
        val track = northTrack(1_234.0) { 100.0 }
        val (pts, dist) = resample(track)
        assertEquals(125, pts.size) // 0..1230 plus the 1234 m end
        assertEquals(0.0, dist[0])
        assertEquals(1230.0, dist[123])
        assertNear(1234.0, dist.last(), 1e-6)
        assertNear(500.0, haversineM(pts[0], pts[50]), 1e-3)
    }

    @Test
    fun resampleReplacesTinyRemainder() {
        val (_, dist) = resample(northTrack(1_000.4) { 0.0 })
        assertEquals(101, dist.size)
        assertNear(1000.4, dist.last(), 1e-6)
    }

    @Test
    fun cutStartKeepsTheRest() {
        val track = northTrack(5_000.0) { it }
        val cut = cutStart(track, 1_200.0)
        assertNear(3_800.0, cumulativeDistances(cut).last(), 1e-3)
        assertNear(1_200.0, cut.first().eleM, 1e-6)
    }

    @Test
    fun smoothingRemovesSpikes() {
        val ele = DoubleArray(101) { if (it == 50) 130.0 else 100.0 }
        val s = smoothElevation(ele, 11)
        assertNear(100.0 + 30.0 / 11, s[50], 1e-9)
        assertEquals(100.0, s[0])
        // a constant slope stays a constant slope away from the edges
        val slope = smoothElevation(DoubleArray(100) { it * 0.5 }, 11)
        assertNear(25.0, slope[50], 1e-9)
    }

    @Test
    fun gradientsAreClamped() {
        val g = gradients(doubleArrayOf(0.0, 1.0, 5.0, 2.0, -2.0), doubleArrayOf(0.0, 10.0, 20.0, 30.0, 40.0))
        assertEquals(listOf(0.1, 0.25, -0.25, -0.25, -0.25), g.toList())
    }

    @Test
    fun tunnelsAreBridgedButSteepHillsKept() {
        // flat at 200 m with a DEM jump to 370 m over a 1 km tunnel (from 1000 to 2000 m)
        val tunnel = DoubleArray(400) { i -> if (i in 100..199) 370.0 else 200.0 }
        val bridged = bridgeTunnels(tunnel, 10.0)
        assertEquals(1, bridged.size)
        assertTrue(tunnel.all { abs(it - 200.0) < 1e-9 })
        // 1 km up at 12 % then down at 12 %: steep but real
        val hill = DoubleArray(400) { i -> if (i <= 200) i * 1.2 else (400 - i) * 1.2 }
        val copy = hill.copyOf()
        assertTrue(bridgeTunnels(hill, 10.0).isEmpty())
        assertEquals(copy.toList(), hill.toList())
    }

    @Test
    fun elevationGainSumsRises() {
        assertEquals(15.0, elevationGain(doubleArrayOf(0.0, 10.0, 5.0, 10.0, 8.0)))
    }

    @Test
    fun buildAndLookup() {
        // 2 km flat, 3 km at 6 %, 2 km flat
        val course = buildCourse("t", "Test", northTrack(7_000.0) { d ->
            when {
                d < 2_000 -> 0.0
                d < 5_000 -> (d - 2_000) * 0.06
                else -> 180.0
            }
        })
        assertNear(7_000.0, course.distanceM, 0.5)
        assertNear(180.0, course.elevationGainM, 1.0)
        assertEquals(350, course.segmentAt(3_500.0))
        assertEquals(course.points.size - 2, course.segmentAt(1e9))
        assertEquals(0, course.segmentAt(-5.0))
        assertNear(0.06, course.gradientAt(3_500.0), 1e-3)
        assertNear(0.0, course.gradientAt(1_000.0), 1e-9)
        assertNear(90.0, course.elevationAt(3_500.0), 0.1)
        assertNear(90.3, course.elevationAt(3_505.0), 0.1)
        assertNear(0.0, course.headingAt(4_000.0), 0.1)
        course.points.zipWithNext().dropLast(1).forEach { (a, b) -> assertNear(10.0, b.distM - a.distM, 1e-6) }
    }

    @Test
    fun climbsFromProfile() {
        // flat, 1 km at 3 %, 1 km down at 3 %, 2 km at 8 % to the summit at 5 km, then descent
        val course = buildCourse("t", "Test", northTrack(8_000.0) { d ->
            when {
                d < 1_000 -> 0.0
                d < 2_000 -> (d - 1_000) * 0.03
                d < 3_000 -> 30.0 - (d - 2_000) * 0.03
                d < 5_000 -> (d - 3_000) * 0.08
                else -> 160.0 - (d - 5_000) * 0.05
            }
        })
        val c = course.deriveClimb("Test hill", nearM = 4_600.0)
        assertNear(5_000.0, c.endM, 60.0, "summit")
        assertNear(3_000.0, c.startM, 60.0, "foot stops at the dip")
        assertNear(0.08, c.avgGrade, 0.005)
        assertTrue(c.maxGrade >= c.avgGrade)
        val first = course.deriveClimb("First", nearM = 2_000.0, searchM = 300.0)
        assertNear(1_000.0, first.startM, 60.0, "flat run-in trimmed")
        assertNear(0.03, first.avgGrade, 0.004)
        val explicit = course.climbBetween("Whole", 3_000.0, 5_000.0)
        assertNear(0.08, explicit.avgGrade, 0.003)
    }

    @Test
    fun jsonRoundTripAndApiCompatibility() {
        val course = buildCourse("t", "Test", northTrack(500.0) { it * 0.01 }, "A", "B")
        val json = ApiJson.encodeToString(Course.serializer(), course)
        assertTrue(json.contains("\"points\":[[45.0,9.0,"), json.take(200))
        assertEquals(course, ApiJson.decodeFromString(Course.serializer(), json))
        val detail = ApiJson.decodeFromString(CourseDetail.serializer(), json)
        assertEquals(course.points.size, detail.points.size)
        assertEquals(course.points[7].eleM, detail.points[7].eleM)
    }

    @Test
    fun spikeFilterSpreadsSteepStepsAndKeepsTotal() {
        val ele = doubleArrayOf(100.0, 100.0, 92.0, 90.0, 90.0, 90.0, 90.0)
        limitSteps(ele, 2.0)
        for (i in 1 until ele.size) assertTrue(abs(ele[i] - ele[i - 1]) <= 2.0 + 1e-9, ele.toList().toString())
        assertEquals(90.0, ele.last(), 1e-9)
        assertEquals(listOf(100.0, 100.0, 98.0, 96.0, 94.0, 92.0, 90.0), ele.toList())
    }
}
