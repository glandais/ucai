package ucai.runner.calibration

import ucai.api.ApiJson
import ucai.api.ClockConfig
import ucai.api.ClockMode
import ucai.api.Order
import ucai.api.RaceDetail
import ucai.api.RaceEvent
import ucai.api.RaceState
import ucai.api.RejectedOrder
import ucai.api.Replay
import ucai.api.ResultEntry
import ucai.engine.course.Course
import ucai.engine.race.Race
import ucai.engine.race.RaceConfig
import ucai.engine.race.RaceEntrant
import ucai.engine.race.RaceObserver
import ucai.engine.replay.ReplayRecorder
import ucai.engine.riders.Field
import ucai.engine.riders.RatingScale
import ucai.engine.riders.RiderGenerator
import ucai.kernel.decimatedDetail
import ucai.kernel.raceSeed
import ucai.team.strategy.Role
import ucai.team.strategy.StrategyParams
import ucai.team.strategy.Tactics
import ucai.team.strategy.TeamStrategy
import java.io.File
import java.util.Locale
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/**
 * Calibration harness (DESIGN §8, §9 step 2): the team v1 strategies drive the engine directly, no HTTP.
 * Each team's [TeamStrategy] gets exactly the `decision` event the kernel would stream to it.
 */
object Calibration {
    fun loadCourse(dir: File, id: String): Course =
        ApiJson.decodeFromString(Course.serializer(), File(dir, "$id.json").readText())

    /**
     * Team configs for a race, seeded: 3 or 4 teams send a rider in the early break, aggressiveness in
     * [0.3, 0.9]; control is left to each strategy (the 3 teams whose leader looks strongest). Race-level draws, the
     * same for every team: the most the controlling teams let the break have, and where they start chasing it down.
     */
    fun teamParams(seed: Long, teamIds: List<String>): Map<String, StrategyParams> {
        val rnd = Random(seed * 31 + 7)
        val breakTeams = teamIds.shuffled(rnd).take(3 + rnd.nextInt(2)).toSet()
        val aggressiveness = teamIds.map { rnd.nextDouble(0.3, 0.9) }
        val breakGapS = rnd.nextDouble(Tactics.CONTROL_GAP_MIN_S, Tactics.CONTROL_GAP_MAX_S)
        val chaseFromKm = rnd.nextDouble(Tactics.CHASE_FROM_MIN_KM, Tactics.CHASE_FROM_MAX_KM)
        return teamIds.withIndex().associate { (i, id) ->
            id to StrategyParams(
                aggressiveness = aggressiveness[i],
                breakaway = id in breakTeams,
                seed = seed * 1_000 + i,
                breakGapS = breakGapS,
                chaseFromKm = chaseFromKm,
            )
        }
    }

    /**
     * The start list the kernel builds for [seed] when the 10 teams register without riders: the same generated
     * riders, rated against [RatingScale.REFERENCE] (the kernel never rates against the field, so ratings don't
     * depend on registration order). Field-relative ratings would change the strategies' roles.
     *
     * With the kernel's [secret] ([ucai.kernel.KernelConfig.seedSecret]), riders come from [raceSeed] like in the
     * kernel (team names and colors stay those of the public seed, as the batch runner registers them); without it,
     * from the bare seed (calibration runs).
     */
    fun field(seed: Long, secret: String? = null): Field =
        if (secret == null) Field.generate(seed).ratedWith(RatingScale.REFERENCE)
        else Field.build(raceSeed(seed, secret), RiderGenerator.teamIdentities(seed)).ratedWith(RatingScale.REFERENCE)

    class Run(
        val course: Course,
        val seed: Long,
        val field: Field,
        val race: Race,
        /** Null when the run was made without recording (`record = false`). */
        val replayOrNull: Replay?,
        val strategies: Map<String, TeamStrategy>,
        val rejected: List<RejectedOrder>,
        val decisions: Int,
        val ordersSent: Int,
        val simMillis: Long,
        val breakaway: RaceMetrics,
    ) {
        val replay: Replay get() = checkNotNull(replayOrNull) { "run made without a replay" }

        /** Gaps (s) of the finishers behind the winner. */
        val finishGaps: List<Double> get() = race.results().mapNotNull { it.gapS }
        val lastGapS: Double get() = finishGaps.maxOrNull() ?: 0.0
        fun downBy(minutes: Int) = finishGaps.count { it > minutes * 60.0 }
    }

