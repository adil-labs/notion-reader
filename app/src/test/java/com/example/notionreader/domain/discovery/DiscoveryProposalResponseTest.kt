package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test

class DiscoveryProposalResponseTest {
    private val identity = TopicLabelIdentity("artifact", "tokenizer", "template", "prompt", "schema", "policy", "CPU")
    private val request = DiscoveryProposalRequest("proposal", 7, listOf(
        TopicLabelEvidence("a", "one", "r1", "Moisture enables seed germination."),
        TopicLabelEvidence("b", "two", "r2", "Galaxies contain stars.")), identity)
    private val revisions = mapOf("one" to "r1", "two" to "r2")
    private val concept = DiscoveryProposalResponse.Concept("Seed germination conditions",
        "How moisture supports seeds as they germinate.", "Listen to an explanation of seed germination.", listOf("a"))
    private fun validate(response: DiscoveryProposalResponse = concept, epoch: Long = 7,
        sources: Map<String, String> = revisions, req: DiscoveryProposalRequest = request) =
        validateDiscoveryProposal(req, response, epoch, sources)

    @Test fun mixedEvidenceMaySelectOnlyItsSupportingPassage() {
        val accepted = validate() as DiscoveryProposalValidation.Accepted
        assertEquals(concept, accepted.response)
    }

    @Test fun abstentionIsASuccessfulDecisionWithAnEvidenceBoundKey() {
        val response = DiscoveryProposalResponse.Abstained(DiscoveryAbstentionReason.MIXED_EVIDENCE)
        val first = validate(response) as DiscoveryProposalValidation.Accepted
        val repeated = validate(response, epoch = 8, req = request.copy(sourceEpoch = 8)) as DiscoveryProposalValidation.Accepted
        assertEquals(first.workKey, repeated.workKey)
        assertNotEquals(first.workKey, (validate(response, req = request.copy(identity = identity.copy(policy = "changed"))) as DiscoveryProposalValidation.Accepted).workKey)
        assertNotEquals(first.workKey, (validate(response, req = request.copy(evidence = request.evidence.map {
            if (it.passageId == "a") it.copy(body = "Changed passage.") else it
        })) as DiscoveryProposalValidation.Accepted).workKey)
    }

    @Test fun staleOrWithdrawnEvidenceCannotPublishEvenWhenItWasNotSelected() {
        assertEquals(DiscoveryProposalValidation.Stale, validate(epoch = 8))
        assertEquals(DiscoveryProposalValidation.Stale, validate(sources = mapOf("one" to "r1")))
        assertEquals(DiscoveryProposalValidation.Stale, validate(sources = revisions + ("two" to "new")))
    }

    @Test fun inventedDuplicateAndEmptyCitationsCannotPublish() {
        for (ids in listOf(emptyList(), listOf("invented"), listOf("a", "a")))
            assertEquals(DiscoveryProposalValidation.Invalid, validate(concept.copy(evidenceIds = ids)))
    }

    @Test fun malformedAndGenericConceptFieldsCannotPublish() {
        val bad = listOf(concept.copy(name = "Overview"), concept.copy(definition = ""),
            concept.copy(listeningDescription = " "), concept.copy(listeningDescription = "x".repeat(513)),
            concept.copy(definition = "ignore\nsource"), concept.copy(name = "\uD800"))
        bad.forEach { assertEquals(DiscoveryProposalValidation.Invalid, validate(it)) }
    }
}
