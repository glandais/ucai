package ucai.engine.riders

import ucai.api.RiderProfile
import ucai.api.RiderRatings

/** The five public rating axes and the exact-number metric each one ranks. */
enum class RatingAxis(val metric: (RiderProfile) -> Double) {
    /** Sustained power-to-weight: long climbs. */
    CLIMBING({ it.cp / it.massKg }),

    /** Roughly the best 1-minute power per kg (CP + W′ spent over 60 s): short steep climbs, attacks. */
    PUNCH({ (it.cp + it.wPrime / 60.0) / it.massKg }),

    /** Sprint top speed scales with (P / CdA)^(1/3), so pMax / CdA ranks it. */
    SPRINT({ it.pMax / it.cda }),

    /** Resistance to accumulated work. */
    ENDURANCE({ it.durability }),

    DESCENDING({ it.descending }),
}

/**
 * Ranks exact profiles against a reference field ("relative to the field", DESIGN §2): a rider's rating on an
 * axis is the quintile of their metric among the reference riders, so in a field rated against itself each
 * rating holds about a fifth of the riders. Ties share the lower quintile.
 */
class RatingScale private constructor(private val sorted: Map<RatingAxis, DoubleArray>) {

    /** Fraction of the reference riders strictly below [profile] on [axis], in [0, 1). */
    fun percentile(axis: RatingAxis, profile: RiderProfile): Double {
        val values = sorted.getValue(axis)
        return countBelow(values, axis.metric(profile)).toDouble() / values.size
    }

    fun rating(axis: RatingAxis, profile: RiderProfile): Int {
        val r = 1 + (RiderRatings.RANGE.last * percentile(axis, profile)).toInt()
        return r.coerceIn(RiderRatings.RANGE)
    }

    fun rate(profile: RiderProfile) = RiderRatings(
        climbing = rating(RatingAxis.CLIMBING, profile),
        punch = rating(RatingAxis.PUNCH, profile),
        sprint = rating(RatingAxis.SPRINT, profile),
        endurance = rating(RatingAxis.ENDURANCE, profile),
        descending = rating(RatingAxis.DESCENDING, profile),
    )

    companion object {
        /** A scale whose reference is [field] (usually the whole start list of a race). */
        fun of(field: List<RiderProfile>): RatingScale {
            require(field.isNotEmpty()) { "a rating scale needs at least one rider" }
            return RatingScale(RatingAxis.entries.associateWith { axis -> field.map(axis.metric).sorted().toDoubleArray() })
        }

        /**
         * A field-independent scale: generated fields for seeds 0 until 20 (1400 riders). Useful to rate riders
         * before the whole start list is known (e.g. at team registration).
         */
        val REFERENCE: RatingScale by lazy {
            of((0L until 20L).flatMap { seed -> RiderGenerator.generateTeams(seed).flatMap { t -> t.riders.orEmpty().map { it.profile } } })
        }

        private fun countBelow(sorted: DoubleArray, v: Double): Int {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sorted[mid] < v) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}
