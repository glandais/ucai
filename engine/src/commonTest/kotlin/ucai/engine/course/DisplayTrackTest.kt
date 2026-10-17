package ucai.engine.course

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayTrackTest {
    private val degLatM = EARTH_RADIUS_M * PI / 180.0
    private val degLonM = degLatM * cos(45.0 * PI / 180.0)

    /** 1 km north, a 15 m hairpin to the east, 1 km south: the shape a point every 50 m cuts. */
    private fun hairpin(): Course {
        val track = mutableListOf<GeoPoint>()
        fun at(xM: Double, yM: Double) = GeoPoint(45.0 + yM / degLatM, 9.0 + xM / degLonM, 0.0)
        for (k in 0..40) track += at(0.0, k * 25.0)
        for (k in 1 until 36) {
            val a = PI - k * PI / 36
            track += at(15.0 + 15.0 * cos(a), 1_000.0 + 15.0 * sin(a))
        }
        for (k in 0..40) track += at(30.0, 1_000.0 - k * 25.0)
        return buildCourse("t", "Test", track)
    }

    /** Largest distance from a course point to [line] (sampled every 0.5 m), in meters. */
    private fun maxGapM(course: Course, line: List<ucai.api.CoursePoint>): Double {
        val samples = line.zipWithNext().flatMap { (a, b) ->
            val n = (haversineM(a.lat, a.lon, b.lat, b.lon) / 0.5).toInt() + 1
            (0..n).map { i -> (a.lat + (b.lat - a.lat) * i / n) to (a.lon + (b.lon - a.lon) * i / n) }
        }
        return course.points.maxOf { p -> samples.minOf { (lat, lon) -> haversineM(p.lat, p.lon, lat, lon) } }
    }

    @Test
    fun keepsTheHairpinAndDropsTheStraights() {
        val course = hairpin()
        val track = course.displayTrack(1.0)
        assertEquals(course.points.first().distM, track.first().distM)
        assertEquals(course.points.last().distM, track.last().distM)
        assertTrue(track.zipWithNext().all { (a, b) -> b.distM > a.distM })
        assertTrue(track.size < 25, "straights kept: ${track.size} points")
        assertTrue(maxGapM(course, track) <= 1.05, "track off the course by ${maxGapM(course, track)} m")
        // One point every 50 m, the public course points, cuts the hairpin.
        val every50 = course.points.filterIndexed { i, _ -> i % 5 == 0 || i == course.points.lastIndex }
            .map { ucai.api.CoursePoint(it.lat, it.lon, it.eleM, it.distM) }
        assertTrue(maxGapM(course, every50) > 3.0)
    }
}
