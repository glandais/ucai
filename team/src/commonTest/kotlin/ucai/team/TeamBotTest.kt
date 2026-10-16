package ucai.team

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ucai.api.OrdersResponse
import ucai.api.RaceState
import ucai.api.RejectReason
import ucai.api.RejectedOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TeamBotTest {
    private val logs = mutableListOf<Pair<LogLevel, String>>()
    private val logger = BotLogger { level, msg -> logs += level to msg }

    /** Runs [body] against a started [kernel] on real dispatchers (real sockets), with a hard timeout. */
    private fun withKernel(kernel: FakeKernel, body: suspend CoroutineScope.(FakeKernel) -> Unit) = runTest(timeout = 30.seconds) {
        withContext(Dispatchers.Default) {
            kernel.start()
            try {
                withTimeout(20.seconds) { body(kernel) }
            } finally {
                kernel.stop()
            }
        }
    }

    @Test
    fun apiUrl() = assertEquals(
        "http://localhost:8080/api/v1",
        TeamBotConfig("http://localhost:8080/", "r", "t", "tok").apiUrl,
    )

    @Test
    fun answersEachDecisionOnceThenStopsOnRaceFinished() = withKernel(
        FakeKernel { s, _ ->
            with(s) {
                chatter(0)
                decision(1)
                awaitPost()
                chatter(10)
                decision(1) // a duplicate must not be answered twice
                decision(2)
                awaitPost()
                send(data = "{not json", event = "event") // unreadable non-decision events are ignored
                send(data = "{}", event = "something_new")
                decision(3)
                awaitPost()
                raceFinished(1000)
                awaitCancellation() // the bot must close the stream itself
            }
        },
    ) { k ->
        val result = TeamBot(k.config(), logger = logger).run()
        assertEquals(listOf(1, 2, 3), k.received.map { it.decisionId })
        assertTrue(k.received.all { it.ack })
        assertTrue(k.received.first().orders.isNotEmpty(), "the race_started decision gives every rider an order")
        assertTrue(k.received.first().orders.all { o -> k.mine.any { it.id == o.riderId } })
        assertEquals(3, result.decisions)
        assertEquals(1000, result.finishTick)
        assertEquals(0, result.reconnects)
        assertEquals(1, k.connections)
    }

    @Test
    fun stopsOnStateFinished() = withKernel(
        FakeKernel { s, _ ->
            with(s) {
                decision(1)
                awaitPost()
                state(RaceState.FINISHED, 500)
                awaitCancellation()
            }
        },
    ) { k ->
        val bot = TeamBot(k.config(), logger = logger)
        val run = async { bot.run() }
        bot.connected.await()
        val result = run.await()
        assertEquals(1, result.decisions)
        assertEquals(500, result.finishTick)
    }

    @Test
    fun reconnectsOnceOnADroppedStream() = withKernel(
        FakeKernel { s, connection ->
            with(s) {
                if (connection == 1) {
                    decision(1)
                    awaitPost()
                    return@FakeKernel // drop
                }
                decision(1) // the pending decision is re-sent on connect: already answered
                decision(2)
                awaitPost()
                state(RaceState.FINISHED, 900)
            }
        },
    ) { k ->
        val result = TeamBot(k.config(), logger = logger).run()
        assertEquals(listOf(1, 2), k.received.map { it.decisionId })
        assertEquals(1, result.reconnects)
        assertEquals(2, k.connections)
        assertTrue(logs.any { it.first == LogLevel.WARN && "reconnecting" in it.second })
    }

    @Test
    fun failsWhenTheStreamKeepsDropping() = withKernel(FakeKernel { _, _ -> }) { k ->
        val e = assertFailsWith<TeamBotException> { TeamBot(k.config(), logger = logger).run() }
        assertTrue("dropped" in e.message!!, e.message)
        assertEquals(2, k.connections)
    }

    @Test
    fun droppedStreamOfAFinishedRaceEndsTheRun() = withKernel(
        FakeKernel { s, _ ->
            with(s) {
                decision(1)
                awaitPost()
                raceState = RaceState.FINISHED // finished, but the stream closed before saying so
            }
        },
    ) { k ->
        val result = TeamBot(k.config(), logger = logger).run()
        assertEquals(1, result.decisions)
        assertEquals(1, k.connections)
    }

    @Test
    fun logsRejectedOrders() = withKernel(
        FakeKernel { s, _ ->
            with(s) {
                decision(1)
                awaitPost()
                raceFinished(10)
            }
        },
    ) { k ->
        k.ordersResponder = { call, req ->
            val (bad, good) = req.orders.partition { it.riderId == 1 }
            call.respondJson(
                OrdersResponse.serializer(),
                OrdersResponse(req.decisionId, good, bad.map { RejectedOrder(it, RejectReason.INVALID_VALUE, "too hard") }, true),
            )
        }
        val result = TeamBot(k.config(), logger = logger).run()
        assertEquals(1, result.ordersRejected)
        assertTrue(logs.any { it.first == LogLevel.WARN && "rejected" in it.second && "INVALID_VALUE" in it.second })
    }

    @Test
    fun failsLoudlyWhenOrdersCannotBePosted() = withKernel(
        FakeKernel { s, _ ->
            with(s) {
                decision(1)
                awaitCancellation()
            }
        },
    ) { k ->
        k.ordersResponder = { call, _ -> call.respondText("boom", status = HttpStatusCode.InternalServerError) }
        val bot = TeamBot(k.config { copy(postRetries = 2, retryDelay = 10.milliseconds) }, logger = logger)
        val e = assertFailsWith<TeamBotException> { bot.run() }
        assertTrue("500" in e.message!! && "decision 1" in e.message!!, e.message)
        assertEquals(3, k.received.size, "1 attempt + 2 retries")
        assertTrue(logs.any { it.first == LogLevel.ERROR })
    }

    @Test
    fun failsOnAnOrderRequestRefused() = withKernel(
        FakeKernel { s, _ ->
            with(s) {
                decision(1)
                awaitCancellation()
            }
        },
    ) { k ->
        k.ordersResponder = { call, _ -> call.respondText("bad orders", status = HttpStatusCode.BadRequest) }
        val e = assertFailsWith<TeamBotException> { TeamBot(k.config(), logger = logger).run() }
        assertTrue("400" in e.message!! && "bad orders" in e.message!!, e.message)
        assertEquals(1, k.received.size, "4xx is not retried")
    }

    @Test
    fun aStrategyFailureStillAcksTheDecision() = withKernel(
        FakeKernel { s, _ ->
            with(s) {
                decision(1)
                awaitPost()
                decision(2)
                awaitPost()
                raceFinished(700)
            }
        },
    ) { k ->
        val bot = TeamBot(k.config(), logger = logger, strategy = { Decider { error("no idea") } })
        val result = bot.run()
        assertEquals(listOf(1, 2), k.received.map { it.decisionId })
        assertTrue(k.received.all { it.ack && it.orders.isEmpty() })
        assertEquals(2, result.strategyErrors)
        assertTrue(logs.any { it.first == LogLevel.ERROR && "no idea" in it.second })
    }

    @Test
    fun aStrategyFailureCanFailTheRunAfterAcking() = withKernel(
        FakeKernel { s, _ ->
            with(s) {
                decision(1)
                awaitCancellation()
            }
        },
    ) { k ->
        val bot = TeamBot(
            k.config { copy(failOnStrategyError = true) }, logger = logger,
            strategy = { Decider { error("no idea") } },
        )
        assertFailsWith<TeamBotException> { bot.run() }
        assertEquals(1, k.received.size)
        assertTrue(k.received.single().ack)
    }

    @Test
    fun failsClearlyOnABadToken() = withKernel(FakeKernel { _, _ -> }) { k ->
        val bot = TeamBot(k.config { copy(token = "wrong") }, logger = logger)
        val e = assertFailsWith<TeamBotException> { bot.run() }
        assertTrue("401" in e.message!! && "team view" in e.message!!, e.message)
        assertFailsWith<TeamBotException> { bot.connected.await() }
        assertEquals(0, k.connections)
    }
}
