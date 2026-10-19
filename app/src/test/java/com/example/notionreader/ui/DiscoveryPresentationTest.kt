package com.example.notionreader.ui

import com.example.notionreader.application.*
import com.example.notionreader.domain.discovery.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryPresentationTest {
    @Test fun chargingOnlyAndAndroidWaitingExplainAutomaticDeferralWithoutAContinueAction() {
        val charging = DiscoveryState(paused = false,
            effort = com.example.notionreader.domain.settings.DiscoveryEffort.CHARGING_ONLY,
            power = com.example.notionreader.application.DiscoveryPowerState(90))
        assertEquals("Waiting for power: Charging only is selected. Connect a charger.", discoveryProgress(charging).message)
        assertNull(discoveryProgress(charging).action)
        val waiting = charging.copy(effort = com.example.notionreader.domain.settings.DiscoveryEffort.BALANCED,
            waitingForExecution = true)
        assertEquals("Waiting for an Android processing window. Discovery resumes automatically.", discoveryProgress(waiting).message)
        assertNull(discoveryProgress(waiting).action)
        assertEquals("Discovery is paused. Resume indexing in Settings.", discoveryProgress(waiting.copy(paused = true)).message)
    }
    @Test fun largeModelDownloadsExposeBoundedReadableProgressUntilInstallation() {
        val model = com.example.notionreader.domain.discovery.DiscoveryModelState(downloading = true,
            downloadedBytes = 173_198_616, totalBytes = 346_397_233)
        assertEquals("Downloading model: 173 of 346 MB (49%).", discoveryModelDownloadProgress(model))
        assertEquals("Downloading model: 346 of 346 MB (100%).", discoveryModelDownloadProgress(model.copy(downloadedBytes = Long.MAX_VALUE)))
        assertEquals("Downloading model…", discoveryModelDownloadProgress(model.copy(totalBytes = 0)))
        assertNull(discoveryModelDownloadProgress(model.copy(downloading = false)))
    }

    @Test fun powerWaitingIsDistinctFromUserPauseAndDoesNotReplaceReadyUpdates() {
        val waiting = DiscoveryState(paused = false, power = DiscoveryPowerState(15), indexing = true)
        assertEquals("Waiting for power: connect a charger or reach 30% battery.", discoveryProgress(waiting).message)
        assertEquals("New discoveries available", discoveryProgress(waiting.copy(topicsUpdated = true)).message)
        assertEquals("Discovery is paused. Resume indexing in Settings.", discoveryProgress(waiting.copy(paused = true)).message)
    }
    @Test fun embeddingTimeIsCoarseAndNeverPresentedAsWholeDiscoveryTime() {
        val state = DiscoveryState(paused = false, embeddingWork = DiscoveryEmbeddingWork(3, 25, ProcessingTimeRange(70, 170)))
        val text = discoveryWorkDescription(state)!!
        assertTrue(text.contains("3 Notes"))
        assertTrue(text.contains("25 passages"))
        assertTrue(text.contains("2–3 minutes"))
        assertTrue(text.contains("Subject and connection analysis is additional"))
        assertTrue(discoveryWorkDescription(state.copy(embeddingWork = DiscoveryEmbeddingWork(3, 25)))!!.contains("Estimating on this device"))
    }
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
    @Test fun unfinishedWorkContinuesAutomaticallyWithoutAResumeAction() {
        val ui = discoveryProgress(DiscoveryState(paused = false, indexedCount = 4,
            topicStatus = "Topic coverage is incomplete. Processing resumes on the next eligible foreground or Resume indexing."))
        assertEquals("Discovery continues automatically when processing is available.", ui.message)
        assertNull(ui.action)
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
        val ui = discoveryProgress(DiscoveryState(paused = false, indexedCount = 51,
            topicSnapshot = TopicSnapshot(engineVersion = 1, formationProgress = TopicFormationProgress(
                "signature", 750, listOf(listOf(0)), comparisonsCompleted = 14_000_000, totalOpportunities = 4218))))
        assertEquals("Finding topics in 51 indexed Notes (4218 passage groups across 750 sections). 14000000 similarity checks completed so far. Progress is saved.", ui.message)
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