    /**
     * Runs [course] with [seed]. With [secret], the race the kernel runs with that seed secret (rider generation and
     * engine RNG from [raceSeed], strategy parameters from the public seed, as in the batch runner).
     */
    fun run(
        course: Course,
        seed: Long,
        config: RaceConfig = RaceConfig(),
        observer: RaceObserver? = null,
        secret: String? = null,
        record: Boolean = true,
    ): Run {
        val t0 = System.nanoTime()
        val field = field(seed, secret)
        val entrants = field.riders.map { RaceEntrant(it.id, it.teamId, it.profile) }
        val race = Race(course, entrants, secret?.let { raceSeed(seed, it) } ?: seed, config = config)
        val recorder = if (record) ReplayRecorder() else null
        recorder?.let { race.addObserver(it) }
        val breakaway = RaceMetrics { id -> field.rider(id)!!.profile.massKg }
        race.addObserver(breakaway)
        observer?.let { race.addObserver(it) }
        // What a bot gets from `GET /courses/{id}`: points decimated to one every 50 m.
        val detailCourse = course.decimatedDetail()
        val params = teamParams(seed, field.teamInfos.map { it.id })
        val strategies = field.teams.associate { t ->
            t.info.id to TeamStrategy(t.info.id, detailCourse, field.publicRiders(), t.riders, params.getValue(t.info.id))
        }

        val rejected = mutableListOf<RejectedOrder>()
        var decisions = 0
        var sent = 0
        var dp: ucai.engine.race.DecisionPoint? = race.start()
        while (dp != null) {
            val point = dp
            decisions++
            val orders = LinkedHashMap<String, List<Order>>()
            for ((teamId, s) in strategies) {
                val o = s.decide(race.decisionEvent(teamId)!!)
                if (o.isNotEmpty()) orders[teamId] = o
                sent += o.size
            }
            if (orders.isNotEmpty()) race.applyOrders(point.decisionId, orders).values.forEach { rejected += it.rejected }
            dp = race.runToNextDecision()
        }
        val simMillis = (System.nanoTime() - t0) / 1_000_000

        val raceId = "${course.id}-$seed"
        val detail = RaceDetail(
            id = raceId, courseId = course.id, courseName = course.name, state = RaceState.FINISHED,
            clock = ClockConfig(ClockMode.LOCKSTEP), teams = field.teamInfos, riders = field.publicRiders(),
            tick = race.tick, seed = seed,
        )
        val replay = recorder?.replay(detail, race)
        return Run(course, seed, field, race, replay, strategies, rejected, decisions, sent, simMillis, breakaway)
    }

    fun writeReplay(replay: Replay, file: File) {
        file.parentFile?.mkdirs()
        GZIPOutputStream(file.outputStream().buffered()).bufferedWriter().use {
            it.write(ApiJson.encodeToString(Replay.serializer(), replay))
        }
    }

    /** Finishing groups by UCI group time (riders sharing a gap), front first. */
    fun finishGroups(results: List<ResultEntry>): List<List<ResultEntry>> {
        val out = mutableListOf<MutableList<ResultEntry>>()
        var last: Double? = null
        for (e in results) {
            val gap = e.gapS ?: continue
            if (last == null || gap != last) out += mutableListOf<ResultEntry>()
            out.last() += e
            last = gap
        }
        return out
    }

