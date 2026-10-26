package com.example.notionreader.data.structure

import com.example.notionreader.data.notion.*
import com.example.notionreader.domain.structure.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StructuralQueryIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val parent = "11111111-1111-1111-1111-111111111111"
    private val child = "22222222-2222-2222-2222-222222222222"
    private val block = "33333333-3333-3333-3333-333333333333"
    private val source = "public-fixture"

    @Test fun blockQueriesRetainExternalAndMalformedLinks() = runTest {
        val store = FileStructuralKnowledgeStore(temporary.newFolder())
        store.initSource(source, 1)
        val record = StructuralExtractor().extract(NotionPage(id = parent), listOf(NotionBlock(block, "paragraph",
            paragraph = NotionTextBlock(listOf(NotionRichText(plainText = "External", href = "https://example.org"),
                NotionRichText(plainText = "Malformed", href = "https://[invalid"))))), { emptyList() }, source, java.time.Instant.EPOCH)
        store.writePage(record, source, 1)
        val expected = record.outgoingRelationships.filter { it.sourceId == block }
        assertEquals(expected.toSet(), store.getOutgoingRelationships(block).toSet())
        assertTrue(expected.any { it.target is RelationshipTarget.ExternalUrl })
        assertTrue(expected.any { it.target is RelationshipTarget.Unresolved })
    }

    @Test fun aMovedChildIsNotListedUnderItsStaleParentBlockObservation() = runTest {
        val store = FileStructuralKnowledgeStore(temporary.newFolder())
        store.initSource(source, 1)
        val extractor = StructuralExtractor()
        store.writePage(extractor.extract(NotionPage(id = parent), listOf(NotionBlock(child, "child_page",
            childPage = NotionChildPageBlock("Child"))), { emptyList() }, source), source, 1)
        store.writePage(extractor.extract(NotionPage(id = child, parent = NotionParent("page_id", pageId = block)),
            emptyList(), { emptyList() }, source), source, 1)
        assertTrue(store.getDirectChildren(parent).isEmpty())
        assertEquals(listOf(child), store.getDirectChildren(block).map { it.id })
        assertTrue(store.getOutgoingRelationships(parent).none { it.kind == ExplicitRelationshipKind.PAGE_PARENT_CHILD })
        assertEquals(listOf(block), store.getIncomingRelationships(child).filter {
            it.kind == ExplicitRelationshipKind.PAGE_PARENT_CHILD }.map { it.sourceId })
    }

    @Test fun parentMetadataAndChildBlocksAgreeOnContainmentWithoutSelfEdges() = runTest {
        val directory = temporary.newFolder()
        val store = FileStructuralKnowledgeStore(directory)
        store.initSource(source, 1)
        val extractor = StructuralExtractor()
        store.writePage(extractor.extract(NotionPage(id = parent), listOf(NotionBlock(id = child,
            type = "child_page", childPage = NotionChildPageBlock("Child"))), { emptyList() }, source), source, 1)
        assertEquals(PageAccessibilityState.UNFETCHED, store.getDirectChildren(parent).single().accessibilityState)
        store.writePage(extractor.extract(NotionPage(id = child, parent = NotionParent("page_id", pageId = parent)),
            emptyList(), { emptyList() }, source), source, 1)
        val restored = FileStructuralKnowledgeStore(directory)
        assertEquals(listOf(child), restored.getDirectChildren(parent).map { it.id })
        assertEquals(listOf(parent), restored.getAncestors(child).map { it.id })
        val edges = restored.getIncomingRelationships(child).filter { it.kind == ExplicitRelationshipKind.PAGE_PARENT_CHILD }
        assertEquals(2, edges.size) // Two independent observations with different provenance.
        assertTrue(edges.all { it.sourceId == parent && it.target == RelationshipTarget.InternalPage(child) })
        assertEquals(edges.toSet(), restored.getOutgoingRelationships(parent).filter {
            it.kind == ExplicitRelationshipKind.PAGE_PARENT_CHILD }.toSet())
    }

    @Test fun aSectionAnchorSurvivesATitleAndBodyEditAndRoundTripsOffline() = runTest {
        val directory = temporary.newFolder()
        val store = FileStructuralKnowledgeStore(directory)
        store.initSource(source, 1)
        suspend fun snapshot(title: String) = StructuralExtractor().extract(NotionPage(id = parent),
            listOf(NotionBlock(block, "heading_1", heading1 = NotionTextBlock(listOf(NotionRichText(plainText = title))))),
            { emptyList() }, source)
        store.writePage(snapshot("Before"), source, 1)
        val before = store.resolveNavigationAnchor(block)
        store.writePage(snapshot("After"), source, 1)
        val restored = FileStructuralKnowledgeStore(directory)
        assertEquals(before, restored.resolveNavigationAnchor(block))
        assertEquals("After", restored.getSections(parent).single().headingTitle)
        assertEquals(block, restored.resolveBlockAnchor(block)!!.sourceBlockId)
    }
}
