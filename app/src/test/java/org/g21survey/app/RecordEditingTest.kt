package org.g21survey.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordEditingTest {
    private val tags = listOf("scientific", "time", "tags", "gr", "vernacular", "quantity", "site", "comment")
    private val record = SurveyRecord(
        "old", "SD1234",
        listOf("Old species", "10:32", "SD1234", "Old common", "2", "Old site", "a, \"note\""),
    )
    private val template = listOf("New species", "", "new", "", "New common", "100", "Template site", "")

    @Test
    fun `tag positions account for removed code column`() {
        val excludedColumns = setOf(2)
        assertEquals(0, outputColumnForTag(tags, excludedColumns, "SCIENTIFIC"))
        assertEquals(2, outputColumnForTag(tags, excludedColumns, "gr"))
        assertEquals(4, outputColumnForTag(tags, excludedColumns, "quantity"))
        assertEquals(null, outputColumnForTag(tags, excludedColumns, "tags"))
        assertEquals(null, outputColumnForTag(tags, excludedColumns, "missing"))
    }

    @Test
    fun `example shortcuts tags map to corresponding records columns`() {
        val exampleTags = listOf(
            "tags", "", "", "", "date", "time", "scientific", "vernacular",
            "", "", "", "quantity", "gr", "site", "comment",
        )
        val excludedColumns = setOf(0, 1)
        assertEquals(4, outputColumnForTag(exampleTags, excludedColumns, "scientific"))
        assertEquals(3, outputColumnForTag(exampleTags, excludedColumns, "time"))
        assertEquals(10, outputColumnForTag(exampleTags, excludedColumns, "gr"))
        assertEquals(9, outputColumnForTag(exampleTags, excludedColumns, "quantity"))
        assertEquals(12, outputColumnForTag(exampleTags, excludedColumns, "comment"))
    }

    @Test
    fun `survey grid references decode to cells at their precision`() {
        listOf(8, 10).forEach { precision ->
            val reference = BritishNationalGrid.toGridReference(352123.0, 518456.0, precision)
            val sixFigureReference = BritishNationalGrid.toSixFigureGridReference(reference)
            val square = BritishNationalGrid.gridSquareForReference(sixFigureReference)
            assertEquals(100.0, square.sizeMetres, 0.0)
            assertEquals(8, sixFigureReference.length)
            assertEquals(
                sixFigureReference,
                BritishNationalGrid.toSixFigureGridReference(
                    BritishNationalGrid.toGridReference(
                        square.easting + square.sizeMetres / 2.0,
                        square.northing + square.sizeMetres / 2.0,
                        8,
                    ),
                ),
            )
        }
    }

    @Test
    fun `survey grid counts include only marked survey taxa`() {
        val tenFigureRef = BritishNationalGrid.toGridReference(352123.0, 518456.0, 10)
        val eightFigureRef = BritishNationalGrid.toGridReference(352129.0, 518459.0, 8)
        val otherSquareRef = BritishNationalGrid.toGridReference(352250.0, 518550.0, 10)
        val records = listOf(
            record.copy(code = "survey-a", gridReference = tenFigureRef),
            record.copy(code = "survey-b", gridReference = eightFigureRef),
            record.copy(code = "survey-a", gridReference = otherSquareRef),
            record.copy(code = "ordinary", gridReference = tenFigureRef),
        )
        assertEquals(
            mapOf(
                BritishNationalGrid.toSixFigureGridReference(tenFigureRef) to 2,
                BritishNationalGrid.toSixFigureGridReference(otherSquareRef) to 1,
            ),
            surveyTaxaGridCounts(records, setOf("survey-a", "survey-b")),
        )
    }

    @Test
    fun `survey grid species counts are unique and ignore hash records`() {
        val firstSquareRef = BritishNationalGrid.toGridReference(352123.0, 518456.0, 10)
        val sameSquareRef = BritishNationalGrid.toGridReference(352129.0, 518459.0, 8)
        val otherSquareRef = BritishNationalGrid.toGridReference(352250.0, 518550.0, 10)
        val records = listOf(
            record.copy(code = "survey-a", gridReference = firstSquareRef),
            record.copy(code = "survey-a", gridReference = sameSquareRef),
            record.copy(code = "survey-b", gridReference = sameSquareRef),
            record.copy(code = "#", identityCode = "#123 special", gridReference = sameSquareRef),
            record.copy(code = "#", identityCode = "#124 special", gridReference = otherSquareRef),
        )

        assertEquals(
            mapOf(
                BritishNationalGrid.toSixFigureGridReference(firstSquareRef) to 2,
            ),
            surveyTaxaGridCounts(records, setOf("survey-a", "survey-b", "#"), countSpecies = true),
        )
    }

    @Test
    fun `tracked grid squares appear with zero until survey taxa are recorded`() {
        val trackedReference = BritishNationalGrid.toSixFigureGridReference(
            BritishNationalGrid.toGridReference(352123.0, 518456.0, 8),
        )
        val recordedReference = BritishNationalGrid.toSixFigureGridReference(
            BritishNationalGrid.toGridReference(352250.0, 518550.0, 10),
        )
        val recordedSquare = BritishNationalGrid.gridSquareForReference(recordedReference)
        assertEquals(
            mapOf(
                BritishNationalGrid.gridSquareForReference(trackedReference) to 0,
                recordedSquare to 2,
            ),
            mergeSurveyTrackSquares(
                mapOf(recordedSquare to 2),
                setOf(trackedReference, recordedReference),
            ),
        )
    }

    @Test
    fun `recognized comments append without replacing existing text`() {
        assertEquals("Existing note Spoken words", appendRecognizedSpeech("Existing note", " Spoken words "))
        assertEquals("Spoken words", appendRecognizedSpeech("", "Spoken words"))
        assertEquals("Existing note ", appendRecognizedSpeech("Existing note ", ""))
    }

    @Test
    fun `integer quantities allow numeric mode but words and decimals do not`() {
        assertEquals(true, isIntegerQuantity("1"))
        assertEquals(true, isIntegerQuantity(" 10 "))
        assertEquals(false, isIntegerQuantity("Present"))
        assertEquals(false, isIntegerQuantity("1.5"))
        assertEquals(false, isIntegerQuantity(""))
    }

    @Test
    fun `recording fills blank tagged fields and preserves literal and inline values`() {
        val headers = listOf("code", "date", "time", "grid", "site", "scientific", "comment", "extra")
        val tags = listOf("tags", "date", "time", "gr", "site", "scientific", "comment", "")
        val fields = listOf("ABC", "", "fixed time", "", "", "Species", "", "Grid #GR#")
        assertEquals(
            listOf("01/01/2026", "fixed time", "SD1234", "Place", "Species", "", "Grid SD1234"),
            renderShortcutValues(headers, setOf(0), tags, fields, "01/01/2026", "10:32", "SD1234", "Place"),
        )
    }

    @Test
    fun `special record values override only scientific and comment tags`() {
        val headers = listOf("code", "scientific", "comment", "quantity")
        val tags = listOf("tags", "scientific", "comment", "quantity")
        assertEquals(
            listOf("#123 bag", "Found in wetland", "2"),
            renderShortcutValues(
                headers,
                setOf(0),
                tags,
                listOf("#", "Generic specimen", "Template note", "2"),
                "01/01/2026",
                "10:32",
                "SD1234",
                "Place",
                comment = "Found in wetland",
                scientific = "#123 bag",
            ),
        )
        assertEquals(
            listOf("#123 #date#", "", "2"),
            renderShortcutValues(
                headers,
                setOf(0),
                tags,
                listOf("#", "Generic specimen", "", "2"),
                "01/01/2026",
                "10:32",
                "SD1234",
                "Place",
                scientific = "#123 #date#",
            ),
        )
    }

    @Test
    fun `special record references are unique without regard to case`() {
        val specimenRecord = record.copy(customReferenceCode = "A12")
        assertEquals(true, isSpecimenReferenceUsed(listOf(specimenRecord), " a12 "))
        assertEquals(false, isSpecimenReferenceUsed(listOf(specimenRecord), "B12"))
    }

    @Test
    fun `special record label is displayed instead of the template code`() {
        assertEquals(
            "#A12 bag",
            displayCodeForRecord(record.copy(customReferenceCode = "A12", customButtonCode = "bag")),
        )
        assertEquals(
            "#A12 bag",
            displayCodeForRecord(record.copy(code = "#", identityCode = "#A12 bag")),
        )
        assertEquals("old", displayCodeForRecord(record))
    }

    @Test
    fun `special record scientific label is applied to its initial record`() {
        val source = SurveyRecord(
            "#",
            "SD1234",
            listOf("Generic specimen", "initial note"),
            customReferenceCode = "A12",
            customButtonCode = "bag",
        )
        val updated = applyCustomScientificLabels(
            listOf(source),
            listOf("scientific", "comment"),
            emptySet(),
        ).single()
        assertEquals(
            listOf("#A12 bag", "initial note"),
            updated.values,
        )
        assertEquals("initial note", updated.customButtonComment)
    }

    @Test
    fun `special records use their button label for grid duplicate identity`() {
        val specimenA = record.copy(identityCode = "#1 bag")
        val specimenB = record.copy(code = "other")
        val specimenC = record.copy(identityCode = "#2 bag")
        assertEquals(
            listOf(specimenC, specimenB),
            replaceSurveyRecord(listOf(specimenA, specimenB), 0, specimenA, specimenC),
        )
        assertThrows(DuplicateSurveyRecordException::class.java) {
            replaceSurveyRecord(listOf(specimenA, specimenB), 1, specimenB, specimenA.copy(code = "other"))
        }
        assertEquals("#1 bag", SpecialRecordButton("1", "bag").label)
    }

    @Test
    fun `code and survey columns are both omitted from generated values`() {
        val headers = listOf("code", "survey", "species", "quantity")
        val fields = listOf("ABC", "yes", "Species", "2")
        assertEquals(
            listOf("Species", "2"),
            renderShortcutValues(
                headers,
                setOf(0, 1),
                listOf("tags", "", "scientific", "quantity"),
                fields,
                "01/01/2026",
                "10:32",
                "SD1234",
                "Place",
            ),
        )
    }

    @Test
    fun `changing code updates scientific vernacular quantity site and comment`() {
        val edited = editSurveyRecord(record, "new", template, tags, setOf(2), "3", "New site", "Updated note")
        assertEquals("new", edited.code)
        assertEquals("SD1234", edited.gridReference)
        assertEquals(
            listOf("New species", "10:32", "SD1234", "New common", "3", "New site", "Updated note"),
            edited.values,
        )
    }

    @Test
    fun `keeping code preserves scientific and vernacular values`() {
        val edited = editSurveyRecord(record, "old", template, tags, setOf(2), "", "", "Updated note")
        assertEquals(listOf("Old species", "10:32", "SD1234", "Old common", "", "", "Updated note"), edited.values)
    }

    @Test
    fun `missing required tag fails instead of editing wrong field`() {
        assertThrows(IllegalArgumentException::class.java) {
            editSurveyRecord(record, "new", template, tags.map { if (it == "site") "" else it }, setOf(2), "", "", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            editSurveyRecord(record, "old", template, tags.map { if (it == "comment") "" else it }, setOf(2), "", "", "")
        }
    }

    @Test
    fun `delete and replacement preserve other records and reject stale edits`() {
        val other = record.copy(code = "other", gridReference = "SD5678")
        val records = listOf(record, other)
        assertEquals(listOf(other), replaceSurveyRecord(records, 0, record, null))
        val edited = record.copy(code = "new")
        assertEquals(listOf(edited, other), replaceSurveyRecord(records, 0, record, edited))
        assertThrows(IllegalArgumentException::class.java) {
            replaceSurveyRecord(records, 1, record, null)
        }
    }

    @Test
    fun `changing to an existing code at same grid is rejected`() {
        val other = record.copy(code = "other")
        assertThrows(DuplicateSurveyRecordException::class.java) {
            replaceSurveyRecord(listOf(record, other), 0, record, record.copy(code = "other"))
        }
        assertEquals(
            2,
            replaceSurveyRecord(listOf(record, other.copy(gridReference = "SD5678")), 0, record, record.copy(code = "other")).size,
        )
    }
}
