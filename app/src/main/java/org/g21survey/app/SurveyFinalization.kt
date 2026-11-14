package org.g21survey.app

internal enum class SurveyEndAction { DELETE, ARCHIVE }

internal interface SurveyFinalizationStorage {
    fun archiveReady(): Boolean
    fun copyToArchive(index: Int)
    fun markArchiveReady()
    fun deleteSource(index: Int)
}

internal fun finalizeSurvey(action: SurveyEndAction, storage: SurveyFinalizationStorage) {
    if (action == SurveyEndAction.ARCHIVE && !storage.archiveReady()) {
        storage.copyToArchive(0)
        storage.copyToArchive(1)
        storage.markArchiveReady()
    }
    storage.deleteSource(0)
    storage.deleteSource(1)
}
