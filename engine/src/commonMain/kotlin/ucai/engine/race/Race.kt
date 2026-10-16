package ucai.engine.race

import ucai.api.Classification
import ucai.api.Climb
import ucai.api.DecisionEvent
import ucai.api.DecisionReason
import ucai.api.GroupSummary
import ucai.api.Order
import ucai.api.PrivateRiderState
import ucai.api.RaceEvent
import ucai.api.RaceState
import ucai.api.RejectReason
import ucai.api.RejectedOrder
import ucai.api.ResultEntry
import ucai.api.RiderMode
import ucai.api.RiderStatus
import ucai.api.Snapshot
import ucai.api.SnapshotRider
import ucai.api.Wind
import ucai.engine.course.Course
import ucai.engine.physics.PhysicsConstants
import ucai.engine.physics.RideConditions
import ucai.engine.physics.airDensity
import ucai.engine.physics.axialWind
import ucai.engine.physics.descentSpeedCap
import ucai.engine.physics.draftFactor
import ucai.engine.physics.eulerStep
import ucai.engine.physics.powerAtSpeed
import ucai.engine.physics.resistiveForce
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * One race simulation (DESIGN §3): fixed 1 s tick, fully deterministic given (course, start list, seed, wind,
 * orders per decision point). No I/O, no wall clock; all randomness comes from `Random(seed)`.
 *
 * Lockstep usage:
 * ```
 * var dp = race.start()                 // decision 0, tick 0
 * while (dp != null) {
 *     race.applyOrders(dp.decisionId, ordersByTeam)
 *     dp = race.runToNextDecision()     // null once the race is finished
 * }
 * ```
 * Realtime usage: [applyOrders] with `lockstep = false`, then [step] once per wall second.
 */
