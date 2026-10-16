package ucai.engine.riders

import ucai.api.Archetype
import ucai.api.RiderProfile
import ucai.api.RiderSpec
import kotlin.math.roundToLong
import kotlin.random.Random

/** One team of a start list. [riders] null means "generate them from the race seed". */
data class TeamEntry(
    val id: String,
    val name: String,
    val color: String,
    val riders: List<RiderSpec>? = null,
)

/**
 * Seeded generator of fictional teams and riders (DESIGN §2 "Rider profile").
 *
 * Every team gets one climber, puncheur, sprinter, rouleur, all-rounder and domestique, plus extra riders
 * (domestique half the time, otherwise a second climber, puncheur or rouleur). Each team has a seeded strength
 * that lifts all its riders a little, and its first climber, puncheur, sprinter and all-rounder are drawn from the
 * upper part of their archetype's range, so every team has a credible leader for every course.
 *
 * Teams use independent random streams (derived from the seed and the team index): replacing one team's riders
 * with explicit ones never changes the others.
 */
object RiderGenerator {
    const val TEAM_COUNT = 10
    const val RIDERS_PER_TEAM = 7

    val CORE = listOf(
        Archetype.CLIMBER, Archetype.PUNCHEUR, Archetype.SPRINTER,
        Archetype.ROULEUR, Archetype.ALL_ROUNDER, Archetype.DOMESTIQUE,
    )

    /** Archetypes whose first rider in a team is a potential leader. */
    val LEADER_ARCHETYPES = setOf(Archetype.CLIMBER, Archetype.PUNCHEUR, Archetype.SPRINTER, Archetype.ALL_ROUNDER)

    /** Leader-grade riders draw their talent from [LEADER_TALENT_FLOOR, 1]. */
    const val LEADER_TALENT_FLOOR = 0.4

    /** Fictional team identities (no riders): ids `team-01`…, names and colors shuffled by the seed. */
    fun teamIdentities(seed: Long, count: Int = TEAM_COUNT): List<TeamEntry> {
        require(count in 1..minOf(Names.TEAM_NAMES.size, Names.TEAM_COLORS.size)) { "unsupported team count $count" }
        val random = Random(mix(seed, -1))
        val names = Names.TEAM_NAMES.shuffled(random)
        val colors = Names.TEAM_COLORS.shuffled(random)
        return (0 until count).map { i -> TeamEntry(teamId(i), names[i], colors[i]) }
    }

    /** [count] fully generated teams of [ridersPerTeam] riders. */
    fun generateTeams(seed: Long, count: Int = TEAM_COUNT, ridersPerTeam: Int = RIDERS_PER_TEAM): List<TeamEntry> {
        val used = mutableSetOf<String>()
        return teamIdentities(seed, count).mapIndexed { i, team ->
            team.copy(riders = generateTeamRiders(seed, i, ridersPerTeam, used))
        }
    }

    fun teamId(index: Int): String = "team-" + (index + 1).toString().padStart(2, '0')

    /** Riders of the team at [teamIndex]. Generated names avoid (and are added to) [usedNames]. */
    fun generateTeamRiders(
        seed: Long,
        teamIndex: Int,
        ridersPerTeam: Int = RIDERS_PER_TEAM,
        usedNames: MutableSet<String> = mutableSetOf(),
    ): List<RiderSpec> {
        require(ridersPerTeam >= 1) { "a team needs at least one rider" }
        val random = Random(mix(seed, teamIndex.toLong()))
        val nameRandom = Random(mix(seed, 1_000L + teamIndex))
        val strength = random.nextDouble()
        val seen = mutableSetOf<Archetype>()
        return lineup(random, ridersPerTeam).map { archetype ->
            val base = 0.35 * strength + 0.65 * random.nextDouble()
            val talent = if (archetype in LEADER_ARCHETYPES && seen.add(archetype)) {
                LEADER_TALENT_FLOOR + (1.0 - LEADER_TALENT_FLOOR) * base
            } else {
                base
            }
            RiderSpec(Names.riderName(nameRandom, usedNames), archetype, generateProfile(random, archetype, talent))
        }
    }

    /** The archetypes of a team: [CORE] first (truncated for tiny teams), then seeded extras. */
    fun lineup(random: Random, size: Int): List<Archetype> {
        val extras = List((size - CORE.size).coerceAtLeast(0)) {
            if (random.nextBoolean()) {
                Archetype.DOMESTIQUE
            } else {
                listOf(Archetype.CLIMBER, Archetype.PUNCHEUR, Archetype.ROULEUR).random(random)
            }
        }
        return CORE.take(size) + extras
    }

    /**
     * One rider profile of [archetype] for a [talent] in [0, 1]. Talent mostly drives CP/kg, then durability,
     * W′ and pMax; mass, CdA and descending are mostly free (CdA grows with mass).
     */
    fun generateProfile(random: Random, archetype: Archetype, talent: Double): RiderProfile {
        val r = ArchetypeRanges.of(archetype)
        val t = talent.coerceIn(0.0, 1.0)
        fun blend(weight: Double) = weight * t + (1.0 - weight) * random.nextDouble()
        val massU = random.nextDouble()
        val massKg = r.massKg.lerp(massU)
        val cp = (r.wkg.lerp(blend(0.8)) * massKg).coerceIn(r.cp.lo, r.cp.hi)
        val explosive = archetype == Archetype.SPRINTER || archetype == Archetype.PUNCHEUR
        val wPrime = r.wPrime.lerp(blend(if (explosive) 0.5 else 0.3))
        val pMax = r.pMax.lerp(blend(if (archetype == Archetype.SPRINTER) 0.6 else 0.3))
        val cda = r.cda.lerp(0.5 * massU + 0.5 * random.nextDouble())
        val durability = r.durability.lerp(blend(0.6))
        val descending = r.descending.lerp(random.nextDouble())
        return RiderProfile(
            massKg = round(massKg, 10.0).coerceIn(r.massKg.lo, r.massKg.hi),
            cp = round(cp, 1.0).coerceIn(r.cp.lo, r.cp.hi),
            wPrime = round(wPrime, 0.01),
            pMax = round(pMax, 0.1),
            cda = round(cda, 1000.0).coerceIn(r.cda.lo, r.cda.hi),
            durability = round(durability, 100.0).coerceIn(r.durability.lo, r.durability.hi),
            descending = round(descending, 100.0).coerceIn(r.descending.lo, r.descending.hi),
        )
    }

    /** Rounds to 1/[perUnit] (e.g. 10.0 → 0.1, 0.01 → hundreds). */
    private fun round(v: Double, perUnit: Double): Double = (v * perUnit).roundToLong() / perUnit

    /** SplitMix64 finalizer of (seed, stream): well-spread independent seeds for each stream. */
    internal fun mix(seed: Long, stream: Long): Long {
        var z = seed + (stream + 1) * -7046029254386353131L // 0x9E3779B97F4A7C15
        z = (z xor (z ushr 30)) * -4658895280553007687L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -7723592293110705685L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }
}
