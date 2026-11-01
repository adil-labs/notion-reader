package com.example.notionreader.domain.discovery

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class TopicCandidatePagingTest {
    private val identity = EmbeddingIdentity("model", 2)
    private val query = TopicQueryEmbedding(TopicRetrievalQuery("topic", semanticHash("concept"),
        "Seed germination\nConditions that enable seeds to germinate.", identity, TopicRetrievalPolicy(.8f)), listOf(1f, 0f))
    private fun record(count: Int): SemanticRecord {
        val passages = (0 until count).map { i ->
            val body = "Moisture helps dormant seeds absorb water and begin their germination process."
            val p = SemanticPassage("p$i", "note", "s$i", "Overview", "b$i", "c$i", 0, body.length, body, body, i)
            EmbeddedPassage(p, if (i == 0) listOf(1f, 0f) else listOf(.9f, .4358899f))
        }
        return SemanticRecord("note", "r1", identity, SemanticMetadata("Note"), passages)
    }
    private fun ready(record: SemanticRecord, limit: Int, after: TopicCandidateCursor? = null) =
        topicCandidatePage(query, record, "r1", limit, after) as TopicCandidatePage.Ready

    @Test fun boundedPagesEventuallyExposeEveryEligiblePassageInAMixedNote() {
        val record = record(31); val found = mutableListOf<TopicMatchReference>(); var cursor: TopicCandidateCursor? = null
        do {
            val page = ready(record, 4, cursor)
            assertTrue(page.candidates.size <= 4)
            found += page.candidates; cursor = page.next
        } while (cursor != null)
        assertEquals((0 until 31).map { "p$it" }, found.map { it.passageId })
        assertEquals(found.size, found.map { it.passageId }.toSet().size)
    }

    @Test fun aCursorCannotSkipEvidenceAfterSourceOrConceptChanges() {
        val source = record(3); val cursor = ready(source, 1).next!!
        assertEquals(TopicCandidatePage.Stale, topicCandidatePage(query, source.copy(revision = "r2"), "r2", 1, cursor))
        assertEquals(TopicCandidatePage.Stale, topicCandidatePage(query.copy(query = query.query.copy(text = "New definition")), source, "r1", 1, cursor))
        assertEquals(TopicCandidatePage.Stale, topicCandidatePage(query, source, "r1", 1, cursor.copy(passageId = "missing")))
    }

    @Test fun headingForeignInvalidAndDuplicateEvidenceCannotBecomeCandidates() {
        val base = record(1); val p = base.passages.single()
        val heading = p.copy(passage = p.passage.copy(sourceBlockId = p.passage.sectionId))
        assertTrue(ready(base.copy(passages = listOf(heading)), 2).candidates.isEmpty())
        assertEquals(TopicCandidatePage.Invalid, topicCandidatePage(query, base.copy(passages = listOf(p, p)), "r1", 2))
        assertEquals(TopicCandidatePage.Invalid, topicCandidatePage(query, base.copy(passages = listOf(p.copy(vector = listOf(Float.NaN, 0f)))), "r1", 2))
        assertEquals(TopicCandidatePage.Invalid, topicCandidatePage(query, base.copy(passages = listOf(p.copy(passage = p.passage.copy(pageId = "foreign")))), "r1", 2))
    }

    @Test fun cancellationPropagatesDuringCandidateCollection() {
        try {
            topicCandidatePage(query, record(4), "r1", 2, checkActive = { throw CancellationException("stop") })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
