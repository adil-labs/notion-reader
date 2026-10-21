package com.example.notionreader.ui

import com.example.notionreader.domain.discovery.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionPresentationTest {
    @Test fun visitingTheWholeCatalogKeepsOnlyANearbyEvidenceWindowAndEarlierRowsRemainAddressable() {
        for (position in 0 until 10_000 step 17) {
            val request = connectionWindowRequest(position, 10_000, 10_000)
            assertTrue(request.limit <= 60)
            assertTrue(position in request.offset until request.offset + request.limit)
            assertTrue(request.offset >= 0 && request.offset + request.limit <= 10_000)
        }
        assertEquals(ConnectionWindowRequest(0, 60), connectionWindowRequest(0, 10_000, 10_000))
        assertEquals(ConnectionWindowRequest(0, 20), connectionWindowRequest(19, 20, 10_000))
        assertEquals(ConnectionWindowRequest(0, 0), connectionWindowRequest(0, 20, 0))
    }
    @Test fun connectionHeadingsBelongToCatalogBoundariesRatherThanLoadedWindows() {
        assertEquals("Named references (24)", connectionGroupHeading(0, 24, 80))
        assertNull(connectionGroupHeading(20, 24, 80))
        assertEquals("Similar passages (56)", connectionGroupHeading(24, 24, 80))
        assertNull(connectionGroupHeading(40, 24, 80))
        assertEquals("Similar passages (80)", connectionGroupHeading(0, 0, 80))
    }

    @Test fun previewCountMakesTheCompleteCatalogDiscoverableWithoutClaimingCompletion() {
        assertEquals("2 of 80 connections", connectionPreviewCount(2, 80))
        assertEquals("0 of 80 connections", connectionPreviewCount(0, 80))
        assertEquals("1 connection", connectionPreviewCount(1, 1))
    }

    private fun pair(index: Int): NoteConnection {
        fun member(id: String): TopicMembership {
            val text = "Forest ecosystems sustain diverse wildlife and protect woodland soil during seasonal rain."
            val p = SemanticPassage(id, id, "s", "Forest ecosystems", "b", "c", 0, text.length, text, text, 0)
            return TopicMembership(id, SemanticMetadata(id), p, .9f, p, "r")
        }
        return NoteConnection("pair-$index", member("a$index"), member("b$index"), 1f - index / 10_001f,
            evidenceKind = ConnectionEvidenceKind.SimilarPassages)
    }

    @Test fun preparingOnePageDoesNotReadOrClassifyTheRestOfTheCatalog() {
        var reads = 0
        val catalog = object : AbstractList<NoteConnection>() {
            override val size = 10_000
            override fun get(index: Int): NoteConnection { reads++; return pair(index) }
        }
        val page = prepareConnectionWindow(catalog, emptyMap(), offset = 60, limit = 20)
        assertEquals(20, reads)
        assertEquals(10_000, page.totalCount)
        assertEquals((60 until 80).map { "pair-$it" }, page.connections.map { it.id })
        assertEquals(page.connections.map { it.id }.toSet(), page.evidence.keys)
    }

    @Test fun selectedComparisonPreparesOnlyItsOwnEvidence() {
        val catalog = (0 until 100).map(::pair)
        val selected = prepareConnectionWindow(catalog, emptyMap(), limit = 0, selectedId = "pair-99")
        assertEquals(listOf("pair-99"), selected.connections.map { it.id })
        assertEquals(setOf("pair-99"), selected.evidence.keys)
        assertTrue(prepareConnectionWindow(catalog, emptyMap(), limit = 0, selectedId = "missing").connections.isEmpty())
    }

    @Test fun boundedPreviewPreservesDeterministicRelevanceAndExposureSelection() {
        val catalog = (0 until 50).map(::pair).reversed()
        val preview = prepareConnectionPreview(catalog, emptyMap())
        assertEquals(selectDiscoveryConnections(catalog, limit = 2), preview.connections)
        assertEquals(2, preview.evidence.size)
        assertEquals(50, preview.totalCount)
    }

    @Test fun anInvalidNamedReferenceDoesNotConsumeAPreviewSlotOrExposeItsEvidence() {
        val invalid = pair(0).copy(independentNamedReference = true, evidenceKind = null)
        val supported = listOf(pair(1), pair(2))
        val preview = prepareConnectionPreview(listOf(invalid) + supported, emptyMap())
        assertEquals(supported, preview.connections)
        assertFalse(preview.evidence.containsKey(invalid.id))
        val page = prepareConnectionWindow(listOf(invalid), emptyMap())
        assertTrue(page.connections.isEmpty())
        assertTrue(page.evidence.isEmpty())
    }

    @Test fun anEmptyWindowDoesNotReadTheCatalogAndBoundsDoNotOverflow() {
        var reads = 0
        val catalog = object : AbstractList<NoteConnection>() {
            override val size = 10_000
            override fun get(index: Int): NoteConnection { reads++; return pair(index) }
        }
        assertTrue(prepareConnectionWindow(catalog, emptyMap(), limit = 0).connections.isEmpty())
        assertTrue(prepareConnectionWindow(catalog, emptyMap(), offset = Int.MAX_VALUE, limit = 20).connections.isEmpty())
        assertEquals(0, reads)
    }
}
