package com.example.notionreader.application

import com.example.notionreader.data.local.FileProposalProgressStore
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingConceptRetrievalTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun candidatePagesRequireSupportAndBindContinuationToTheExactQueryVector() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val store = FileProposalProgressStore(root.resolve("progress"))
        val embedding = EmbeddingIdentity("model", 2); val work = semanticHash("concept")
        val concept = SupportConcept("pending:$work", work, "Seed germination", "Conditions needed for seeds to germinate.")
        val query = TopicQueryEmbedding(TopicRetrievalQuery(concept.topicId, work, "Seed germination\nConditions needed for seeds to germinate.",
            embedding, TopicRetrievalPolicy(.5f)), listOf(1f, 0f))
        val verifier = TopicLabelIdentity("a", "t", "template", "support", "schema", "v1", "CPU")
        fun passage(id: String, order: Int): EmbeddedPassage {
            val text = "Moisture and suitable temperature allow seeds to germinate and begin sprouting."
            return EmbeddedPassage(SemanticPassage(id, "note", "section", "Overview", "block$id", "chunk$id", 0,
                text.length, text, text, order), listOf(1f, 0f))
        }
        val record = SemanticRecord("note", "r1", embedding, SemanticMetadata("note"), listOf(passage("a", 0), passage("b", 1)))
        assertTrue(index.write(record)); var calls = 0
        suspend fun step(q: TopicQueryEmbedding, cursor: PendingRetrievalCursor?) = retrievePendingConceptPage(concept, q, record, index,
            verifier, 4, mapOf("note" to "r1"), cursor, 1, store, { true }, { true }) {
            calls++; TopicSupportRun.Completed(if (it.evidence.passageId == "a") SupportDecision.UNSUPPORTED else SupportDecision.SUPPORTED)
        }
        val first = step(query, null) as PendingConceptRetrievalPage.Ready
        assertTrue(first.supported.isEmpty()); assertNotNull(first.next)
        assertEquals(PendingConceptRetrievalPage.Stale, step(query.copy(vector = listOf(1f, .00001f)), first.next))
        val second = step(query, first.next) as PendingConceptRetrievalPage.Ready
        assertEquals(setOf("b"), second.supported); assertNull(second.next); assertEquals(2, calls)
        assertEquals(first, step(query, null)); assertEquals(2, calls)
    }
}
