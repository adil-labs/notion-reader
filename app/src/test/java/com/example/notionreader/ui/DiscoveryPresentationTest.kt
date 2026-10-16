package com.example.notionreader.ui

import com.example.notionreader.application.*
import com.example.notionreader.domain.discovery.TopicSnapshot
import org.junit.Assert.*
import org.junit.Test

class DiscoveryPresentationTest {
    @Test fun readyDiscoveriesTakePrecedenceOverUpdatingAndPausedMessages() {
        val ui = discoveryProgress(DiscoveryState(topicsUpdated = true, indexing = true, paused = true,
            topicStatus = "Finding topics in indexed cached Notes…"))
        assertEquals("New discoveries available", ui.message)
        assertEquals(DiscoveryProgressAction.Refresh, ui.action)
    }
    @Test fun pausedAndIndexingHaveOneDistinctDescription() {
        assertEquals("Discovery is paused. Resume indexing in Settings.", discoveryProgress(DiscoveryState(paused = true)).message)
        assertEquals("Preparing cached Notes…", discoveryProgress(DiscoveryState(paused = false, indexing = true)).message)
    }
    @Test fun exhaustedForegroundWorkOffersAnExplicitContinueInsteadOfLookingBusy() {
        val ui = discoveryProgress(DiscoveryState(paused = false, indexedCount = 4,
            topicStatus = "Topic coverage is incomplete. Processing resumes on the next eligible foreground or Resume indexing."))
        assertEquals("More discoveries can be prepared.", ui.message)
        assertEquals(DiscoveryProgressAction.Resume, ui.action)
    }
    @Test fun completedEmptyDiscoveryIsDifferentFromWorkStillInProgress() {
        assertEquals("No supported groups yet. Search your Notes or add an interest.", discoveryProgress(
            DiscoveryState(paused = false, indexedCount = 4, topicSnapshot = TopicSnapshot(complete = true))).message)
        assertEquals("Finding topics and connections…", discoveryProgress(DiscoveryState(paused = false, indexedCount = 4)).message)
    }
    @Test fun genericPreviewNamesAreHonestWithoutInventingOrRewritingTopics() {
        assertEquals("Related Notes", topicPreviewTitle("Notes related to A very long source Note title"))
        assertEquals("Similar Notes", topicPreviewTitle("Related knowledge"))
        assertEquals("Forest ecology", topicPreviewTitle("Forest ecology"))
    }
    @Test fun namedQueueOriginsDistinguishRecentSessionsWhileSingleNotesKeepTheirTitle() {
        fun entry(origin: String) = HistoryEntry("s", "Current Note", null, "Paused", false, false, 1, origin)
        assertEquals("Saved Queue: Morning", recentListeningTitle(entry("Saved Queue: Morning")))
        assertEquals("Interest: Forests", recentListeningTitle(entry("Interest: Forests")))
        assertEquals("Current Note", recentListeningTitle(entry("Single Note")))
        assertEquals("Current Note", recentListeningTitle(entry("Browse selection")))
    }
}
