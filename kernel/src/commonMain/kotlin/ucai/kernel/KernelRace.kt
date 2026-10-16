package ucai.kernel

import io.ktor.http.HttpStatusCode
import io.ktor.util.GZipEncoder
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import ucai.api.ApiJson
import ucai.api.Classification
import ucai.api.ClockConfig
import ucai.api.ClockMode
import ucai.api.CourseDetail
import ucai.api.DecisionEvent
import ucai.api.Order
import ucai.api.OrdersRequest
import ucai.api.OrdersResponse
import ucai.api.PrivateRider
import ucai.api.RaceDetail
import ucai.api.RaceEvent
import ucai.api.RaceState
import ucai.api.RaceSummary
import ucai.api.RegisterTeamRequest
import ucai.api.RejectReason
import ucai.api.RejectedOrder
import ucai.api.Replay
import ucai.api.Snapshot
import ucai.api.SseEvents
import ucai.api.StateEvent
import ucai.api.TeamInfo
import ucai.api.TeamStateEvent
import ucai.api.TeamView
import ucai.api.Wind
import ucai.engine.course.Course
import ucai.engine.race.DecisionPoint
import ucai.engine.race.Race
import ucai.engine.race.RaceEntrant
import ucai.engine.race.RaceObserver
import ucai.engine.race.RaceView
import ucai.engine.replay.ReplayRecorder
import ucai.engine.riders.ProfileLimits
import ucai.engine.riders.RatingScale
import ucai.engine.riders.RiderGenerator
import ucai.engine.riders.toPrivate
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal class RegisteredTeam(val info: TeamInfo, val riders: List<PrivateRider>)

/** A finished race's replay, encoded once: plain JSON and gzip. */
internal class ReplayBlob(val json: ByteArray, val gzip: ByteArray)

/**
 * One race in the kernel (DESIGN §3–§4): registration, the engine [Race], its tick loop and lockstep coordinator,
 * and the SSE subscribers. Every state change happens under [mutex]; the tick loop runs in [scope] (never in a
 * request handler) and only holds the lock while the engine computes up to the next decision point.
 *
 * Lockstep protocol:
 * - at each decision point every team gets a `decision` event and the race waits, with no timeout, until every
 *   team has acked (`ack: true`);
 * - orders are only accepted for the current `decisionId`; a team's orders accumulate until it acks (for the same
 *   rider the last one wins) and are frozen once it has acked;
 * - when every team has acked, all orders are applied together through the engine's canonical [Race.applyOrders];
 * - a team whose stream closed (no open team stream left) is auto-acked with no orders, and it is logged;
 * - a team that has **never** connected its stream is waited for, so a controller can connect before or after the
 *   admin starts the race: on connect it receives the pending decision. Starting with `wait = false` auto-acks
 *   teams that are not connected instead (a race with absent teams).
 *
 * Realtime: one tick per [KernelConfig.realtimeTick], orders (no `decisionId` needed) apply at the next tick, no
 * `decision` events.
 */
