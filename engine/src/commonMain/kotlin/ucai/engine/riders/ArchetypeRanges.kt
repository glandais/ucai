package ucai.engine.riders

import ucai.api.Archetype
import ucai.api.RiderProfile
import ucai.api.RiderSpec

/** Closed range of doubles used to describe the generator's distributions. */
data class Span(val lo: Double, val hi: Double) {
    init {
        require(lo <= hi) { "empty span [$lo, $hi]" }
    }

    fun lerp(u: Double): Double = lo + (hi - lo) * u.coerceIn(0.0, 1.0)
    operator fun contains(v: Double): Boolean = v in lo..hi
}

/**
 * Generation ranges of one archetype (DESIGN §2 "Rider profile"). Mass is rider + bike.
 * CP is drawn as power-to-weight ([wkg] × mass) then clamped to [cp], so the W/kg stays coherent with the mass.
 */
data class ArchetypeRanges(
    val massKg: Span,
    val wkg: Span,
    val cp: Span,
    val wPrime: Span,
    val pMax: Span,
    val cda: Span,
    val durability: Span,
    val descending: Span,
) {
    companion object {
        /** The design table: every archetype range sits inside it. */
        val DESIGN = ArchetypeRanges(
            massKg = Span(62.0, 88.0),
            wkg = Span(4.3, 6.25),
            cp = Span(330.0, 440.0),
            wPrime = Span(15_000.0, 35_000.0),
            pMax = Span(900.0, 1700.0),
            cda = Span(0.30, 0.38),
            durability = Span(0.0, 1.0),
            descending = Span(0.0, 1.0),
        )

        /**
         * Climber: light, highest CP/kg, small W′ and modest pMax. Puncheur: explosive (big W′), good CP/kg.
         * Sprinter: heavy, highest pMax, low CP/kg. Rouleur: heavy, highest absolute CP, less aero.
         * All-rounder: balanced with the best durability. Domestique: solid but lower CP/kg and durability.
         */
        fun of(archetype: Archetype): ArchetypeRanges = when (archetype) {
            Archetype.CLIMBER -> ArchetypeRanges(
                Span(62.0, 69.0), Span(5.55, 6.25), Span(345.0, 430.0), Span(16_000.0, 23_000.0),
                Span(900.0, 1150.0), Span(0.30, 0.33), Span(0.72, 0.88), Span(0.35, 0.80),
            )
            Archetype.PUNCHEUR -> ArchetypeRanges(
                Span(67.0, 75.0), Span(5.20, 5.75), Span(360.0, 430.0), Span(24_000.0, 32_000.0),
                Span(1150.0, 1450.0), Span(0.31, 0.34), Span(0.68, 0.85), Span(0.45, 0.90),
            )
            Archetype.SPRINTER -> ArchetypeRanges(
                Span(75.0, 88.0), Span(4.50, 4.95), Span(345.0, 415.0), Span(27_000.0, 35_000.0),
                Span(1450.0, 1700.0), Span(0.32, 0.35), Span(0.60, 0.78), Span(0.45, 0.90),
            )
            Archetype.ROULEUR -> ArchetypeRanges(
                Span(76.0, 86.0), Span(4.90, 5.25), Span(380.0, 440.0), Span(18_000.0, 25_000.0),
                Span(1000.0, 1300.0), Span(0.33, 0.38), Span(0.72, 0.90), Span(0.50, 0.90),
            )
            Archetype.ALL_ROUNDER -> ArchetypeRanges(
                Span(67.0, 75.0), Span(5.35, 5.85), Span(370.0, 435.0), Span(20_000.0, 27_000.0),
                Span(1050.0, 1250.0), Span(0.31, 0.35), Span(0.75, 0.90), Span(0.50, 0.85),
            )
            Archetype.DOMESTIQUE -> ArchetypeRanges(
                Span(64.0, 84.0), Span(4.55, 5.15), Span(330.0, 400.0), Span(15_000.0, 24_000.0),
                Span(900.0, 1250.0), Span(0.31, 0.37), Span(0.45, 0.65), Span(0.30, 0.80),
            )
        }
    }
}

/**
 * Hard limits for riders supplied explicitly at team registration. Wider than the design table ("typical"
 * values) so custom riders are possible, but they reject nonsense that would break the physics.
 */
object ProfileLimits {
    val massKg = Span(45.0, 110.0)
    val cp = Span(150.0, 550.0)
    val wPrime = Span(5_000.0, 50_000.0)
    val pMax = Span(500.0, 2_500.0)
    val cda = Span(0.20, 0.50)
    val unit = Span(0.0, 1.0)
    const val MAX_NAME_LENGTH = 64

    /** Problems with [spec], empty when it is acceptable. */
    fun validate(spec: RiderSpec): List<String> = buildList {
        if (spec.name.isBlank()) add("name is blank")
        if (spec.name.length > MAX_NAME_LENGTH) add("name is longer than $MAX_NAME_LENGTH characters")
        addAll(validate(spec.profile))
    }

    fun validate(p: RiderProfile): List<String> = buildList {
        fun check(field: String, v: Double, span: Span) {
            if (!v.isFinite() || v !in span) add("$field = $v is outside [${span.lo}, ${span.hi}]")
        }
        check("massKg", p.massKg, massKg)
        check("cp", p.cp, cp)
        check("wPrime", p.wPrime, wPrime)
        check("pMax", p.pMax, pMax)
        check("cda", p.cda, cda)
        check("durability", p.durability, unit)
        check("descending", p.descending, unit)
        if (p.pMax.isFinite() && p.cp.isFinite() && p.pMax <= p.cp) add("pMax must be above cp")
    }
}
