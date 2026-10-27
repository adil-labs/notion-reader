package com.example.notionreader.domain.structure

import java.time.Instant

/**
 * Normalizes raw Notion identifiers into canonical 36-character dashed UUIDs.
 * Strictly validates that the identifier consists of 32 hexadecimal characters:
 * either 32 consecutive hex chars, or 36 chars with dashes at standard positions (8-4-4-4-12).
 * Returns null for any malformed input, arbitrary dash placement, or invalid hex digits.
 */
fun normalizeNotionId(raw: String): String? {
    val trimmed = raw.trim().lowercase()
    if (trimmed.length == 32) {
        if (!trimmed.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return buildString(36) {
            append(trimmed, 0, 8).append('-')
            append(trimmed, 8, 12).append('-')
            append(trimmed, 12, 16).append('-')
            append(trimmed, 16, 20).append('-')
            append(trimmed, 20, 32)
        }
    } else if (trimmed.length == 36) {
        if (trimmed[8] != '-' || trimmed[13] != '-' || trimmed[18] != '-' || trimmed[23] != '-') return null
        for (i in 0 until 36) {
            if (i == 8 || i == 13 || i == 18 || i == 23) continue
            val c = trimmed[i]
            if (c !in '0'..'9' && c !in 'a'..'f') return null
        }
        return trimmed
    }
    return null
}

/**
 * Authoritative factual representation of a Notion page or database entry.
 */
data class StructuralPage(
    val id: String,
    val title: String,
    val parent: StructuralParent,
    val databaseId: String? = null,
    val dataSourceId: String? = null,
    val sourceIdentity: String = "",
    val createdAt: Instant? = null,
    val lastEditedAt: Instant? = null,
    val accessibilityState: PageAccessibilityState = PageAccessibilityState.ACCESSIBLE,
    val sourceRevision: String = "",
    val syncStatus: SyncCompleteness = SyncCompleteness.COMPLETE
)

sealed interface StructuralParent {
    data class Page(val pageId: String) : StructuralParent
    data class Database(val databaseId: String) : StructuralParent
    data class DataSource(val dataSourceId: String) : StructuralParent
    data class Block(val blockId: String) : StructuralParent
    object Workspace : StructuralParent
    data class Unknown(val rawType: String, val rawId: String?) : StructuralParent
}

enum class PageAccessibilityState {
    ACCESSIBLE,
    RESTRICTED_PERMISSION,
    CONFIRMED_TRASHED,
    NOT_FOUND_OR_UNSHARED,
    UNFETCHED
}

enum class SyncCompleteness {
    LIMIT_EXCEEDED,
    COMPLETE,
    PARTIAL_TRAVERSAL_INCOMPLETE,
    FAILED_RECOVERABLE,
    STALE,
    UNKNOWN
}

/**
 * Authoritative factual representation of an individual block within a page.
 */
data class StructuralBlock(
    val id: String,
    val pageId: String,
    val parentBlockId: String? = null,
    val type: String,
    val siblingOrder: Int,
    val contentFingerprint: String,
    val childCompleteness: ChildCompleteness,
    val hasChildren: Boolean = false
)

enum class ChildCompleteness {
    LEAF,
    REFERENCE,
    COMPLETE,
    PAGINATION_INCOMPLETE,
    DEPTH_LIMIT_REACHED,
    UNFETCHED,
    PERMISSION_DENIED
}

/**
 * Reconstructed hierarchical section within a page.
 */
data class StructuralSection(
    val sectionKey: String,
    val pageId: String,
    val headingBlockId: String?,
    val headingLevel: Int,
    val headingTitle: String?,
    val headingPath: List<String>,
    val blockIds: List<String>,
    val navigationAnchor: StructuralNavigationAnchor
)

/**
 * Stable navigation anchor grounding playback and reading positions.
 * Note: [readerChunkId] is null if the block does not produce a speakable chunk (e.g. divider, unsupported block).
 * Chunk IDs must never be fabricated for unsupported, empty, or non-speakable blocks.
 */
data class StructuralNavigationAnchor(
    val sourceBlockId: String,
    val readerChunkId: String? = null,
    val characterOffset: Int = 0
)

/**
 * Explicit relationship derived from Notion structure or explicit references.
 *
 * Invariant: Never treat an explicit relationship or structural adjacency as proof of
 * semantic similarity, topic membership, or conceptual prerequisites.
 */
data class ExplicitRelationship(
    val id: String,
    val sourceId: String,
    val sourceKind: StructuralEntityKind,
    val kind: ExplicitRelationshipKind,
    val target: RelationshipTarget,
    val provenance: RelationshipProvenance,
    val resolutionStatus: TargetResolutionStatus
)

enum class StructuralEntityKind {
    PAGE,
    BLOCK,
    PROPERTY
}

enum class ExplicitRelationshipKind {
    PAGE_PARENT_CHILD,
    BLOCK_PARENT_CHILD,
    DATABASE_MEMBERSHIP,
    DATA_SOURCE_MEMBERSHIP,
    NOTION_RELATION,
    PAGE_MENTION,
    DATABASE_MENTION,
    DATA_SOURCE_MENTION,
    INTERNAL_HYPERLINK,
    EXTERNAL_HYPERLINK,
    SYNCED_BLOCK,
    CHILD_DATABASE,
    LINK_TO_DATABASE,
    LINK_TO_PAGE
}

sealed interface RelationshipTarget {
    data class InternalPage(val pageId: String) : RelationshipTarget
    data class InternalBlock(val blockId: String, val pageId: String? = null) : RelationshipTarget
    data class InternalDatabase(val databaseId: String) : RelationshipTarget
    data class InternalDataSource(val dataSourceId: String) : RelationshipTarget
    data class ExternalUrl(val url: String) : RelationshipTarget
    data class Unresolved(val rawReference: String, val reason: String) : RelationshipTarget
}

data class RelationshipProvenance(
    val sourcePageId: String,
    val sourceBlockId: String? = null,
    val propertyId: String? = null,
    val propertyName: String? = null,
    val sourceIdentity: String = "",
    val textRange: IntRange? = null,
    val observedRevision: String,
    val extractedAt: Instant
)

enum class TargetResolutionStatus {
    RESOLVED,
    UNRESOLVED_PERMITTED,
    UNRESOLVED_INACCESSIBLE,
    UNRESOLVED_NOT_FOUND,
    EXTERNAL,
    MALFORMED
}
