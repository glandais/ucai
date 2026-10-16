package ucai.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One rider in a snapshot, serialized compactly as `[riderId, distM, speed, groupId, rankInGroup]`. */
@Serializable(with = SnapshotRiderSerializer::class)
data class SnapshotRider(
    val riderId: Int,
    val distM: Double,
    val speed: Double,
    val groupId: Int,
    val rankInGroup: Int,
)

object SnapshotRiderSerializer : CompactArraySerializer<SnapshotRider>("ucai.api.SnapshotRider", 5) {
    override fun Writer.write(value: SnapshotRider) {
        int(value.riderId); double(value.distM); double(value.speed); int(value.groupId); int(value.rankInGroup)
    }

    override fun Reader.read() =
        SnapshotRider(riderId = int(), distM = double(), speed = double(), groupId = int(), rankInGroup = int())
}

/** [gapS] is the time gap to the head of the race (0 for the leading group). */
@Serializable
data class GroupSummary(
    val id: Int,
    val headDistM: Double,
    val size: Int,
    val gapS: Double,
)

/** Public snapshot: SSE `snapshot` and `GET /races/{raceId}/state`. */
@Serializable
data class Snapshot(
    val tick: Int,
    val raceTimeS: Double,
    val riders: List<SnapshotRider>,
    val groups: List<GroupSummary>,
)

/** SSE `state`. */
@Serializable
data class StateEvent(val state: RaceState, val tick: Int? = null)

/** SSE `team` (team stream only): own riders' live state. */
@Serializable
data class TeamStateEvent(val tick: Int, val riders: List<PrivateRiderState>)

/** Why a decision point was raised. */
@Serializable
enum class DecisionReason {
    /** Every 30 s of race time. */
    @SerialName("periodic") PERIODIC,
    @SerialName("race_started") RACE_STARTED,
    @SerialName("attack") ATTACK,
    @SerialName("group_split") GROUP_SPLIT,
    @SerialName("group_merge") GROUP_MERGE,
    @SerialName("rider_dropped") RIDER_DROPPED,
    @SerialName("climb_summit") CLIMB_SUMMIT,
    @SerialName("km_to_go") KM_TO_GO,
    /** A `follow` rider could not stay with its target within `maxIntensity`. */
    @SerialName("target_lost") TARGET_LOST,
}

/**
 * SSE `decision` (team stream only): everything a bot needs to decide.
 * [events] are the public events since the previous decision point.
 */
@Serializable
data class DecisionEvent(
    val decisionId: Int,
    val tick: Int,
    val reasons: List<DecisionReason>,
    val snapshot: Snapshot,
    val team: List<PrivateRiderState>,
    val events: List<RaceEvent> = emptyList(),
)
