package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Proposal-block release depends on the catalog identity that actually drives existing-concept
 * eligibility and duplicate/continuity admission. Unrelated generation/connection progress must
 * not change it.
 */
class EvidenceCatalogIdentityTest {
    private val model = EmbeddingIdentity("model", 2)
    private val verifier = TopicLabelIdentity("a", "t", "template", "support", "schema", "v1", "CPU")
    private val key = semanticHash("concept")

    private fun fixture(): TopicSnapshot {
        val text = "Moisture and warmth help seeds germinate."
        val passage = SemanticPassage("seed:p", "seed", "seed:s", "Seeds", "seed:b", "seed:c", 0,
            text.length, text, text, 0)
        val query = TopicQueryEmbedding(TopicRetrievalQuery("topic", key, "Seeds\nSeeds need moisture.",
            model, TopicRetrievalPolicy(.8f)), listOf(1f, 0f))
        val concept = CanonicalConceptEvidence(key, "Seeds", "Seeds need moisture.", "Listen about seeds.",
            verifier, verifier, emptyList(), emptyList(), scannedRevisions = mapOf("seed" to "r1"),
            matchingComplete = true, queryEmbedding = query)
        val member = TopicMembership("seed", SemanticMetadata("Seeds"), passage, 1f, passage, "r1")
        val topic = DiscoveredTopic("topic", "Seeds", passage, EmbeddedPassage(passage, listOf(1f, 0f)),
            listOf(member), 1, canonicalConcept = concept,
            canonicalProjection = CanonicalTopicProjection(key, "Seeds", "Seeds need moisture.",
                "Listen about seeds.", emptyList(), null))
        return TopicSnapshot(topics = listOf(topic), identity = model, canonicalCatalogReady = true,
            generation = 3, acceptedRevisions = mapOf("seed" to "r1"),
            continuity = listOf(TopicContinuity("continuity", 1, setOf("seed"), setOf("anchor"),
                canonicalKey = semanticHash("continuity"))))
    }

    @Test fun proposalCatalogIdentityTracksEligibilityAndDuplicateDependencies() {
        val base = fixture()
        val identity = evidenceCatalogIdentity(base)
        fun changed(topic: com.example.notionreader.domain.discovery.DiscoveredTopic): String =
            evidenceCatalogIdentity(base.copy(topics = listOf(topic)))
        val topic = base.topics.single()
        val concept = topic.canonicalConcept!!
        assertNotEquals("Canonical readiness gates admission eligibility",
            identity, evidenceCatalogIdentity(base.copy(canonicalCatalogReady = false)))
        assertNotEquals("The existing-concept query drives evidence eligibility",
            identity, changed(topic.copy(canonicalConcept = concept.copy(
                queryEmbedding = concept.queryEmbedding!!.copy(vector = listOf(0f, 1f))))))
        assertNotEquals("The verifier identity drives independent eligibility",
            identity, changed(topic.copy(canonicalConcept = concept.copy(verifier = verifier.copy(policy = "v2")))))
        assertNotEquals("Concept wording drives duplicate/continuity admission",
            identity, changed(topic.copy(canonicalConcept = concept.copy(name = "Seed sprouting"))))
        assertNotEquals("Concept definition drives duplicate/continuity admission",
            identity, changed(topic.copy(canonicalConcept = concept.copy(definition = "Different definition."))))
        assertNotEquals("Seed members are part of the existing-concept identity",
            identity, changed(topic.copy(members = topic.members.map { it.copy(revision = "r2") })))
        val continuity = base.continuity.single()
        assertNotEquals("Continuity replacement affects duplicate admission",
            identity, evidenceCatalogIdentity(base.copy(continuity = listOf(continuity.copy(replacementId = "replacement")))))
        assertNotEquals("Continuity evidence affects duplicate admission",
            identity, evidenceCatalogIdentity(base.copy(continuity = listOf(continuity.copy(pageIds = setOf("other"))))))
        // A connection-only generation bump must not release a proposal block.
        assertEquals("Unrelated generation progress is not a proposal dependency",
            identity, evidenceCatalogIdentity(base.copy(generation = base.generation + 1)))
    }

    @Test fun admissionUsesRelevantCatalogIdentityNotTheInMemoryTicket() {
        val base = fixture()
        fun ticket(snapshot: TopicSnapshot, epoch: Long = 1, cache: Long = 1, query: String = "q") =
            TopicCatalogTicket(snapshot, epoch, cache, model, query)
        val original = ticket(base)
        assertTrue("An epoch/generation/query-ticket restore does not change admission semantics",
            catalogAdmissionCurrent(original, ticket(base.copy(generation = base.generation + 1), epoch = 2, query = "q2")))
        assertFalse(catalogAdmissionCurrent(original, original.copy(cacheRevision = 2)))
        assertFalse(catalogAdmissionCurrent(original, original.copy(identity = EmbeddingIdentity("other", 2))))
        val topic = base.topics.single(); val concept = topic.canonicalConcept!!
        val changed = topic.copy(canonicalConcept = concept.copy(
            queryEmbedding = concept.queryEmbedding!!.copy(vector = listOf(0f, 1f))))
        assertFalse(catalogAdmissionCurrent(original, ticket(base.copy(topics = listOf(changed)))))
    }
}