package com.example.notionreader.data.structure

import com.example.notionreader.domain.structure.*
import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
internal data class StructuralContainerDto(
    val schemaVersion: Int = 1,
    val epoch: Long,
    val id: String,
    val kind: String,
    val title: String,
    val parentType: String,
    val parentId: String? = null,
    val sourceIdentity: String,
    val sourceRevision: String,
    val createdAt: Long? = null,
    val lastEditedAt: Long? = null
)

internal fun StructuralContainer.toDto(epoch: Long): StructuralContainerDto {
    val parent = StructuralPage(id, title, parent, sourceIdentity = sourceIdentity).toDto()
    return StructuralContainerDto(epoch = epoch, id = id, kind = kind.name, title = title,
        parentType = parent.parentType, parentId = parent.parentId, sourceIdentity = sourceIdentity,
        sourceRevision = sourceRevision, createdAt = createdAt?.toEpochMilli(), lastEditedAt = lastEditedAt?.toEpochMilli())
}

internal fun StructuralContainerDto.toDomain() = StructuralContainer(id, StructuralContainerKind.valueOf(kind), title,
    parseParent(parentType, parentId), sourceIdentity, sourceRevision, createdAt?.let(Instant::ofEpochMilli),
    lastEditedAt?.let(Instant::ofEpochMilli))
// ---------------------------------------------------------------- Serialization DTOs

@Serializable
internal data class StructuralPageRecordDto(
    val schemaVersion: Int,
    val eligibilityEpoch: Long = 0,
    val sourceIdentity: String = "",
    val page: StructuralPageDto,
    val blocks: List<StructuralBlockDto>,
    val sections: List<StructuralSectionDto>,
    val outgoingRelationships: List<ExplicitRelationshipDto>
)

@Serializable
internal data class StructuralPageDto(
    val id: String,
    val title: String,
    val parentType: String,
    val parentId: String? = null,
    val databaseId: String? = null,
    val dataSourceId: String? = null,
    val sourceIdentity: String = "",
    val createdAtEpochMilli: Long? = null,
    val lastEditedAtEpochMilli: Long? = null,
    val accessibilityState: String,
    val sourceRevision: String,
    val syncStatus: String
)

@Serializable
internal data class StructuralBlockDto(
    val id: String,
    val pageId: String,
    val parentBlockId: String? = null,
    val type: String,
    val siblingOrder: Int,
    val contentFingerprint: String,
    val childCompleteness: String,
    val hasChildren: Boolean
)

@Serializable
internal data class StructuralSectionDto(
    val sectionKey: String,
    val pageId: String,
    val headingBlockId: String? = null,
    val headingLevel: Int,
    val headingTitle: String? = null,
    val headingPath: List<String>,
    val blockIds: List<String>,
    val navigationAnchorSourceBlockId: String,
    val navigationAnchorReaderChunkId: String? = null,
    val navigationAnchorOffset: Int = 0
)

@Serializable
internal data class ExplicitRelationshipDto(
    val id: String,
    val sourceId: String,
    val sourceKind: String,
    val kind: String,
    val targetType: String,
    val targetPrimaryId: String? = null,
    val targetSecondaryId: String? = null,
    val targetUrl: String? = null,
    val provenancePageId: String,
    val provenanceBlockId: String? = null,
    val provenancePropertyId: String? = null,
    val provenancePropertyName: String? = null,
    val provenanceSourceIdentity: String = "",
    val provenanceTextStart: Int? = null,
    val provenanceTextEnd: Int? = null,
    val provenanceRevision: String,
    val provenanceExtractedAtEpochMilli: Long,
    val resolutionStatus: String
)

@Serializable
internal data class PendingWorkItemDto(
    val pageId: String,
    val requestedAtEpochMilli: Long,
    val reason: String,
    val sourceIdentity: String = ""
)

// ---------------------------------------------------------------- Mapping extensions

internal fun StructuralPageRecord.toDto() = StructuralPageRecordDto(
    schemaVersion = schemaVersion,
    sourceIdentity = sourceIdentity,
    page = page.toDto(),
    blocks = blocks.map { it.toDto() },
    sections = sections.map { it.toDto() },
    outgoingRelationships = outgoingRelationships.map { it.toDto() }
)

