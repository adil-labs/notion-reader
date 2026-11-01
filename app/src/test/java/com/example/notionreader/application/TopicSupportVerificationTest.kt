package com.example.notionreader.application

import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TopicSupportVerificationTest {
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
            index, embedding, verifier, 4, sources, { true }, ::failIfCalled))
        assertEquals(SupportVerificationResult.Stale, verifyRetrievedPassage(concept, candidate, index, embedding,
            verifier, 4, emptyMap(), { true }, ::failIfCalled))
    }
}
