package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test

class PassageDeltaTest {
    private val identity = EmbeddingIdentity("model", 2)
    private fun passage(id: String, text: String, section: String = "section", block: String = "block", start: Int = 0) =
        SemanticPassage(id, "note", section, "Heading", block, "chunk", start, start + text.length, text, "context\n$text", start)
    private fun record(revision: String, vararg passages: SemanticPassage) = SemanticRecord("note", revision, identity,
        SemanticMetadata("Note"), passages.map { EmbeddedPassage(it, listOf(1f, 0f)) })

    @Test fun newAndChangedBodiesAreAnchorsAndRemovedBodiesAreRetired() {
        val old = record("r1", passage("old", "first"), passage("remove", "second"))
        val current = record("r2", passage("old", "edited"), passage("new", "third"))
        val delta = passageDelta(old, current)
        assertEquals(setOf("old", "new"), delta.changed)
        assertEquals(setOf("old", "remove"), delta.removed)
        assertTrue(delta.rebound.isEmpty())
    }

    @Test fun movedOffsetsRebindUnchangedBodyWithoutCreatingAChangedAnchor() {
        val old = record("r1", passage("old", "same body"))
        val current = record("r2", passage("new", "same body", start = 20))
        val delta = passageDelta(old, current)
        assertTrue(delta.changed.isEmpty()); assertTrue(delta.removed.isEmpty())
        assertEquals(mapOf("old" to "new"), delta.rebound)
    }

    @Test fun duplicatedBodiesAndDifferentProvenanceDoNotSilentlyCollapse() {
        val old = record("r1", passage("one", "same"), passage("two", "same"))
        val current = record("r2", passage("three", "same"), passage("four", "same", section = "other"))
        val delta = passageDelta(old, current)
        assertEquals(setOf("four"), delta.changed)
        assertEquals(1, delta.removed.size)
        assertEquals(1, delta.rebound.size)
        assertEquals("three", delta.rebound.values.single())
    }

    @Test fun duplicateBodiesPreserveExactIdentitiesBeforeRebindingMoves() {
        val old = record("r1", passage("a", "same"), passage("b", "same"))
        val current = record("r2", passage("b", "same"), passage("c", "same"))
        assertEquals(mapOf("b" to "b", "a" to "c"), passageDelta(old, current).rebound)
    }

    @Test fun missingOrIncompatiblePreviousIndexTreatsEveryCurrentBodyAsNew() {
        val current = record("r2", passage("one", "one"), passage("two", "two"))
        assertEquals(setOf("one", "two"), passageDelta(null, current).changed)
        assertEquals(setOf("one", "two"), passageDelta(current.copy(identity = identity.copy(model = "other")), current).changed)
    }
}
