package ucai.engine.race

import ucai.api.Order
import ucai.api.RejectedOrder

/** Accepted ranges of order parameters (DESIGN §3 "Rider modes"). */
object OrderLimits {
    val PULL_INTENSITY = 0.3..1.5
    const val PULL_DEFAULT = 0.9
    /** Attack intensity is a fraction of the W′-allowed max: (0, 1]. */
    val ATTACK_INTENSITY = 0.05..1.0
    const val ATTACK_DEFAULT = 1.0
    val ATTACK_DURATION_S = 1..600
    const val ATTACK_DURATION_DEFAULT = 30
    val FOLLOW_MAX_INTENSITY = 0.3..1.5
    const val FOLLOW_MAX_DEFAULT = 1.0
}

/** Outcome of a batch of orders for one team. */
data class OrderResult(val accepted: List<Order>, val rejected: List<RejectedOrder>) {
    companion object {
        val EMPTY = OrderResult(emptyList(), emptyList())
    }
}

/** A decision point: race paused at [tick]; [events] happened since the previous one. */
data class DecisionPoint(
    val decisionId: Int,
    val tick: Int,
    val reasons: List<ucai.api.DecisionReason>,
    val events: List<ucai.api.RaceEvent>,
)
