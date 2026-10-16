package ucai.kernel

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import ucai.api.ApiJson
import ucai.api.ApiPaths
import ucai.api.ClockConfig
import ucai.api.ClockMode
import ucai.api.CreateRaceRequest
import ucai.api.OrdersRequest
import ucai.api.RegisterTeamRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthTest {
    /** A 401 carries the bearer challenge and the usual error body (DESIGN §4). */
    private suspend fun assertUnauthorized(r: HttpResponse, message: String? = null) {
        assertEquals(HttpStatusCode.Unauthorized, r.status, message)
        assertEquals("Bearer", r.headers[HttpHeaders.WWWAuthenticate], message)
        assertEquals(ApiJson.encodeToString(ErrorResponse.serializer(), ErrorResponse("missing or unknown token")), r.bodyAsText(), message)
    }

    private val create = CreateRaceRequest("short", 1, ClockConfig(ClockMode.LOCKSTEP))

    @Test
    fun adminRoutesNeedTheAdminToken() = kernelTest { api ->
        assertUnauthorized(api.post(ApiPaths.races(), CreateRaceRequest.serializer(), create, token = null))
        assertUnauthorized(api.post(ApiPaths.races(), CreateRaceRequest.serializer(), create, token = "nope"))
        val raceId = api.createRace()
        val team = api.register(raceId, "A")
        // A team token is not an admin token.
        assertEquals(HttpStatusCode.Unauthorized, api.post(ApiPaths.races(), CreateRaceRequest.serializer(), create, token = team.token).status)
        val reg = RegisterTeamRequest("B", "#000000")
        assertEquals(HttpStatusCode.Unauthorized, api.post(ApiPaths.raceTeams(raceId), RegisterTeamRequest.serializer(), reg, token = null).status)
        assertEquals(HttpStatusCode.Unauthorized, api.post(ApiPaths.raceTeams(raceId), RegisterTeamRequest.serializer(), reg, token = team.token).status)
        assertEquals(HttpStatusCode.Unauthorized, api.client.post(ApiPaths.raceStart(raceId)).status)
        assertEquals(HttpStatusCode.Unauthorized, api.client.post(ApiPaths.raceStart(raceId)) { bearerAuth(team.token) }.status)
        assertEquals(HttpStatusCode.OK, api.start(raceId, wait = false).status)
    }

    @Test
    fun teamRoutesAreScopedToOneTeamOfOneRace() = kernelTest { api ->
        val raceId = api.createRace()
        val a = api.register(raceId, "A")
        val b = api.register(raceId, "B")
        val otherRace = api.createRace()
        val c = api.register(otherRace, "C")
        assertEquals(a.teamId, c.teamId, "same team id in another race")

        val view = ApiPaths.team(raceId, a.teamId)
        val stream = ApiPaths.teamStream(raceId, a.teamId)
        for (path in listOf(view, stream)) {
            assertUnauthorized(api.get(path), "$path without a token")
            assertUnauthorized(api.get(path, "garbage"), "$path with an unknown token")
            assertEquals(HttpStatusCode.Unauthorized, api.get(path, ADMIN).status, "the admin is not a team")
            assertEquals(HttpStatusCode.Forbidden, api.get(path, b.token).status, "$path with another team's token")
            assertEquals(HttpStatusCode.Forbidden, api.get(path, c.token).status, "$path with the same team id of another race")
        }
        assertEquals(HttpStatusCode.OK, api.get(view, a.token).status)

        val orders = OrdersRequest(0, emptyList(), ack = true)
        val path = ApiPaths.teamOrders(raceId, a.teamId)
        assertEquals(HttpStatusCode.Unauthorized, api.post(path, OrdersRequest.serializer(), orders, token = null).status)
        assertEquals(HttpStatusCode.Forbidden, api.post(path, OrdersRequest.serializer(), orders, token = b.token).status)
        assertEquals(HttpStatusCode.Unauthorized, api.post(path, OrdersRequest.serializer(), orders, token = ADMIN).status)
        // A team id that is not in the race, with a token of the race: still another team's route.
        assertEquals(HttpStatusCode.Forbidden, api.get(ApiPaths.team(raceId, "team-09"), a.token).status)
    }

    @Test
    fun publicRoutesNeedNoToken() = kernelTest { api ->
        val raceId = api.createRace()
        api.register(raceId, "A")
        for (p in listOf(ApiPaths.courses(), ApiPaths.course("short"), ApiPaths.races(), ApiPaths.race(raceId), ApiPaths.raceResults(raceId))) {
            assertEquals(HttpStatusCode.OK, api.get(p).status, p)
        }
        assertEquals(HttpStatusCode.NotFound, api.get(ApiPaths.race("race-99")).status)
        assertEquals(HttpStatusCode.NotFound, api.get(ApiPaths.course("nowhere")).status)
        assertEquals(HttpStatusCode.Conflict, api.get(ApiPaths.raceState(raceId)).status, "no snapshot before the start")
        assertEquals(HttpStatusCode.Conflict, api.get(ApiPaths.raceReplay(raceId)).status, "no replay before the finish")
        assertEquals(HttpStatusCode.NotFound, api.get(ApiPaths.raceStream("race-99")).status)
    }
}
