package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test

class FormationInputTest {
    private val model = EmbeddingIdentity("shared-input", 2)
    private fun passage(id: String, page: String = "a", section: String = "a:s", vector: List<Float> = listOf(1f, 0f),
        heading: Boolean = false): EmbeddedPassage {
        val text = "Atomic database transactions preserve consistent records and recover committed changes after interruption."
        return EmbeddedPassage(SemanticPassage(id, page, section, "Database transactions",
            if (heading) section else "$id:block", "$id:chunk", 0, text.length, text, text, 0), vector)
    }

    @Test fun sharedOpportunitiesKeepEveryWithinSectionDirectionAndAllItsProvenance() {
        val record = SemanticRecord("a", "r", model, SemanticMetadata("Database transactions"), listOf(
            passage("a:1"), passage("a:2"), passage("a:3", vector = listOf(0f, 1f)),
            passage("a:4", section = "a:later")))
        val input = prepareFormationInput(listOf(record), model)
        assertEquals(4, input.evidence.size)
        assertEquals(2, input.authoredSections)
        assertEquals(listOf(listOf("a:1", "a:2"), listOf("a:3"), listOf("a:4")),
            input.opportunities.map { group -> group.passages.map { it.passage.id } })
        val checkpoint = SectionHierarchicalEngine(model).step(listOf(record), null, TopicPolicy(), 1)
        assertEquals(input.opportunities.size, checkpoint.formationProgress!!.totalOpportunities)
    }

    @Test fun sharedInputRejectsTheSameHeadingIdentityAndVectorFailuresAsShippingFormation() {
        val record = SemanticRecord("a", "r", model, SemanticMetadata("Database transactions"), listOf(
            passage("valid"), passage("heading", heading = true), passage("wrong-page", page = "b"),
            passage("zero", vector = listOf(0f, 0f)), passage("nonfinite", vector = listOf(Float.NaN, 1f)),
            passage("wrong-size", vector = listOf(1f))))
        val input = prepareFormationInput(listOf(record, record.copy(pageId = "c", identity = EmbeddingIdentity("other", 2))), model)
        assertEquals(listOf("valid"), input.evidence.map { it.embedded.passage.id })
        assertArrayEquals(floatArrayOf(1f, 0f), input.opportunities.single().vector, 0f)
    }

    @Test fun evaluationObservesExactSeedsWithoutChangingShippingProjection() {
        val records = listOf(SemanticRecord("a", "r", model, SemanticMetadata("Database transactions"), listOf(
            passage("a:1"), passage("a:2", vector = listOf(0f, 1f)))))
        var observed: ExactSeedFormation? = null
        val engine = SectionHierarchicalEngine(model)
        val evaluated = engine.evaluate(records, TopicPolicy(), onFormation = { observed = it })
        val shipped = engine.discover(records, null, TopicPolicy())
        assertEquals(2, observed!!.opportunities)
        assertEquals(setOf(listOf(0), listOf(1)), observed!!.groups.toSet())
        fun properties(snapshot: TopicSnapshot) = (snapshot.topics + snapshot.provisionalTopics).map { topic ->
            topic.label to topic.members.map { Triple(it.pageId, it.passage.id, it.score) }
        }
        assertEquals(properties(shipped), properties(evaluated))
        assertTrue(evaluated.complete)
        assertNull(evaluated.formationProgress)
    }
}
