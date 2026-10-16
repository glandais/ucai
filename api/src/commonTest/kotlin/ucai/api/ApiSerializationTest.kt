package ucai.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiSerializationTest {
    private fun <T> roundTrip(serializer: KSerializer<T>, value: T): String {
        val json = ApiJson.encodeToString(serializer, value)
        assertEquals(value, ApiJson.decodeFromString(serializer, json), json)
        return json
    }

    private val profile = RiderProfile(72.0, 400.0, 22000.0, 1300.0, 0.32, 0.8, 0.6)
    private val ratings = RiderRatings(climbing = 4, punch = 3, sprint = 2, endurance = 5, descending = 3)
    private val privateRider = PrivateRider(7, "Ada Rossi", "t1", Archetype.ALL_ROUNDER, ratings, profile)
    private val riderState = PrivateRiderState(
        riderId = 7, distM = 1200.5, speed = 11.2, power = 310.0, wPrimeBal = 18000.0, cpEff = 395.0,
        wPrimeEff = 21500.0, energyKj = 450.0, groupId = 1, rankInGroup = 3,
        mode = RiderMode.FOLLOW, status = RiderStatus.RACING,
    )
    private val snapshot = Snapshot(
        tick = 120, raceTimeS = 120.0,
        riders = listOf(SnapshotRider(7, 1200.5, 11.2, 1, 3), SnapshotRider(8, 1210.0, 11.25, 1, 0)),
        groups = listOf(GroupSummary(1, 1210.0, 2, 0.0)),
    )

    @Test
    fun basePath() {
        assertEquals("/api/v1", ApiV1.BASE_PATH)
        assertEquals("/api/v1/races/r1/teams/t2/orders", ApiPaths.teamOrders("r1", "t2"))
        assertEquals(ApiPaths.BASE + ApiPaths.TEAM_ORDERS, "/api/v1/races/{raceId}/teams/{teamId}/orders")
    }

    @Test
    fun compactSnapshot() {
        val json = roundTrip(Snapshot.serializer(), snapshot)
        assertTrue("\"riders\":[[7,1200.5,11.2,1,3],[8,1210.0,11.25,1,0]]" in json, json)
    }

    @Test
    fun compactArraysIgnoreTrailingElements() {
        val decoded = ApiJson.decodeFromString(SnapshotRider.serializer(), "[3, 10, 9.5, 2, 1, 42, \"x\"]")
        assertEquals(SnapshotRider(3, 10.0, 9.5, 2, 1), decoded)
    }

    @Test
    fun courseDetail() {
        val course = CourseDetail(
            id = "milano-sanremo", name = "Milano-Sanremo", distanceM = 298700.0, elevationGainM = 2200.0,
            points = listOf(CoursePoint(45.46, 9.19, 120.0, 0.0), CoursePoint(45.461, 9.191, 121.5, 10.0)),
            climbs = listOf(Climb("Poggio", 292100.0, 295800.0, 0.037, 0.08)),
        )
        val json = roundTrip(CourseDetail.serializer(), course)
        assertTrue("\"points\":[[45.46,9.19,120.0,0.0],[45.461,9.191,121.5,10.0]]" in json, json)
        assertFalse("startName" in json, "nulls are omitted: $json")
        roundTrip(CourseSummary.serializer(), course.summary())
    }

    @Test
    fun raceDtos() {
        val create = CreateRaceRequest("il-lombardia", 42, ClockConfig(ClockMode.LOCKSTEP), Wind(270.0, 3.0))
        val json = roundTrip(CreateRaceRequest.serializer(), create)
        assertTrue("\"clock\":{\"mode\":\"lockstep\"}" in json, json)
        roundTrip(CreateRaceRequest.serializer(), create.copy(wind = null))
        roundTrip(CreateRaceResponse.serializer(), CreateRaceResponse("r1"))
        roundTrip(RaceSummary.serializer(), RaceSummary("r1", "il-lombardia", "Il Lombardia", RaceState.RUNNING, 10))
        val detail = RaceDetail(
            "r1", "il-lombardia", "Il Lombardia", RaceState.REGISTRATION, ClockConfig(ClockMode.REALTIME),
            teams = listOf(TeamInfo("t1", "Blue", "#1f77b4")), riders = listOf(privateRider.toPublic()),
        )
        roundTrip(RaceDetail.serializer(), detail)
    }

    @Test
    fun publicRaceDetailHidesSeedUntilFinished() {
        for (state in listOf(RaceState.REGISTRATION, RaceState.RUNNING)) {
            val json = roundTrip(RaceDetail.serializer(), RaceDetail("r1", "c", "Course", state, ClockConfig(ClockMode.LOCKSTEP)))
            assertFalse("seed" in json, json)
            assertFailsWith<IllegalArgumentException> {
                RaceDetail("r1", "c", "Course", state, ClockConfig(ClockMode.LOCKSTEP), seed = 42)
            }
        }
        val finished = RaceDetail("r1", "c", "Course", RaceState.FINISHED, ClockConfig(ClockMode.LOCKSTEP), seed = 42)
        assertTrue("\"seed\":42" in roundTrip(RaceDetail.serializer(), finished))
    }

    @Test
    fun teamDtos() {
        roundTrip(RegisterTeamRequest.serializer(), RegisterTeamRequest("Blue", "#1f77b4"))
        roundTrip(
            RegisterTeamRequest.serializer(),
            RegisterTeamRequest("Blue", "#1f77b4", listOf(RiderSpec("Ada Rossi", Archetype.CLIMBER, profile))),
        )
        val json = roundTrip(RegisterTeamResponse.serializer(), RegisterTeamResponse("t1", "secret", listOf(privateRider)))
        assertTrue("\"archetype\":\"all_rounder\"" in json, json)
        assertTrue("\"wPrime\":22000.0" in json, json)
        roundTrip(TeamView.serializer(), TeamView("r1", TeamInfo("t1", "Blue", "#1f77b4"), 5, listOf(privateRider), listOf(riderState)))
    }

    @Test
    fun publicRiderHasNoPrivateNumbers() {
        val json = ApiJson.encodeToString(PublicRider.serializer(), privateRider.toPublic())
        listOf("cp", "wPrime", "durability", "massKg", "pMax").forEach { assertFalse("\"$it\"" in json, json) }
    }

    @Test
    fun orders() {
        val request = OrdersRequest(
            decisionId = 3,
            orders = listOf(
                Order(7, RiderMode.ATTACK, intensity = 0.9, durationS = 20),
                Order(8, RiderMode.FOLLOW, targetRiderId = 12, maxIntensity = 1.05),
                Order(9, RiderMode.RECOVER),
            ),
            ack = true,
        )
        val json = roundTrip(OrdersRequest.serializer(), request)
        assertTrue("{\"riderId\":9,\"mode\":\"recover\"}" in json, json)
        assertEquals(OrdersRequest(), ApiJson.decodeFromString(OrdersRequest.serializer(), "{}"))
        roundTrip(
            OrdersResponse.serializer(),
            OrdersResponse(
                decisionId = 3, accepted = request.orders.take(2), acked = true,
                rejected = listOf(RejectedOrder(request.orders[2], RejectReason.STALE_DECISION, "current is 4")),
            ),
        )
    }

    @Test
    fun eventPolymorphism() {
        val events: List<RaceEvent> = listOf(
            RaceEvent.RaceStarted(0),
            RaceEvent.Attack(100, 7, 1, 5000.0),
            RaceEvent.GroupSplit(103, 1, 2, listOf(7), -3.5),
            RaceEvent.GroupMerge(400, 2, 1, listOf(7)),
            RaceEvent.RiderDropped(500, 9, 1, 8000.0),
            RaceEvent.ClimbSummit(900, "Poggio", 295800.0, listOf(7, 8)),
            RaceEvent.KmToGo(1000, 10, 1),
            RaceEvent.RiderFinished(25000, 7, 1, 24999.4),
            RaceEvent.RaceFinished(25100, 7, 24999.4),
        )
        val json = roundTrip(ListSerializer(RaceEvent.serializer()), events)
        listOf(
            "race_started", "attack", "group_split", "group_merge", "rider_dropped",
            "climb_summit", "km_to_go", "rider_finished", "race_finished",
        ).forEach { assertTrue("\"type\":\"$it\"" in json, "$it missing in $json") }

        val single = ApiJson.encodeToString(RaceEvent.serializer(), RaceEvent.KmToGo(1000, 10, 1))
        assertEquals("{\"type\":\"km_to_go\",\"tick\":1000,\"km\":10,\"leadGroupId\":1}", single)
        val withUnknown = "{\"type\":\"attack\",\"tick\":1,\"riderId\":2,\"fromGroupId\":0,\"distM\":5,\"future\":true}"
        assertEquals(RaceEvent.Attack(1, 2, 0, 5.0), ApiJson.decodeFromString(RaceEvent.serializer(), withUnknown))
    }

    @Test
    fun streamPayloads() {
        val json = roundTrip(StateEvent.serializer(), StateEvent(RaceState.FINISHED, 25100))
        assertEquals("{\"state\":\"finished\",\"tick\":25100}", json)
        roundTrip(TeamStateEvent.serializer(), TeamStateEvent(120, listOf(riderState)))
        val decision = DecisionEvent(
            decisionId = 4, tick = 120, reasons = listOf(DecisionReason.PERIODIC, DecisionReason.ATTACK),
            snapshot = snapshot, team = listOf(riderState), events = listOf(RaceEvent.Attack(118, 8, 1, 1200.0)),
        )
        val decisionJson = roundTrip(DecisionEvent.serializer(), decision)
        assertTrue("\"reasons\":[\"periodic\",\"attack\"]" in decisionJson, decisionJson)
        assertTrue("\"type\":\"attack\"" in decisionJson, decisionJson)
    }

    @Test
    fun replayAndResults() {
        val results = Classification(
            "r1", final = true,
            entries = listOf(
                ResultEntry(7, "t1", RiderStatus.FINISHED, 1, 24999.4, 0.0),
                ResultEntry(8, "t1", RiderStatus.ABANDONED),
            ),
        )
        roundTrip(Classification.serializer(), results)
        val replay = Replay(
            race = RaceDetail("r1", "c", "Course", RaceState.FINISHED, ClockConfig(ClockMode.LOCKSTEP), seed = 1),
            course = CourseDetail("c", "Course", 10.0, 0.0, points = listOf(CoursePoint(1.0, 2.0, 3.0, 0.0))),
            teams = listOf(TeamInfo("t1", "Blue", "#1f77b4")),
            riders = listOf(privateRider.toPublic()),
            frames = listOf(ReplayFrame(0, listOf(FrameRider(0.0, 0.0, 1, 0))), ReplayFrame(5, listOf(FrameRider(50.5, 11.0, 1, 0)))),
            events = listOf(RaceEvent.RaceStarted(0)),
            results = results,
        )
        val json = roundTrip(Replay.serializer(), replay)
        assertTrue(json.startsWith("{\"version\":1,"), json)
        assertTrue("{\"t\":5,\"r\":[[50.5,11.0,1,0]]}" in json, json)
    }
}
