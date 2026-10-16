package ucai.team.strategy

import ucai.api.Archetype
import ucai.api.Climb
import ucai.api.CourseDetail
import ucai.api.DecisionEvent
import ucai.api.GroupSummary
import ucai.api.Order
import ucai.api.PrivateRider
import ucai.api.PrivateRiderState
import ucai.api.PublicRider
import ucai.api.RaceEvent
import ucai.api.RiderMode
import ucai.api.RiderStatus
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Team config knobs (DESIGN §5 "Strategy parameters"), so a batch can compare strategies.
 *
 * @property aggressiveness 0..1: how likely the leader attacks on the penultimate finale climb, how hard
 *   responders follow.
 * @property breakaway the team sends a rider in the early break.
 * @property control the team controls the gap to the break; null = decided from the public field (the 3 teams
 *   whose leader looks strongest for the course).
 * @property seed seeds the team's own draws (attack points), so a run is reproducible.
 */
data class StrategyParams(
    val aggressiveness: Double = 0.5,
    val breakaway: Boolean = false,
    val control: Boolean? = null,
    val seed: Long = 0,
)

/**
 * Every number of the v1 strategy, in one place (the engine's are in `ucai.engine.physics.Tuning`). Intensities are
 * fractions of cpEff. Tuned with the calibration harness (DESIGN §8): `./gradlew :runner:calibrate`.
 */
object Tactics {
    /** The early break goes between these distances from the start (seeded), attempts at most [BREAK_ATTEMPTS]. */
    const val BREAK_FROM_MIN_M = 3_000.0
    const val BREAK_FROM_MAX_M = 15_000.0
    const val BREAK_WINDOW_END_M = 80_000.0
    const val BREAK_ATTEMPTS = 2
    const val BREAK_ATTACK_INTENSITY = 0.8
    const val BREAK_ATTACK_S = 60
    /** Break riders' turns, and in the last 30 km (the peloton's chase must be faster: it shares the work). */
    const val BREAK_PULL = 0.95
    const val BREAK_PULL_LATE = 1.0
    const val BREAK_PULL_SPENT = 0.9
    /** A break rider ahead of another break group less than [BREAK_WAIT_S] behind eases to [BREAK_WAIT] so they join. */
    const val BREAK_WAIT_S = 60.0
    const val BREAK_WAIT = 0.75

    /** Control: target gap (s) = [CONTROL_S_PER_KM] × km to go, clamped; chase until back under [CONTROL_RELEASE] × target. */
    const val CONTROL_S_PER_KM = 1.3
    const val CONTROL_MIN_GAP_S = 20.0
    const val CONTROL_MAX_GAP_S = 240.0
    const val CONTROL_RELEASE = 0.6
    /** In the last [CONTROL_CATCH_KM] km the controlling teams chase whenever a break is ahead: they want it back. */
    const val CONTROL_CATCH_KM = 30.0
    /**
     * Tempo while the break builds its lead (below the break's [BREAK_PULL], so it gets minutes); [CONTROL_HOLD] by one
     * domestique once it is established and the gap is in the band, else the peloton falls back to the faster
     * default rotation and chases it down; chase above the target gap (by km to go: > 100, > 40, less).
     */
    const val CONTROL_TEMPO = 0.85
    const val CONTROL_HOLD = 0.92
    /**
     * In the band, the hold pull follows the gap: [CONTROL_HOLD] at [CONTROL_HOLD_AT] × target, [CONTROL_HOLD_GAIN]
     * more per target of gap above, within [CONTROL_HOLD_MIN]..[CONTROL_HOLD_MAX]; so the gap stays in the band.
     */
    const val CONTROL_HOLD_AT = 0.8
    const val CONTROL_HOLD_GAIN = 0.5
    const val CONTROL_HOLD_MIN = 0.82
    const val CONTROL_HOLD_MAX = 1.05
    /**
     * Before the finale, control pulls on a climb are capped at this (no [CLIMB_BONUS]): in the draft on the flat
     * anyone holds a 1.05 pull, on a climb a weaker domestique would be dropped.
     */
    const val CONTROL_CLIMB_CAP = 0.95
    const val CONTROL_CHASE_EARLY = 1.05
    const val CONTROL_CHASE_MID = 1.1
    const val CONTROL_CHASE_LATE = 1.2
    /** Teams that control when [StrategyParams.control] is null: those whose leader looks strongest. */
    const val CONTROL_TEAMS = 3

