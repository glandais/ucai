package ucai.engine.race

import ucai.api.PrivateRiderState
import ucai.api.RiderMode
import ucai.api.RiderStatus
import ucai.engine.physics.RiderPhysiology

/** Live state of one rider. Mutated only by [Race]. */
internal class RiderState(val entrant: RaceEntrant, val index: Int, startSpeed: Double) : RiderView {
    val profile = entrant.profile
    val phys = RiderPhysiology(
        cp = profile.cp, wPrime = profile.wPrime, pMax = profile.pMax,
        massKg = profile.massKg, durability = profile.durability,
    )

    override val id: Int get() = entrant.id
    override val teamId: String get() = entrant.teamId
    override var distM = 0.0
    override var speed = startSpeed
    override var power = 0.0
    override val wPrimeBal: Double get() = phys.wPrimeBal
    override val cpEff: Double get() = phys.cpEff
    /** cpEff on the current terrain ([RiderPhysiology.cpNow]): intensities and caps in the race are fractions of it. */
    val cpNow: Double get() = phys.cpNow
    override val wPrimeEff: Double get() = phys.wPrimeEff
    override val energyKj: Double get() = phys.energyKj
    override var groupId = 0
    /** Current group; kept (stale) once the rider has finished. */
    lateinit var group: Group
    override var rankInGroup = 1
    override var mode = RiderMode.FOLLOW
    override var status = RiderStatus.RACING
    override var finishTimeS: Double? = null
    var position: Int? = null

    // Order parameters.
    var intensity = 0.0
    var maxIntensity = 1.0
    var targetRiderId: Int? = null
    var attackEndsTick = 0
    var attackFromGroupId = 0
    var sprintLaunched = false
    var positioningFactor = 1.0
    var targetLostReported = false
    var lastTargetGapM = Double.NaN

    /** Seeded positioning draw in [-1, 1], fixed for the race. */
    var positioningDraw = 0.0

    /** Tick of the last `pull` within the lead-out zone, or -1. */
    var lastPullInFinaleTick = -1

    // Group mechanics.
    /** True when riding in the body of its group (same distance and speed as the body). */
    var attached = true
    /** Consecutive ticks with a gap above the split threshold to the rider ahead in the group. */
    var gapTicks = 0

    // Per-tick scratch.
    var prevDistM = 0.0
    var newSpeed = 0.0
    var computed = false
    var aheadGapM = Double.POSITIVE_INFINITY

    /** On the road: [RiderStatus.RACING] or [RiderStatus.DROPPED] (a dropped rider still rides and takes orders). */
    val racing: Boolean get() = status.onRoad

    fun privateState() = PrivateRiderState(
        riderId = id, distM = distM, speed = speed, power = power,
        wPrimeBal = wPrimeBal, cpEff = cpEff, wPrimeEff = wPrimeEff, energyKj = energyKj,
        groupId = groupId, rankInGroup = rankInGroup, mode = mode, status = status,
    )
}

/**
 * A group: riders riding together. The attached riders form the *body*, which moves as one (same distance and
 * speed, draft by rank). Other members are detached: dropping off, chasing back, attacking or sprinting.
 */
internal class Group(override val id: Int) : GroupView {
    val members = mutableListOf<RiderState>()
    /** Attached riders, in rotation order (next turn first). */
    val queue = mutableListOf<RiderState>()
    var bodyDist = 0.0
    var bodySpeed = 0.0
    var prevBodyDist = 0.0
    var newBodySpeed = 0.0
    var leader: RiderState? = null
    var turnEndsTick = 0
    var bodyGapTicks = 0

    /** Members front first: detached ahead, then the body in rank order, then detached behind. */
    var ordered: List<RiderState> = emptyList()

    override val headDistM: Double get() = members.maxOf { it.distM }
    override val size: Int get() = members.size
    override val riderIds: List<Int> get() = ordered.map { it.id }

    val rearDistM: Double get() = members.minOf { it.distM }
    val front: RiderState get() = members.maxWith(compareBy<RiderState> { it.distM }.thenBy { -it.index })
}

/**
 * Split hysteresis (DESIGN §3 "Groups", rule 3): the counter of consecutive ticks above the threshold.
 * On descents the counter is frozen (no splits on descents in v1).
 */
object SplitHysteresis {
    fun next(counter: Int, gapS: Double, thresholdS: Double, frozen: Boolean): Int = when {
        frozen -> counter
        gapS > thresholdS -> counter + 1
        else -> 0
    }

    fun fires(counter: Int, ticks: Int): Boolean = counter >= ticks
}
