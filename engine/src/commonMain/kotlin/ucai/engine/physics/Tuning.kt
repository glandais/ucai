package ucai.engine.physics

/**
 * Every number the calibration pass tuned (DESIGN §3, §8), in one place. [ucai.engine.race.RaceConfig] takes its
 * defaults from here (tests and the harness may still override them per race); [Fatigue] reads the durability ones.
 * The scripted strategies have their own table, `ucai.team.strategy.Tactics` (team/ only depends on api/).
 * Calibration: `./gradlew :runner:calibrate --args="--seeds 1..8"`.
 */
object Tuning {
    // --- Default rotation (groups without a `pull` rider) ---
    /** Turn intensity at the front, fraction of cpEff (batch 1: 0.65, races ran at 31–37 km/h). */
    const val ROTATION_INTENSITY = 1.03
    /** Turn intensity on climbs: a 10–30 min climb is ridden near the 20-min power, above CP. */
    const val ROTATION_CLIMB_INTENSITY = 1.1
    /** Gradient from which the climb intensity applies. */
    const val ROTATION_CLIMB_GRADIENT = 0.03
    /** Share of the rotation (the strongest at the front on the current terrain) that takes turns. */
    const val ROTATION_SHARE = 0.5
    /** Turns shared by k riders are harder by × (1 + gain · (1 − 1/k)): a big organized group is faster than a pair. */
    const val ROTATION_SHARE_GAIN = 0.2
    /** A `follow` rider with a lower `maxIntensity` sits in and never takes turns. */
    const val ROTATION_MIN_INTENSITY = 0.9

    // --- Drafting (draftFactor) ---
    /**
     * Aero drag the riders behind take off the front rider of a big group: ≈ 2.6 % with one rider behind (Blocken
     * 2013), ≈ 5 % in a line of 4 (team pursuit, Barry 2015). With [FRONT_SHELTER_RIDERS] = 3 a group of 4 gets 5 %.
     */
    const val FRONT_SHELTER = 0.08
    /** Group size scale of the front shelter (riders behind). */
    const val FRONT_SHELTER_RIDERS = 3.0
    /** Draft floor deep in a tiny group and in a large peloton (fraction of the solo aero drag). */
    const val DRAFT_FLOOR_SMALL = 0.50
    const val DRAFT_FLOOR_LARGE = 0.25
    /** Group size scale from the small to the large floor. */
    const val DRAFT_FLOOR_SIZE = 20.0
    /** Ranks over which the draft goes from the 3rd wheel (0.55) to the floor. */
    const val DRAFT_DECAY_RANKS = 8.0

    // --- Terrain (Fatigue.terrainFactor) ---
    /** Extra power a rider sustains on a climb, fraction of cpEff (0 = same as on the flat). */
    const val UPHILL_POWER_GAIN = 0.10
    /** Gradient where the uphill gain starts, and where it is full. */
    const val UPHILL_FROM_GRADIENT = 0.02
    const val UPHILL_FULL_GRADIENT = 0.06

    // --- Riders out of the race ---
    /** Pace of a `recover` rider alone, fraction of cpEff (batch 1: 0.55). */
    const val RECOVER_SOLO_INTENSITY = 0.7
    /** Stall guard: nobody goes slower than this, m/s (≈ 5.4 km/h, walking the bike up a 20 % ramp). */
    const val CRAWL_SPEED = 1.5

    // --- Durability (Fatigue.loss) ---
    /** Energy scale of the durability loss, kJ per kg of rider + bike. */
    const val DURABILITY_K = 220.0
    /** Exponent: < 1, the loss grows fast at first, then slowly over a 4000–5000 kJ monument. */
    const val DURABILITY_N = 0.7
    /** Work (kJ per kg) before durability costs anything (~200 kJ, the first half hour). */
    const val DURABILITY_ONSET_KJ_PER_KG = 5.0
    /** Durability never takes more than this fraction of CP and W′. */
    const val DURABILITY_MAX_LOSS = 0.35
}
