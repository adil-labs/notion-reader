package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test

class TopicContinuityReservationsTest {
    private fun passage(id: String, page: String = id) = SemanticPassage(id, page, "s", "Section", "b", "c", 0, 8, "Evidence", "Evidence", 0)

    @Test fun aSurvivingAnchorBreaksOverlapTiesButCannotOutrankStrongerSourceEvidence() {
        val prior = listOf(TopicContinuity("old", 1, setOf("a", "b"), setOf("a", "b"), anchorSourceId = "b"))
        assertEquals(1, reserveTopicBranches(prior, listOf(listOf(passage("a")), listOf(passage("b"))), { it.id })["old"])
        assertEquals(0, reserveTopicBranches(prior,
            listOf(listOf(passage("a"), passage("b")), listOf(passage("b"))), { it.id })["old"])
    }

    @Test fun confirmedSourceRemovalAlsoWithdrawsItsOpaqueAnchorIdentity() {
        val a = passage("a"); val b = passage("b")
        val evidence = setOf(topicEvidenceIdentity(a), topicEvidenceIdentity(b))
        val continuity = TopicContinuity("old", 1, setOf("a", "b"), evidence,
            anchorSourceId = topicEvidenceIdentity(a))
        val snapshot = TopicSnapshot(continuity = listOf(continuity))
        assertEquals(continuity, invalidateTopics(snapshot, setOf("a"), permanent = false).continuity.single())
        val removed = invalidateTopics(snapshot, setOf("a"), permanent = true).continuity.single()
        assertEquals(setOf("b"), removed.pageIds)
        assertTrue(removed.sourceIds.isEmpty())
        assertNull(removed.anchorSourceId)
    }

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
