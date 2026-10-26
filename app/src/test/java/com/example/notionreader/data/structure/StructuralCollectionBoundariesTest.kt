package com.example.notionreader.data.structure

import com.example.notionreader.data.notion.*
import com.example.notionreader.domain.structure.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StructuralCollectionBoundariesTest {
    private val page = "11111111-1111-1111-1111-111111111111"
    private val parent = "22222222-2222-2222-2222-222222222222"
    private val child = "33333333-3333-3333-3333-333333333333"
    private fun text(value: String) = NotionTextBlock(listOf(NotionRichText(plainText = value)))

    @Test fun codeBodyAndCaptionKeepSeparateExplicitReferences() = runTest {
        val link = NotionRichText(plainText = "Reference", href = "https://example.org")
        val record = StructuralExtractor().extract(NotionPage(id = page), listOf(NotionBlock(parent, "code",
            code = NotionCodeBlock(richText = listOf(link), caption = listOf(link)))), { emptyList() })
        val edges = record.outgoingRelationships.filter { it.kind == ExplicitRelationshipKind.EXTERNAL_HYPERLINK }
        assertEquals(2, edges.size)
        assertEquals(2, edges.map { it.id }.distinct().size)
    }

    @Test fun aPrimaryMediaUrlAndItsCaptionKeepSeparateOccurrences() = runTest {
        val record = StructuralExtractor().extract(NotionPage(id = page), listOf(NotionBlock(parent, "bookmark",
            bookmark = NotionBookmarkBlock(url = "https://example.org", caption = listOf(
                NotionRichText(plainText = "Reference", href = "https://example.org"))))), { emptyList() })
        val edges = record.outgoingRelationships.filter { it.kind == ExplicitRelationshipKind.EXTERNAL_HYPERLINK }
        assertEquals(2, edges.size)
        assertEquals(2, edges.map { it.id }.distinct().size)
    }

    @Test fun everyNestedBlockHasAnExplicitParentRelationship() = runTest {
        val record = StructuralExtractor().extract(NotionPage(id = page),
            listOf(NotionBlock(parent, "toggle", hasChildren = true)),
            { listOf(NotionBlock(child, "paragraph", paragraph = text("Body"))) }, sourceIdentity = "source")
        val edges = record.outgoingRelationships.filter { it.sourceId == child && it.kind == ExplicitRelationshipKind.BLOCK_PARENT_CHILD }
        assertEquals(1, edges.size)
        val edge = edges.single()
        assertEquals(RelationshipTarget.InternalBlock(blockId = parent, pageId = page), edge.target)
        assertEquals(child, edge.provenance.sourceBlockId)
        assertEquals("source", edge.provenance.sourceIdentity)
    }

    @Test fun originalSyncedBlocksKeepNativeChildrenWithoutMakingThemSpeakable() = runTest {
        val record = StructuralExtractor().extract(NotionPage(id = page),
            listOf(NotionBlock(parent, "synced_block", hasChildren = true, syncedBlock = NotionSyncedBlock())),
            { listOf(NotionBlock(child, "paragraph", paragraph = text("Synced body"))) })
        assertEquals(listOf(parent, child), record.blocks.map { it.id })
        assertTrue(record.sections.all { it.navigationAnchor.readerChunkId == null })
    }

    @Test fun aDuplicateBlockIdentityIsIncompleteAndNeverStoredTwice() = runTest {
        val record = StructuralExtractor().extract(NotionPage(id = page),
            listOf(NotionBlock(parent, "toggle", hasChildren = true)),
            { listOf(NotionBlock(parent, "paragraph", paragraph = text("Cycle"))) })
        assertEquals(listOf(parent), record.blocks.map { it.id })
        assertEquals(SyncCompleteness.PARTIAL_TRAVERSAL_INCOMPLETE, record.page.syncStatus)
    }

    @Test fun cancellationOfChildCollectionCannotBecomeACompleteEmptySubtree() = runTest {
        val failure = runCatching {
            StructuralExtractor().extract(NotionPage(id = page),
                listOf(NotionBlock(parent, "toggle", hasChildren = true)),
                { throw CancellationException("cancelled synthetic request") })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    @Test fun headingsOutsideTheReaderDepthLimitHaveNoInventedPlaybackChunk() = runTest {
        val root = NotionBlock(parent, "toggle", hasChildren = true)
        val nested = (1..7).associate { depth ->
            val previous = if (depth == 1) parent else "44444444-4444-4444-4444-${(depth - 1).toString().padStart(12, '0')}"
            val id = "44444444-4444-4444-4444-${depth.toString().padStart(12, '0')}"
            previous to listOf(if (depth == 7) NotionBlock(id, "heading_1", heading1 = text("Deep heading"))
                else NotionBlock(id, "toggle", hasChildren = true))
        }
        val record = StructuralExtractor().extract(NotionPage(id = page), listOf(root), { nested[it].orEmpty() })
        assertEquals("Deep heading", record.sections.last().headingTitle)
        assertNull(record.sections.last().navigationAnchor.readerChunkId)
    }
}

