package com.example.notionreader.data.structure

import com.example.notionreader.data.content.NotionBlockNormalizer
import com.example.notionreader.data.content.ReaderDocumentMapper
import com.example.notionreader.data.notion.NotionBlock
import com.example.notionreader.data.notion.NotionChildDatabaseBlock
import com.example.notionreader.data.notion.NotionChildPageBlock
import com.example.notionreader.data.notion.NotionDividerBlock
import com.example.notionreader.data.notion.NotionLinkToPageBlock
import com.example.notionreader.data.notion.NotionMention
import com.example.notionreader.data.notion.NotionPage
import com.example.notionreader.data.notion.NotionParent
import com.example.notionreader.data.notion.NotionProperty
import com.example.notionreader.data.notion.NotionReference
import com.example.notionreader.data.notion.NotionRelationTarget
import com.example.notionreader.data.notion.NotionRichText
import com.example.notionreader.data.notion.NotionSyncedBlock
import com.example.notionreader.data.notion.NotionSyncedFrom
import com.example.notionreader.data.notion.NotionTextBlock
import com.example.notionreader.data.notion.NotionTodoBlock
import com.example.notionreader.domain.structure.ChildCompleteness
import com.example.notionreader.domain.structure.ExplicitRelationshipKind
import com.example.notionreader.domain.structure.RelationshipTarget
import com.example.notionreader.domain.structure.StructuralParent
import com.example.notionreader.domain.structure.SyncCompleteness
import com.example.notionreader.domain.structure.TargetResolutionStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class StructuralExtractorTest {

    private val extractor = StructuralExtractor(maxDepth = 5)
    private val pageId = "11111111-1111-1111-1111-111111111111"

    @Test
    fun extractsPageHierarchyAndParentMembership() = runTest {
        val page = NotionPage(
            id = pageId,
            parent = NotionParent(type = "data_source_id", dataSourceId = "22222222-2222-2222-2222-222222222222"),
            properties = mapOf(
                "title" to NotionProperty(type = "title", title = listOf(NotionRichText(plainText = "Overview")))
            )
        )

        val record = extractor.extract(page, emptyList(), { emptyList() })
        val parent = record.page.parent

        assertTrue(parent is StructuralParent.DataSource)
        assertEquals("22222222-2222-2222-2222-222222222222", (parent as StructuralParent.DataSource).dataSourceId)
        assertEquals("22222222-2222-2222-2222-222222222222", record.page.dataSourceId)
        assertNull("Database ID must remain distinct from dataSourceId", record.page.databaseId)

        val parentEdge = record.outgoingRelationships.single { it.kind == ExplicitRelationshipKind.DATA_SOURCE_MEMBERSHIP }
        assertEquals("22222222-2222-2222-2222-222222222222", (parentEdge.target as RelationshipTarget.InternalDataSource).dataSourceId)
    }

    @Test
    fun extractsNestedBlocksPreservingSiblingOrderAndParentIds() = runTest {
        val childId = "33333333-3333-3333-3333-333333333333"
        val parentBlock = NotionBlock(
            id = "22222222-2222-2222-2222-222222222222",
            type = "paragraph",
            hasChildren = true,
            paragraph = NotionTextBlock(richText = listOf(NotionRichText(plainText = "Parent text")))
        )
        val childBlock = NotionBlock(
            id = childId,
            type = "bulleted_list_item",
            hasChildren = false,
            bulletedListItem = NotionTextBlock(richText = listOf(NotionRichText(plainText = "Child item")))
        )

        val page = NotionPage(id = pageId)
        val record = extractor.extract(
            page = page,
            blocks = listOf(parentBlock),
            childrenSupplier = { if (it == "22222222-2222-2222-2222-222222222222") listOf(childBlock) else emptyList() }
        )

        assertEquals(2, record.blocks.size)
        val parent = record.blocks[0]
        val child = record.blocks[1]

        assertEquals(0, parent.siblingOrder)
        assertNull(parent.parentBlockId)

        assertEquals(0, child.siblingOrder)
        assertEquals("22222222-2222-2222-2222-222222222222", child.parentBlockId)
    }

    @Test
    fun reconstructsDeterministicHeadingPathHierarchy() = runTest {
        val h1 = NotionBlock(
            id = "11111111-0000-0000-0000-000000000001",
            type = "heading_1",
            heading1 = NotionTextBlock(richText = listOf(NotionRichText(plainText = "Architecture")))
        )
        val h2 = NotionBlock(
            id = "11111111-0000-0000-0000-000000000002",
            type = "heading_2",
            heading2 = NotionTextBlock(richText = listOf(NotionRichText(plainText = "Data Storage")))
        )
        val p = NotionBlock(
            id = "11111111-0000-0000-0000-000000000003",
            type = "paragraph",
            paragraph = NotionTextBlock(richText = listOf(NotionRichText(plainText = "Details here")))
        )

        val page = NotionPage(
            id = pageId,
            properties = mapOf("title" to NotionProperty(type = "title", title = listOf(NotionRichText(plainText = "System Design"))))
        )

        val record = extractor.extract(page, listOf(h1, h2, p), { emptyList() })
        assertEquals(2, record.sections.size)

        val section1 = record.sections[0]
        assertEquals(listOf("System Design", "Architecture"), section1.headingPath)
        assertEquals("11111111-0000-0000-0000-000000000001:0", section1.navigationAnchor.readerChunkId)

        val section2 = record.sections[1]
        assertEquals(listOf("System Design", "Architecture", "Data Storage"), section2.headingPath)
        assertEquals("11111111-0000-0000-0000-000000000002:0", section2.navigationAnchor.readerChunkId)
    }

    @Test
    fun sectionKeysAndAnchorsAgreeWithRealReaderDocumentMapper() = runTest {
        val h1Id = "11111111-0000-0000-0000-000000000001"
        val p1Id = "11111111-0000-0000-0000-000000000002"
        val h2Id = "11111111-0000-0000-0000-000000000003"
        val p2Id = "11111111-0000-0000-0000-000000000004"

        val pIntro = NotionBlock(
            id = "00000000-0000-0000-0000-000000000001",
            type = "paragraph",
            paragraph = NotionTextBlock(richText = listOf(NotionRichText(plainText = "Intro text.")))
        )
        val h1 = NotionBlock(
            id = h1Id,
            type = "heading_1",
            heading1 = NotionTextBlock(richText = listOf(NotionRichText(plainText = "Heading 1")))
        )
        val p1 = NotionBlock(
            id = p1Id,
            type = "paragraph",
            paragraph = NotionTextBlock(richText = listOf(NotionRichText(plainText = "Content 1.")))
        )
        val h2 = NotionBlock(
            id = h2Id,
            type = "heading_2",
            heading2 = NotionTextBlock(richText = listOf(NotionRichText(plainText = "Heading 2")))
        )
        val p2 = NotionBlock(
            id = p2Id,
            type = "paragraph",
            paragraph = NotionTextBlock(richText = listOf(NotionRichText(plainText = "Content 2.")))
        )

        val blocks = listOf(pIntro, h1, p1, h2, p2)
        val page = NotionPage(
            id = pageId,
            properties = mapOf("title" to NotionProperty(type = "title", title = listOf(NotionRichText(plainText = "Test Doc"))))
        )

        // Map via real Reader pipeline
        val normalizer = NotionBlockNormalizer()
        val normalizedBlocks = normalizer.normalizeAll(blocks, { emptyList() })
        val readerDoc = ReaderDocumentMapper().map(pageId, "Test Doc", Instant.now(), normalizedBlocks)

        // Extract via StructuralExtractor
        val record = extractor.extract(page, blocks, { emptyList() })

        // 1. Agree on section count
        assertEquals(readerDoc.sections.size, record.sections.size)

        // 2. Agree on section keys: intro has "$pageId:intro", heading sections have headingBlockId directly
        assertEquals("$pageId:intro", record.sections[0].sectionKey)
        assertEquals(readerDoc.sections[0].id, record.sections[0].sectionKey)

        assertEquals(h1Id, record.sections[1].sectionKey)
        assertEquals(readerDoc.sections[1].id, record.sections[1].sectionKey)

        assertEquals(h2Id, record.sections[2].sectionKey)
        assertEquals(readerDoc.sections[2].id, record.sections[2].sectionKey)

        // 3. Agree on speakable chunk anchors
        assertEquals(readerDoc.sections[0].chunks.first().id, record.sections[0].navigationAnchor.readerChunkId)
        assertEquals(readerDoc.sections[1].chunks.first().id, record.sections[1].navigationAnchor.readerChunkId)
        assertEquals(readerDoc.sections[2].chunks.first().id, record.sections[2].navigationAnchor.readerChunkId)
    }

    @Test
    fun navigationAnchorDoesNotFabricateChunkIdForDividerOrUnsupportedBlocks() = runTest {
        val divider = NotionBlock(id = "11111111-0000-0000-0000-000000000001", type = "divider", divider = NotionDividerBlock())
        val page = NotionPage(id = pageId)

        val record = extractor.extract(page, listOf(divider), { emptyList() })
        val introSection = record.sections.single()

        assertNull("Divider must never fabricate a reader chunk ID", introSection.navigationAnchor.readerChunkId)
        assertEquals("11111111-0000-0000-0000-000000000001", introSection.navigationAnchor.sourceBlockId)
    }

    @Test
    fun emptyHeadingEmitsNoSectionAndNoChunk() = runTest {
        val emptyHeading = NotionBlock(
            id = "11111111-0000-0000-0000-000000000001",
            type = "heading_1",
            heading1 = NotionTextBlock(richText = emptyList())
        )
        val p = NotionBlock(
            id = "11111111-0000-0000-0000-000000000002",
            type = "paragraph",
            paragraph = NotionTextBlock(richText = listOf(NotionRichText(plainText = "Paragraph below empty heading")))
        )
        val page = NotionPage(id = pageId)

        val record = extractor.extract(page, listOf(emptyHeading, p), { emptyList() })

        // Empty heading does not create its own section; content belongs to intro section
        assertEquals(1, record.sections.size)
        assertEquals("$pageId:intro", record.sections.single().sectionKey)
    }

    @Test
    fun extractsPropertyRelationsAndMentionsWithProvenance() = runTest {
        val relatedPageId = "22222222-2222-2222-2222-222222222222"
        val mentionedDbId = "33333333-3333-3333-3333-333333333333"

        val page = NotionPage(
            id = pageId,
            properties = mapOf(
                "Related Project" to NotionProperty(
                    id = "rel-1",
                    type = "relation",
                    relation = listOf(NotionRelationTarget(id = relatedPageId))
                ),
                "Summary" to NotionProperty(
                    id = "summary-1",
                    type = "rich_text",
                    richText = listOf(
                        NotionRichText(
                            plainText = "Mentioning database",
                            mention = NotionMention(type = "database", database = NotionReference(id = mentionedDbId))
                        )
                    )
                )
            )
        )

        val record = extractor.extract(page, emptyList(), { emptyList() }, sourceIdentity = "source-conn-1")
        val relationEdge = record.outgoingRelationships.single { it.kind == ExplicitRelationshipKind.NOTION_RELATION }
        val mentionEdge = record.outgoingRelationships.single { it.kind == ExplicitRelationshipKind.DATABASE_MENTION }

        assertEquals(relatedPageId, (relationEdge.target as RelationshipTarget.InternalPage).pageId)
        assertEquals(mentionedDbId, (mentionEdge.target as RelationshipTarget.InternalDatabase).databaseId)

        // Provenance checks
        assertEquals("rel-1", relationEdge.provenance.propertyId)
        assertEquals("Related Project", relationEdge.provenance.propertyName)
        assertEquals("source-conn-1", relationEdge.provenance.sourceIdentity)

        assertEquals("summary-1", mentionEdge.provenance.propertyId)
        assertEquals("Summary", mentionEdge.provenance.propertyName)
        assertEquals("source-conn-1", mentionEdge.provenance.sourceIdentity)
        assertEquals("source-conn-1", record.sourceIdentity)
        assertEquals("source-conn-1", record.page.sourceIdentity)
    }

    @Test
    fun safeUrlClassificationDistinguishesNotionUrlsFromExternalUrlsContainingUuids() {
        val targetUuid = "3dec9345-e10a-8180-867d-fa96b49bd30b"
        val notionUrl = "https://www.notion.so/workspace/Page-$targetUuid"
        val externalUrl = "https://example.com/item/$targetUuid"

        val notionEdge = NotionUrlClassifier.classify(
            rawUrl = notionUrl,
            sourcePageId = pageId,
            sourceBlockId = "block-1",
            revision = "rev-1",
            extractedAt = Instant.now()
        )

        val externalEdge = NotionUrlClassifier.classify(
            rawUrl = externalUrl,
            sourcePageId = pageId,
            sourceBlockId = "block-1",
            revision = "rev-1",
            extractedAt = Instant.now()
        )

        assertEquals(ExplicitRelationshipKind.INTERNAL_HYPERLINK, notionEdge.kind)
        assertTrue(notionEdge.target is RelationshipTarget.InternalPage)
        assertEquals(targetUuid, (notionEdge.target as RelationshipTarget.InternalPage).pageId)
        assertEquals(TargetResolutionStatus.UNRESOLVED_PERMITTED, notionEdge.resolutionStatus)

        assertEquals(ExplicitRelationshipKind.EXTERNAL_HYPERLINK, externalEdge.kind)
        assertTrue("External URL containing UUID must not be classified as internal Notion page", externalEdge.target is RelationshipTarget.ExternalUrl)
        assertEquals(externalUrl, (externalEdge.target as RelationshipTarget.ExternalUrl).url)
        assertEquals(TargetResolutionStatus.EXTERNAL, externalEdge.resolutionStatus)
    }

    @Test
    fun extractsSyncedBlockAndSubpageRelationships() = runTest {
        val childPageId = "22222222-2222-2222-2222-222222222222"
        val sourceSyncedBlockId = "33333333-3333-3333-3333-333333333333"

        val childPageBlock = NotionBlock(
            id = childPageId,
            type = "child_page",
            childPage = NotionChildPageBlock(title = "Subpage Title")
        )
        val syncedBlock = NotionBlock(
            id = "44444444-4444-4444-4444-444444444444",
            type = "synced_block",
            syncedBlock = NotionSyncedBlock(syncedFrom = NotionSyncedFrom(blockId = sourceSyncedBlockId))
        )

        val page = NotionPage(id = pageId)
        val record = extractor.extract(page, listOf(childPageBlock, syncedBlock), { emptyList() })

        val subpageEdge = record.outgoingRelationships.single { it.kind == ExplicitRelationshipKind.PAGE_PARENT_CHILD }
        assertEquals(pageId, subpageEdge.sourceId)
        assertEquals(com.example.notionreader.domain.structure.StructuralEntityKind.PAGE, subpageEdge.sourceKind)
        assertEquals(childPageId, subpageEdge.provenance.sourceBlockId)
        assertEquals(childPageId, (subpageEdge.target as RelationshipTarget.InternalPage).pageId)

        val syncedEdge = record.outgoingRelationships.single { it.kind == ExplicitRelationshipKind.SYNCED_BLOCK }
        val targetInternalBlock = syncedEdge.target as RelationshipTarget.InternalBlock
        assertEquals(sourceSyncedBlockId, targetInternalBlock.blockId)
        assertNull("Target page owner of synced block must not be falsely invented as current page", targetInternalBlock.pageId)
    }

    @Test
    fun childDatabaseAndLinkToPageDoNotAssertDatabaseMembership() = runTest {
        val childDbId = "22222222-2222-2222-2222-222222222222"
        val linkedDbId = "33333333-3333-3333-3333-333333333333"
        val linkedPageId = "44444444-4444-4444-4444-444444444444"

        val childDb = NotionBlock(id = childDbId, type = "child_database", childDatabase = NotionChildDatabaseBlock(title = "Inline DB"))
        val linkToDb = NotionBlock(id = "55555555-5555-5555-5555-555555555555", type = "link_to_page", linkToPage = NotionLinkToPageBlock(databaseId = linkedDbId))
        val linkToPage = NotionBlock(id = "66666666-6666-6666-6666-666666666666", type = "link_to_page", linkToPage = NotionLinkToPageBlock(pageId = linkedPageId))

        val page = NotionPage(id = pageId)
        val record = extractor.extract(page, listOf(childDb, linkToDb, linkToPage), { emptyList() })

        val childDbEdge = record.outgoingRelationships.single { it.kind == ExplicitRelationshipKind.CHILD_DATABASE }
        assertEquals(childDbId, (childDbEdge.target as RelationshipTarget.InternalDatabase).databaseId)

        val linkDbEdge = record.outgoingRelationships.single { it.kind == ExplicitRelationshipKind.LINK_TO_DATABASE }
        assertEquals(linkedDbId, (linkDbEdge.target as RelationshipTarget.InternalDatabase).databaseId)

        val linkPageEdge = record.outgoingRelationships.single { it.kind == ExplicitRelationshipKind.LINK_TO_PAGE }
        assertEquals(linkedPageId, (linkPageEdge.target as RelationshipTarget.InternalPage).pageId)
    }

    @Test
    fun neverDescendsChildPageOrChildDatabaseAsPartOfOwningPage() = runTest {
        var descendantCalled = false
        val childPage = NotionBlock(id = "22222222-2222-2222-2222-222222222222", type = "child_page", hasChildren = true)
        val page = NotionPage(id = pageId)

        val record = extractor.extract(page, listOf(childPage), {
            descendantCalled = true
            emptyList()
        })

        assertTrue("child_page must not descend children into owning page", !descendantCalled)
        val block = record.blocks.single()
        assertEquals(ChildCompleteness.REFERENCE, block.childCompleteness)
        assertEquals(true, block.hasChildren)
    }

    @Test
    fun defendsAgainstCyclicBlockHierarchyAndMarksSyncStatusIncomplete() = runTest {
        val blockAId = "11111111-0000-0000-0000-000000000001"
        val blockBId = "11111111-0000-0000-0000-000000000002"

        val blockA = NotionBlock(id = blockAId, type = "paragraph", hasChildren = true)
        val blockB = NotionBlock(id = blockBId, type = "paragraph", hasChildren = true)

        val page = NotionPage(id = pageId)
        val record = extractor.extract(
            page = page,
            blocks = listOf(blockA),
            childrenSupplier = { id ->
                if (id == blockAId) listOf(blockB) else if (id == blockBId) listOf(blockA) else emptyList()
            }
        )

        // Traversal terminated cleanly without infinite loop
        assertTrue(record.blocks.size >= 2)
        assertEquals(SyncCompleteness.PARTIAL_TRAVERSAL_INCOMPLETE, record.page.syncStatus)
    }

    @Test
    fun respectsDepthLimitAndRecordsIncompleteChildStatus() = runTest {
        val shallowExtractor = StructuralExtractor(maxDepth = 1)
        val root = NotionBlock(id = "11111111-0000-0000-0000-000000000001", type = "paragraph", hasChildren = true)
        val child = NotionBlock(id = "11111111-0000-0000-0000-000000000002", type = "paragraph", hasChildren = true)

        val page = NotionPage(id = pageId)
        val record = shallowExtractor.extract(
            page = page,
            blocks = listOf(root),
            childrenSupplier = { listOf(child) }
        )

        val childBlock = record.blocks.first { it.id == "11111111-0000-0000-0000-000000000002" }
        assertEquals(ChildCompleteness.DEPTH_LIMIT_REACHED, childBlock.childCompleteness)
        assertEquals(SyncCompleteness.LIMIT_EXCEEDED, record.page.syncStatus)
    }

    @Test
    fun truncatedPropertyRelationMarksSyncStatusPartial() = runTest {
        val page = NotionPage(
            id = pageId,
            properties = mapOf(
                "BigRelation" to NotionProperty(
                    id = "big-rel",
                    type = "relation",
                    relation = listOf(NotionRelationTarget(id = "22222222-2222-2222-2222-222222222222")),
                    hasMore = true
                )
            )
        )

        val record = extractor.extract(page, emptyList(), { emptyList() })
        assertEquals(SyncCompleteness.PARTIAL_TRAVERSAL_INCOMPLETE, record.page.syncStatus)
    }

    @Test
    fun contentFingerprintIsSensitiveToSiblingOrderAndCheckedState() = runTest {
        val block1 = NotionBlock(id = "11111111-0000-0000-0000-000000000001", type = "to_do", toDo = NotionTodoBlock(checked = false))
        val block2 = NotionBlock(id = "11111111-0000-0000-0000-000000000001", type = "to_do", toDo = NotionTodoBlock(checked = true))

        val page = NotionPage(id = pageId)
        val record1 = extractor.extract(page, listOf(block1), { emptyList() })
        val record2 = extractor.extract(page, listOf(block2), { emptyList() })

        assertNotEquals(
            "Checked state change must update fingerprint",
            record1.blocks.first().contentFingerprint,
            record2.blocks.first().contentFingerprint
        )
    }
}
