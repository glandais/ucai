package ucai.kernel

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.builtins.ListSerializer
import ucai.api.ApiJson
import ucai.api.ApiPaths
import ucai.api.ClockConfig
import ucai.api.ClockMode
import ucai.api.CourseDetail
import ucai.api.CourseSummary
import ucai.api.CreateRaceRequest
import ucai.api.RaceDetail
import ucai.api.RaceState
import ucai.api.RaceSummary
import ucai.api.RegisterTeamRequest
import ucai.api.TeamView
import ucai.engine.riders.RatingScale
import ucai.engine.riders.toSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistryTest {
    @Test
    fun courses() = kernelTest { api ->
        val list = api.getAs(ApiPaths.courses(), ListSerializer(CourseSummary.serializer()))
        assertEquals(listOf("long", "short"), list.map { it.id })
        val detail = api.getAs(ApiPaths.course("short"), CourseDetail.serializer())
        assertEquals(6_000.0, detail.distanceM)
        assertEquals(6_000.0, detail.points.last().distM, "the finish is kept")
        assertEquals(121, detail.points.size, "one point every 50 m")
        assertEquals(1, detail.climbs.size)
    }

    @Test
    fun createAndRegister() = kernelTest { api ->
        assertEquals(
            HttpStatusCode.NotFound,
            api.post(ApiPaths.races(), CreateRaceRequest.serializer(), CreateRaceRequest("nowhere", 1, ClockConfig(ClockMode.LOCKSTEP))).status,
            "an unknown course is a 404",
        )
        val raceId = api.createRace(seed = 7)
        val a = api.register(raceId, "Alpha", "#ff0000")
        val b = api.register(raceId, "Bravo", "#00ff00")
        assertEquals("team-01", a.teamId)
        assertEquals("team-02", b.teamId)
        assertEquals((1..7).toList(), a.riders.map { it.id })
        assertEquals((8..14).toList(), b.riders.map { it.id })
        assertTrue(a.token != b.token && a.token.length >= 32)
        // Ratings from the reference scale: independent of the rest of the field and of registration order.
        for (r in a.riders + b.riders) assertEquals(RatingScale.REFERENCE.rate(r.profile), r.ratings)
        val solo = api.createRace(seed = 7)
        assertEquals(a.riders, api.register(solo, "Alpha", "#ff0000").riders, "same seed and index, same riders and ratings")
        // The public seed alone does not rebuild the riders: they come from the seed mixed with the kernel secret.
        val public = ucai.engine.riders.RiderGenerator.generateTeamRiders(7, 0)
        assertTrue(public.map { it.name } != a.riders.map { it.name }, "riders must not be generated from the bare seed")
        val secret = ucai.engine.riders.RiderGenerator.generateTeamRiders(raceSeed(7, api.kernel.config.seedSecret), 0)
        assertEquals(secret.map { it.name to it.profile }, a.riders.map { it.name to it.profile })

        // Validation.
        fun reg(name: String, color: String = "#123456", riders: List<ucai.api.RiderSpec>? = null) =
            RegisterTeamRequest(name, color, riders)
        suspend fun status(req: RegisterTeamRequest) = api.post(ApiPaths.raceTeams(raceId), RegisterTeamRequest.serializer(), req).status
        assertEquals(HttpStatusCode.BadRequest, status(reg("Alpha")), "duplicate team name")
        assertEquals(HttpStatusCode.BadRequest, status(reg("C", color = "red")))
        assertEquals(HttpStatusCode.BadRequest, status(reg("")))
        assertEquals(HttpStatusCode.BadRequest, status(reg("C", riders = a.riders.take(3).map { it.toSpec() })), "7 riders")
        assertEquals(HttpStatusCode.BadRequest, status(reg("C", riders = a.riders.map { it.toSpec() })), "names already used")
        val bad = a.riders.mapIndexed { i, r -> r.toSpec().copy(name = "Rider $i", profile = r.profile.copy(cp = 9_000.0)) }
        assertEquals(HttpStatusCode.BadRequest, status(reg("C", riders = bad)), "profile out of range")
        val own = a.riders.mapIndexed { i, r -> r.toSpec().copy(name = "Own $i") }
        val ok = api.post(ApiPaths.raceTeams(raceId), RegisterTeamRequest.serializer(), reg("Charlie", riders = own))
        assertEquals(HttpStatusCode.Created, ok.status, ok.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, api.client.post(ApiPaths.raceTeams(raceId), ADMIN, "{not json").status)

        val detail = api.getAs(ApiPaths.race(raceId), RaceDetail.serializer())
        assertEquals(3, detail.teams.size)
        assertEquals(21, detail.riders.size)
        assertEquals(RaceState.REGISTRATION, detail.state)
        assertNull(detail.seed)
        val races = api.getAs(ApiPaths.races(), ListSerializer(RaceSummary.serializer()))
        assertEquals(listOf(raceId, solo), races.map { it.id })
        assertEquals(3, races.first().teamCount)

        val view = api.getAs(ApiPaths.team(raceId, a.teamId), TeamView.serializer(), a.token)
        assertEquals(a.riders, view.riders)
        assertTrue(view.states.isEmpty(), "no live state before the start")

        // Start closes registration.
        assertEquals(HttpStatusCode.OK, api.start(raceId, wait = false).status)
        assertEquals(HttpStatusCode.Conflict, api.start(raceId).status)
        assertEquals(HttpStatusCode.Conflict, status(reg("Late")))
        val empty = api.createRace()
        assertEquals(HttpStatusCode.Conflict, api.start(empty).status, "no team")
        assertEquals(HttpStatusCode.BadRequest, api.client.post(ApiPaths.raceStart(solo) + "?wait=maybe") { bearerAuth(ADMIN) }.status)
    }

    @Test
    fun raceIsFullAfterMaxTeams() = kernelTest { api ->
        val raceId = api.createRace()
        repeat(10) { api.register(raceId, "T$it") }
        assertEquals(
            HttpStatusCode.Conflict,
            api.post(ApiPaths.raceTeams(raceId), RegisterTeamRequest.serializer(), RegisterTeamRequest("T10", "#000000")).status,
        )
    }

    private suspend fun HttpClient.post(path: String, token: String, raw: String) = post(path) {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(raw)
    }
}
