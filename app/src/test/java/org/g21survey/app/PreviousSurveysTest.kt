package org.g21survey.app

import java.io.IOException
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import org.junit.BeforeClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

class PreviousSurveysTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun initializeGrid() {
            BritishNationalGrid.initialize(File("src/main/assets/ostn15-etrs-to-osgb.bin").readBytes())
        }
    }

    private val templates = listOf(
        PreviousTemplate("a", "Species A"),
        PreviousTemplate("b", "Species B"),
        PreviousTemplate("c", "Ambiguous"),
        PreviousTemplate("d", "Ambiguous"),
    )

    @Test
    fun `matches archived scientific names and combines counts with current records`() {
        val result = matchPreviousRecords(
            listOf(
                listOf("Grid", "Species"),
                listOf("SD52121845", " species a "),
                listOf("SD52131846", "Species B"),
            ),
            "Species", "Grid", templates,
        )
        val current = listOf(SurveyRecord("a", "SD52121845", emptyList()))
        assertEquals(emptyList<String>(), result.warnings)
        assertEquals(setOf("SD521184"), result.squares)
        assertEquals(mapOf("SD521184" to 3), surveyTaxaGridCounts(result.records + current, setOf("a", "b")))
        assertEquals(
            mapOf("SD521184" to 2),
            surveyTaxaGridCounts(result.records + current, setOf("a", "b"), countSpecies = true),
        )
    }

    @Test
    fun `reports unmatched ambiguous and invalid rows without inventing counts`() {
        val result = matchPreviousRecords(
            listOf(
                listOf("Species", "Grid"),
                listOf("Unknown", "SD52121845"),
                listOf("Ambiguous", "SD52121845"),
                listOf("Species A", "not a grid"),
                listOf("Species B"),
            ),
            "Species", "Grid", templates,
        )
        assertEquals(4, result.warnings.size)
        assertEquals(emptyList<SurveyRecord>(), result.records)
        assertEquals(setOf("SD521184"), result.squares)
    }

    @Test
    fun `rejects missing or ambiguous csv columns`() {
        assertThrows(IOException::class.java) {
            matchPreviousRecords(listOf(listOf("Species", "Species", "Grid")), "Species", "Grid", templates)
        }
        assertThrows(IOException::class.java) {
            matchPreviousRecords(listOf(listOf("Species")), "Species", "Grid", templates)
        }
    }

    @Test
    fun `reads separate GPX segments and derives squares from saved points`() {
        val (latitude, longitude) = BritishNationalGrid.toWgs84(352125.0, 418455.0)
        val track = """<gpx xmlns="http://www.topografix.com/GPX/1/1" xmlns:g21="https://g21survey.org/gpx/1">
            <trk><trkseg><trkpt lat="$latitude" lon="$longitude"/></trkseg>
            <trkseg><trkpt lat="$latitude" lon="$longitude"/></trkseg></trk>
            <extensions><g21:visitedSquares><g21:square ref="SD522184"/></g21:visitedSquares></extensions>
            </gpx>"""
        val parsed = parsePreviousTrack(parser(track))
        assertEquals(listOf(1, 1), parsed.segments.map { it.size })
        assertEquals(setOf("SD521184"), parsed.squares)
    }

    @Test
    fun `archived GPX contains only the recorded track points`() {
        val (latitude, longitude) = BritishNationalGrid.toWgs84(352125.0, 418455.0)
        val point = TrackPoint(latitude, longitude, 352125.0, 418455.0, null, 123L)
        val output = StringWriter()
        output.buffered().use { writeGpx(it, listOf(point)) }
        assertFalse(output.toString().contains("visitedSquares"))
        val track = parsePreviousTrack(parser(output.toString()))
        assertEquals(1, track.segments.single().size)
        assertEquals(setOf("SD521184"), track.squares)
    }

    @Test
    fun `survey label uses filename date and first record location field`() {
        val label = previousSurveyDisplayLabel(
            "g21s-20261114-134500-123",
            listOf(
                listOf("Species", "Location name"),
                listOf("Cladonia", "Bog one"),
                listOf("Sphagnum", "Bog two"),
            ),
            "Location name",
        )
        assertEquals("14-11-2026 - Bog one", label)
    }

    @Test
    fun `survey label handles missing location or malformed date`() {
        assertEquals(
            "14-11-2026 - No location recorded",
            previousSurveyDisplayLabel("g21s-20261114-134500-123", listOf(listOf("Location")), "Location"),
        )
        assertEquals(
            "Unknown date - Location unavailable",
            previousSurveyDisplayLabel("invalid", null, null),
        )
    }

    @Test
    fun `rejects malformed or unsafe GPX`() {
        assertThrows(IOException::class.java) {
            parsePreviousTrack(parser("""<!DOCTYPE gpx><gpx/>"""))
        }
        assertThrows(IOException::class.java) {
            parsePreviousTrack(parser("""<other/>"""))
        }
        assertThrows(IOException::class.java) {
            parsePreviousTrack(parser("""<gpx><trk><trkseg><trkpt lat="bad" lon="0"/></trkseg></trk></gpx>"""))
        }
    }

    private fun parser(xml: String): KXmlParser = KXmlParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(StringReader(xml))
    }
}
