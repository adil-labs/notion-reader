package com.example.notionreader.application

import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.discovery.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryRuntimeTest {
    @get:Rule val files = TemporaryFolder()
    private val identity = TopicLabelIdentity("a", "t", "c", "p", "s", "v", "GPU")
    private val request = TopicLabelRequest("topic", "group", 0,
        listOf(TopicLabelEvidence("p", "page", "r", "Seeds need water.")), identity)
    private class Models {
        var embeddingLive = true
        var labelLive = false
        var nativeRunning = false
        var embeddingCalls = 0
        var releases = 0
        val provider = object : EmbeddingProvider {
            override val identity = EmbeddingIdentity("test", 2)
            override val tokenizer = object : SemanticTokenizer { override fun tokens(text: String) = emptyList<SemanticToken>() }
            override suspend fun embed(text: String): List<Float> {
                check(!labelLive) { "Both engines resident" }; embeddingLive = true; embeddingCalls++
                return listOf(1f, 0f)
            }
            override fun release() { check(!nativeRunning); embeddingLive = false; releases++ }
        }
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val nativeStopped = CompletableDeferred<Unit>()
        val cancellationSeen = CompletableDeferred<Unit>()
        var delayed = false
        val labeler = object : TopicLabelRuntime {
            override suspend fun label(request: TopicLabelRequest): TopicLabelRun {
                check(!embeddingLive) { "Embedding engine not released" }
                labelLive = true; nativeRunning = true; entered.complete(Unit)
                try { if (delayed) finish.await() }
                catch (cancelled: CancellationException) {
                    cancellationSeen.complete(Unit)
                    withContext(NonCancellable) { nativeStopped.await() }
                    throw cancelled
                } finally { nativeRunning = false }
                return TopicLabelRun.Completed(TopicLabelCandidate("Seed germination", listOf("p"), "How moisture supports seed germination."))
            }
            override suspend fun close() { check(!nativeRunning) { "Close before native terminal" }; labelLive = false }
        }
    }
    @Test fun roleSwitchesReleaseThePreviousEngineBeforeOpeningTheOther() = runTest {
        val models = Models()
        val runtime = DiscoveryRuntime(FileDocumentCache(files.newFolder()), models.provider, models.labeler)
        val result = runCatching { runtime.labelTopic(request) { true } }.getOrNull()
        runtime.foregroundStep { runtime.inference.withLock { runtime.embedExactInput("search") } }
        assertTrue(result is TopicLabelRun.Completed)
        assertEquals(1, models.releases); assertEquals(1, models.embeddingCalls)
        assertFalse(models.labelLive)
    }
    @Test fun foregroundSearchWaitsForNativeCleanupAndRunsBeforeQueuedBulkWork() = runTest {
        val models = Models().apply { delayed = true }
        val runtime = DiscoveryRuntime(FileDocumentCache(files.newFolder()), models.provider, models.labeler)
        val labels = async { runCatching { runtime.labelTopic(request) { true } }.getOrNull() }; runCurrent()
        val order = mutableListOf<String>()
        val search = async { runtime.foregroundStep {
            runtime.inference.withLock { runtime.embedExactInput("query") }; order += "search"
        } }
        val bulk = async { runtime.bulkStep { order += "bulk" } }; runCurrent()
        val cancellationSeen = models.cancellationSeen.isCompleted
        val embeddedBeforeTerminal = models.embeddingCalls
        models.nativeStopped.complete(Unit); models.finish.complete(Unit); advanceUntilIdle()
        labels.await(); search.await(); bulk.await()
        assertTrue("A foreground query must interrupt labeling", cancellationSeen)
        assertEquals(0, embeddedBeforeTerminal)
        assertEquals(listOf("search", "bulk"), order)
    }
    @Test fun playbackPowerOrWindowDeferralCancelsWorkWithoutReleasingBeforeTerminal() = runTest {
        val models = Models().apply { delayed = true }
        val runtime = DiscoveryRuntime(FileDocumentCache(files.newFolder()), models.provider, models.labeler)
        var permitted = true
        val labels = async { runCatching { runtime.labelTopic(request) { permitted } }.getOrNull() }; runCurrent()
        permitted = false; advanceTimeBy(60); runCurrent()
        val cancelled = models.cancellationSeen.isCompleted
        val stillRunning = models.nativeRunning
        models.nativeStopped.complete(Unit); models.finish.complete(Unit); advanceUntilIdle()
        labels.await()
        assertTrue(cancelled); assertTrue(stillRunning)
    }
    @Test fun aSourceChangeDuringLabelingCannotReturnACompletedCandidate() = runTest {
        val cache = FileDocumentCache(files.newFolder())
        val models = Models().apply { delayed = true }
        val runtime = DiscoveryRuntime(cache, models.provider, models.labeler)
        val labels = async { runCatching { runtime.labelTopic(request) { true } }.getOrNull() }; runCurrent()
        assertTrue("The test must observe active native labeling", models.entered.isCompleted)
        cache.put(CachedDocument(ReaderDocument.fromChunkTexts("changed", "Changed", listOf("New body")), null, Instant.EPOCH))
        advanceTimeBy(60); runCurrent()
        models.nativeStopped.complete(Unit); models.finish.complete(Unit); advanceUntilIdle()
        assertFalse(labels.await() is TopicLabelRun.Completed)
    }
}