    /**
     * One line per course over several seeds: winning average speed range, winners' archetypes, the largest gap
     * inside the top 10, and the riders who did not finish.
     */
    fun summary(runs: List<Run>): String {
        if (runs.isEmpty()) return ""
        val speeds = runs.map { r -> r.course.distanceM / r.race.results().first().timeS!! * 3.6 }
        val archetypes = runs.map { r -> r.field.rider(r.race.results().first().riderId)!!.archetype.name.lowercase() }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" }
        val top10 = runs.map { r -> r.race.results().take(10).maxOf { it.gapS ?: 0.0 } }
        val dnf = runs.sumOf { r -> r.race.results().count { it.position == null } }
        val winners = runs.map { r -> r.race.results().first().riderId }
        fun f(x: Double) = "%.1f".format(Locale.ROOT, x)
        fun i(x: Double) = "%.0f".format(Locale.ROOT, x)
        return "  ${runs.first().course.id}: ${runs.size} seeds, avg ${f(speeds.min())}–${f(speeds.max())} km/h, " +
            "winners [$archetypes], top-10 gap max ${top10.joinToString("/") { i(it) }} s, " +
            "winner ids $winners, not finished $dnf\n" +
            "    break max gap ${runs.joinToString("/") { i(it.breakaway.maxGapS) }} s, caught at km " +
            "${runs.joinToString("/") { i(it.breakaway.caughtKm) }} of ${i(runs.first().course.distanceM / 1000)}; " +
            ">10 min ${runs.joinToString("/") { "${it.downBy(10)}" }}, >20 min ${runs.joinToString("/") { "${it.downBy(20)}" }}, " +
            "last finisher +${runs.joinToString("/") { i(it.lastGapS) }} s, finish groups ${runs.joinToString("/") { "${finishGroups(it.race.results()).size}" }}\n" +
            "    break won ${runs.count { Score.breakWon(it) }}/${runs.size}, peloton at half ${runs.joinToString("/") { "${it.breakaway.pelotonAtHalf}" }}, " +
            "CP lost leaders ${runs.joinToString("/") { f(Score.cpLostPct(it, Role.LEADER)) }} %, domestiques ${runs.joinToString("/") { f(Score.cpLostPct(it, Role.DOMESTIQUE)) }} %, " +
            "max ${runs.joinToString("/") { i(it.breakaway.maxSpeed * 3.6) }} km/h, best 20 min ${runs.joinToString("/") { r -> f(r.breakaway.riderIds.maxOf { r.breakaway.best20WKg(it) }) }} W/kg, " +
            "favourite won ${runs.count { Score.favouriteRank(it) == 1 }}/${runs.size}\n" +
            "    winner margin ${runs.joinToString("/") { i(Score.winnerMarginS(it)) }} s, winning group ${runs.joinToString("/") { "${Score.winningGroupSize(it)}" }}, " +
            "head at 10/5/2 km ${runs.joinToString(" ") { r -> r.breakaway.headAt.values.joinToString("/") { "${it.first}@${i(it.second)}" } }}, " +
            "break caught at km to go ${runs.joinToString("/") { if (Score.breakWon(it)) "won" else i(it.course.distanceM / 1000 - it.breakaway.caughtKm) }}\n" +
            Score.courseReport(runs)
    }

