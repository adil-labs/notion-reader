package com.example.notionreader.data.structure

import com.example.notionreader.data.notion.NotionBlock
import com.example.notionreader.data.notion.NotionPage
import com.example.notionreader.data.notion.NotionRichText
import com.example.notionreader.data.notion.title
import com.example.notionreader.data.content.DEFAULT_MAX_BLOCK_DEPTH
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
import com.example.notionreader.domain.structure.ChildCompleteness
import com.example.notionreader.domain.structure.ExplicitRelationship
import com.example.notionreader.domain.structure.ExplicitRelationshipKind
import com.example.notionreader.domain.structure.PageAccessibilityState
import com.example.notionreader.domain.structure.RelationshipProvenance
import com.example.notionreader.domain.structure.RelationshipTarget
import com.example.notionreader.domain.structure.StructuralBlock
import com.example.notionreader.domain.structure.StructuralEntityKind
import com.example.notionreader.domain.structure.StructuralNavigationAnchor
import com.example.notionreader.domain.structure.StructuralPage
import com.example.notionreader.domain.structure.StructuralPageRecord
import com.example.notionreader.domain.structure.StructuralParent
import com.example.notionreader.domain.structure.StructuralSection
import com.example.notionreader.domain.structure.SyncCompleteness
import com.example.notionreader.domain.structure.TargetResolutionStatus
import com.example.notionreader.domain.structure.normalizeNotionId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import java.time.Instant

/**
 * Extracts normalized, authoritative structural knowledge records from Notion API entities.
 * Enforces cycle defense, bounded traversal, deterministic section paths, and safe URL classification.
 */
