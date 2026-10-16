package ucai.team

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ucai.api.ApiJson
import ucai.api.ApiPaths
import ucai.api.CourseDetail
import ucai.api.DecisionEvent
import ucai.api.Order
import ucai.api.OrdersRequest
import ucai.api.OrdersResponse
import ucai.api.RaceDetail
import ucai.api.RaceEvent
import ucai.api.RaceState
import ucai.api.RejectReason
import ucai.api.SseEvents
import ucai.api.StateEvent
import ucai.api.TeamView
import ucai.team.strategy.StrategyParams
import ucai.team.strategy.TeamStrategy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Where and how a [TeamBot] plays.
 *
 * @property baseUrl kernel root, e.g. `http://localhost:8080` (the bot adds [ApiPaths.BASE]).
 * @property token the team token returned at registration (`Authorization: Bearer`).
 * @property requestTimeout for every plain request (GET, orders POST); the stream itself has none.
 * @property maxReconnects reconnections allowed after a dropped stream before failing; the budget is restored once
 *   a decision has been answered on the new connection.
 * @property postRetries extra attempts for an orders POST that fails on I/O or a 5xx (the POST is idempotent).
 * @property failOnStrategyError a decision the strategy can't answer is still acked with no orders (so the kernel
 *   doesn't wait forever); when true, [TeamBot.run] then fails instead of carrying on.
 */
data class TeamBotConfig(
    val baseUrl: String,
    val raceId: String,
    val teamId: String,
    val token: String,
    val params: StrategyParams = StrategyParams(),
    val requestTimeout: Duration = 30.seconds,
    val maxReconnects: Int = 1,
    val postRetries: Int = 2,
    val retryDelay: Duration = 200.milliseconds,
    val failOnStrategyError: Boolean = false,
) {
    val apiUrl: String get() = baseUrl.trimEnd('/') + ApiPaths.BASE

    internal fun url(path: String) = baseUrl.trimEnd('/') + path
}

/** Everything known before the race when the strategy is built. */
data class StrategyContext(
    val config: TeamBotConfig,
    val race: RaceDetail,
    val course: CourseDetail,
    val team: TeamView,
)

