package ucai.engine.replay

import ucai.api.Classification
import ucai.api.CourseDetail
import ucai.api.FrameRider
import ucai.api.PublicRider
import ucai.api.RaceDetail
import ucai.api.RaceEvent
import ucai.api.RaceState
import ucai.api.Replay
import ucai.api.ReplayFormat
import ucai.api.ReplayFrame
import ucai.engine.race.Race
import ucai.engine.race.RaceObserver
import ucai.engine.race.RaceView
import kotlin.math.max
import kotlin.math.round

/**
 * Records a race into the replay v1 format (DESIGN §4 "Replay format"). Register it with [Race.addObserver]
 * before [Race.start]; once the race is finished, [replay] builds the [Replay].
 *
 * Adaptive frame step: a frame at tick 0, then every [stepS] s of race time (ticks on a multiple of the step),
 * every [fineStepS] s while the head of the race (riders still racing) is within [fineLastM] of the finish or for [fineAfterAttackS] s
 * after an attack, and always the last tick of the race. Rider arrays follow the race's start-list order.
 *
 * Distances are rounded to 0.1 m and speeds to 0.01 m/s to keep the file small.
 */
class ReplayRecorder(
    private val stepS: Int = ReplayFormat.STEP_S,
    private val fineStepS: Int = ReplayFormat.FINE_STEP_S,
    private val fineLastM: Double = ReplayFormat.FINE_LAST_M,
    private val fineAfterAttackS: Int = ReplayFormat.FINE_AFTER_ATTACK_S,
) : RaceObserver {
    init {
        require(stepS >= 1 && fineStepS >= 1) { "frame steps must be at least 1 s" }
    }

    private val frameList = mutableListOf<ReplayFrame>()
    private val eventList = mutableListOf<RaceEvent>()
    private var order: List<Int>? = null
    private var fineUntilTick = -1
    private var lastTick = -1
    private var finished = false

    /** Frames recorded so far. */
    val frames: List<ReplayFrame> get() = frameList

    /** Every event received so far, in emission order. */
    val events: List<RaceEvent> get() = eventList

    /** Rider ids in frame order (the race's start-list order); empty before the first tick. */
    val riderIds: List<Int> get() = order.orEmpty()

    override fun onTick(race: RaceView, events: List<RaceEvent>) {
        if (order == null) order = race.riders.map { it.id }
        eventList += events
        for (e in events) if (e is RaceEvent.Attack) fineUntilTick = max(fineUntilTick, e.tick + fineAfterAttackS)

        val t = race.tick
        if (t == lastTick) return
        // Head of the race still on the road: finished riders stand at the line and do not count.
        val head = race.riders.filter { it.status.onRoad }.maxOfOrNull { it.distM }
        val fine = (head != null && race.course.distanceM - head <= fineLastM) || t <= fineUntilTick
        val step = if (fine) fineStepS else stepS
        val last = race.state == RaceState.FINISHED
        if (t == 0 || last || t % step == 0) {
            frameList += ReplayFrame(t, race.riders.map { FrameRider(round1(it.distM), round2(it.speed), it.groupId, it.rankInGroup) })
            lastTick = t
        }
        if (last) finished = true
    }

    /**
     * Builds the replay. [detail] gives the race metadata, teams and public riders (any order: they are put in
     * frame order); [results] is the final classification and [course] the course to embed.
     */
    fun replay(detail: RaceDetail, results: Classification, course: CourseDetail): Replay {
        check(finished) { "the race is not finished" }
        val byId = detail.riders.associateBy { it.id }
        val ids = riderIds
        require(byId.keys == ids.toSet()) { "the race detail riders do not match the recorded riders" }
        val riders: List<PublicRider> = ids.map { byId.getValue(it) }
        return Replay(
            race = detail,
            course = course,
            teams = detail.teams,
            riders = riders,
            frames = frameList.toList(),
            events = eventList.toList(),
            results = results,
        )
    }

    /** Convenience for an engine [race]: its classification under [RaceDetail.id] and its full course. */
    fun replay(detail: RaceDetail, race: Race): Replay =
        replay(detail, race.classification(detail.id), race.course.toDetail())

    private companion object {
        fun round1(x: Double) = round(x * 10.0) / 10.0
        fun round2(x: Double) = round(x * 100.0) / 100.0
    }
}
