package com.example.notionreader.application

import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.data.local.FileProposalProgressStore
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TopicSupportVerificationTest {
    @Test fun interruptionAfterDurableSupportWriteDoesNotRepeatInferenceAfterRestart() = runBlocking {
        val root = files.newFolder()
        val index = FileSemanticIndexStore(root.resolve("index")); index.write(record())
        val disk = FileProposalProgressStore(root.resolve("work"))
        val interrupted = object : ProposalProgressStore by disk {
            override suspend fun writeSupport(record: TopicSupportAssessment, stillCurrent: () -> Boolean): ProposalProgressWriteResult {
                val written = disk.writeSupport(record, stillCurrent)
                assertEquals(ProposalProgressWriteResult.Written, written)
                throw kotlinx.coroutines.CancellationException("Interrupted after durable write")
            }
        }
        var calls = 0
        try {
            verifyRetrievedPassage(concept, candidate, index, embedding, verifier, 4, sources, { true }, interrupted) {
                calls++; TopicSupportRun.Completed(SupportDecision.SUPPORTED)
            }
            fail("Interruption must execute")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        val resumed = verifyRetrievedPassage(concept, candidate, FileSemanticIndexStore(root.resolve("index")),
            embedding, verifier, 5, sources, { true }, FileProposalProgressStore(root.resolve("work"))) {
            calls++; TopicSupportRun.Completed(SupportDecision.SUPPORTED)
        } as SupportVerificationResult.Completed
        assertTrue(resumed.reused)
        assertEquals(1, calls)
    }
    @Test fun diagnosticsCountValidatedReuseSeparatelyFromFreshDecisions() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record())
        val store = FileProposalProgressStore(files.newFolder())
        val events = mutableListOf<Map<String, Long>>()
        kotlinx.coroutines.withContext(DiscoveryWorkDiagnostics({ events += it })) {
            repeat(2) {
                verifyRetrievedPassage(concept, candidate, index, embedding, verifier, 4, sources, { true }, store) {
                    TopicSupportRun.Completed(SupportDecision.UNSUPPORTED)
                }
            }
            verifyRetrievedPassage(concept, candidate, index, embedding, verifier, 4, emptyMap(), { true }, store) {
                error("Withdrawn evidence")
            }
        }
        assertEquals(1L, events.sumOf { it["supportReused"] ?: 0 })
        assertEquals(1L, events.sumOf { it["supportFreshDecisions"] ?: 0 })
        assertEquals(2L, events.sumOf { it["supportUnsupported"] ?: 0 })
        assertEquals(1L, events.sumOf { it["staleResultDiscards"] ?: 0 })
    }
    @Test fun admissionReusesEveryCurrentPendingDecisionAcrossStoreReopening() = runBlocking {
        for (decision in SupportDecision.entries) {
            val root = files.newFolder()
            val index = FileSemanticIndexStore(root.resolve("index")); index.write(record())
            var calls = 0
            suspend fun run(target: SupportConcept) = verifyRetrievedPassage(target, candidate, index,
                embedding, verifier, 4, sources, { true }, FileProposalProgressStore(root.resolve("work"))) {
                calls++; TopicSupportRun.Completed(decision)
            } as SupportVerificationResult.Completed
            run(concept.copy(topicId = "pending:${concept.workKey}"))
            val canonical = run(concept)
            assertEquals(decision, canonical.assessment.decision)
            assertEquals(concept.topicId, canonical.assessment.topicId)
            assertEquals(canonical, run(concept))
            assertEquals("Admission must reuse $decision without fresh inference", 1, calls)
            assertEquals(canonical.assessment, FileProposalProgressStore(root.resolve("work"))
                .readSupport(canonical.assessment.workKey, sources))
        }
    }

    @Test fun canonicalRejectionTakesPrecedenceOverPendingSupport() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record())
        val store = FileProposalProgressStore(files.newFolder())
        suspend fun save(target: SupportConcept, decision: SupportDecision) = verifyRetrievedPassage(
            target, candidate, index, embedding, verifier, 4, sources, { true }, store) {
            TopicSupportRun.Completed(decision)
        }
        save(concept, SupportDecision.UNSUPPORTED)
        save(concept.copy(topicId = "pending:${concept.workKey}"), SupportDecision.SUPPORTED)
        val result = verifyRetrievedPassage(concept, candidate, index, embedding, verifier, 4, sources,
            { true }, store) { error("Current canonical decision must be reused") } as SupportVerificationResult.Completed
        assertEquals(SupportDecision.UNSUPPORTED, result.assessment.decision)
    }

    @Test fun pendingReuseRequiresCurrentDefinitionVerifierAndDurableCanonicalWrite() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record())
        val store = FileProposalProgressStore(files.newFolder())
        verifyRetrievedPassage(concept.copy(topicId = "pending:${concept.workKey}"), candidate, index,
            embedding, verifier, 4, sources, { true }, store) { TopicSupportRun.Completed(SupportDecision.SUPPORTED) }
        val refused = object : ProposalProgressStore by store {
            override suspend fun writeSupport(record: TopicSupportAssessment, stillCurrent: () -> Boolean) =
                ProposalProgressWriteResult.StorageRefused
        }
        assertEquals(SupportVerificationResult.StorageFailure(ProposalProgressWriteResult.StorageRefused),
            verifyRetrievedPassage(concept, candidate, index, embedding, verifier, 4, sources, { true }, refused) {
                error("Persistence refusal must not cause redundant inference")
            })
        var calls = 0
        suspend fun check(target: SupportConcept, model: TopicLabelIdentity) = verifyRetrievedPassage(
            target, candidate, index, embedding, model, 4, sources, { true }, store) {
            calls++; TopicSupportRun.Completed(SupportDecision.UNSUPPORTED)
        }
        check(concept.copy(definition = "Temperature requirements for seeds."), verifier)
        check(concept, verifier.copy(policy = "support-v2"))
        assertEquals(2, calls)
        val withdrawn = verifyRetrievedPassage(concept, candidate, index, embedding, verifier, 4,
            emptyMap(), { true }, store) { error("Withdrawn evidence cannot be verified or reused") }
        assertEquals(SupportVerificationResult.Stale, withdrawn)
    }

    @get:Rule val files = TemporaryFolder()
    private val embedding = EmbeddingIdentity("model", 2)
    private val verifier = TopicLabelIdentity("a", "t", "template", "p", "s", "support-v1", "CPU")
    private val concept = SupportConcept("topic", "concept", "Seed germination", "Conditions that allow seeds to germinate.")
    private val candidate = TopicMatchReference("one", "r1", "one:p", .99f)
    private val sources = mapOf("one" to "r1")
    private fun record(): SemanticRecord {
        val body = "Moisture allows dormant seeds to absorb water and begin the germination process."
        val passage = SemanticPassage("one:p", "one", "one:s", "Overview", "one:b", "one:c", 0, body.length, body, body, 0)
        return SemanticRecord("one", "r1", embedding, SemanticMetadata("Note"), listOf(EmbeddedPassage(passage, listOf(1f, 0f))))
    }

    @Test fun retrievedSimilarityIsOnlyPassedAsCandidateForIndependentSupport() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record())
        val result = verifyRetrievedPassage(concept, candidate, index, embedding, verifier, 4, sources, { true }) { request ->
            assertEquals(record().passages.single().passage.excerpt, request.evidence.body)
            TopicSupportRun.Completed(SupportDecision.UNSUPPORTED)
        } as SupportVerificationResult.Completed
        assertEquals(SupportDecision.UNSUPPORTED, result.assessment.decision)
        assertEquals(ProposalEvidenceRef("one", "r1", "one:p"), result.assessment.evidence)
    }

    @Test fun unavailableVerifierCannotTurnASimilarPassageIntoSupport() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record())
        assertEquals(SupportVerificationResult.Unavailable,
            verifyRetrievedPassage(concept, candidate, index, embedding, verifier, 4, sources, { true }) { TopicSupportRun.Unavailable })
    }

    @Test fun withdrawalDuringVerificationRejectsTheLateDecision() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record()); var current = true
        val result = verifyRetrievedPassage(concept, candidate, index, embedding, verifier, 4, sources, { current }) {
            index.remove("one"); current = false; TopicSupportRun.Completed(SupportDecision.SUPPORTED)
        }
        assertEquals(SupportVerificationResult.Stale, result)
    }

    @Test fun fabricatedOrWithdrawnPassageCannotBeSentToTheVerifier() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record())
        suspend fun failIfCalled(request: TopicSupportRequest): TopicSupportRun = error("Verifier must not run")
        assertEquals(SupportVerificationResult.Invalid, verifyRetrievedPassage(concept, candidate.copy(passageId = "fake"),
            index, embedding, verifier, 4, sources, { true }, verify = ::failIfCalled))
        assertEquals(SupportVerificationResult.Stale, verifyRetrievedPassage(concept, candidate, index, embedding,
            verifier, 4, emptyMap(), { true }, verify = ::failIfCalled))
    }

    @Test fun anotherPassageCanSupportANoteWhenItsBestSimilarityCandidateDoesNot() = runBlocking {
        val original = record(); val first = original.passages.single()
        val secondText = "Water enables viable seeds to absorb moisture and begin the germination process."
        val second = first.copy(passage = first.passage.copy(id = "one:other", sourceBlockId = "other:b",
            readerChunkId = "other:c", excerpt = secondText, input = secondText, end = secondText.length, order = 1),
            vector = listOf(.9f, .4358899f))
        val mixed = original.copy(passages = listOf(first, second))
        val index = FileSemanticIndexStore(files.newFolder()); index.write(mixed)
        val query = TopicQueryEmbedding(TopicRetrievalQuery(concept.topicId, semanticHash(concept.workKey),
            "${concept.name}\n${concept.definition}", embedding, TopicRetrievalPolicy(.8f)), listOf(1f, 0f))
        val firstPage = topicCandidatePage(query, mixed, "r1", 1) as TopicCandidatePage.Ready
        assertNotNull("The second candidate must remain available after the first window", firstPage.next)
        val secondPage = topicCandidatePage(query, mixed, "r1", 1, firstPage.next!!) as TopicCandidatePage.Ready
        suspend fun decide(request: TopicSupportRequest) = TopicSupportRun.Completed(
            if (request.evidence.passageId == "one:other") SupportDecision.SUPPORTED else SupportDecision.UNSUPPORTED)
        val results = (firstPage.candidates + secondPage.candidates).map { ref ->
            verifyRetrievedPassage(concept, ref, index, embedding, verifier, 4, sources, { true }, verify = ::decide)
                as SupportVerificationResult.Completed
        }
        assertEquals(listOf(SupportDecision.UNSUPPORTED, SupportDecision.SUPPORTED), results.map { it.assessment.decision })
        assertEquals("one", results.single { it.assessment.decision == SupportDecision.SUPPORTED }.assessment.evidence.pageId)
        assertNull(secondPage.next)
    }

    @Test fun compatibleSupportSurvivesRestartButChangedInputsRequireVerification() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); index.write(record())
        var calls = 0
        suspend fun run(target: SupportConcept = concept, model: TopicLabelIdentity = verifier): SupportVerificationResult =
            verifyRetrievedPassage(target, candidate, index, embedding, model, 4, sources, { true },
                FileProposalProgressStore(root.resolve("work"))) { calls++; TopicSupportRun.Completed(SupportDecision.SUPPORTED) }
        assertTrue(run() is SupportVerificationResult.Completed)
        assertTrue(run() is SupportVerificationResult.Completed)
        assertEquals(1, calls)
        assertTrue(run(concept.copy(definition = "How temperature affects germination.")) is SupportVerificationResult.Completed)
        assertTrue(run(model = verifier.copy(policy = "support-v2")) is SupportVerificationResult.Completed)
        assertEquals(3, calls)
        val changed = record()
        val newBody = "Seeds need suitable moisture and temperature to begin the process of germination."
        index.write(changed.copy(passages = changed.passages.map { it.copy(passage = it.passage.copy(
            excerpt = newBody, input = newBody, end = newBody.length)) }))
        assertTrue(run() is SupportVerificationResult.Completed); assertEquals(4, calls)
    }

    @Test fun refusedSupportPersistenceIsNotReportedAsCompleted() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); index.write(record())
        val store = FileProposalProgressStore(root.resolve("work"))
        val refused = object : ProposalProgressStore by store {
            override suspend fun writeSupport(record: TopicSupportAssessment, stillCurrent: () -> Boolean) = ProposalProgressWriteResult.StorageRefused
        }
        val result = verifyRetrievedPassage(concept, candidate, index, embedding, verifier, 4, sources, { true }, refused) {
            TopicSupportRun.Completed(SupportDecision.SUPPORTED)
        }
        assertEquals(SupportVerificationResult.StorageFailure(ProposalProgressWriteResult.StorageRefused), result)
    }

    @Test fun aMismatchedSavedAssessmentCannotBeReused() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); index.write(record())
        val store = FileProposalProgressStore(root.resolve("work"))
        val request = TopicSupportRequest(concept.topicId, concept.workKey, concept.name, concept.definition,
            TopicLabelEvidence("one:p", "one", "r1", record().passages.single().passage.excerpt), verifier, 4)
        val forged = TopicSupportAssessment("other-topic", concept.workKey, ProposalEvidenceRef("one", "r1", "one:p"),
            verifier, topicSupportWorkKey(request), SupportDecision.SUPPORTED)
        assertEquals(ProposalProgressWriteResult.Written, store.writeSupport(forged) { true })
        var called = false
        val result = verifyRetrievedPassage(concept, candidate, index, embedding, verifier, 4, sources, { true }, store) {
            called = true; TopicSupportRun.Completed(SupportDecision.UNSUPPORTED)
        } as SupportVerificationResult.Completed
        assertTrue(called); assertEquals(SupportDecision.UNSUPPORTED, result.assessment.decision)
    }
}