/** Turns a decision event into orders. [TeamBot.defaultStrategy] wraps [TeamStrategy]. */
fun interface Decider {
    fun decide(event: DecisionEvent): List<Order>
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

fun interface BotLogger {
    fun log(level: LogLevel, message: String)
}

/** What a finished run did. [finishTick] is the tick of the end of the race, when the stream reported it. */
data class TeamBotResult(
    val teamId: String,
    val decisions: Int,
    val ordersSent: Int,
    val ordersRejected: Int,
    val reconnects: Int,
    val strategyErrors: Int,
    val finishTick: Int?,
)

/** A run that could not go on. The kernel may be waiting for this team: whoever runs the bot must react. */
class TeamBotException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The team v1 bot (DESIGN §5): talks to the kernel over HTTP only (API v1), one instance per team.
 *
 * [run] fetches the team view, the race (public start list) and the course, builds the strategy, then follows the
 * team SSE stream: each `decision` is answered exactly once with a POST of the strategy's orders, its `decisionId`
 * and `ack: true`. The run completes on `race_finished` or `state: finished`. A dropped stream is reconnected
 * ([TeamBotConfig.maxReconnects]); since the kernel has no resume buffer, it must send the pending `decision` (if
 * any) to a team stream as soon as it connects, or a decision raised while the bot was away would never be answered.
 *
 * Lockstep has no timeout, so the bot never stays silent on an error: an HTTP error or an unreadable stream fails
 * [run] with a [TeamBotException], and a decision the strategy can't answer is acked with no orders.
 *
 * @param engine HTTP engine to use (not closed by the bot); by default a CIO engine owned and closed by the bot.
 */
class TeamBot(
    val config: TeamBotConfig,
    engine: HttpClientEngine? = null,
    private val logger: BotLogger = BotLogger { level, msg -> println("[team ${config.teamId}] $level $msg") },
    private val strategy: (StrategyContext) -> Decider = ::defaultStrategy,
) {
    private val ownsEngine = engine == null
    private val client = HttpClient(engine ?: CIO.create { requestTimeout = 0 }) {
        expectSuccess = false
        install(SSE)
        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeout.inWholeMilliseconds
            connectTimeoutMillis = config.requestTimeout.inWholeMilliseconds
        }
        install(ContentNegotiation) { json(ApiJson) }
    }

    private val connectedSignal = CompletableDeferred<Unit>()

    /** Completes when the team stream is first open (fails if [run] fails before): start the race after it. */
    val connected: Deferred<Unit> get() = connectedSignal

    private var lastDecisionId = Int.MIN_VALUE
    private var decisions = 0
    private var ordersSent = 0
    private var ordersRejected = 0
    private var reconnects = 0
    private var strategyErrors = 0
    private var finishTick: Int? = null

    private val raceId get() = config.raceId
    private val teamId get() = config.teamId

    /** Plays the race to the end. Throws [TeamBotException] when it can't. Call once. */
    suspend fun run(): TeamBotResult {
        try {
            val decider = setUp()
            follow(decider)
            logger.log(LogLevel.INFO, "race over after $decisions decisions ($ordersRejected orders rejected)")
            return TeamBotResult(teamId, decisions, ordersSent, ordersRejected, reconnects, strategyErrors, finishTick)
        } catch (e: CancellationException) {
            connectedSignal.cancel(e)
            throw e
        } catch (e: Exception) {
            val err = e as? TeamBotException ?: TeamBotException("team $teamId in race $raceId: ${e.message ?: e}", e)
            logger.log(LogLevel.ERROR, err.message ?: "failed")
            connectedSignal.completeExceptionally(err)
            throw err
        } finally {
            if (ownsEngine) client.engine.close()
            client.close()
        }
    }

    private suspend fun setUp(): Decider {
        val team: TeamView = getJson(ApiPaths.team(raceId, teamId), auth = true, what = "team view")
        val race: RaceDetail = getJson(ApiPaths.race(raceId), auth = false, what = "race")
        val course: CourseDetail = getJson(ApiPaths.course(race.courseId), auth = false, what = "course ${race.courseId}")
        logger.log(LogLevel.INFO, "${team.team.name}: ${team.riders.size} riders on ${course.name}, race ${race.state}")
        return strategy(StrategyContext(config, race, course, team))
    }

    private suspend inline fun <reified T> getJson(path: String, auth: Boolean, what: String): T {
        val response = client.get(config.url(path)) { if (auth) bearerAuth(config.token) }
        if (!response.status.isSuccess()) throw httpError("GET $what", response)
        return response.body()
    }

    /** Follows the stream until the race is over, reconnecting on drops. */
    private suspend fun follow(decider: Decider) {
        var budget = config.maxReconnects
        while (true) {
            val before = decisions
            val drop = connectOnce(decider) ?: return
            if (decisions > before) budget = config.maxReconnects
            // The race may have ended while the stream was going down.
            val race: RaceDetail = getJson(ApiPaths.race(raceId), auth = false, what = "race")
            if (race.state == RaceState.FINISHED) {
                logger.log(LogLevel.INFO, "stream closed, race finished")
                return
            }
            if (budget <= 0) {
                throw TeamBotException("team $teamId in race $raceId: team stream dropped ($drop), no reconnection left")
            }
            budget--
            reconnects++
            logger.log(LogLevel.WARN, "team stream dropped ($drop), reconnecting")
        }
    }

    /** One stream connection. Returns null when the race is over, else why the stream ended. */
    private suspend fun connectOnce(decider: Decider): String? {
        var over = false
        // Thrown from inside the flow, a failure would come out wrapped as a stream error: carry it out instead.
        var fatal: TeamBotException? = null
        try {
            client.sse(config.url(ApiPaths.teamStream(raceId, teamId)), request = {
                bearerAuth(config.token)
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                }
            }) {
                connectedSignal.complete(Unit)
                over = incoming.firstOrNull {
                    try {
                        handle(it, decider)
                    } catch (e: TeamBotException) {
                        fatal = e
                        true
                    }
                } != null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SSEClientException) {
            val status = e.response?.status
            if (status != null && status.value in 400..499) {
                throw TeamBotException("team $teamId in race $raceId: team stream refused: $status", e)
            }
            return "connection failed: ${status ?: e.message ?: e.cause}"
        } catch (e: TeamBotException) {
            throw e
        } catch (e: Exception) {
            return "${e::class.simpleName}: ${e.message}"
        }
        fatal?.let { throw it }
        return if (over) null else "end of stream"
    }

    /** Handles one SSE event; true when the race is over. */
    private suspend fun handle(ev: ServerSentEvent, decider: Decider): Boolean {
        val data = ev.data ?: return false
        when (ev.event) {
            SseEvents.DECISION -> onDecision(data, decider)
            SseEvents.STATE -> {
                val state = decodeOrNull(StateEvent.serializer(), data, "state") ?: return false
                if (state.state == RaceState.FINISHED) {
                    finishTick = finishTick ?: state.tick
                    return true
                }
            }
            SseEvents.EVENT -> {
                val event = decodeOrNull(RaceEvent.serializer(), data, "event") ?: return false
                if (event is RaceEvent.RaceFinished) {
                    finishTick = event.tick
                    return true
                }
            }
            // snapshot, team: everything the bot needs comes with the decision.
        }
        return false
    }

    private fun <T> decodeOrNull(serializer: kotlinx.serialization.KSerializer<T>, data: String, what: String): T? =
        try {
            ApiJson.decodeFromString(serializer, data)
        } catch (e: Exception) {
            logger.log(LogLevel.WARN, "unreadable $what event ignored: ${e.message}")
            null
        }

    private suspend fun onDecision(data: String, decider: Decider) {
        val event = try {
            ApiJson.decodeFromString(DecisionEvent.serializer(), data)
        } catch (e: Exception) {
            // Still close the decision if its id is readable, so the kernel doesn't wait for us.
            val id = try {
                ApiJson.parseToJsonElement(data).jsonObject["decisionId"]?.jsonPrimitive?.int
            } catch (_: Exception) {
                null
            } ?: throw TeamBotException("team $teamId in race $raceId: unreadable decision event: ${e.message}", e)
            if (id <= lastDecisionId) return
            lastDecisionId = id
            strategyErrors++
            logger.log(LogLevel.ERROR, "decision $id unreadable (${e.message}), acked with no orders")
            answer(id, emptyList())
            if (config.failOnStrategyError) throw TeamBotException("team $teamId: decision $id unreadable", e)
            return
        }
        if (event.decisionId <= lastDecisionId) {
            logger.log(LogLevel.DEBUG, "decision ${event.decisionId} already answered")
            return
        }
        lastDecisionId = event.decisionId
        val orders = try {
            decider.decide(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            strategyErrors++
            logger.log(LogLevel.ERROR, "strategy failed on decision ${event.decisionId} (${e.message}), acked with no orders")
            answer(event.decisionId, emptyList())
            if (config.failOnStrategyError) {
                throw TeamBotException("team $teamId: strategy failed on decision ${event.decisionId}", e)
            }
            return
        }
        answer(event.decisionId, orders)
    }

    /** POSTs the orders with `ack: true`, retrying I/O errors and 5xx; anything else that isn't an ack is fatal. */
    private suspend fun answer(decisionId: Int, orders: List<Order>) {
        val body = OrdersRequest(decisionId = decisionId, orders = orders, ack = true)
        val path = ApiPaths.teamOrders(raceId, teamId)
        var attempt = 0
        while (true) {
            val response = try {
                client.post(config.url(path)) {
                    bearerAuth(config.token)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt++ < config.postRetries) {
                    logger.log(LogLevel.WARN, "orders for decision $decisionId failed (${e.message}), retrying")
                    delay(config.retryDelay * attempt)
                    continue
                }
                throw TeamBotException("team $teamId in race $raceId: orders for decision $decisionId failed: ${e.message}", e)
            }
            when {
                response.status.isSuccess() -> {
                    decisions++
                    ordersSent += orders.size
                    onOrdersResponse(decisionId, response.body())
                    return
                }
                response.status == HttpStatusCode.Conflict -> {
                    // The kernel moved past this decision (e.g. we answered after a reconnection): not fatal.
                    logger.log(LogLevel.WARN, "decision $decisionId is stale: ${response.bodyAsText().take(200)}")
                    return
                }
                response.status.value >= 500 && attempt++ < config.postRetries -> {
                    logger.log(LogLevel.WARN, "orders for decision $decisionId: ${response.status}, retrying")
                    delay(config.retryDelay * attempt)
                }
                else -> throw httpError("POST orders for decision $decisionId", response)
            }
        }
    }

    private fun onOrdersResponse(decisionId: Int, response: OrdersResponse) {
        ordersRejected += response.rejected.size
        for (r in response.rejected) {
            val why = r.message?.let { " ($it)" } ?: ""
            logger.log(LogLevel.WARN, "decision $decisionId: order ${r.order} rejected: ${r.reason}$why")
        }
        val stale = response.rejected.any { it.reason == RejectReason.STALE_DECISION || it.reason == RejectReason.RACE_NOT_RUNNING }
        if (!response.acked && !stale) {
            throw TeamBotException("team $teamId in race $raceId: decision $decisionId was not acked by the kernel")
        }
    }

    private suspend fun httpError(what: String, response: HttpResponse): TeamBotException {
        val text = try {
            response.bodyAsText().take(500)
        } catch (_: Exception) {
            ""
        }
        return TeamBotException("team $teamId in race $raceId: $what: ${response.status} $text".trimEnd())
    }

    companion object {
        /** The v1 scripted strategy, built from the public start list and the team's own riders. */
        fun defaultStrategy(ctx: StrategyContext): Decider {
            val s = TeamStrategy(ctx.config.teamId, ctx.course, ctx.race.riders, ctx.team.riders, ctx.config.params)
            return Decider(s::decide)
        }
    }
}
