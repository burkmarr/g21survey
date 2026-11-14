package org.g21survey.app

import java.util.Locale

internal data class SurveyRecord(
    val code: String,
    val gridReference: String,
    val values: List<String>,
    val customReferenceCode: String? = null,
    val customButtonCode: String? = null,
    val customButtonComment: String? = null,
    val identityCode: String? = null,
)

internal class DuplicateSurveyRecordException : IllegalArgumentException()
internal class DuplicateSpecimenReferenceException : IllegalArgumentException()
internal val SurveyRecord.recordIdentityCode: String
    get() = identityCode ?: code

internal data class SpecialRecordButton(
    val referenceCode: String,
    val buttonCode: String,
    val comment: String? = null,
) {
    val label: String
        get() = "#$referenceCode $buttonCode"
}

internal fun displayCodeForRecord(record: SurveyRecord): String {
    val referenceCode = record.customReferenceCode
    val buttonCode = record.customButtonCode
    return if (referenceCode.isNullOrBlank() || buttonCode.isNullOrBlank()) {
        record.identityCode ?: record.code
    } else {
        SpecialRecordButton(referenceCode, buttonCode).label
    }
}

internal fun applyCustomScientificLabels(
    records: List<SurveyRecord>,
    tags: List<String>,
    excludedColumns: Set<Int>,
): List<SurveyRecord> {
    val scientificColumn = outputColumnForTag(tags, excludedColumns, "scientific")
    val commentColumn = outputColumnForTag(tags, excludedColumns, "comment")
    if (scientificColumn == null && commentColumn == null) return records
    return records.map { record ->
        if (record.customReferenceCode.isNullOrBlank() ||
            record.customButtonCode.isNullOrBlank()
        ) {
            record
        } else {
            val values = record.values.toMutableList()
            scientificColumn?.takeIf { it in values.indices }?.let {
                values[it] = displayCodeForRecord(record)
            }
            val buttonComment = record.customButtonComment ?: commentColumn
                ?.let { record.values.getOrNull(it)?.takeIf(String::isNotBlank) }
            if (values == record.values && buttonComment == record.customButtonComment) {
                record
            } else {
                record.copy(values = values, customButtonComment = buttonComment)
            }
        }
    }
}

internal fun recordColumnForShortcutColumn(shortcutColumn: Int, excludedColumns: Set<Int>): Int? {
    if (shortcutColumn in excludedColumns) return null
    return shortcutColumn - excludedColumns.count { it < shortcutColumn }
}

internal fun outputColumnForTag(tags: List<String>, excludedColumns: Set<Int>, tag: String): Int? {
    val shortcutColumn = tags.indexOfFirst { it.trim().equals(tag, ignoreCase = true) }
    return if (shortcutColumn < 0) null else recordColumnForShortcutColumn(shortcutColumn, excludedColumns)
}

internal fun renderShortcutValues(
    headers: List<String>,
    excludedColumns: Set<Int>,
    tags: List<String>,
    fields: List<String>,
    date: String,
    time: String,
    gridReference: String,
    site: String,
    comment: String? = null,
    scientific: String? = null,
): List<String> = headers.indices.mapNotNull { index ->
    if (recordColumnForShortcutColumn(index, excludedColumns) == null) return@mapNotNull null
    val field = fields.getOrElse(index) { "" }
    val tag = tags.getOrNull(index)?.trim()?.lowercase(Locale.ROOT)
    val override = when {
        tag == "comment" && comment != null -> comment
        tag == "scientific" && scientific != null -> scientific
        else -> null
    }
    if (override != null) return@mapNotNull override
    val value = when {
        field.isBlank() -> when (tag) {
            "date" -> date
            "time" -> time
            "gr" -> gridReference
            "site" -> site
            else -> field
        }
        else -> field
    }
    value.replace("#date#", date, ignoreCase = true)
        .replace("#time#", time, ignoreCase = true)
        .replace("#gr#", gridReference, ignoreCase = true)
        .replace("#site#", site, ignoreCase = true)
        .replace("#comment#", "", ignoreCase = true)
}

