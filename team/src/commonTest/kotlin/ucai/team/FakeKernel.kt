package ucai.team

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.KSerializer
import ucai.api.ApiJson
import ucai.api.ApiPaths
import ucai.api.Archetype
import ucai.api.ClockConfig
import ucai.api.ClockMode
import ucai.api.Climb
import ucai.api.CourseDetail
import ucai.api.CoursePoint
import ucai.api.DecisionEvent
import ucai.api.DecisionReason
import ucai.api.GroupSummary
import ucai.api.OrdersRequest
import ucai.api.OrdersResponse
import ucai.api.PrivateRider
import ucai.api.PrivateRiderState
import ucai.api.RaceDetail
import ucai.api.RaceEvent
import ucai.api.RaceState
import ucai.api.RiderMode
import ucai.api.RiderProfile
import ucai.api.RiderRatings
import ucai.api.RiderStatus
import ucai.api.Snapshot
import ucai.api.SnapshotRider
import ucai.api.SseEvents
import ucai.api.StateEvent
import ucai.api.TeamInfo
import ucai.api.TeamStateEvent
import ucai.api.TeamView

/**
 * A minimal kernel speaking API v1 for one race and one team, on a real CIO server (port 0), so the bot runs its
 * production HTTP + SSE path. Each team stream connection runs [stream] with the connection number (1, 2, ...);
 * the script pushes events with [decision] etc. and waits for the bot's answers on [posts].
 */
