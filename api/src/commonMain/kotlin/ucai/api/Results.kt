package ucai.api

import kotlinx.serialization.Serializable

/**
 * One line of the classification. Finished riders have [position], [timeS] (interpolated crossing time)
 * and [gapS] (group time, UCI rule); others only have their [status].
 */
@Serializable
data class ResultEntry(
    val riderId: Int,
    val teamId: String,
    val status: RiderStatus,
    val position: Int? = null,
    val timeS: Double? = null,
    val gapS: Double? = null,
)

/** `GET /races/{raceId}/results`; partial ([final] = false) while racing. */
@Serializable
data class Classification(
    val raceId: String,
    val final: Boolean,
    val entries: List<ResultEntry>,
)
