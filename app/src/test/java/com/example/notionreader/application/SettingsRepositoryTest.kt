package com.example.notionreader.application

import com.example.notionreader.domain.settings.AnnouncementVerbosity
import com.example.notionreader.domain.settings.NavigationUnit
import com.example.notionreader.domain.settings.ReaderSettings
import com.example.notionreader.domain.settings.SpeechRate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsRepositoryTest {

    @Test
    fun inMemoryRepositoryEmitsDefaultsAndAppliesUpdates() = runTest {
        var clearHistoryCount = 0
        var clearCacheCount = 0
        var clearAllCount = 0

        val repository = InMemorySettingsRepository(
            onClearHistory = { clearHistoryCount++ },
            onClearCache = { clearCacheCount++ },
            onClearAll = { clearAllCount++ }
        )

        assertEquals(NavigationUnit.SECTION, repository.settings.value.defaultNavigationUnit)

        repository.updateSettings {
            it.copy(
                defaultNavigationUnit = NavigationUnit.NOTE,
                continueToNextNote = false,
                speechRate = SpeechRate.RATE_1_25,
                playbackAnnouncements = AnnouncementVerbosity.MINIMAL
            )
        }

        val updated = repository.settings.value
        assertEquals(NavigationUnit.NOTE, updated.defaultNavigationUnit)
        assertFalse(updated.continueToNextNote)
        assertEquals(SpeechRate.RATE_1_25, updated.speechRate)
        assertEquals(AnnouncementVerbosity.MINIMAL, updated.playbackAnnouncements)

        repository.clearHistory()
        assertEquals(1, clearHistoryCount)

        repository.clearCache()
        assertEquals(1, clearCacheCount)

        repository.clearAllLocalData()
        assertEquals(1, clearAllCount)
        assertEquals("clearAllLocalData resets settings back to default", ReaderSettings(), repository.settings.value)
    }
}