class StructuralExtractor(
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val maxBlocks: Int = 5000
) {
    init { require(maxDepth in 0..64 && maxBlocks in 1..10000) }

    suspend fun extract(
        page: NotionPage,
        blocks: List<NotionBlock>,
        childrenSupplier: suspend (String) -> List<NotionBlock>,
        sourceIdentity: String = "",
        extractedAt: Instant = Instant.now()
    ): StructuralPageRecord {
        val canonicalPageId = normalizeNotionId(page.id) ?: throw ReaderLoadException(ReaderErrorCategory.MALFORMED_RESPONSE)
        val pageTitle = page.title()
        val revision = page.lastEditedTime ?: page.createdTime ?: ""

        // 1. Extract StructuralParent and parent membership edges
        val (structuralParent, databaseId, dataSourceId, parentEdges) = extractParent(
            page = page,
            pageId = canonicalPageId,
            revision = revision,
            sourceIdentity = sourceIdentity,
            extractedAt = extractedAt
        )

        // 2. Extract property-level relationships (relations, URLs, mentions)
        val propertyEdges = extractPropertyRelationships(
            page = page,
            pageId = canonicalPageId,
            revision = revision,
            sourceIdentity = sourceIdentity,
            extractedAt = extractedAt
        )

        // 3. Recursively extract blocks, section paths, and block-level relationships
        val visitedIds = mutableSetOf<String>()
        var cycleDetected = false
        val normalizedBlocks = mutableListOf<NormalizedStructuralBlock>()
        var examinedBlocks = 0
        val claimBlock = { ++examinedBlocks <= maxBlocks }

        for ((index, block) in blocks.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (!claimBlock()) { cycleDetected = true; break }
            normalizedBlocks += normalizeBlockRecursive(
                block = block,
                pageId = canonicalPageId,
                parentBlockId = null,
                siblingOrder = index,
                depth = 0,
                readerEligible = true,
                visitedIds = visitedIds,
                onCycle = { cycleDetected = true },
                claimBlock = claimBlock,
                childrenSupplier = childrenSupplier,
                revision = revision,
                sourceIdentity = sourceIdentity,
                extractedAt = extractedAt
            )
        }

        // 4. Flatten blocks & collect block-level relationships
        val flattenedStructuralBlocks = mutableListOf<StructuralBlock>()
        val blockEdges = mutableListOf<ExplicitRelationship>()
        flattenBlocks(normalizedBlocks, canonicalPageId, flattenedStructuralBlocks, blockEdges)

        // 5. Reconstruct sections and heading hierarchy paths (matching ReaderDocumentMapper)
        val sections = reconstructSections(canonicalPageId, pageTitle, normalizedBlocks)

        // Aggregate incompleteness across descendants and truncated properties
        val hasTruncatedProperties = page.properties.values.any { it.hasMore }
        val isTreeIncomplete = cycleDetected || hasTruncatedProperties || isAnyIncomplete(normalizedBlocks)

        val pageRecord = StructuralPage(
            id = canonicalPageId,
            title = pageTitle,
            parent = structuralParent,
            databaseId = databaseId,
            dataSourceId = dataSourceId,
            sourceIdentity = sourceIdentity,
            createdAt = page.createdTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
            lastEditedAt = page.lastEditedTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
            accessibilityState = if (page.isTrashed) PageAccessibilityState.CONFIRMED_TRASHED else PageAccessibilityState.ACCESSIBLE,
            sourceRevision = revision,
            syncStatus = if (isTreeIncomplete) SyncCompleteness.PARTIAL_TRAVERSAL_INCOMPLETE else SyncCompleteness.COMPLETE
        )

        val allEdges = parentEdges + propertyEdges + blockEdges

        return StructuralPageRecord(
            page = pageRecord,
            blocks = flattenedStructuralBlocks,
            sections = sections,
            outgoingRelationships = allEdges,
            sourceIdentity = sourceIdentity
        )
    }

    private fun isAnyIncomplete(nodes: List<NormalizedStructuralBlock>): Boolean {
        for (node in nodes) {
            if (node.childCompleteness !in setOf(ChildCompleteness.LEAF, ChildCompleteness.COMPLETE, ChildCompleteness.REFERENCE)) {
                return true
            }
            if (isAnyIncomplete(node.children)) return true
        }
        return false
    }

    private fun extractParent(
        page: NotionPage,
        pageId: String,
        revision: String,
        sourceIdentity: String,
        extractedAt: Instant
    ): Tuple4<StructuralParent, String?, String?, List<ExplicitRelationship>> {
        val parent = page.parent
        val edges = mutableListOf<ExplicitRelationship>()
        val provenance = RelationshipProvenance(
            sourcePageId = pageId,
            sourceIdentity = sourceIdentity,
            observedRevision = revision,
            extractedAt = extractedAt
        )

        if (parent == null) {
            return Tuple4(StructuralParent.Unknown("none", null), null, null, emptyList())
        }

        val type = parent.type.lowercase()
        return when {
            type == "page_id" && parent.pageId != null -> {
                val canonical = normalizeNotionId(parent.pageId) ?: parent.pageId
                val edgeId = NotionUrlClassifier.stableEdgeId(canonical, pageId, ExplicitRelationshipKind.PAGE_PARENT_CHILD, propertyId = "parent")
                edges += ExplicitRelationship(
                    id = edgeId,
                    sourceId = canonical,
                    sourceKind = StructuralEntityKind.PAGE,
                    kind = ExplicitRelationshipKind.PAGE_PARENT_CHILD,
                    target = RelationshipTarget.InternalPage(pageId),
                    provenance = provenance,
                    resolutionStatus = TargetResolutionStatus.RESOLVED
                )
                Tuple4(StructuralParent.Page(canonical), null, null, edges)
            }
            type == "database_id" && parent.databaseId != null -> {
                val canonical = normalizeNotionId(parent.databaseId) ?: parent.databaseId
                val edgeId = NotionUrlClassifier.stableEdgeId(pageId, canonical, ExplicitRelationshipKind.DATABASE_MEMBERSHIP)
                edges += ExplicitRelationship(
                    id = edgeId,
                    sourceId = pageId,
                    sourceKind = StructuralEntityKind.PAGE,
                    kind = ExplicitRelationshipKind.DATABASE_MEMBERSHIP,
                    target = RelationshipTarget.InternalDatabase(canonical),
                    provenance = provenance,
                    resolutionStatus = TargetResolutionStatus.UNRESOLVED_PERMITTED
                )
                Tuple4(StructuralParent.Database(canonical), canonical, null, edges)
            }
            type == "data_source_id" && parent.dataSourceId != null -> {
                val canonical = normalizeNotionId(parent.dataSourceId) ?: parent.dataSourceId
                val edgeId = NotionUrlClassifier.stableEdgeId(pageId, canonical, ExplicitRelationshipKind.DATA_SOURCE_MEMBERSHIP)
                edges += ExplicitRelationship(
                    id = edgeId,
                    sourceId = pageId,
                    sourceKind = StructuralEntityKind.PAGE,
                    kind = ExplicitRelationshipKind.DATA_SOURCE_MEMBERSHIP,
                    target = RelationshipTarget.InternalDataSource(canonical),
                    provenance = provenance,
                    resolutionStatus = TargetResolutionStatus.UNRESOLVED_PERMITTED
                )
                Tuple4(StructuralParent.DataSource(canonical), null, canonical, edges)
            }
            type == "block_id" && parent.blockId != null -> {
                val canonical = normalizeNotionId(parent.blockId) ?: parent.blockId
                val edgeId = NotionUrlClassifier.stableEdgeId(pageId, canonical, ExplicitRelationshipKind.BLOCK_PARENT_CHILD)
                edges += ExplicitRelationship(
                    id = edgeId,
                    sourceId = pageId,
                    sourceKind = StructuralEntityKind.PAGE,
                    kind = ExplicitRelationshipKind.BLOCK_PARENT_CHILD,
                    target = RelationshipTarget.InternalBlock(blockId = canonical, pageId = null),
                    provenance = provenance,
                    resolutionStatus = TargetResolutionStatus.UNRESOLVED_PERMITTED
                )
                Tuple4(StructuralParent.Block(canonical), null, null, edges)
            }
            parent.workspace || type == "workspace" -> {
                Tuple4(StructuralParent.Workspace, null, null, emptyList())
            }
            else -> {
                val rawId = parent.pageId ?: parent.databaseId ?: parent.dataSourceId ?: parent.blockId
                Tuple4(StructuralParent.Unknown(parent.type, rawId), null, null, emptyList())
            }
        }
    }

    private fun extractPropertyRelationships(
        page: NotionPage,
        pageId: String,
        revision: String,
        sourceIdentity: String,
        extractedAt: Instant
    ): List<ExplicitRelationship> {
        val edges = mutableListOf<ExplicitRelationship>()

        for ((propName, prop) in page.properties) {
            val propertyId = prop.id ?: propName
            val provenance = RelationshipProvenance(
                sourcePageId = pageId,
                propertyId = propertyId,
                propertyName = propName,
                sourceIdentity = sourceIdentity,
                observedRevision = revision,
                extractedAt = extractedAt
            )

            // 1. Notion Relation property
            for ((relIndex, relationTarget) in prop.relation.withIndex()) {
                val canonical = normalizeNotionId(relationTarget.id) ?: relationTarget.id
                if (canonical.isNotBlank()) {
                    val edgeId = NotionUrlClassifier.stableEdgeId(
                        sourceId = pageId,
                        targetKey = canonical,
                        kind = ExplicitRelationshipKind.NOTION_RELATION,
                        propertyId = propertyId,
                        occurrence = relIndex
                    )
                    edges += ExplicitRelationship(
                        id = edgeId,
                        sourceId = pageId,
                        sourceKind = StructuralEntityKind.PROPERTY,
                        kind = ExplicitRelationshipKind.NOTION_RELATION,
                        target = RelationshipTarget.InternalPage(canonical),
                        provenance = provenance,
                        resolutionStatus = TargetResolutionStatus.UNRESOLVED_PERMITTED
                    )
                }
            }

            // 2. Direct URL property
            if (!prop.url.isNullOrBlank()) {
                edges += NotionUrlClassifier.classify(
                    rawUrl = prop.url,
                    sourcePageId = pageId,
                    sourceBlockId = null,
                    revision = revision,
                    extractedAt = extractedAt,
                    propertyId = propertyId,
                    propertyName = propName,
                    sourceIdentity = sourceIdentity,
                    occurrence = 0
                )
            }

            // 3. Rich text mentions & URLs in property
            for ((rtIndex, rt) in (prop.richText + prop.title).withIndex()) {
                edges += extractRichTextRelationships(
                    rt = rt,
                    pageId = pageId,
                    sourceBlockId = null,
                    revision = revision,
                    sourceIdentity = sourceIdentity,
                    extractedAt = extractedAt,
                    propertyId = propertyId,
                    propertyName = propName,
                    occurrence = rtIndex
                )
            }
        }

        return edges
    }

    private suspend fun normalizeBlockRecursive(
        block: NotionBlock,
        pageId: String,
        parentBlockId: String?,
        siblingOrder: Int,
        depth: Int,
        readerEligible: Boolean,
        visitedIds: MutableSet<String>,
        onCycle: () -> Unit,
        claimBlock: () -> Boolean,
        childrenSupplier: suspend (String) -> List<NotionBlock>,
        revision: String,
        sourceIdentity: String,
        extractedAt: Instant
    ): NormalizedStructuralBlock {
        val canonicalBlockId = normalizeNotionId(block.id) ?: throw ReaderLoadException(ReaderErrorCategory.MALFORMED_RESPONSE,
            "Structural block identity is malformed")
        val blockEdges = mutableListOf<ExplicitRelationship>()
        val provenance = RelationshipProvenance(
            sourcePageId = pageId,
            sourceBlockId = canonicalBlockId,
            sourceIdentity = sourceIdentity,
            observedRevision = revision,
            extractedAt = extractedAt
        )

        // Cycle defense: cycles must mark incomplete
        if (!visitedIds.add(canonicalBlockId)) {
            onCycle()
            return NormalizedStructuralBlock(
                id = canonicalBlockId,
                type = block.type,
                parentBlockId = parentBlockId,
                siblingOrder = siblingOrder,
                text = "",
                contentFingerprint = "cycle:${canonicalBlockId}",
                childCompleteness = ChildCompleteness.DEPTH_LIMIT_REACHED,
                hasChildren = false
            )
        }

        var headingLevel = 0
        var isSpeakable = true
        var text = ""
        var extraFingerprint = ""

        when (block.type) {
            "heading_1" -> {
                headingLevel = 1
                text = block.heading1?.richText?.joinPlainText().orEmpty()
                extraFingerprint = "level=1"
                block.heading1?.richText?.forEachIndexed { i, it ->
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt, occurrence = i)
                }
            }
            "heading_2" -> {
                headingLevel = 2
                text = block.heading2?.richText?.joinPlainText().orEmpty()
                extraFingerprint = "level=2"
                block.heading2?.richText?.forEachIndexed { i, it ->
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt, occurrence = i)
                }
            }
            "heading_3" -> {
                headingLevel = 3
                text = block.heading3?.richText?.joinPlainText().orEmpty()
                extraFingerprint = "level=3"
                block.heading3?.richText?.forEachIndexed { i, it ->
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt, occurrence = i)
                }
            }
            "heading_4" -> {
                // heading_4 carries additive structural info; does not alter reader speech coverage
                headingLevel = 4
                isSpeakable = false
                text = block.heading4?.richText?.joinPlainText().orEmpty()
                extraFingerprint = "level=4"
                block.heading4?.richText?.forEachIndexed { i, it ->
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt, occurrence = i)
                }
            }
            "paragraph" -> {
                text = block.paragraph?.richText?.joinPlainText().orEmpty()
                block.paragraph?.richText?.forEachIndexed { i, it ->
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt, occurrence = i)
                }
            }
            "bulleted_list_item" -> {
                text = block.bulletedListItem?.richText?.joinPlainText().orEmpty()
                block.bulletedListItem?.richText?.forEachIndexed { i, it ->
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt, occurrence = i)
                }
            }
            "numbered_list_item" -> {
                text = block.numberedListItem?.richText?.joinPlainText().orEmpty()
                block.numberedListItem?.richText?.forEachIndexed { i, it ->
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt, occurrence = i)
                }
            }
            "to_do" -> {
                text = block.toDo?.richText?.joinPlainText().orEmpty()
                extraFingerprint = "checked=${block.toDo?.checked}"
                block.toDo?.richText?.forEachIndexed { i, it ->
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt, occurrence = i)
                }
            }
            "quote" -> {
                text = block.quote?.richText?.joinPlainText().orEmpty()
                block.quote?.richText?.forEachIndexed { i, it ->
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt, occurrence = i)
                }
            }
            "code" -> {
                text = block.code?.richText?.joinPlainText().orEmpty()
                extraFingerprint = "lang=${block.code?.language}:caption=${block.code?.caption?.joinPlainText()}"
                block.code?.richText?.forEachIndexed { i, it ->
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt, occurrence = i)
                }
                block.code?.caption?.forEachIndexed { i, it ->
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt,
                        occurrence = -(i + 1))
                }
            }
            "callout" -> {
                text = block.callout?.richText?.joinPlainText().orEmpty()
                block.callout?.richText?.forEachIndexed { i, it ->
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt, occurrence = i)
                }
            }
            "toggle" -> {
                text = block.toggle?.richText?.joinPlainText().orEmpty()
                block.toggle?.richText?.forEachIndexed { i, it ->
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt, occurrence = i)
                }
            }
            "child_page" -> {
                isSpeakable = false
                val childTitle = block.childPage?.title.orEmpty()
                text = childTitle
                extraFingerprint = "title=$childTitle"
                val edgeId = NotionUrlClassifier.stableEdgeId(pageId, canonicalBlockId, ExplicitRelationshipKind.PAGE_PARENT_CHILD, propertyId = canonicalBlockId)
                blockEdges += ExplicitRelationship(
                    id = edgeId,
                    sourceId = pageId,
                    sourceKind = StructuralEntityKind.PAGE,
                    kind = ExplicitRelationshipKind.PAGE_PARENT_CHILD,
                    target = RelationshipTarget.InternalPage(canonicalBlockId),
                    provenance = provenance,
                    resolutionStatus = TargetResolutionStatus.UNRESOLVED_PERMITTED
                )
            }
            "child_database" -> {
                isSpeakable = false
                val dbTitle = block.childDatabase?.title.orEmpty()
                text = dbTitle
                extraFingerprint = "title=$dbTitle"
                val edgeId = NotionUrlClassifier.stableEdgeId(canonicalBlockId, canonicalBlockId, ExplicitRelationshipKind.CHILD_DATABASE)
                blockEdges += ExplicitRelationship(
                    id = edgeId,
                    sourceId = canonicalBlockId,
                    sourceKind = StructuralEntityKind.BLOCK,
                    kind = ExplicitRelationshipKind.CHILD_DATABASE,
                    target = RelationshipTarget.InternalDatabase(canonicalBlockId),
                    provenance = provenance,
                    resolutionStatus = TargetResolutionStatus.UNRESOLVED_PERMITTED
                )
            }
            "synced_block" -> {
                isSpeakable = false
                val sourceBlockRef = block.syncedBlock?.syncedFrom?.blockId
                if (!sourceBlockRef.isNullOrBlank()) {
                    val canonicalRef = normalizeNotionId(sourceBlockRef) ?: sourceBlockRef
                    extraFingerprint = "synced_from=$canonicalRef"
                    val edgeId = NotionUrlClassifier.stableEdgeId(canonicalBlockId, canonicalRef, ExplicitRelationshipKind.SYNCED_BLOCK)
                    blockEdges += ExplicitRelationship(
                        id = edgeId,
                        sourceId = canonicalBlockId,
                        sourceKind = StructuralEntityKind.BLOCK,
                        kind = ExplicitRelationshipKind.SYNCED_BLOCK,
                        target = RelationshipTarget.InternalBlock(blockId = canonicalRef, pageId = null),
                        provenance = provenance,
                        resolutionStatus = TargetResolutionStatus.UNRESOLVED_PERMITTED
                    )
                }
            }
            "bookmark" -> {
                isSpeakable = false
                val url = block.bookmark?.url.orEmpty()
                extraFingerprint = "url=$url"
                if (url.isNotBlank()) {
                    blockEdges += NotionUrlClassifier.classify(url, pageId, canonicalBlockId, revision, extractedAt, sourceIdentity = sourceIdentity, occurrence = -1)
                }
                block.bookmark?.caption?.forEachIndexed { i, it ->
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt, occurrence = i)
                }
            }
            "link_preview" -> {
                isSpeakable = false
                val url = block.linkPreview?.url.orEmpty()
                extraFingerprint = "url=$url"
                if (url.isNotBlank()) {
                    blockEdges += NotionUrlClassifier.classify(url, pageId, canonicalBlockId, revision, extractedAt, sourceIdentity = sourceIdentity, occurrence = -1)
                }
                block.linkPreview?.caption?.forEachIndexed { i, it ->
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt, occurrence = i)
                }
            }
            "link_to_page" -> {
                isSpeakable = false
                val targetPage = block.linkToPage?.pageId?.let { normalizeNotionId(it) ?: it }
                val targetDb = block.linkToPage?.databaseId?.let { normalizeNotionId(it) ?: it }
                if (targetPage != null) {
                    extraFingerprint = "page=$targetPage"
                    val edgeId = NotionUrlClassifier.stableEdgeId(canonicalBlockId, targetPage, ExplicitRelationshipKind.LINK_TO_PAGE)
                    blockEdges += ExplicitRelationship(
                        id = edgeId,
                        sourceId = canonicalBlockId,
                        sourceKind = StructuralEntityKind.BLOCK,
                        kind = ExplicitRelationshipKind.LINK_TO_PAGE,
                        target = RelationshipTarget.InternalPage(targetPage),
                        provenance = provenance,
                        resolutionStatus = TargetResolutionStatus.UNRESOLVED_PERMITTED
                    )
                } else if (targetDb != null) {
                    extraFingerprint = "db=$targetDb"
                    val edgeId = NotionUrlClassifier.stableEdgeId(canonicalBlockId, targetDb, ExplicitRelationshipKind.LINK_TO_DATABASE)
                    blockEdges += ExplicitRelationship(
                        id = edgeId,
                        sourceId = canonicalBlockId,
                        sourceKind = StructuralEntityKind.BLOCK,
                        kind = ExplicitRelationshipKind.LINK_TO_DATABASE,
                        target = RelationshipTarget.InternalDatabase(targetDb),
                        provenance = provenance,
                        resolutionStatus = TargetResolutionStatus.UNRESOLVED_PERMITTED
                    )
                }
            }
            "image" -> {
                isSpeakable = false
                val url = block.image?.file?.url ?: block.image?.external?.url
                extraFingerprint = "url=${url.orEmpty()}"
                if (!url.isNullOrBlank()) {
                    blockEdges += NotionUrlClassifier.classify(url, pageId, canonicalBlockId, revision, extractedAt, sourceIdentity = sourceIdentity, occurrence = -1)
                }
                block.image?.caption?.forEachIndexed { i, it ->
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt, occurrence = i)
                }
            }
            "video" -> {
                isSpeakable = false
                val url = block.video?.file?.url ?: block.video?.external?.url
                extraFingerprint = "url=${url.orEmpty()}"
                if (!url.isNullOrBlank()) {
                    blockEdges += NotionUrlClassifier.classify(url, pageId, canonicalBlockId, revision, extractedAt, sourceIdentity = sourceIdentity, occurrence = -1)
                }
                block.video?.caption?.forEachIndexed { i, it ->
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt, occurrence = i)
                }
            }
            "file" -> {
                isSpeakable = false
                val url = block.file?.file?.url ?: block.file?.external?.url
                extraFingerprint = "url=${url.orEmpty()}"
                if (!url.isNullOrBlank()) {
                    blockEdges += NotionUrlClassifier.classify(url, pageId, canonicalBlockId, revision, extractedAt, sourceIdentity = sourceIdentity, occurrence = -1)
                }
                block.file?.caption?.forEachIndexed { i, it ->
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt, occurrence = i)
                }
            }
            "pdf" -> {
                isSpeakable = false
                val url = block.pdf?.file?.url ?: block.pdf?.external?.url
                extraFingerprint = "url=${url.orEmpty()}"
                if (!url.isNullOrBlank()) {
                    blockEdges += NotionUrlClassifier.classify(url, pageId, canonicalBlockId, revision, extractedAt, sourceIdentity = sourceIdentity, occurrence = -1)
                }
                block.pdf?.caption?.forEachIndexed { i, it ->
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt, occurrence = i)
                }
            }
            "audio" -> {
                isSpeakable = false
                val url = block.audio?.file?.url ?: block.audio?.external?.url
                extraFingerprint = "url=${url.orEmpty()}"
                if (!url.isNullOrBlank()) {
                    blockEdges += NotionUrlClassifier.classify(url, pageId, canonicalBlockId, revision, extractedAt, sourceIdentity = sourceIdentity, occurrence = -1)
                }
                block.audio?.caption?.forEachIndexed { i, it ->
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt, occurrence = i)
                }
            }
            "embed" -> {
                isSpeakable = false
                val url = block.embed?.url.orEmpty()
                extraFingerprint = "url=$url"
                if (url.isNotBlank()) {
                    blockEdges += NotionUrlClassifier.classify(url, pageId, canonicalBlockId, revision, extractedAt, sourceIdentity = sourceIdentity, occurrence = -1)
                }
                block.embed?.caption?.forEachIndexed { i, it ->
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt, occurrence = i)
                }
            }
            "table" -> {
                isSpeakable = false
            }
            "table_row" -> {
                isSpeakable = false
                var cellIndex = 0
                block.tableRow?.cells?.flatten()?.forEach {
                    blockEdges += extractRichTextRelationships(it, pageId, canonicalBlockId, revision, sourceIdentity, extractedAt, occurrence = cellIndex++)
                }
            }
            "divider" -> {
                isSpeakable = false
            }
            else -> {
                isSpeakable = false
            }
        }

        // Never descend child_page or child_database as part of owning page
        val duplicateSyncedBlock = block.type == "synced_block" && block.syncedBlock?.syncedFrom?.blockId != null
        val isNonDescendedType = block.type in NON_DESCENDED_TYPES || duplicateSyncedBlock
        val shouldDescend = block.hasChildren && !isNonDescendedType

        var childCompleteness = when {
            isNonDescendedType -> ChildCompleteness.REFERENCE
            !block.hasChildren -> ChildCompleteness.LEAF
            depth >= maxDepth -> ChildCompleteness.DEPTH_LIMIT_REACHED
            else -> ChildCompleteness.COMPLETE
        }

        val childBlocks = mutableListOf<NormalizedStructuralBlock>()

        if (shouldDescend && depth < maxDepth) {
            val rawChildren = try {
                childrenSupplier(canonicalBlockId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ReaderLoadException) {
                if (failure.category in setOf(ReaderErrorCategory.PERMISSION_DENIED, ReaderErrorCategory.PAGE_NOT_FOUND,
                        ReaderErrorCategory.AUTHORIZATION_REQUIRED)) throw failure
                childCompleteness = ChildCompleteness.PAGINATION_INCOMPLETE
                emptyList()
            } catch (_: Exception) {
                childCompleteness = ChildCompleteness.PAGINATION_INCOMPLETE
                emptyList()
            }

            for ((childIndex, child) in rawChildren.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (!claimBlock()) {
                    childCompleteness = ChildCompleteness.PAGINATION_INCOMPLETE
                    break
                }
                childBlocks += normalizeBlockRecursive(
                    block = child,
                    pageId = pageId,
                    parentBlockId = canonicalBlockId,
                    siblingOrder = childIndex,
                    depth = depth + 1,
                    readerEligible = readerEligible && block.type in READER_DESCENDED_TYPES && depth < DEFAULT_MAX_BLOCK_DEPTH,
                    visitedIds = visitedIds,
                    onCycle = onCycle,
                    claimBlock = claimBlock,
                    childrenSupplier = childrenSupplier,
                    revision = revision,
                    sourceIdentity = sourceIdentity,
                    extractedAt = extractedAt
                )
            }
        }

        val fingerprint = computeFingerprint(
            type = block.type,
            id = canonicalBlockId,
            siblingOrder = siblingOrder,
            text = text,
            extra = "$extraFingerprint:${block.hasChildren}:$parentBlockId:${blockEdges.map { it.id }}"
        )

        blockEdges += ExplicitRelationship(
            id = NotionUrlClassifier.stableEdgeId(canonicalBlockId, parentBlockId ?: pageId, ExplicitRelationshipKind.BLOCK_PARENT_CHILD),
            sourceId = canonicalBlockId, sourceKind = StructuralEntityKind.BLOCK,
            kind = ExplicitRelationshipKind.BLOCK_PARENT_CHILD,
            target = parentBlockId?.let { RelationshipTarget.InternalBlock(blockId = it, pageId = pageId) }
                ?: RelationshipTarget.InternalPage(pageId),
            provenance = provenance, resolutionStatus = TargetResolutionStatus.RESOLVED
        )

        return NormalizedStructuralBlock(
            id = canonicalBlockId,
            type = block.type,
            parentBlockId = parentBlockId,
            siblingOrder = siblingOrder,
            text = text,
            headingLevel = headingLevel,
            isSpeakable = isSpeakable && readerEligible && !(depth >= DEFAULT_MAX_BLOCK_DEPTH && block.hasChildren),
            contentFingerprint = fingerprint,
            childCompleteness = childCompleteness,
            hasChildren = block.hasChildren,
            explicitEdges = blockEdges,
            children = childBlocks
        )
    }

    private fun extractRichTextRelationships(
        rt: NotionRichText,
        pageId: String,
        sourceBlockId: String?,
        revision: String,
        sourceIdentity: String,
        extractedAt: Instant,
        propertyId: String? = null,
        propertyName: String? = null,
        occurrence: Int = 0
    ): List<ExplicitRelationship> {
        val edges = mutableListOf<ExplicitRelationship>()
        val provenance = RelationshipProvenance(
            sourcePageId = pageId,
            sourceBlockId = sourceBlockId,
            propertyId = propertyId,
            propertyName = propertyName,
            sourceIdentity = sourceIdentity,
            observedRevision = revision,
            extractedAt = extractedAt
        )

        val sourceId = sourceBlockId ?: pageId
        val sourceKind = if (sourceBlockId != null) StructuralEntityKind.BLOCK else StructuralEntityKind.PAGE

        // 1. RichText mention (extract IDs from API, not href parses)
        val mention = rt.mention
        if (mention != null) {
            val mentionPageId = mention.page?.id?.let { normalizeNotionId(it) ?: it }
            val mentionDbId = mention.database?.id?.let { normalizeNotionId(it) ?: it }
            val mentionDsId = mention.dataSource?.id?.let { normalizeNotionId(it) ?: it }
            val linkPreviewUrl = mention.linkPreview?.url

            if (!mentionPageId.isNullOrBlank()) {
                val edgeId = NotionUrlClassifier.stableEdgeId(
                    sourceId = sourceId,
                    targetKey = mentionPageId,
                    kind = ExplicitRelationshipKind.PAGE_MENTION,
                    propertyId = propertyId,
                    occurrence = occurrence
                )
                edges += ExplicitRelationship(
                    id = edgeId,
                    sourceId = sourceId,
                    sourceKind = sourceKind,
                    kind = ExplicitRelationshipKind.PAGE_MENTION,
                    target = RelationshipTarget.InternalPage(mentionPageId),
                    provenance = provenance,
                    resolutionStatus = TargetResolutionStatus.UNRESOLVED_PERMITTED
                )
            } else if (!mentionDbId.isNullOrBlank()) {
                val edgeId = NotionUrlClassifier.stableEdgeId(
                    sourceId = sourceId,
                    targetKey = mentionDbId,
                    kind = ExplicitRelationshipKind.DATABASE_MENTION,
                    propertyId = propertyId,
                    occurrence = occurrence
                )
                edges += ExplicitRelationship(
                    id = edgeId,
                    sourceId = sourceId,
                    sourceKind = sourceKind,
                    kind = ExplicitRelationshipKind.DATABASE_MENTION,
                    target = RelationshipTarget.InternalDatabase(mentionDbId),
                    provenance = provenance,
                    resolutionStatus = TargetResolutionStatus.UNRESOLVED_PERMITTED
                )
            } else if (!mentionDsId.isNullOrBlank()) {
                val edgeId = NotionUrlClassifier.stableEdgeId(
                    sourceId = sourceId,
                    targetKey = mentionDsId,
                    kind = ExplicitRelationshipKind.DATA_SOURCE_MENTION,
                    propertyId = propertyId,
                    occurrence = occurrence
                )
                edges += ExplicitRelationship(
                    id = edgeId,
                    sourceId = sourceId,
                    sourceKind = sourceKind,
                    kind = ExplicitRelationshipKind.DATA_SOURCE_MENTION,
                    target = RelationshipTarget.InternalDataSource(mentionDsId),
                    provenance = provenance,
                    resolutionStatus = TargetResolutionStatus.UNRESOLVED_PERMITTED
                )
            } else if (!linkPreviewUrl.isNullOrBlank()) {
                edges += NotionUrlClassifier.classify(
                    rawUrl = linkPreviewUrl,
                    sourcePageId = pageId,
                    sourceBlockId = sourceBlockId,
                    revision = revision,
                    extractedAt = extractedAt,
                    propertyId = propertyId,
                    propertyName = propertyName,
                    sourceIdentity = sourceIdentity,
                    occurrence = occurrence
                )
            }
        }

        // 2. RichText href hyperlink (if no mention already captured it)
        if (!rt.href.isNullOrBlank() && mention?.page == null && mention?.database == null && mention?.dataSource == null) {
            edges += NotionUrlClassifier.classify(
                rawUrl = rt.href,
                sourcePageId = pageId,
                sourceBlockId = sourceBlockId,
                revision = revision,
                extractedAt = extractedAt,
                propertyId = propertyId,
                propertyName = propertyName,
                sourceIdentity = sourceIdentity,
                occurrence = occurrence
            )
        }

        return edges
    }

    private fun flattenBlocks(
        nodes: List<NormalizedStructuralBlock>,
        pageId: String,
        outBlocks: MutableList<StructuralBlock>,
        outEdges: MutableList<ExplicitRelationship>,
        seen: MutableSet<String> = mutableSetOf()
    ) {
        for (node in nodes) {
            if (!seen.add(node.id)) continue
            outBlocks += StructuralBlock(
                id = node.id,
                pageId = pageId,
                parentBlockId = node.parentBlockId,
                type = node.type,
                siblingOrder = node.siblingOrder,
                contentFingerprint = node.contentFingerprint,
                childCompleteness = node.childCompleteness,
                hasChildren = node.hasChildren
            )
            outEdges += node.explicitEdges
            flattenBlocks(node.children, pageId, outBlocks, outEdges, seen)
        }
    }

    /**
     * Reconstructs hierarchical sections agreeing with ReaderDocumentMapper:
     * - Section keys match heading block IDs directly (or "$pageId:intro" for intro).
     * - Anchor chunk IDs are "${block.id}:0" only for speakable blocks with non-blank text.
     * - Structural-only or non-spoken blocks (dividers, unsupported) have null readerChunkId.
     * - Empty headings emit no section and no chunk.
     */
    private fun reconstructSections(
        pageId: String,
        pageTitle: String,
        nodes: List<NormalizedStructuralBlock>
    ): List<StructuralSection> {
        val sections = mutableListOf<StructuralSection>()
        val headingStack = mutableListOf<Pair<Int, String>>()

        var currentSectionKey: String? = null
        var currentHeadingBlockId: String? = null
        var currentHeadingLevel = 0
        var currentHeadingTitle: String? = null
        var currentHeadingPath: List<String> = listOf(pageTitle)
        var currentBlockIds = mutableListOf<String>()
        var currentAnchor: StructuralNavigationAnchor? = null

        fun closeSection() {
            if (currentSectionKey != null && currentBlockIds.isNotEmpty()) {
                val anchor = currentAnchor ?: StructuralNavigationAnchor(
                    sourceBlockId = currentHeadingBlockId ?: currentBlockIds.first(),
                    readerChunkId = null
                )
                sections += StructuralSection(
                    sectionKey = currentSectionKey!!,
                    pageId = pageId,
                    headingBlockId = currentHeadingBlockId,
                    headingLevel = currentHeadingLevel,
                    headingTitle = currentHeadingTitle,
                    headingPath = currentHeadingPath,
                    blockIds = currentBlockIds.toList(),
                    navigationAnchor = anchor
                )
            }
            currentBlockIds = mutableListOf()
            currentAnchor = null
        }

        fun ensureIntroSection(firstBlock: NormalizedStructuralBlock) {
            if (currentSectionKey == null) {
                currentSectionKey = "$pageId:intro"
                currentHeadingBlockId = null
                currentHeadingLevel = 0
                currentHeadingTitle = null
                currentHeadingPath = listOf(pageTitle)
                if (firstBlock.isSpeakable && firstBlock.text.isNotBlank()) {
                    currentAnchor = StructuralNavigationAnchor(
                        sourceBlockId = firstBlock.id,
                        readerChunkId = "${firstBlock.id}:0"
                    )
                }
            }
        }

        fun walk(list: List<NormalizedStructuralBlock>) {
            for (node in list) {
                if (node.headingLevel in 1..4 && node.text.isNotBlank()) {
                    closeSection()

                    // Deterministic heading path stack
                    while (headingStack.isNotEmpty() && headingStack.last().first >= node.headingLevel) {
                        headingStack.removeAt(headingStack.size - 1)
                    }
                    headingStack.add(node.headingLevel to node.text.trim())

                    // Section key is the heading block ID (matches ReaderDocumentMapper D8)
                    currentSectionKey = node.id
                    currentHeadingBlockId = node.id
                    currentHeadingLevel = node.headingLevel
                    currentHeadingTitle = node.text.trim()
                    currentHeadingPath = listOf(pageTitle) + headingStack.map { it.second }
                    currentAnchor = StructuralNavigationAnchor(
                        sourceBlockId = node.id,
                        readerChunkId = if (node.isSpeakable) "${node.id}:0" else null
                    )
                    currentBlockIds.add(node.id)
                    walk(node.children)
                } else if (node.headingLevel in 1..4 && node.text.isBlank()) {
                    // D4 in ReaderDocumentMapper: empty heading emits no section and no chunk.
                    // Its children are read into enclosing section.
                    walk(node.children)
                } else {
                    ensureIntroSection(node)
                    currentBlockIds.add(node.id)
                    if (currentAnchor == null && node.isSpeakable && node.text.isNotBlank()) {
                        currentAnchor = StructuralNavigationAnchor(
                            sourceBlockId = node.id,
                            readerChunkId = "${node.id}:0"
                        )
                    }
                    walk(node.children)
                }
            }
        }

        walk(nodes)
        closeSection()

        return sections
    }

    private fun computeFingerprint(
        type: String,
        id: String,
        siblingOrder: Int,
        text: String,
        extra: String = ""
    ): String {
        val input = "$type:$id:$siblingOrder:$text:$extra"
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.take(16).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_MAX_DEPTH = 10
        private val NON_DESCENDED_TYPES = setOf("child_page", "child_database")
        private val READER_DESCENDED_TYPES = setOf("paragraph", "heading_1", "heading_2", "heading_3", "bulleted_list_item",
            "numbered_list_item", "to_do", "quote", "code", "callout", "toggle", "divider")
    }
}

private fun List<NotionRichText>.joinPlainText(): String =
    joinToString("") { it.plainText }

private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