internal fun editSurveyRecord(
    record: SurveyRecord,
    newCode: String,
    templateFields: List<String>,
    tags: List<String>,
    excludedColumns: Set<Int>,
    quantity: String,
    site: String,
    comment: String,
): SurveyRecord {
    val values = record.values.toMutableList()
    fun setTaggedValue(tag: String, value: String) {
        val index = requireNotNull(outputColumnForTag(tags, excludedColumns, tag)) {
            "shortcuts.csv is missing the '$tag' tag."
        }
        require(index in values.indices) { "The '$tag' tag is outside the records CSV header." }
        values[index] = value
    }
    if (record.code != newCode) {
        for (tag in listOf("scientific", "vernacular")) {
            val templateIndex = tags.indexOfFirst { it.trim().equals(tag, ignoreCase = true) }
            require(templateIndex >= 0 && templateIndex !in excludedColumns) {
                "shortcuts.csv is missing the '$tag' tag."
            }
            setTaggedValue(tag, templateFields.getOrElse(templateIndex) { "" })
        }
    }
    setTaggedValue("quantity", quantity)
    setTaggedValue("site", site)
    setTaggedValue("comment", comment)
    return record.copy(
        code = newCode,
        values = values,
        identityCode = if (newCode == record.code) record.identityCode else null,
    )
}

internal fun replaceSurveyRecord(
    records: List<SurveyRecord>,
    index: Int,
    original: SurveyRecord,
    replacement: SurveyRecord?,
): List<SurveyRecord> {
    require(records.getOrNull(index) == original) {
        "This record has changed. Return to the list and try again."
    }
    val isDuplicate = replacement != null && records.withIndex().any {
        it.index != index && it.value.recordIdentityCode == replacement.recordIdentityCode &&
            it.value.gridReference == replacement.gridReference
    }
    if (isDuplicate) throw DuplicateSurveyRecordException()
    return records.toMutableList().apply {
        if (replacement == null) removeAt(index) else this[index] = replacement
    }
}

internal fun surveyTaxaGridCounts(
    records: List<SurveyRecord>,
    surveyTaxaCodes: Set<String>,
    countSpecies: Boolean = false,
): Map<String, Int> {
    val surveyRecords = records.filter {
        it.code in surveyTaxaCodes && (!countSpecies || it.code != "#")
    }
    return if (countSpecies) {
        surveyRecords.groupBy { BritishNationalGrid.toSixFigureGridReference(it.gridReference) }
            .mapValues { (_, squareRecords) -> squareRecords.map { it.code }.distinct().size }
    } else {
        surveyRecords.asSequence()
            .groupingBy { BritishNationalGrid.toSixFigureGridReference(it.gridReference) }
            .eachCount()
    }
}

internal fun mergeSurveyTrackSquares(
    recordSquares: Map<GridSquare, Int>,
    trackGridReferences: Set<String>,
): Map<GridSquare, Int> = recordSquares.toMutableMap().apply {
    trackGridReferences.forEach { reference ->
        putIfAbsent(BritishNationalGrid.gridSquareForReference(reference), 0)
    }
}

internal fun appendRecognizedSpeech(comment: String, recognizedText: String): String {
    val spoken = recognizedText.trim()
    if (spoken.isEmpty()) return comment
    val separator = if (comment.isEmpty() || comment.last().isWhitespace()) "" else " "
    return comment + separator + spoken
}

internal fun isIntegerQuantity(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.isNotEmpty() && trimmed.all { it in '0'..'9' }
}

internal fun isSpecimenReferenceUsed(records: List<SurveyRecord>, referenceCode: String): Boolean {
    val normalizedReference = referenceCode.trim()
    return records.any {
        it.customReferenceCode?.equals(normalizedReference, ignoreCase = true) == true
    }
}
