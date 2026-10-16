package ucai.engine.course

import ucai.api.Climb
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt

const val MAX_GRADIENT = 0.25
const val SMOOTHING_WINDOW_M = 100.0

/** Cumulative haversine distance at each point; `[0] = 0`. */
fun cumulativeDistances(track: List<GeoPoint>): DoubleArray {
    val d = DoubleArray(track.size)
    for (i in 1 until track.size) d[i] = d[i - 1] + haversineM(track[i - 1], track[i])
    return d
}

private fun lerp(a: GeoPoint, b: GeoPoint, t: Double) =
    GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t, a.eleM + (b.eleM - a.eleM) * t)

/** Point at distance [distM] along [track], interpolated linearly (lat/lon/ele) inside a segment. */
private fun pointAt(track: List<GeoPoint>, cum: DoubleArray, from: Int, distM: Double): Pair<Int, GeoPoint> {
    var i = from
    while (i < track.size - 2 && cum[i + 1] < distM) i++
    val len = cum[i + 1] - cum[i]
    val t = if (len <= 0.0) 0.0 else ((distM - cum[i]) / len).coerceIn(0.0, 1.0)
    return i to lerp(track[i], track[i + 1], t)
}

/** Drops the first [cutM] meters of [track]; the new first point is interpolated. */
fun cutStart(track: List<GeoPoint>, cutM: Double): List<GeoPoint> {
    if (cutM <= 0.0) return track
    val cum = cumulativeDistances(track)
    require(cutM < cum.last()) { "cut $cutM m beyond track length ${cum.last()} m" }
    val (i, p) = pointAt(track, cum, 0, cutM)
    return listOf(p) + track.subList(i + 1, track.size)
}

/**
 * Resamples [track] every [stepM] meters along its cumulative distance. The last point is the track end:
 * it is appended when the remainder is at least 1 m, otherwise it replaces the last grid point.
 * Returns the points and their distances.
 */
fun resample(track: List<GeoPoint>, stepM: Double = COURSE_STEP_M): Pair<List<GeoPoint>, DoubleArray> {
    require(track.size >= 2) { "track needs at least 2 points" }
    val cum = cumulativeDistances(track)
    val total = cum.last()
    val n = (total / stepM).toInt()
    val pts = ArrayList<GeoPoint>(n + 2)
    val dist = ArrayList<Double>(n + 2)
    var seg = 0
    for (k in 0..n) {
        val (s, p) = pointAt(track, cum, seg, k * stepM)
        seg = s
        pts += p; dist += k * stepM
    }
    if (total - n * stepM >= 1.0) {
        pts += track.last(); dist += total
    } else {
        pts[pts.lastIndex] = track.last(); dist[dist.lastIndex] = total
    }
    return pts to dist.toDoubleArray()
}

/** Centered moving average over [windowPoints] points (odd, shrunk at both ends). */
fun smoothElevation(ele: DoubleArray, windowPoints: Int): DoubleArray {
    val half = windowPoints / 2
    val prefix = DoubleArray(ele.size + 1)
    for (i in ele.indices) prefix[i + 1] = prefix[i] + ele[i]
    return DoubleArray(ele.size) { i ->
        val lo = max(0, i - half)
        val hi = min(ele.size - 1, i + half)
        (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
    }
}

/**
 * Removes DEM artifacts where the road goes through a tunnel (or over a viaduct): the elevation jumps
 * by an impossible gradient (> [steep] over 100 m, far beyond any paved road;
 * a shorter window would also catch hairpins where some sources bunch points with evenly spaced elevations) and comes back near its starting level within [maxLengthM].
 * Such a section is replaced by a straight line. Real climbs never come back that quickly, so they are kept.
 * [ele] is modified in place; returns the bridged index ranges.
 */
fun bridgeTunnels(ele: DoubleArray, stepM: Double, steep: Double = 0.5, maxLengthM: Double = 3_000.0): List<IntRange> {
    val look = (100.0 / stepM).roundToInt().coerceAtLeast(1)
    val maxSpan = (maxLengthM / stepM).toInt()
    val back = (500.0 / stepM).toInt()
    val bridged = ArrayList<IntRange>()
    var i = 0
    while (i + look < ele.size) {
        val rise = ele[i + look] - ele[i]
        if (abs(rise) / (look * stepM) <= steep) { i++; continue }
        val up = rise > 0
        // start of the jump: walk back while the road keeps rising (resp. falling) into it
        var a = i
        while (a > 0 && i - a < back && (if (up) ele[a - 1] < ele[a] - 0.5 else ele[a - 1] > ele[a] + 0.5)) a--
        val base = ele[a]
        var j = i + look
        while (j < ele.size && j - a <= maxSpan && (if (up) ele[j] > base + 10.0 else ele[j] < base - 10.0)) j++
        if (j < ele.size && j - a <= maxSpan) {
            for (k in a + 1 until j) ele[k] = ele[a] + (ele[j] - ele[a]) * (k - a) / (j - a)
            bridged += a..j
            i = j
        } else {
            i++
        }
    }
    return bridged
}

/**
 * Spike filter: limits the elevation change between consecutive points to ±[maxDeltaM] and carries the excess
 * over to the following points, so the total climb or descent is kept but spread over a longer stretch.
 * [ele] is modified in place.
 */
fun limitSteps(ele: DoubleArray, maxDeltaM: Double) {
    var carry = 0.0
    val raw = ele.copyOf()
    for (i in 1 until ele.size) {
        val want = raw[i] - raw[i - 1] + carry
        val step = want.coerceIn(-maxDeltaM, maxDeltaM)
        carry = want - step
        ele[i] = ele[i - 1] + step
    }
}

/** Gradient of each segment `i -> i + 1`, clamped to ±[maxAbs]; the last point repeats the previous one. */
fun gradients(ele: DoubleArray, dist: DoubleArray, maxAbs: Double = MAX_GRADIENT): DoubleArray {
    val g = DoubleArray(ele.size)
    for (i in 0 until ele.size - 1) {
        val d = dist[i + 1] - dist[i]
        g[i] = if (d <= 0.0) 0.0 else ((ele[i + 1] - ele[i]) / d).coerceIn(-maxAbs, maxAbs)
    }
    if (ele.size > 1) g[ele.size - 1] = g[ele.size - 2]
    return g
}

/** Heading of each segment `i -> i + 1`; the last point repeats the previous one. */
fun headings(points: List<GeoPoint>): DoubleArray {
    val h = DoubleArray(points.size)
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        h[i] = bearingDeg(a.lat, a.lon, b.lat, b.lon)
    }
    if (points.size > 1) h[points.size - 1] = h[points.size - 2]
    return h
}