internal fun StructuralPage.toDto() = StructuralPageDto(
    id = id,
    title = title,
    sourceIdentity = sourceIdentity,
    parentType = when (parent) {
        is StructuralParent.Page -> "page"
        is StructuralParent.Database -> "database"
        is StructuralParent.DataSource -> "data_source"
        is StructuralParent.Block -> "block"
        StructuralParent.Workspace -> "workspace"
        is StructuralParent.Unknown -> "unknown:${parent.rawType}"
    },
    parentId = when (parent) {
        is StructuralParent.Page -> parent.pageId
        is StructuralParent.Database -> parent.databaseId
        is StructuralParent.DataSource -> parent.dataSourceId
        is StructuralParent.Block -> parent.blockId
        is StructuralParent.Unknown -> parent.rawId
        StructuralParent.Workspace -> null
    },
    databaseId = databaseId,
    dataSourceId = dataSourceId,
    createdAtEpochMilli = createdAt?.toEpochMilli(),
    lastEditedAtEpochMilli = lastEditedAt?.toEpochMilli(),
    accessibilityState = accessibilityState.name,
    sourceRevision = sourceRevision,
    syncStatus = syncStatus.name
)

internal fun StructuralBlock.toDto() = StructuralBlockDto(
    id = id,
    pageId = pageId,
    parentBlockId = parentBlockId,
    type = type,
    siblingOrder = siblingOrder,
    contentFingerprint = contentFingerprint,
    childCompleteness = childCompleteness.name,
    hasChildren = hasChildren
)

internal fun StructuralSection.toDto() = StructuralSectionDto(
    sectionKey = sectionKey,
    pageId = pageId,
    headingBlockId = headingBlockId,
    headingLevel = headingLevel,
    headingTitle = headingTitle,
    headingPath = headingPath,
    blockIds = blockIds,
    navigationAnchorSourceBlockId = navigationAnchor.sourceBlockId,
    navigationAnchorReaderChunkId = navigationAnchor.readerChunkId,
    navigationAnchorOffset = navigationAnchor.characterOffset
)

internal fun ExplicitRelationship.toDto() = ExplicitRelationshipDto(
    id = id,
    sourceId = sourceId,
    sourceKind = sourceKind.name,
    kind = kind.name,
    targetType = when (target) {
        is RelationshipTarget.InternalPage -> "page"
        is RelationshipTarget.InternalBlock -> "block"
        is RelationshipTarget.InternalDatabase -> "database"
        is RelationshipTarget.InternalDataSource -> "data_source"
        is RelationshipTarget.ExternalUrl -> "external"
        is RelationshipTarget.Unresolved -> "unresolved"
    },
    targetPrimaryId = when (target) {
        is RelationshipTarget.InternalPage -> target.pageId
        is RelationshipTarget.InternalBlock -> target.pageId
        is RelationshipTarget.InternalDatabase -> target.databaseId
        is RelationshipTarget.InternalDataSource -> target.dataSourceId
        is RelationshipTarget.Unresolved -> target.rawReference
        else -> null
    },
    targetSecondaryId = when (target) {
        is RelationshipTarget.InternalBlock -> target.blockId
        is RelationshipTarget.Unresolved -> target.reason
        else -> null
    },
    targetUrl = (target as? RelationshipTarget.ExternalUrl)?.url,
    provenancePageId = provenance.sourcePageId,
    provenanceBlockId = provenance.sourceBlockId,
    provenancePropertyId = provenance.propertyId,
    provenancePropertyName = provenance.propertyName,
    provenanceSourceIdentity = provenance.sourceIdentity,
    provenanceTextStart = provenance.textRange?.first,
    provenanceTextEnd = provenance.textRange?.last,
    provenanceRevision = provenance.observedRevision,
    provenanceExtractedAtEpochMilli = provenance.extractedAt.toEpochMilli(),
    resolutionStatus = resolutionStatus.name
)

