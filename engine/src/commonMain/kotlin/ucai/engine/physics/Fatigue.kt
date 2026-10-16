package ucai.engine.physics

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Durability and W′ model (DESIGN §3 "Fatigue").
 *
 * `loss = (1 − durability) · (max(0, energyKj / massKg − E0) / K)^N`, capped at [MAX_LOSS] (constants in [Tuning]).
 *
 * The batch-1 form was quadratic, `(energyKj / (massKg · K))²`. It could not hit both calibration targets — a
 * domestique (durability 0.5, 70 kg) losing 10–15 % of CP at 2000 kJ and a protected leader (0.8) ~5 % at 1500 kJ —
 * and, worse, a monument costs every rider 4000–5000 kJ (≈ 170 W average for 7 h, like real power files), where the
 * quadratic form put the whole field at 25–35 %. A concave power law (N = 0.7) after a short onset (E0 = 3 kJ/kg, the
 * first ~30 min cost nothing) hits both targets (13.9 % and 4.0 %; 5 % for a durability-0.75 leader) and keeps
 * growing slowly: at the finish a leader (0.8, ~4500 kJ) is at ~9 %, a domestique (0.55, ~5000 kJ) at ~22 %.
 */
object Fatigue {
    const val K = Tuning.DURABILITY_K
    const val N = Tuning.DURABILITY_N
    const val ONSET_KJ_PER_KG = Tuning.DURABILITY_ONSET_KJ_PER_KG
    const val MAX_LOSS = Tuning.DURABILITY_MAX_LOSS

    fun loss(durability: Double, energyKj: Double, massKg: Double): Double {
        val x = max(0.0, energyKj / massKg - ONSET_KJ_PER_KG) / K
        return min(MAX_LOSS, (1.0 - durability.coerceIn(0.0, 1.0)) * x.pow(N))
    }

    /**
     * Base recovery time constant (s), Skiba et al. 2012: τ = 546·e^(−0.01·D_CP) + 316,
     * D_CP = cpEff − P (W below CP). Durability then scales it by (1 + loss).
     */
    fun tauBase(cpEff: Double, power: Double): Double =
        546.0 * exp(-0.01 * max(0.0, cpEff - power)) + 316.0

    /**
     * One tick of the W′ balance: drains by (P − cpEff)·dt above CP, recovers exponentially
     * towards [wPrimeEff] below, with τ = τ_base·(1 + loss). Result in [0, wPrimeEff].
     */
    fun nextWPrimeBal(
        wPrimeBal: Double,
        power: Double,
        cpEff: Double,
        wPrimeEff: Double,
        loss: Double,
        dt: Double = PhysicsConstants.DT,
    ): Double {
        val next = if (power > cpEff) {
            wPrimeBal - (power - cpEff) * dt
        } else {
            val tau = tauBase(cpEff, power) * (1.0 + loss)
            wPrimeEff - (wPrimeEff - wPrimeBal) * exp(-dt / tau)
        }
        return next.coerceIn(0.0, wPrimeEff)
    }

    /**
     * Uphill, riders hold more power than on the flat for the same effort (lower speed and inertia, more torque per
     * pedal stroke: field and lab studies find 3–8 % more uphill). 1 below [Tuning.UPHILL_FROM_GRADIENT], rising
     * linearly to 1 + [Tuning.UPHILL_POWER_GAIN] at [Tuning.UPHILL_FULL_GRADIENT].
     */
    fun terrainFactor(gradient: Double): Double {
        val span = Tuning.UPHILL_FULL_GRADIENT - Tuning.UPHILL_FROM_GRADIENT
        return 1.0 + Tuning.UPHILL_POWER_GAIN * ((gradient - Tuning.UPHILL_FROM_GRADIENT) / span).coerceIn(0.0, 1.0)
    }

    /** Highest power holdable for [dt]: cpEff plus whatever W′ is left. At W′bal = 0 this is cpEff. */
    fun maxSustainablePower(cpEff: Double, wPrimeBal: Double, dt: Double = PhysicsConstants.DT): Double =
        cpEff + max(0.0, wPrimeBal) / dt

    /**
     * Freshness factor of the sprint, f(W′bal / wPrimeEff): 1 when W′ is full, 0.5 when empty,
     * concave (√) so a partly used W′ still sprints well.
     */
    fun sprintFreshness(wPrimeFraction: Double): Double = 0.5 + 0.5 * sqrt(wPrimeFraction.coerceIn(0.0, 1.0))

    /** Sprint power = pMax · f(W′bal / wPrimeEff) · (1 − loss), never below cpEff. */
    fun sprintPower(pMax: Double, wPrimeBal: Double, wPrimeEff: Double, loss: Double, cpEff: Double): Double {
        val fraction = if (wPrimeEff > 0.0) wPrimeBal / wPrimeEff else 0.0
        return max(cpEff, pMax * sprintFreshness(fraction) * (1.0 - loss))
    }
}

/** Live physiology of one rider: accumulated work and W′ balance, updated once per tick. */
class RiderPhysiology(
    val cp: Double,
    val wPrime: Double,
    val pMax: Double,
    val massKg: Double,
    val durability: Double,
    energyKj: Double = 0.0,
    wPrimeBal: Double = wPrime,
) {
    var energyKj: Double = energyKj
        private set
    var wPrimeBal: Double = wPrimeBal
        private set

    /**
     * Terrain factor on the power the rider sustains right now ([Fatigue.terrainFactor]): above 1 on climbs, where a
     * rider pushes more watts for the same effort. Set by the race each tick; [cpEff] stays the flat value.
     */
    var terrainFactor: Double = 1.0

    val loss: Double get() = Fatigue.loss(durability, energyKj, massKg)
    val cpEff: Double get() = cp * (1.0 - loss)
    val wPrimeEff: Double get() = wPrime * (1.0 - loss)
    /** Critical power on the current terrain: what intensities, caps and the W′ balance are measured against. */
    val cpNow: Double get() = cpEff * terrainFactor

    fun maxSustainablePower(dt: Double = PhysicsConstants.DT): Double =
        min(Fatigue.maxSustainablePower(cpNow, wPrimeBal, dt), max(cpNow, sprintPower()))

    fun sprintPower(): Double = Fatigue.sprintPower(pMax, wPrimeBal, wPrimeEff, loss, cpEff)

    /** Applies [power] (W) for [dt] s. W′ uses the fatigue state at the start of the tick. */
    fun tick(power: Double, dt: Double = PhysicsConstants.DT) {
        val p = max(0.0, power)
        val l = loss
        wPrimeBal = Fatigue.nextWPrimeBal(wPrimeBal, p, cp * (1.0 - l) * terrainFactor, wPrime * (1.0 - l), l, dt)
        energyKj += p * dt / 1000.0
    }
}
