package com.example.notionreader.data.structure

import com.example.notionreader.domain.structure.ChildCompleteness
import com.example.notionreader.domain.structure.ExplicitRelationship
import com.example.notionreader.domain.structure.ExplicitRelationshipKind
import com.example.notionreader.domain.structure.RelationshipProvenance
import com.example.notionreader.domain.structure.RelationshipTarget
import com.example.notionreader.domain.structure.StructuralEntityKind
import com.example.notionreader.domain.structure.TargetResolutionStatus
import com.example.notionreader.domain.structure.normalizeNotionId
import java.net.URI
import java.security.MessageDigest
import java.time.Instant

/**
 * Provider-independent normalized block representation used as an intermediate step
 * during structural knowledge extraction.
 */
data class NormalizedStructuralBlock(
    val id: String,
    val type: String,
    val parentBlockId: String?,
    val siblingOrder: Int,
    val text: String,
    val headingLevel: Int = 0,
    val isSpeakable: Boolean = true,
    val contentFingerprint: String,
    val childCompleteness: ChildCompleteness,
    val hasChildren: Boolean = false,
    val explicitEdges: List<ExplicitRelationship> = emptyList(),
    val children: List<NormalizedStructuralBlock> = emptyList()
)

/**
 * Safe classification of URL hrefs.
 * Only URLs whose host is notion.so, notion.site, or notion.com (including app.notion.com) or relative paths
 * with http/https schemes may resolve to internal Notion entities.
 * External URLs containing a 32-hex string are NEVER falsely classified as internal Notion pages.
 */
object NotionUrlClassifier {

    private val NOTION_ID_REGEX = Regex("[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}")

