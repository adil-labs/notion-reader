package com.example.notionreader.application

import com.example.notionreader.data.local.FileProposalProgressStore
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingConceptCoverageTest {
    @get:Rule val files = TemporaryFolder()
    private val embedding = EmbeddingIdentity("model", 2)
    private val generator = TopicLabelIdentity("a", "t", "template", "proposal", "schema", "v1", "CPU")
    private val verifier = generator.copy(prompt = "support", policy = "support-v1")
    private val sources = mapOf("one" to "r1", "two" to "r1")
    private fun record(id: String): SemanticRecord {
        val text = "Moisture and suitable temperature allow seeds to germinate and begin sprouting."
        val p = SemanticPassage("$id:p", id, "$id:s", "Overview", "$id:b", "$id:c", 0, text.length, text, text, 0)
        return SemanticRecord(id, "r1", embedding, SemanticMetadata(id), listOf(EmbeddedPassage(p, listOf(1f, 0f))))
    }
    private val proposal = IncrementalProposal("proposal", listOf(ProposalEvidenceRef("one", "r1", "one:p"),
        ProposalEvidenceRef("two", "r1", "two:p")), setOf(ProposalReason.CHANGED_BODY), emptySet())
    @Test fun allSelectedCitationsRequireCurrentIndependentDecisions() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); index.write(record("one")); index.write(record("two"))
        val store = FileProposalProgressStore(root.resolve("progress"))
        val prepared = prepareProposalEvidence(proposal, index, embedding, generator, 5, sources) { true } as ProposalEvidenceResult.Ready
        val response = DiscoveryProposalResponse.Concept("Seed germination conditions", "Conditions for seed germination.",
            "Listen to seed germination conditions.", listOf("one:p", "two:p"))
        val accepted = validateDiscoveryProposal(prepared.request, response, 5, sources) as DiscoveryProposalValidation.Accepted
        val generated = ProposalGenerationResult.Completed(response, accepted.workKey, emptySet())
        fun assessment(e: TopicLabelEvidence, decision: SupportDecision): TopicSupportAssessment {
            val request = TopicSupportRequest("pending:${accepted.workKey}", accepted.workKey, response.name, response.definition, e, verifier, 5)
            return (validateTopicSupport(request, decision, 5, accepted.workKey, sources) as TopicSupportValidation.Accepted).assessment
        }
        val one = assessment(prepared.request.evidence[0], SupportDecision.SUPPORTED)
        val two = assessment(prepared.request.evidence[1], SupportDecision.UNSUPPORTED)
        assertEquals(ProposalProgressWriteResult.Written, store.writeSupport(one) { true })
        suspend fun load() = readPendingConceptCoverage(proposal, generated, index, embedding, generator, verifier, 5, sources, store) { true }
        assertEquals(PendingConceptCoverage.Pending(setOf("two:p")), load())
        assertEquals(ProposalProgressWriteResult.Written, store.writeSupport(two) { true })
        assertEquals(PendingConceptCoverage.Complete(listOf(one, two)), load())
        val forged = object : ProposalProgressStore by store {
            override suspend fun readSupport(workKey: String, sources: Map<String, String>): TopicSupportAssessment? =
                store.readSupport(workKey, sources)?.copy(topicId = "unrelated")
        }
        assertEquals(PendingConceptCoverage.Invalid, readPendingConceptCoverage(proposal, generated, index, embedding,
            generator, verifier, 5, sources, forged) { true })
        var current = true
        val interrupted = object : ProposalProgressStore by store {
            override suspend fun readSupport(workKey: String, sources: Map<String, String>): TopicSupportAssessment? =
                store.readSupport(workKey, sources).also { current = false }
        }
        assertEquals(PendingConceptCoverage.Stale, readPendingConceptCoverage(proposal, generated, index, embedding,
            generator, verifier, 5, sources, interrupted) { current })
        val changedVerifier = verifier.copy(policy = "new")
        assertEquals(PendingConceptCoverage.Pending(setOf("one:p", "two:p")), readPendingConceptCoverage(proposal, generated,
            index, embedding, generator, changedVerifier, 5, sources, store) { true })
        assertEquals(PendingConceptCoverage.Stale, readPendingConceptCoverage(proposal, generated,
            index, embedding, generator, verifier, 5, sources - "two", store) { true })
    }
}
