package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test

class TopicSupportTest {
    private val verifier = TopicLabelIdentity("model", "tokenizer", "template", "prompt", "schema", "support-v1", "CPU")
    private fun request(page: String, text: String = "Moisture allows seeds to begin germination.") = TopicSupportRequest(
        "topic", "concept-v1", "Seed germination", "Conditions that allow seeds to germinate.",
        TopicLabelEvidence("$page:p", page, "r1", text), verifier, 7)
    private val sources = mapOf("positive" to "r1", "negative" to "r1", "uncertain" to "r1")
    private fun assess(request: TopicSupportRequest, decision: SupportDecision): TopicSupportAssessment =
        (validateTopicSupport(request, decision, 7, "concept-v1", sources) as TopicSupportValidation.Accepted).assessment

    @Test fun similarityCandidatesWithoutSupportCannotBecomeMembers() {
        val result = aggregateTopicSupport(listOf(request("positive")), emptyList(), 7, "concept-v1", sources, true)
        assertTrue(result.supported.isEmpty()); assertFalse(result.complete)
    }

    @Test fun onlySupportedPassagesCountWhileUncertaintyRemainsIncomplete() {
        val positive = request("positive")
        val negative = request("negative", "Water does not cause seeds to germinate in these conditions.")
        val uncertain = request("uncertain", "Seeds are stored in sealed containers.")
        val decisions = listOf(assess(positive, SupportDecision.SUPPORTED), assess(negative, SupportDecision.UNSUPPORTED),
            assess(uncertain, SupportDecision.UNCERTAIN))
        val result = aggregateTopicSupport(listOf(positive, negative, uncertain), decisions, 7, "concept-v1", sources, true)
        assertEquals(listOf(ProposalEvidenceRef("positive", "r1", "positive:p")), result.supported)
        assertFalse(result.complete)
    }

    @Test fun allUnsupportedCandidatesYieldAnHonestCompleteEmptyResult() {
        val negative = request("negative")
        val result = aggregateTopicSupport(listOf(negative), listOf(assess(negative, SupportDecision.UNSUPPORTED)),
            7, "concept-v1", sources, true)
        assertTrue(result.supported.isEmpty()); assertTrue(result.complete)
        assertFalse(aggregateTopicSupport(listOf(negative), listOf(assess(negative, SupportDecision.UNSUPPORTED)),
            7, "concept-v1", sources, false).complete)
    }

    @Test fun changedConceptSourceAndEpochRejectOldAssessments() {
        val positive = request("positive")
        assertEquals(TopicSupportValidation.Stale, validateTopicSupport(positive, SupportDecision.SUPPORTED, 8, "concept-v1", sources))
        assertEquals(TopicSupportValidation.Stale, validateTopicSupport(positive, SupportDecision.SUPPORTED, 7, "concept-v2", sources))
        assertEquals(TopicSupportValidation.Stale, validateTopicSupport(positive, SupportDecision.SUPPORTED, 7, "concept-v1", sources - "positive"))
        val result = aggregateTopicSupport(listOf(positive), listOf(assess(positive, SupportDecision.SUPPORTED)),
            7, "concept-v1", sources + ("positive" to "r2"), true)
        assertTrue(result.supported.isEmpty()); assertFalse(result.complete)
    }

    @Test fun changedBodyOrVerifierCannotReuseAnOldSupportDecision() {
        val original = request("positive")
        val saved = assess(original, SupportDecision.SUPPORTED)
        for (changed in listOf(original.copy(evidence = original.evidence.copy(body = "Seeds do not germinate without water.")),
            original.copy(verifier = verifier.copy(policy = "support-v2")))) {
            val result = aggregateTopicSupport(listOf(changed), listOf(saved), 7, "concept-v1", sources, true)
            assertTrue(result.supported.isEmpty()); assertFalse(result.complete)
        }
    }

    @Test fun emptyRetrievalCanBeCompleteWithoutInventingSeedMembership() {
        assertEquals(TopicSupportCoverage(emptyList(), true),
            aggregateTopicSupport(emptyList(), emptyList(), 7, "concept-v1", sources, true))
        assertFalse(aggregateTopicSupport(emptyList(), emptyList(), 7, "concept-v1", sources, false).complete)
    }

    @Test fun conflictingDuplicateAssessmentsCannotProduceSupport() {
        val positive = request("positive")
        val supported = assess(positive, SupportDecision.SUPPORTED)
        val unsupported = assess(positive, SupportDecision.UNSUPPORTED)
        val result = aggregateTopicSupport(listOf(positive), listOf(supported, unsupported), 7, "concept-v1", sources, true)
        assertTrue(result.supported.isEmpty()); assertFalse(result.complete)
    }
}
