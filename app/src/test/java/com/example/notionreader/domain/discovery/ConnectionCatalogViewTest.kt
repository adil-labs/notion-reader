package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CancellationException

class ConnectionCatalogViewTest {
    private fun pair(id: String, score: Float, passageId: String = id): NoteConnection {
        fun member(page: String): TopicMembership {
            val text = "Forest ecosystems protect woodland habitat and sustain diverse wildlife populations."
            val p = SemanticPassage("$page:$passageId", page, "section", "Ecology", "body", "chunk", 0, text.length, text, text, 0)
            return TopicMembership(page, SemanticMetadata(page), p, score, p, "r")
        }
        return NoteConnection(id, member("a"), member("b$id"), score)
    }

    @Test fun boundedUpdatesRetainEveryPriorPairAndRankTheStrongerEvidence() {
        val base = (0 until 1000).map { pair("p$it", .5f) }
        val improved = pair("p999", .9f)
        val added = pair("new", .8f)
        val result = mergeConnectionUpdates(base, listOf(improved, added))
        assertEquals(1001, result.size)
        assertEquals(listOf(improved, added), result.take(2))
        assertEquals(base.map { it.id }.toSet() + "new", result.map { it.id }.toSet())
        assertEquals(1001, result.map { it.id }.distinct().size)
    }

    @Test fun weakerUpdatesCannotReplaceTheBestPreviouslyValidatedPassage() {
        val best = pair("same", .9f, "best")
        assertEquals(listOf(best), mergeConnectionUpdates(listOf(best), listOf(pair("same", .6f, "weak"))))
        val earlier = pair("same", .9f, "a")
        assertEquals(listOf(earlier), mergeConnectionUpdates(listOf(best), listOf(earlier)))
    }

    @Test fun mergeResultsDoNotDependOnUpdateOrderOrDropIndependentEvidenceKinds() {
        val semantic = pair("semantic", .8f)
        val reference = pair("reference", 0f).copy(independentNamedReference = true)
        val stronger = pair("semantic", .95f)
        val a = mergeConnectionUpdates(listOf(semantic), listOf(reference, stronger))
        val b = mergeConnectionUpdates(listOf(semantic), listOf(stronger, reference))
        assertEquals(a, b)
        assertEquals(listOf(stronger, reference), a)
    }

    @Test fun eligibilityFilteringKeepsAnIndexedViewWithoutExposingWithdrawnEntries() {
        val base = (0 until 1000).map { pair("p$it", .5f) }
        val result = filterConnectionCatalog(base) { it.id != "p12" }
        assertEquals(999, result.size)
        assertFalse(result.any { it.id == "p12" })
        assertEquals(base.filter { it.id != "p12" }, result)
    }

    @Test fun repeatedGenerationsRemainUsableAndRetainOnlyTheStrongestCurrentPair() {
        var catalog: List<NoteConnection> = (0 until 200).map { pair("p$it", .5f) }
        var expected = pair("p199", .5f)
        repeat(64) { generation ->
            expected = pair("p199", .6f + generation / 200f, "v$generation")
            catalog = mergeConnectionUpdates(catalog, listOf(expected))
        }
        assertEquals(200, catalog.size)
        assertEquals(expected, catalog.first())
        assertEquals(200, catalog.map { it.id }.distinct().size)
    }

    @Test fun cancellingAMergeLeavesTheBaseGenerationUnchanged() {
        val base = (0 until 1000).map { pair("p$it", .5f) }
        var checks = 0
        try {
            mergeConnectionUpdates(base, listOf(pair("new", .9f))) {
                if (++checks == 3) throw CancellationException("Yield to playback")
            }
            fail("A cancelled merge must not publish an incomplete catalog")
        } catch (_: CancellationException) {
            assertEquals(1000, base.size)
            assertFalse(base.any { it.id == "new" })
        }
    }
}