    fun report(r: Run): String {
        fun cpLeft(id: Int) = r.race.riders.first { it.id == id }.cpEff / r.field.rider(id)!!.profile.cp * 100
        val results = r.race.results()
        val winner = results.first()
        val w = r.field.rider(winner.riderId)!!
        val strat = r.strategies.getValue(w.teamId)
        val time = winner.timeS!!
        val speed = r.course.distanceM / time * 3.6
        val groups = finishGroups(results)
        val events = r.race.events
        fun fmt(s: Double) = "%d:%02d:%02d".format(Locale.ROOT, (s / 3600).toInt(), ((s % 3600) / 60).toInt(), (s % 60).toInt())
        val sb = StringBuilder()
        sb.appendLine("== ${r.course.name} seed ${r.seed}")
        sb.appendLine(
            "  winner      #${w.id} ${w.name} (${w.archetype.name.lowercase()}, ${strat.roles.role(w.id).name.lowercase()} of ${w.teamId})" +
                " in ${fmt(time)}, avg ${"%.2f".format(Locale.ROOT, speed)} km/h",
        )
        val dnf = results.count { it.position == null }
        if (dnf > 0) sb.appendLine("  NOT FINISHED $dnf riders (stalled, max ticks ${r.race.config.maxTicks})")
        sb.appendLine("  groups      ${groups.size} at the finish, sizes ${groups.map { it.size }}")
        val b = r.breakaway.earlyBreak
        sb.appendLine(
            "  break       " + (b?.let {
                "from km ${"%.0f".format(Locale.ROOT, it.startKm)}, max gap ${"%.0f".format(Locale.ROOT, it.maxGapS)} s at km " +
                    "${"%.0f".format(Locale.ROOT, it.maxGapKm)}, caught at km ${"%.0f".format(Locale.ROOT, r.breakaway.caughtKm)}"
            } ?: "none"),
        )
        sb.appendLine(
            "  time cut    >10 min ${r.downBy(10)}, >20 min ${r.downBy(20)}, >30 min ${r.downBy(30)}, " +
                "last finisher +${"%.0f".format(Locale.ROOT, r.lastGapS)} s",
        )
        val late = results.filter { (it.gapS ?: 0.0) > 20 * 60.0 }
        if (late.isNotEmpty()) sb.appendLine(
            "  >20 min     " + late.joinToString("  ") { e ->
                val role = r.strategies.getValue(e.teamId).roles.role(e.riderId).name.take(4).lowercase()
                "#${e.riderId} $role left km${"%.0f".format(Locale.ROOT, r.breakaway.leftPelotonKm[e.riderId] ?: 0.0)} +${"%.0f".format(Locale.ROOT, (e.gapS ?: 0.0) / 60)}' cp${"%.0f".format(Locale.ROOT, cpLeft(e.riderId))}%"
            },
        )
        sb.appendLine(
            "  top 10      " + results.take(10).joinToString("  ") { e ->
                val a = r.field.rider(e.riderId)!!.archetype.name.take(3).lowercase()
                "${e.riderId}($a)+${"%.0f".format(Locale.ROOT, e.gapS)}s"
            },
        )
        val leaders = r.strategies.values.map { it.roles.leader.id }.toSet()
        val leaderPos = results.filter { it.riderId in leaders }.map { it.position }
        sb.appendLine("  leaders at  $leaderPos")
        // Fatigue at the finish, by role: mean work done (kJ) and mean CP lost (%).
        val byRole = r.race.riders.groupBy { r.strategies.getValue(it.teamId).roles.role(it.id) }
        sb.appendLine(
            "  fatigue     " + byRole.entries.sortedBy { it.key.ordinal }.joinToString("  ") { (role, rs) ->
                val kj = rs.map { it.energyKj }.average()
                val lost = rs.map { 1.0 - it.cpEff / r.field.rider(it.id)!!.profile.cp }.average() * 100
                "${role.name.lowercase()} ${"%.0f".format(Locale.ROOT, kj)} kJ −${"%.1f".format(Locale.ROOT, lost)}%"
            },
        )
        sb.appendLine(
            "  events      attacks ${events.count { it is RaceEvent.Attack }}, splits ${events.count { it is RaceEvent.GroupSplit }}," +
                " merges ${events.count { it is RaceEvent.GroupMerge }}, dropped ${events.count { it is RaceEvent.RiderDropped }}",
        )
        sb.appendLine(
            "  attack log  " + events.filterIsInstance<RaceEvent.Attack>().joinToString("  ") { a ->
                val ar = r.field.rider(a.riderId)!!
                "km${"%.0f".format(Locale.ROOT, a.distM / 1000)} #${a.riderId} ${r.strategies.getValue(ar.teamId).roles.role(a.riderId).name.take(4).lowercase()}"
            },
        )
        val m = r.breakaway
        val best20 = m.riderIds.maxBy { m.best20WKg(it) }
        val best60 = m.riderIds.maxBy { m.best60W(it) }
        fun f1(x: Double) = "%.1f".format(Locale.ROOT, x)
        sb.appendLine(
            "  physics     max ${f1(m.maxSpeed * 3.6)} km/h at km ${"%.0f".format(Locale.ROOT, m.maxSpeedKm)}, best 20 min " +
                "#$best20 ${"%.0f".format(Locale.ROOT, m.best20W(best20))} W ${f1(m.best20WKg(best20))} W/kg, best 60 min " +
                "#$best60 ${"%.0f".format(Locale.ROOT, m.best60W(best60))} W ${f1(m.best60WKg(best60))} W/kg; " +
                "peloton at half distance ${m.pelotonAtHalf} of ${m.onRoadAtHalf}; winner fit rank ${Score.favouriteRank(r)}" +
                (if (Score.breakWon(r)) "; WON BY THE BREAK" else ""),
        )
        sb.appendLine("  " + Score.describe(Score.race(r), Score.raceTerms(r)))
        sb.appendLine(
            "  run         ${r.simMillis} ms, ${r.race.tick} ticks, ${r.decisions} decisions, ${r.ordersSent} orders, " +
                "${r.rejected.size} rejected, ${r.replayOrNull?.frames?.size ?: 0} frames",
        )
        if (r.rejected.isNotEmpty()) sb.appendLine("  REJECTED    ${r.rejected.take(5)}")
        return sb.toString()
    }
}
