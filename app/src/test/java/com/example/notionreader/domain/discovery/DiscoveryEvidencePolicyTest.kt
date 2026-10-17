package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test

class DiscoveryEvidencePolicyTest {
    private val identity = EmbeddingIdentity("evidence-policy", 2)
    private fun passage(text: String, heading: String? = null, id: String = "a") =
        SemanticPassage("$id:p", id, "$id:s", heading, "$id:b", "$id:c", 0, text.length, text, text, 0)
    private fun records(texts: List<String>, heading: String? = null) = texts.mapIndexed { i, text ->
        val id = "n$i"
        SemanticRecord(id, "r", identity, SemanticMetadata("Note $i"),
            listOf(EmbeddedPassage(passage(text, heading, id), listOf(1f, 0f))))
    }
    private fun build(records: List<SemanticRecord>) = SectionHierarchicalEngine(identity).discover(records, null, TopicPolicy())

    @Test fun explicitReferenceListsDoNotEstablishTopicOrConnectionEvidence() {
        assertFalse(passage("Related: Shared Meaning; Cultural Identity.").isBodyEvidence())
        assertFalse(passage("Shared Meaning, Culture and Identity", "Related knowledge").isBodyEvidence())
        assertFalse(passage("[Another Note](https://example.com/note)").isBodyEvidence())
        assertFalse(passage("https://example.com/note").isBodyEvidence())
    }
    @Test fun specificationRoutingIsNotEvidenceOfAConceptualConnection() {
        assertFalse(passage("Exact navigation behavior is defined by the separate UI Screens specification.").isBodyEvidence())
        assertFalse(passage("The future Active Queue specification owns:").isBodyEvidence())
    }
    @Test fun substantiveDiscussionIsPreservedEvenUnderAReferenceHeading() {
        assertTrue(passage("Forest roots absorb water and release it gradually. This process slows runoff after heavy rainfall and protects the soil from erosion.", "Related knowledge").isBodyEvidence())
        assertTrue(passage("Related concepts have practical consequences for how we understand culture.").isBodyEvidence())
        assertTrue(passage("Queue membership remains frozen after playback starts.").isBodyEvidence())
    }
    @Test fun referencesCannotManufactureAGroupEvenWithIdenticalVectors() {
        val snapshot = build(records(List(3) { "Related: Forest ecology; Water cycles." }))
        assertTrue(snapshot.topics.isEmpty())
    }
    @Test fun genericReferenceHeadingsAndPronounFragmentsAreNotTopicNames() {
        val texts = listOf("I fear one mistake. Stable relationships grow through honest communication.",
            "I fear one mistake. Stable relationships depend on mutual trust.",
            "I fear one mistake. Stable relationships allow disagreement.")
        val topic = build(records(texts, "Related knowledge")).topics.single()
        assertNotEquals("Related knowledge", topic.label)
        assertFalse(topic.label.startsWith("I ", ignoreCase = true))
        assertTrue(texts.any { it.contains(topic.label, ignoreCase = true) } || topic.label.startsWith("Notes related to "))
    }
    @Test fun nearDuplicatePassagesInDifferentBlocksDoNotCreateDuplicateNeighborhoods() {
        val records = (0..2).map { i ->
            val first = passage("Forest roots absorb rainfall and protect woodland soil from erosion.", id = "n$i")
            val second = first.copy(id = "n$i:other", sourceBlockId = "n$i:other-block", readerChunkId = "n$i:other-chunk",
                excerpt = "Forest roots absorb rainfall and protect woodland soil from erosion!")
            SemanticRecord("n$i", "r", identity, SemanticMetadata("Woodland $i"), listOf(
                EmbeddedPassage(first, listOf(1f, 0f)), EmbeddedPassage(second, listOf(.94f, .341174f))))
        }
        assertEquals(1, build(records).topics.size)
    }
    @Test fun repeatedHubNotesDoNotCrowdOtherSupportedConnectionsOutOfTheCollection() {
        fun pair(a: String, b: String, score: Float): NoteConnection {
            val p = passage("Forest roots absorb rainfall and protect the soil.", id = a)
            val left = TopicMembership(a, SemanticMetadata(a), p, score, p)
            val right = left.copy(pageId = b, metadata = SemanticMetadata(b), passage = p.copy(pageId = b))
            return NoteConnection("$a:$b", left, right, score)
        }
        val selected = selectDiscoveryConnections(listOf(pair("a", "b", .99f), pair("a", "c", .98f),
            pair("a", "d", .97f), pair("a", "e", .96f), pair("d", "e", .9f)))
        assertEquals(listOf("a:b", "a:c", "d:e"), selected.map { it.id })
        val exposures = selected.flatMap { listOf(it.left.pageId, it.right.pageId) }.groupingBy { it }.eachCount()
        assertTrue(exposures.values.all { it <= 2 })
        assertEquals(selected, selectDiscoveryConnections(selected.reversed()))
    }
    @Test fun oldPolicyCheckpointsReprocessEvidenceWithoutReusingCompletionFlags() {
        val references = records(List(3) { "Forest roots absorb rainfall and protect woodland soil from erosion." })
        val old = TopicSnapshot(identity = identity, complete = false, policyVersion = 5,
            acceptedRevisions = references.associate { it.pageId to it.revision })
        val rebuilt = SectionHierarchicalEngine(identity).discover(references, old, TopicPolicy())
        assertEquals(TopicPolicy().version, rebuilt.policyVersion)
        assertEquals(references.map { it.pageId }.toSet(), rebuilt.acceptedRevisions.keys)
        assertEquals(1, rebuilt.topics.size)
    }

    @Test fun aLegacyFragmentLabelCanImproveWithoutChangingTopicIdentity() {
        val records = records(List(3) { "I fear one mistake. Stable relationships grow through honest communication." })
        val first = build(records)
        val oldTopic = first.topics.single().copy(label = "I fear one")
        val old = first.copy(topics = listOf(oldTopic))
        val result = SectionHierarchicalEngine(identity).discover(records, old, TopicPolicy()).topics.single()
        assertEquals(oldTopic.id, result.id)
        assertNotEquals("I fear one", result.label)
    }

    @Test fun equalVocabularyWithDifferentOrderOrNegationIsNotDuplicateEvidence() {
        val a = passage("Forest roots absorb water while rainfall washes away soil.")
        assertFalse(nearDuplicateDiscoveryPassages(a, a.copy(excerpt = "Rainfall washes away forest roots while soil absorb water.")))
        assertFalse(nearDuplicateDiscoveryPassages(a, a.copy(excerpt = "Forest roots never absorb water while rainfall washes away soil.")))
    }

    @Test fun checklistQuestionsAndLongReferenceTitlesCannotEstablishAConnection() {
        assertFalse(passage("Truth: What is actually supported by evidence?").isBodyEvidence())
        assertFalse(passage("Social Influence, Authenticity and Personal Value — personal value versus external recognition.", "Related knowledge").isBodyEvidence())
        assertTrue(passage("Forest roots absorb water and release it gradually. This process slows runoff after heavy rainfall and protects the soil from erosion.", "Related knowledge").isBodyEvidence())
    }

}