    /** Finale climbs: summits within this distance of the finish. */
    const val FINALE_WINDOW_M = 60_000.0
    /** Domestiques start pulling this far before the foot of a finale climb, stop this far before the summit. */
    const val CLIMB_FOOT_LEAD_M = 1_500.0
    const val CLIMB_STOP_BEFORE_TOP_M = 200.0
    /** Above the default rotation on climbs (1.1), else a pull would slow the group down. */
    const val CLIMB_PULL = 1.15
    const val CLIMB_PULL_SPRINT_COURSE = 1.1

    /** Puncheur-style attacks go between these distances before the summit (seeded, at most 70 % of the climb). */
    const val ATTACK_MIN_BEFORE_TOP_M = 300.0
    const val ATTACK_MAX_BEFORE_TOP_M = 2_500.0
    /** Chance that a leader at its attack point waits for the next decision point instead. */
    const val ATTACK_HESITATION = 0.5
    /** Leader attacks need this W′bal fraction. */
    const val ATTACK_MIN_W = 0.5
    const val RESPONSE_MIN_W = 0.45
    const val RESPONSE_S = 120
    /** Attacks are followed only this close to the finish, unless the attacker is a rival leader. */
    const val RESPONSE_WINDOW_M = 80_000.0

    /** Chasing / riding a winning move: within this distance of the finish, from this gap (in the all-out zone: less). */
    const val CHASE_WINDOW_M = 50_000.0
    const val CHASE_MIN_GAP_S = 10.0
    const val CHASE_MIN_GAP_FINALE_S = 3.0
    /** Helpers' turns in a chase: above 1, since several share the front. */
    const val CHASE_PULL = 1.1
    /** In the last [CHASE_ALL_OUT_M], up to 3 helpers with W′ left chase at [CHASE_PULL_ALL_OUT]. */
    const val CHASE_ALL_OUT_M = 8_000.0
    const val CHASE_PULL_ALL_OUT = 1.3
    /** A front group this small rides for itself: the leader takes turns at [LEADER_PULL]. */
    const val SMALL_GROUP = 10
    const val LEADER_PULL = 0.92

    /** Lead-out from this distance to go, at this intensity; everyone with a chance sprints from [SPRINT_ARM_M]. */
    const val LEAD_OUT_FROM_M = 2_500.0
    const val LEAD_OUT_PULL = 1.2
    const val SPRINT_ARM_M = 1_100.0

    /** Spent: cpEff below this fraction of CP, or W′bal below this fraction of wPrimeEff. */
    const val SPENT_CP = 0.85
    const val SPENT_W = 0.15
    /** Domestiques still work (control, climbs) down to this cpEff fraction of CP, below [SPENT_CP]: it's their job. */
    const val WORKER_MIN_CP = 0.75
    /**
     * A spent rider dropped behind the peloton, this far behind it or in a group of 3 or less, is out of the race:
     * he waits (`follow` at [GRUPPETTO_WAIT]) while another group is less than [GRUPPETTO_BEHIND_S] behind, so the
     * stragglers ride home together; with nobody behind he rides home in `pull` at [GRUPPETTO_PULL]. Sitting in the
     * peloton, he `recover`s.
     */
    const val GRUPPETTO_GAP_S = 120.0
    const val GRUPPETTO_BEHIND_S = 120.0
    const val GRUPPETTO_WAIT = 1.0
    const val GRUPPETTO_PULL = 1.0

    /**
     * Pulls (break, chase; control only in the last [FINALE_WINDOW_M]) get [CLIMB_BONUS] more intensity where the next
     * [GRADE_AHEAD_M] climb ≥ [CLIMB_GRADE]. Early control rides climbs at its flat tempo, or domestiques are
     * dropped on the first climb.
     */
    const val CLIMB_GRADE = 0.03
    const val CLIMB_BONUS = 0.1
    const val GRADE_AHEAD_M = 500.0

