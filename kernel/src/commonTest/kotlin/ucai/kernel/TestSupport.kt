package ucai.kernel

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import ucai.api.ApiJson
import ucai.api.ApiPaths
import ucai.api.Climb
import ucai.api.ClockConfig
import ucai.api.ClockMode
import ucai.api.CreateRaceRequest
import ucai.api.CreateRaceResponse
import ucai.api.DecisionEvent
import ucai.api.Order
import ucai.api.OrdersRequest
import ucai.api.OrdersResponse
import ucai.api.RaceEvent
import ucai.api.RaceState
import ucai.api.RegisterTeamRequest
import ucai.api.RegisterTeamResponse
import ucai.api.SseEvents
import ucai.api.StateEvent
import ucai.api.TeamStateEvent
import ucai.engine.course.COURSE_STEP_M
import ucai.engine.course.Course
import ucai.engine.course.EARTH_RADIUS_M
import ucai.engine.course.TrackPoint
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.PI
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

const val ADMIN = "test-admin-token"

/** A straight north-bound [lengthM] course, flat except a 5 % climb in the middle. */
fun testCourse(id: String = "short", lengthM: Double = 6_000.0): Course {
    val degLatM = EARTH_RADIUS_M * PI / 180.0
    val climb = Climb("Côte", lengthM * 0.4, lengthM * 0.6, 0.05, 0.05)
    val n = (lengthM / COURSE_STEP_M).toInt()
    var ele = 100.0
    val pts = (0..n).map { k ->
        val d = if (k == n) lengthM else k * COURSE_STEP_M
        val g = if (d >= climb.startM && d < climb.endM) climb.avgGrade else 0.0
        TrackPoint(45.0 + d / degLatM, 9.0, ele, d, g, 0.0).also { ele += g * COURSE_STEP_M }
    }
    return Course(id, "Short $id", lengthM, climb.avgGrade * (climb.endM - climb.startM), points = pts, climbs = listOf(climb))
}

/** Captures the kernel log (thread-safe: the tick loop logs from Dispatchers.Default). */
@OptIn(ExperimentalAtomicApi::class)
class LogCapture : KernelLog {
    private val ref = AtomicReference(emptyList<String>())
    val lines: List<String> get() = ref.load()

    override fun log(level: KernelLog.Level, message: String) {
        while (true) {
            val cur = ref.load()
            if (ref.compareAndSet(cur, cur + "$level $message")) return
        }
    }
}

fun testConfig(log: KernelLog = LogCapture(), realtimeTick: Duration = 50.milliseconds) = KernelConfig(
    adminToken = ADMIN,
    courses = CourseCatalog.of(listOf(testCourse(), testCourse("long", 12_000.0))),
    realtimeTick = realtimeTick,
    heartbeat = 100.milliseconds,
    log = log,
)

/** A kernel in a Ktor test application (REST only: the test engine delivers a streamed response only once it ends). */
fun kernelTest(
    config: KernelConfig = testConfig(),
    timeout: Duration = 60.seconds,
    block: suspend ApplicationTestBuilder.(Api) -> Unit,
) = testApplication {
    val kernel = Kernel(config)
    application { kernelModule(kernel) }
    val api = Api(createClient { install(SSE) }, kernel)
    withTimeout(timeout) { block(api) }
}

/**
 * A kernel on a real embedded CIO server (port 0, as the runner starts it) with a CIO client: for the SSE tests,
 * which need streamed responses.
 */
fun liveKernelTest(
    config: KernelConfig = testConfig(),
    timeout: Duration = 90.seconds,
    block: suspend (Api) -> Unit,
) = runTest(timeout = timeout + 10.seconds) {
    withContext(Dispatchers.Default) {
        val server = startKernelServer(config)
        val client = HttpClient(CIO) {
            install(SSE)
            defaultRequest { url(server.baseUrl) }
        }
        try {
            withTimeout(timeout) { block(Api(client, server.kernel)) }
        } finally {
            client.close()
            server.stop()
        }
    }
}

/** Thin JSON helpers over the test client. */
class Api(val client: HttpClient, val kernel: Kernel) {
    suspend fun <T> post(path: String, serializer: KSerializer<T>, body: T, token: String? = ADMIN): HttpResponse =
        client.post(path) {
            token?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(ApiJson.encodeToString(serializer, body))
        }

    suspend fun get(path: String, token: String? = null): HttpResponse = client.get(path) { token?.let { bearerAuth(it) } }

    suspend fun <T> getAs(path: String, serializer: KSerializer<T>, token: String? = null): T {
        val r = get(path, token)
        assertEquals(HttpStatusCode.OK, r.status, "GET $path: ${r.bodyAsText()}")
        return ApiJson.decodeFromString(serializer, r.bodyAsText())
    }

