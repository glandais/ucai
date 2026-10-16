package ucai.engine.race

import kotlin.test.Test
import kotlin.test.assertEquals

class PassTimesTest {
    private val points = DoubleArray(101) { it * 10.0 } // 0..1000 m every 10 m

    @Test
    fun gapIsWhenTheHeadPassed() {
        val p = PassTimes(points)
        // Head at 10 m/s for 50 s (to 500 m), then 2 m/s (a climb) for 50 s (to 600 m).
        for (t in 1..50) p.advance(t.toDouble(), t * 10.0)
        for (t in 51..100) p.advance(t.toDouble(), 500.0 + (t - 50) * 2.0)
        assertEquals(0.0, p.gapAt(600.0), 1e-9)
        assertEquals(0.0, p.gapAt(700.0), 1e-9)
        // A rider at 450 m: the head passed there at t = 45, now t = 100. Distance ÷ the head's climbing speed
        // would say 150 m / 2 m/s = 75 s.
        assertEquals(55.0, p.gapAt(450.0), 1e-9)
        // Between points and between ticks: 455 m at t = 45.5.
        assertEquals(54.5, p.gapAt(455.0), 1e-9)
        // On the climb: 590 m was passed at t = 95, the foot (500 m) at t = 50.
        assertEquals(5.0, p.gapAt(590.0), 1e-9)
        assertEquals(50.0, p.gapAt(500.0), 1e-9)
    }

    @Test
    fun headNeverGoesBack() {
        val p = PassTimes(points)
        p.advance(1.0, 100.0)
        p.advance(2.0, 50.0) // the furthest rider finished / a smaller max: ignored
        assertEquals(100.0, p.headM)
        assertEquals(1.0, p.gapAt(100.0 - 1e-9), 1e-6)
        assertEquals(1.5, p.gapAt(50.0), 1e-9)
    }
}