class FakeKernel(
    var raceState: RaceState = RaceState.RUNNING,
    private val stream: suspend FakeKernel.(session: ServerSSESession, connection: Int) -> Unit,
) {
    val raceId = "r1"
    val teamId = "A"
    val token = "secret-A"

    /** Every orders POST, in arrival order. */
    val posts = Channel<OrdersRequest>(Channel.UNLIMITED)
    val received = mutableListOf<OrdersRequest>()
    var connections = 0
        private set

    /** Answer to a POST; by default accepts everything and acks. */
    var ordersResponder: suspend (ApplicationCall, OrdersRequest) -> Unit = { call, req ->
        call.respondJson(OrdersResponse.serializer(), OrdersResponse(req.decisionId, req.orders, emptyList(), req.ack))
    }
    var teamViewStatus: HttpStatusCode = HttpStatusCode.OK

    val course = CourseDetail(
        "liege-bastogne-liege", "LBL", 250_000.0, 4_000.0,
        points = listOf(CoursePoint(50.0, 5.0, 100.0, 0.0), CoursePoint(50.1, 5.0, 100.0, 250_000.0)),
        climbs = listOf(Climb("A", 200_000.0, 202_000.0, 0.08, 0.1), Climb("B", 236_000.0, 237_500.0, 0.09, 0.11)),
    )

    private fun rider(id: Int, team: String, a: Archetype, r: RiderRatings, cp: Double = 380.0) =
        PrivateRider(id, "R$id", team, a, r, RiderProfile(70.0, cp, 22_000.0, 1_100.0, 0.32, 0.7, 0.6))

    private fun team(team: String, first: Int) = listOf(
        rider(first, team, Archetype.PUNCHEUR, RiderRatings(4, 5, 3, 4, 3)),
        rider(first + 1, team, Archetype.CLIMBER, RiderRatings(5, 3, 1, 4, 3)),
        rider(first + 2, team, Archetype.SPRINTER, RiderRatings(1, 2, 5, 2, 3)),
        rider(first + 3, team, Archetype.ROULEUR, RiderRatings(2, 2, 3, 3, 3), cp = 430.0),
        rider(first + 4, team, Archetype.DOMESTIQUE, RiderRatings(1, 1, 2, 1, 3)),
        rider(first + 5, team, Archetype.DOMESTIQUE, RiderRatings(1, 1, 2, 1, 3)),
        rider(first + 6, team, Archetype.ALL_ROUNDER, RiderRatings(4, 4, 2, 5, 3)),
    )

    val mine = team(teamId, 1)
    private val other = team("B", 8)

    private fun states(distM: Double) = mine.map {
        PrivateRiderState(
            it.id, distM, 11.0, 250.0, 22_000.0, it.profile.cp, 22_000.0, 1_000.0, 1, it.id, RiderMode.FOLLOW,
            RiderStatus.RACING,
        )
    }

    private fun snapshot(tick: Int, distM: Double) = Snapshot(
        tick, tick.toDouble(), (mine + other).map { SnapshotRider(it.id, distM, 11.0, 1, it.id) },
        listOf(GroupSummary(1, distM, 14, 0.0)),
    )

    private val raceDetail
        get() = RaceDetail(
            raceId, course.id, course.name, raceState, ClockConfig(ClockMode.LOCKSTEP),
            teams = listOf(TeamInfo("A", "Team A", "#d62728"), TeamInfo("B", "Team B", "#1f77b4")),
            riders = (mine + other).map { it.toPublic() },
        )

    fun decisionEvent(id: Int, tick: Int = id * 300, distM: Double = tick * 11.0) = DecisionEvent(
        decisionId = id, tick = tick,
        reasons = listOf(if (id == 1) DecisionReason.RACE_STARTED else DecisionReason.PERIODIC),
        snapshot = snapshot(tick, distM), team = states(distM),
        events = if (id == 1) listOf(RaceEvent.RaceStarted(0)) else emptyList(),
    )

    suspend fun ServerSSESession.decision(id: Int) =
        send(ApiJson.encodeToString(DecisionEvent.serializer(), decisionEvent(id)), SseEvents.DECISION, "${id * 300}")

    suspend fun ServerSSESession.chatter(tick: Int) {
        send(ApiJson.encodeToString(Snapshot.serializer(), snapshot(tick, tick * 11.0)), SseEvents.SNAPSHOT, "$tick")
        send(ApiJson.encodeToString(TeamStateEvent.serializer(), TeamStateEvent(tick, states(tick * 11.0))), SseEvents.TEAM, "$tick")
        send(ApiJson.encodeToString(RaceEvent.serializer(), RaceEvent.KmToGo(tick, 50, 1)), SseEvents.EVENT, "$tick")
    }

    suspend fun ServerSSESession.state(state: RaceState, tick: Int) =
        send(ApiJson.encodeToString(StateEvent.serializer(), StateEvent(state, tick)), SseEvents.STATE, "$tick")

    suspend fun ServerSSESession.raceFinished(tick: Int) =
        send(ApiJson.encodeToString(RaceEvent.serializer(), RaceEvent.RaceFinished(tick, 1, tick.toDouble())), SseEvents.EVENT, "$tick")

    /** Waits for the bot's answer to the current decision. */
    suspend fun awaitPost(): OrdersRequest = posts.receive()

    private lateinit var server: EmbeddedServer<*, *>
    lateinit var baseUrl: String
        private set

    suspend fun start(): FakeKernel {
        server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(SSE)
            routing {
                get(ApiPaths.BASE + ApiPaths.TEAM) {
                    if (!authorized(call)) return@get
                    if (teamViewStatus != HttpStatusCode.OK) {
                        call.respondText("nope", status = teamViewStatus)
                        return@get
                    }
                    call.respondJson(
                        TeamView.serializer(),
                        TeamView(raceId, TeamInfo("A", "Team A", "#d62728"), 0, mine, states(0.0)),
                    )
                }
                get(ApiPaths.BASE + ApiPaths.RACE) { call.respondJson(RaceDetail.serializer(), raceDetail) }
                get(ApiPaths.BASE + ApiPaths.COURSE) { call.respondJson(CourseDetail.serializer(), course) }
                sse(ApiPaths.BASE + ApiPaths.TEAM_STREAM) {
                    check(call.request.headers[HttpHeaders.Authorization] == "Bearer $token") { "bad token" }
                    connections++
                    stream(this, connections)
                }
                post(ApiPaths.BASE + ApiPaths.TEAM_ORDERS) {
                    if (!authorized(call)) return@post
                    val req = ApiJson.decodeFromString(OrdersRequest.serializer(), call.receiveText())
                    received += req
                    posts.send(req)
                    ordersResponder(call, req)
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        baseUrl = "http://127.0.0.1:$port"
        return this
    }

    fun stop() = server.stop(0, 0)

    private suspend fun authorized(call: ApplicationCall): Boolean {
        if (call.request.headers[HttpHeaders.Authorization] == "Bearer $token") return true
        call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
        return false
    }

    fun config(f: TeamBotConfig.() -> TeamBotConfig = { this }) = TeamBotConfig(baseUrl, raceId, teamId, token).f()
}

suspend fun <T> ApplicationCall.respondJson(serializer: KSerializer<T>, value: T, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(ApiJson.encodeToString(serializer, value), ContentType.Application.Json, status)
