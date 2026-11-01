package com.example.notionreader.application

import com.example.notionreader.data.local.FileProposalProgressStore
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingProposalStepTest {
    @get:Rule val files = TemporaryFolder()
    private val identity = EmbeddingIdentity("model", 2)
    private fun record(text: String = "Moisture allows dormant seeds to absorb water and begin the germination process."): SemanticRecord {
        val p = SemanticPassage("p", "note", "section", "Overview", "block", "chunk", 0, text.length, text, text, 0)
        return SemanticRecord("note", "r1", identity, SemanticMetadata("note"), listOf(EmbeddedPassage(p, listOf(1f, 0f))))
    }
    @Test fun aPersistedDeltaFeedsCurrentUnexplainedBodiesIntoTheProposalWindow() = runBlocking {
        val store = FileProposalProgressStore(files.newFolder()); val note = record()
        val delta = PassageDeltaRecord("note", "r1", identity, passageDeltaFingerprint(note), PassageDelta(setOf("p"), emptySet(), emptyMap()))
        assertEquals(ProposalProgressWriteResult.Written, store.writeDelta(delta) { true })
        val result = advancePendingProposalWindow(note, emptySet(), mapOf("note" to "r1"), emptySet(), false, 0, store, 1, { true }, { true }) { null }
            as PendingProposalStepResult.Ready
        assertEquals("p", result.step.window.proposals.single().evidence.single().passageId)
        assertFalse(result.step.scanPending)
        assertNotNull(store.readDelta("note", "r1", identity))
    }
    @Test fun missingOrChangedEvidenceCannotStartTheProposalWindow() = runBlocking {
        val store = FileProposalProgressStore(files.newFolder()); val note = record()
        suspend fun step(value: SemanticRecord) = advancePendingProposalWindow(value, emptySet(), mapOf("note" to "r1"), emptySet(), true, 0, store, 1, { true }, { true }) { error("No read") }
        assertEquals(PendingProposalStepResult.Missing, step(note))
        val delta = PassageDeltaRecord("note", "r1", identity, passageDeltaFingerprint(note), PassageDelta(setOf("p"), emptySet(), emptyMap()))
        assertEquals(ProposalProgressWriteResult.Written, store.writeDelta(delta) { true })
        assertEquals(PendingProposalStepResult.Stale, step(record("The passage body has changed and is no longer the same evidence.")))
    }
    @Test fun onlyCurrentChangedBodiesAreEligibleAndExplainedBodiesAreNotProposed() = runBlocking {
        val store = FileProposalProgressStore(files.newFolder()); val note = record()
        val delta = PassageDeltaRecord("note", "r1", identity, passageDeltaFingerprint(note), PassageDelta(setOf("invented"), emptySet(), emptyMap()))
        assertEquals(ProposalProgressWriteResult.Written, store.writeDelta(delta) { true })
        suspend fun step(explained: Set<String>) = advancePendingProposalWindow(note, explained, mapOf("note" to "r1"), emptySet(), true, 0, store, 1, { true }, { true }) { null }
        assertEquals(PendingProposalStepResult.Stale, step(emptySet()))
        assertEquals(ProposalProgressWriteResult.Written, store.writeDelta(delta.copy(delta = delta.delta.copy(changed = setOf("p")))) { true })
        assertTrue((step(setOf("p")) as PendingProposalStepResult.Ready).step.window.proposals.isEmpty())
    }
}
