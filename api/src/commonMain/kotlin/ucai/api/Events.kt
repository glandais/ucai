package ucai.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A notable race event, serialized with a snake_case `type` discriminator:
 * `{"type": "attack", "tick": 1234, "riderId": 12, ...}`.
 * Serialize through `RaceEvent.serializer()` (or a `List<RaceEvent>`) so `type` is written.
 */
@Serializable
sealed interface RaceEvent {
    val tick: Int

    @Serializable
    @SerialName("race_started")
    data class RaceStarted(override val tick: Int) : RaceEvent

    @Serializable
    @SerialName("attack")
    data class Attack(override val tick: Int, val riderId: Int, val fromGroupId: Int, val distM: Double) : RaceEvent

    /** [riderIds] left [groupId] and now form [newGroupId], [gapS] behind (negative = ahead). */
    @Serializable
    @SerialName("group_split")
    data class GroupSplit(
        override val tick: Int,
        val groupId: Int,
        val newGroupId: Int,
        val riderIds: List<Int>,
        val gapS: Double,
    ) : RaceEvent

    /** [groupId] was caught by (merged into) [intoGroupId]. */
    @Serializable
    @SerialName("group_merge")
    data class GroupMerge(
        override val tick: Int,
        val groupId: Int,
        val intoGroupId: Int,
        val riderIds: List<Int>,
    ) : RaceEvent

    @Serializable
    @SerialName("rider_dropped")
    data class RiderDropped(override val tick: Int, val riderId: Int, val fromGroupId: Int, val distM: Double) : RaceEvent

    /** [riderIds] in passage order at the top (first riders only). */
    @Serializable
    @SerialName("climb_summit")
    data class ClimbSummit(
        override val tick: Int,
        val climbName: String,
        val distM: Double,
        val riderIds: List<Int>,
    ) : RaceEvent

    /** Emitted when the head of the race passes 50, 20, 10, 5 and 1 km to go. */
    @Serializable
    @SerialName("km_to_go")
    data class KmToGo(override val tick: Int, val km: Int, val leadGroupId: Int) : RaceEvent

    @Serializable
    @SerialName("rider_finished")
    data class RiderFinished(override val tick: Int, val riderId: Int, val position: Int, val timeS: Double) : RaceEvent

    @Serializable
    @SerialName("race_finished")
    data class RaceFinished(override val tick: Int, val winnerRiderId: Int, val timeS: Double) : RaceEvent

    companion object {
        val KM_TO_GO_MARKS = listOf(50, 20, 10, 5, 1)
    }
}
