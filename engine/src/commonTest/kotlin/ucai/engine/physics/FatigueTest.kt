package ucai.engine.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FatigueTest {
    private fun rider(durability: Double = 0.6) =
        RiderPhysiology(cp = 400.0, wPrime = 25_000.0, pMax = 1400.0, massKg = 70.0, durability = durability)

    @Test
    fun wPrimeDrainsAboveCpAndRecoversBelow() {
        val r = rider()
        repeat(60) { r.tick(500.0) }
        assertEquals(25_000.0 - 60 * 100.0, r.wPrimeBal, 50.0)
        val drained = r.wPrimeBal
        repeat(300) { r.tick(200.0) }
        assertTrue(r.wPrimeBal > drained + 3000.0, "recovered to ${r.wPrimeBal}")
        assertTrue(r.wPrimeBal <= r.wPrimeEff)
        println("W'bal: after 60 s @500 W ${drained.r1()} J, after 300 s @200 W ${r.wPrimeBal.r1()} J")
    }

    @Test
    fun wPrimeNeverNegativeAndMaxPowerFallsToCp() {
        val r = rider()
        repeat(400) { r.tick(600.0) }
        assertEquals(0.0, r.wPrimeBal)
        assertEquals(r.cpEff, r.maxSustainablePower(), 1e-9)
    }

    @Test
    fun durabilityCalibration() {
        val domestique = Fatigue.loss(durability = 0.5, energyKj = 2000.0, massKg = 70.0)
        val leader = Fatigue.loss(durability = 0.8, energyKj = 1500.0, massKg = 70.0)
        println("loss: domestique 2000 kJ ${(domestique * 100).r1()} %, leader 1500 kJ ${(leader * 100).r1()} %")
        assertTrue(domestique in 0.10..0.15, "domestique $domestique")
        assertTrue(leader in 0.03..0.06, "leader $leader")
        assertEquals(Fatigue.MAX_LOSS, Fatigue.loss(0.0, 10_000.0, 70.0))
    }

    /** A monument costs 4000–5000 kJ: the loss keeps growing but stays realistic, and nothing in the first half hour. */
    @Test
    fun durabilityOverAMonument() {
        assertEquals(0.0, Fatigue.loss(durability = 0.5, energyKj = 200.0, massKg = 70.0))
        val leader = Fatigue.loss(durability = 0.8, energyKj = 4500.0, massKg = 70.0)
        val domestique = Fatigue.loss(durability = 0.55, energyKj = 5000.0, massKg = 72.0)
        println("loss at the finish: leader 4500 kJ ${(leader * 100).r1()} %, domestique 5000 kJ ${(domestique * 100).r1()} %")
        assertTrue(leader in 0.06..0.12, "leader $leader")
        assertTrue(domestique in 0.18..0.28, "domestique $domestique")
        assertTrue(Fatigue.loss(0.8, 3000.0, 70.0) < leader, "still growing")
    }

    @Test
    fun accumulatedWorkLowersCpAndSlowsRecovery() {
        val r = rider(durability = 0.5)
        repeat(8000) { r.tick(250.0) }
        assertEquals(2000.0, r.energyKj, 1e-6)
        assertTrue(r.cpEff < 0.9 * r.cp)
        assertEquals(r.cp * (1 - r.loss), r.cpEff, 1e-9)

        val fresh = Fatigue.nextWPrimeBal(10_000.0, 200.0, 400.0, 25_000.0, loss = 0.0)
        val tired = Fatigue.nextWPrimeBal(10_000.0, 200.0, 400.0, 25_000.0, loss = 0.2)
        assertTrue(tired < fresh)
    }

    /** Uphill a rider holds more power for the same effort: the W′ balance is measured against the terrain CP. */
    @Test
    fun terrainFactorOnClimbs() {
        assertEquals(1.0, Fatigue.terrainFactor(0.0))
        assertEquals(1.0, Fatigue.terrainFactor(-0.08))
        assertEquals(1.0 + Tuning.UPHILL_POWER_GAIN, Fatigue.terrainFactor(0.09), 1e-9)
        assertTrue(Fatigue.terrainFactor(0.04) in 1.0..(1.0 + Tuning.UPHILL_POWER_GAIN))
        assertTrue(Tuning.UPHILL_POWER_GAIN in 0.0..0.1, "3–8 % in the literature")

        val flat = rider()
        val climb = rider().apply { terrainFactor = Fatigue.terrainFactor(0.07) }
        assertEquals(flat.cpEff, climb.cpEff, 1e-9)
        assertEquals(climb.cpEff * climb.terrainFactor, climb.cpNow, 1e-9)
        repeat(60) {
            flat.tick(420.0)
            climb.tick(420.0)
        }
        assertTrue(climb.wPrimeBal > flat.wPrimeBal, "420 W costs less W′ on a climb")
    }

    @Test
    fun sprintPowerDependsOnFreshness() {
        val fresh = Fatigue.sprintPower(1400.0, 25_000.0, 25_000.0, loss = 0.0, cpEff = 400.0)
        val emptied = Fatigue.sprintPower(1400.0, 0.0, 25_000.0, loss = 0.0, cpEff = 400.0)
        val tired = Fatigue.sprintPower(1400.0, 25_000.0, 25_000.0, loss = 0.1, cpEff = 360.0)
        assertEquals(1400.0, fresh, 1e-9)
        assertEquals(700.0, emptied, 1e-9)
        assertEquals(1260.0, tired, 1e-9)
    }
}