    fun classify(
        rawUrl: String,
        sourcePageId: String,
        sourceBlockId: String?,
        revision: String,
        extractedAt: Instant,
        propertyId: String? = null,
        propertyName: String? = null,
        sourceIdentity: String = "",
        occurrence: Int = 0
    ): ExplicitRelationship {
        val trimmed = rawUrl.trim()
        val provenance = RelationshipProvenance(
            sourcePageId = sourcePageId,
            sourceBlockId = sourceBlockId,
            propertyId = propertyId,
            propertyName = propertyName,
            sourceIdentity = sourceIdentity,
            observedRevision = revision,
            extractedAt = extractedAt
        )

        val uri = runCatching { URI(trimmed) }.getOrNull()
        val sourceId = sourceBlockId ?: sourcePageId
        val sourceKind = if (sourceBlockId != null) StructuralEntityKind.BLOCK else StructuralEntityKind.PAGE

        if (uri == null) {
            val edgeId = stableEdgeId(sourceId, trimmed, ExplicitRelationshipKind.EXTERNAL_HYPERLINK, propertyId, occurrence)
            return ExplicitRelationship(
                id = edgeId,
                sourceId = sourceId,
                sourceKind = sourceKind,
                kind = ExplicitRelationshipKind.EXTERNAL_HYPERLINK,
                target = RelationshipTarget.Unresolved(trimmed, "Invalid URI syntax"),
                provenance = provenance,
                resolutionStatus = TargetResolutionStatus.MALFORMED
            )
        }

        // Reject opaque URIs and non-http/https schemes from notion host classification
        if (uri.isOpaque || (uri.scheme != null && uri.scheme != "http" && uri.scheme != "https")) {
            val edgeId = stableEdgeId(sourceId, trimmed, ExplicitRelationshipKind.EXTERNAL_HYPERLINK, propertyId, occurrence)
            return ExplicitRelationship(
                id = edgeId,
                sourceId = sourceId,
                sourceKind = sourceKind,
                kind = ExplicitRelationshipKind.EXTERNAL_HYPERLINK,
                target = RelationshipTarget.ExternalUrl(trimmed),
                provenance = provenance,
                resolutionStatus = TargetResolutionStatus.EXTERNAL
            )
        }

        val isNotion = isNotionHost(uri)
        if (isNotion) {
            val pathMatch = NOTION_ID_REGEX.findAll(uri.path.orEmpty()).lastOrNull()?.value?.let { normalizeNotionId(it) }
            val fragMatch = uri.fragment?.let { NOTION_ID_REGEX.findAll(it).lastOrNull()?.value }?.let { normalizeNotionId(it) }

            if (fragMatch != null) {
                val blockId = fragMatch
                val targetPageId = pathMatch ?: sourcePageId.takeIf { uri.host == null && uri.path.isNullOrEmpty() }
                val edgeId = stableEdgeId(sourceId, "$targetPageId#$blockId", ExplicitRelationshipKind.INTERNAL_HYPERLINK, propertyId, occurrence)
                return ExplicitRelationship(
                    id = edgeId,
                    sourceId = sourceId,
                    sourceKind = sourceKind,
                    kind = ExplicitRelationshipKind.INTERNAL_HYPERLINK,
                    target = RelationshipTarget.InternalBlock(blockId = blockId, pageId = targetPageId),
                    provenance = provenance,
                    resolutionStatus = TargetResolutionStatus.UNRESOLVED_PERMITTED
                )
            } else if (pathMatch != null) {
                val pageId = pathMatch
                val edgeId = stableEdgeId(sourceId, pageId, ExplicitRelationshipKind.INTERNAL_HYPERLINK, propertyId, occurrence)
                return ExplicitRelationship(
                    id = edgeId,
                    sourceId = sourceId,
                    sourceKind = sourceKind,
                    kind = ExplicitRelationshipKind.INTERNAL_HYPERLINK,
                    target = RelationshipTarget.InternalPage(pageId),
                    provenance = provenance,
                    resolutionStatus = TargetResolutionStatus.UNRESOLVED_PERMITTED
                )
            }
            return ExplicitRelationship(
                id = stableEdgeId(sourceId, trimmed, ExplicitRelationshipKind.INTERNAL_HYPERLINK, propertyId, occurrence),
                sourceId = sourceId, sourceKind = sourceKind, kind = ExplicitRelationshipKind.INTERNAL_HYPERLINK,
                target = RelationshipTarget.Unresolved(trimmed, "Notion URL has no supported entity identity"),
                provenance = provenance, resolutionStatus = TargetResolutionStatus.UNRESOLVED_PERMITTED
            )
        }

        // External URL
        val edgeId = stableEdgeId(sourceId, trimmed, ExplicitRelationshipKind.EXTERNAL_HYPERLINK, propertyId, occurrence)
        return ExplicitRelationship(
            id = edgeId,
            sourceId = sourceId,
            sourceKind = sourceKind,
            kind = ExplicitRelationshipKind.EXTERNAL_HYPERLINK,
            target = RelationshipTarget.ExternalUrl(trimmed),
            provenance = provenance,
            resolutionStatus = TargetResolutionStatus.EXTERNAL
        )
    }

    private fun isNotionHost(uri: URI): Boolean {
        if (uri.isOpaque) return false
        val host = uri.host?.lowercase()
        if (host == null) {
            return uri.scheme == null && (uri.path?.startsWith("/") == true || (uri.path.isNullOrEmpty() && uri.fragment != null))
        }
        return host == "notion.so" ||
            host.endsWith(".notion.so") ||
            host == "notion.site" ||
            host.endsWith(".notion.site") ||
            host == "notion.com" ||
            host.endsWith(".notion.com")
    }

    fun stableEdgeId(
        sourceId: String,
        targetKey: String,
        kind: ExplicitRelationshipKind,
        propertyId: String? = null,
        occurrence: Int = 0
    ): String {
        val input = "$sourceId:${propertyId.orEmpty()}:$targetKey:${kind.name}:$occurrence"
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val hex = bytes.take(16).joinToString("") { "%02x".format(it) }
        return "$sourceId:edge:$hex"
    }
}
