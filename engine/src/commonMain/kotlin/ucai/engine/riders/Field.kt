package ucai.engine.riders

import ucai.api.PrivateRider
import ucai.api.PublicRider
import ucai.api.RiderRatings
import ucai.api.RiderSpec
import ucai.api.TeamInfo

/** A team of the start list with its riders' full profiles. */
data class FieldTeam(val info: TeamInfo, val riders: List<PrivateRider>)

/**
 * The start list of a race: teams and riders with ids, exact profiles and public ratings.
 * Rider ids are 1, 2, 3… in team order then rider order.
 */
class Field(val teams: List<FieldTeam>) {
    val riders: List<PrivateRider> = teams.flatMap { it.riders }
    private val byId = riders.associateBy { it.id }

    val teamInfos: List<TeamInfo> get() = teams.map { it.info }

    /** What everyone sees (DESIGN §2: name, team, archetype, ratings). */
    fun publicRiders(): List<PublicRider> = riders.map { it.toPublic() }

    /** Full profiles, only for the team [teamId]. */
    fun teamRiders(teamId: String): List<PrivateRider> =
        teams.firstOrNull { it.info.id == teamId }?.riders ?: throw NoSuchElementException("unknown team $teamId")

    fun rider(id: Int): PrivateRider? = byId[id]

    /** The same field with ratings recomputed against [scale]. */
    fun ratedWith(scale: RatingScale): Field = Field(
        teams.map { t -> t.copy(riders = t.riders.map { it.copy(ratings = scale.rate(it.profile)) }) },
    )

    companion object {
        /** A fully generated field: [count] fictional teams of [ridersPerTeam] riders. */
        fun generate(
            seed: Long,
            count: Int = RiderGenerator.TEAM_COUNT,
            ridersPerTeam: Int = RiderGenerator.RIDERS_PER_TEAM,
        ): Field = build(seed, RiderGenerator.teamIdentities(seed, count), ridersPerTeam)

        /**
         * Builds a start list from [entries]: teams with explicit [TeamEntry.riders] keep them (validated against
         * [ProfileLimits], exactly [ridersPerTeam] each), the others are generated from [seed] and their index.
         * Ratings are relative to the whole field unless a [scale] is given.
         */
        fun build(
            seed: Long,
            entries: List<TeamEntry>,
            ridersPerTeam: Int = RiderGenerator.RIDERS_PER_TEAM,
            scale: RatingScale? = null,
        ): Field {
            require(entries.isNotEmpty()) { "a field needs at least one team" }
            val dupTeams = entries.groupBy { it.id }.filterValues { it.size > 1 }.keys
            require(dupTeams.isEmpty()) { "duplicate team ids: $dupTeams" }

            val usedNames = mutableSetOf<String>()
            entries.forEach { e ->
                e.riders?.let { specs ->
                    require(specs.size == ridersPerTeam) {
                        "team ${e.id} has ${specs.size} riders, expected $ridersPerTeam"
                    }
                    specs.forEach { s ->
                        val errors = ProfileLimits.validate(s)
                        require(errors.isEmpty()) { "team ${e.id}, rider '${s.name}': ${errors.joinToString("; ")}" }
                        require(usedNames.add(s.name)) { "duplicate rider name '${s.name}'" }
                    }
                }
            }
            val specs = entries.mapIndexed { i, e ->
                e.riders ?: RiderGenerator.generateTeamRiders(seed, i, ridersPerTeam, usedNames)
            }
            val ratingScale = scale ?: RatingScale.of(specs.flatten().map { it.profile })

            var nextId = 1
            return Field(
                entries.mapIndexed { i, e ->
                    FieldTeam(
                        TeamInfo(e.id, e.name, e.color),
                        specs[i].map { s -> s.toPrivate(nextId++, e.id, ratingScale.rate(s.profile)) },
                    )
                },
            )
        }
    }
}

/** API mapping: a spec placed in a team with an id and its public ratings. */
fun RiderSpec.toPrivate(id: Int, teamId: String, ratings: RiderRatings) =
    PrivateRider(id, name, teamId, archetype, ratings, profile)

/** Back to what a controller would send at registration. */
fun PrivateRider.toSpec() = RiderSpec(name, archetype, profile)
