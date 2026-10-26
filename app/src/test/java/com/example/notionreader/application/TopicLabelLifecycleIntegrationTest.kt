package com.example.notionreader.application

import com.example.notionreader.data.local.*
import com.example.notionreader.domain.discovery.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class TopicLabelLifecycleIntegrationTest {
    private val model = EmbeddingIdentity("test", 2)
    private val generator = TopicLabelIdentity("a", "t", "c", "p", "s", "v", "GPU")
    private class Graph(var saved: TopicSnapshot) : TopicStore {
        var refuseWrites = false
        override suspend fun read() = saved.copy(topics = saved.topics.map { it.copy(generatedLabel = null) })
        override suspend fun write(snapshot: TopicSnapshot): Boolean {
            if (refuseWrites) return false
            saved = snapshot.copy(topics = snapshot.topics.map { it.copy(generatedLabel = null) }); return true
        }
        override suspend fun clear() { saved = TopicSnapshot() }
        override suspend fun bytes() = 0L
    }
    private fun snapshot(sources: Map<String, String>): TopicSnapshot {
        val body = "Increasing waits after a failed request reduces repeated load on an unavailable server and spreads retries over time."
        val p = SemanticPassage("p", "n", "s", "Overview", "b", "c", 0, body.length, body, body, 0)
        val topic = DiscoveredTopic("topic", "Overview", p, EmbeddedPassage(p, listOf(1f, 0f)),
            listOf(TopicMembership("n", SemanticMetadata("Note"), p, 1f, p, "r1")), 1)
        return TopicSnapshot(topics = listOf(topic), identity = model, complete = true, connectionPassComplete = true,
            generation = 1, sourceSignature = topicSourceSignature(model, TopicPolicy(), 0, sources))
    }
    private suspend fun fixture(test: suspend (FileDocumentCache, FileSemanticIndexStore, Graph, FileTopicLabelStore) -> Unit) {
        val dir = Files.createTempDirectory("topic-label-lifecycle").toFile()
        try { test(FileDocumentCache(dir.resolve("cache")), FileSemanticIndexStore(dir.resolve("index")),
            Graph(snapshot(mapOf("n" to "r1"))), FileTopicLabelStore(dir.resolve("labels"))) }
        finally { dir.deleteRecursively() }
    }
    private fun completed(request: TopicLabelRequest) = TopicLabelRun.Completed(
        TopicLabelCandidate("Retry backoff", request.evidence.map { it.passageId }, "Increasing retry waits reduces repeated server load."))

    @Test fun completedGroupingIsNamedAndReopenedWithoutInferenceOnInspection() = runBlocking { fixture { cache, index, graph, store ->
        var calls = 0; var shown = TopicView()
        fun worker() = TopicCoordinator(cache, index, graph, null, { model }, TopicPolicy(), Mutex(), { true }, {},
            { shown = it }, labels = TopicLabelCoordinator(store, { generator }, { true }, { calls++; completed(it) }, { true }))
        val sources = mapOf("n" to "r1")
        val first = worker(); first.invalidate(sources); first.build(sources, 0, cache.revision.value)
        assertEquals(1, calls); assertEquals("Retry backoff", shown.snapshot.topics.single().effectiveLabel)
        assertFalse(first.needsMoreWork); assertTrue(first.bytes() > 0)
        val reopened = worker(); reopened.invalidate(sources); reopened.refresh(); reopened.setInspected(false)
        assertEquals("Retry backoff", shown.snapshot.topics.single().effectiveLabel)
        assertEquals(1, calls)
    } }
    @Test fun namingPreservesTheInspectedGenerationUntilRefresh() = runBlocking { fixture { cache, index, graph, store ->
        var shown = TopicView()
        val worker = TopicCoordinator(cache, index, graph, null, { model }, TopicPolicy(), Mutex(), { true }, {},
            { shown = it }, labels = TopicLabelCoordinator(store, { generator }, { true }, ::completed, { true }))
        val sources = mapOf("n" to "r1")
        worker.invalidate(sources); worker.setInspected(true)
        val original = shown.snapshot.topics.single()
        worker.build(sources, 0, cache.revision.value)
        assertNull(shown.snapshot.topics.single().currentGeneratedLabel); assertTrue(shown.updated)
        worker.refresh()
        assertEquals("Retry backoff", shown.snapshot.topics.single().effectiveLabel)
        assertEquals(original.id, shown.snapshot.topics.single().id)
        assertSame(original.members, shown.snapshot.topics.single().members)
    } }
    @Test fun withdrawalAndResetRemovePrivateSavedNamesAndProgress() = runBlocking { fixture { cache, index, graph, store ->
        val worker = TopicCoordinator(cache, index, graph, null, { model }, TopicPolicy(), Mutex(), { true }, {}, {},
            labels = TopicLabelCoordinator(store, { generator }, { true }, ::completed, { true }))
        val sources = mapOf("n" to "r1")
        worker.invalidate(sources); worker.build(sources, 0, cache.revision.value)
        assertNotNull(store.read("topic")); assertNotNull(store.readCursor())
        worker.invalidate(emptyMap())
        assertNull(store.read("topic")); assertNull(store.readCursor())
        worker.clear(); assertEquals(0L, worker.bytes())
    } }
    @Test fun sourceWithdrawalDoesNotWaitForNativeGenerationOrPublishItsLateResult() = runBlocking { fixture { cache, index, graph, store ->
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); var shown = TopicView()
        val labels = TopicLabelCoordinator(store, { generator }, { true }, {
            started.complete(Unit); finish.await(); completed(it)
        }, { true })
        val worker = TopicCoordinator(cache, index, graph, null, { model }, TopicPolicy(), Mutex(), { true }, {},
            { shown = it }, labels = labels)
        val sources = mapOf("n" to "r1"); worker.invalidate(sources)
        val work = launch { worker.build(sources, 0, cache.revision.value) }
        withTimeout(2000) { started.await(); worker.invalidate(emptyMap()) }
        assertTrue(shown.snapshot.topics.isEmpty())
        finish.complete(Unit); work.join()
        assertNull(store.read("topic")); assertTrue(shown.snapshot.topics.isEmpty())
    } }
    @Test fun aRejectedGraphWriteCannotPreserveNamesForWithdrawnSources() = runBlocking { fixture { cache, index, graph, store ->
        val worker = TopicCoordinator(cache, index, graph, null, { model }, TopicPolicy(), Mutex(), { true }, {}, {},
            labels = TopicLabelCoordinator(store, { generator }, { true }, ::completed, { true }))
        val sources = mapOf("n" to "r1")
        worker.invalidate(sources); worker.build(sources, 0, cache.revision.value)
        assertNotNull(store.read("topic"))
        graph.refuseWrites = true; worker.invalidate(emptyMap())
        assertNull(store.read("topic")); assertNull(store.readCursor())
    } }
}