    suspend fun createRace(courseId: String = "short", seed: Long = 42, mode: ClockMode = ClockMode.LOCKSTEP): String {
        val r = post(ApiPaths.races(), CreateRaceRequest.serializer(), CreateRaceRequest(courseId, seed, ClockConfig(mode)))
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        return ApiJson.decodeFromString(CreateRaceResponse.serializer(), r.bodyAsText()).raceId
    }

    suspend fun register(raceId: String, name: String, color: String = "#123456"): RegisterTeamResponse {
        val r = post(ApiPaths.raceTeams(raceId), RegisterTeamRequest.serializer(), RegisterTeamRequest(name, color))
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        return ApiJson.decodeFromString(RegisterTeamResponse.serializer(), r.bodyAsText())
    }

    suspend fun start(raceId: String, wait: Boolean = true): HttpResponse =
        client.post(ApiPaths.raceStart(raceId) + if (wait) "" else "?wait=false") { bearerAuth(ADMIN) }

    suspend fun orders(raceId: String, team: RegisterTeamResponse, req: OrdersRequest): Pair<HttpStatusCode, OrdersResponse> {
        val r = post(ApiPaths.teamOrders(raceId, team.teamId), OrdersRequest.serializer(), req, team.token)
        return r.status to ApiJson.decodeFromString(OrdersResponse.serializer(), r.bodyAsText())
    }

    /** Collects a stream (named events; heartbeat comments are skipped) until [until] says stop or the server closes it. */
    suspend fun stream(path: String, token: String? = null, until: (ServerSentEvent) -> Boolean = { false }): List<ServerSentEvent> {
        val out = mutableListOf<ServerSentEvent>()
        client.sse(path, request = { token?.let { bearerAuth(it) } }) {
            incoming.filter { it.event != null }.takeWhile { ev ->
                out += ev
                !until(ev)
            }.collect { }
        }
        return out
    }
}

/**
 * The minimal in-test controller: follows its team stream, answers every `decision` with [decide]'s orders and an
 * ack, and stops when the race is finished (or after [leaveAfter] decisions, closing its stream).
 */
class TestBot(
    private val api: Api,
    private val raceId: String,
    val team: RegisterTeamResponse,
    private val leaveAfter: Int? = null,
    private val decide: (DecisionEvent) -> List<Order> = { emptyList() },
) {
    val decisions = mutableListOf<DecisionEvent>()
    val responses = mutableListOf<Pair<HttpStatusCode, OrdersResponse>>()
    val raw = mutableListOf<ServerSentEvent>()
    var snapshots = 0
    var teamEvents = mutableListOf<TeamStateEvent>()
    val events = mutableListOf<RaceEvent>()
    var finished = false

    suspend fun run() {
        api.client.sse(ApiPaths.teamStream(raceId, team.teamId), request = { bearerAuth(team.token) }) {
            incoming.filter { it.event != null }.takeWhile { ev ->
                raw += ev
                when (ev.event) {
                    SseEvents.DECISION -> {
                        val d = ApiJson.decodeFromString(DecisionEvent.serializer(), ev.data!!)
                        decisions += d
                        if (leaveAfter != null && decisions.size > leaveAfter) return@takeWhile false
                        responses += api.orders(raceId, team, OrdersRequest(d.decisionId, decide(d), ack = true))
                    }
                    SseEvents.SNAPSHOT -> snapshots++
                    SseEvents.TEAM -> teamEvents += ApiJson.decodeFromString(TeamStateEvent.serializer(), ev.data!!)
                    SseEvents.EVENT -> events += ApiJson.decodeFromString(RaceEvent.serializer(), ev.data!!)
                    SseEvents.STATE -> if (ApiJson.decodeFromString(StateEvent.serializer(), ev.data!!).state == RaceState.FINISHED) finished = true
                }
                !finished
            }.collect { }
        }
    }
}

/** Every object key in a JSON tree. */
fun JsonElement.allKeys(): Set<String> = when (this) {
    is JsonObject -> keys + values.flatMap { it.allKeys() }
    is JsonArray -> flatMap { it.allKeys() }.toSet()
    else -> emptySet()
}

/** Keys that only a team's private view may contain (DESIGN §4 "Public"); rider modes are checked by value. */
val PRIVATE_KEYS = setOf(
    "profile", "cp", "wPrime", "pMax", "cda", "durability", "massKg", "power", "wPrimeBal", "cpEff", "wPrimeEff",
    "energyKj", "seed", "token",
)
