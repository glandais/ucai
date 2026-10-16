package ucai.api

import kotlinx.serialization.Serializable

/**
 * Replay format v1 (`GET /races/{raceId}/replay`, gzip). Frame rider arrays follow the order of [riders].
 * Frames are adaptive (see [ReplayFormat]); the frontend interpolates `distM` linearly between frames.
 */
@Serializable
data class Replay(
    val version: Int = ReplayFormat.VERSION,
    val race: RaceDetail,
    val course: CourseDetail,
    val teams: List<TeamInfo>,
    val riders: List<PublicRider>,
    val frames: List<ReplayFrame>,
    val events: List<RaceEvent>,
    val results: Classification,
)

/** [t] is race time in seconds (= tick); [r] has one entry per replay rider, in replay order. */
@Serializable
data class ReplayFrame(val t: Int, val r: List<FrameRider>)

/** Serialized compactly as `[distM, speed, groupId, rankInGroup]`. */
@Serializable(with = FrameRiderSerializer::class)
data class FrameRider(val distM: Double, val speed: Double, val groupId: Int, val rankInGroup: Int)

object FrameRiderSerializer : CompactArraySerializer<FrameRider>("ucai.api.FrameRider", 4) {
    override fun Writer.write(value: FrameRider) {
        double(value.distM); double(value.speed); int(value.groupId); int(value.rankInGroup)
    }

    override fun Reader.read() = FrameRider(distM = double(), speed = double(), groupId = int(), rankInGroup = int())
}

/** Adaptive frame step policy of replay v1. */
object ReplayFormat {
    const val VERSION = 1
    const val STEP_S = 5
    const val FINE_STEP_S = 1
    /** Fine steps when the head of the race is within this distance of the finish. */
    const val FINE_LAST_M = 3000.0
    /** Fine steps for this long after an attack. */
    const val FINE_AFTER_ATTACK_S = 60
}
