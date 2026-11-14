package org.g21survey.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SurveyFinalizationTest {
    private class Storage : SurveyFinalizationStorage {
        val sources = mutableListOf("records", "track")
        val archived = mutableMapOf<Int, String>()
        var ready = false
        var failCopy: Int? = null
        var failDelete: Int? = null

        override fun archiveReady() = ready

        override fun copyToArchive(index: Int) {
            if (failCopy == index) throw IOException("copy failed")
            archived[index] = sources[index]
        }

        override fun markArchiveReady() {
            ready = true
        }

        override fun deleteSource(index: Int) {
            if (failDelete == index) throw IOException("delete failed")
            sources[index] = ""
        }
    }

    @Test
    fun deleteRemovesBothWithoutArchiving() {
        val storage = Storage()
        finalizeSurvey(SurveyEndAction.DELETE, storage)
        assertEquals(listOf("", ""), storage.sources)
        assertTrue(storage.archived.isEmpty())
    }

    @Test
    fun failedCopyPreservesBothSourcesAndRetryArchivesThem() {
        val storage = Storage().apply { failCopy = 1 }
        try {
            finalizeSurvey(SurveyEndAction.ARCHIVE, storage)
            throw AssertionError("Expected copy failure")
        } catch (_: IOException) {
            assertEquals(listOf("records", "track"), storage.sources)
            assertFalse(storage.ready)
        }
        storage.failCopy = null
        finalizeSurvey(SurveyEndAction.ARCHIVE, storage)
        assertEquals(mapOf(0 to "records", 1 to "track"), storage.archived)
        assertEquals(listOf("", ""), storage.sources)
    }

    @Test
    fun failedDeletionCanBeRetriedWithoutRecopyingDeletedSource() {
        val storage = Storage().apply { failDelete = 1 }
        try {
            finalizeSurvey(SurveyEndAction.ARCHIVE, storage)
            throw AssertionError("Expected deletion failure")
        } catch (_: IOException) {
            assertTrue(storage.ready)
            assertEquals(listOf("", "track"), storage.sources)
        }
        storage.failDelete = null
        finalizeSurvey(SurveyEndAction.ARCHIVE, storage)
        assertEquals(mapOf(0 to "records", 1 to "track"), storage.archived)
        assertEquals(listOf("", ""), storage.sources)
    }
}
