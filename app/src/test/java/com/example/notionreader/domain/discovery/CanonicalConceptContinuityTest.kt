package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test

class CanonicalConceptContinuityTest {
    @Test fun compatibleRegroupingRetainsConceptOnlyForTheSameEvidenceAndIdentity() {
        val text = "Seeds need moisture to germinate and grow."
        val passage = SemanticPassage("p", "note", "section", "Seeds", "block", "chunk", 0, text.length, text, text, 0)
        val member = TopicMembership("note", SemanticMetadata("Seeds"), passage, 1f, passage, "r1")
        val key = semanticHash("concept")
        val generator = TopicLabelIdentity("g", "t", "template", "proposal", "schema", "v1", "CPU")
        val verifier = generator.copy(prompt = "support")
        val ref = ProposalEvidenceRef("note", "r1", "p")
        val assessment = TopicSupportAssessment("pending:$key", key, ref, verifier, semanticHash("support"), SupportDecision.SUPPORTED)
        val concept = CanonicalConceptEvidence(key, "Seeds", "Seeds need moisture.", "Listen about seeds.",
            generator, verifier, listOf(ref), listOf(assessment))
        val topic = DiscoveredTopic("topic", "Seeds", passage, EmbeddedPassage(passage, listOf(1f, 0f)),
            listOf(member), 1, canonicalConcept = concept)
        val identity = EmbeddingIdentity("model", 2)
        val previous = TopicSnapshot(topics = listOf(topic), identity = identity, acceptedRevisions = mapOf("note" to "r1"))
        val regrouped = previous.copy(topics = listOf(topic.copy(canonicalConcept = null)))
        assertEquals(concept, retainCanonicalConcepts(previous, regrouped).topics.single().canonicalConcept)
        assertNull(retainCanonicalConcepts(previous, regrouped.copy(acceptedRevisions = mapOf("note" to "r2"))).topics.single().canonicalConcept)
        assertNull(retainCanonicalConcepts(previous, regrouped.copy(identity = EmbeddingIdentity("other", 2))).topics.single().canonicalConcept)
        assertNull(retainCanonicalConcepts(previous, regrouped.copy(topics = listOf(topic.copy(id = "different", canonicalConcept = null)))).topics.single().canonicalConcept)
        val changed = passage.copy(id = "different")
        val changedTopic = topic.copy(labelSource = changed, anchor = EmbeddedPassage(changed, listOf(1f, 0f)),
            members = listOf(member.copy(passage = changed, anchor = changed)), canonicalConcept = null)
        assertNull(retainCanonicalConcepts(previous, regrouped.copy(topics = listOf(changedTopic))).topics.single().canonicalConcept)
    }
}
