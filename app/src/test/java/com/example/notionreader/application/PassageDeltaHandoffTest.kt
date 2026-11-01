package com.example.notionreader.application

import com.example.notionreader.data.local.FileProposalProgressStore
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PassageDeltaHandoffTest {
    @get:Rule val files = TemporaryFolder()
    private val embedding = EmbeddingIdentity("model", 2)
    private fun record(revision: String, text: String): SemanticRecord {
        val passage = SemanticPassage("p", "note", "section", "title", "block", "chunk", 0, text.length, text, text, 0)
        return SemanticRecord("note", revision, embedding, SemanticMetadata("note"), listOf(EmbeddedPassage(passage, listOf(1f, 0f))))
    }
    @Test fun handoffSurvivesOldRecordRemovalAndReopening() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index"))
        index.write(record("old", "old text")); val current = record("new", "new text")
        val store = FileProposalProgressStore(root.resolve("progress"))
        val first = capturePassageDelta(current, index, store) { true } as PassageDeltaHandoff.Ready
        assertEquals(setOf("p"), first.record.delta.changed)
        index.remove("note")
        val restored = capturePassageDelta(current, index, FileProposalProgressStore(root.resolve("progress"))) { true }
        assertEquals(first, restored)
    }
    @Test fun secondEditDoesNotLoseUnprocessedEarlierChanges() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index"))
        val store = FileProposalProgressStore(root.resolve("progress"))
        val first = record("r1", "first text"); val next = record("r2", "first text")
        assertTrue(capturePassageDelta(first, index, store) { true } is PassageDeltaHandoff.Ready)
        index.write(first)
        val delta = (capturePassageDelta(next, index, store) { true } as PassageDeltaHandoff.Ready).record.delta
        assertEquals(setOf("p"), delta.changed)
    }
    @Test fun mismatchedCurrentBodiesCannotReuseTheSavedHandoff() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index"))
        val store = FileProposalProgressStore(root.resolve("progress"))
        val first = capturePassageDelta(record("r1", "first"), index, store) { true } as PassageDeltaHandoff.Ready
        val second = capturePassageDelta(record("r1", "different"), index, store) { true } as PassageDeltaHandoff.Ready
        assertNotEquals(first.record.fingerprint, second.record.fingerprint)
    }
    @Test fun onlyACoveredPriorRevisionCanSkipUnchangedPassages() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index"))
        index.write(record("r1", "same text")); val current = record("r2", "same text")
        val unknown = capturePassageDelta(current, index, FileProposalProgressStore(root.resolve("unknown"))) { true } as PassageDeltaHandoff.Ready
        val covered = capturePassageDelta(current, index, FileProposalProgressStore(root.resolve("covered")), previousCovered = true) { true } as PassageDeltaHandoff.Ready
        assertEquals(setOf("p"), unknown.record.delta.changed)
        assertTrue(covered.record.delta.changed.isEmpty())
    }
    @Test fun aStaleOrRefusedHandoffCannotBeReportedReady() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index"))
        val real = FileProposalProgressStore(root.resolve("progress")); val current = record("new", "new text")
        val refused = object : ProposalProgressStore by real {
            override suspend fun writeDelta(record: PassageDeltaRecord, stillCurrent: () -> Boolean) = ProposalProgressWriteResult.StorageRefused
        }
        assertEquals(PassageDeltaHandoff.StorageFailure(ProposalProgressWriteResult.StorageRefused), capturePassageDelta(current, index, refused) { true })
        assertEquals(PassageDeltaHandoff.Stale, capturePassageDelta(current, index, real) { false })
    }
}