/** Sum of positive elevation differences. */
fun elevationGain(ele: DoubleArray): Double {
    var gain = 0.0
    for (i in 1 until ele.size) if (ele[i] > ele[i - 1]) gain += ele[i] - ele[i - 1]
    return gain
}

private fun Double.roundTo(decimals: Int): Double {
    var f = 1.0
    repeat(decimals) { f *= 10.0 }
    return round(this * f) / f + 0.0 // + 0.0 turns -0.0 into 0.0
}

/**
 * Builds a course (without climbs) from a raw track already cut at km 0:
 * resample every 10 m, bridge tunnel artifacts, optionally limit the per-point step to [maxStepM] (spike filter),
 * smooth elevation over [smoothingWindowM], then gradient and heading per segment.
 */
fun buildCourse(
    id: String,
    name: String,
    track: List<GeoPoint>,
    startName: String? = null,
    finishName: String? = null,
    smoothingWindowM: Double = SMOOTHING_WINDOW_M,
    maxStepM: Double? = null,
    onBridge: (startM: Double, endM: Double) -> Unit = { _, _ -> },
): Course {
    val (pts, dist) = resample(track, COURSE_STEP_M)
    val window = (smoothingWindowM / COURSE_STEP_M).roundToInt() / 2 * 2 + 1
    val rawEle = DoubleArray(pts.size) { pts[it].eleM }
    bridgeTunnels(rawEle, COURSE_STEP_M).forEach { onBridge(dist[it.first], dist[it.last]) }
    if (maxStepM != null) limitSteps(rawEle, maxStepM)
    val ele = smoothElevation(rawEle, window)
    val grad = gradients(ele, dist)
    val head = headings(pts)
    val points = pts.indices.map { i ->
        TrackPoint(
            lat = pts[i].lat.roundTo(6),
            lon = pts[i].lon.roundTo(6),
            eleM = ele[i].roundTo(1),
            distM = dist[i].roundTo(1),
            gradient = grad[i].roundTo(4),
            headingDeg = head[i].roundTo(1),
        )
    }
    return Course(
        id = id, name = name, distanceM = dist.last().roundTo(1), elevationGainM = elevationGain(ele).roundTo(0),
        startName = startName, finishName = finishName, points = points,
    )
}

/** Index of the course point nearest to ([lat], [lon]) among [range]. */
fun Course.nearestIndex(lat: Double, lon: Double, range: IntRange = points.indices): Int =
    range.minBy { haversineM(lat, lon, points[it].lat, points[it].lon) }

/** A climb between two distances, with grades computed from the profile. */
fun Course.climbBetween(name: String, startM: Double, endM: Double): Climb {
    require(endM > startM) { "climb $name: end $endM <= start $startM" }
    val from = segmentAt(startM)
    val to = segmentAt(endM)
    val avg = (elevationAt(endM) - elevationAt(startM)) / (endM - startM)
    // maxGrade over a ~100 m window: per-10 m values are noisier than what a rider feels.
    val w = (SMOOTHING_WINDOW_M / COURSE_STEP_M).toInt()
    var maxG = avg
    for (i in from..max(from, to - w)) {
        val j = min(i + w, points.size - 1)
        maxG = max(maxG, (points[j].eleM - points[i].eleM) / (points[j].distM - points[i].distM))
    }
    return Climb(name, startM.roundTo(0), endM.roundTo(0), avg.roundTo(4), maxG.roundTo(4))
}

/**
 * Derives a climb from a point near it: the summit is the highest point within [searchM] of [nearM];
 * the foot is found by walking back from the summit to the lowest point, stopping once the road rises
 * more than [dipToleranceM] above that low point (that is the previous hill), then skipping any flat run-in
 * (less than 2 % over 200 m).
 */
fun Course.deriveClimb(
    name: String,
    nearM: Double,
    searchM: Double = 1_000.0,
    dipToleranceM: Double = 10.0,
    maxLengthM: Double = 20_000.0,
): Climb {
    val lo = segmentAt(max(0.0, nearM - searchM))
    val hi = segmentAt(nearM + searchM) + 1
    val summit = (lo..hi).maxBy { points[it].eleM }
    var foot = summit
    var i = summit
    while (i > 0 && points[summit].distM - points[i - 1].distM <= maxLengthM) {
        i--
        if (points[i].eleM < points[foot].eleM) foot = i
        else if (points[i].eleM > points[foot].eleM + dipToleranceM) break
    }
    val look = 20
    while (foot + look < summit) {
        val g = (points[foot + look].eleM - points[foot].eleM) / (points[foot + look].distM - points[foot].distM)
        if (g >= 0.02) break
        foot++
    }
    if (foot >= summit) foot = max(0, summit - 1)
    return climbBetween(name, points[foot].distM, points[summit].distM)
}

val Climb.lengthM: Double get() = endM - startM
