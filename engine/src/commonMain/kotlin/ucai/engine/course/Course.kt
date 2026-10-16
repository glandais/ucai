package ucai.engine.course

import kotlinx.serialization.Serializable
import ucai.api.Climb
import ucai.api.CompactArraySerializer
import ucai.api.CourseDetail
import ucai.api.CoursePoint
import ucai.api.CourseSummary

/** Spacing of [Course.points]; only the last point may be closer to its predecessor. */
const val COURSE_STEP_M = 10.0

/**
 * A course resampled every [COURSE_STEP_M], cut at km 0. This is the format of `races/courses/<id>.json`.
 * Points serialize as `[lat, lon, eleM, distM, gradient, headingDeg]`, so the file also decodes as [CourseDetail]
 * (which ignores the trailing elements).
 */
@Serializable
data class Course(
    val id: String,
    val name: String,
    val distanceM: Double,
    val elevationGainM: Double,
    val startName: String? = null,
    val finishName: String? = null,
    val points: List<TrackPoint>,
    val climbs: List<Climb> = emptyList(),
) {
    init {
        require(points.size >= 2) { "course $id needs at least 2 points" }
    }

    /** Index of the segment `points[i] -> points[i + 1]` containing [distM], clamped to the course. O(1). */
    fun segmentAt(distM: Double): Int = (distM / COURSE_STEP_M).toInt().coerceIn(0, points.size - 2)

    /** Gradient (fraction) of the segment at [distM]. */
    fun gradientAt(distM: Double): Double = points[segmentAt(distM)].gradient

    /** Heading (degrees, 0 = north) of the segment at [distM]. */
    fun headingAt(distM: Double): Double = points[segmentAt(distM)].headingDeg

    /** Elevation at [distM], linearly interpolated. */
    fun elevationAt(distM: Double): Double {
        val a = points[segmentAt(distM)]
        val b = points[segmentAt(distM) + 1]
        val t = ((distM - a.distM) / (b.distM - a.distM)).coerceIn(0.0, 1.0)
        return a.eleM + (b.eleM - a.eleM) * t
    }

    fun summary() = CourseSummary(id, name, distanceM, elevationGainM, startName, finishName)

    fun toDetail() = CourseDetail(
        id, name, distanceM, elevationGainM, startName, finishName,
        points.map { CoursePoint(it.lat, it.lon, it.eleM, it.distM) }, climbs,
    )
}

/** [gradient] (fraction) and [headingDeg] describe the segment that starts at this point. */
@Serializable(with = TrackPointSerializer::class)
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val eleM: Double,
    val distM: Double,
    val gradient: Double,
    val headingDeg: Double,
)

object TrackPointSerializer : CompactArraySerializer<TrackPoint>("ucai.engine.course.TrackPoint", 6) {
    override fun Writer.write(value: TrackPoint) {
        double(value.lat); double(value.lon); double(value.eleM)
        double(value.distM); double(value.gradient); double(value.headingDeg)
    }

    override fun Reader.read() = TrackPoint(double(), double(), double(), double(), double(), double())
}
