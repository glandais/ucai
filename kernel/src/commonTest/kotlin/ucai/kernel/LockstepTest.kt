package ucai.kernel

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.util.GZipEncoder
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ucai.api.ApiJson
import ucai.api.ApiPaths
import ucai.api.Classification
import ucai.api.DecisionReason
import ucai.api.Order
import ucai.api.OrdersRequest
import ucai.api.RaceDetail
import ucai.api.RaceEvent
import ucai.api.RaceState
import ucai.api.RejectReason
import ucai.api.Replay
import ucai.api.RiderMode
import ucai.api.RiderStatus
import ucai.api.SseEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class LockstepTest {
    /**
     * A full small lockstep race driven through HTTP + SSE: 3 bots register, connect and are started (in any order:
     * one connects only after the start), one attacks at decision 0, every decision is acked by everyone, and the
     * race finishes with its results and replay.
     */
    @Test
    fun fullRaceOverHttpAndSse() = liveKernelTest { api ->
        val raceId = api.createRace("long", seed = 3)
        val teams = listOf("A", "B", "C").map { api.register(raceId, it) }
        val attacker = teams[0].riders.first().id
        val bots = teams.mapIndexed { i, t ->
            TestBot(api, raceId, t) { d -> if (i == 0 && d.decisionId == 0) listOf(Order(attacker, RiderMode.ATTACK, 0.8, 60)) else emptyList() }
        }
        val mark = TimeSource.Monotonic.markNow()
        coroutineScope {
            val first = bots.take(2).map { launch { it.run() } }
            delay(300) // the first two streams are connected
            assertEquals(HttpStatusCode.OK, api.start(raceId).status)
            delay(300) // the race waits for the team that never connected
            val detail = api.getAs(ApiPaths.race(raceId), RaceDetail.serializer())
            assertEquals(RaceState.RUNNING, detail.state)
            assertEquals(0, detail.tick, "held at decision 0 until team C connects")
            val late = launch { bots[2].run() }
            (first + late).forEach { it.join() }
        }
        val elapsedS = mark.elapsedNow().inWholeMilliseconds / 1000.0

        // Same decisions for everyone, consecutive ids, all acked and accepted.
        val ids = bots[0].decisions.map { it.decisionId }
        assertEquals((0 until ids.size).toList(), ids)
        assertTrue(ids.size > 20, "decisions: ${ids.size}")
        for (b in bots) {
            assertTrue(b.finished, "${b.team.teamId} saw the finish")
            assertEquals(ids, b.decisions.map { it.decisionId })
            assertTrue(b.responses.all { (status, r) -> status == HttpStatusCode.OK && r.acked && r.rejected.isEmpty() }, "${b.responses.take(3)}")
        }
        // The attack was applied at decision 0 and reported at decision 1, to everyone.
        val attack = bots[1].decisions[1].events.filterIsInstance<RaceEvent.Attack>().single()
        assertEquals(attacker, attack.riderId)
        assertEquals(0, attack.tick)
        assertTrue(DecisionReason.ATTACK in bots[2].decisions[1].reasons)

        // Snapshot rate capped at ~10 per wall second (plus the first and last ones).
        for (b in bots) assertTrue(b.snapshots <= 10 * elapsedS + 3, "${b.snapshots} snapshots in $elapsedS s")
        assertTrue(bots.all { it.snapshots >= 2 && it.teamEvents.size == it.snapshots })
        assertTrue(bots[0].events.any { it is RaceEvent.RaceFinished })

        val results = api.getAs(ApiPaths.raceResults(raceId), Classification.serializer())
        assertTrue(results.final)
        assertEquals(21, results.entries.size)
        assertTrue(results.entries.all { it.status == RiderStatus.FINISHED && it.position != null })

        // Replay: plain JSON, or gzip on request.
        val replay = api.getAs(ApiPaths.raceReplay(raceId), Replay.serializer())
        assertEquals(results, replay.results)
        assertEquals(21, replay.riders.size)
        assertNull(replay.race.seed)
        assertTrue(replay.events.any { it is RaceEvent.Attack && it.riderId == attacker })
        val gz = api.client.get(ApiPaths.raceReplay(raceId)) { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals("gzip", gz.headers[HttpHeaders.ContentEncoding])
        val unzipped = GZipEncoder.decode(ByteReadChannel(gz.readRawBytes())).toByteArray().decodeToString()
        assertEquals(replay, ApiJson.decodeFromString(Replay.serializer(), unzipped))

        // A stream opened on a finished race gets the final state and closes.
        val late = api.stream(ApiPaths.raceStream(raceId))
        assertEquals(listOf(SseEvents.STATE, SseEvents.SNAPSHOT), late.map { it.event })
    }

    /** The `decision` payload: snapshot + own team's full state + events since the previous decision. */
    @Test
    fun decisionPayload() = liveKernelTest { api ->
        val raceId = api.createRace()
        val a = api.register(raceId, "A")
        val b = api.register(raceId, "B")
        val botA = TestBot(api, raceId, a, leaveAfter = 3)
        val botB = TestBot(api, raceId, b, leaveAfter = 3)
        coroutineScope {
            val jobs = listOf(launch { botA.run() }, launch { botB.run() })
            api.start(raceId)
            jobs.forEach { it.join() }
        }
        val d0 = botA.decisions[0]
        assertEquals(0, d0.decisionId)
        assertEquals(0, d0.tick)
        assertEquals(listOf(DecisionReason.RACE_STARTED), d0.reasons)
        assertEquals(listOf<RaceEvent>(RaceEvent.RaceStarted(0)), d0.events)
        assertEquals(14, d0.snapshot.riders.size, "public snapshot of the whole field")
        assertEquals(a.riders.map { it.id }, d0.team.map { it.riderId }, "only own riders' state")
        assertTrue(d0.team.all { it.cpEff > 0 && it.wPrimeBal > 0 && it.mode == RiderMode.FOLLOW && it.status == RiderStatus.RACING })
        assertEquals(b.riders.map { it.id }, botB.decisions[0].team.map { it.riderId })

        val d1 = botA.decisions[1]
        assertTrue(d1.tick in 5..30)
        assertTrue(d1.events.none { it is RaceEvent.RaceStarted }, "events since the previous decision only")
        assertEquals(d1.tick, d1.snapshot.tick)
        assertTrue(d1.team.all { it.energyKj > 0 })
        // The raw SSE id is the tick.
        val raw = botA.raw.first { it.event == SseEvents.DECISION && it.data!!.contains("\"decisionId\":1") }
        assertEquals(d1.tick.toString(), raw.id)
        // The team stream opens with `state`.
        assertEquals(SseEvents.STATE, botA.raw.first().event)
    }

    /** Order validation, stale decisions, idempotent repeats and frozen orders after the ack. */
    @Test
    fun ordersValidation() = liveKernelTest { api ->
        val raceId = api.createRace()
        val a = api.register(raceId, "A")
        val b = api.register(raceId, "B")
        val mine = a.riders[0].id
        val theirs = b.riders[0].id

        val early = api.orders(raceId, a, OrdersRequest(0, listOf(Order(mine, RiderMode.PULL, 0.9)), ack = true))
        assertEquals(HttpStatusCode.Conflict, early.first)
        assertEquals(RejectReason.RACE_NOT_RUNNING, early.second.rejected.single().reason)

        // Team B's bot acks decisions only once A is done with its checks (see below); A is driven by hand.
        val bot = TestBot(api, raceId, b, leaveAfter = 2)
        coroutineScope {
            val streamA = async { api.stream(ApiPaths.teamStream(raceId, a.teamId), a.token) { it.event == SseEvents.DECISION && it.data!!.contains("\"decisionId\":1") } }
            delay(200)
            api.start(raceId)
            delay(200)
            suspend fun send(vararg o: Order, decision: Int? = 0, ack: Boolean = false) = api.orders(raceId, a, OrdersRequest(decision, o.toList(), ack))

            val missing = send(Order(mine, RiderMode.PULL, 0.9), decision = null)
            assertEquals(HttpStatusCode.Conflict, missing.first)
            assertEquals(RejectReason.MISSING_DECISION, missing.second.rejected.single().reason)
            val stale = send(Order(mine, RiderMode.PULL, 0.9), decision = 5, ack = true)
            assertEquals(HttpStatusCode.Conflict, stale.first)
            assertEquals(RejectReason.STALE_DECISION, stale.second.rejected.single().reason)
            assertEquals(false, stale.second.acked)

            val (status, res) = send(
                Order(mine, RiderMode.PULL, 0.9),
                Order(theirs, RiderMode.PULL, 0.9),
                Order(999, RiderMode.PULL),
                Order(mine, RiderMode.PULL, 7.0),
                Order(a.riders[1].id, RiderMode.ATTACK, durationS = 0),
                Order(a.riders[2].id, RiderMode.FOLLOW, targetRiderId = a.riders[2].id),
            )
            assertEquals(HttpStatusCode.OK, status)
            assertEquals(listOf(Order(mine, RiderMode.PULL, 0.9)), res.accepted)
            assertEquals(
                listOf(RejectReason.NOT_OWN_RIDER, RejectReason.UNKNOWN_RIDER, RejectReason.INVALID_VALUE, RejectReason.INVALID_VALUE, RejectReason.INVALID_VALUE),
                res.rejected.map { it.reason },
            )
            assertEquals(false, res.acked)

            // Last order for a rider wins; the same body twice is idempotent, before and after the ack.
            val body = arrayOf(Order(mine, RiderMode.RECOVER), Order(a.riders[1].id, RiderMode.PULL, 1.0))
            val first = send(*body, ack = true)
            assertEquals(HttpStatusCode.OK to true, first.first to first.second.acked)
            assertEquals(first, send(*body, ack = true))
            val after = send(Order(mine, RiderMode.SPRINT))
            assertEquals(RejectReason.DECISION_ACKED, after.second.rejected.single().reason)
            assertTrue(after.second.acked)

            val view = api.getAs(ApiPaths.team(raceId, a.teamId), ucai.api.TeamView.serializer(), a.token)
            assertEquals(0, view.tick, "waiting for team B")
            launch { bot.run() }
            val eventsA = streamA.await()
            val d1 = eventsA.last()
            assertEquals(SseEvents.DECISION, d1.event)
            val stateAt1 = api.getAs(ApiPaths.team(raceId, a.teamId), ucai.api.TeamView.serializer(), a.token).states
            assertEquals(RiderMode.RECOVER, stateAt1.first { it.riderId == mine }.mode, "last order sent wins")
            assertEquals(RiderMode.PULL, stateAt1.first { it.riderId == a.riders[1].id }.mode)
            assertEquals(RejectReason.STALE_DECISION, send(Order(mine, RiderMode.PULL, 0.9), decision = 0).second.rejected.single().reason)
        }
    }

    /** A team whose stream closes is auto-acked with no orders (and logged); the race goes on to the finish. */
    @Test
    fun closedStreamIsAutoAcked() {
        val log = LogCapture()
        liveKernelTest(testConfig(log)) { api ->
            val raceId = api.createRace()
            val stays = TestBot(api, raceId, api.register(raceId, "Stays"))
            val leaves = TestBot(api, raceId, api.register(raceId, "Leaves"), leaveAfter = 3)
            coroutineScope {
                val jobs = listOf(launch { stays.run() }, launch { leaves.run() })
                delay(200)
                api.start(raceId)
                jobs.forEach { it.join() }
            }
            assertTrue(stays.finished)
            assertTrue(stays.decisions.size > 10)
            assertEquals(4, leaves.decisions.size, "left at decision 3 without acking it")
            assertTrue(log.lines.any { it.startsWith("WARN") && it.contains("team-02 auto-acked") }, log.lines.joinToString("\n"))
            assertTrue(api.getAs(ApiPaths.raceResults(raceId), Classification.serializer()).final)
        }
    }

    /** `wait=false`: teams without a stream are auto-acked from the start, the race runs on its own. */
    @Test
    fun startWithoutWaiting() = kernelTest { api ->
        val raceId = api.createRace()
        api.register(raceId, "Absent")
        assertEquals(HttpStatusCode.OK, api.start(raceId, wait = false).status)
        api.kernel.race(raceId)!!.awaitFinish()
        assertTrue(api.getAs(ApiPaths.raceResults(raceId), Classification.serializer()).final)
    }

    /** Realtime: one tick per (shortened) tick period, orders without a decision id apply at the next tick. */
    @Test
    fun realtime() = liveKernelTest { api ->
        val raceId = api.createRace(mode = ucai.api.ClockMode.REALTIME)
        val a = api.register(raceId, "A")
        assertEquals(HttpStatusCode.OK, api.start(raceId).status)
        val rider = a.riders[0].id
        val (status, res) = api.orders(raceId, a, OrdersRequest(null, listOf(Order(rider, RiderMode.PULL, 0.95))))
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(1, res.accepted.size)
        delay(300)
        val view = api.getAs(ApiPaths.team(raceId, a.teamId), ucai.api.TeamView.serializer(), a.token)
        assertTrue(view.tick in 2..20, "tick ${view.tick}")
        assertEquals(RiderMode.PULL, view.states.first { it.riderId == rider }.mode)
        val events = api.stream(ApiPaths.raceStream(raceId)) { it.event == SseEvents.SNAPSHOT && (it.id?.toInt() ?: 0) >= view.tick + 2 }
        assertEquals(SseEvents.STATE, events.first().event)
        assertNotNull(events.lastOrNull { it.event == SseEvents.SNAPSHOT })
    }

    @Test
    fun manyRacesInParallel() = liveKernelTest { api ->
        val ids = (1..3).map { api.createRace(seed = it.toLong()) }
        coroutineScope {
            ids.map { id ->
                async {
                    val bots = (1..2).map { TestBot(api, id, api.register(id, "T$it")) }
                    val jobs = bots.map { launch { it.run() } }
                    api.start(id)
                    jobs.forEach { it.join() }
                    bots.all { it.finished }
                }
            }.awaitAll()
        }.forEach { assertTrue(it) }
        val body = api.get(ApiPaths.races()).bodyAsText()
        assertEquals(3, Regex("\"finished\"").findAll(body).count())
    }
}
