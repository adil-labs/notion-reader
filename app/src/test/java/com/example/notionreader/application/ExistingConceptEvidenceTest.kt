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

    private fun rankedRecord(): SemanticRecord {
        fun p(id: String, order: Int, vector: List<Float>): EmbeddedPassage {
            val text = "Moisture allows dormant seeds to absorb water and begin germination number $order."
            return EmbeddedPassage(SemanticPassage(id, "note", "section", "Overview", "block$id", "chunk$id", 0,
                text.length, text, text, order), vector)
        }
        return SemanticRecord("note", "r1", embedding, SemanticMetadata("note"),
            listOf(p("a", 0, listOf(1f, 0f)), p("b", 1, listOf(.8f, .6f)), p("c", 2, listOf(.6f, .8f))))
    }

    @Test fun aSingleVerifiedStrongestPassageStopsBeforeLowerRankedCandidates() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val note = rankedRecord(); index.write(note)
        val store = FileProposalProgressStore(root.resolve("progress")); val calls = mutableListOf<String>()
        val page = verifyExistingConceptPage(concept, query, note, index, verifier, 7, mapOf("note" to "r1"),
            null, 1, store, { true }, { true }) {
            calls += it.evidence.passageId
            TopicSupportRun.Completed(SupportDecision.SUPPORTED)
        } as ExistingConceptEvidenceResult.Ready
        assertEquals(listOf("a"), calls)
        assertEquals(setOf("a"), page.supported)
        assertNull("Early support must not demand the unchecked tail", page.next)
        assertEquals(ConceptScanTermination.SUPPORTED_EARLY, page.termination)
        assertEquals(concept.workKey, page.earlyStoppedWorkKey)
        assertEquals(1, page.examined)
        assertTrue(page.retrieved >= 1)
    }

    @Test fun unsupportedThenSupportedUsesExactlyTwoChecksAndLeavesTheTailUnchecked() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val note = rankedRecord(); index.write(note)
        val store = FileProposalProgressStore(root.resolve("progress")); val calls = mutableListOf<String>()
        suspend fun step(cursor: TopicCandidateCursor?) = verifyExistingConceptPage(concept, query, note, index, verifier, 7,
            mapOf("note" to "r1"), cursor, 1, store, { true }, { true }) {
            calls += it.evidence.passageId
            TopicSupportRun.Completed(if (it.evidence.passageId == "a") SupportDecision.UNSUPPORTED else SupportDecision.SUPPORTED)
        } as ExistingConceptEvidenceResult.Ready
        val first = step(null)
        assertTrue(first.supported.isEmpty()); assertNotNull(first.next)
        val second = step(first.next)
        assertEquals(listOf("a", "b"), calls)
        assertEquals(setOf("b"), second.supported)
        assertNull(second.next)
        assertEquals(ConceptScanTermination.SUPPORTED_EARLY, second.termination)
    }

    @Test fun allUnsupportedPassagesProduceAnExhaustedNegativeOutcome() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val note = rankedRecord(); index.write(note)
        val store = FileProposalProgressStore(root.resolve("progress")); val calls = mutableListOf<String>()
        suspend fun step(cursor: TopicCandidateCursor?) = verifyExistingConceptPage(concept, query, note, index, verifier, 7,
            mapOf("note" to "r1"), cursor, 2, store, { true }, { true }) {
            calls += it.evidence.passageId
            TopicSupportRun.Completed(SupportDecision.UNSUPPORTED)
        } as ExistingConceptEvidenceResult.Ready
        val first = step(null); assertNotNull(first.next)
        val done = step(first.next)
        assertEquals(listOf("a", "b", "c"), calls)
        assertTrue(done.supported.isEmpty())
        assertNull(done.next)
        assertEquals(ConceptScanTermination.EXHAUSTED, done.termination)
    }

    @Test fun uncertaintyBeforeSupportIsRetainedRatherThanSilentlyDiscarded() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val note = rankedRecord(); index.write(note)
        val store = FileProposalProgressStore(root.resolve("progress")); val calls = mutableListOf<String>()
        suspend fun step(cursor: TopicCandidateCursor?) = verifyExistingConceptPage(concept, query, note, index, verifier, 7,
            mapOf("note" to "r1"), cursor, 1, store, { true }, { true }) {
            calls += it.evidence.passageId
            TopicSupportRun.Completed(if (it.evidence.passageId == "a") SupportDecision.UNCERTAIN else SupportDecision.SUPPORTED)
        } as ExistingConceptEvidenceResult.Ready
        val first = step(null)
        assertNotNull(first.next)
        // Page results retain the decisions made in that page; the Note-level scan accumulates
        // them (see PendingConceptRetrievalTest). Uncertainty is never dropped on the floor.
        assertEquals(setOf("a"), first.uncertain)
        val second = step(first.next)
        assertEquals(listOf("a", "b"), calls)
        assertEquals(setOf("b"), second.supported)
        assertEquals(ConceptScanTermination.SUPPORTED_EARLY, second.termination)
    }

    @Test fun supportFirstAndExhaustiveCollectTheSameMembershipButNotTheSameCoverage() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val note = rankedRecord(); index.write(note)
        val store = FileProposalProgressStore(root.resolve("progress"))
        val supportFirstCalls = mutableListOf<String>()
        val supportFirst = verifyExistingConceptPage(concept, query, note, index, verifier, 7, mapOf("note" to "r1"),
            null, 1, store, { true }, { true }) {
            supportFirstCalls += it.evidence.passageId
            TopicSupportRun.Completed(if (it.evidence.passageId == "a") SupportDecision.SUPPORTED else SupportDecision.UNSUPPORTED)
        } as ExistingConceptEvidenceResult.Ready
        // Exhaustive comparison: the same shipping pager and verifier, every eligible candidate,
        // with its own store so cached decisions from the support-first arm cannot hide a check.
        val exhaustiveStore = FileProposalProgressStore(root.resolve("progress-exhaustive"))
        val exhaustiveCalls = mutableListOf<String>()
        var cursor: TopicCandidateCursor? = null
        do {
            val page = topicCandidatePage(query, note, "r1", 1, cursor) as TopicCandidatePage.Ready
            for (candidate in page.candidates) {
                val result = verifyRetrievedPassage(concept, candidate, index, embedding, verifier, 7,
                    mapOf("note" to "r1"), { true }, exhaustiveStore) {
                    exhaustiveCalls += it.evidence.passageId
                    TopicSupportRun.Completed(if (it.evidence.passageId == "a") SupportDecision.SUPPORTED else SupportDecision.UNSUPPORTED)
                } as SupportVerificationResult.Completed
                assertTrue(result.assessment.decision in SupportDecision.entries)
            }
            cursor = page.next
        } while (cursor != null)
        assertEquals(listOf("a"), supportFirstCalls)
        assertEquals(listOf("a", "b", "c"), exhaustiveCalls)
        assertEquals(setOf("a"), supportFirst.supported)
        assertEquals(ConceptScanTermination.SUPPORTED_EARLY, supportFirst.termination)
    }

    @Test fun earlyStopDiagnosticsReportTheCheckedPrefixNotInventedCoverage() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val note = rankedRecord(); index.write(note)
        val store = FileProposalProgressStore(root.resolve("progress"))
        val events = mutableListOf<Map<String, Long>>()
        kotlinx.coroutines.withContext(DiscoveryWorkDiagnostics({ events += it })) {
            verifyExistingConceptPage(concept, query, note, index, verifier, 7, mapOf("note" to "r1"),
                null, 1, store, { true }, { true }) { TopicSupportRun.Completed(SupportDecision.SUPPORTED) }
        }
        assertEquals(1L, events.sumOf { it["conceptScanEarlyStopped"] ?: 0 })
        assertEquals(1L, events.sumOf { it["supportCandidatesRetrieved"] ?: 0 })
        assertEquals(1L, events.sumOf { it["supportCandidatesChecked"] ?: 0 })
        assertEquals(1L, events.sumOf { it["supportFreshDecisions"] ?: 0 })
        assertEquals("The unchecked tail must not be reported as unsupported", 0L,
            events.sumOf { it["supportUnsupported"] ?: 0 })
    }

    @Test fun changedVerifierIdentityCannotReuseThePreviousScanDecision() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val note = rankedRecord(); index.write(note)
        val store = FileProposalProgressStore(root.resolve("progress")); var calls = 0
        suspend fun scan(model: TopicLabelIdentity) = verifyExistingConceptPage(concept, query, note, index, model, 7,
            mapOf("note" to "r1"), null, 1, store, { true }, { true }) {
            calls++; TopicSupportRun.Completed(SupportDecision.SUPPORTED)
        } as ExistingConceptEvidenceResult.Ready
        assertEquals(setOf("a"), scan(verifier).supported)
        assertEquals(setOf("a"), scan(verifier.copy(policy = "support-v2")).supported)
        assertEquals("A changed verifier identity requires a fresh decision", 2, calls)
    }
}
