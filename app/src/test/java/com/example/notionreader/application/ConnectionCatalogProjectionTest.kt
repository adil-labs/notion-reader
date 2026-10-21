package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConnectionCatalogProjectionTest {
    private fun pair(n: Int, named: Boolean = false): NoteConnection {
        fun member(id: String, text: String): TopicMembership {
            val p = SemanticPassage(id, id, "section", "Evidence", "block", "chunk", 0, text.length, text, text, 0)
            return TopicMembership(id, SemanticMetadata(id), p, .9f, p, "r")
        }
        return NoteConnection("pair$n", member("a$n", if (named) "See b$n." else "Forest ecosystems sustain diverse woodland wildlife through seasonal rain."),
            member("b$n", "Woodland ecosystems protect diverse wildlife and forest habitats during seasonal rain."),
            1f - n * .1f, independentNamedReference = named,
            evidenceKind = if (named) null else ConnectionEvidenceKind.SimilarPassages)
    }

    @Test fun groupingSeparatesEvidenceKindsWhilePreservingEveryPairAndItsRankWithinTheGroup() = runBlocking {
        val pairs = listOf(pair(0), pair(1, true), pair(2))
        val snapshot = TopicSnapshot(connections = pairs)
        val titles = pairs.flatMap { listOf(it.left, it.right) }.associate { it.pageId to it.metadata.title }
        val projection = ConnectionCatalogProjection { s, offset, limit ->
            ConnectionCatalogPageResult(s.connections.drop(offset).take(limit), s.connections.size)
        }
        val result = projection.read(snapshot, titles, DiscoveryReadToken(0, 0), 0, 20) { true }
        assertTrue(result.page.available)
        assertEquals(1, result.namedCount)
        assertEquals(listOf("pair1", "pair0", "pair2"), result.page.connections.map { it.id })
        assertEquals(pairs, snapshot.connections)
    }

    @Test fun anotherPageReusesOnlyIntegerRoutesAndResetRejectsInFlightResults() = runBlocking {
        val pairs = (0..89).map { pair(it, it % 3 == 0) }
        val snapshot = TopicSnapshot(connections = pairs)
        val titles = pairs.flatMap { listOf(it.left, it.right) }.associate { it.pageId to it.metadata.title }
        var reads = 0
        var current = true
        var resetDuringRead = false
        val projection = ConnectionCatalogProjection { s, offset, limit ->
            assertTrue("Evidence reads stay page bounded", limit <= 64)
            if (resetDuringRead) current = false
            reads += minOf(limit, (s.connections.size - offset).coerceAtLeast(0))
            ConnectionCatalogPageResult(s.connections.drop(offset).take(limit), s.connections.size)
        }
        val token = DiscoveryReadToken(0, 0)
        projection.read(snapshot, titles, token, 0, 20) { current }
        val before = reads
        val next = projection.read(snapshot, titles, token, 40, 20) { current }
        assertTrue(next.page.available)
        assertEquals("Only the requested source page is reread, without rescanning the catalog", 64, reads - before)
        resetDuringRead = true
        assertFalse(projection.read(snapshot, titles, token, 0, 20) { current }.page.available)
    }
}
