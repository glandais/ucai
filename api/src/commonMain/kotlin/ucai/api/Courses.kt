package ucai.api

import kotlinx.serialization.Serializable

/** `GET /courses` item. */
@Serializable
data class CourseSummary(
    val id: String,
    val name: String,
    val distanceM: Double,
    val elevationGainM: Double,
    val startName: String? = null,
    val finishName: String? = null,
)

/** `GET /courses/{courseId}`, also the format of `races/courses/<id>.json` and of the replay `course`. */
@Serializable
data class CourseDetail(
    val id: String,
    val name: String,
    val distanceM: Double,
    val elevationGainM: Double,
    val startName: String? = null,
    val finishName: String? = null,
    val points: List<CoursePoint>,
    val climbs: List<Climb> = emptyList(),
    /** Geometry for maps, denser than [points] in turns (Douglas-Peucker); empty when [points] are already exact. */
    val track: List<CoursePoint> = emptyList(),
) {
    fun summary() = CourseSummary(id, name, distanceM, elevationGainM, startName, finishName)
}

/** A track point, serialized compactly as `[lat, lon, eleM, distM]`. */
@Serializable(with = CoursePointSerializer::class)
data class CoursePoint(val lat: Double, val lon: Double, val eleM: Double, val distM: Double)

object CoursePointSerializer : CompactArraySerializer<CoursePoint>("ucai.api.CoursePoint", 4) {
    override fun Writer.write(value: CoursePoint) {
        double(value.lat); double(value.lon); double(value.eleM); double(value.distM)
    }

    override fun Reader.read() = CoursePoint(lat = double(), lon = double(), eleM = double(), distM = double())
}

/** A named climb; [endM] is the summit. Grades are fractions (0.037 = 3.7 %). */
@Serializable
data class Climb(
    val name: String,
    val startM: Double,
    val endM: Double,
    val avgGrade: Double,
    val maxGrade: Double,
)
