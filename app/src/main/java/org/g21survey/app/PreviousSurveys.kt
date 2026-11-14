package org.g21survey.app

import java.io.IOException
import java.io.BufferedWriter
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.xmlpull.v1.XmlPullParser

internal data class PreviousTemplate(val code: String, val scientific: String)
internal data class PreviousRecords(
    val records: List<SurveyRecord>,
    val squares: Set<String>,
    val warnings: List<String>,
)
internal data class PreviousTrack(
    val segments: List<List<TrackPoint>>,
    val squares: Set<String>,
)

internal fun previousSurveyDisplayLabel(
    surveyName: String,
    records: List<List<String>>?,
    locationHeader: String?,
): String {
    val timestamp = surveyName.removePrefix("g21s-")
    val parser = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).apply { isLenient = false }
    val position = ParsePosition(0)
    val date = parser.parse(timestamp, position)
        ?.takeIf { position.index == timestamp.length }
        ?.let { SimpleDateFormat("dd-MM-yyyy", Locale.UK).format(it) }
        ?: "Unknown date"
    val location = if (records == null || locationHeader == null) {
        "Location unavailable"
    } else {
        val header = records.firstOrNull().orEmpty()
        val columns = header.indices.filter {
            header[it].trim().equals(locationHeader.trim(), ignoreCase = true)
        }
        val value = columns.singleOrNull()
            ?.let { records.getOrNull(1)?.getOrNull(it)?.trim() }
            .orEmpty()
        value.ifEmpty { "No location recorded" }
    }
    return "$date - $location"
}

internal fun writeGpx(writer: BufferedWriter, points: List<TrackPoint>) {
    val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    writer.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    writer.appendLine(
        """<gpx version="1.1" creator="g21 Survey" xmlns="http://www.topografix.com/GPX/1/1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">""",
    )
    writer.appendLine("  <trk>")
    writer.appendLine("    <name>g21 Survey track</name>")
    writer.appendLine("    <trkseg>")
    points.forEach { point ->
        if (
            !point.latitude.isFinite() ||
            !point.longitude.isFinite() ||
            point.latitude !in -90.0..90.0 ||
            point.longitude !in -180.0..180.0
        ) throw IOException("The track contains an invalid GPS coordinate.")
        writer.appendLine(
            """      <trkpt lat="${String.format(Locale.US, "%.5f", point.latitude)}" lon="${String.format(Locale.US, "%.5f", point.longitude)}">""",
        )
        writer.appendLine("        <time>${timeFormat.format(Date(point.timestamp))}</time>")
        writer.appendLine("      </trkpt>")
    }
    writer.appendLine("    </trkseg>")
    writer.appendLine("  </trk>")
    writer.appendLine("</gpx>")
}

internal fun parsePreviousTrack(parser: XmlPullParser): PreviousTrack {
    val segments = mutableListOf<List<TrackPoint>>()
    val pointSquares = mutableSetOf<String>()
    var segment: MutableList<TrackPoint>? = null
    var hasRoot = false
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.DOCDECL) throw IOException("GPX must not contain a document type.")
        if (event == XmlPullParser.START_TAG) {
            if (parser.depth == 1) {
                if (parser.name != "gpx" ||
                    parser.namespace !in listOf("", "http://www.topografix.com/GPX/1/1")
                ) throw IOException("The track file is not GPX.")
                hasRoot = true
            }
            when {
                parser.name == "trkseg" && parser.namespace in listOf("", "http://www.topografix.com/GPX/1/1") ->
                    segment = mutableListOf()
                parser.name == "trkpt" && parser.namespace in listOf("", "http://www.topografix.com/GPX/1/1") -> {
                    val latitude = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        ?: throw IOException("Track point has an invalid latitude.")
                    val longitude = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        ?: throw IOException("Track point has an invalid longitude.")
                    val (east, north) = BritishNationalGrid.fromWgs84(latitude, longitude)
                    val point = TrackPoint(latitude, longitude, east, north, null, 0)
                    (segment ?: throw IOException("Track point is outside a track segment.")).add(point)
                    pointSquares.add(BritishNationalGrid.toSixFigureGridReference(
                        BritishNationalGrid.toGridReference(east, north, 8),
                    ))
                }
            }
        } else if (event == XmlPullParser.END_TAG && parser.name == "trkseg" &&
            parser.namespace in listOf("", "http://www.topografix.com/GPX/1/1")
        ) {
            segments.add(segment ?: throw IOException("Invalid track segment."))
            segment = null
        }
        event = parser.nextToken()
    }
    if (!hasRoot) throw IOException("The track file is empty.")
    return PreviousTrack(segments, pointSquares)
}

internal fun matchPreviousRecords(
    rows: List<List<String>>,
    scientificHeader: String,
    gridHeader: String,
    templates: List<PreviousTemplate>,
): PreviousRecords {
    val header = rows.firstOrNull() ?: throw IOException("The records CSV is empty.")
    fun column(name: String): Int {
        val matches = header.indices.filter { header[it].trim().equals(name.trim(), ignoreCase = true) }
        if (matches.size != 1) throw IOException("Records CSV must contain one '$name' column.")
        return matches.single()
    }
    val scientificColumn = column(scientificHeader)
    val gridColumn = column(gridHeader)
    val byName = templates.filter { it.scientific.isNotBlank() }
        .groupBy { it.scientific.trim().lowercase(java.util.Locale.ROOT) }
    val records = mutableListOf<SurveyRecord>()
    val squares = mutableSetOf<String>()
    val warnings = mutableListOf<String>()
    rows.drop(1).forEachIndexed { index, row ->
        val number = index + 2
        if (row.size != header.size) {
            warnings.add("Row $number has the wrong number of fields.")
            return@forEachIndexed
        }
        val grid = row[gridColumn]
        try {
            squares.add(BritishNationalGrid.toSixFigureGridReference(grid))
        } catch (_: IllegalArgumentException) {
            warnings.add("Row $number has an invalid grid reference.")
            return@forEachIndexed
        }
        val name = row[scientificColumn].trim()
        val matches = byName[name.lowercase(java.util.Locale.ROOT)].orEmpty().map { it.code }.distinct()
        if (matches.size != 1) {
            warnings.add("Row $number: scientific name '${name.ifEmpty { "(blank)" }}' has ${if (matches.isEmpty()) "no" else "multiple"} matching codes.")
        } else {
            records.add(SurveyRecord(matches.single(), grid, row))
        }
    }
    return PreviousRecords(records, squares, warnings)
}
