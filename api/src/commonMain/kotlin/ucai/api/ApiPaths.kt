package ucai.api

/**
 * REST paths of API v1 (DESIGN.md section 4).
 *
 * Templates (e.g. [RACE]) are relative to [BASE] and use Ktor-style `{param}` placeholders,
 * so the kernel can write `route(ApiPaths.BASE) { get(ApiPaths.RACE) { ... } }`.
 * Builder functions (e.g. [race]) return full paths including [BASE], for clients.
 */
object ApiPaths {
    const val BASE = "/api/v1"

    const val PARAM_COURSE_ID = "courseId"
    const val PARAM_RACE_ID = "raceId"
    const val PARAM_TEAM_ID = "teamId"

    /** Environment variable holding the admin bearer token. */
    const val ADMIN_TOKEN_ENV = "UCAI_ADMIN_TOKEN"

    const val COURSES = "/courses"
    const val COURSE = "/courses/{courseId}"

    const val RACES = "/races"
    const val RACE = "/races/{raceId}"
    const val RACE_TEAMS = "/races/{raceId}/teams"
    const val RACE_START = "/races/{raceId}/start"
    const val RACE_STATE = "/races/{raceId}/state"
    const val RACE_STREAM = "/races/{raceId}/stream"
    const val RACE_RESULTS = "/races/{raceId}/results"
    const val RACE_REPLAY = "/races/{raceId}/replay"

    const val TEAM = "/races/{raceId}/teams/{teamId}"
    const val TEAM_STREAM = "/races/{raceId}/teams/{teamId}/stream"
    const val TEAM_ORDERS = "/races/{raceId}/teams/{teamId}/orders"

    fun courses() = BASE + COURSES
    fun course(courseId: String) = "$BASE/courses/$courseId"
    fun races() = BASE + RACES
    fun race(raceId: String) = "$BASE/races/$raceId"
    fun raceTeams(raceId: String) = race(raceId) + "/teams"
    fun raceStart(raceId: String) = race(raceId) + "/start"
    fun raceState(raceId: String) = race(raceId) + "/state"
    fun raceStream(raceId: String) = race(raceId) + "/stream"
    fun raceResults(raceId: String) = race(raceId) + "/results"
    fun raceReplay(raceId: String) = race(raceId) + "/replay"
    fun team(raceId: String, teamId: String) = raceTeams(raceId) + "/$teamId"
    fun teamStream(raceId: String, teamId: String) = team(raceId, teamId) + "/stream"
    fun teamOrders(raceId: String, teamId: String) = team(raceId, teamId) + "/orders"
}

/** SSE `event:` names. The SSE `id` is the tick. */
object SseEvents {
    /** Payload: [Snapshot]. */
    const val SNAPSHOT = "snapshot"

    /** Payload: [RaceEvent] (polymorphic, `type` discriminator). */
    const val EVENT = "event"

    /** Payload: [StateEvent]. */
    const val STATE = "state"

    /** Team stream only. Payload: [DecisionEvent]. */
    const val DECISION = "decision"

    /** Team stream only. Payload: [TeamStateEvent]. */
    const val TEAM = "team"
}