class Race(
    override val course: Course,
    entrants: List<RaceEntrant>,
    val seed: Long,
    val wind: Wind? = null,
    val config: RaceConfig = RaceConfig(),
) : RaceView {
    private val rng = Random(seed)
    private val all: List<RiderState>
    private val byId: Map<Int, RiderState>
    private val groupList = mutableListOf<Group>()
    private var nextGroupId = 1

    override var tick = 0
        private set
    override var state = RaceState.REGISTRATION
        private set
    override val riders: List<RiderView> get() = all
    override val groups: List<GroupView> get() = groupList
    private val eventLog = mutableListOf<RaceEvent>()
    override val events: List<RaceEvent> get() = eventLog

    private val observers = mutableListOf<RaceObserver>()
    private val sinceObserver = mutableListOf<RaceEvent>()
    private val sinceDecision = mutableListOf<RaceEvent>()
    private val pendingReasons = mutableSetOf<DecisionReason>()
    private var lastDecisionTick = 0
    private var decisionCounter = -1
    /** Last lockstep decision whose orders were applied (each decision is applied once). */
    private var appliedDecisionId = -1

    /** The decision point the race is paused at; null before [start] and once finished. */
    var currentDecision: DecisionPoint? = null
        private set

    private val kmMarks = RaceEvent.KM_TO_GO_MARKS.filter { it * 1000.0 < course.distanceM }.sortedDescending()
        .toMutableList()
    private val summits = course.climbs.filter { it.endM in 0.0..course.distanceM }.map { Summit(it) }
    private var finishedCount = 0

    private val finishM: Double get() = course.distanceM
    private val dt = PhysicsConstants.DT

    init {
        require(entrants.isNotEmpty()) { "a race needs riders" }
        require(entrants.map { it.id }.toSet().size == entrants.size) { "rider ids must be unique" }
        all = entrants.mapIndexed { i, e -> RiderState(e, i, config.startSpeed) }
        byId = all.associateBy { it.id }
        for (r in all) r.positioningDraw = rng.nextDouble(-1.0, 1.0)
        val peloton = newGroup()
        peloton.bodyDist = 0.0
        peloton.bodySpeed = config.startSpeed
        for (r in all) {
            peloton.members += r
            peloton.queue += r
            r.group = peloton
            r.groupId = peloton.id
        }
        assignRanks()
    }

    fun addObserver(observer: RaceObserver) {
        observers += observer
    }

    // ---------------------------------------------------------------------------------------------------------
    // Lockstep protocol
    // ---------------------------------------------------------------------------------------------------------

    /** Starts the race: emits `race_started` and returns decision 0 at tick 0. */
    fun start(): DecisionPoint {
        check(state == RaceState.REGISTRATION) { "race already started" }
        state = RaceState.RUNNING
        emit(RaceEvent.RaceStarted(tick))
        notifyObservers()
        return newDecision()
    }

    /**
     * Runs ticks until the next decision point (every [RaceConfig.decisionPeriodS] s, or after stable events,
     * at least [RaceConfig.decisionMinSpacingS] s after the previous one). Returns null once the race is finished.
     */
    fun runToNextDecision(): DecisionPoint? {
        check(state != RaceState.REGISTRATION) { "race not started" }
        while (state == RaceState.RUNNING) {
            step()
            if (state != RaceState.RUNNING) break
            val since = tick - lastDecisionTick
            if (since >= config.decisionMinSpacingS && (pendingReasons.isNotEmpty() || since >= config.decisionPeriodS)) {
                return newDecision()
            }
        }
        currentDecision = null
        return null
    }

    /** Runs to the finish, ignoring decision points (no orders). */
    fun runToFinish() {
        if (state == RaceState.REGISTRATION) start()
        while (runToNextDecision() != null) Unit
    }

    private fun newDecision(): DecisionPoint {
        decisionCounter++
        val since = tick - lastDecisionTick
        val reasons = pendingReasons.toMutableSet()
        if (reasons.isEmpty() || (decisionCounter > 0 && since >= config.decisionPeriodS)) reasons += DecisionReason.PERIODIC
        val dp = DecisionPoint(decisionCounter, tick, reasons.sortedBy { it.ordinal }, sinceDecision.toList())
        pendingReasons.clear()
        sinceDecision.clear()
        lastDecisionTick = tick
        currentDecision = dp
        return dp
    }

    /** Public snapshot (TV view): no private numbers. */
    fun snapshot(): Snapshot {
        val lead = groupList.firstOrNull()?.headDistM ?: 0.0
        return Snapshot(
            tick = tick,
            raceTimeS = raceTimeS,
            riders = all.map { SnapshotRider(it.id, it.distM, it.speed, it.groupId, it.rankInGroup) },
            groups = groupList.map { g ->
                val f = g.front
                GroupSummary(g.id, f.distM, g.size, (lead - f.distM) / max(1.0, f.speed))
            },
        )
    }

    /** Own riders' full live state for [teamId], in start-list order. */
    fun teamState(teamId: String): List<PrivateRiderState> =
        all.filter { it.teamId == teamId }.map { it.privateState() }

    /** The `decision` SSE payload for [teamId] at the current decision point, or null when not paused at one. */
    fun decisionEvent(teamId: String): DecisionEvent? {
        val dp = currentDecision ?: return null
        return DecisionEvent(dp.decisionId, dp.tick, dp.reasons, snapshot(), teamState(teamId), dp.events)
    }

    val teamIds: List<String> get() = all.map { it.teamId }.distinct().sorted()

    // ---------------------------------------------------------------------------------------------------------
    // Orders
    // ---------------------------------------------------------------------------------------------------------

    /** Validates one order of [teamId]; null when valid. In lockstep, [decisionId] must be the current one. */
    fun validate(teamId: String, decisionId: Int?, order: Order, lockstep: Boolean = true): RejectedOrder? {
        fun reject(reason: RejectReason, message: String? = null) = RejectedOrder(order, reason, message)
        if (state != RaceState.RUNNING) return reject(RejectReason.RACE_NOT_RUNNING)
        val current = currentDecision?.decisionId
        if (lockstep && decisionId == null) return reject(RejectReason.MISSING_DECISION)
        if (decisionId != null && decisionId != current) {
            return reject(RejectReason.STALE_DECISION, "current decision is $current")
        }
        val r = byId[order.riderId] ?: return reject(RejectReason.UNKNOWN_RIDER)
        if (r.teamId != teamId) return reject(RejectReason.NOT_OWN_RIDER)
        if (!r.racing) return reject(RejectReason.RIDER_NOT_RACING)
        valueError(order)?.let { return reject(RejectReason.INVALID_VALUE, it) }
        return null
    }

    /** Validates a batch without applying it (for the immediate HTTP response before every team has acked). */
    fun validateOrders(teamId: String, decisionId: Int?, orders: List<Order>, lockstep: Boolean = true): OrderResult {
        val accepted = mutableListOf<Order>()
        val rejected = mutableListOf<RejectedOrder>()
        for (o in orders) validate(teamId, decisionId, o, lockstep)?.let { rejected += it } ?: run { accepted += o }
        return OrderResult(accepted, rejected)
    }

    /**
     * Applies every team's orders for a decision, in canonical order: teams by `teamId`, then orders by `riderId`;
     * for the same rider the last valid order sent wins. The iteration order of [ordersByTeam] never matters.
     * In lockstep, a decision is applied at most once: a later call for the same decision only validates its orders
     * (no order applied, no event emitted), so a duplicate body is idempotent.
     */
    fun applyOrders(decisionId: Int?, ordersByTeam: Map<String, List<Order>>, lockstep: Boolean = true): Map<String, OrderResult> {
        // DESIGN §4: a decision's orders are applied together, once; a duplicate call is idempotent (validated only).
        val duplicate = lockstep && decisionId != null && decisionId == currentDecision?.decisionId &&
            decisionId == appliedDecisionId
        if (duplicate) return ordersByTeam.keys.sorted().associateWith { validateOrders(it, decisionId, ordersByTeam.getValue(it), lockstep) }
        if (lockstep && decisionId != null && decisionId == currentDecision?.decisionId) appliedDecisionId = decisionId
        val results = LinkedHashMap<String, OrderResult>()
        for (teamId in ordersByTeam.keys.sorted()) {
            val orders = ordersByTeam.getValue(teamId)
            val result = validateOrders(teamId, decisionId, orders, lockstep)
            val last = HashMap<Int, Order>()
            for (o in result.accepted) last[o.riderId] = o
            for (riderId in last.keys.sorted()) applyOrder(byId.getValue(riderId), last.getValue(riderId))
            results[teamId] = result
        }
        return results
    }

    private fun valueError(o: Order): String? {
        fun bad(x: Double?, range: ClosedFloatingPointRange<Double>) = x != null && (x.isNaN() || x !in range)
        return when (o.mode) {
            RiderMode.PULL -> if (bad(o.intensity, OrderLimits.PULL_INTENSITY)) "intensity must be in ${OrderLimits.PULL_INTENSITY}" else null
            RiderMode.ATTACK -> when {
                bad(o.intensity, OrderLimits.ATTACK_INTENSITY) -> "intensity must be in ${OrderLimits.ATTACK_INTENSITY}"
                o.durationS != null && o.durationS !in OrderLimits.ATTACK_DURATION_S -> "durationS must be in ${OrderLimits.ATTACK_DURATION_S}"
                else -> null
            }
            RiderMode.FOLLOW -> when {
                bad(o.maxIntensity, OrderLimits.FOLLOW_MAX_INTENSITY) -> "maxIntensity must be in ${OrderLimits.FOLLOW_MAX_INTENSITY}"
                o.targetRiderId == o.riderId -> "a rider cannot follow itself"
                o.targetRiderId != null && o.targetRiderId !in byId -> "unknown target rider ${o.targetRiderId}"
                else -> null
            }
            RiderMode.SPRINT, RiderMode.RECOVER -> null
        }
    }

    private fun applyOrder(r: RiderState, o: Order) {
        r.targetRiderId = null
        r.maxIntensity = OrderLimits.FOLLOW_MAX_DEFAULT
        r.sprintLaunched = false
        r.targetLostReported = false
        r.lastTargetGapM = Double.NaN
        r.mode = o.mode
        when (o.mode) {
            RiderMode.FOLLOW -> {
                r.targetRiderId = o.targetRiderId
                r.maxIntensity = o.maxIntensity ?: OrderLimits.FOLLOW_MAX_DEFAULT
            }
            RiderMode.PULL -> r.intensity = o.intensity ?: OrderLimits.PULL_DEFAULT
            RiderMode.ATTACK -> {
                r.intensity = o.intensity ?: OrderLimits.ATTACK_DEFAULT
                r.attackEndsTick = tick + (o.durationS ?: OrderLimits.ATTACK_DURATION_DEFAULT)
                r.attackFromGroupId = r.groupId
                if (r.attached) detach(r)
                emit(RaceEvent.Attack(tick, r.id, r.groupId, r.distM))
            }
            RiderMode.SPRINT, RiderMode.RECOVER -> Unit
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------------------------------------------------

    /** Advances the race by one tick (1 s). */
    fun step() {
        check(state == RaceState.RUNNING) { "race not running" }
        val active = all.filter { it.racing }

        // 0. Mode transitions and kernel safeguards.
        for (r in active) {
            r.computed = false
            when (r.mode) {
                RiderMode.ATTACK -> if (tick >= r.attackEndsTick || wPrimeEmpty(r)) {
                    r.mode = RiderMode.PULL
                    r.intensity = config.postAttackPullIntensity
                }
                RiderMode.SPRINT -> if (!r.sprintLaunched && finishM - r.distM <= config.sprintLaunchM) launchSprint(r)
                RiderMode.FOLLOW -> r.targetRiderId?.let { if (byId[it]?.racing != true) r.targetRiderId = null }
                else -> Unit
            }
            if (r.mode == RiderMode.PULL && finishM - r.distM <= config.leadOutZoneM) r.lastPullInFinaleTick = tick
        }

        // 1. Distance to the nearest rider ahead (start of tick), for the draft of detached riders.
        val sorted = active.sortedWith(BY_DIST_DESC)
        for (i in sorted.indices) {
            sorted[i].aheadGapM = if (i == 0) Double.POSITIVE_INFINITY else sorted[i - 1].distM - sorted[i].distM
        }

        // 2. Group bodies: pace setter, followers' required power, gaps opening.
        for (g in groupList) {
            g.queue.filter { wantsDetached(it, g) }.forEach { detach(it) }
            if (g.queue.isEmpty()) reseed(g)
            if (g.queue.isNotEmpty()) paceBody(g) else g.newBodySpeed = g.bodySpeed
        }

        // 3. Detached riders, front first (a target ahead is already computed).
        for (r in sorted) if (!r.attached && !r.computed) rideDetached(r)

        // 4. Move.
        for (g in groupList) {
            g.prevBodyDist = g.bodyDist
            if (g.queue.isNotEmpty()) {
                g.bodySpeed = g.newBodySpeed
                g.bodyDist += g.bodySpeed * dt
            }
        }
        for (r in active) {
            r.prevDistM = r.distM
            r.speed = r.newSpeed
            r.distM = if (r.attached) r.group.bodyDist else r.distM + r.speed * dt
            r.phys.tick(r.power, dt)
        }
        tick++

        // 5. Crossings: km to go, summits, finish (interpolated between ticks).
        crossings(active)

        // 6. Group topology: rejoin, split (hysteresis), merge.
        groupList.removeAll { it.members.isEmpty() }
        for (g in groupList.toList()) reattach(g)
        splits()
        merges()
        groupList.removeAll { it.members.isEmpty() }
        sortGroups()
        assignRanks()

        if (all.none { it.racing } || tick >= config.maxTicks) finishRace()
        notifyObservers()
    }

    private fun wPrimeEmpty(r: RiderState) = r.wPrimeBal <= config.wPrimeEmptyFraction * r.wPrimeEff

    private fun launchSprint(r: RiderState) {
        r.sprintLaunched = true
        val pulled = all.any { it !== r && it.teamId == r.teamId && it.lastPullInFinaleTick >= 0 }
        val x = (r.positioningDraw + if (pulled) config.leadOutPositioningBias else 0.0).coerceIn(-1.0, 1.0)
        r.positioningFactor = 1.0 + config.positioningSpread * x
    }

    private fun target(r: RiderState): RiderState? = r.targetRiderId?.let { byId[it] }?.takeIf { it.racing }

    /** Riders that must leave the body of [g]: attacking from it, sprinting, or chasing a target elsewhere. */
    private fun wantsDetached(r: RiderState, g: Group): Boolean = when (r.mode) {
        RiderMode.ATTACK -> r.attackFromGroupId == g.id
        RiderMode.SPRINT -> r.sprintLaunched
        RiderMode.FOLLOW -> {
            val t = target(r)
            t != null && !(t.group === g && t.attached) && t.distM >= r.distM
        }
        else -> false
    }

    private fun detach(r: RiderState) {
        r.attached = false
        r.group.queue.remove(r)
    }

    private fun attach(r: RiderState, g: Group) {
        r.attached = true
        r.distM = g.bodyDist
        r.speed = g.bodySpeed
        r.gapTicks = 0
        g.queue += r
    }

    /** Gives an empty body to the front-most member willing to ride in it. */
    private fun reseed(g: Group) {
        val cand = g.members.filter { !wantsDetached(it, g) }.maxWithOrNull(BY_DIST_THEN_INDEX)
        if (cand == null) {
            val f = g.front
            g.bodyDist = f.distM
            g.bodySpeed = f.speed
            g.prevBodyDist = f.prevDistM
            return
        }
        g.bodyDist = cand.distM
        g.bodySpeed = cand.speed
        g.prevBodyDist = cand.distM
        g.leader = null
        g.bodyGapTicks = cand.gapTicks
        attach(cand, g)
    }

    private fun conditions(r: RiderState, distM: Double, kDraft: Double): RideConditions {
        val p = course.points[course.segmentAt(distM)]
        val w = wind?.let { axialWind(it.fromDeg, it.speed, p.headingDeg) } ?: 0.0
        return RideConditions(r.profile.massKg, r.profile.cda, p.gradient, kDraft, w, airDensity(p.eleM))
    }

    /** Pedal power to go from [v] to [v1] in one Euler step (inverse of [eulerStep]), never negative. */
    private fun requiredPower(v: Double, v1: Double, c: RideConditions): Double {
        val f = c.massKg * (v1 - v) / dt + resistiveForce(v, c)
        return max(0.0, f * max(v, PhysicsConstants.MIN_SPEED_FOR_POWER) / PhysicsConstants.ETA)
    }

    private fun rotationEligible(r: RiderState) = r.mode == RiderMode.FOLLOW &&
        r.maxIntensity >= config.rotationMinIntensity &&
        r.wPrimeBal >= config.rotationMinWPrimeFraction * r.wPrimeEff

    /**
     * Next turn among the riders of the queue matching [eligible]: the leader keeps it until its turn ends. With
     * [strongest], only the riders for whom the front is cheapest take turns (see [strongHalf]).
     */
    private fun turn(g: Group, eligible: (RiderState) -> Boolean, strongest: Boolean = false): RiderState {
        val cur = g.leader
        if (cur != null && cur.attached && cur.group === g && eligible(cur) && tick < g.turnEndsTick) return cur
        if (cur != null && g.queue.remove(cur)) g.queue += cur
        val strong = if (strongest) strongHalf(g, eligible) else null
        val next = g.queue.firstOrNull { eligible(it) && (strong == null || it in strong) } ?: g.queue.first(eligible)
        g.leader = next
        g.turnEndsTick = tick + rng.nextInt(config.turnMinS, config.turnMaxS + 1)
        return next
    }

    /**
     * The default rotation is ridden by the stronger part of the group: riders ranked by cpEff over the power the
     * front costs them at the group's speed on the current gradient (W/kg on climbs, W/CdA on the flat); the best
     * [RaceConfig.rotationShare] of the eligible riders take turns, the others sit in. Computed at turn changes only.
     */
    private fun strongHalf(g: Group, eligible: (RiderState) -> Boolean): Set<RiderState> {
        val cand = g.queue.filter(eligible)
        if (cand.size <= 2) return cand.toSet()
        val vRef = max(g.bodySpeed, config.rotationRefSpeed)
        val ranked = cand.sortedByDescending { r ->
            r.cpEff / max(1.0, powerAtSpeed(vRef, conditions(r, g.bodyDist, 1.0)))
        }
        val n = max(2, ceil(cand.size * config.rotationShare).toInt())
        return ranked.take(n).toSet()
    }

    /** Default rotation intensity: harder on climbs (a 10–30 min climb is ridden near the 20-min power, above CP). */
    private fun rotationIntensity(g: Group): Double =
        if (course.gradientAt(g.bodyDist) >= config.climbGradient) config.climbIntensity else config.defaultIntensity

    private class Pace(val leader: RiderState, val power: Double, val setter: (RiderState) -> Boolean)

    private fun paceSetter(g: Group): Pace {
        val q = g.queue
        q.firstOrNull { it.mode == RiderMode.ATTACK }?.let { a ->
            g.leader = a
            return Pace(a, attackPower(a)) { it.mode == RiderMode.ATTACK }
        }
        val isPull: (RiderState) -> Boolean = { it.mode == RiderMode.PULL }
        if (q.any(isPull)) {
            val l = turn(g, isPull)
            return Pace(l, min(l.intensity * l.cpEff, l.phys.maxSustainablePower()), isPull)
        }
        val elig: (RiderState) -> Boolean = ::rotationEligible
        if (q.any(elig)) {
            val l = turn(g, elig, strongest = true)
            return Pace(l, min(min(rotationIntensity(g), l.maxIntensity) * l.cpEff, l.phys.maxSustainablePower()), elig)
        }
        val l = q.first()
        g.leader = l
        val intensity = when (l.mode) {
            RiderMode.RECOVER -> config.recoverSoloIntensity
            RiderMode.FOLLOW -> min(config.defaultIntensity, l.maxIntensity)
            else -> config.defaultIntensity
        }
        return Pace(l, min(intensity * l.cpEff, l.phys.maxSustainablePower())) { false }
    }

    /** Max power an attached rider spends to stay in the body. */
    private fun attachedCap(r: RiderState): Double {
        val maxSust = r.phys.maxSustainablePower(dt)
        return when (r.mode) {
            RiderMode.FOLLOW, RiderMode.SPRINT -> min(r.maxIntensity * r.cpEff, maxSust)
            RiderMode.PULL, RiderMode.ATTACK -> maxSust
            RiderMode.RECOVER -> min(config.recoverHoldIntensity * r.cpEff, maxSust)
        }
    }

    private fun paceBody(g: Group) {
        val grad = course.gradientAt(g.bodyDist)
        val descent = grad <= config.descentGradient
        val pace = paceSetter(g)
        val leader = pace.leader
        val rest = g.queue.filter { it !== leader }
        val body = ArrayList<RiderState>(g.queue.size)
        body += leader
        rest.filterTo(body) { pace.setter(it) }
        rest.filterTo(body) { !pace.setter(it) && it.mode != RiderMode.RECOVER }
        rest.filterTo(body) { !pace.setter(it) && it.mode == RiderMode.RECOVER }
        g.ordered = body

        val n = body.size
        val v = g.bodySpeed
        val c = conditions(leader, g.bodyDist, 1.0)
        val v1 = crawl(eulerStep(v, pace.power, c, dt, descentSpeedCap(leader.profile.descending, grad)))
        leader.power = min(pace.power, requiredPower(v, v1, c))
        leader.newSpeed = v1
        leader.computed = true

        val toBack = mutableListOf<RiderState>()
        for (i in 1 until n) {
            val r = body[i]
            r.computed = true
            if (descent) {
                r.power = 0.0
                r.newSpeed = v1
                continue
            }
            val cr = conditions(r, g.bodyDist, draftFactor(i + 1, n))
            val cap = attachedCap(r)
            val req = requiredPower(v, v1, cr)
            if (req <= cap) {
                r.power = req
                r.newSpeed = v1
                continue
            }
            // Can't hold it at this rank: go to the back of the rotation, or let the wheel go.
            val cb = cr.copy(kDraft = draftFactor(n, n))
            val reqBack = requiredPower(v, v1, cb)
            if (reqBack <= cap) {
                r.power = reqBack
                r.newSpeed = v1
                toBack += r
            } else {
                r.power = cap
                r.newSpeed = crawl(eulerStep(v, cap, cb, dt, descentSpeedCap(r.profile.descending, grad)))
                detach(r)
            }
        }
        for (r in toBack) if (g.queue.remove(r)) g.queue += r
        g.newBodySpeed = v1
    }

    /**
     * Stall guard: a racing rider never goes below [RaceConfig.crawlSpeed]. On the steepest ramps a cooked rider's
     * power can be below gravity even at the 1 m/s floor of the integration; he then zigzags or walks the bike up
     * instead of stopping for good.
     */
    private fun crawl(v: Double) = max(v, config.crawlSpeed)

    private fun attackPower(r: RiderState): Double {
        val remaining = max(1, r.attackEndsTick - tick)
        val allowed = min(r.cpEff + r.wPrimeBal / remaining, max(r.cpEff, r.phys.sprintPower()))
        return r.intensity * allowed
    }

    private fun proximityDraft(gapM: Double): Double {
        if (gapM.isInfinite()) return 1.0
        val second = draftFactor(2, 2)
        return second + (1.0 - second) * (gapM / config.draftFadeM).coerceIn(0.0, 1.0)
    }

    private fun closing(gapM: Double) = min(config.maxClosingSpeed, max(0.0, gapM) / config.closingTimeS)

    private fun rideDetached(r: RiderState) {
        val g = r.group
        val grad = course.gradientAt(r.distM)
        var c = conditions(r, r.distM, proximityDraft(r.aheadGapM))
        val vMax = descentSpeedCap(r.profile.descending, grad)
        val v = r.speed
        val maxSust = r.phys.maxSustainablePower(dt)
        val hasBody = g.queue.isNotEmpty()
        val refDist = if (hasBody) g.bodyDist else g.front.distM
        val refSpeed = if (hasBody) g.newBodySpeed else g.front.speed
        fun toward(target: Double) = requiredPower(v, min(target, v + PhysicsConstants.MAX_ACCEL * dt), c)

        val p = when (r.mode) {
            RiderMode.ATTACK -> attackPower(r)
            RiderMode.PULL -> min(r.intensity * r.cpEff, maxSust)
            RiderMode.RECOVER -> min(toward(refSpeed), config.recoverSoloIntensity * r.cpEff)
            RiderMode.SPRINT, RiderMode.FOLLOW -> if (r.mode == RiderMode.SPRINT && r.sprintLaunched) {
                if (leadOut(r)) c = c.copy(kDraft = min(c.kDraft, config.leadOutDraft))
                min(r.phys.sprintPower() * r.positioningFactor, r.cpEff + r.wPrimeBal / dt)
            } else {
                val cap = min(r.maxIntensity * r.cpEff, maxSust)
                val t = target(r)
                if (t != null && t.distM >= r.distM) {
                    val gap = t.distM - r.distM
                    val vt = if (t.computed) t.newSpeed else t.speed
                    val req = toward(vt + closing(gap))
                    if (req > cap && gap > config.attachM && gap > r.lastTargetGapM && !r.targetLostReported) {
                        r.targetLostReported = true
                        pendingReasons += DecisionReason.TARGET_LOST
                    }
                    r.lastTargetGapM = gap
                    min(req, cap)
                } else if (r.distM < refDist) {
                    min(toward(refSpeed + closing(refDist - r.distM)), cap)
                } else {
                    min(toward(refSpeed), min(cap, config.waitIntensity * r.cpEff))
                }
            }
        }
        val power = max(0.0, p)
        val v1 = crawl(eulerStep(v, power, c, dt, vMax))
        r.power = if (v1 >= vMax) min(power, requiredPower(v, v1, c)) else power
        r.newSpeed = v1
        r.computed = true
    }

    private fun leadOut(r: RiderState): Boolean = finishM - r.distM > config.leadOutEndM &&
        r.group.members.any { it !== r && it.teamId == r.teamId && it.mode == RiderMode.PULL }

    // ---------------------------------------------------------------------------------------------------------
    // Crossings
    // ---------------------------------------------------------------------------------------------------------

    private class Summit(val climb: Climb) {
        val passages = mutableListOf<RiderState>()
        var firstTick = -1
        var emitted = false
    }

    /** Interpolated crossing time of [atM] during the tick that just ran: t = t0 + (atM − dist0) / v. */
    private fun crossingTime(r: RiderState, atM: Double): Double =
        (tick - 1) + (atM - r.prevDistM) / max(r.speed, 1e-9)

    private fun crosses(r: RiderState, atM: Double) = r.prevDistM < atM && r.distM >= atM

    private val crossingOrder = compareBy<Pair<Double, RiderState>> { it.first }
        .thenBy { it.second.rankInGroup }.thenBy { it.second.index }

    private fun crossings(active: List<RiderState>) {
        val head = active.maxWith(BY_DIST_THEN_INDEX)
        while (kmMarks.isNotEmpty() && head.distM >= finishM - kmMarks.first() * 1000.0) {
            emit(RaceEvent.KmToGo(tick, kmMarks.removeAt(0), head.groupId))
        }

        for (s in summits) {
            if (s.emitted) continue
            val endM = s.climb.endM
            active.filter { crosses(it, endM) }.map { crossingTime(it, endM) to it }.sortedWith(crossingOrder)
                .forEach { s.passages += it.second }
            if (s.passages.isNotEmpty() && s.firstTick < 0) s.firstTick = tick
            val everyone = active.none { it.distM < endM }
            if (s.passages.isNotEmpty() &&
                (s.passages.size >= config.summitRiders || tick - s.firstTick >= config.summitWaitS || everyone)
            ) emitSummit(s)
        }

        val finishers = active.filter { crosses(it, finishM) }.map { crossingTime(it, finishM) to it }
            .sortedWith(crossingOrder)
        for ((time, r) in finishers) {
            r.finishTimeS = time
            r.position = ++finishedCount
            r.status = RiderStatus.FINISHED
            r.distM = finishM
            r.attached = false
            r.group.members.remove(r)
            r.group.queue.remove(r)
            // Standing at the line, out of every group (FrameRider / SnapshotRider sentinel: groupId 0).
            r.speed = 0.0
            r.newSpeed = 0.0
            r.groupId = ucai.api.FrameRider.FINISHED_GROUP
            r.rankInGroup = r.position!!
            emit(RaceEvent.RiderFinished(tick, r.id, r.position!!, time))
        }
    }

    private fun emitSummit(s: Summit) {
        s.emitted = true
        emit(RaceEvent.ClimbSummit(tick, s.climb.name, s.climb.endM, s.passages.take(config.summitRiders).map { it.id }))
    }

    // ---------------------------------------------------------------------------------------------------------
    // Group topology
    // ---------------------------------------------------------------------------------------------------------

    private fun newGroup(): Group = Group(nextGroupId++).also { groupList += it }

    /** Detached members willing to ride in the body rejoin it when level with it, or when it catches them. */
    private fun reattach(g: Group) {
        if (g.members.isEmpty()) return
        if (g.queue.isEmpty()) reseed(g)
        if (g.queue.isEmpty()) return
        for (r in g.members.sortedWith(BY_DIST_DESC)) {
            if (r.attached || wantsDetached(r, g)) continue
            val close = abs(r.distM - g.bodyDist) <= config.attachM && r.speed >= g.bodySpeed - 0.1
            val caught = r.prevDistM > g.prevBodyDist && r.distM <= g.bodyDist
            if (close || caught) attach(r, g)
        }
    }

    /** One position in a group: a detached rider, or the whole body ([rider] null). */
    private class Entry(val rider: RiderState?, val dist: Double, val speed: Double)

    private fun entries(g: Group): List<Entry> {
        val list = ArrayList<Entry>(g.members.size + 1)
        if (g.queue.isNotEmpty()) list += Entry(null, g.bodyDist, g.bodySpeed)
        for (r in g.members) if (!r.attached) list += Entry(r, r.distM, r.speed)
        list.sortWith(
            compareByDescending<Entry> { it.dist }.thenBy { if (it.rider == null) -1 else it.rider.index },
        )
        return list
    }

    private fun splits() {
        for (g in groupList.toList()) {
            if (g.members.isEmpty()) continue
            val e = entries(g)
            fun counter(x: Entry) = x.rider?.gapTicks ?: g.bodyGapTicks
            fun setCounter(x: Entry, c: Int) = if (x.rider == null) g.bodyGapTicks = c else x.rider.gapTicks = c
            setCounter(e[0], 0)
            val frozen = course.gradientAt(e[0].dist) <= config.descentGradient
            var at = -1
            var atGap = 0.0
            for (i in 1 until e.size) {
                val gap = (e[i - 1].dist - e[i].dist) / max(1.0, e[i].speed)
                val c = SplitHysteresis.next(counter(e[i]), gap, config.splitGapS, frozen)
                setCounter(e[i], c)
                if (at < 0 && SplitHysteresis.fires(c, config.splitTicks)) {
                    at = i
                    atGap = gap
                }
            }
            if (at > 0) split(g, e, at, atGap)
        }
    }

    private fun split(g: Group, e: List<Entry>, at: Int, gapS: Double) {
        val bodyIndex = e.indexOfFirst { it.rider == null }
        val rearMoves = bodyIndex < at
        val moved = (if (rearMoves) e.subList(at, e.size) else e.subList(0, at)).map { it.rider!! }
        val ng = newGroup()
        for (r in moved) {
            g.members.remove(r)
            ng.members += r
            r.group = ng
            r.groupId = ng.id
        }
        moved.first().gapTicks = 0
        reattach(ng)
        orderGroup(ng)
        emit(RaceEvent.GroupSplit(tick, g.id, ng.id, ng.ordered.map { it.id }, if (rearMoves) gapS else -gapS))
        if (rearMoves && moved.size <= 2) {
            for (r in ng.ordered) emit(RaceEvent.RiderDropped(tick, r.id, g.id, r.distM))
        }
    }

    private fun merges() {
        sortGroups()
        var i = 1
        while (i < groupList.size) {
            val a = groupList[i - 1]
            val b = groupList[i]
            val bf = b.front
            val gap = (a.rearDistM - bf.distM) / max(1.0, bf.speed)
            if (gap < config.mergeGapS) {
                // The bigger group absorbs the other (a catch keeps the chasing peloton's id).
                val (into, gone) = if (b.size > a.size) b to a else a to b
                merge(gone, into)
                groupList.remove(gone)
                if (i > 1) i--
            } else {
                i++
            }
        }
    }

    private fun merge(gone: Group, into: Group) {
        orderGroup(gone)
        val ids = gone.ordered.map { it.id }
        for (r in gone.members) {
            r.attached = false
            r.gapTicks = 0
            r.group = into
            r.groupId = into.id
            into.members += r
        }
        gone.members.clear()
        gone.queue.clear()
        emit(RaceEvent.GroupMerge(tick, gone.id, into.id, ids))
        reattach(into)
    }

    private fun sortGroups() {
        groupList.sortWith(compareByDescending<Group> { it.headDistM }.thenBy { it.id })
    }

    /** Front-first member order: detached ahead, body by rank (previous rank order, newcomers last), detached behind. */
    private fun orderGroup(g: Group) {
        val bodyRanked = g.ordered.filter { it.attached && it.group === g } + g.queue.filter { r -> g.ordered.none { it === r } }
        val out = ArrayList<RiderState>(g.members.size)
        for (e in entries(g)) if (e.rider == null) out += bodyRanked else out += e.rider
        g.ordered = out
    }

    private fun assignRanks() {
        for (g in groupList) {
            orderGroup(g)
            g.ordered.forEachIndexed { i, r -> r.rankInGroup = i + 1 }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Events, finish, results
    // ---------------------------------------------------------------------------------------------------------

    private fun emit(e: RaceEvent) {
        eventLog += e
        sinceObserver += e
        sinceDecision += e
        reasonOf(e)?.let { pendingReasons += it }
    }

    private fun reasonOf(e: RaceEvent): DecisionReason? = when (e) {
        is RaceEvent.RaceStarted -> DecisionReason.RACE_STARTED
        is RaceEvent.Attack -> DecisionReason.ATTACK
        is RaceEvent.GroupSplit -> DecisionReason.GROUP_SPLIT
        is RaceEvent.GroupMerge -> DecisionReason.GROUP_MERGE
        is RaceEvent.RiderDropped -> DecisionReason.RIDER_DROPPED
        is RaceEvent.ClimbSummit -> DecisionReason.CLIMB_SUMMIT
        is RaceEvent.KmToGo -> DecisionReason.KM_TO_GO
        is RaceEvent.RiderFinished, is RaceEvent.RaceFinished -> null
    }

    private fun finishRace() {
        state = RaceState.FINISHED
        for (s in summits) if (!s.emitted && s.passages.isNotEmpty()) emitSummit(s)
        all.firstOrNull { it.position == 1 }?.let { emit(RaceEvent.RaceFinished(tick, it.id, it.finishTimeS!!)) }
        currentDecision = null
    }

    private fun notifyObservers() {
        if (observers.isEmpty()) {
            sinceObserver.clear()
            return
        }
        val batch = sinceObserver.toList()
        sinceObserver.clear()
        for (o in observers) o.onTick(this, batch)
    }

    /**
     * Classification: finishers by crossing time (interpolated), with the UCI group time in `gapS` (a finisher less
     * than [RaceConfig.groupTimeGapS] behind the previous one gets its group time); then the others by distance.
     */
    fun results(): List<ResultEntry> {
        val finished = all.filter { it.position != null }.sortedBy { it.position }
        val out = ArrayList<ResultEntry>(all.size)
        var groupTime = 0.0
        var prev = Double.NEGATIVE_INFINITY
        val winner = finished.firstOrNull()?.finishTimeS ?: 0.0
        for (r in finished) {
            val t = r.finishTimeS!!
            if (t - prev >= config.groupTimeGapS) groupTime = t
            prev = t
            out += ResultEntry(r.id, r.teamId, r.status, r.position, t, groupTime - winner)
        }
        all.filter { it.position == null }.sortedWith(BY_DIST_DESC).forEach { out += ResultEntry(it.id, it.teamId, it.status) }
        return out
    }

    fun classification(raceId: String) = Classification(raceId, state == RaceState.FINISHED, results())

    private companion object {
        val BY_DIST_DESC = compareByDescending<RiderState> { it.distM }.thenBy { it.index }
        val BY_DIST_THEN_INDEX = compareBy<RiderState> { it.distM }.thenByDescending { it.index }
    }
}
