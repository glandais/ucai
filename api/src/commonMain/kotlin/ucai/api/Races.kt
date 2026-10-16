package ucai.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RaceState {
    @SerialName("registration") REGISTRATION,
    @SerialName("running") RUNNING,
    @SerialName("finished") FINISHED,
}

@Serializable
enum class ClockMode {
    @SerialName("lockstep") LOCKSTEP,
    @SerialName("realtime") REALTIME,
}

@Serializable
data class ClockConfig(val mode: ClockMode)

/** Constant wind: direction it blows from (degrees, 0 = north, clockwise) and speed in m/s. */
@Serializable
data class Wind(val fromDeg: Double, val speed: Double)

/** `POST /races` (admin). */
@Serializable
data class CreateRaceRequest(
    val courseId: String,
    val seed: Long,
    val clock: ClockConfig,
    val wind: Wind? = null,
)

@Serializable
data class CreateRaceResponse(val raceId: String)

/** Public team info. [color] is a CSS hex color such as `#d62728`. */
@Serializable
data class TeamInfo(val id: String, val name: String, val color: String)

/** `GET /races` item. Wall-clock times are ISO-8601 strings. */
@Serializable
data class RaceSummary(
    val id: String,
    val courseId: String,
    val courseName: String,
    val state: RaceState,
    val teamCount: Int,
    val startedAt: String? = null,
)

/**
 * `GET /races/{raceId}`: metadata, public start list and current state.
 * The [seed] rebuilds every rider profile and the seeded race factors, so it is only revealed once the race is
 * [RaceState.FINISHED] (for replays); it is always null before that.
 */
@Serializable
data class RaceDetail(
    val id: String,
    val courseId: String,
    val courseName: String,
    val state: RaceState,
    val clock: ClockConfig,
    val wind: Wind? = null,
    val teams: List<TeamInfo> = emptyList(),
    val riders: List<PublicRider> = emptyList(),
    val tick: Int = 0,
    val startedAt: String? = null,
    val seed: Long? = null,
) {
    init {
        require(seed == null || state == RaceState.FINISHED) { "the seed of a race is only public once it is finished" }
    }
}

/** `POST /races/{raceId}/teams` (admin). Riders are generated when [riders] is omitted. */
@Serializable
data class RegisterTeamRequest(
    val name: String,
    val color: String,
    val riders: List<RiderSpec>? = null,
)

/** The [token] is only returned here; riders come with their full profiles. */
@Serializable
data class RegisterTeamResponse(
    val teamId: String,
    val token: String,
    val riders: List<PrivateRider>,
)

/** `GET /races/{raceId}/teams/{teamId}`: the team's private view. */
@Serializable
data class TeamView(
    val raceId: String,
    val team: TeamInfo,
    val tick: Int,
    val riders: List<PrivateRider>,
    val states: List<PrivateRiderState>,
)
