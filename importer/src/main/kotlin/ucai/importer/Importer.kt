package ucai.importer

import ucai.api.ApiJson
import ucai.engine.course.Course
import ucai.engine.course.GeoPoint
import ucai.engine.course.buildCourse
import ucai.engine.course.climbBetween
import ucai.engine.course.cumulativeDistances
import ucai.engine.course.cutStart
import ucai.engine.course.deriveClimb
import ucai.engine.course.haversineM
import ucai.engine.course.nearestIndex
import java.io.File
import java.util.Locale

internal fun fmt0(v: Double) = String.format(Locale.ROOT, "%.0f", v)
internal fun fmt1(v: Double) = String.format(Locale.ROOT, "%.1f", v)

/**
 * A hand-placed climb: the summit is the highest point within [searchKm] of [summitKm];
 * [lengthKm] (official figure) sets the foot, otherwise the foot is derived from the profile.
 */
data class ClimbSpec(val name: String, val summitKm: Double, val lengthKm: Double? = null, val searchKm: Double = 1.0)

/** How the start of the raw track is cut, for the report. */
data class Import(
    val course: Course,
    val rawLengthM: Double,
    val cutM: Double,
    val cutReason: String,
    val tunnels: List<ClosedFloatingPointRange<Double>>,
)

fun Course.placeClimb(spec: ClimbSpec): ucai.api.Climb {
    val derived = deriveClimb(spec.name, spec.summitKm * 1000.0, searchM = spec.searchKm * 1000.0)
    val length = spec.lengthKm ?: return derived
    return climbBetween(spec.name, derived.endM - length * 1000.0, derived.endM)
}

/** GPX courses: the official distance is kept, measured back from the finish. */
data class GpxCourse(
    val id: String,
    val name: String,
    val file: String,
    val officialM: Double,
    val startName: String,
    val finishName: String,
    val climbs: List<ClimbSpec>,
)

fun importGpx(rawDir: File, spec: GpxCourse): Import {
    val gpx = parseGpx(File(rawDir, spec.file))
    val raw = gpx.points
    val rawLength = cumulativeDistances(raw).last()
    // A GPX shorter than the official distance already starts at (or after) km 0: keep it whole.
    val cut = (rawLength - spec.officialM).coerceAtLeast(0.0)
    val tunnels = ArrayList<ClosedFloatingPointRange<Double>>()
    val base = buildCourse(spec.id, spec.name, cutStart(raw, cut), spec.startName, spec.finishName) { a, b -> tunnels += a..b }
    val course = base.copy(climbs = spec.climbs.map { base.placeClimb(it) }.sortedBy { it.endM })
    val reason = if (cut > 0) "cut ${fmt0(cut)} m so ${spec.officialM / 1000} km remain"
    else "GPX (${fmt1(rawLength / 1000)} km) is not longer than the official ${spec.officialM / 1000} km: no cut"
    return Import(course, rawLength, cut, reason, tunnels)
}

object KmlStyles {
    const val RACE1 = "styleRace1"
    const val RACE2 = "styleRace2"
    const val CLIMB = "styleClimb"
    const val KM0 = "styleDepartReel"
    const val FINISH = "styleFinish"
}

/** Race lines `#styleRace1` then `#styleRace2`, concatenated; other lines (`#styleLineRouge` arrow) are ignored. */
fun kmlRaceTrack(placemarks: List<Placemark>): List<GeoPoint> {
    val lines = listOf(KmlStyles.RACE1, KmlStyles.RACE2).map { style ->
        placemarks.single { it.style == style && it.coordinates.size > 1 }.coordinates
    }
    val (a, b) = lines
    // Drop the duplicated junction point when the second line starts where the first ends.
    return if (haversineM(a.last(), b.first()) < 5.0) a + b.drop(1) else a + b
}

private fun List<GeoPoint>.nearest(p: GeoPoint, range: IntRange) = range.minBy { haversineM(this[it], p) }

