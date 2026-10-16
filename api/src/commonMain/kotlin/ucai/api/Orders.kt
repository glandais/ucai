package ucai.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An instruction to one rider. Which parameters apply depends on [mode]:
 * - `follow`: [targetRiderId], [maxIntensity] (fraction of cpEff, default 1.0)
 * - `pull`: [intensity] (fraction of cpEff)
 * - `attack`: [intensity] (fraction of the W′-allowed max), [durationS]
 * - `sprint`, `recover`: none
 */
@Serializable
data class Order(
    val riderId: Int,
    val mode: RiderMode,
    val intensity: Double? = null,
    val durationS: Int? = null,
    val targetRiderId: Int? = null,
    val maxIntensity: Double? = null,
)

/** `POST /races/{raceId}/teams/{teamId}/orders`. [decisionId] is optional in `realtime`. */
@Serializable
data class OrdersRequest(
    val decisionId: Int? = null,
    val orders: List<Order> = emptyList(),
    val ack: Boolean = false,
)

@Serializable
enum class RejectReason {
    @SerialName("unknown_rider") UNKNOWN_RIDER,
    @SerialName("not_own_rider") NOT_OWN_RIDER,
    @SerialName("stale_decision") STALE_DECISION,
    @SerialName("missing_decision") MISSING_DECISION,
    @SerialName("invalid_value") INVALID_VALUE,
    @SerialName("rider_not_racing") RIDER_NOT_RACING,
    @SerialName("race_not_running") RACE_NOT_RUNNING,
}

@Serializable
data class RejectedOrder(
    val order: Order,
    val reason: RejectReason,
    val message: String? = null,
)

@Serializable
data class OrdersResponse(
    val decisionId: Int? = null,
    val accepted: List<Order>,
    val rejected: List<RejectedOrder>,
    /** True when the team's decision is closed (this or an earlier request had `ack: true`). */
    val acked: Boolean,
)
