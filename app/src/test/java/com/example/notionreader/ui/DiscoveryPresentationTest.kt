package com.example.notionreader.ui

import com.example.notionreader.application.*
import com.example.notionreader.domain.discovery.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryPresentationTest {
    @Test fun independentReferencePreviewsQuoteTheReferencingPassageRegardlessOfPairOrder() {
        fun member(id: String, text: String): TopicMembership {
            val passage = SemanticPassage(id, id, "$id:section", "References", "$id:block", "$id:chunk",
                0, text.length, text, text, 0)
            return TopicMembership(id, SemanticMetadata(id), passage, 0f, passage)
        }
        val target = member("a", "Target passage provides context without sharing the source subject.")
        val source = member("z", "See a.")
        val connection = NoteConnection("pair", target, source, 0f, independentNamedReference = true)
        val evidence = ConnectionEvidence(listOf(NamedNoteReference("z", "a")))
        assertEquals("See a.", connectionPreviewExcerpt(connection, evidence))
        assertEquals(target.passage.excerpt, connectionPreviewExcerpt(connection.copy(independentNamedReference = false), evidence))
    }
    @Test fun anInvalidIndependentReferenceNeverBecomesASimilarityCard() {
        fun member(id: String, title: String, text: String): TopicMembership {
            val passage = SemanticPassage(id, id, "$id:section", "References", "$id:block", "$id:chunk",
                0, text.length, text, text, 0)
            return TopicMembership(id, SemanticMetadata(title), passage, 0f, passage)
        }
        val pair = NoteConnection("pair", member("a", "Alpha", "See Beta."),
            member("b", "Beta", "Target source context."), 0f, independentNamedReference = true)
        assertEquals(listOf(pair), visibleDiscoveryConnections(listOf(pair), mapOf("a" to "Alpha", "b" to "Beta")))
        assertTrue(visibleDiscoveryConnections(listOf(pair), mapOf("a" to "Alpha", "b" to "Beta", "c" to "Beta")).isEmpty())
        val similarity = pair.copy(independentNamedReference = false)
        assertEquals(listOf(similarity), visibleDiscoveryConnections(listOf(similarity), mapOf("a" to "Alpha", "b" to "Beta", "c" to "Beta")))
    }
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
    @Test fun experimentalSingleNoteCoverageDoesNotClaimThreeNotesAreRequired() {
        val ui = discoveryProgress(DiscoveryState(paused = false, indexedCount = 1,
            topicSnapshot = TopicSnapshot(complete = true, engineVersion = 1)))
        assertEquals("No supported groups yet. Search your Notes or add an interest.", ui.message)
    }
    @Test fun backgroundFormationProgressIsVisibleWithoutReplacingTheInspectedSnapshot() {
        val progress = "Topic coverage is incomplete. 0 of 2401 sections finalized; 12 comparisons completed. Progress is saved."
        assertEquals(progress, discoveryProgress(DiscoveryState(paused = false, indexedCount = 1,
            topicStatus = progress, topicSnapshot = TopicSnapshot())).message)
    }
    @Test fun incompleteGroupingReportsEligibleSectionsAndActualWorkWithoutClaimingCompletion() {
        val ui = discoveryProgress(DiscoveryState(paused = false, indexedCount = 1,
            topicSnapshot = TopicSnapshot(engineVersion = 1, formationProgress = TopicFormationProgress(
                "signature", 2401, listOf(listOf(0)), comparisonsCompleted = 12))))
        assertEquals("Comparing 2401 eligible sections; 12 comparisons completed. 0 of 2401 sections finalized.", ui.message)
    }
    @Test fun evidenceProgressCountsCandidatesRatherThanEmptyClusterSlots() {
        val ui = discoveryProgress(DiscoveryState(paused = false, indexedCount = 1,
            topicSnapshot = TopicSnapshot(engineVersion = 1, formationProgress = TopicFormationProgress(
                "signature", 3, listOf(emptyList(), listOf(0, 1, 2), emptyList()),
                groupingComplete = true, projectionCluster = 2))))
        assertEquals("Checking topic evidence: 1 of 1 candidates; 3 of 3 sections finalized.", ui.message)
    }
    @Test fun evidenceProgressIncludesBroadAndFocusedCandidateGroups() {
        val ui = discoveryProgress(DiscoveryState(paused = false, indexedCount = 1,
            topicSnapshot = TopicSnapshot(engineVersion = 1, formationProgress = TopicFormationProgress(
                "signature", 2, listOf(listOf(0, 1), emptyList()),
                groupingComplete = true, projectionCluster = 2,
                candidateGroups = listOf(listOf(0, 1), listOf(0), listOf(1))))))
        assertEquals("Checking topic evidence: 2 of 3 candidates; 2 of 2 sections finalized.", ui.message)
    }
    @Test fun namedQueueOriginsDistinguishRecentSessionsWhileSingleNotesKeepTheirTitle() {
        fun entry(origin: String) = HistoryEntry("s", "Current Note", null, "Paused", false, false, 1, origin)
        assertEquals("Saved Queue: Morning", recentListeningTitle(entry("Saved Queue: Morning")))
        assertEquals("Interest: Forests", recentListeningTitle(entry("Interest: Forests")))
        assertEquals("Current Note", recentListeningTitle(entry("Single Note")))
        assertEquals("Current Note", recentListeningTitle(entry("Browse selection")))
    }
}
