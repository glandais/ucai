package ucai.runner.calibration

import ucai.api.Archetype
import ucai.team.strategy.FinaleKind
import ucai.team.strategy.Role
import java.util.Locale
import kotlin.math.min

/**
 * Calibration score (DESIGN §8 "Score"): one number, lower is better, from the per-race metrics against the realism
 * targets. Each [Term] is a value with a target band [lo, hi]; outside the band its penalty is
 * `min(cap, (distance to the band / tolerance)²)` (separate tolerances below and above), times its weight. A race
 * scores the sum of its terms; a course the mean of its races plus the course terms (over all seeds); the total is
 * the sum of the courses.
 */
object Score {
    const val CAP = 9.0

    class Term(
        val name: String,
        val value: Double,
        val lo: Double,
        val hi: Double,
        val tolLo: Double,
        val tolHi: Double = tolLo,
        val weight: Double = 1.0,
    ) {
        val penalty: Double
            get() {
                val x = when {
                    value < lo -> (lo - value) / tolLo
                    value > hi -> (value - hi) / tolHi
                    else -> 0.0
                }
                return weight * min(CAP, x * x)
            }
    }

    /** Per-course targets. */
    class Targets(
        val speedKmh: ClosedFloatingPointRange<Double>,
        /** Winner archetypes that fit (penalty 0) and that are acceptable (0.5); any other 1. */
        val fit: Set<Archetype>,
        val acceptable: Set<Archetype>,
        /** Largest gap inside the top 10 (s). */
        val top10GapS: ClosedFloatingPointRange<Double>,
        /** Gap from the winner to 2nd (s): the hilly monuments are usually won solo or from a small group. */
        val winnerMarginS: ClosedFloatingPointRange<Double>,
        /**
         * Share of the seeds decided by a front group of at most [selectiveGroup] riders (the winner's group at the
         * line, by group time), so a course that ends the same way every year scores worse.
         */
        val selectiveGroup: Int,
        val selectiveShare: ClosedFloatingPointRange<Double>,
    )

    private val HILLY_TOP10 = 5.0..120.0
    /** Real LBL and Lombardia: mostly solo, else 2–5 riders; a margin of a few seconds to a minute and a half. */
    private val HILLY_MARGIN = 3.0..90.0
    private val ANY_MARGIN = 0.0..Double.MAX_VALUE

    fun targets(courseId: String): Targets = when (courseId) {
        // Sanremo: a bunch sprint most years, a Poggio move (5 riders or fewer) in some (2019, 2021–2023, 2025).
        "milano-sanremo" -> Targets(40.0..44.0, setOf(Archetype.SPRINTER, Archetype.PUNCHEUR), setOf(Archetype.ALL_ROUNDER, Archetype.ROULEUR), 0.0..120.0, ANY_MARGIN, 5, 0.2..0.5)
        "liege-bastogne-liege" -> Targets(39.0..42.0, setOf(Archetype.PUNCHEUR), setOf(Archetype.CLIMBER, Archetype.ALL_ROUNDER), HILLY_TOP10, HILLY_MARGIN, 3, 0.3..1.0)
        "il-lombardia" -> Targets(39.0..42.0, setOf(Archetype.CLIMBER), setOf(Archetype.PUNCHEUR, Archetype.ALL_ROUNDER), HILLY_TOP10, HILLY_MARGIN, 3, 0.3..1.0)
        else -> Targets(38.0..44.0, Archetype.entries.toSet(), emptySet(), 0.0..120.0, ANY_MARGIN, 70, 0.0..1.0)
    }

    /** Gap from the winner to 2nd at the line (s). */
    fun winnerMarginS(r: Calibration.Run): Double = r.race.results().getOrNull(1)?.gapS ?: 0.0

    /** Riders who finished with the winner's time (1 = won solo). */
    fun winningGroupSize(r: Calibration.Run): Int = r.race.results().count { it.position != null && it.gapS == 0.0 }

    /** Was the race decided by a front group of at most the course's [Targets.selectiveGroup] riders? */
    fun selective(r: Calibration.Run): Boolean = winningGroupSize(r) <= targets(r.course.id).selectiveGroup

    /** Was the race won from the early break (the winner was in it and it was never caught before the last km)? */
    fun breakWon(r: Calibration.Run): Boolean {
        val b = r.breakaway.earlyBreak ?: return false
        val winner = r.race.results().first().riderId
        return winner in b.riderIds && r.breakaway.caughtKm >= r.course.distanceM / 1000.0 - 1.0
    }

    /** CP lost at the finish (%), mean over the riders of [role]. */
    fun cpLostPct(r: Calibration.Run, role: Role): Double {
        val rs = r.race.riders.filter { r.strategies.getValue(it.teamId).roles.role(it.id) == role }
        if (rs.isEmpty()) return 0.0
        return rs.map { 1.0 - it.cpEff / r.field.rider(it.id)!!.profile.cp }.average() * 100
    }

