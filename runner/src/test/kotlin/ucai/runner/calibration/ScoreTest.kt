package ucai.runner.calibration

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoreTest {
    @Test
    fun termPenalty() {
        assertEquals(0.0, Score.Term("x", 41.0, 40.0, 44.0, 1.0, weight = 3.0).penalty)
        assertEquals(3.0 * 4.0, Score.Term("x", 38.0, 40.0, 44.0, 1.0, weight = 3.0).penalty, 1e-9)
        assertEquals(0.25, Score.Term("x", 690.0, 180.0, 600.0, 60.0, 180.0).penalty, 1e-9)
        assertEquals(Score.CAP, Score.Term("x", 0.0, 180.0, 600.0, 60.0).penalty, 1e-9)
    }

    @Test
    fun raceScoreIsTheSumOfItsTerms() {
        val run = Calibration.run(Calibration.loadCourse(File("races/courses"), "milano-sanremo"), 1)
        val terms = Score.raceTerms(run)
        assertEquals(terms.sumOf { it.penalty }, Score.race(run), 1e-9)
        assertTrue(terms.all { it.penalty >= 0.0 && it.value.isFinite() })
        assertTrue(Score.course(listOf(run)) >= Score.race(run))
        assertTrue(Calibration.report(run).contains("score"))
    }
}
