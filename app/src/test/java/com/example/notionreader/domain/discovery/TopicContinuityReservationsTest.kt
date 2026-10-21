package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test

class TopicContinuityReservationsTest {
    private fun passage(id: String, page: String = id) = SemanticPassage(id, page, "s", "Section", "b", "c", 0, 8, "Evidence", "Evidence", 0)

    @Test fun sourceIdentityWorkDoesNotMultiplyWithTheNumberOfPriorTopics() {
        val a = passage("a"); val b = passage("b"); val c = passage("c")
        val prior = (0 until 100).map { TopicContinuity("old$it", it.toLong(), setOf("a"), setOf("a")) }
        var hashes = 0
        val result = reserveTopicBranches(prior, listOf(listOf(a, b), listOf(a, c)), {
            hashes++; it.id
        })
        assertEquals(prior.map { it.id }.toSet(), result.keys)
        assertTrue(result.values.all { it == 0 })
        assertEquals("Shared candidate evidence is identified once, regardless of registry size", 3, hashes)
    }

    @Test fun sourceOverlapOutranksPageOverlapAndMissingSourcesDoNotClaimBranches() {
        val prior = listOf(TopicContinuity("source", 1, setOf("page"), setOf("a", "b")),
            TopicContinuity("missing", 2, setOf("page"), setOf("missing")))
        val result = reserveTopicBranches(prior,
            listOf(listOf(passage("a", "elsewhere"), passage("b", "elsewhere")), listOf(passage("b", "page")), listOf(passage("c", "page"))), { it.id })
        assertEquals(0, result["source"])
        assertNull(result["missing"])
    }
}
