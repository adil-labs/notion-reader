package com.example.notionreader.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSettingsTest {

    @Test
    fun defaultSettingsMatchGoverningSpecification() {
        val settings = ReaderSettings()

        // Playback defaults
        assertEquals(NavigationUnit.SECTION, settings.defaultNavigationUnit)
        assertTrue(settings.continueToNextNote)
        assertEquals(NotePauseDuration.NONE, settings.pauseBetweenNotes)

        // Speech defaults
        org.junit.Assert.assertNull(settings.ttsEnginePackage)
        assertTrue(settings.useSystemSpeechSettings)
        assertEquals(SpeechRate.SYSTEM_DEFAULT, settings.speechRate)
        assertEquals(VoicePitch.SYSTEM_DEFAULT, settings.voicePitch)
        assertEquals(AnnouncementVerbosity.CONCISE, settings.playbackAnnouncements)

        // Offline defaults
        assertTrue(settings.cacheNotesForOffline)
        assertTrue(settings.prefetchUpcomingNotes)
        assertEquals(DownloadNetworkPolicy.WIFI_AND_MOBILE_DATA, settings.downloadNetworkPolicy)

        // Accessibility & Appearance defaults
        assertTrue(settings.keepScreenAwake)
        assertEquals(AppTheme.SYSTEM_DEFAULT, settings.appTheme)
        assertEquals(ReaderSpacing.COMFORTABLE, settings.readerSpacing)
    }

    @Test
    fun immutabilityAndCopyTransformPreservesOtherValues() {
        val initial = ReaderSettings()
        val modified = initial.copy(
            continueToNextNote = false,
            speechRate = SpeechRate.RATE_1_5,
            playbackAnnouncements = AnnouncementVerbosity.DETAILED
        )

        assertFalse(modified.continueToNextNote)
        assertEquals(SpeechRate.RATE_1_5, modified.speechRate)
        assertEquals(AnnouncementVerbosity.DETAILED, modified.playbackAnnouncements)

        // Other values untouched
        assertEquals(NavigationUnit.SECTION, modified.defaultNavigationUnit)
        assertTrue(modified.useSystemSpeechSettings)
        assertTrue(modified.keepScreenAwake)
    }
}
