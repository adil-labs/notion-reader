package com.example.notionreader.data.structure

import com.example.notionreader.data.notion.NotionApi
import com.example.notionreader.data.notion.NotionBlock
import com.example.notionreader.data.notion.NotionPage
import com.example.notionreader.data.notion.NotionParent
import com.example.notionreader.data.notion.NotionProperty
import com.example.notionreader.data.notion.title
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
import com.example.notionreader.domain.structure.PageAccessibilityState
import com.example.notionreader.domain.structure.RelationshipTarget
import com.example.notionreader.domain.structure.StructuralPage
import com.example.notionreader.domain.structure.StructuralPageRecord
import com.example.notionreader.domain.structure.StructuralParent
import com.example.notionreader.domain.structure.SyncCompleteness
import com.example.notionreader.domain.structure.TargetResolutionStatus
import com.example.notionreader.domain.structure.normalizeNotionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant

/**
 * Normalized provider-independent container metadata collected from owning Notion databases
 * or data sources. Persistable alongside page records in subsequent integration passes.
 */
data class NormalizedContainerMetadata(
    val id: String,
    val kind: ContainerKind,
    val title: String,
    val databaseId: String? = null,
    val inTrash: Boolean = false,
    val parent: StructuralParent = StructuralParent.Unknown("none", null),
    val createdAt: Instant? = null,
    val lastEditedAt: Instant? = null,
    val sourceIdentity: String = "",
    val accessibilityState: PageAccessibilityState = PageAccessibilityState.ACCESSIBLE,
    val sourceRevision: String = ""
)

enum class ContainerKind {
    DATABASE,
    DATA_SOURCE
}

/**
 * Structural payload returned by [NotionStructuralCollector].
 */
data class CollectedStructuralData(
    val pageRecord: StructuralPageRecord,
    val containerMetadata: NormalizedContainerMetadata? = null,
    val resolvedTargetTitles: Map<String, String> = emptyMap(),
    val containers: List<NormalizedContainerMetadata> = emptyList(),
    val targetPages: List<StructuralPage> = emptyList()
)

private data class TargetResolutionResult(
    val status: TargetResolutionStatus
)

/**
 * Bounded structural collector extending the Notion API pipeline.
 *
 * Designed for callable integration into NotionRepository without duplicating reader HTTP:
 * accepts pre-fetched root and child blocks, paginates reference-bearing property items,
 * boundedly resolves internal target metadata (capping workspace traversal), and maps
 * ambiguous 404s honestly as [PageAccessibilityState.NOT_FOUND_OR_UNSHARED] without
 * claiming confirmed deletion.
 */
