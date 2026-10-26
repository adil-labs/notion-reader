package com.example.notionreader.domain.structure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StructuralModelsTest {

    @Test
    fun normalizeNotionIdHandlesDashedAndUndashed() {
        val undashed = "3dec9345e10a8180867dfa96b49bd30b"
        val dashed = "3dec9345-e10a-8180-867d-fa96b49bd30b"

        assertEquals(dashed, normalizeNotionId(undashed))
        assertEquals(dashed, normalizeNotionId(dashed))
        assertEquals(dashed, normalizeNotionId("  3DEC9345-E10A-8180-867D-FA96B49BD30B  "))
    }

    @Test
    fun normalizeNotionIdRejectsInvalidLengthsOrCharacters() {
        assertNull(normalizeNotionId("short-id"))
        assertNull(normalizeNotionId("3dec9345-e10a-8180-867d-fa96b49bd30z")) // 'z' not hex
        assertNull(normalizeNotionId(""))
    }

    @Test
    fun navigationAnchorPreservesNullReaderChunkIdForUnspeakableBlocks() {
        val anchor = StructuralNavigationAnchor(
            sourceBlockId = "block-1",
            readerChunkId = null,
            characterOffset = 0
        )
        assertNull("Unspeakable blocks must never have fabricated chunk IDs", anchor.readerChunkId)
    }

    @Test
    fun structuralParentDistinguishesDatabaseAndDataSource() {
        val dbParent = StructuralParent.Database("db-123")
        val dsParent = StructuralParent.DataSource("ds-456")

        assertEquals("db-123", dbParent.databaseId)
        assertEquals("ds-456", dsParent.dataSourceId)
    }
}
