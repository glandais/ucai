package ucai.team.strategy

import ucai.api.Archetype
import ucai.api.Climb
import ucai.api.CourseDetail
import ucai.api.PrivateRider
import ucai.api.PublicRider
import ucai.api.RiderRatings

/**
 * What kind of finale a course has (DESIGN §5 "Roles"). The bot only sees the API, so this is its own reading of
 * the course, not the engine's.
 */
enum class FinaleKind {
    /** Milano-Sanremo: flat-ish, short climbs, then a sprint. */
    SPRINT,

    /** Liège-Bastogne-Liège: many short steep climbs. */
    PUNCHY,

    /** Il Lombardia: long climbs. */
    CLIMBING,
    ;

    /** Course fit from public ratings, 1..5. */
    fun fit(r: RiderRatings): Double = when (this) {
        SPRINT -> 0.8 * r.sprint + 0.15 * r.punch + 0.05 * r.endurance
        PUNCHY -> 0.5 * r.punch + 0.25 * r.climbing + 0.25 * r.endurance
        CLIMBING -> 0.6 * r.climbing + 0.3 * r.endurance + 0.1 * r.descending
    }

    companion object {
        private val KNOWN = mapOf(
            "milano-sanremo" to SPRINT,
            "liege-bastogne-liege" to PUNCHY,
            "il-lombardia" to CLIMBING,
        )

        /** Known monuments by id; otherwise < 10 m of climbing per km is a sprint, a ≥ 6 km climb is climbing. */
        fun of(course: CourseDetail): FinaleKind = KNOWN[course.id] ?: when {
            course.elevationGainM / (course.distanceM / 1000.0) < 10.0 -> SPRINT
            course.climbs.any { it.endM - it.startM >= 6_000.0 } -> CLIMBING
            else -> PUNCHY
        }
    }
}

enum class Role { LEADER, LIEUTENANT, SPRINTER, DOMESTIQUE }

/**
 * Roles at the start (DESIGN §5): the leader is the best fit for the course, the lieutenant the second best, the
 * sprinter the fastest finisher (may be the leader), the others are domestiques. [breakaway] is the domestique
 * sent in the early break when the team plays that card (the strongest rouleur or domestique by CP, else
 * the strongest other domestique).
 */
class TeamRoles(
    val leader: PrivateRider,
    val lieutenant: PrivateRider?,
    val sprinter: PrivateRider,
    val domestiques: List<PrivateRider>,
    val breakaway: PrivateRider,
) {
    fun role(riderId: Int): Role = when (riderId) {
        leader.id -> Role.LEADER
        lieutenant?.id -> Role.LIEUTENANT
        sprinter.id -> Role.SPRINTER
        else -> Role.DOMESTIQUE
    }

    companion object {
        /**
         * Fit of own riders: public ratings, with the exact numbers ranking riders of the same rating inside the
         * team (at most 0.2 of a rating point).
         */
        fun ownFit(own: List<PrivateRider>, kind: FinaleKind): Map<Int, Double> {
            fun rankIn(metric: (PrivateRider) -> Double): Map<Int, Double> {
                val sorted = own.sortedBy(metric)
                return sorted.withIndex().associate { (i, r) -> r.id to i.toDouble() / own.size }
            }
            val climb = rankIn { it.profile.cp / it.profile.massKg }
            val punch = rankIn { (it.profile.cp + it.profile.wPrime / 60.0) / it.profile.massKg }
            val sprint = rankIn { it.profile.pMax / it.profile.cda }
            val endurance = rankIn { it.profile.durability }
            return own.associate { r ->
                val tie = when (kind) {
                    FinaleKind.SPRINT -> 0.8 * sprint.getValue(r.id) + 0.2 * punch.getValue(r.id)
                    FinaleKind.PUNCHY -> 0.5 * punch.getValue(r.id) + 0.25 * climb.getValue(r.id) + 0.25 * endurance.getValue(r.id)
                    FinaleKind.CLIMBING -> 0.7 * climb.getValue(r.id) + 0.3 * endurance.getValue(r.id)
                }
                r.id to kind.fit(r.ratings) + 0.2 * tie
            }
        }

        fun assign(own: List<PrivateRider>, kind: FinaleKind): TeamRoles {
            require(own.size >= 2) { "a team needs at least 2 riders" }
            val fit = ownFit(own, kind)
            val byFit = own.sortedWith(compareByDescending<PrivateRider> { fit.getValue(it.id) }.thenBy { it.id })
            val leader = byFit[0]
            val sprinter = own.maxWith(compareBy<PrivateRider> { it.profile.pMax / it.profile.cda }.thenByDescending { it.id })
            val lieutenant = byFit.drop(1).firstOrNull { it.id != sprinter.id || kind == FinaleKind.SPRINT }
            val domestiques = own.filter { it.id != leader.id && it.id != sprinter.id && it.id != lieutenant?.id }
            // A rouleur or a pure domestique goes in the break; a climber or puncheur is kept for the finale.
            val pool = domestiques.ifEmpty { own.filter { it.id != leader.id } }
            val breakaway = pool.maxWith(
                compareBy<PrivateRider> { it.archetype == Archetype.ROULEUR || it.archetype == Archetype.DOMESTIQUE }
                    .thenBy { it.profile.cp }.thenByDescending { it.id },
            )
            return TeamRoles(leader, lieutenant, sprinter, domestiques, breakaway)
        }
    }
}

/** Public reading of the field: each team's likely leader and how strong it looks for the course. */
class FieldReading(field: List<PublicRider>, val kind: FinaleKind) {
    /** Likely leader of each team (best public fit, lowest id on ties). */
    val leaders: Map<String, PublicRider> = field.groupBy { it.teamId }.mapValues { (_, rs) ->
        rs.maxWith(compareBy<PublicRider> { kind.fit(it.ratings) }.thenByDescending { it.id })
    }

    val leaderIds: Set<Int> = leaders.values.map { it.id }.toSet()

    /** 1 = the team whose leader looks strongest; ties share a rank by team id order. */
    fun rankOf(teamId: String): Int {
        val ordered = leaders.entries.sortedWith(
            compareByDescending<Map.Entry<String, PublicRider>> { kind.fit(it.value.ratings) }.thenBy { it.key },
        )
        return ordered.indexOfFirst { it.key == teamId } + 1
    }
}

/** Climbs that matter for the finale: summits within [windowM] of the finish, in course order. */
fun finaleClimbs(course: CourseDetail, windowM: Double): List<Climb> =
    course.climbs.filter { it.endM >= course.distanceM - windowM && it.endM <= course.distanceM }.sortedBy { it.startM }

/** Archetypes that attack on climbs; sprinters, rouleurs and domestiques wait for the finish. */
internal val Archetype.attacksOnClimbs: Boolean
    get() = this == Archetype.CLIMBER || this == Archetype.PUNCHEUR || this == Archetype.ALL_ROUNDER
