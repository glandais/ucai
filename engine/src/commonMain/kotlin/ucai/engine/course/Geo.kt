package ucai.engine.course

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

const val EARTH_RADIUS_M = 6_371_000.0

/** A raw track point (degrees, meters). */
data class GeoPoint(val lat: Double, val lon: Double, val eleM: Double)

private fun Double.rad() = this * PI / 180.0

/** Great-circle distance in meters. */
fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = (lat2 - lat1).rad()
    val dLon = (lon2 - lon1).rad()
    val h = sin(dLat / 2).let { it * it } + cos(lat1.rad()) * cos(lat2.rad()) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
}

fun haversineM(a: GeoPoint, b: GeoPoint) = haversineM(a.lat, a.lon, b.lat, b.lon)

/** Initial bearing from point 1 to point 2, degrees in [0, 360), 0 = north, 90 = east. */
fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = lat1.rad()
    val phi2 = lat2.rad()
    val dLon = (lon2 - lon1).rad()
    val y = sin(dLon) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
    val deg = atan2(y, x) * 180.0 / PI
    return (deg + 360.0) % 360.0
}