class KernelRace internal constructor(
    val id: String,
    val course: Course,
    internal val courseDetail: CourseDetail,
    val seed: Long,
    val clock: ClockConfig,
    val wind: Wind?,
    private val config: KernelConfig,
    private val scope: CoroutineScope,
) {
    private val log = config.log
    private val mutex = Mutex()
    private val teams = mutableListOf<RegisteredTeam>()
    private val usedNames = mutableSetOf<String>()
    private var state = RaceState.REGISTRATION
    private var startedAt: String? = null
    private var engine: Race? = null
    private var recorder: ReplayRecorder? = null
    private var replay: ReplayBlob? = null
    private var loop: Job? = null

    private val subscribers = mutableListOf<Subscriber>()
    private val openStreams = HashMap<String, Int>()
    private val everConnected = HashSet<String>()
    private var waitForTeams = true
    private var pending: PendingDecision? = null
    private val realtimeOrders = HashMap<String, LinkedHashMap<Int, Order>>()
    private var lastSnapshot: TimeMark? = null
    /** Teams currently auto-acked because they have no open stream. */
    private val autoAcking = HashSet<String>()

    private val lockstep get() = clock.mode == ClockMode.LOCKSTEP

    /** Teams' decision state at the current decision point. */
    private class PendingDecision(val point: DecisionPoint) {
        val acked = HashSet<String>()
        /** Per team: riderId → last accepted order. */
        val orders = HashMap<String, LinkedHashMap<Int, Order>>()
        /** Every accepted order per team, so a repeat after the ack is still accepted (idempotent). */
        val sent = HashMap<String, MutableSet<Order>>()
        val done = CompletableDeferred<Unit>()
    }

    // ---------------------------------------------------------------------------------------------------------
    // Registration and start (admin)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Registers a team: id `team-NN` by registration order, riders generated from the race seed and the team index
     * when [RegisterTeamRequest.riders] is omitted. Rider ids are `index × 7 + 1…`, and ratings come from
     * [RatingScale.REFERENCE] so they never depend on the other teams or the registration order.
     */
    internal suspend fun register(req: RegisterTeamRequest): RegisteredTeam = mutex.withLock {
        if (state != RaceState.REGISTRATION) conflict("registration is closed (race $state)")
        if (teams.size >= config.maxTeams) conflict("the race is full (${config.maxTeams} teams)")
        if (req.name.isBlank() || req.name.length > 64) badRequest("name must be 1 to 64 characters")
        if (!COLOR.matches(req.color)) badRequest("color must be a #rrggbb hex color")
        if (teams.any { it.info.name == req.name }) badRequest("a team named '${req.name}' is already registered")
        val index = teams.size
        val perTeam = RiderGenerator.RIDERS_PER_TEAM
        val specs = req.riders?.let { list ->
            if (list.size != perTeam) badRequest("a team has $perTeam riders, got ${list.size}")
            val names = mutableSetOf<String>()
            list.forEach { s ->
                val errors = ProfileLimits.validate(s)
                if (errors.isNotEmpty()) badRequest("rider '${s.name}': ${errors.joinToString("; ")}")
                if (s.name in usedNames || !names.add(s.name)) badRequest("duplicate rider name '${s.name}'")
            }
            usedNames += names
            list
        } ?: RiderGenerator.generateTeamRiders(seed, index, perTeam, usedNames)
        val info = TeamInfo(RiderGenerator.teamId(index), req.name, req.color)
        val riders = specs.mapIndexed { k, s -> s.toPrivate(index * perTeam + k + 1, info.id, RatingScale.REFERENCE.rate(s.profile)) }
        RegisteredTeam(info, riders).also { teams += it }
    }

    /** Starts the race (see the class doc for [wait]). Returns the public detail. */
    internal suspend fun start(wait: Boolean): RaceDetail = mutex.withLock {
        if (state != RaceState.REGISTRATION) conflict("race already $state")
        if (teams.isEmpty()) conflict("no team registered")
        waitForTeams = wait
        val race = Race(course, teams.flatMap { t -> t.riders.map { RaceEntrant(it.id, it.teamId, it.profile) } }, seed, wind)
        val rec = ReplayRecorder()
        race.addObserver(rec)
        race.addObserver(StreamObserver())
        engine = race
        recorder = rec
        state = RaceState.RUNNING
        startedAt = config.wallClock?.invoke()
        broadcast(stateEvent())
        val first = race.start()
        val absent = teams.map { it.info.id }.filter { (openStreams[it] ?: 0) == 0 }
        log.info("race $id started (${clock.mode.name.lowercase()}, ${teams.size} teams)" +
            if (absent.isNotEmpty() && lockstep) ", ${if (wait) "waiting for" else "auto-acking"} streams of $absent" else "")
        loop = scope.launch(Dispatchers.Default) {
            try {
                if (lockstep) runLockstep(first) else runRealtime()
                mutex.withLock { finish() }
            } catch (e: Throwable) {
                log.error("race $id: tick loop failed: $e")
                throw e
            }
        }
        detailLocked()
    }

    // ---------------------------------------------------------------------------------------------------------
    // Tick loops
    // ---------------------------------------------------------------------------------------------------------

    private suspend fun runLockstep(first: DecisionPoint) {
        var dp: DecisionPoint? = first
        while (dp != null) {
            val p = mutex.withLock { openDecision(dp) }
            p.done.await()
            dp = mutex.withLock {
                val race = engine!!
                pending = null
                val orders = p.orders.filterValues { it.isNotEmpty() }.mapValues { (_, byRider) -> byRider.values.toList() }
                if (orders.isNotEmpty()) {
                    race.applyOrders(p.point.decisionId, orders).forEach { (team, r) ->
                        if (r.rejected.isNotEmpty()) log.warn("race $id decision ${p.point.decisionId}: $team orders rejected at apply: ${r.rejected}")
                    }
                }
                race.runToNextDecision()
            }
            yield()
        }
    }

    private suspend fun runRealtime() {
        while (true) {
            delay(config.realtimeTick)
            val finished = mutex.withLock {
                val race = engine!!
                if (realtimeOrders.isNotEmpty()) {
                    val orders = realtimeOrders.mapValues { (_, byRider) -> byRider.values.toList() }
                    realtimeOrders.clear()
                    race.applyOrders(null, orders, lockstep = false)
                }
                race.step()
                race.state == RaceState.FINISHED
            }
            if (finished) return
        }
    }

    /** Opens the decision point [dp]: auto-acks absent teams, sends `decision` to the connected ones. */
    private fun openDecision(dp: DecisionPoint): PendingDecision {
        val p = PendingDecision(dp)
        pending = p
        for (t in teams) {
            val teamId = t.info.id
            if ((openStreams[teamId] ?: 0) > 0) continue
            if (teamId in everConnected) {
                autoAck(p, teamId, "stream closed")
            } else if (!waitForTeams) {
                autoAck(p, teamId, "stream not connected")
            }
        }
        for (s in subscribers.toList()) {
            val teamId = s.teamId ?: continue
            if (teamId !in p.acked) s.offer(decisionSse(teamId))
        }
        checkDone(p)
        return p
    }

    private fun autoAck(p: PendingDecision, teamId: String, why: String) {
        if (!p.acked.add(teamId)) return
        p.orders.remove(teamId)
        // Logged once per absence (not at every decision point), until the team connects again.
        if (autoAcking.add(teamId)) {
            log.warn("race $id decision ${p.point.decisionId}: team $teamId auto-acked with no orders ($why), until it reconnects")
        }
    }

    private fun checkDone(p: PendingDecision) {
        if (p.acked.size >= teams.size) p.done.complete(Unit)
    }

    private suspend fun finish() {
        val race = engine!!
        state = RaceState.FINISHED
        pending = null
        val detail = detailLocked()
        val r: Replay = recorder!!.replay(detail, race.classification(id), courseDetail)
        val json = ApiJson.encodeToString(Replay.serializer(), r).encodeToByteArray()
        val gzip = GZipEncoder.encode(ByteReadChannel(json), Dispatchers.Default).toByteArray()
        replay = ReplayBlob(json, gzip)
        publishSnapshot(race)
        broadcast(stateEvent())
        for (s in subscribers) s.close()
        log.info("race $id finished at tick ${race.tick}")
    }

    /** Pushes engine events and throttled snapshots to the streams, from inside the engine's tick. */
    private inner class StreamObserver : RaceObserver {
        override fun onTick(race: RaceView, events: List<RaceEvent>) {
            for (e in events) broadcast(sse(SseEvents.EVENT, RaceEvent.serializer(), e, e.tick))
            if (race.state == RaceState.FINISHED) return // finish() sends the last snapshot
            val last = lastSnapshot
            if (last == null || last.elapsedNow() >= config.snapshotInterval) publishSnapshot(engine!!)
        }
    }

    private fun publishSnapshot(race: Race) {
        lastSnapshot = TimeSource.Monotonic.markNow()
        if (subscribers.isEmpty()) return
        val snap = sse(SseEvents.SNAPSHOT, Snapshot.serializer(), race.snapshot(), race.tick)
        val teamEvents = HashMap<String, io.ktor.sse.ServerSentEvent>()
        for (s in subscribers.toList()) {
            if (!s.offer(snap)) continue
            val teamId = s.teamId ?: continue
            s.offer(teamEvents.getOrPut(teamId) { teamSse(race, teamId) })
        }
    }

    private fun teamSse(race: Race, teamId: String) =
        sse(SseEvents.TEAM, TeamStateEvent.serializer(), TeamStateEvent(race.tick, race.teamState(teamId)), race.tick)

    private fun decisionSse(teamId: String): io.ktor.sse.ServerSentEvent {
        val ev = engine!!.decisionEvent(teamId)!!
        return sse(SseEvents.DECISION, DecisionEvent.serializer(), ev, ev.tick)
    }

    private fun stateEvent() = sse(SseEvents.STATE, StateEvent.serializer(), StateEvent(state, engine?.tick ?: 0), engine?.tick ?: 0)

    private fun broadcast(event: io.ktor.sse.ServerSentEvent) {
        for (s in subscribers.toList()) s.offer(event)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Streams
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Opens a stream ([teamId] null: public). The first events are `state`, then (once started) a fresh `snapshot`,
     * the team's `team` state and, if the team has not acked the pending decision, its `decision`. A finished race
     * sends `state` and the last snapshot, then closes the stream.
     */
    internal suspend fun subscribe(teamId: String?): Subscriber = mutex.withLock {
        val s = Subscriber(teamId, config.streamBuffer)
        s.offer(stateEvent())
        engine?.let { race ->
            s.offer(sse(SseEvents.SNAPSHOT, Snapshot.serializer(), race.snapshot(), race.tick))
            if (teamId != null) s.offer(teamSse(race, teamId))
        }
        if (state == RaceState.FINISHED) {
            s.close()
            return@withLock s
        }
        subscribers += s
        if (teamId != null) {
            openStreams[teamId] = (openStreams[teamId] ?: 0) + 1
            val first = everConnected.add(teamId)
            val back = autoAcking.remove(teamId)
            if (first || back) log.info("race $id: team $teamId stream ${if (back) "reconnected" else "connected"}")
            pending?.let { p -> if (teamId !in p.acked) s.offer(decisionSse(teamId)) }
        }
        s
    }

    /** Closes a stream; the last team stream to close auto-acks the team's pending decision. */
    internal suspend fun unsubscribe(s: Subscriber) = mutex.withLock {
        s.close()
        if (!subscribers.remove(s)) return@withLock
        val teamId = s.teamId ?: return@withLock
        val left = (openStreams[teamId] ?: 1) - 1
        openStreams[teamId] = left
        if (left == 0 && state == RaceState.RUNNING) {
            log.warn("race $id: team $teamId stream closed")
            pending?.let { p ->
                autoAck(p, teamId, "stream closed")
                checkDone(p)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Orders
    // ---------------------------------------------------------------------------------------------------------

    /** `POST …/orders`: the HTTP status (200, or 409 when the decision is not open) and the response. */
    internal suspend fun postOrders(teamId: String, req: OrdersRequest): Pair<HttpStatusCode, OrdersResponse> = mutex.withLock {
        fun rejectAll(reason: RejectReason, message: String) = HttpStatusCode.Conflict to OrdersResponse(
            req.decisionId, emptyList(), req.orders.map { RejectedOrder(it, reason, message) }, acked = false,
        )
        val race = engine
        if (race == null || state != RaceState.RUNNING) return@withLock rejectAll(RejectReason.RACE_NOT_RUNNING, "race is $state")
        if (!lockstep) {
            val result = race.validateOrders(teamId, null, req.orders, lockstep = false)
            val byRider = realtimeOrders.getOrPut(teamId) { LinkedHashMap() }
            for (o in result.accepted) byRider[o.riderId] = o
            return@withLock HttpStatusCode.OK to OrdersResponse(req.decisionId, result.accepted, result.rejected, acked = req.ack)
        }
        val p = pending
        if (req.decisionId == null) return@withLock rejectAll(RejectReason.MISSING_DECISION, "decisionId is required in lockstep")
        if (p == null || req.decisionId != p.point.decisionId) {
            return@withLock rejectAll(RejectReason.STALE_DECISION, "current decision is ${p?.point?.decisionId ?: "none (race running)"}")
        }
        val result = race.validateOrders(teamId, req.decisionId, req.orders)
        val sent = p.sent.getOrPut(teamId) { mutableSetOf() }
        val accepted = mutableListOf<Order>()
        val rejected = result.rejected.toMutableList()
        if (teamId in p.acked) {
            // Closed decision: only a repeat of what was already sent is accepted (same body twice is idempotent).
            for (o in result.accepted) {
                if (o in sent) accepted += o else rejected += RejectedOrder(o, RejectReason.DECISION_ACKED, "decision ${p.point.decisionId} already acked")
            }
        } else {
            val byRider = p.orders.getOrPut(teamId) { LinkedHashMap() }
            for (o in result.accepted) {
                byRider[o.riderId] = o
                sent += o
                accepted += o
            }
            if (req.ack) {
                p.acked += teamId
                checkDone(p)
            }
        }
        HttpStatusCode.OK to OrdersResponse(req.decisionId, accepted, rejected, acked = teamId in p.acked)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Views
    // ---------------------------------------------------------------------------------------------------------

    internal suspend fun summary(): RaceSummary = mutex.withLock {
        RaceSummary(id, course.id, course.name, state, teams.size, startedAt)
    }

    /** Public detail: archetypes and ratings only, never the seed (DESIGN §4, batch-2 review). */
    internal suspend fun detail(): RaceDetail = mutex.withLock { detailLocked() }

    private fun detailLocked() = RaceDetail(
        id = id, courseId = course.id, courseName = course.name, state = state, clock = clock, wind = wind,
        teams = teams.map { it.info }, riders = teams.flatMap { t -> t.riders.map { it.toPublic() } },
        tick = engine?.tick ?: 0, startedAt = startedAt, seed = null,
    )

    internal suspend fun snapshot(): Snapshot = mutex.withLock {
        engine?.snapshot() ?: conflict("race not started")
    }

    internal suspend fun classification(): Classification = mutex.withLock {
        engine?.classification(id) ?: Classification(id, final = false, entries = emptyList())
    }

    internal suspend fun teamView(teamId: String): TeamView = mutex.withLock {
        val t = teams.firstOrNull { it.info.id == teamId } ?: notFound("unknown team $teamId")
        TeamView(id, t.info, engine?.tick ?: 0, t.riders, engine?.teamState(teamId).orEmpty())
    }

    internal suspend fun hasTeam(teamId: String): Boolean = mutex.withLock { teams.any { it.info.id == teamId } }

    internal suspend fun replay(): ReplayBlob = mutex.withLock {
        replay ?: conflict("the replay is available once the race is finished (race $state)")
    }

    /** Waits for the tick loop to end (tests, runner). */
    suspend fun awaitFinish() {
        mutex.withLock { loop }?.join()
    }

    internal companion object {
        val COLOR = Regex("#[0-9a-fA-F]{6}")
    }
}
