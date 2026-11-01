package com.example.notionreader.application

import com.example.notionreader.data.local.FileProposalProgressStore
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExistingConceptEvidenceTest {
    @get:Rule val files = TemporaryFolder()
    private val embedding = EmbeddingIdentity("model", 2)
    private val verifier = TopicLabelIdentity("a", "t", "template", "p", "s", "policy", "CPU")
    private val concept = SupportConcept("topic", semanticHash("concept"), "Seed germination", "Conditions needed for seeds to germinate.")
    private val query = TopicQueryEmbedding(TopicRetrievalQuery(concept.topicId, concept.workKey, "seed germination", embedding, TopicRetrievalPolicy(.5f)), listOf(1f, 0f))
    private fun record(): SemanticRecord {
        fun p(id: String, order: Int): EmbeddedPassage {
            val text = "Moisture allows dormant seeds to absorb water and begin germination number $order."
            return EmbeddedPassage(SemanticPassage(id, "note", "section", "Overview", "block$id", "chunk$id", 0, text.length, text, text, order), listOf(1f, 0f))
        }
        return SemanticRecord("note", "r1", embedding, SemanticMetadata("note"), listOf(p("a", 0), p("b", 1)))
    }
    @Test fun similarityOnlyRetrievesCandidatesAndIndependentSupportControlsExplanation() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val note = record(); index.write(note)
        val store = FileProposalProgressStore(root.resolve("progress")); val calls = mutableListOf<String>()
        suspend fun step(cursor: TopicCandidateCursor?) = verifyExistingConceptPage(concept, query, note, index, verifier, 7,
            mapOf("note" to "r1"), cursor, 1, store, { true }, { true }) {
            calls += it.evidence.passageId
            TopicSupportRun.Completed(if (it.evidence.passageId == "a") SupportDecision.UNSUPPORTED else SupportDecision.SUPPORTED)
        } as ExistingConceptEvidenceResult.Ready
        val first = step(null); assertTrue(first.supported.isEmpty()); assertNotNull(first.next)
        val second = step(first.next); assertEquals(setOf("b"), second.supported); assertNull(second.next)
        assertEquals(listOf("a", "b"), calls)
        assertEquals(first, step(null)); assertEquals(listOf("a", "b"), calls)
    }
    @Test fun uncertainSupportRemainsUnresolvedAndStaleSourceCannotBeExplained() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val note = record(); index.write(note)
        val store = FileProposalProgressStore(root.resolve("progress"))
        val result = verifyExistingConceptPage(concept, query, note, index, verifier, 7, mapOf("note" to "r1"), null, 2,
            store, { true }, { true }) { TopicSupportRun.Completed(SupportDecision.UNCERTAIN) } as ExistingConceptEvidenceResult.Ready
        assertTrue(result.supported.isEmpty()); assertEquals(setOf("a", "b"), result.uncertain)
        assertEquals(ExistingConceptEvidenceResult.Stale, verifyExistingConceptPage(concept, query, note, index, verifier, 7,
            emptyMap(), null, 2, store, { true }, { true }) { error("No inference") })
    }
    @Test fun onlyVerifiedExistingSupportIsRemovedFromNewProposalAnchors() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val note = record(); index.write(note)
        val store = FileProposalProgressStore(root.resolve("progress")); val sources = mapOf("note" to "r1")
        val delta = PassageDeltaRecord("note", "r1", embedding, passageDeltaFingerprint(note), PassageDelta(setOf("a", "b"), emptySet(), emptyMap()))
        assertEquals(ProposalProgressWriteResult.Written, store.writeDelta(delta) { true })
        val existing = verifyExistingConceptPage(concept, query, note, index, verifier, 7, sources, null, 2,
            store, { true }, { true }) {
            TopicSupportRun.Completed(if (it.evidence.passageId == "a") SupportDecision.UNSUPPORTED else SupportDecision.SUPPORTED)
        } as ExistingConceptEvidenceResult.Ready
        val proposals = advancePendingProposalWindow(note, existing.supported, sources, emptySet(), true, 0,
            store, 1, { true }, { true }) { null } as PendingProposalStepResult.Ready
        assertEquals(listOf("a"), proposals.step.window.proposals.map { it.evidence.first().passageId })
    }
    @Test fun mismatchedConceptQueryCannotExplainAnyPassage() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val note = record(); index.write(note)
        val store = FileProposalProgressStore(root.resolve("progress"))
        assertEquals(ExistingConceptEvidenceResult.Invalid, verifyExistingConceptPage(concept.copy(workKey = semanticHash("other")), query,
            note, index, verifier, 7, mapOf("note" to "r1"), null, 2, store, { true }, { true }) { error("No inference") })
    }
}
