package ucai.runner.calibration

import ucai.team.strategy.Role
import java.util.Locale

/**
 * What one harness race measures, as plain numbers (a run, with its replay, is too big to keep many of): the
 * realism metrics of DESIGN §8, used by `CalibrationTest` and written by `calibrate --csv`.
 */
data class RaceRow(
    val course: String,
    val seed: Long,
    val distKm: Double,
    val rejected: Int,
    val finished: Int,
    val speedKmh: Double,
    val winnerId: Int,
    val archetype: String,
    /** 0 when the winner's archetype fits the course, 0.5 when acceptable, else 1 (Score "winner"). */
    val winnerScore: Double,
    val breakGapS: Double,
    val caughtKm: Double,
    val breakWon: Boolean,
    val favouriteWon: Boolean,
    val top10GapS: Double,
    val winnerMarginS: Double,
    val winningGroup: Int,
    val selective: Boolean,
    val lastGapMin: Double,
    val over20: Int,
    val pelotonAtHalf: Double,
    val leaderCpLost: Double,
    val domestiqueCpLost: Double,
    val maxKmh: Double,
    val best20WKg: Double,
    val best60WKg: Double,
    val best60W: Double,
    val score: Double,
) {
    /** Km to go when the early break was caught (0 when it won). */
    val caughtToGoKm: Double get() = if (breakWon) 0.0 else distKm - caughtKm

    fun csv(): String = listOf(
        course, seed, f(distKm), rejected, finished, f(speedKmh), winnerId, archetype, f(winnerScore), f(breakGapS),
        f(caughtKm), breakWon, favouriteWon, f(top10GapS), f(winnerMarginS), winningGroup, selective, f(lastGapMin),
        over20, f(pelotonAtHalf), f(leaderCpLost), f(domestiqueCpLost), f(maxKmh), f(best20WKg), f(best60WKg),
        f(best60W), f(score),
    ).joinToString(",")

    companion object {
        const val CSV_HEADER = "course,seed,distKm,rejected,finished,speedKmh,winnerId,archetype,winnerScore,breakGapS," +
            "caughtKm,breakWon,favouriteWon,top10GapS,winnerMarginS,winningGroup,selective,lastGapMin,over20," +
            "pelotonAtHalf,leaderCpLost,domestiqueCpLost,maxKmh,best20WKg,best60WKg,best60W,score"

        private fun f(x: Double) = "%.2f".format(Locale.ROOT, x)

        fun of(r: Calibration.Run): RaceRow {
            val results = r.race.results()
            val winner = results.first()
            val t = Score.targets(r.course.id)
            val arch = r.field.rider(winner.riderId)!!.archetype
            val m = r.breakaway
            val ids = m.riderIds
            return RaceRow(
                course = r.course.id, seed = r.seed, distKm = r.course.distanceM / 1000.0, rejected = r.rejected.size,
                finished = results.count { it.position != null }, speedKmh = r.course.distanceM / winner.timeS!! * 3.6,
                winnerId = winner.riderId, archetype = arch.name.lowercase(),
                winnerScore = when (arch) {
                    in t.fit -> 0.0
                    in t.acceptable -> 0.5
                    else -> 1.0
                },
                breakGapS = m.maxGapS, caughtKm = m.caughtKm, breakWon = Score.breakWon(r), favouriteWon = Score.favouriteRank(r) == 1,
                top10GapS = results.take(10).maxOf { it.gapS ?: 0.0 }, winnerMarginS = Score.winnerMarginS(r),
                winningGroup = Score.winningGroupSize(r), selective = Score.selective(r),
                lastGapMin = r.lastGapS / 60.0, over20 = r.downBy(20),
                pelotonAtHalf = if (m.onRoadAtHalf == 0) 0.0 else m.pelotonAtHalf.toDouble() / m.onRoadAtHalf,
                leaderCpLost = Score.cpLostPct(r, Role.LEADER), domestiqueCpLost = Score.cpLostPct(r, Role.DOMESTIQUE),
                maxKmh = m.maxSpeed * 3.6, best20WKg = ids.maxOf { m.best20WKg(it) }, best60WKg = ids.maxOf { m.best60WKg(it) },
                best60W = ids.maxOf { m.best60W(it) }, score = Score.race(r),
            )
        }
    }
}
