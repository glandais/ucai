package ucai.importer

import ucai.engine.course.GeoPoint
import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

private fun <T> File.readXml(block: (XMLStreamReader) -> T): T {
    val factory = XMLInputFactory.newInstance().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }
    return inputStream().buffered().use { input ->
        val reader = factory.createXMLStreamReader(input)
        try { block(reader) } finally { reader.close() }
    }
}

/** A parsed GPX file: every `trkpt`, plus the metadata description. */
data class GpxFile(val name: String?, val desc: String?, val points: List<GeoPoint>)

fun parseGpx(file: File): GpxFile = file.readXml { r ->
    val points = ArrayList<GeoPoint>()
    var name: String? = null
    var desc: String? = null
    var lat = 0.0
    var lon = 0.0
    var ele = 0.0
    var inTrkpt = false
    while (r.hasNext()) {
        when (r.next()) {
            XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                "trkpt" -> {
                    inTrkpt = true
                    lat = r.getAttributeValue(null, "lat").toDouble()
                    lon = r.getAttributeValue(null, "lon").toDouble()
                    ele = 0.0
                }
                "ele" -> if (inTrkpt) ele = r.elementText.trim().toDouble()
                "name" -> if (name == null) name = r.elementText.trim()
                "desc" -> if (desc == null) desc = r.elementText.trim()
            }
            XMLStreamConstants.END_ELEMENT -> if (r.localName == "trkpt") {
                points += GeoPoint(lat, lon, ele)
                inTrkpt = false
            }
        }
    }
    GpxFile(name, desc, points)
}

/** A KML placemark: style id without `#`, and its Point or LineString coordinates (KML order is lon,lat,ele). */
data class Placemark(val name: String, val style: String, val coordinates: List<GeoPoint>)

fun parseKml(file: File): List<Placemark> = file.readXml { r ->
    val result = ArrayList<Placemark>()
    var inPlacemark = false
    var name = ""
    var style = ""
    var coords = emptyList<GeoPoint>()
    while (r.hasNext()) {
        when (r.next()) {
            XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                "Placemark" -> { inPlacemark = true; name = ""; style = ""; coords = emptyList() }
                "name" -> if (inPlacemark) name = r.elementText.trim()
                "styleUrl" -> if (inPlacemark) style = r.elementText.trim().removePrefix("#")
                "coordinates" -> if (inPlacemark) coords = parseKmlCoordinates(r.elementText)
            }
            XMLStreamConstants.END_ELEMENT -> if (r.localName == "Placemark") {
                result += Placemark(name, style, coords)
                inPlacemark = false
            }
        }
    }
    result
}

internal fun parseKmlCoordinates(text: String): List<GeoPoint> =
    text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { tuple ->
        val parts = tuple.split(',')
        GeoPoint(lat = parts[1].toDouble(), lon = parts[0].toDouble(), eleM = parts.getOrNull(2)?.toDouble() ?: 0.0)
    }
