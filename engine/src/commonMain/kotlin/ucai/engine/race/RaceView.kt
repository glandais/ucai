package ucai.engine.race

import ucai.api.RaceEvent
import ucai.api.RaceState
import ucai.api.RiderMode
import ucai.api.RiderStatus
import ucai.engine.course.Course

/** Read-only state of one rider, as seen by observers (recorder). Exact numbers included. */
interface RiderView {
    val id: Int
    val teamId: String
    val distM: Double
    val speed: Double
    val power: Double
    val wPrimeBal: Double
    val cpEff: Double
    val wPrimeEff: Double
    val energyKj: Double
    val groupId: Int
    val rankInGroup: Int
    val mode: RiderMode
    val status: RiderStatus
    /** Interpolated crossing time, s; null until finished. */
    val finishTimeS: Double?
}

/** Read-only state of one group. [headDistM] is the distance of its front rider. */
interface GroupView {
    val id: Int
    val headDistM: Double
    val size: Int
    val riderIds: List<Int>
}

/** Read-only view of a race, handed to observers each tick. */
interface RaceView {
    val course: Course
    val tick: Int
    val raceTimeS: Double get() = tick.toDouble()
    val state: RaceState
    /** All riders, in start-list order (stable for the whole race). */
    val riders: List<RiderView>
    /** Groups on the road, front first. */
    val groups: List<GroupView>
    /** Every event since the start, in emission order. */
    val events: List<RaceEvent>
    /**
     * Real time gap (s) of a point at [distM] behind the head of the race: how long ago the furthest rider (finished
     * riders included, so the winner once he is in) passed it. 0 at or ahead of the head. `GroupSummary.gapS`.
     */
    fun gapAt(distM: Double): Double
}

/**
 * Seam for the replay recorder: called once at the start (tick 0) and after every tick, with the events emitted
 * since the previous call. Events created when orders are applied at a decision point (`attack`) are delivered
 * with the next call; their `tick` is the decision tick.
 */
fun interface RaceObserver {
    fun onTick(race: RaceView, events: List<RaceEvent>)
}
