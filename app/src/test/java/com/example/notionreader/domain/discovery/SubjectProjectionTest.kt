package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test

class SubjectProjectionTest {
    @Test fun sharedProjectionKeepsTheBestBodyPassageGloballyAndItsLiteralSource() {
        val model = EmbeddingIdentity("shared-projection", 2)
        fun p(id: String, page: String, vector: List<Float>): EmbeddedPassage {
            val text = "Atomic database transactions preserve consistent records and recover committed changes after interruption."
            return EmbeddedPassage(SemanticPassage(id, page, "$page:s", "Atomic transactions", "$id:b", "$id:c",
                0, text.length, text, text, 0), vector)
        }
        val seed = p("a:seed", "a", listOf(1f, 0f))
        val weak = p("b:early", "b", listOf(.8f, .6f))
        val strong = p("b:strong", "b", listOf(1f, 0f))
        val records = listOf(SemanticRecord("a", "r", model, SemanticMetadata("Seed"), listOf(seed)),
            SemanticRecord("b", "r", model, SemanticMetadata("Target"), listOf(weak, strong)))
        val input = prepareFormationInput(records, model)
        val vectors = input.evidence.associate { it.embedded.passage.id to it.vector }
        val projection = prepareSubjectProjection(input.opportunities, listOf(0), vectors, 2)
        val members = linkedMapOf<String, TopicMembership>()
        input.evidence.forEach { considerSubjectMembership(it, projectionDot(projection.center, it.vector), projection.anchor.passage, members, .6f) }
        assertEquals("b:strong", members.getValue("b").passage.id)
        assertEquals(1f, members.getValue("b").score, 0f)
        val candidate = nameSubjectCandidate("case", 1, projection, members.values.toList())
        assertEquals("Atomic transactions", candidate.label)
        assertTrue(candidate.members.any { it.passage.id == candidate.labelSource.id })
        assertEquals(seed.passage, candidate.anchor.passage)
    }
}
