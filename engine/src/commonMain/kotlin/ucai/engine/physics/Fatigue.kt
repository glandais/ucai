package ucai.engine.physics

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Durability and W′ model (DESIGN §3 "Fatigue").
 *
 * `loss = (1 − durability) · (energyKj / (massKg · K))²`, capped at [MAX_LOSS].
 *
 * Calibration of K: the targets are a domestique (durability 0.5, 70 kg) at 2000 kJ losing
 * 10–15 % of CP, and a protected leader (durability ~0.8) losing ~5 % at ~1500 kJ.
 * The quadratic form fixes the ratio of the two losses to (0.5/0.2)·(2000/1500)² ≈ 4.4, so both
 * cannot be hit exactly. K = 53 puts the domestique at 14.5 % (upper part of the range, the
 * mechanic that decides monuments) and the leader at 3.3 % at 1500 kJ; a durability-0.8 leader
 * reaches 5 % around 1850 kJ, and a durability-0.7 leader reaches 4.9 % at 1500 kJ.
 */
object Fatigue {
    const val K = 53.0
    const val MAX_LOSS = 0.35

    fun loss(durability: Double, energyKj: Double, massKg: Double): Double {
        val r = energyKj / (massKg * K)
        return min(MAX_LOSS, (1.0 - durability.coerceIn(0.0, 1.0)) * r * r)
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

    val loss: Double get() = Fatigue.loss(durability, energyKj, massKg)
    val cpEff: Double get() = cp * (1.0 - loss)
    val wPrimeEff: Double get() = wPrime * (1.0 - loss)

    fun maxSustainablePower(dt: Double = PhysicsConstants.DT): Double =
        min(Fatigue.maxSustainablePower(cpEff, wPrimeBal, dt), max(cpEff, sprintPower()))

    fun sprintPower(): Double = Fatigue.sprintPower(pMax, wPrimeBal, wPrimeEff, loss, cpEff)

    /** Applies [power] (W) for [dt] s. W′ uses the fatigue state at the start of the tick. */
    fun tick(power: Double, dt: Double = PhysicsConstants.DT) {
        val p = max(0.0, power)
        val l = loss
        wPrimeBal = Fatigue.nextWPrimeBal(wPrimeBal, p, cp * (1.0 - l), wPrime * (1.0 - l), l, dt)
        energyKj += p * dt / 1000.0
    }
}
