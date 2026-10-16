package ucai.team

import ucai.api.ApiV1

/** Placeholder for the team v1 bot. */
class TeamBot(val baseUrl: String) {
    val apiUrl: String get() = baseUrl.trimEnd('/') + ApiV1.BASE_PATH
}