internal fun StructuralPageRecordDto.toDomain() = StructuralPageRecord(
    schemaVersion = schemaVersion,
    sourceIdentity = sourceIdentity,
    page = page.toDomain(),
    blocks = blocks.map { it.toDomain() },
    sections = sections.map { it.toDomain() },
    outgoingRelationships = outgoingRelationships.map { it.toDomain() }
)

internal fun StructuralPageDto.toDomain() = StructuralPage(
    id = id,
    title = title,
    sourceIdentity = sourceIdentity,
    parent = parseParent(parentType, parentId),
    databaseId = databaseId,
    dataSourceId = dataSourceId,
    createdAt = createdAtEpochMilli?.let { Instant.ofEpochMilli(it) },
    lastEditedAt = lastEditedAtEpochMilli?.let { Instant.ofEpochMilli(it) },
    accessibilityState = PageAccessibilityState.valueOf(accessibilityState),
    sourceRevision = sourceRevision,
    syncStatus = runCatching { SyncCompleteness.valueOf(syncStatus) }.getOrDefault(SyncCompleteness.UNKNOWN)
)

internal fun StructuralBlockDto.toDomain() = StructuralBlock(
    id = id,
    pageId = pageId,
    parentBlockId = parentBlockId,
    type = type,
    siblingOrder = siblingOrder,
    contentFingerprint = contentFingerprint,
    childCompleteness = runCatching { ChildCompleteness.valueOf(childCompleteness) }.getOrDefault(ChildCompleteness.UNFETCHED),
    hasChildren = hasChildren
)

internal fun StructuralSectionDto.toDomain() = StructuralSection(
    sectionKey = sectionKey,
    pageId = pageId,
    headingBlockId = headingBlockId,
    headingLevel = headingLevel,
    headingTitle = headingTitle,
    headingPath = headingPath,
    blockIds = blockIds,
    navigationAnchor = StructuralNavigationAnchor(
        sourceBlockId = navigationAnchorSourceBlockId,
        readerChunkId = navigationAnchorReaderChunkId,
        characterOffset = navigationAnchorOffset
    )
)

internal fun ExplicitRelationshipDto.toDomain() = ExplicitRelationship(
    id = id,
    sourceId = sourceId,
    sourceKind = StructuralEntityKind.valueOf(sourceKind),
    kind = ExplicitRelationshipKind.valueOf(kind),
    target = when (targetType) {
        "page" -> RelationshipTarget.InternalPage(targetPrimaryId.orEmpty())
        "block" -> RelationshipTarget.InternalBlock(targetSecondaryId.orEmpty(), targetPrimaryId)
        "database" -> RelationshipTarget.InternalDatabase(targetPrimaryId.orEmpty())
        "data_source" -> RelationshipTarget.InternalDataSource(targetPrimaryId.orEmpty())
        "external" -> RelationshipTarget.ExternalUrl(targetUrl.orEmpty())
        else -> RelationshipTarget.Unresolved(targetPrimaryId.orEmpty(), targetSecondaryId.orEmpty())
    },
    provenance = RelationshipProvenance(
        sourcePageId = provenancePageId,
        sourceBlockId = provenanceBlockId,
        propertyId = provenancePropertyId,
        propertyName = provenancePropertyName,
        sourceIdentity = provenanceSourceIdentity,
        textRange = if (provenanceTextStart != null && provenanceTextEnd != null) provenanceTextStart..provenanceTextEnd else null,
        observedRevision = provenanceRevision,
        extractedAt = Instant.ofEpochMilli(provenanceExtractedAtEpochMilli)
    ),
    resolutionStatus = TargetResolutionStatus.valueOf(resolutionStatus)
)

private fun parseParent(type: String, id: String?): StructuralParent = when {
    type == "page" && id != null -> StructuralParent.Page(id)
    type == "database" && id != null -> StructuralParent.Database(id)
    type == "data_source" && id != null -> StructuralParent.DataSource(id)
    type == "block" && id != null -> StructuralParent.Block(id)
    type == "workspace" -> StructuralParent.Workspace
    else -> StructuralParent.Unknown(type.removePrefix("unknown:"), id)
}


