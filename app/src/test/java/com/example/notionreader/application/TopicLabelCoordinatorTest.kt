package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TopicLabelCoordinatorTest {
    private val identity = TopicLabelIdentity("a", "t", "c", "p", "s", "v", "GPU")
    private class Store : TopicLabelStore {
        val labels = linkedMapOf<String, GeneratedTopicLabel>()
        var cursor: TopicLabelCursor? = null
        var beforeCommit: () -> Unit = {}
        var cursorWriteResult = TopicLabelWriteResult.Written
        override suspend fun read(topicId: String) = labels[topicId]
        override suspend fun write(label: GeneratedTopicLabel, stillCurrent: () -> Boolean): TopicLabelWriteResult {
            if (!stillCurrent()) return TopicLabelWriteResult.Stale
            beforeCommit()
            if (!stillCurrent()) return TopicLabelWriteResult.Stale
            labels[label.topicId] = label; return TopicLabelWriteResult.Written
        }
        override suspend fun remove(topicId: String) { labels.remove(topicId) }
        override suspend fun prune(eligibleFingerprints: Map<String, String>) {
            labels.entries.removeAll { eligibleFingerprints[it.key] != it.value.groupFingerprint }
            if (eligibleFingerprints.isEmpty()) cursor = null
        }
        override suspend fun clear() { labels.clear(); cursor = null }
        override suspend fun bytes() = labels.size.toLong()
        override suspend fun readCursor() = cursor
        override suspend fun writeCursor(cursor: TopicLabelCursor, stillCurrent: () -> Boolean): TopicLabelWriteResult {
            if (!stillCurrent()) return TopicLabelWriteResult.Stale
            if (cursorWriteResult != TopicLabelWriteResult.Written) return cursorWriteResult
            this.cursor = cursor; return TopicLabelWriteResult.Written
        }
    }
    private fun snapshot(): TopicSnapshot {
        fun topic(id: String, order: Long): DiscoveredTopic {
            val body = "Increasing waits after a failed request reduces repeated load on an unavailable server and spreads retries over time."
            val p = SemanticPassage("p$id", "n$id", "s$id", "Overview", "b$id", "c$id", 0, body.length, body, body, 0)
            return DiscoveredTopic(id, "Overview", p, EmbeddedPassage(p, listOf(1f, 0f)),
                listOf(TopicMembership(p.pageId, SemanticMetadata("Note"), p, 1f, p, "r1")), order)
        }
        return TopicSnapshot(topics = listOf(topic("first", 1), topic("second", 2)), complete = true)
    }
    private fun completed(request: TopicLabelRequest) = TopicLabelRun.Completed(
        TopicLabelCandidate("Retry backoff", request.evidence.map { it.passageId }, "Increasing retry waits reduces repeated server load."))
    @Test fun labelingChangesOnlyNamesAndARecreatedCoordinatorReusesSuccessfulRecords() = runBlocking {
        val store = Store(); var calls = 0
        fun coordinator() = TopicLabelCoordinator(store, { identity }, { true }, { calls++; completed(it) }, { true })
        val original = snapshot(); val first = coordinator().process(original, 0, { true })
        assertEquals(listOf("Retry backoff", "Retry backoff"), first.snapshot.topics.map { it.effectiveLabel })
        assertEquals(original.topics.map { it.id }, first.snapshot.topics.map { it.id })
        assertSame(original.topics.first().members, first.snapshot.topics.first().members)
        val restored = coordinator().process(original, 90, { true })
        assertEquals(first.snapshot.topics, restored.snapshot.topics)
        assertEquals(2, calls); assertEquals(2, store.labels.size)
    }
    @Test fun aLateNativeResultCannotBeStoredAfterSourceInvalidation() = runBlocking {
        val store = Store(); var current = true; var calls = 0
        val worker = TopicLabelCoordinator(store, { identity }, { true }, { calls++; current = false; completed(it) }, { true })
        val result = worker.process(snapshot(), 0, { current })
        assertEquals(1, calls); assertTrue(result.stale); assertTrue(store.labels.isEmpty())
    }
    @Test fun theCommitBoundaryRechecksSourceValidity() = runBlocking {
        val store = Store(); var current = true
        store.beforeCommit = { current = false }
        val result = TopicLabelCoordinator(store, { identity }, { true }, ::completed, { true }).process(snapshot(), 0, { current })
        assertTrue(result.stale); assertTrue(store.labels.isEmpty())
    }
    @Test fun modelAbsenceRetainsCompatibleSavedNamesWithoutStartingInference() = runBlocking {
        val store = Store()
        TopicLabelCoordinator(store, { identity }, { true }, ::completed, { true }).process(snapshot(), 0, { true })
        val result = TopicLabelCoordinator(store, { identity }, { false }, { error("Must not generate") }, { true })
            .process(snapshot(), 1, { true })
        assertTrue(result.unavailable)
        assertTrue(result.snapshot.topics.all { it.effectiveLabel == "Retry backoff" })
    }
    @Test fun aPersistedCursorLetsOtherPendingGroupsRunBeforeRetryingAFailure() = runBlocking {
        val store = Store(); var permitted = true
        val first = TopicLabelCoordinator(store, { identity }, { true }, {
            permitted = false; TopicLabelRun.Failed(TopicLabelRuntimeFailure.BackendFailure)
        }, { permitted }).process(snapshot(), 0, { true })
        assertNotNull(first.failure); assertTrue(store.labels.isEmpty())
        permitted = true; val order = mutableListOf<String>()
        TopicLabelCoordinator(store, { identity }, { true }, { order += it.topicId; completed(it) }, { permitted })
            .process(snapshot(), 1, { true })
        assertEquals(listOf("second", "first"), order)
    }
    @Test fun cancellationCannotClaimDurableProgressWhenCursorStorageIsRefused() = runBlocking {
        val store = Store(); store.cursorWriteResult = TopicLabelWriteResult.StorageRefused
        val result = TopicLabelCoordinator(store, { identity }, { true }, { TopicLabelRun.Cancelled }, { true })
            .process(snapshot(), 0, { true })
        assertTrue(result.pending); assertNotNull(result.failure)
        assertNull(store.cursor); assertTrue(store.labels.isEmpty())
    }
}
