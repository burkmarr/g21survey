package org.g21survey.app

import java.util.Locale

internal data class SurveyRecord(
    val code: String,
    val gridReference: String,
    val values: List<String>,
)

internal class DuplicateSurveyRecordException : IllegalArgumentException()

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
): List<String> = headers.indices.mapNotNull { index ->
    if (recordColumnForShortcutColumn(index, excludedColumns) == null) return@mapNotNull null
    val field = fields.getOrElse(index) { "" }
    val value = if (field.isBlank()) {
        when (tags.getOrNull(index)?.trim()?.lowercase(Locale.ROOT)) {
            "date" -> date
            "time" -> time
            "gr" -> gridReference
            "site" -> site
            else -> field
        }
    } else {
        field
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
    return record.copy(code = newCode, values = values)
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
        it.index != index && it.value.code == replacement.code &&
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
): Map<String, Int> =
    records.asSequence()
        .filter { it.code in surveyTaxaCodes }
        .groupingBy { BritishNationalGrid.toSixFigureGridReference(it.gridReference) }
        .eachCount()
