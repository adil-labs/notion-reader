package com.example.notionreader.application

import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.data.local.FileTopicStore
import com.example.notionreader.domain.discovery.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class TopicWorkResetTest {
    @Test fun clearingPartialDiscoveryRemovesItsPendingWork() = runBlocking {
        withCoordinator { coordinator, sources, revision ->
            coordinator.build(sources, 0, revision)
            assertTrue("The precondition must actually leave unfinished work", coordinator.needsMoreWork)
            coordinator.clear()
            assertFalse(coordinator.needsMoreWork)
        }
    }

    @Test fun obsoleteFormationCannotRestorePendingWorkAfterClear() = runBlocking {
        var resetBeforeStep = false
        var resets = 0
        lateinit var coordinator: TopicCoordinator
        withCoordinator(
            beforeBulk = { if (resetBeforeStep) { resetBeforeStep = false; resets++; coordinator.clear() } },
            changed = { if (it.status == "Finding topics in all indexed cached Notes…") resetBeforeStep = true }
        ) { worker, sources, revision ->
            coordinator = worker
            worker.build(sources, 0, revision)
            assertEquals("The reset must happen inside the old formation quantum", 1, resets)
            assertFalse(worker.needsMoreWork)
        }
    }

    private suspend fun withCoordinator(beforeBulk: suspend () -> Unit = {}, changed: (TopicView) -> Unit = {},
        check: suspend (TopicCoordinator, Map<String, String>, Long) -> Unit) {
        val directory = Files.createTempDirectory("topic-work-reset").toFile()
        try {
            val cache = FileDocumentCache(directory.resolve("cache"))
            val index = FileSemanticIndexStore(directory.resolve("index"))
            val model = EmbeddingIdentity("reset-proof", 2)
            val sources = (0..3).associate { "p$it" to "r1" }
            for (id in sources.keys) {
                val text = "Forest ecology supports woodland wildlife and diverse habitats."
                val passage = SemanticPassage("$id:p", id, "$id:s", "Forest ecology", "$id:b", "$id:c",
                    0, text.length, text, text, 0)
                assertTrue(index.write(SemanticRecord(id, "r1", model, SemanticMetadata(id),
                    listOf(EmbeddedPassage(passage, listOf(1f, 0f))))))
            }
            val worker = TopicCoordinator(cache, index, FileTopicStore(directory.resolve("topics")),
                FileTopicStore(directory.resolve("checkpoint")), { model }, TopicPolicy(), Mutex(), { true },
                beforeBulk, changed, formationWorkBudget = 1, shouldYield = { true })
            index.setPaused(false)
            check(worker, sources, cache.revision.value)
        } finally { directory.deleteRecursively() }
    }
}
