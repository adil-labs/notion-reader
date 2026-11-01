package com.example.notionreader.data.local

import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.document.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileProposalProgressStoreTest {
    @get:Rule val files = TemporaryFolder()
    private val sources = mapOf("new" to "r1", "older" to "r2")
    private val record = ProposalProgressRecord("new", "r1", 0, EmbeddingIdentity("model", 2),
        ProposalScanCheckpoint(semanticHash("context"), mapOf("older" to "r2"), mapOf("anchor" to listOf(
            ProposalScoredEvidence(ProposalEvidenceRef("older", "r2", "passage"), .9f, ProposalReason.SEMANTIC_NEIGHBOR))), emptySet()))

    @Test fun sourceReferencesAndProgressSurviveReopeningWithoutSourceBodies() = runBlocking {
        val dir = files.newFolder(); val store = FileProposalProgressStore(dir)
        assertEquals(ProposalProgressWriteResult.Written, store.write(record) { true })
        assertEquals(record, FileProposalProgressStore(dir).read("new", 0, sources))
        assertEquals(dir.listFiles()!!.sumOf { it.length() }, store.bytes())
        assertFalse(dir.listFiles()!!.single().readText().contains("passage body"))
    }

    @Test fun withdrawnSourcesCannotBeReadOrRetainedAfterReconciliation() = runBlocking {
        val dir = files.newFolder(); val store = FileProposalProgressStore(dir)
        assertEquals(ProposalProgressWriteResult.Written, store.write(record) { true })
        assertNull(store.read("new", 0, sources - "older"))
        store.reconcile(sources - "older")
        assertNull(FileProposalProgressStore(dir).read("new", 0, sources))
        assertEquals(0L, store.bytes())
    }

    @Test fun staleCommitAndStorageRefusalCannotResurrectWithdrawnEvidence() = runBlocking {
        val dir = files.newFolder(); val store = FileProposalProgressStore(dir)
        assertEquals(ProposalProgressWriteResult.Written, store.write(record) { true })
        var checks = 0
        assertEquals(ProposalProgressWriteResult.Stale, store.write(record) { ++checks == 1 })
        val refused = FileProposalProgressStore(dir, object : StorageWriteGate {
            override suspend fun <T> write(category: StorageCategory, additionalPeakBytes: Long, block: suspend () -> T): T =
                throw StorageRefused(StorageFailure.DEVICE_SPACE)
        })
        assertEquals(ProposalProgressWriteResult.StorageRefused, refused.write(record) { true })
        assertNull(refused.read("new", 0, sources - "older"))
        store.reconcile(sources - "older")
        assertNull(refused.read("new", 0, sources))
        assertFalse(dir.listFiles()!!.any { it.extension == "tmp" })
    }

    @Test fun invalidReferencesAndCorruptFilesCannotRestoreProgress() = runBlocking {
        val dir = files.newFolder(); val store = FileProposalProgressStore(dir)
        val invalid = record.copy(checkpoint = record.checkpoint.copy(scannedRevisions = emptyMap()))
        assertEquals(ProposalProgressWriteResult.Invalid, store.write(invalid) { true })
        assertEquals(ProposalProgressWriteResult.Written, store.write(record) { true })
        dir.listFiles()!!.single().writeText("broken")
        assertNull(FileProposalProgressStore(dir).read("new", 0, sources))
        store.reconcile(sources); assertEquals(0L, store.bytes())
    }

    @Test fun resetDeletesOnlyOwnedProgressFiles() = runBlocking {
        val dir = files.newFolder(); val store = FileProposalProgressStore(dir)
        val unrelated = File(dir, "unrelated.txt").apply { writeText("keep") }
        assertEquals(ProposalProgressWriteResult.Written, store.write(record) { true }); store.clear()
        assertNull(store.read("new", 0, sources)); assertEquals("keep", unrelated.readText())
    }
}
