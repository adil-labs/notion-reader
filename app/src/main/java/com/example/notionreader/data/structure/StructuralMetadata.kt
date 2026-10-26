package com.example.notionreader.data.structure

import com.example.notionreader.data.notion.*
import com.example.notionreader.domain.structure.*
import java.time.Instant

/** Metadata projection only: never claim that an untraversed page body is complete. */
fun NotionPage.structuralMetadata(identity: String): StructuralPage? {
    val canonical = normalizeNotionId(id) ?: return null
    val native = parent
    val owner = when (native?.type) {
        "page_id" -> native.pageId?.let(::normalizeNotionId)?.let { StructuralParent.Page(it) }
        "block_id" -> native.blockId?.let(::normalizeNotionId)?.let { StructuralParent.Block(it) }
        "database_id" -> native.databaseId?.let(::normalizeNotionId)?.let { StructuralParent.Database(it) }
        "data_source_id" -> native.dataSourceId?.let(::normalizeNotionId)?.let { StructuralParent.DataSource(it) }
        "workspace" -> StructuralParent.Workspace
        else -> null
    } ?: StructuralParent.Unknown(native?.type ?: "none", native?.pageId ?: native?.blockId ?: native?.databaseId ?: native?.dataSourceId)
    return StructuralPage(canonical, if (isTrashed) "" else title(), owner,
        databaseId = (owner as? StructuralParent.Database)?.databaseId,
        dataSourceId = (owner as? StructuralParent.DataSource)?.dataSourceId, sourceIdentity = identity,
        createdAt = createdTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
        lastEditedAt = lastEditedTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
        accessibilityState = if (isTrashed) PageAccessibilityState.CONFIRMED_TRASHED else PageAccessibilityState.ACCESSIBLE,
        sourceRevision = lastEditedTime ?: createdTime ?: "", syncStatus = SyncCompleteness.UNKNOWN)
}

