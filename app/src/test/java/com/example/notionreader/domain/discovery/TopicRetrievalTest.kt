package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test

class TopicRetrievalTest {
    private val embedding = EmbeddingIdentity("embedding", 2)
    private val concept = GeneratedTopicLabel("seed-topic", semanticHash("sources"), semanticHash("work"),
        "Seed germination", TopicLabelIdentity("a", "t", "c", "p", "s", "v", "GPU"), listOf("seed-p"),
        "Conditions that allow a seed to germinate and sprout.")
    private val query get() = TopicRetrievalQuery(concept.topicId, concept.workKey,
        requireNotNull(topicConceptQuery(concept)), embedding, TopicRetrievalPolicy(.8f))
    private fun record(id: String, vector: List<Float> = listOf(1f, 0f)): SemanticRecord {
        val body = "Suitable moisture and temperature permit seeds to germinate and begin sprouting."
        val passage = SemanticPassage("$id:p", id, "$id:s", "Summary", "$id:b", "$id:c", 0, body.length, body, body, 0)
        return SemanticRecord(id, "r1", embedding, SemanticMetadata("Source"), listOf(EmbeddedPassage(passage, vector)))
    }
    @Test fun aMatchingOutsideNoteCanBeIncludedAndAnUnrelatedSeedNoteCanRemainUnmatched() {
        val vector = TopicQueryEmbedding(query, listOf(1f, 0f))
        assertEquals("outside", matchTopicRecord(vector, record("outside"), "r1")!!.pageId)
        assertNull(matchTopicRecord(vector, record("seed", listOf(0f, 1f)), "r1"))
    }
    @Test fun noMatchIsNotPaddedAndTheSameNoteCanMatchTwoConcepts() {
        val vector = TopicQueryEmbedding(query, listOf(1f, 0f))
        assertNull(matchTopicRecord(vector, record("unrelated", listOf(0f, 1f)), "r1"))
        val first = matchTopicRecord(vector, record("shared"), "r1")!!
        val second = matchTopicRecord(vector.copy(query = query.copy(topicId = "other-concept")), record("shared"), "r1")!!
        assertEquals(first.pageId, second.pageId)
    }
    @Test fun sourceRevisionEmbeddingIdentityAndBodyEligibilityRemainMandatory() {
        val vector = TopicQueryEmbedding(query, listOf(1f, 0f))
        val source = record("source")
        assertNull(matchTopicRecord(vector, source, "changed"))
        assertNull(matchTopicRecord(vector, source, null))
        assertNull(matchTopicRecord(vector, source.copy(identity = embedding.copy(model = "different")), "r1"))
        assertNull(matchTopicRecord(vector, source.copy(passages = source.passages.map {
            it.copy(passage = it.passage.copy(sourceBlockId = it.passage.sectionId)) }), "r1"))
    }
    @Test fun retrievalIdentityChangesWithMeaningEmbeddingOrPolicyWithoutChangingTheTopicId() {
        val original = query
        for (changed in listOf(original.copy(text = original.text + " More specific meaning."),
            original.copy(conceptWorkKey = semanticHash("new work")),
            original.copy(embedding = embedding.copy(model = "other")),
            original.copy(policy = TopicRetrievalPolicy(.9f)))) {
            assertEquals(original.topicId, changed.topicId)
            assertNotEquals(original.key, changed.key)
        }
    }
    @Test fun partialOrStaleGenerationsCannotBeRestoredAsCompleteMatches() {
        val vector = TopicQueryEmbedding(query, listOf(1f, 0f))
        val match = matchTopicRecord(vector, record("outside"), "r1")!!
        val saved = TopicRetrievalProgress(vector, mapOf("outside" to "r1"), mapOf("outside" to match), complete = true)
        assertTrue(topicRetrievalIsCurrent(saved, query, mapOf("outside" to "r1")))
        assertFalse(topicRetrievalIsCurrent(saved.copy(complete = false), query, mapOf("outside" to "r1")))
        assertFalse(topicRetrievalIsCurrent(saved, query, mapOf("outside" to "r2")))
        assertFalse(topicRetrievalIsCurrent(saved, query, emptyMap()))
        assertFalse(topicRetrievalIsCurrent(saved, query, mapOf("outside" to "r1", "new" to "r1")))
        assertFalse(topicRetrievalIsCurrent(saved, query.copy(text = "Changed meaning"), mapOf("outside" to "r1")))
    }
}