fun importLbl(rawDir: File, officialM: Double = 259_500.0): Import {
    val placemarks = parseKml(File(rawDir, "liege-bastogne-liege-2026.kml"))
    val raw = kmlRaceTrack(placemarks)
    val rawLength = cumulativeDistances(raw).last()
    val km0 = placemarks.single { it.style == KmlStyles.KM0 }.coordinates.single()
    val finish = placemarks.single { it.style == KmlStyles.FINISH }.coordinates.single()
    // Liège is both start and finish: look for km 0 in the first third and the finish in the last third.
    val third = raw.size / 3
    val from = raw.nearest(km0, 0 until third)
    val to = raw.nearest(finish, raw.size - third until raw.size)
    val track = raw.subList(from, to + 1)
    val tunnels = ArrayList<ClosedFloatingPointRange<Double>>()
    val base = buildCourse("liege-bastogne-liege", "Liège-Bastogne-Liège", track, "Liège", "Liège") { a, b -> tunnels += a..b }
    // Climb placemarks are listed in race order: snap each one after the previous summit, so a climb whose
    // road is also used near the start (Côte des Forges) is not snapped to the wrong passage.
    var after = 0
    val climbs = placemarks.filter { it.style == KmlStyles.CLIMB }.map { pm ->
        val p = pm.coordinates.single()
        val near = base.points[base.nearestIndex(p.lat, p.lon, after until base.points.size)].distM
        base.deriveClimb(pm.name, near, searchM = 600.0).also { after = base.segmentAt(it.endM) }
    }
    val cutM = cumulativeDistances(raw.subList(0, from + 1)).last()
    val reason = "cut at raw point #$from nearest to the 'km 0' placemark " +
        "(${fmt0(haversineM(raw[from], km0))} m away), ${fmt0(cutM)} m of neutral start dropped; " +
        "finish at raw point #$to (${fmt0(haversineM(raw[to], finish))} m from the finish placemark); official ${officialM / 1000} km"
    return Import(base.copy(climbs = climbs), rawLength, cutM, reason, tunnels)
}

// Summits placed on the computed profile; lengths are the organiser's figures where it gives them.
val MILANO_SANREMO = GpxCourse(
    id = "milano-sanremo", name = "Milano-Sanremo", file = "Milano-Sanremo 2026 _ The Route.gpx",
    officialM = 298_000.0, startName = "Pavia", finishName = "Sanremo",
    climbs = listOf(
        ClimbSpec("Passo del Turchino", 148.0, 5.0), // the steeper part after Masone; the drag from Ovada is ~2 %
        ClimbSpec("Capo Mele", 245.5),
        ClimbSpec("Capo Cervo", 250.5),
        ClimbSpec("Capo Berta", 258.5),
        ClimbSpec("Cipressa", 275.5, 5.6),
        ClimbSpec("Poggio di Sanremo", 291.5, 3.7),
    ),
)

// Official 2026 figures (RCS): foot of Selvino at 20.6 km, Berbenno at 61.1 km,
// Valpiana summit 153.5 km to go, Giovenzana summit 103 km to go; Civiglio and San Fermo on the Como circuit.
val IL_LOMBARDIA = GpxCourse(
    id = "il-lombardia", name = "Il Lombardia", file = "Il Lombardia 2026 _ The Route.gpx",
    officialM = 239_000.0, startName = "Bergamo", finishName = "Como",
    climbs = listOf(
        ClimbSpec("Bocche del Gavarno", 14.0),
        ClimbSpec("Selvino", 30.7, 10.1, searchKm = 0.0),
        ClimbSpec("Colle di Berbenno", 65.6, 4.5, searchKm = 0.0),
        ClimbSpec("Valpiana", 85.5, 10.1),
        ClimbSpec("Giovenzana", 135.5, 3.6, searchKm = 0.5),
        ClimbSpec("Madonna del Ghisallo", 178.0, 8.6),
        ClimbSpec("San Fermo della Battaglia", 211.0, 2.7, searchKm = 0.5),
        ClimbSpec("Civiglio", 221.5, 4.2, searchKm = 0.5),
        ClimbSpec("San Fermo della Battaglia", 233.0, 2.7, searchKm = 0.5),
    ),
)

fun importAll(rawDir: File): List<Import> =
    listOf(importGpx(rawDir, MILANO_SANREMO), importLbl(rawDir), importGpx(rawDir, IL_LOMBARDIA))

fun writeCourse(outDir: File, course: Course): File {
    outDir.mkdirs()
    val file = File(outDir, "${course.id}.json")
    file.writeText(ApiJson.encodeToString(Course.serializer(), course))
    return file
}
