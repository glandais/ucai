package ucai.kernel

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import ucai.api.ApiJson
import ucai.api.ApiPaths
import ucai.api.DecisionEvent
import ucai.api.Order
import ucai.api.RaceState
import ucai.api.RiderMode
import ucai.api.Snapshot
import ucai.api.SseEvents
import ucai.api.TeamStateEvent
import ucai.api.TeamView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DESIGN §1/§4 information asymmetry: what public endpoints and other teams never see. */
class PrivacyTest {
    /** [seed]: a distinctive value that must never appear in the payload. */
    private fun assertPublic(where: String, json: String, seed: Long? = null) {
        val leaked = Json.parseToJsonElement(json).allKeys() intersect PRIVATE_KEYS
        assertTrue(leaked.isEmpty(), "$where leaks $leaked")
        assertTrue(!MODE_VALUE.containsMatchIn(json), "$where leaks modes")
        if (seed != null) assertTrue(!json.contains(seed.toString()), "$where leaks the seed")
    }

    private val seed = 987_654_321L
    private val MODE_VALUE = Regex("\"mode\"\\s*:\\s*\"(follow|pull|attack|sprint|recover)\"")

    @Test
    fun publicEndpointsHaveNoPrivateNumbersModesOrSeed() = kernelTest { api ->
        // A race held at decision 0 (its team never connects) and one that runs to the finish on its own.
        val held = api.createRace(seed = seed)
        val ran = api.createRace(seed = seed)
        for (id in listOf(held, ran)) repeat(2) { api.register(id, "T$it") }
        fun paths(id: String) = listOf(ApiPaths.courses(), ApiPaths.races(), ApiPaths.race(id), ApiPaths.raceResults(id))
        for (p in paths(held)) assertPublic("GET $p (registration)", api.get(p).bodyAsText(), seed)

        assertEquals(HttpStatusCode.OK, api.start(held).status)
        assertPublic("POST start", api.start(ran, wait = false).bodyAsText(), seed)
        for (p in paths(held) + ApiPaths.raceState(held)) assertPublic("GET $p (running)", api.get(p).bodyAsText(), seed)
        assertEquals(RaceState.RUNNING, api.getAs(ApiPaths.race(held), ucai.api.RaceDetail.serializer()).state)

        api.kernel.race(ran)!!.awaitFinish()
        for (p in paths(ran) + ApiPaths.raceState(ran) + ApiPaths.raceReplay(ran)) {
            val r = api.get(p)
            assertEquals(HttpStatusCode.OK, r.status, p)
            assertPublic("GET $p (finished)", r.bodyAsText(), seed)
        }
        assertTrue(!api.get(ApiPaths.race(ran)).bodyAsText().contains("\"seed\""), "no seed even once finished")
    }

    @Test
    fun publicStreamHasNoPrivateNumbersOrModes() = liveKernelTest { api ->
        val raceId = api.createRace(seed = seed)
        val a = api.register(raceId, "A")
        val b = api.register(raceId, "B")
        val bots = listOf(
            TestBot(api, raceId, a) { d -> if (d.decisionId == 0) listOf(Order(a.riders[0].id, RiderMode.PULL, 0.9)) else emptyList() },
            TestBot(api, raceId, b) { d -> if (d.decisionId == 0) listOf(Order(b.riders[0].id, RiderMode.ATTACK, 0.7, 30)) else emptyList() },
        )
        coroutineScope {
            val public = launch {
                val events = api.stream(ApiPaths.raceStream(raceId))
                assertTrue(events.any { it.event == SseEvents.SNAPSHOT } && events.any { it.event == SseEvents.EVENT })
                for (e in events) {
                    assertTrue(e.event in setOf(SseEvents.STATE, SseEvents.SNAPSHOT, SseEvents.EVENT), "public stream sends ${e.event}")
                    e.data?.let { assertPublic("public stream ${e.event}", it, seed) }
                }
            }
            val jobs = bots.map { launch { it.run() } }
            delay(200)
            api.start(raceId)
            (jobs + public).forEach { it.join() }
        }
        assertTrue(bots.all { it.finished })
    }

    @Test
    fun aTeamSeesNothingPrivateAboutOtherTeams() = liveKernelTest { api ->
        val raceId = api.createRace()
        val a = api.register(raceId, "A")
        val b = api.register(raceId, "B")
        val own = a.riders.map { it.id }.toSet()
        val botA = TestBot(api, raceId, a, leaveAfter = 4)
        val botB = TestBot(api, raceId, b, leaveAfter = 4) { d ->
            if (d.decisionId == 0) listOf(Order(b.riders[0].id, RiderMode.PULL, 1.0)) else emptyList()
        }
        coroutineScope {
            val jobs = listOf(launch { botA.run() }, launch { botB.run() })
            api.start(raceId)
            jobs.forEach { it.join() }
        }
        val view = api.getAs(ApiPaths.team(raceId, a.teamId), TeamView.serializer(), a.token)
        assertEquals(own, view.riders.map { it.id }.toSet())
        assertEquals(own, view.states.map { it.riderId }.toSet())
        for (ev in botA.raw) {
            val data = ev.data ?: continue
            when (ev.event) {
                SseEvents.DECISION -> {
                    val d = ApiJson.decodeFromString(DecisionEvent.serializer(), data)
                    assertEquals(own, d.team.map { it.riderId }.toSet())
                    // The rest of the payload is the public snapshot and public events.
                    assertPublic("decision snapshot", ApiJson.encodeToString(Snapshot.serializer(), d.snapshot))
                }
                SseEvents.TEAM -> {
                    assertEquals(own, ApiJson.decodeFromString(TeamStateEvent.serializer(), data).riders.map { it.riderId }.toSet())
                    assertTrue(MODE_VALUE.containsMatchIn(data), "the mode check does detect private states")
                }
                else -> assertPublic("team stream ${ev.event}", data)
            }
        }
        assertTrue(botA.decisions.size >= 4)
    }
}
