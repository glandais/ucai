package ucai.engine.physics

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Physical constants of the kernel v1 model (DESIGN §3 "Physics"). */
object PhysicsConstants {
    const val G = 9.81
    const val CRR = 0.004
    const val ETA = 0.976
    const val RHO_SEA_LEVEL = 1.225
    const val MAX_ACCEL = 1.5
    const val MIN_SPEED_FOR_POWER = 1.0
    const val DT = 1.0
}

/** Air density: 1.225 kg/m³ at sea level, −10 % per 1000 m (linear, floored to stay positive). */
fun airDensity(elevationM: Double): Double =
    PhysicsConstants.RHO_SEA_LEVEL * max(0.3, 1.0 - 0.1 * elevationM / 1000.0)

/**
 * Everything but speed and power that the resistive force depends on.
 * [gradient] is rise/run (0.06 = 6 %), [massKg] is rider + bike, [windAxial] is the
 * headwind component in m/s (negative = tailwind), [kDraft] multiplies the aero term.
 */
data class RideConditions(
    val massKg: Double,
    val cda: Double,
    val gradient: Double = 0.0,
    val kDraft: Double = 1.0,
    val windAxial: Double = 0.0,
    val rho: Double = PhysicsConstants.RHO_SEA_LEVEL,
)

/**
 * Resistive force (N) at ground speed [v]: gravity + rolling + aero.
 * Aero uses the signed square of the air speed so a tailwind faster than the rider pushes.
 * Can be negative downhill.
 */
fun resistiveForce(v: Double, c: RideConditions): Double {
    val theta = atan(c.gradient)
    val gravityRolling = c.massKg * PhysicsConstants.G * (sin(theta) + PhysicsConstants.CRR * cos(theta))
    val air = v + c.windAxial
    return gravityRolling + 0.5 * c.rho * c.cda * c.kDraft * air * abs(air)
}

/** Pedal power (W) needed to hold speed [v]: F·v/η. Negative when gravity alone would do more than hold it. */
fun powerAtSpeed(v: Double, c: RideConditions): Double =
    resistiveForce(v, c) * v / PhysicsConstants.ETA

/**
 * Steady-state speed (m/s) for pedal power [power], i.e. the root of F(v)·v = P·η.
 *
 * F is non-decreasing in v, so F(v)·v is negative while F < 0 and increasing once F > 0:
 * the sign change is unique and bisection is robust, including on descents and with tailwind.
 * With P ≤ 0 the answer is the freewheel terminal speed (F = 0), or 0 if the rider would stop.
 */
fun steadySpeed(power: Double, c: RideConditions): Double {
    val target = max(0.0, power) * PhysicsConstants.ETA
    val f: (Double) -> Double =
        if (target > 0.0) { v -> resistiveForce(v, c) * v - target } else { v -> resistiveForce(v, c) }
    if (f(0.0) >= 0.0) return 0.0
    var lo = 0.0
    var hi = 10.0
    while (f(hi) < 0.0 && hi < 1e4) hi *= 2.0
    repeat(100) {
        val mid = 0.5 * (lo + hi)
        if (f(mid) < 0.0) lo = mid else hi = mid
    }
    return 0.5 * (lo + hi)
}

/**
 * One explicit Euler step of the speed: a = (P·η / max(v, 1) − F) / m, capped at +1.5 m/s²,
 * v ≥ 0, then optionally clamped to [maxSpeed] (descent cap: the rider brakes).
 */
fun eulerStep(
    v: Double,
    power: Double,
    c: RideConditions,
    dt: Double = PhysicsConstants.DT,
    maxSpeed: Double = Double.POSITIVE_INFINITY,
): Double {
    val propulsion = max(0.0, power) * PhysicsConstants.ETA / max(v, PhysicsConstants.MIN_SPEED_FOR_POWER)
    val a = min(PhysicsConstants.MAX_ACCEL, (propulsion - resistiveForce(v, c)) / c.massKg)
    return min(max(0.0, v + a * dt), maxSpeed)
}

/**
 * Max safe speed (m/s) on a descent, from the rider's [descending] skill (0..1) and the [gradient].
 * No cap above −3 %. Below, the cap starts at 60–85 km/h (by skill) and drops 2 km/h per
 * extra percent of slope (steeper roads are more technical), never under 35 km/h.
 */
fun descentSpeedCap(descending: Double, gradient: Double): Double {
    if (gradient >= -0.03) return Double.POSITIVE_INFINITY
    val skill = descending.coerceIn(0.0, 1.0)
    val kmh = 60.0 + 25.0 * skill - 200.0 * (-gradient - 0.03)
    return max(35.0, kmh) / 3.6
}

/**
 * Drafting factor on the aero term by [rank] (1 = front) in a group of [groupSize].
 * 1st 1.00, 2nd 0.64, 3rd 0.55, then exponential decay towards a floor that goes from ≈0.50
 * in a tiny group to ≈0.35 in a large peloton (more rows of shelter).
 */
fun draftFactor(rank: Int, groupSize: Int): Double {
    val size = max(1, groupSize)
    return when (min(max(1, rank), size)) {
        1 -> 1.0
        2 -> 0.64
        else -> {
            val floor = 0.35 + 0.15 * exp(-(size - 3) / 20.0)
            floor + (0.55 - floor) * exp(-(min(rank, size) - 3) / 8.0)
        }
    }
}

/**
 * Headwind component (m/s, negative = tailwind) along a segment heading.
 * [fromDeg] is where the wind blows from and [headingDeg] where the rider goes, both clockwise from north.
 */
fun axialWind(fromDeg: Double, speed: Double, headingDeg: Double): Double =
    speed * cos((fromDeg - headingDeg) * kotlin.math.PI / 180.0)