class NotionStructuralCollector(
    private val api: NotionApi,
    private val extractor: StructuralExtractor = StructuralExtractor(),
    private val maxTargetResolutionRequests: Int = DEFAULT_MAX_TARGET_RESOLUTION_REQUESTS
) {

    suspend fun collectPageStructure(
        pageId: String,
        sourceIdentity: String = "",
        cachedPage: NotionPage? = null,
        cachedBlocks: List<NotionBlock>? = null,
        cachedChildren: Map<String, List<NotionBlock>>? = null,
        extractedAt: Instant = Instant.now()
    ): CollectedStructuralData {
        currentCoroutineContext().ensureActive()
        var partialPropertyHydration = false

        // 1. Fetch or reuse page metadata
        val page = try {
            cachedPage ?: api.retrievePage(pageId)
        } catch (e: ReaderLoadException) {
            when (e.category) {
                ReaderErrorCategory.PAGE_NOT_FOUND -> {
                    // Ambiguous 404: Notion object_not_found can mean missing OR unshared; never confirmed trash
                    val emptyRecord = StructuralPageRecord(
                        page = StructuralPage(
                            id = normalizeNotionId(pageId) ?: pageId,
                            title = "",
                            parent = StructuralParent.Unknown("none", null),
                            sourceIdentity = sourceIdentity,
                            accessibilityState = PageAccessibilityState.NOT_FOUND_OR_UNSHARED,
                            syncStatus = SyncCompleteness.FAILED_RECOVERABLE
                        ),
                        blocks = emptyList(),
                        sections = emptyList(),
                        outgoingRelationships = emptyList(),
                        sourceIdentity = sourceIdentity
                    )
                    currentCoroutineContext().ensureActive()
                    return CollectedStructuralData(pageRecord = emptyRecord)
                }
                ReaderErrorCategory.PERMISSION_DENIED -> {
                    val emptyRecord = StructuralPageRecord(
                        page = StructuralPage(
                            id = normalizeNotionId(pageId) ?: pageId,
                            title = "",
                            parent = StructuralParent.Unknown("none", null),
                            sourceIdentity = sourceIdentity,
                            accessibilityState = PageAccessibilityState.RESTRICTED_PERMISSION,
                            syncStatus = SyncCompleteness.FAILED_RECOVERABLE
                        ),
                        blocks = emptyList(),
                        sections = emptyList(),
                        outgoingRelationships = emptyList(),
                        sourceIdentity = sourceIdentity
                    )
                    currentCoroutineContext().ensureActive()
                    return CollectedStructuralData(pageRecord = emptyRecord)
                }
                ReaderErrorCategory.AUTHORIZATION_REQUIRED -> {
                    // Propagate AUTHORIZATION_REQUIRED from initial page metadata (not a per-page permission state),
                    // allowing coordinator to invalidate whole source.
                    throw e
                }
                else -> throw e
            }
        }

        // Return minimal CONFIRMED_TRASHED record before fetching blocks/relations/targets
        if (page.isTrashed) {
            val canonicalPageId = normalizeNotionId(pageId) ?: pageId
            val trashedRecord = StructuralPageRecord(
                page = StructuralPage(
                    id = canonicalPageId,
                    title = "",
                    parent = mapNotionParent(page.parent),
                    databaseId = page.parent?.databaseId?.let { normalizeNotionId(it) ?: it },
                    dataSourceId = page.parent?.dataSourceId?.let { normalizeNotionId(it) ?: it },
                    sourceIdentity = sourceIdentity,
                    createdAt = page.createdTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
                    lastEditedAt = page.lastEditedTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
                    accessibilityState = PageAccessibilityState.CONFIRMED_TRASHED,
                    sourceRevision = page.lastEditedTime ?: page.createdTime ?: "",
                    syncStatus = SyncCompleteness.COMPLETE
                ),
                blocks = emptyList(),
                sections = emptyList(),
                outgoingRelationships = emptyList(),
                sourceIdentity = sourceIdentity
            )
            currentCoroutineContext().ensureActive()
            return CollectedStructuralData(pageRecord = trashedRecord)
        }

        // 2. Hydrate reference-bearing property items that may be truncated on the main page object
        val hydratedProperties = page.properties.toMutableMap()
        for ((propName, prop) in page.properties) {
            currentCoroutineContext().ensureActive()
            val propType = prop.type?.lowercase()
            val isRelation = propType == "relation" || prop.relation.isNotEmpty()
            val isTitle = propType == "title" || prop.title.isNotEmpty()
            val isRichText = propType == "rich_text" || prop.richText.isNotEmpty()

            val shouldHydrate = prop.hasMore ||
                (isRelation && prop.relation.size >= 25) ||
                (isTitle && prop.title.size >= 25) ||
                (isRichText && prop.richText.size >= 25)

            if (shouldHydrate) {
                val propId = prop.id ?: propName
                try {
                    val allItems = api.retrieveAllPropertyItems(page.id, propId)
                    if (allItems.isEmpty()) { partialPropertyHydration = true; continue }
                    if (isTitle) {
                        val titleItems = allItems.mapNotNull { it.title ?: it.richText }
                        hydratedProperties[propName] = prop.copy(
                            title = if (titleItems.isNotEmpty()) titleItems else prop.title,
                            hasMore = false
                        )
                    } else if (isRichText) {
                        val richTextItems = allItems.mapNotNull { it.richText ?: it.title }
                        hydratedProperties[propName] = prop.copy(
                            richText = if (richTextItems.isNotEmpty()) richTextItems else prop.richText,
                            hasMore = false
                        )
                    } else if (isRelation) {
                        val extraRelations = allItems.mapNotNull { it.relation }
                        hydratedProperties[propName] = prop.copy(
                            relation = if (extraRelations.isNotEmpty()) extraRelations.distinctBy { it.id } else prop.relation,
                            hasMore = false
                        )
                    } else {
                        hydratedProperties[propName] = prop.copy(hasMore = false)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e is ReaderLoadException && e.category == ReaderErrorCategory.AUTHORIZATION_REQUIRED) throw e
                    if (e is ReaderLoadException && e.category in setOf(ReaderErrorCategory.PERMISSION_DENIED, ReaderErrorCategory.PAGE_NOT_FOUND)) throw e
                    partialPropertyHydration = true
                }
            }
        }
        val hydratedPage = page.copy(properties = hydratedProperties)

        // 3. Fetch or reuse root blocks and child blocks
        var partialChildFetch = false
        val rootBlocks = try {
            cachedBlocks ?: api.retrieveBlockChildren(page.id)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
                    if (e is ReaderLoadException && e.category == ReaderErrorCategory.AUTHORIZATION_REQUIRED) throw e
            if (e is ReaderLoadException && (
                e.category == ReaderErrorCategory.PERMISSION_DENIED ||
                e.category == ReaderErrorCategory.PAGE_NOT_FOUND ||
                e.category == ReaderErrorCategory.AUTHORIZATION_REQUIRED
            )) {
                throw e
            }
            partialChildFetch = true
            emptyList()
        }

        var capturedAccessFailure: ReaderLoadException? = null

        val childrenSupplier: suspend (String) -> List<NotionBlock> = { blockId ->
            currentCoroutineContext().ensureActive()
            if (cachedChildren != null && cachedChildren.containsKey(blockId)) {
                cachedChildren[blockId].orEmpty()
            } else {
                try {
                    api.retrieveBlockChildren(blockId)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e is ReaderLoadException && e.category == ReaderErrorCategory.AUTHORIZATION_REQUIRED) throw e
                    if (e is ReaderLoadException && (
                        e.category == ReaderErrorCategory.PERMISSION_DENIED ||
                        e.category == ReaderErrorCategory.PAGE_NOT_FOUND ||
                        e.category == ReaderErrorCategory.AUTHORIZATION_REQUIRED
                    )) {
                        capturedAccessFailure = e
                        throw e
                    }
                    partialChildFetch = true
                    throw e
                }
            }
        }

        // 4. Extract raw structural facts
        val baseRecord = extractor.extract(
            page = hydratedPage,
            blocks = rootBlocks,
            childrenSupplier = childrenSupplier,
            sourceIdentity = sourceIdentity,
            extractedAt = extractedAt
        )

        capturedAccessFailure?.let { throw it }

        val recordWithHonestStatus = if ((partialPropertyHydration || partialChildFetch) &&
            baseRecord.page.syncStatus == SyncCompleteness.COMPLETE
        ) {
            baseRecord.copy(
                page = baseRecord.page.copy(syncStatus = SyncCompleteness.PARTIAL_TRAVERSAL_INCOMPLETE)
            )
        } else {
            baseRecord
        }

        // 5. Collect container metadata (both owning data source and containing database)
        val containers = mutableListOf<NormalizedContainerMetadata>()
        when (val parent = recordWithHonestStatus.page.parent) {
            is StructuralParent.DataSource -> {
                var dsDatabaseId: String? = null
                try {
                    val ds = api.retrieveDataSource(parent.dataSourceId)
                    if (ds != null) {
                        dsDatabaseId = ds.databaseId
                        val isDsTrashed = ds.inTrash
                        val dsAccess = if (isDsTrashed) PageAccessibilityState.CONFIRMED_TRASHED else PageAccessibilityState.ACCESSIBLE
                        containers += NormalizedContainerMetadata(
                            id = ds.id,
                            kind = ContainerKind.DATA_SOURCE,
                            title = if (dsAccess == PageAccessibilityState.CONFIRMED_TRASHED) "" else ds.name,
                            databaseId = ds.databaseId,
                            inTrash = ds.inTrash,
                            parent = mapNotionParent(ds.parent),
                            createdAt = ds.createdTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
                            lastEditedAt = ds.lastEditedTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
                            sourceIdentity = sourceIdentity,
                            accessibilityState = dsAccess,
                            sourceRevision = ds.lastEditedTime ?: ds.createdTime ?: ""
                        )
                    } else {
                        containers += NormalizedContainerMetadata(
                            id = parent.dataSourceId,
                            kind = ContainerKind.DATA_SOURCE,
                            title = "",
                            parent = StructuralParent.Unknown("none", null),
                            sourceIdentity = sourceIdentity,
                            accessibilityState = PageAccessibilityState.NOT_FOUND_OR_UNSHARED
                        )
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e is ReaderLoadException && e.category == ReaderErrorCategory.AUTHORIZATION_REQUIRED) throw e
                    if (e !is ReaderLoadException || e.category !in setOf(ReaderErrorCategory.PERMISSION_DENIED, ReaderErrorCategory.PAGE_NOT_FOUND)) throw e
                    val accessState = if (e is ReaderLoadException && e.category == ReaderErrorCategory.PERMISSION_DENIED) {
                        PageAccessibilityState.RESTRICTED_PERMISSION
                    } else {
                        PageAccessibilityState.NOT_FOUND_OR_UNSHARED
                    }
                    containers += NormalizedContainerMetadata(
                        id = parent.dataSourceId,
                        kind = ContainerKind.DATA_SOURCE,
                        title = "",
                        parent = StructuralParent.Unknown("none", null),
                        sourceIdentity = sourceIdentity,
                        accessibilityState = accessState
                    )
                }

                val containingDbId = dsDatabaseId ?: recordWithHonestStatus.page.databaseId
                if (!containingDbId.isNullOrBlank()) {
                    val canonicalDbId = normalizeNotionId(containingDbId) ?: containingDbId
                    try {
                        val db = api.retrieveDatabase(canonicalDbId)
                        if (db != null) {
                            val isDbTrashed = db.inTrash
                            val dbAccess = if (isDbTrashed) PageAccessibilityState.CONFIRMED_TRASHED else PageAccessibilityState.ACCESSIBLE
                            containers += NormalizedContainerMetadata(
                                id = db.id,
                                kind = ContainerKind.DATABASE,
                                title = if (dbAccess == PageAccessibilityState.CONFIRMED_TRASHED) "" else db.title.joinToString("") { it.plainText },
                                databaseId = null,
                                inTrash = db.inTrash,
                                parent = mapNotionParent(db.parent),
                                createdAt = db.createdTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
                                lastEditedAt = db.lastEditedTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
                                sourceIdentity = sourceIdentity,
                                accessibilityState = dbAccess,
                                sourceRevision = db.lastEditedTime ?: db.createdTime ?: ""
                            )
                        } else {
                            containers += NormalizedContainerMetadata(
                                id = canonicalDbId,
                                kind = ContainerKind.DATABASE,
                                title = "",
                                parent = StructuralParent.Unknown("none", null),
                                sourceIdentity = sourceIdentity,
                                accessibilityState = PageAccessibilityState.NOT_FOUND_OR_UNSHARED
                            )
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    if (e is ReaderLoadException && e.category == ReaderErrorCategory.AUTHORIZATION_REQUIRED) throw e
                        if (e !is ReaderLoadException || e.category !in setOf(ReaderErrorCategory.PERMISSION_DENIED, ReaderErrorCategory.PAGE_NOT_FOUND)) throw e
                        val accessState = if (e is ReaderLoadException && e.category == ReaderErrorCategory.PERMISSION_DENIED) {
                            PageAccessibilityState.RESTRICTED_PERMISSION
                        } else {
                            PageAccessibilityState.NOT_FOUND_OR_UNSHARED
                        }
                        containers += NormalizedContainerMetadata(
                            id = canonicalDbId,
                            kind = ContainerKind.DATABASE,
                            title = "",
                            parent = StructuralParent.Unknown("none", null),
                            sourceIdentity = sourceIdentity,
                            accessibilityState = accessState
                        )
                    }
                }
            }
            is StructuralParent.Database -> {
                val canonicalDbId = normalizeNotionId(parent.databaseId) ?: parent.databaseId
                try {
                    val db = api.retrieveDatabase(canonicalDbId)
                    if (db != null) {
                        val isDbTrashed = db.inTrash
                        val dbAccess = if (isDbTrashed) PageAccessibilityState.CONFIRMED_TRASHED else PageAccessibilityState.ACCESSIBLE
                        containers += NormalizedContainerMetadata(
                            id = db.id,
                            kind = ContainerKind.DATABASE,
                            title = if (dbAccess == PageAccessibilityState.CONFIRMED_TRASHED) "" else db.title.joinToString("") { it.plainText },
                            databaseId = null,
                            inTrash = db.inTrash,
                            parent = mapNotionParent(db.parent),
                            createdAt = db.createdTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
                            lastEditedAt = db.lastEditedTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
                            sourceIdentity = sourceIdentity,
                            accessibilityState = dbAccess,
                            sourceRevision = db.lastEditedTime ?: db.createdTime ?: ""
                        )
                    } else {
                        containers += NormalizedContainerMetadata(
                            id = canonicalDbId,
                            kind = ContainerKind.DATABASE,
                            title = "",
                            parent = StructuralParent.Unknown("none", null),
                            sourceIdentity = sourceIdentity,
                            accessibilityState = PageAccessibilityState.NOT_FOUND_OR_UNSHARED
                        )
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e is ReaderLoadException && e.category == ReaderErrorCategory.AUTHORIZATION_REQUIRED) throw e
                    if (e !is ReaderLoadException || e.category !in setOf(ReaderErrorCategory.PERMISSION_DENIED, ReaderErrorCategory.PAGE_NOT_FOUND)) throw e
                    val accessState = if (e is ReaderLoadException && e.category == ReaderErrorCategory.PERMISSION_DENIED) {
                        PageAccessibilityState.RESTRICTED_PERMISSION
                    } else {
                        PageAccessibilityState.NOT_FOUND_OR_UNSHARED
                    }
                    containers += NormalizedContainerMetadata(
                        id = canonicalDbId,
                        kind = ContainerKind.DATABASE,
                        title = "",
                        parent = StructuralParent.Unknown("none", null),
                        sourceIdentity = sourceIdentity,
                        accessibilityState = accessState
                    )
                }
            }
            else -> Unit
        }

        // 6. Bounded internal target resolution
        var budget = maxTargetResolutionRequests
        val resolvedTitles = mutableMapOf<String, String>()
        val targetPages = mutableListOf<StructuralPage>()
        val resolutionCache = mutableMapOf<RelationshipTarget, TargetResolutionResult>()

        val updatedRelationships = recordWithHonestStatus.outgoingRelationships.map { edge ->
            currentCoroutineContext().ensureActive()
            if (edge.resolutionStatus != TargetResolutionStatus.UNRESOLVED_PERMITTED) {
                return@map edge
            }

            val target = edge.target
            val cachedResult = resolutionCache[target]
            if (cachedResult != null) {
                return@map edge.copy(resolutionStatus = cachedResult.status)
            }

            if (budget <= 0) {
                return@map edge
            }

            when (target) {
                is RelationshipTarget.InternalPage -> {
                    budget--
                    val result = try {
                        val resolvedPage = api.retrievePage(target.pageId)
                        if (normalizeNotionId(resolvedPage.id) != normalizeNotionId(target.pageId))
                            throw ReaderLoadException(ReaderErrorCategory.MALFORMED_RESPONSE)
                        val isTrashed = resolvedPage.isTrashed
                        val accessibilityState = if (isTrashed) PageAccessibilityState.CONFIRMED_TRASHED else PageAccessibilityState.ACCESSIBLE
                        val title = if (accessibilityState == PageAccessibilityState.CONFIRMED_TRASHED) "" else resolvedPage.title()
                        val targetPageObj = StructuralPage(
                            id = normalizeNotionId(resolvedPage.id) ?: resolvedPage.id,
                            title = title,
                            parent = mapNotionParent(resolvedPage.parent),
                            databaseId = resolvedPage.parent?.databaseId?.let { normalizeNotionId(it) ?: it },
                            dataSourceId = resolvedPage.parent?.dataSourceId?.let { normalizeNotionId(it) ?: it },
                            sourceIdentity = sourceIdentity,
                            createdAt = resolvedPage.createdTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
                            lastEditedAt = resolvedPage.lastEditedTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
                            accessibilityState = accessibilityState,
                            sourceRevision = resolvedPage.lastEditedTime ?: resolvedPage.createdTime ?: "",
                            syncStatus = SyncCompleteness.UNKNOWN
                        )
                        targetPages += targetPageObj

                        if (isTrashed) {
                            TargetResolutionResult(status = TargetResolutionStatus.UNRESOLVED_INACCESSIBLE)
                        } else {
                            resolvedTitles[target.pageId] = title
                            TargetResolutionResult(status = TargetResolutionStatus.RESOLVED)
                        }
                    } catch (e: ReaderLoadException) {
                        if (e.category == ReaderErrorCategory.AUTHORIZATION_REQUIRED) throw e
                        when (e.category) {
                            ReaderErrorCategory.PAGE_NOT_FOUND ->
                                TargetResolutionResult(status = TargetResolutionStatus.UNRESOLVED_NOT_FOUND)
                            ReaderErrorCategory.PERMISSION_DENIED,
                            ReaderErrorCategory.AUTHORIZATION_REQUIRED ->
                                TargetResolutionResult(status = TargetResolutionStatus.UNRESOLVED_INACCESSIBLE)
                            else -> TargetResolutionResult(status = edge.resolutionStatus)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    if (e is ReaderLoadException && e.category == ReaderErrorCategory.AUTHORIZATION_REQUIRED) throw e
                        TargetResolutionResult(status = edge.resolutionStatus)
                    }
                    if (result.status in setOf(TargetResolutionStatus.UNRESOLVED_NOT_FOUND, TargetResolutionStatus.UNRESOLVED_INACCESSIBLE) &&
                        targetPages.none { it.id == target.pageId }) {
                        targetPages += StructuralPage(target.pageId, "", StructuralParent.Unknown("unavailable", null),
                            sourceIdentity = sourceIdentity, accessibilityState = if (result.status == TargetResolutionStatus.UNRESOLVED_NOT_FOUND)
                                PageAccessibilityState.NOT_FOUND_OR_UNSHARED else PageAccessibilityState.RESTRICTED_PERMISSION,
                            syncStatus = SyncCompleteness.UNKNOWN)
                    }
                    resolutionCache[target] = result
                    edge.copy(resolutionStatus = result.status)
                }
                is RelationshipTarget.InternalDatabase -> {
                    budget--
                    val result = try {
                        val resolvedDb = api.retrieveDatabase(target.databaseId)
                        if (resolvedDb != null) {
                            if (normalizeNotionId(resolvedDb.id) != normalizeNotionId(target.databaseId))
                                throw ReaderLoadException(ReaderErrorCategory.MALFORMED_RESPONSE)
                            containers += NormalizedContainerMetadata(resolvedDb.id, ContainerKind.DATABASE,
                                if (resolvedDb.inTrash) "" else resolvedDb.title.joinToString("") { it.plainText },
                                inTrash = resolvedDb.inTrash, parent = mapNotionParent(resolvedDb.parent), sourceIdentity = sourceIdentity,
                                accessibilityState = if (resolvedDb.inTrash) PageAccessibilityState.CONFIRMED_TRASHED else PageAccessibilityState.ACCESSIBLE,
                                sourceRevision = resolvedDb.lastEditedTime ?: resolvedDb.createdTime ?: "")
                            if (resolvedDb.inTrash) {
                                TargetResolutionResult(status = TargetResolutionStatus.UNRESOLVED_INACCESSIBLE)
                            } else {
                                val title = resolvedDb.title.joinToString("") { it.plainText }
                                resolvedTitles[target.databaseId] = title
                                TargetResolutionResult(status = TargetResolutionStatus.RESOLVED)
                            }
                        } else {
                            // Default fake NotionApi null metadata is unsupported/unresolved, never confirmed deletion
                            TargetResolutionResult(status = edge.resolutionStatus)
                        }
                    } catch (e: ReaderLoadException) {
                        if (e.category == ReaderErrorCategory.AUTHORIZATION_REQUIRED) throw e
                        when (e.category) {
                            ReaderErrorCategory.PAGE_NOT_FOUND ->
                                TargetResolutionResult(status = TargetResolutionStatus.UNRESOLVED_NOT_FOUND)
                            ReaderErrorCategory.PERMISSION_DENIED,
                            ReaderErrorCategory.AUTHORIZATION_REQUIRED ->
                                TargetResolutionResult(status = TargetResolutionStatus.UNRESOLVED_INACCESSIBLE)
                            else -> TargetResolutionResult(status = edge.resolutionStatus)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    if (e is ReaderLoadException && e.category == ReaderErrorCategory.AUTHORIZATION_REQUIRED) throw e
                        TargetResolutionResult(status = edge.resolutionStatus)
                    }
                    if (result.status in setOf(TargetResolutionStatus.UNRESOLVED_NOT_FOUND, TargetResolutionStatus.UNRESOLVED_INACCESSIBLE)) {
                        containers += NormalizedContainerMetadata(target.databaseId, ContainerKind.DATABASE, "", sourceIdentity = sourceIdentity,
                            accessibilityState = if (result.status == TargetResolutionStatus.UNRESOLVED_NOT_FOUND)
                                PageAccessibilityState.NOT_FOUND_OR_UNSHARED else PageAccessibilityState.RESTRICTED_PERMISSION)
                    }
                    resolutionCache[target] = result
                    edge.copy(resolutionStatus = result.status)
                }
                is RelationshipTarget.InternalDataSource -> {
                    budget--
                    val result = try {
                        val resolvedDs = api.retrieveDataSource(target.dataSourceId)
                        if (resolvedDs != null) {
                            if (normalizeNotionId(resolvedDs.id) != normalizeNotionId(target.dataSourceId))
                                throw ReaderLoadException(ReaderErrorCategory.MALFORMED_RESPONSE)
                            containers += NormalizedContainerMetadata(resolvedDs.id, ContainerKind.DATA_SOURCE,
                                if (resolvedDs.inTrash) "" else resolvedDs.name, databaseId = resolvedDs.databaseId,
                                inTrash = resolvedDs.inTrash, parent = mapNotionParent(resolvedDs.parent), sourceIdentity = sourceIdentity,
                                accessibilityState = if (resolvedDs.inTrash) PageAccessibilityState.CONFIRMED_TRASHED else PageAccessibilityState.ACCESSIBLE,
                                sourceRevision = resolvedDs.lastEditedTime ?: resolvedDs.createdTime ?: "")
                            if (resolvedDs.inTrash) {
                                TargetResolutionResult(status = TargetResolutionStatus.UNRESOLVED_INACCESSIBLE)
                            } else {
                                val title = resolvedDs.name
                                resolvedTitles[target.dataSourceId] = title
                                TargetResolutionResult(status = TargetResolutionStatus.RESOLVED)
                            }
                        } else {
                            TargetResolutionResult(status = edge.resolutionStatus)
                        }
                    } catch (e: ReaderLoadException) {
                        if (e.category == ReaderErrorCategory.AUTHORIZATION_REQUIRED) throw e
                        when (e.category) {
                            ReaderErrorCategory.PAGE_NOT_FOUND ->
                                TargetResolutionResult(status = TargetResolutionStatus.UNRESOLVED_NOT_FOUND)
                            ReaderErrorCategory.PERMISSION_DENIED,
                            ReaderErrorCategory.AUTHORIZATION_REQUIRED ->
                                TargetResolutionResult(status = TargetResolutionStatus.UNRESOLVED_INACCESSIBLE)
                            else -> TargetResolutionResult(status = edge.resolutionStatus)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    if (e is ReaderLoadException && e.category == ReaderErrorCategory.AUTHORIZATION_REQUIRED) throw e
                        TargetResolutionResult(status = edge.resolutionStatus)
                    }
                    if (result.status in setOf(TargetResolutionStatus.UNRESOLVED_NOT_FOUND, TargetResolutionStatus.UNRESOLVED_INACCESSIBLE)) {
                        containers += NormalizedContainerMetadata(target.dataSourceId, ContainerKind.DATA_SOURCE, "", sourceIdentity = sourceIdentity,
                            accessibilityState = if (result.status == TargetResolutionStatus.UNRESOLVED_NOT_FOUND)
                                PageAccessibilityState.NOT_FOUND_OR_UNSHARED else PageAccessibilityState.RESTRICTED_PERMISSION)
                    }
                    resolutionCache[target] = result
                    edge.copy(resolutionStatus = result.status)
                }
                else -> edge
            }
        }

        val owningDatabase = containers.firstOrNull { it.kind == ContainerKind.DATA_SOURCE && it.id == recordWithHonestStatus.page.dataSourceId }
            ?.databaseId?.let { normalizeNotionId(it) }
        val fullyResolvedRecord = recordWithHonestStatus.copy(outgoingRelationships = updatedRelationships,
            page = recordWithHonestStatus.page.copy(databaseId = recordWithHonestStatus.page.databaseId ?: owningDatabase))

        currentCoroutineContext().ensureActive()
        return CollectedStructuralData(
            pageRecord = fullyResolvedRecord,
            containerMetadata = containers.firstOrNull(),
            resolvedTargetTitles = resolvedTitles,
            containers = containers.distinctBy { it.id },
            targetPages = targetPages
        )
    }

    private fun mapNotionParent(parent: NotionParent?): StructuralParent {
        if (parent == null) return StructuralParent.Unknown("none", null)
        val type = parent.type.lowercase()
        return when {
            type == "page_id" && parent.pageId != null -> {
                val canonical = normalizeNotionId(parent.pageId) ?: parent.pageId
                StructuralParent.Page(canonical)
            }
            type == "database_id" && parent.databaseId != null -> {
                val canonical = normalizeNotionId(parent.databaseId) ?: parent.databaseId
                StructuralParent.Database(canonical)
            }
            type == "data_source_id" && parent.dataSourceId != null -> {
                val canonical = normalizeNotionId(parent.dataSourceId) ?: parent.dataSourceId
                StructuralParent.DataSource(canonical)
            }
            type == "block_id" && parent.blockId != null -> {
                val canonical = normalizeNotionId(parent.blockId) ?: parent.blockId
                StructuralParent.Block(canonical)
            }
            parent.workspace || type == "workspace" -> StructuralParent.Workspace
            else -> {
                val rawId = parent.pageId ?: parent.databaseId ?: parent.dataSourceId ?: parent.blockId
                StructuralParent.Unknown(parent.type, rawId)
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_TARGET_RESOLUTION_REQUESTS = 25
    }
}