    fun raceTerms(r: Calibration.Run): List<Term> {
        val t = targets(r.course.id)
        val results = r.race.results()
        val winner = results.first()
        val speed = r.course.distanceM / winner.timeS!! * 3.6
        val arch = r.field.rider(winner.riderId)!!.archetype
        val archPenalty = when (arch) {
            in t.fit -> 0.0
            in t.acceptable -> 0.5
            else -> 1.0
        }
        val m = r.breakaway
        val distKm = r.course.distanceM / 1000.0
        val caughtToGo = if (breakWon(r)) 0.0 else distKm - m.caughtKm
        val top10 = results.take(10).maxOf { it.gapS ?: 0.0 }
        val atHalf = if (m.onRoadAtHalf == 0) 0.0 else m.pelotonAtHalf.toDouble() / m.onRoadAtHalf
        val ids = m.riderIds
        val p20 = ids.maxOfOrNull { m.best20WKg(it) } ?: 0.0
        val p60 = ids.maxOfOrNull { m.best60WKg(it) } ?: 0.0
        val p60W = ids.maxOfOrNull { m.best60W(it) } ?: 0.0
        return listOf(
            Term("speed", speed, t.speedKmh.start, t.speedKmh.endInclusive, 1.0, weight = 3.0),
            Term("winner", archPenalty, 0.0, 0.0, 1.0, weight = 2.0),
            Term("breakGap", m.maxGapS, 180.0, 600.0, 60.0, 180.0, weight = 2.0),
            Term("caughtToGo", caughtToGo, 5.0, 60.0, 20.0, weight = 2.0),
            Term("top10Gap", top10, t.top10GapS.start, t.top10GapS.endInclusive, 5.0, 60.0, weight = 2.0),
            Term("winnerMargin", winnerMarginS(r), t.winnerMarginS.start, t.winnerMarginS.endInclusive, 3.0, 60.0),
            Term("pelotonAtHalf", atHalf, 0.75, 1.0, 0.1, weight = 2.0),
            Term("lastFinisherMin", r.lastGapS / 60, 0.0, 15.0, 10.0),
            Term("over20min", r.downBy(20).toDouble(), 0.0, 2.0, 4.0),
            Term("leaderCpLost", cpLostPct(r, Role.LEADER), 5.0, 12.0, 2.0),
            Term("domestiqueCpLost", cpLostPct(r, Role.DOMESTIQUE), 10.0, 20.0, 3.0),
            Term("maxKmh", m.maxSpeed * 3.6, 0.0, 90.0, 5.0, weight = 2.0),
            Term("max20WKg", p20, 0.0, 6.8, 0.3),
            Term("max60WKg", p60, 0.0, 6.0, 0.3),
            Term("max60W", p60W, 0.0, 500.0, 30.0),
        )
    }

    /** Rank of the winner by public course fit among all riders (1 = the favourite; ties share the best rank). */
    fun favouriteRank(r: Calibration.Run): Int {
        val kind = FinaleKind.of(r.course.toDetail())
        val pub = r.field.publicRiders()
        val w = pub.first { it.id == r.race.results().first().riderId }
        val fit = kind.fit(w.ratings)
        return 1 + pub.count { kind.fit(it.ratings) > fit }
    }

    fun courseTerms(runs: List<Calibration.Run>): List<Term> {
        if (runs.isEmpty()) return emptyList()
        val n = runs.size.toDouble()
        return courseTerms(
            runs.first().course.id, runs.count { breakWon(it) } / n, runs.count { favouriteRank(it) == 1 } / n,
            runs.count { selective(it) } / n,
        )
    }

    /** Course terms from the share of seeds won from the early break, by the favourite, and by a small group. */
    fun courseTerms(courseId: String, breakWins: Double, favourite: Double, selective: Double): List<Term> {
        val t = targets(courseId)
        return listOf(
            Term("breakWinShare", breakWins, 0.0, 0.2, 0.15, weight = 2.0),
            Term("favouriteWinShare", favourite, 0.0, 0.5, 0.25),
            Term("selectiveShare", selective, t.selectiveShare.start, t.selectiveShare.endInclusive, 0.15),
        )
    }

    fun race(r: Calibration.Run): Double = raceTerms(r).sumOf { it.penalty }

    fun course(runs: List<Calibration.Run>): Double =
        if (runs.isEmpty()) 0.0 else runs.map { race(it) }.average() + courseTerms(runs).sumOf { it.penalty }

    private fun f(x: Double) = "%.2f".format(Locale.ROOT, x)

    /** `score 3.21 = speed 1.20 (37.9) + …`, nonzero terms only, value in parentheses. */
    fun describe(total: Double, terms: List<Term>): String =
        "score ${f(total)}" + terms.filter { it.penalty > 0.0 }.joinToString("") { " + ${it.name} ${f(it.penalty)} (${f(it.value)})" }

    /** Per-course score line: total, mean race score, mean penalty per term over the seeds, course terms. */
    fun courseReport(runs: List<Calibration.Run>): String {
        if (runs.isEmpty()) return ""
        val perRace = runs.map { raceTerms(it) }
        val names = perRace.first().map { it.name }
        val mean = names.mapIndexed { i, n -> n to perRace.map { it[i].penalty }.average() }.filter { it.second > 0.0 }
        val ct = courseTerms(runs)
        return "    score ${f(course(runs))}: races ${runs.joinToString("/") { f(race(it)) }} (mean ${f(perRace.map { t -> t.sumOf { it.penalty } }.average())})" +
            mean.joinToString("") { " ${it.first} ${f(it.second)}" } +
            "; course" + ct.joinToString("") { " ${it.name} ${f(it.penalty)} (${f(it.value)})" } + "\n"
    }
}
