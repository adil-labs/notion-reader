package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test

/** Coverage facts are derived from checked Notes, never from the Notes that happen to support. */
class TopicCoverageTest {
    private val verifier = TopicLabelIdentity("a", "t", "template", "support", "schema", "v1", "CPU")
    private val key = semanticHash("concept")
    private val concept = CanonicalConceptEvidence(key, "Seed germination", "Moisture helps seeds germinate.",
        "Listen about seeds.", verifier, verifier, emptyList(), emptyList(), proposalKey = semanticHash("proposal"))

    private fun assessment(page: String, passage: String, decision: SupportDecision) =
        TopicSupportAssessment("topic", key, ProposalEvidenceRef(page, "r1", passage), verifier,
            semanticHash("support-$page-$passage-$decision"), decision)

    @Test fun checkingANonSupportingNoteStillCompletesMembershipCoverage() {
        val partiallyScanned = concept.copy(scannedRevisions = mapOf("a" to "r1", "b" to "r1"),
            supported = listOf(ProposalEvidenceRef("a", "r1", "pa")), matchingComplete = false)
        val sources = mapOf("a" to "r1", "b" to "r1", "c" to "r1")
        val updated = reconcileConceptCoverage(partiallyScanned, "c", "r1", sources, fullCoverage = true,
            assessments = emptyList(), earlyWorkKeys = emptySet())
        assertEquals("A checked Note that does not support the concept is not a member",
            listOf(ProposalEvidenceRef("a", "r1", "pa")), updated.supported)
        assertEquals("Every eligible Note reached a terminal assessment", true, updated.matchingComplete)
        assertTrue(updated.uncertainRevisions.isEmpty())
        assertTrue(updated.uncheckedRevisions.isEmpty())
    }

    @Test fun unresolvedEvidenceLeavesMembershipCoverageIncomplete() {
        val sources = mapOf("a" to "r1", "b" to "r1")
        val updated = reconcileConceptCoverage(concept.copy(scannedRevisions = mapOf("a" to "r1")), "b", "r1",
            sources, fullCoverage = true, assessments = listOf(assessment("b", "pb", SupportDecision.UNCERTAIN)),
            earlyWorkKeys = emptySet())
        assertEquals(mapOf("b" to "r1"), updated.uncertainRevisions)
        assertFalse("Uncertain is a terminal scan but not complete membership coverage", updated.matchingComplete)
    }

    @Test fun verifiedMemberWithAnUncheckedTailKeepsPassageCoverageIncomplete() {
        val sources = mapOf("a" to "r1", "b" to "r1")
        val updated = reconcileConceptCoverage(concept.copy(scannedRevisions = mapOf("a" to "r1")), "b", "r1",
            sources, fullCoverage = true, assessments = listOf(assessment("b", "pb", SupportDecision.SUPPORTED)),
            earlyWorkKeys = setOf(key))
        assertTrue(updated.supported.any { it.pageId == "b" })
        assertEquals(mapOf("b" to "r1"), updated.uncheckedRevisions)
        assertTrue("Verified membership with an unchecked tail is still complete membership coverage",
            updated.matchingComplete)
        assertFalse(concept.copy(uncheckedRevisions = updated.uncheckedRevisions,
            matchingComplete = updated.matchingComplete).passageCoverageComplete)
    }

    @Test fun supportAndUncertaintyForOneNoteAreBothRetained() {
        val sources = mapOf("a" to "r1")
        val updated = reconcileConceptCoverage(concept, "a", "r1", sources, fullCoverage = true,
            assessments = listOf(assessment("a", "pa", SupportDecision.SUPPORTED),
                assessment("a", "pb", SupportDecision.UNCERTAIN)), earlyWorkKeys = emptySet())
        assertEquals(listOf(ProposalEvidenceRef("a", "r1", "pa")), updated.supported)
        assertEquals(mapOf("a" to "r1"), updated.uncertainRevisions)
        assertFalse("Retaining a contradiction keeps coverage honest", updated.matchingComplete)
    }

    @Test fun incompleteSourceCoverageCannotReportComplete() {
        val sources = mapOf("a" to "r1")
        val updated = reconcileConceptCoverage(concept, "a", "r1", sources, fullCoverage = false,
            assessments = listOf(assessment("a", "pa", SupportDecision.SUPPORTED)), earlyWorkKeys = emptySet())
        assertFalse("An unindexed cached Note prevents complete coverage", updated.matchingComplete)
    }

    @Test fun changedRevisionWithdrawsItsStaleSupport() {
        val sources = mapOf("a" to "r2")
        val scanned = concept.copy(scannedRevisions = mapOf("a" to "r1"),
            supported = listOf(ProposalEvidenceRef("a", "r1", "pa")), matchingComplete = true)
        val updated = reconcileConceptCoverage(scanned, "a", "r2", sources, fullCoverage = true,
            assessments = emptyList(), earlyWorkKeys = emptySet())
        assertEquals("A changed source revision cannot retain its old support", emptyList<ProposalEvidenceRef>(), updated.supported)
        assertEquals(mapOf("a" to "r2"), updated.scannedRevisions)
        assertTrue(updated.matchingComplete)
    }
}
