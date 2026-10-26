package com.example.notionreader.data.local

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileTopicLabelStoreTest {
    @get:Rule val files = TemporaryFolder()
    private val label = GeneratedTopicLabel("topic", semanticHash("sources"), semanticHash("request"),
        "Seed germination", TopicLabelIdentity("a", "t", "c", "p", "s", "v", "GPU"), listOf("p"), "How moisture supports seed germination.")
    @Test fun labelsSurviveReopeningWithoutPersistingPassageBodiesOrVectors() = runBlocking {
        val dir = files.newFolder()
        val first = FileTopicLabelStore(dir)
        assertEquals(TopicLabelWriteResult.Written, first.write(label) { true })
        assertEquals(label, FileTopicLabelStore(dir).read("topic"))
        val text = dir.listFiles()!!.filter { it.extension == "json" }.single().readText()
        assertFalse(text.contains("passageBody")); assertFalse(text.contains("vector"))
        assertEquals(dir.listFiles()!!.sumOf { it.length() }, first.bytes())
    }
    @Test fun oldNameOnlyRecordsBecomePendingInsteadOfInventingRetrievalDefinitions() = runBlocking {
        val dir = files.newFolder()
        val store = FileTopicLabelStore(dir)
        assertEquals(TopicLabelWriteResult.Invalid, store.write(label.copy(definition = "")) { true })
        assertEquals(TopicLabelWriteResult.Written, store.write(label) { true })
        val record = dir.listFiles()!!.single()
        record.writeText(record.readText().replace("\"version\":2", "\"version\":1")
            .replace(",\"definition\":\"How moisture supports seed germination.\"", ""))
        assertNull(FileTopicLabelStore(dir).read(label.topicId))
    }
    @Test fun aSourceChangeAtTheCommitBoundaryPreservesThePreviousRecord() = runBlocking {
        val dir = files.newFolder(); val store = FileTopicLabelStore(dir)
        store.write(label) { true }
        var checks = 0
        assertEquals(TopicLabelWriteResult.Stale, store.write(label.copy(text = "Changed label")) { ++checks == 1 })
        assertEquals(label, FileTopicLabelStore(dir).read("topic"))
        assertFalse(dir.listFiles()!!.any { it.extension == "tmp" })
    }
    @Test fun withdrawingOrChangingAGroupRemovesItsDerivedLabel() = runBlocking {
        val dir = files.newFolder(); val store = FileTopicLabelStore(dir)
        store.write(label) { true }; store.write(label.copy(topicId = "other")) { true }
        store.prune(mapOf("topic" to "changed fingerprint", "other" to label.groupFingerprint))
        assertNull(store.read("topic")); assertEquals(label.copy(topicId = "other"), store.read("other"))
        store.remove("other"); assertNull(store.read("other"))
    }
    @Test fun invalidOrCorruptedRecordsNeverBecomeSuccessfulCacheEntries() = runBlocking {
        val dir = files.newFolder(); val store = FileTopicLabelStore(dir)
        assertEquals(TopicLabelWriteResult.Invalid, store.write(label.copy(text = "\uD800")) { true })
        store.write(label) { true }
        dir.listFiles()!!.single().writeText("broken JSON")
        assertNull(FileTopicLabelStore(dir).read("topic"))
    }
    @Test fun storageRefusalPreservesThePreviouslyCommittedLabel() = runBlocking {
        val dir = files.newFolder()
        FileTopicLabelStore(dir).write(label) { true }
        val gate = object : com.example.notionreader.domain.document.StorageWriteGate {
            override suspend fun <T> write(category: com.example.notionreader.domain.document.StorageCategory,
                additionalPeakBytes: Long, block: suspend () -> T): T =
                throw com.example.notionreader.domain.document.StorageRefused(com.example.notionreader.domain.document.StorageFailure.DEVICE_SPACE)
        }
        val limited = FileTopicLabelStore(dir, gate)
        assertEquals(TopicLabelWriteResult.StorageRefused, limited.write(label.copy(text = "Replacement")) { true })
        assertEquals(label, FileTopicLabelStore(dir).read("topic"))
    }

    @Test fun aFairnessCursorSurvivesRestartAndClearRemovesIt() = runBlocking {
        val dir = files.newFolder(); val cursor = TopicLabelCursor(42, "canonical-topic")
        val store = FileTopicLabelStore(dir)
        assertEquals(TopicLabelWriteResult.Written, store.writeCursor(cursor) { true })
        assertEquals(cursor, FileTopicLabelStore(dir).readCursor())
        store.clear(); assertNull(FileTopicLabelStore(dir).readCursor())
    }

    private suspend fun blockedDirectoryPreservesCommittedRecords(write: suspend (FileTopicLabelStore) -> TopicLabelWriteResult) {
        val dir = files.newFolder()
        val backup = java.io.File(dir.parentFile, "${dir.name}-retained")
        val store = FileTopicLabelStore(dir)
        val cursor = TopicLabelCursor(42, "canonical-topic")
        assertEquals(TopicLabelWriteResult.Written, store.write(label) { true })
        assertEquals(TopicLabelWriteResult.Written, store.writeCursor(cursor) { true })
        assertTrue(dir.renameTo(backup))
        try {
            dir.writeText("A file now obstructs the store directory")
            assertEquals(TopicLabelWriteResult.IoFailure, write(store))
        } finally {
            assertTrue(dir.delete())
            assertTrue(backup.renameTo(dir))
        }
        assertEquals(label, store.read("topic"))
        assertEquals(cursor, store.readCursor())
        assertFalse(dir.listFiles()!!.any { it.extension == "tmp" })
    }

    @Test fun anObstructedDirectoryReportsLabelWriteFailureWithoutLosingCommittedRecords() = runBlocking {
        blockedDirectoryPreservesCommittedRecords { it.write(label.copy(text = "Replacement")) { true } }
    }

    @Test fun anObstructedDirectoryReportsProgressWriteFailureWithoutLosingCommittedRecords() = runBlocking {
        blockedDirectoryPreservesCommittedRecords { it.writeCursor(TopicLabelCursor(43, "next-topic")) { true } }
    }

}
