package ucai.engine.riders

import ucai.api.Archetype
import ucai.api.Climb
import ucai.api.RiderProfile
import ucai.api.RiderRatings
import ucai.api.RiderSpec
import ucai.engine.course.Course
import ucai.engine.course.TrackPoint
import ucai.engine.physics.r1
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RidersTest {
    private val seeds = 0L until 50L

    @Test
    fun sameSeedSameField() {
        val a = Field.generate(42)
        val b = Field.generate(42)
        assertEquals(a.teams, b.teams)
        assertEquals(10, a.teams.size)
        assertTrue(a.teams.all { it.riders.size == 7 })
        assertEquals((1..70).toList(), a.riders.map { it.id })
    }

    @Test
    fun differentSeedsDiffer() {
        val fields = seeds.map { Field.generate(it) }
        val profiles = fields.map { f -> f.riders.map { it.profile } }
        assertEquals(profiles.size, profiles.toSet().size, "every seed gives a different field")
        assertNotEquals(fields[0].riders.map { it.name }, fields[1].riders.map { it.name })
        assertTrue(fields.map { f -> f.teams.map { it.info.name } }.toSet().size > 1, "team names are shuffled")
    }

    @Test
    fun namesAndColorsAreUniqueAndFictional() {
        for (seed in seeds) {
            val f = Field.generate(seed)
            assertEquals(70, f.riders.map { it.name }.toSet().size)
            assertEquals(10, f.teams.map { it.info.name }.toSet().size)
            assertEquals(10, f.teams.map { it.info.color }.toSet().size)
            assertTrue(f.teams.all { Regex("#[0-9a-f]{6}").matches(it.info.color) })
            assertTrue(f.teams.all { t -> t.riders.all { it.teamId == t.info.id } })
        }
    }

    @Test
    fun rangesRespected() {
        val design = ArchetypeRanges.DESIGN
        for (seed in seeds) for (r in Field.generate(seed).riders) {
            val p = r.profile
            val a = ArchetypeRanges.of(r.archetype)
            for ((span, v) in listOf(
                a.massKg to p.massKg, a.cp to p.cp, a.wPrime to p.wPrime, a.pMax to p.pMax,
                a.cda to p.cda, a.durability to p.durability, a.descending to p.descending,
            )) assertTrue(v in span, "${r.archetype} $v not in $span")
            for ((span, v) in listOf(
                design.massKg to p.massKg, design.cp to p.cp, design.wPrime to p.wPrime,
                design.pMax to p.pMax, design.cda to p.cda,
            )) assertTrue(v in span, "$v not in design $span")
            assertTrue(ProfileLimits.validate(r.toSpec()).isEmpty())
        }
    }

    @Test
    fun archetypeRangesFitTheDesignTable() {
        val d = ArchetypeRanges.DESIGN
        for (arch in Archetype.entries) {
            val a = ArchetypeRanges.of(arch)
            for ((inner, outer) in listOf(
                a.massKg to d.massKg, a.cp to d.cp, a.wPrime to d.wPrime, a.pMax to d.pMax, a.cda to d.cda,
                a.durability to d.durability, a.descending to d.descending,
            )) assertTrue(inner.lo >= outer.lo && inner.hi <= outer.hi, "$arch $inner outside $outer")
        }
    }

    @Test
    fun balancedTeams() {
        for (seed in seeds) for (t in Field.generate(seed).teams) {
            val counts = t.riders.groupingBy { it.archetype }.eachCount()
            for (arch in RiderGenerator.CORE) assertTrue((counts[arch] ?: 0) >= 1, "${t.info.name} lacks $arch")
            assertTrue(counts.values.all { it <= 2 })
        }
    }

    @Test
    fun archetypesAreCoherent() {
        val riders = seeds.flatMap { Field.generate(it).riders }
        fun avg(arch: Archetype, f: (RiderProfile) -> Double) =
            riders.filter { it.archetype == arch }.map { f(it.profile) }.average()
        val wkg = { p: RiderProfile -> p.cp / p.massKg }

        // Climbers: lightest, best CP/kg, modest pMax.
        for (other in Archetype.entries - Archetype.CLIMBER) {
            assertTrue(avg(Archetype.CLIMBER, wkg) > avg(other, wkg), "climber W/kg vs $other")
            assertTrue(avg(Archetype.CLIMBER) { it.massKg } < avg(other) { it.massKg }, "climber mass vs $other")
        }
        assertTrue(avg(Archetype.CLIMBER) { it.pMax } < avg(Archetype.PUNCHEUR) { it.pMax })
        // Sprinters: heavy, highest pMax, lowest W/kg.
        for (other in Archetype.entries - Archetype.SPRINTER) {
            assertTrue(avg(Archetype.SPRINTER) { it.pMax } > avg(other) { it.pMax }, "sprinter pMax vs $other")
            assertTrue(avg(Archetype.SPRINTER, wkg) < avg(other, wkg), "sprinter W/kg vs $other")
        }
        assertTrue(avg(Archetype.SPRINTER) { it.massKg } > 78.0)
        // Rouleurs: the highest absolute CP.
        for (other in Archetype.entries - Archetype.ROULEUR) {
            assertTrue(avg(Archetype.ROULEUR) { it.cp } > avg(other) { it.cp }, "rouleur CP vs $other")
        }
        // Puncheurs: the biggest W′ after sprinters.
        assertTrue(avg(Archetype.PUNCHEUR) { it.wPrime } > avg(Archetype.CLIMBER) { it.wPrime })
        assertTrue(avg(Archetype.PUNCHEUR) { it.wPrime } > avg(Archetype.ALL_ROUNDER) { it.wPrime })
        // Domestiques: the lowest durability.
        for (other in Archetype.entries - Archetype.DOMESTIQUE) {
            assertTrue(avg(Archetype.DOMESTIQUE) { it.durability } < avg(other) { it.durability })
        }
    }

    @Test
    fun ratingsRelativeToTheField() {
        val f = Field.generate(7)
        for (axis in RatingAxis.entries) {
            val ratings = f.riders.map { RatingScale.of(f.riders.map { r -> r.profile }).rating(axis, it.profile) }
            assertTrue(ratings.all { it in RiderRatings.RANGE })
            // Quintiles of 70 riders: about 14 per rating, ties aside.
            for (r in RiderRatings.RANGE) assertTrue(ratings.count { it == r } in 8..20, "$axis rating $r: ${ratings.count { it == r }}")
            // Monotonic in the metric.
            val sorted = f.riders.sortedBy { axis.metric(it.profile) }
            val rs = sorted.map { RatingScale.of(f.riders.map { r -> r.profile }).rating(axis, it.profile) }
            assertEquals(rs.sorted(), rs, "$axis ratings follow the metric")
        }
        val stored = f.riders.map { it.ratings }
        val scale = RatingScale.of(f.riders.map { it.profile })
        assertEquals(f.riders.map { scale.rate(it.profile) }, stored)
    }

    @Test
    fun ratingsMatchArchetypes() {
        val riders = seeds.flatMap { Field.generate(it).riders }
        fun avg(arch: Archetype, f: (RiderRatings) -> Int) =
            riders.filter { it.archetype == arch }.map { f(it.ratings).toDouble() }.average()
        assertTrue(avg(Archetype.CLIMBER) { it.climbing } > 4.0)
        assertTrue(avg(Archetype.SPRINTER) { it.sprint } > 4.0)
        assertTrue(avg(Archetype.SPRINTER) { it.climbing } < 2.0)
        assertTrue(avg(Archetype.PUNCHEUR) { it.punch } > 3.5)
        assertTrue(avg(Archetype.DOMESTIQUE) { it.endurance } < 2.0)
    }

    @Test
    fun credibleLeaderForEachCourse() {
        for (seed in seeds) {
            val f = Field.generate(seed)
            val scale = RatingScale.of(f.riders.map { it.profile })
            for (kind in CourseKind.entries) {
                val fits = f.riders.map { kind.fit(it.profile, scale) }.sorted()
                // Credible: in the top half of the whole field for this course, with leader-grade numbers.
                val median = fits[fits.size / 2]
                for (t in f.teams) {
                    val leader = t.riders.leaderFor(kind, scale)
                    val p = leader.profile
                    val where = "seed $seed ${t.info.name} $kind ${leader.archetype}"
                    assertTrue(kind.fit(p, scale) >= median, "$where fit ${kind.fit(p, scale).r1()} < ${median.r1()}")
                    when (kind) {
                        CourseKind.CLIMBING -> {
                            assertTrue(leader.archetype in setOf(Archetype.CLIMBER, Archetype.ALL_ROUNDER), where)
                            assertTrue(p.cp / p.massKg >= 5.5, "$where W/kg ${(p.cp / p.massKg).r1()}")
                            assertTrue(p.durability >= 0.72, "$where durability ${p.durability}")
                        }
                        CourseKind.PUNCHY -> {
                            assertTrue(leader.archetype in setOf(Archetype.PUNCHEUR, Archetype.ALL_ROUNDER, Archetype.CLIMBER), where)
                            assertTrue(RatingAxis.PUNCH.metric(p) >= 10.5, "$where punch ${RatingAxis.PUNCH.metric(p).r1()}")
                            assertTrue(p.cp / p.massKg >= 5.3, "$where W/kg ${(p.cp / p.massKg).r1()}")
                        }
                        CourseKind.SPRINT -> {
                            assertTrue(leader.archetype in setOf(Archetype.SPRINTER, Archetype.PUNCHEUR), where)
                            assertTrue(p.pMax >= 1200.0, "$where pMax ${p.pMax}")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun publicAndPrivateMapping() {
        val f = Field.generate(3)
        val pub = f.publicRiders()
        assertEquals(f.riders.map { it.id }, pub.map { it.id })
        for ((priv, p) in f.riders.zip(pub)) {
            assertEquals(priv.name, p.name)
            assertEquals(priv.teamId, p.teamId)
            assertEquals(priv.archetype, p.archetype)
            assertEquals(priv.ratings, p.ratings)
        }
        val team = f.teams[4]
        assertEquals(team.riders, f.teamRiders(team.info.id))
        assertEquals(f.riders[10], f.rider(11))
        assertFailsWith<NoSuchElementException> { f.teamRiders("nope") }
        val spec = f.riders[0].toSpec()
        assertEquals(f.riders[0], spec.toPrivate(1, f.riders[0].teamId, f.riders[0].ratings))
    }

    private fun customTeam(prefix: String) = List(7) { k ->
        RiderSpec(
            "$prefix Rider $k", if (k == 0) Archetype.CLIMBER else Archetype.DOMESTIQUE,
            RiderProfile(70.0, 360.0 + k, 20_000.0, 1100.0, 0.33, 0.6, 0.5),
        )
    }

    @Test
    fun explicitRidersReplaceGeneratedOnes() {
        val seed = 11L
        val ids = RiderGenerator.teamIdentities(seed)
        val custom = customTeam("Custom")
        val entries = ids.mapIndexed { i, e -> if (i == 2) e.copy(riders = custom) else e }
        val f = Field.build(seed, entries)
        val generated = Field.generate(seed)

        assertEquals(custom, f.teams[2].riders.map { it.toSpec() })
        // The other teams keep the same generated riders (independent streams per team).
        for (i in ids.indices - 2) {
            assertEquals(generated.teams[i].riders.map { it.profile }, f.teams[i].riders.map { it.profile })
        }
        assertEquals((1..70).toList(), f.riders.map { it.id })
        assertTrue(f.riders.all { it.ratings.climbing in RiderRatings.RANGE })
    }

    @Test
    fun explicitRidersAreValidated() {
        val ids = RiderGenerator.teamIdentities(1)
        fun build(riders: List<RiderSpec>) = Field.build(1, ids.mapIndexed { i, e -> if (i == 0) e.copy(riders = riders) else e })
        val ok = customTeam("X")
        assertFailsWith<IllegalArgumentException> { build(ok.take(6)) }
        assertFailsWith<IllegalArgumentException> { build(ok.mapIndexed { k, s -> if (k == 0) s.copy(profile = s.profile.copy(cp = Double.NaN)) else s }) }
        assertFailsWith<IllegalArgumentException> { build(ok.mapIndexed { k, s -> if (k == 0) s.copy(profile = s.profile.copy(massKg = 20.0)) else s }) }
        assertFailsWith<IllegalArgumentException> { build(ok.mapIndexed { k, s -> if (k == 0) s.copy(profile = s.profile.copy(durability = 1.5)) else s }) }
        assertFailsWith<IllegalArgumentException> { build(ok.mapIndexed { k, s -> if (k == 1) s.copy(name = ok[0].name) else s }) }
        assertFailsWith<IllegalArgumentException> { build(ok.map { it.copy(name = " ") }) }
        assertFailsWith<IllegalArgumentException> {
            Field.build(1, listOf(ids[0], ids[0]))
        }
    }

    @Test
    fun referenceScaleAndRerating() {
        val f = Field.generate(5)
        val ref = f.ratedWith(RatingScale.REFERENCE)
        assertEquals(f.riders.map { it.profile }, ref.riders.map { it.profile })
        // A field of 70 rated against a 1400-rider reference stays close to its self-rating.
        val diffs = f.riders.zip(ref.riders).map { (a, b) -> kotlin.math.abs(a.ratings.climbing - b.ratings.climbing) }
        assertTrue(diffs.all { it <= 1 }, "climbing ratings vs reference: $diffs")
    }

    @Test
    fun generateProfileIsDeterministicAndTalentMatters() {
        val a = RiderGenerator.generateProfile(Random(1), Archetype.CLIMBER, 0.5)
        val b = RiderGenerator.generateProfile(Random(1), Archetype.CLIMBER, 0.5)
        assertEquals(a, b)
        val low = (0 until 200).map { RiderGenerator.generateProfile(Random(it), Archetype.PUNCHEUR, 0.0) }
        val high = (0 until 200).map { RiderGenerator.generateProfile(Random(it), Archetype.PUNCHEUR, 1.0) }
        assertTrue(high.map { it.cp / it.massKg }.average() > low.map { it.cp / it.massKg }.average() + 0.3)
        assertTrue(high.map { it.durability }.average() > low.map { it.durability }.average())
    }

    @Test
    fun courseKinds() {
        fun course(id: String, distanceM: Double, gainM: Double, climbs: List<Climb> = emptyList()) = Course(
            id, id, distanceM, gainM,
            points = listOf(TrackPoint(45.0, 9.0, 0.0, 0.0, 0.0, 0.0), TrackPoint(45.1, 9.0, 0.0, distanceM, 0.0, 0.0)),
            climbs = climbs,
        )
        assertEquals(CourseKind.SPRINT, CourseKind.of(course("milano-sanremo", 297_000.0, 2_000.0)))
        assertEquals(CourseKind.PUNCHY, CourseKind.of(course("liege-bastogne-liege", 257_000.0, 4_000.0)))
        assertEquals(CourseKind.CLIMBING, CourseKind.of(course("il-lombardia", 238_000.0, 4_500.0)))
        assertEquals(CourseKind.SPRINT, CourseKind.of(course("flat", 200_000.0, 800.0)))
        val short = listOf(Climb("Wall", 1_000.0, 2_500.0, 0.09, 0.15))
        assertEquals(CourseKind.PUNCHY, CourseKind.of(course("hills", 200_000.0, 3_000.0, short)))
        val long = short + Climb("Pass", 10_000.0, 18_000.0, 0.06, 0.1)
        assertEquals(CourseKind.CLIMBING, CourseKind.of(course("mountains", 200_000.0, 3_000.0, long)))
    }
}
