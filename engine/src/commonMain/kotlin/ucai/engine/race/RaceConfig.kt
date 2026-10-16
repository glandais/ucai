package ucai.engine.race

import ucai.api.RiderProfile

/** A rider on the start list: [id] is unique in the race, [teamId] is the controlling team. */
data class RaceEntrant(val id: Int, val teamId: String, val profile: RiderProfile)

/**
 * Tunable parameters of the race engine (DESIGN §3). Defaults follow the design; tests and the calibration
 * harness may change them.
 */
data class RaceConfig(
    /** Default rotation intensity at the front, fraction of cpEff. */
    val defaultIntensity: Double = 0.65,
    /** Turn length bounds of the default rotation (seeded, inclusive), s. */
    val turnMinS: Int = 30,
    val turnMaxS: Int = 60,
    /** A rotation member whose W′bal is below this fraction of wPrimeEff skips turns. */
    val rotationMinWPrimeFraction: Double = 0.3,
    /** Split: gap to the rider ahead above [splitGapS] for [splitTicks] consecutive ticks. */
    val splitGapS: Double = 3.0,
    val splitTicks: Int = 3,
    /** Merge: a group within [mergeGapS] of the group ahead joins it. */
    val mergeGapS: Double = 1.0,
    /** Gradient at or below which a group is on a descent: speed cap, followers freewheel, no splits. */
    val descentGradient: Double = -0.03,
    /** A detached rider rejoins the body of its group within this distance (m) when not slower than it. */
    val attachM: Double = 3.0,
    /** Draft of a detached rider fades from the 2nd-wheel factor at 0 m to none at this distance (m). */
    val draftFadeM: Double = 10.0,
    /** Max intensity (fraction of cpEff) a `recover` rider spends to stay in a group, and when alone. */
    val recoverHoldIntensity: Double = 0.75,
    val recoverSoloIntensity: Double = 0.55,
    /** Max intensity of a rider waiting for its group (ahead of its body in `follow`). */
    val waitIntensity: Double = 0.8,
    /** Max closing speed above the reference speed when closing a gap (m/s), and seconds to close it. */
    val maxClosingSpeed: Double = 2.0,
    val closingTimeS: Double = 10.0,
    /** W′bal below this fraction of wPrimeEff counts as empty (kernel safeguards). */
    val wPrimeEmptyFraction: Double = 0.02,
    /** Intensity of the `pull` an attack turns into after its burst (or when W′ is empty). */
    val postAttackPullIntensity: Double = 0.9,
    /** A `sprint` rider stays in the group until this distance to go (m), then goes all-out. */
    val sprintLaunchM: Double = 300.0,
    /** A sprinter keeps the draft of a pulling teammate until this distance to go (m). */
    val leadOutEndM: Double = 200.0,
    /** Draft factor of the lead-out wheel. */
    val leadOutDraft: Double = 0.64,
    /** Positioning factor spread: ±10 % of effective sprint power. */
    val positioningSpread: Double = 0.10,
    /** Bias (in units of the spread, before clamping to ±1) for a sprinter whose teammate pulled in the last km. */
    val leadOutPositioningBias: Double = 0.5,
    /** Distance to go (m) within which a `pull` counts as a lead-out. */
    val leadOutZoneM: Double = 1000.0,
    /** UCI group-time rule: a finisher less than this behind the previous one gets its time. */
    val groupTimeGapS: Double = 1.0,
    /** Decision points. */
    val decisionPeriodS: Int = 30,
    val decisionMinSpacingS: Int = 5,
    /** Riders passing a summit reported in `climb_summit`, and how long to wait for them (s). */
    val summitRiders: Int = 10,
    val summitWaitS: Int = 60,
    /** Speed at km 0 (flying start), m/s. */
    val startSpeed: Double = 10.0,
    /** Safety stop. */
    val maxTicks: Int = 100_000,
)
