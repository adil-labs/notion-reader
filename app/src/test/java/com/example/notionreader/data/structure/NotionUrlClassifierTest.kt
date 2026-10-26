package com.example.notionreader.data.structure

import com.example.notionreader.domain.structure.ExplicitRelationshipKind
import com.example.notionreader.domain.structure.RelationshipTarget
import com.example.notionreader.domain.structure.TargetResolutionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NotionUrlClassifierTest {

    @Test
    fun anAbsoluteBlockLinkWithoutAPageIdentityDoesNotInventItsOwner() {
        val edge = NotionUrlClassifier.classify("https://notion.so/#44444444444444444444444444444444",
            "11111111-1111-1111-1111-111111111111", null, "rev", Instant.EPOCH)
        assertEquals(RelationshipTarget.InternalBlock("44444444-4444-4444-4444-444444444444", null), edge.target)
    }

    @Test
    fun anUnrecognizedNotionRouteRemainsAnUnresolvedInternalReference() {
        val edge = NotionUrlClassifier.classify("https://www.notion.so/some-new-route",
            "11111111-1111-1111-1111-111111111111", null, "rev", Instant.EPOCH)
        assertEquals(ExplicitRelationshipKind.INTERNAL_HYPERLINK, edge.kind)
        assertEquals(TargetResolutionStatus.UNRESOLVED_PERMITTED, edge.resolutionStatus)
        assertTrue(edge.target is RelationshipTarget.Unresolved)
    }

    private val sourcePageId = "11111111-1111-1111-1111-111111111111"
    private val targetUuid = "3dec9345-e10a-8180-867d-fa96b49bd30b"
    private val blockUuid = "44444444-4444-4444-4444-444444444444"

    @Test
    fun classifiesAppNotionComAsInternal() {
        val url = "https://app.notion.com/workspace/Page-$targetUuid"
        val edge = NotionUrlClassifier.classify(
            rawUrl = url,
            sourcePageId = sourcePageId,
            sourceBlockId = null,
            revision = "rev-1",
            extractedAt = Instant.now()
        )

        assertEquals(ExplicitRelationshipKind.INTERNAL_HYPERLINK, edge.kind)
        assertTrue(edge.target is RelationshipTarget.InternalPage)
        assertEquals(targetUuid, (edge.target as RelationshipTarget.InternalPage).pageId)
        assertEquals(TargetResolutionStatus.UNRESOLVED_PERMITTED, edge.resolutionStatus)
    }

    @Test
    fun classifiesNotionSiteAsInternal() {
        val url = "https://myworkspace.notion.site/Page-$targetUuid"
        val edge = NotionUrlClassifier.classify(
            rawUrl = url,
            sourcePageId = sourcePageId,
            sourceBlockId = null,
            revision = "rev-1",
            extractedAt = Instant.now()
        )

        assertEquals(ExplicitRelationshipKind.INTERNAL_HYPERLINK, edge.kind)
        assertEquals(targetUuid, (edge.target as RelationshipTarget.InternalPage).pageId)
    }

    @Test
    fun classifiesRelativePathAsInternal() {
        val url = "/workspace/Page-$targetUuid"
        val edge = NotionUrlClassifier.classify(
            rawUrl = url,
            sourcePageId = sourcePageId,
            sourceBlockId = null,
            revision = "rev-1",
            extractedAt = Instant.now()
        )

        assertEquals(ExplicitRelationshipKind.INTERNAL_HYPERLINK, edge.kind)
        assertEquals(targetUuid, (edge.target as RelationshipTarget.InternalPage).pageId)
    }

    @Test
    fun crossPageBlockAnchorAssignsPathPageAsTargetPageNotSourcePage() {
        val url = "https://www.notion.so/workspace/TargetPage-$targetUuid#$blockUuid"
        val edge = NotionUrlClassifier.classify(
            rawUrl = url,
            sourcePageId = sourcePageId,
            sourceBlockId = null,
            revision = "rev-1",
            extractedAt = Instant.now()
        )

        assertEquals(ExplicitRelationshipKind.INTERNAL_HYPERLINK, edge.kind)
        assertTrue(edge.target is RelationshipTarget.InternalBlock)
        val target = edge.target as RelationshipTarget.InternalBlock
        assertEquals(blockUuid, target.blockId)
        assertEquals("Target page must come from URI path, not sourcePageId", targetUuid, target.pageId)
    }

    @Test
    fun fragmentOnlyBlockAnchorAssignsSourcePageAsTargetPage() {
        val url = "#$blockUuid"
        val edge = NotionUrlClassifier.classify(
            rawUrl = url,
            sourcePageId = sourcePageId,
            sourceBlockId = null,
            revision = "rev-1",
            extractedAt = Instant.now()
        )

        assertEquals(ExplicitRelationshipKind.INTERNAL_HYPERLINK, edge.kind)
        assertTrue(edge.target is RelationshipTarget.InternalBlock)
        val target = edge.target as RelationshipTarget.InternalBlock
        assertEquals(blockUuid, target.blockId)
        assertEquals("Fragment-only target page must default to sourcePageId", sourcePageId, target.pageId)
    }

    @Test
    fun rejectsNonHttpSchemesAndOpaqueUris() {
        val mailto = "mailto:user@notion.so"
        val edge = NotionUrlClassifier.classify(
            rawUrl = mailto,
            sourcePageId = sourcePageId,
            sourceBlockId = null,
            revision = "rev-1",
            extractedAt = Instant.now()
        )

        assertEquals(ExplicitRelationshipKind.EXTERNAL_HYPERLINK, edge.kind)
        assertTrue(edge.target is RelationshipTarget.ExternalUrl)
        assertEquals(TargetResolutionStatus.EXTERNAL, edge.resolutionStatus)
    }

    @Test
    fun externalUrlContainingUuidIsNeverClassifiedAsInternal() {
        val external = "https://example.com/items/$targetUuid"
        val edge = NotionUrlClassifier.classify(
            rawUrl = external,
            sourcePageId = sourcePageId,
            sourceBlockId = null,
            revision = "rev-1",
            extractedAt = Instant.now()
        )

        assertEquals(ExplicitRelationshipKind.EXTERNAL_HYPERLINK, edge.kind)
        assertTrue(edge.target is RelationshipTarget.ExternalUrl)
        assertEquals(TargetResolutionStatus.EXTERNAL, edge.resolutionStatus)
    }

    @Test
    fun stableEdgeIdIsCollisionResistantAcrossPropertiesAndOccurrences() {
        val id1 = NotionUrlClassifier.stableEdgeId(
            sourceId = sourcePageId,
            targetKey = targetUuid,
            kind = ExplicitRelationshipKind.NOTION_RELATION,
            propertyId = "prop-A",
            occurrence = 0
        )
        val id2 = NotionUrlClassifier.stableEdgeId(
            sourceId = sourcePageId,
            targetKey = targetUuid,
            kind = ExplicitRelationshipKind.NOTION_RELATION,
            propertyId = "prop-B",
            occurrence = 0
        )
        val id3 = NotionUrlClassifier.stableEdgeId(
            sourceId = sourcePageId,
            targetKey = targetUuid,
            kind = ExplicitRelationshipKind.NOTION_RELATION,
            propertyId = "prop-A",
            occurrence = 1
        )

        assertNotEquals(id1, id2)
        assertNotEquals(id1, id3)
        assertEquals(id1, NotionUrlClassifier.stableEdgeId(sourcePageId, targetUuid, ExplicitRelationshipKind.NOTION_RELATION, "prop-A", 0))
    }
}
