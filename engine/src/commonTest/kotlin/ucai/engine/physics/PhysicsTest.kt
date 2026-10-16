package ucai.engine.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reference rider: 70 kg rider + 8 kg bike = 78 kg system mass, CdA 0.32, sea level. */
private const val SYSTEM_MASS = 78.0
private const val CDA = 0.32

class PhysicsTest {
    private val flat = RideConditions(massKg = SYSTEM_MASS, cda = CDA)

    private fun kmh(v: Double) = v * 3.6

    @Test
    fun soloFlatAt400W() {
        val v = kmh(steadySpeed(400.0, flat))
        println("flat 400 W solo: ${v.r1()} km/h")
        assertEquals(43.0, v, 2.0)
    }

    @Test
    fun climbAt6Percent() {
        val climb = flat.copy(gradient = 0.06)
        val at400 = kmh(steadySpeed(400.0, climb))
        val at335 = kmh(steadySpeed(335.0, climb))
        println("6 % climb: 400 W -> ${at400.r1()} km/h, 335 W -> ${at335.r1()} km/h")
        // Matches DESIGN §8: ~24 km/h at 400 W (5.7 W/kg), ~21 km/h at ~335 W.
        assertEquals(24.6, at400, 1.0)
        assertEquals(21.0, at335, 1.0)
    }

    @Test
    fun powerAndSpeedAreInverse() {
        for (g in listOf(-0.08, -0.03, 0.0, 0.04, 0.1)) {
            val c = flat.copy(gradient = g, windAxial = 3.0)
            val v = steadySpeed(300.0, c)
            assertEquals(300.0, powerAtSpeed(v, c), 1e-6)
        }
    }

    @Test
    fun descentWithNoPowerReachesTerminalSpeed() {
        val c = flat.copy(gradient = -0.06)
        val v = steadySpeed(0.0, c)
        assertEquals(0.0, resistiveForce(v, c), 1e-6)
        assertTrue(kmh(v) in 45.0..65.0, "terminal ${kmh(v)}")
        assertEquals(0.0, steadySpeed(0.0, flat))
    }

    @Test
    fun draftSavesPowerAt45Kmh() {
        val v = 45.0 / 3.6
        val front = powerAtSpeed(v, flat)
        val second = powerAtSpeed(v, flat.copy(kDraft = draftFactor(2, 20)))
        val saving = 1.0 - second / front
        println("45 km/h: front ${front.r1()} W, 2nd wheel ${second.r1()} W, saving ${(saving * 100).r1()} %")
        assertTrue(saving in 0.30..0.40, "saving $saving")
    }

    @Test
    fun draftFactorShape() {
        assertEquals(1.0, draftFactor(1, 70))
        assertEquals(0.64, draftFactor(2, 70))
        assertEquals(0.55, draftFactor(3, 70), 1e-9)
        var previous = 1.0
        for (r in 1..70) {
            val k = draftFactor(r, 70)
            assertTrue(k <= previous)
            previous = k
        }
        assertEquals(0.35, draftFactor(70, 70), 0.02)
        assertTrue(draftFactor(5, 5) > draftFactor(5, 70) - 1e-9)
    }

    @Test
    fun axialWindComponent() {
        assertEquals(5.0, axialWind(fromDeg = 0.0, speed = 5.0, headingDeg = 0.0), 1e-9)
        assertEquals(-5.0, axialWind(fromDeg = 180.0, speed = 5.0, headingDeg = 0.0), 1e-9)
        assertEquals(0.0, axialWind(fromDeg = 90.0, speed = 5.0, headingDeg = 0.0), 1e-9)
        assertTrue(steadySpeed(400.0, flat.copy(windAxial = 5.0)) < steadySpeed(400.0, flat))
    }

    @Test
    fun airDensityDropsWithAltitude() {
        assertEquals(1.225, airDensity(0.0), 1e-9)
        assertEquals(1.225 * 0.9, airDensity(1000.0), 1e-9)
    }

    @Test
    fun eulerConvergesToSteadySpeed() {
        for (c in listOf(flat, flat.copy(gradient = 0.06), flat.copy(gradient = -0.02, kDraft = 0.5))) {
            val target = steadySpeed(350.0, c)
            var v = 0.0
            repeat(300) { v = eulerStep(v, 350.0, c) }
            assertEquals(target, v, 0.01)
        }
    }

    @Test
    fun eulerAccelerationIsCapped() {
        val v1 = eulerStep(5.0, 1500.0, flat)
        assertEquals(6.5, v1, 1e-9)
        assertTrue(eulerStep(0.5, 0.0, flat.copy(gradient = 0.1)) >= 0.0)
    }

    @Test
    fun descentCapRespected() {
        val c = flat.copy(gradient = -0.08)
        val cap = descentSpeedCap(descending = 0.3, gradient = -0.08)
        var v = 10.0
        repeat(120) {
            v = eulerStep(v, 300.0, c, maxSpeed = cap)
            assertTrue(v <= cap)
        }
        assertEquals(cap, v, 1e-9)
        assertTrue(descentSpeedCap(1.0, -0.08) > descentSpeedCap(0.0, -0.08))
        assertTrue(descentSpeedCap(0.5, -0.12) < descentSpeedCap(0.5, -0.05))
        assertEquals(Double.POSITIVE_INFINITY, descentSpeedCap(0.5, 0.0))
        println("descent cap -8 %: skill 0 ${kmh(descentSpeedCap(0.0, -0.08)).r1()}, skill 1 ${kmh(descentSpeedCap(1.0, -0.08)).r1()} km/h")
    }

    @Test
    fun noNegativeSpeedOnSteepClimbWithoutPower() {
        var v = 3.0
        repeat(10) { v = eulerStep(v, 0.0, flat.copy(gradient = 0.15)) }
        assertEquals(0.0, abs(v))
    }
}
