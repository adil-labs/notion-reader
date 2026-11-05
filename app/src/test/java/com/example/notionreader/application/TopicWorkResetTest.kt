package com.example.notionreader.application

import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.data.local.FileTopicStore
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.document.CachedDocument
import java.time.Instant
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class TopicWorkResetTest {
    @Test fun clearingPartialDiscoveryRemovesItsPendingWork() = runBlocking {
        withCoordinator { coordinator, store, sources, revision ->
            coordinator.invalidate(sources)
            coordinator.build(sources, 0, revision, metadata = sources.keys.associateWith { SemanticMetadata(it) })
            assertTrue("The precondition must actually leave unfinished work", coordinator.needsMoreWork)
            assertTrue(store.beginReset())
            coordinator.clear()
            assertFalse(coordinator.needsMoreWork)
        }
    }

    private suspend fun withCoordinator(beforeBulk: suspend () -> Unit = {}, changed: (TopicView) -> Unit = {},
        check: suspend (TopicCoordinator, TopicStore, Map<String, String>, Long) -> Unit) {
        val directory = Files.createTempDirectory("topic-work-reset").toFile()
        try {
            val cache = FileDocumentCache(directory.resolve("cache"))
            val index = FileSemanticIndexStore(directory.resolve("index"))
            val model = EmbeddingIdentity("reset-proof", 2)
            val sources = linkedMapOf<String, String>()
            for (id in (0..3).map { "p$it" }) {
                val text = "Forest ecology supports woodland wildlife and diverse habitats."
                val document = ReaderDocument.fromChunkTexts(id, id, listOf(text))
                cache.put(CachedDocument(document, null, Instant.EPOCH))
                val revision = semanticRevision(document)
                sources[id] = revision
                val passage = SemanticPassage("$id:p", id, "$id:s", "Forest ecology", "$id:b", "$id:c",
                    0, text.length, text, text, 0)
                assertTrue(index.write(SemanticRecord(id, revision, model, SemanticMetadata(id),
                    listOf(EmbeddedPassage(passage, listOf(1f, 0f))))))
            }
            val store = FileTopicStore(directory.resolve("topics"))
            assertTrue(store.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, acceptedRevisions = sources,
                connectionPassComplete = false, policyVersion = TopicPolicy().version)))
            val worker = TopicCoordinator(cache, index, store,
                FileTopicStore(directory.resolve("checkpoint")), { model }, TopicPolicy(), Mutex(), { true },
                beforeBulk, changed, shouldYield = { true })
            index.setPaused(false)
            check(worker, store, sources, cache.revision.value)
        } finally { directory.deleteRecursively() }
    }
}
