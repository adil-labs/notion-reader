package com.example.notionreader.data.local

import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.document.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileTopicRetrievalStoreTest {
    @get:Rule val files = TemporaryFolder()
    private val query = TopicRetrievalQuery("topic", semanticHash("concept"), "Generated name\nSource-supported definition.",
        EmbeddingIdentity("model", 2), TopicRetrievalPolicy(.8f))
    private val progress = TopicRetrievalProgress(TopicQueryEmbedding(query, listOf(1f, 0f)),
        mapOf("outside" to "r1"), mapOf("outside" to TopicMatchReference("outside", "r1", "outside:p", .95f)), true)

    @Test fun queryVectorsAndSourceReferencesSurviveRestartWithoutAnotherCatalog() = runBlocking {
        val dir = files.newFolder(); val store = FileTopicRetrievalStore(dir)
        assertEquals(TopicRetrievalWriteResult.Written, store.write(progress) { true })
        assertEquals(progress, FileTopicRetrievalStore(dir).read("topic"))
        assertNull(store.read("unrelated"))
        assertFalse(dir.listFiles()!!.single().readText().contains("passageBody"))
        assertEquals(dir.listFiles()!!.sumOf { it.length() }, store.bytes())
    }
    @Test fun aStaleCommitPreservesTheLastCompleteGeneration() = runBlocking {
        val dir = files.newFolder(); val store = FileTopicRetrievalStore(dir)
        store.write(progress) { true }
        var checks = 0
        assertEquals(TopicRetrievalWriteResult.Stale, store.write(progress.copy(complete = false)) { ++checks == 1 })
        assertEquals(progress, store.read("topic"))
        assertFalse(dir.listFiles()!!.any { it.extension == "tmp" })
    }
    @Test fun storageRefusalAndAnObstructedDirectoryPreserveCommittedResults() = runBlocking {
        val dir = files.newFolder(); val store = FileTopicRetrievalStore(dir)
        store.write(progress) { true }
        val refused = FileTopicRetrievalStore(dir, object : StorageWriteGate {
            override suspend fun <T> write(category: StorageCategory, additionalPeakBytes: Long, block: suspend () -> T): T =
                throw StorageRefused(StorageFailure.DEVICE_SPACE)
        })
        assertEquals(TopicRetrievalWriteResult.StorageRefused, refused.write(progress.copy(complete = false)) { true })
        val backup = File(dir.parentFile, "${dir.name}-retained")
        assertTrue(dir.renameTo(backup))
        try {
            dir.writeText("obstructed")
            assertEquals(TopicRetrievalWriteResult.IoFailure, store.write(progress) { true })
        } finally { assertTrue(dir.delete()); assertTrue(backup.renameTo(dir)) }
        assertEquals(progress, store.read("topic"))
    }
    @Test fun corruptedAndUnboundMatchesNeverBecomeSavedSuccessfulWork() = runBlocking {
        val dir = files.newFolder(); val store = FileTopicRetrievalStore(dir)
        assertEquals(TopicRetrievalWriteResult.Invalid, store.write(progress.copy(scannedRevisions = emptyMap())) { true })
        store.write(progress) { true }
        dir.listFiles()!!.single().writeText("broken JSON")
        assertNull(FileTopicRetrievalStore(dir).read("topic"))
    }
    @Test fun pruningAndResetRemoveOnlyOwnedRetrievalFiles() = runBlocking {
        val dir = files.newFolder(); val store = FileTopicRetrievalStore(dir)
        File(dir, "unrelated.txt").writeText("preserve")
        store.write(progress) { true }
        store.prune(setOf("topic")); assertEquals(progress, store.read("topic"))
        store.prune(emptySet()); assertNull(store.read("topic"))
        store.write(progress) { true }; store.clear()
        assertNull(store.read("topic")); assertEquals("preserve", File(dir, "unrelated.txt").readText())
        assertEquals(0L, store.bytes())
    }
}
