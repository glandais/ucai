package ucai.engine.riders

import ucai.api.PrivateRider
import ucai.api.RiderProfile
import ucai.engine.course.Course

/** What kind of finale a course has, which decides the leader profile (DESIGN §5 "Roles"). */
enum class CourseKind(private val weights: Map<RatingAxis, Double>) {
    /** Milano-Sanremo: long, flat-ish, short climbs then a sprint. */
    SPRINT(mapOf(RatingAxis.SPRINT to 0.8, RatingAxis.PUNCH to 0.15, RatingAxis.ENDURANCE to 0.05)),

    /** Liège-Bastogne-Liège: many short steep climbs. */
    PUNCHY(mapOf(RatingAxis.PUNCH to 0.5, RatingAxis.CLIMBING to 0.25, RatingAxis.ENDURANCE to 0.25)),

    /** Il Lombardia: long climbs. */
    CLIMBING(mapOf(RatingAxis.CLIMBING to 0.6, RatingAxis.ENDURANCE to 0.3, RatingAxis.DESCENDING to 0.1)),
    ;

    /** Course fit of [profile] in [0, 1): weighted percentiles against [scale]. */
    fun fit(profile: RiderProfile, scale: RatingScale): Double =
        weights.entries.sumOf { (axis, w) -> w * scale.percentile(axis, profile) }

    companion object {
        private val KNOWN = mapOf(
            "milano-sanremo" to SPRINT,
            "liege-bastogne-liege" to PUNCHY,
            "il-lombardia" to CLIMBING,
        )

        /** Known monuments by id; otherwise < 10 m of climbing per km is a sprint, a ≥ 6 km climb is climbing. */
        fun of(course: Course): CourseKind = KNOWN[course.id] ?: when {
            course.elevationGainM / (course.distanceM / 1000.0) < 10.0 -> SPRINT
            course.climbs.any { it.endM - it.startM >= 6_000.0 } -> CLIMBING
            else -> PUNCHY
        }
    }
}

/** The team's best leader for [kind]: highest [CourseKind.fit], lowest id on ties. */
fun List<PrivateRider>.leaderFor(kind: CourseKind, scale: RatingScale): PrivateRider {
    require(isNotEmpty()) { "no riders" }
    return maxWith(compareBy<PrivateRider> { kind.fit(it.profile, scale) }.thenByDescending { it.id })
}