    /** `follow` cap (fraction of cpEff) to stay in the group: above 1, a rider digs into W′ to hang on. */
    const val SIT_IN_MAX_PROTECTED = 1.3
    const val SIT_IN_MAX_DOMESTIQUE = 1.1
    /** A domestique who lost too much CP (spent) but still has W′: he hangs on up to this, and `recover`s when W′ is low. */
    const val SIT_IN_MAX_SPENT = 1.0
}

/**
 * The team v1 strategy (DESIGN §5) as a pure function of `decision` events: everything comes from the API types,
 * so the same object drives the HTTP bot and the calibration harness. One instance per team and race; it keeps a
 * little memory between decisions (orders sent, attacks made, who is pulling).
 *
 * Rules, highest priority first (a rider gets the first order that applies):
 * 1. finale: lead-out pull for the sprinter, `sprint` for contenders (in any group) in the last ~1 km;
 * 2. leader attacks on the last 1–2 finale climbs (puncheurs late, climbers mid-climb on long climbs) when W′bal
 *    allows, then rides on with the move;
 * 3. responses: when a rival leader (or anyone in the finale) attacks from the leader's group, the leader or the
 *    lieutenant follows with `targetRiderId` and `maxIntensity`;
 * 4. early breakaway rider (seeded teams);
 * 5. domestiques pull at the foot of the finale climbs;
 * 6. chase when the leader is behind in the finale (all out in the last 8 km), or ride the move when the leader is
 *    in a small front group;
 * 7. control the break (strongest teams): easy tempo while it goes, then one domestique holds the gap in a band
 *    under a target shrinking towards the finish, chasing above it (and always in the last 30 km);
 * 8. spent domestiques hang on in the wheels (`recover` when W′ is low); once dropped behind the peloton they wait
 *    for the stragglers behind and ride home together; everyone else `follow`s with a `maxIntensity` above 1 so
 *    they dig into W′ to stay in the group.
 *
 * Pulls on climbs get [Tactics.CLIMB_BONUS] more intensity, like the engine's default rotation, but only in the finale
 * for the break and the control: early climbs are ridden at tempo.
 */
