package ucai.engine.race

import ucai.api.Climb
import ucai.api.RiderProfile
import ucai.engine.course.COURSE_STEP_M
import ucai.engine.course.Course
import ucai.engine.course.EARTH_RADIUS_M
import ucai.engine.course.TrackPoint
import kotlin.math.PI
import kotlin.random.Random

/** Small hand-written rider profiles (the real generator lives in ucai.engine.riders). */
object Fixtures {
    val ROULEUR = RiderProfile(massKg = 80.0, cp = 400.0, wPrime = 22_000.0, pMax = 1300.0, cda = 0.32, durability = 0.6, descending = 0.6)
    val CLIMBER = RiderProfile(massKg = 66.0, cp = 380.0, wPrime = 20_000.0, pMax = 1000.0, cda = 0.30, durability = 0.7, descending = 0.5)
    val SPRINTER = RiderProfile(massKg = 82.0, cp = 360.0, wPrime = 30_000.0, pMax = 1600.0, cda = 0.33, durability = 0.6, descending = 0.6)
    val WEAK = RiderProfile(massKg = 80.0, cp = 300.0, wPrime = 15_000.0, pMax = 900.0, cda = 0.34, durability = 0.4, descending = 0.4)
    val STRONG = RiderProfile(massKg = 72.0, cp = 440.0, wPrime = 25_000.0, pMax = 1200.0, cda = 0.31, durability = 0.8, descending = 0.8)

    /** [count] identical riders, ids from [firstId], teams of [perTeam] named "T0", "T1"... */
    fun riders(count: Int, profile: RiderProfile = ROULEUR, firstId: Int = 1, perTeam: Int = 7): List<RaceEntrant> =
        (0 until count).map { RaceEntrant(firstId + it, "T${it / perTeam}", profile) }

    /** A 10-team × 7-rider field with seeded, varied profiles. */
    fun field(seed: Long = 7, teams: Int = 10, perTeam: Int = 7): List<RaceEntrant> {
        val rnd = Random(seed)
        val bases = listOf(ROULEUR, CLIMBER, SPRINTER, STRONG, ROULEUR.copy(cp = 370.0), CLIMBER.copy(cp = 360.0), WEAK.copy(cp = 340.0))
        return (0 until teams * perTeam).map { i ->
            val b = bases[i % perTeam]
            val p = b.copy(
                cp = b.cp * rnd.nextDouble(0.95, 1.05),
                wPrime = b.wPrime * rnd.nextDouble(0.9, 1.1),
                durability = (b.durability + rnd.nextDouble(-0.1, 0.1)).coerceIn(0.0, 1.0),
            )
            RaceEntrant(i + 1, "T${(i / perTeam).toString().padStart(2, '0')}", p)
        }
    }

    /**
     * A straight north-bound course of [lengthM] whose gradient is [gradient] (of distance), sampled every 10 m.
     */
    fun course(
        lengthM: Double,
        climbs: List<Climb> = emptyList(),
        id: String = "test",
        gradient: (Double) -> Double = { 0.0 },
    ): Course {
        val degLatM = EARTH_RADIUS_M * PI / 180.0
        val n = (lengthM / COURSE_STEP_M).toInt()
        var ele = 100.0
        val pts = (0..n).map { k ->
            val d = if (k == n) lengthM else k * COURSE_STEP_M
            val gr = gradient(d)
            TrackPoint(45.0 + d / degLatM, 9.0, ele, d, gr, 0.0).also { ele += gr * COURSE_STEP_M }
        }
        return Course(id, id, lengthM, 0.0, points = pts, climbs = climbs)
    }

    /** ~295 km with a few climbs and descents, like a monument. */
    fun monument(): Course {
        val climbs = listOf(
            Climb("Col A", 140_000.0, 146_000.0, 0.05, 0.05),
            Climb("Côte B", 250_000.0, 253_000.0, 0.06, 0.06),
            Climb("Côte C", 285_000.0, 288_000.0, 0.05, 0.05),
        )
        return course(295_000.0, climbs, "monument") { d ->
            when {
                climbs.any { d >= it.startM && d < it.endM } -> climbs.first { d >= it.startM && d < it.endM }.avgGrade
                climbs.any { d >= it.endM && d < it.endM + 4_000 } -> -0.06
                else -> 0.0
            }
        }
    }
}
