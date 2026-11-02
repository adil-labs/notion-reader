package com.example.notionreader.application

import com.example.notionreader.data.local.FileProposalProgressStore
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CanonicalTopicHydrationTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun restorationRequiresCurrentSourceAndIndependentlySavedSupport() = runBlocking {
        val root = files.newFolder()
        val index = FileSemanticIndexStore(root.resolve("index"))
        val store = FileProposalProgressStore(root.resolve("progress"))
        val model = EmbeddingIdentity("model", 2)
        val generator = TopicLabelIdentity("g", "t", "template", "proposal", "schema", "v1", "CPU")
        val verifier = generator.copy(prompt = "support")
        val text = "Moisture and warmth help seeds to germinate and begin growing."
        val passage = SemanticPassage("p", "note", "section", "Seeds", "block", "chunk", 0, text.length, text, text, 0)
        val embedded = EmbeddedPassage(passage, listOf(1f, 0f))
        val record = SemanticRecord("note", "r1", model, SemanticMetadata("Seeds"), listOf(embedded))
        assertTrue(index.write(record))
        val evidence = TopicLabelEvidence("p", "note", "r1", text)
        val proposalKey = semanticHash("proposal")
        val request = DiscoveryProposalRequest(proposalKey, 0, listOf(evidence), generator)
        val response = DiscoveryProposalResponse.Concept("Seed germination", "Moisture helps seeds germinate.",
            "Listen to seed germination.", listOf("p"))
        val key = (validateDiscoveryProposal(request, response, 0, mapOf("note" to "r1")) as DiscoveryProposalValidation.Accepted).workKey
        val support = TopicSupportRequest("pending:$key", key, response.name, response.definition, evidence, verifier, 0)
        val assessment = (validateTopicSupport(support, SupportDecision.SUPPORTED, 0, key, mapOf("note" to "r1")) as TopicSupportValidation.Accepted).assessment
        assertEquals(ProposalProgressWriteResult.Written, store.writeSupport(assessment) { true })
        val ref = ProposalEvidenceRef("note", "r1", "p")
        val concept = CanonicalConceptEvidence(key, response.name, response.definition, response.listeningDescription,
            generator, verifier, listOf(ref), listOf(assessment), listOf(ref), mapOf("note" to "r1"), true, proposalKey)
        val member = TopicMembership("note", record.metadata, passage, 1f, passage, "r1")
        val topic = DiscoveredTopic("canonical", "Seeds", passage, embedded, listOf(member), 1, canonicalConcept = concept)
        val restored = hydrateCanonicalTopic(topic, model, mapOf("note" to "r1"), index, store) { true }!!
        assertEquals("Seed germination", restored.effectiveLabel)
        assertEquals(TopicPublicationStatus.Emerging, discoveryTopicPublicationStatus(restored))
        assertEquals(listOf("note"), restored.matchingMembers.map { it.pageId })
        assertNull(hydrateCanonicalTopic(topic, model, mapOf("note" to "r2"), index, store) { true })
        store.clear()
        assertNull(hydrateCanonicalTopic(topic, model, mapOf("note" to "r1"), index, store) { true })
    }
}