class TeamStrategy(
    val teamId: String,
    val course: CourseDetail,
    field: List<PublicRider>,
    own: List<PrivateRider>,
    val params: StrategyParams = StrategyParams(),
) {
    val kind: FinaleKind = FinaleKind.of(course)
    val roles: TeamRoles = TeamRoles.assign(own, kind)
    private val reading = FieldReading(field, kind)
    private val ownById = own.associateBy { it.id }
    private val rivalLeaders = reading.leaderIds - own.map { it.id }.toSet()
    val controls: Boolean = params.control ?: (reading.rankOf(teamId) <= Tactics.CONTROL_TEAMS)
    private val finishM = course.distanceM
    private val finale: List<Climb> = finaleClimbs(course, Tactics.FINALE_WINDOW_M)

    private val rng = Random(params.seed)
    private val breakFromM = rng.nextDouble(Tactics.BREAK_FROM_MIN_M, Tactics.BREAK_FROM_MAX_M)
    /** Per finale climb: where the leader attacks, and whether it attacks there at all. */
    private val attackPlan: List<Pair<Double, Boolean>> = finale.mapIndexed { i, c ->
        val len = c.endM - c.startM
        val long = len >= 3_000.0
        val climberStyle = roles.leader.archetype == Archetype.CLIMBER ||
            (roles.leader.archetype == Archetype.ALL_ROUNDER && kind == FinaleKind.CLIMBING)
        val lo = Tactics.ATTACK_MIN_BEFORE_TOP_M
        val hi = max(lo + 100.0, min(len * 0.7, Tactics.ATTACK_MAX_BEFORE_TOP_M))
        val at = if (climberStyle && long) c.startM + len * rng.nextDouble(0.3, 0.7) else c.endM - rng.nextDouble(lo, hi)
        val last = i == finale.lastIndex
        val penultimate = i == finale.lastIndex - 1
        val go = last || (penultimate && rng.nextDouble() < params.aggressiveness)
        at to go
    }

    private val lastSent = HashMap<Int, Order>()
    private var breakAttempts = 0
    private var breakLastTick = -1_000
    private val attackedClimbs = mutableSetOf<Int>()
    private var leaderAttack: Pair<Order, Int>? = null
    private var response: Response? = null
    private val pullers = mutableSetOf<Int>()

    private class Response(val riderId: Int, val targetId: Int, val untilTick: Int)

    /** Orders for this decision point: only those that change something since the last ones sent. */
    fun decide(ev: DecisionEvent): List<Order> {
        val v = View(ev)
        val want = LinkedHashMap<Int, Order>()
        fun assign(id: Int, o: Order) {
            if (v.racing(id) && id !in want) want[id] = o
        }

        finale(v, ::assign)
        leaderAttack(v, ev.events, ::assign)
        responses(v, ev.events, ::assign)
        breakaway(v, ::assign)
        climbWork(v, want.keys, ::assign)
        chase(v, want.keys, ::assign)
        control(v, want.keys, ::assign)
        for (s in v.mine) {
            if (s.status != RiderStatus.RACING) continue
            val role = roles.role(s.riderId)
            val spentRole = role == Role.DOMESTIQUE || role == Role.LIEUTENANT
            if (spentRole && v.spent(s.riderId)) {
                // Spent: sit in and accept being dropped; once out of the race, ride home with the stragglers.
                val g = v.group(s.riderId)
                val pel = v.peloton
                val behindPeloton = g != null && pel != null && g.id != pel.id && g.headDistM < pel.headDistM
                val outOfRace = g == null ||
                    (behindPeloton && (g.gapS - pel!!.gapS > Tactics.GRUPPETTO_GAP_S || g.size <= 3))
                val order = when {
                    // Tired legs (CP lost) but W′ left: hang on in the wheels, just don't work.
                    !outOfRace && v.w(s.riderId) >= Tactics.SPENT_W ->
                        Order(s.riderId, RiderMode.FOLLOW, maxIntensity = Tactics.SIT_IN_MAX_SPENT)
                    !outOfRace -> Order(s.riderId, RiderMode.RECOVER)
                    v.groupBehindWithin(g, Tactics.GRUPPETTO_BEHIND_S) ->
                        Order(s.riderId, RiderMode.FOLLOW, maxIntensity = Tactics.GRUPPETTO_WAIT)
                    else -> pull(s.riderId, Tactics.GRUPPETTO_PULL)
                }
                assign(s.riderId, order)
            }
            val sitIn = if (role == Role.DOMESTIQUE) Tactics.SIT_IN_MAX_DOMESTIQUE else Tactics.SIT_IN_MAX_PROTECTED
            assign(s.riderId, Order(s.riderId, RiderMode.FOLLOW, maxIntensity = sitIn))
        }
        pullers.retainAll { want[it]?.mode == RiderMode.PULL }

        val out = want.values.filter { lastSent[it.riderId] != it }
        for (o in out) lastSent[o.riderId] = o
        return out
    }

    // ---------------------------------------------------------------------------------------------------------

    private inner class View(ev: DecisionEvent) {
        val tick = ev.tick
        val mine: List<PrivateRiderState> = ev.team
        private val state = ev.team.associateBy { it.riderId }
        val groups: List<GroupSummary> = ev.snapshot.groups
        private val groupById = groups.associateBy { it.id }
        val head: GroupSummary? = groups.firstOrNull()
        val peloton: GroupSummary? = groups.maxWithOrNull(compareBy<GroupSummary> { it.size }.thenBy { -it.headDistM })

        fun racing(id: Int) = state[id]?.status == RiderStatus.RACING
        fun state(id: Int): PrivateRiderState = state.getValue(id)
        fun group(id: Int): GroupSummary? = state[id]?.let { groupById[it.groupId] }
        fun toGo(id: Int) = finishM - state(id).distM
        fun w(id: Int): Double = state(id).let { if (it.wPrimeEff <= 0.0) 0.0 else it.wPrimeBal / it.wPrimeEff }
        fun cpFrac(id: Int): Double = state(id).cpEff / ownById.getValue(id).profile.cp
        fun spent(id: Int) = cpFrac(id) < Tactics.SPENT_CP || w(id) < Tactics.SPENT_W
        fun teammatesIn(groupId: Int, except: Set<Int>) =
            mine.filter { it.status == RiderStatus.RACING && it.groupId == groupId && it.riderId !in except }
        val pelotonIsHead get() = peloton != null && peloton.id == head?.id

        /** Another group less than [s] behind [g] (false without a group). */
        fun groupBehindWithin(g: GroupSummary?, s: Double): Boolean {
            val gap = g?.gapS ?: return false
            return groups.any { it.id != g.id && it.headDistM < g.headDistM && it.gapS - gap < s }
        }

        /** The rider the team rides for now: the leader, else the lieutenant, else the sprinter, else anyone. */
        val leaderId: Int? = listOfNotNull(roles.leader.id, roles.lieutenant?.id, roles.sprinter.id)
            .firstOrNull { racing(it) } ?: mine.firstOrNull { it.status == RiderStatus.RACING }?.riderId
    }

    private fun pull(id: Int, intensity: Double) = Order(id, RiderMode.PULL, intensity = intensity)

    /** 1. Lead-out and sprint. */
    private fun finale(v: View, assign: (Int, Order) -> Unit) {
        val head = v.head ?: return
        for (s in v.mine) {
            val id = s.riderId
            if (!v.racing(id) || v.toGo(id) > Tactics.SPRINT_ARM_M) continue
            val g = v.group(id) ?: continue
            val role = roles.role(id)
            val contender = role == Role.LEADER || role == Role.SPRINTER || role == Role.LIEUTENANT
            // Contenders sprint in every group (for the win or the places), everyone does in a small group.
            if (contender || (g.id == head.id && g.size <= Tactics.SMALL_GROUP)) assign(id, Order(id, RiderMode.SPRINT))
        }
        // Lead-out: one fresh teammate pulls for the finisher in a big front group.
        val finisher = listOf(roles.sprinter.id, roles.leader.id).firstOrNull { v.racing(it) } ?: return
        val g = v.group(finisher) ?: return
        val toGo = v.toGo(finisher)
        if (g.id != head.id || g.size < Tactics.SMALL_GROUP || toGo > Tactics.LEAD_OUT_FROM_M || toGo < 400.0) return
        if (roles.sprinter.id == finisher && v.toGo(finisher) <= Tactics.SPRINT_ARM_M) assign(finisher, Order(finisher, RiderMode.SPRINT))
        val man = v.teammatesIn(g.id, setOf(finisher, roles.leader.id)).filter { v.w(it.riderId) > 0.25 }
            .maxByOrNull { ownById.getValue(it.riderId).profile.pMax }
        if (man != null) assign(man.riderId, pull(man.riderId, Tactics.LEAD_OUT_PULL))
    }

    /** 2. Leader attacks on the last finale climbs, then rides on with the move. */
    private fun leaderAttack(v: View, events: List<RaceEvent>, assign: (Int, Order) -> Unit) {
        val id = v.leaderId ?: return
        if (id != roles.leader.id) return
        leaderAttack?.let { (order, until) ->
            if (v.tick < until) return assign(id, order)
            leaderAttack = null
        }
        if (!roles.leader.archetype.attacksOnClimbs) return
        val d = v.state(id).distM
        val g = v.group(id) ?: return
        for ((i, c) in finale.withIndex()) {
            val (at, go) = attackPlan[i]
            if (!go || i in attackedClimbs || d < at || d >= c.endM - 100.0) continue
            // A rival just went from this group: follow first (responses), counter at the next decision point.
            val rivalWent = events.any { it is RaceEvent.Attack && it.fromGroupId == g.id && !ownById.containsKey(it.riderId) }
            if (rivalWent) continue
            // Not everyone goes on the same pedal stroke: wait for a later decision point now and then.
            if (rng.nextDouble() < Tactics.ATTACK_HESITATION) continue
            attackedClimbs += i
            if (v.w(id) < Tactics.ATTACK_MIN_W || g.size < 3) continue
            val climberStyle = (c.endM - c.startM) >= 3_000.0 && at < c.endM - 1_000.0
            val duration = if (climberStyle) min(120, max(30, ((c.endM - d) / 6.0).toInt())) else 30
            val o = Order(id, RiderMode.ATTACK, intensity = 0.9 + 0.1 * params.aggressiveness, durationS = duration)
            leaderAttack = o to (v.tick + duration)
            return assign(id, o)
        }
    }

    /** 3. Follow attacks from the leader's group. */
    private fun responses(v: View, events: List<RaceEvent>, assign: (Int, Order) -> Unit) {
        response?.let { r ->
            val alive = v.tick < r.untilTick && v.racing(r.riderId) && v.w(r.riderId) >= Tactics.SPENT_W
            if (alive) {
                assign(r.riderId, Order(r.riderId, RiderMode.FOLLOW, targetRiderId = r.targetId, maxIntensity = maxFollow()))
                return
            }
            response = null
        }
        val leaderId = v.leaderId ?: return
        val leaderGroup = v.state(leaderId).groupId
        for (a in events.filterIsInstance<RaceEvent.Attack>()) {
            if (ownById.containsKey(a.riderId)) continue
            val dangerous = a.riderId in rivalLeaders || finishM - a.distM <= Tactics.RESPONSE_WINDOW_M
            if (!dangerous || a.fromGroupId != leaderGroup) continue
            val sprintLeader = kind == FinaleKind.SPRINT && !roles.leader.archetype.attacksOnClimbs
            val candidates = listOfNotNull(if (sprintLeader) null else leaderId, roles.lieutenant?.id)
            val who = candidates.firstOrNull {
                v.racing(it) && v.state(it).groupId == a.fromGroupId && v.w(it) >= Tactics.RESPONSE_MIN_W
            } ?: continue
            response = Response(who, a.riderId, v.tick + Tactics.RESPONSE_S)
            assign(who, Order(who, RiderMode.FOLLOW, targetRiderId = a.riderId, maxIntensity = maxFollow()))
            return
        }
    }

    private fun maxFollow() = 1.15 + 0.2 * params.aggressiveness

    /** 4. Early break. */
    private fun breakaway(v: View, assign: (Int, Order) -> Unit) {
        if (!params.breakaway) return
        val b = roles.breakaway.id
        if (!v.racing(b)) return
        val g = v.group(b) ?: return
        val pel = v.peloton ?: return
        val ahead = g.id != pel.id && g.headDistM > pel.headDistM
        val d = v.state(b).distM
        when {
            ahead -> {
                // Early on, wait for the other break riders behind (between us and the peloton): one break, not solos.
                val joining = v.toGo(b) > Tactics.FINALE_WINDOW_M && v.groups.any {
                    it.id != g.id && it.headDistM < g.headDistM && it.headDistM > pel.headDistM && it.gapS - g.gapS < Tactics.BREAK_WAIT_S
                }
                val intensity = when {
                    joining -> Tactics.BREAK_WAIT
                    // Cooked, he still takes his turns (a break of `follow` riders would ride the faster default rotation).
                    v.spent(b) -> Tactics.BREAK_PULL_SPENT
                    v.toGo(b) < 30_000.0 -> Tactics.BREAK_PULL_LATE
                    else -> Tactics.BREAK_PULL
                }
                // No climbing bonus before the finale: the peloton's control doesn't take one either.
                val finale = v.toGo(b) <= Tactics.FINALE_WINDOW_M
                assign(b, pull(b, if (finale) terrain(intensity, d) else intensity))
            }
            g.id == pel.id && d in breakFromM..Tactics.BREAK_WINDOW_END_M &&
                breakAttempts < Tactics.BREAK_ATTEMPTS && v.tick - breakLastTick > 300 -> {
                breakAttempts++
                breakLastTick = v.tick
                assign(b, Order(b, RiderMode.ATTACK, intensity = Tactics.BREAK_ATTACK_INTENSITY, durationS = Tactics.BREAK_ATTACK_S))
            }
            v.tick - breakLastTick < Tactics.BREAK_ATTACK_S -> assign(b, lastSent[b] ?: Order(b, RiderMode.FOLLOW))
        }
    }

    /** Fresh domestiques in [groupId], current pullers first, then the strongest now (cpEff). */
    private fun workers(v: View, groupId: Int, busy: Set<Int>, minW: Double): List<Int> =
        v.teammatesIn(groupId, busy).map { it.riderId }
            .filter { roles.role(it) == Role.DOMESTIQUE && v.cpFrac(it) >= Tactics.WORKER_MIN_CP && v.w(it) >= minW }
            .sortedWith(compareBy<Int> { if (it in pullers) 0 else 1 }.thenByDescending { v.state(it).cpEff })

    /** 5. Domestiques set the pace at the foot of the finale climbs. */
    private fun climbWork(v: View, busy: Set<Int>, assign: (Int, Order) -> Unit) {
        val leader = v.leaderId ?: return
        val d = v.state(leader).distM
        val i = finale.indexOfFirst { d >= it.startM - Tactics.CLIMB_FOOT_LEAD_M && d < it.endM - Tactics.CLIMB_STOP_BEFORE_TOP_M }
        if (i < 0) return
        val g = v.group(leader) ?: return
        if (g.size < 5) return
        val intensity = when {
            kind != FinaleKind.SPRINT && roles.leader.archetype.attacksOnClimbs -> Tactics.CLIMB_PULL
            kind == FinaleKind.SPRINT && (controls || roles.leader.archetype.attacksOnClimbs) -> Tactics.CLIMB_PULL_SPRINT_COURSE
            else -> return
        }
        val n = if (i == finale.lastIndex) 2 else 1
        for (w in workers(v, g.id, busy + leader, 0.3).take(n)) {
            pullers += w
            assign(w, pull(w, intensity))
        }
    }

    /** 6. Chase when the leader is behind; ride the move when the leader is in a small front group. */
    private fun chase(v: View, busy: Set<Int>, assign: (Int, Order) -> Unit) {
        val leader = v.leaderId ?: return
        if (v.toGo(leader) > Tactics.CHASE_WINDOW_M) return
        val g = v.group(leader) ?: return
        val head = v.head ?: return
        val inFront = g.id == head.id
        val minGap = if (v.toGo(leader) <= Tactics.CHASE_ALL_OUT_M) Tactics.CHASE_MIN_GAP_FINALE_S else Tactics.CHASE_MIN_GAP_S
        if (!inFront && g.gapS < minGap) return
        if (inFront && (g.id == v.peloton?.id || g.size > Tactics.SMALL_GROUP)) return
        // In the last km everyone with W′ left empties himself for the leader; before, only the fresh riders chase.
        val allOut = !inFront && v.toGo(leader) <= Tactics.CHASE_ALL_OUT_M
        val helpers = v.teammatesIn(g.id, busy + leader).map { it.riderId }
            .filter { (if (allOut) v.w(it) > Tactics.SPENT_W else !v.spent(it)) && !(kind == FinaleKind.SPRINT && it == roles.sprinter.id) }
            .take(if (allOut) 3 else 2)
        val intensity = if (allOut) Tactics.CHASE_PULL_ALL_OUT else Tactics.CHASE_PULL
        for (h in helpers) assign(h, pull(h, terrain(intensity, v.state(h).distM)))
        val leaderWorks = g.size <= Tactics.SMALL_GROUP && v.w(leader) > 0.3 && v.toGo(leader) > 1_500.0 &&
            (inFront || helpers.isEmpty())
        if (leaderWorks && leader !in busy) assign(leader, pull(leader, Tactics.LEADER_PULL))
    }

    /**
     * 7. Control the break. While the break goes and builds its lead (until the gap first reaches a target that
     * shrinks towards the finish, or the end of the break window), the strongest teams ride an easy tempo at the
     * front: a peloton without pullers rides the faster default rotation and would never let a break go. Once the
     * break is established, one domestique holds the gap in the band (his pull follows the gap, see
     * [Tactics.CONTROL_HOLD]); above the target they chase until it is back under [Tactics.CONTROL_RELEASE] × target,
     * and in the last [Tactics.CONTROL_CATCH_KM] km whenever a break is ahead.
     */
    private fun control(v: View, busy: Set<Int>, assign: (Int, Order) -> Unit) {
        if (!controls) return
        val pel = v.peloton ?: return
        val leader = v.leaderId ?: return
        if (v.state(leader).groupId != pel.id) return
        val breakAhead = !v.pelotonIsHead
        if (!breakAhead && (breakEstablished || pel.headDistM > Tactics.BREAK_WINDOW_END_M)) {
            controlling = false
            return
        }
        val kmToGo = (finishM - pel.headDistM) / 1000.0
        val target = (Tactics.CONTROL_S_PER_KM * kmToGo).coerceIn(Tactics.CONTROL_MIN_GAP_S, Tactics.CONTROL_MAX_GAP_S)
        val gap = if (breakAhead) pel.gapS else 0.0
        if (gap > target || (breakAhead && kmToGo < Tactics.CONTROL_CATCH_KM)) controlling = true
        if (gap > target || pel.headDistM > Tactics.BREAK_WINDOW_END_M) breakEstablished = true
        if (gap < Tactics.CONTROL_RELEASE * target && kmToGo >= Tactics.CONTROL_CATCH_KM) controlling = false
        val (n, intensity) = when {
            controlling -> (if (gap > 1.5 * target || kmToGo < 30.0) 3 else 2) to when {
                kmToGo > 100.0 -> Tactics.CONTROL_CHASE_EARLY
                kmToGo > 40.0 -> Tactics.CONTROL_CHASE_MID
                else -> Tactics.CONTROL_CHASE_LATE
            }
            !breakEstablished -> 1 to Tactics.CONTROL_TEMPO
            else -> 1 to (Tactics.CONTROL_HOLD + Tactics.CONTROL_HOLD_GAIN * (gap / target - Tactics.CONTROL_HOLD_AT))
                .coerceIn(Tactics.CONTROL_HOLD_MIN, Tactics.CONTROL_HOLD_MAX)
        }
        val d = pel.headDistM
        val finale = finishM - d <= Tactics.FINALE_WINDOW_M
        for (w in workers(v, pel.id, busy + leader + roles.sprinter.id, 0.2).take(n)) {
            pullers += w
            val climbing = gradeAhead(d) >= Tactics.CLIMB_GRADE
            assign(w, pull(w, if (finale) terrain(intensity, d) else if (climbing) min(intensity, Tactics.CONTROL_CLIMB_CAP) else intensity))
        }
    }

    private var breakEstablished = false

    /** Average gradient of the next [Tactics.GRADE_AHEAD_M] from [distM], from the course points. */
    private fun gradeAhead(distM: Double): Double {
        val pts = course.points
        if (pts.size < 2) return 0.0
        fun eleAt(d: Double): Double {
            var lo = 0
            var hi = pts.lastIndex
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (pts[mid].distM <= d) lo = mid else hi = mid
            }
            val a = pts[lo]
            val b = pts[hi]
            val span = b.distM - a.distM
            return if (span <= 0.0) a.eleM else a.eleM + (b.eleM - a.eleM) * ((d - a.distM) / span).coerceIn(0.0, 1.0)
        }
        val to = min(finishM, distM + Tactics.GRADE_AHEAD_M)
        return if (to <= distM) 0.0 else (eleAt(to) - eleAt(distM)) / (to - distM)
    }

    /** A pull is harder on a climb, like the default rotation: [Tactics.CLIMB_BONUS] more from [Tactics.CLIMB_GRADE]. */
    private fun terrain(intensity: Double, distM: Double) =
        if (gradeAhead(distM) >= Tactics.CLIMB_GRADE) intensity + Tactics.CLIMB_BONUS else intensity

    private var controlling = false
}
