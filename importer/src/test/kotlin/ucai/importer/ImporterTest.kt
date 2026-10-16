package ucai.importer

import ucai.api.ApiJson
import ucai.api.CourseDetail
import ucai.engine.course.Course
import ucai.engine.course.haversineM
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImporterTest {
    private val rawDir = File("races/raw")
    private val imports by lazy { importAll(rawDir).associateBy { it.course.id } }

    private val official = mapOf(
        "milano-sanremo" to 298_000.0,
        "liege-bastogne-liege" to 259_500.0,
        "il-lombardia" to 239_000.0,
    )

    // Official figures are 2200-4850 m; the 100 m smoothing shaves a few percent off.
    private val gainRange = mapOf(
        "milano-sanremo" to 1_700.0..2_600.0,
        "liege-bastogne-liege" to 3_600.0..4_600.0,
        "il-lombardia" to 4_000.0..5_000.0,
    )

    @Test
    fun distanceWithinOnePercentOfOfficial() {
        for ((id, officialM) in official) {
            val c = imports.getValue(id).course
            val err = abs(c.distanceM - officialM) / officialM
            println("$id: ${c.distanceM / 1000} km (official ${officialM / 1000}), error ${"%.2f".format(err * 100)} %")
            assertTrue(err <= 0.01, "$id distance ${c.distanceM} vs $officialM")
        }
    }

    @Test
    fun elevationGainPlausible() {
        for ((id, range) in gainRange) {
            val c = imports.getValue(id).course
            println("$id: elevation gain ${c.elevationGainM} m")
            assertTrue(c.elevationGainM in range, "$id gain ${c.elevationGainM} not in $range")
        }
    }

    @Test
    fun lblBuiltFromTheTwoRaceLinesOnly() {
        val placemarks = parseKml(File(rawDir, "liege-bastogne-liege-2026.kml"))
        val race1 = placemarks.single { it.style == KmlStyles.RACE1 }.coordinates
        val race2 = placemarks.single { it.style == KmlStyles.RACE2 }.coordinates
        val arrow = placemarks.single { it.style == "styleLineRouge" }.coordinates
        val track = kmlRaceTrack(placemarks)
        assertEquals(race1.size + race2.size - 1, track.size, "junction point shared once")
        assertEquals(race1, track.subList(0, race1.size))
        assertEquals(race2.drop(1), track.subList(race1.size, track.size))
        assertTrue(arrow.none { it in track }, "direction arrow ignored")
        val junction = race1.size - 1
        for (i in junction - 5..junction + 5) {
            val jump = haversineM(track[i], track[i + 1])
            assertTrue(jump < 100.0, "jump of $jump m at raw point $i near the junction")
        }
    }

    @Test
    fun climbsHavePositiveGrades() {
        for (imp in imports.values) {
            val c = imp.course
            assertTrue(c.climbs.isNotEmpty(), c.id)
            for (cl in c.climbs) {
                assertTrue(cl.avgGrade in 0.02..0.15, "${c.id} ${cl.name} avg ${cl.avgGrade}")
                assertTrue(cl.maxGrade >= cl.avgGrade && cl.maxGrade <= 0.25, "${c.id} ${cl.name} max ${cl.maxGrade}")
                assertTrue(cl.startM >= 0 && cl.endM <= c.distanceM && cl.endM - cl.startM >= 500, "${c.id} ${cl.name}")
            }
            assertEquals(c.climbs.sortedBy { it.endM }, c.climbs, "${c.id} climbs in race order")
        }
        assertEquals(11, imports.getValue("liege-bastogne-liege").course.climbs.size)
        // the last LBL climb is the Roche-aux-Faucons, ~13 km from the finish
        val lbl = imports.getValue("liege-bastogne-liege").course
        val raf = lbl.climbs.last()
        assertTrue("Roche-aux-Faucons" in raf.name)
        assertTrue(lbl.distanceM - raf.endM in 11_000.0..16_000.0)
        // the Poggio summit is ~5.5 km from the Via Roma finish
        val msr = imports.getValue("milano-sanremo").course
        assertTrue(msr.distanceM - msr.climbs.last().endM in 4_500.0..6_500.0)
    }

    @Test
    fun lblStartsAtKmZero() {
        val placemarks = parseKml(File(rawDir, "liege-bastogne-liege-2026.kml"))
        val km0 = placemarks.single { it.style == KmlStyles.KM0 }.coordinates.single()
        val first = imports.getValue("liege-bastogne-liege").course.points.first()
        assertTrue(haversineM(km0.lat, km0.lon, first.lat, first.lon) < 100.0)
    }

    @Test
    fun courseFilesRoundTrip() {
        val out = File(System.getProperty("java.io.tmpdir"), "ucai-importer-test").apply { deleteRecursively() }
        for (imp in imports.values) {
            val file = writeCourse(out, imp.course)
            val text = file.readText()
            assertEquals(imp.course, ApiJson.decodeFromString(Course.serializer(), text))
            assertEquals(imp.course.points.size, ApiJson.decodeFromString(CourseDetail.serializer(), text).points.size)
        }
        out.deleteRecursively()
    }
}
