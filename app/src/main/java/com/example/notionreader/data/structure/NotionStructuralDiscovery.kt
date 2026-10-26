package com.example.notionreader.data.structure

import com.example.notionreader.data.notion.*
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
import com.example.notionreader.domain.structure.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant

/** Additional native inputs to the existing Browse/cache pipeline, never an authoritative workspace catalog. */
class NotionStructuralDiscovery(private val api: NotionApi) {
    data class Result(val complete: Boolean, val sources: Int, val pages: Int)
    suspend fun collect(identity: String, onContainer: suspend (StructuralContainer) -> Unit,
        onPage: suspend (StructuralPage) -> Unit, maxSources: Int = 200, maxPages: Int = 20_000): Result {
        val sources = api.searchDataSources()
        var visitedPages = 0
        var complete = sources.size <= maxSources
        val databases = mutableSetOf<String>()
        for (source in sources.take(maxSources)) {
            currentCoroutineContext().ensureActive()
            val id = normalizeNotionId(source.id) ?: throw ReaderLoadException(ReaderErrorCategory.MALFORMED_RESPONSE)
            val databaseId = source.databaseId?.let(::normalizeNotionId)
            val parent = databaseId?.let { StructuralParent.Database(it) } ?: StructuralParent.Unknown(source.parent?.type ?: "none", null)
            onContainer(StructuralContainer(id, StructuralContainerKind.DATA_SOURCE, if (source.inTrash) "" else source.name, parent,
                identity, source.lastEditedTime ?: source.createdTime ?: "", source.createdTime.instant(), source.lastEditedTime.instant(),
                if (source.inTrash) PageAccessibilityState.CONFIRMED_TRASHED else PageAccessibilityState.ACCESSIBLE))
            if (source.inTrash) continue
            if (databaseId != null && databases.add(databaseId)) {
                try {
                    api.retrieveDatabase(databaseId)?.let { database ->
                        val metadata = NotionPage(database.id, parent = database.parent).structuralMetadata(identity)
                        onContainer(StructuralContainer(databaseId, StructuralContainerKind.DATABASE,
                            if (database.inTrash) "" else database.title.joinToString("") { it.plainText }, metadata?.parent ?: StructuralParent.Unknown("none", null),
                            identity, database.lastEditedTime ?: database.createdTime ?: "", database.createdTime.instant(), database.lastEditedTime.instant(),
                            if (database.inTrash) PageAccessibilityState.CONFIRMED_TRASHED else PageAccessibilityState.ACCESSIBLE))
                    }
                } catch (failure: ReaderLoadException) {
                    if (failure.category !in setOf(ReaderErrorCategory.PERMISSION_DENIED, ReaderErrorCategory.PAGE_NOT_FOUND)) throw failure
                    onContainer(StructuralContainer(databaseId, StructuralContainerKind.DATABASE, "", StructuralParent.Unknown("unavailable", null), identity,
                        accessibilityState = if (failure.category == ReaderErrorCategory.PERMISSION_DENIED) PageAccessibilityState.RESTRICTED_PERMISSION else PageAccessibilityState.NOT_FOUND_OR_UNSHARED))
                }
            }
            var cursor: String? = null
            val cursors = mutableSetOf<String>()
            val pageIds = mutableSetOf<String>()
            var requests = 0
            do {
                currentCoroutineContext().ensureActive()
                if (++requests > 500 || visitedPages >= maxPages) return Result(false, sources.size.coerceAtMost(maxSources), visitedPages)
                val response = try { api.queryDataSource(id, 100, cursor) }
                catch (failure: ReaderLoadException) {
                    if (failure.category !in setOf(ReaderErrorCategory.PERMISSION_DENIED, ReaderErrorCategory.PAGE_NOT_FOUND)) throw failure
                    complete = false
                    break
                }
                response.validateEnvelope()
                for (page in response.results) {
                    val metadata = page.structuralMetadata(identity) ?: throw ReaderLoadException(ReaderErrorCategory.MALFORMED_RESPONSE)
                    if (!pageIds.add(metadata.id)) throw ReaderLoadException(ReaderErrorCategory.MALFORMED_RESPONSE)
                    if (++visitedPages > maxPages) return Result(false, sources.size.coerceAtMost(maxSources), visitedPages - 1)
                    onPage(metadata)
                }
                cursor = if (response.hasMore) response.nextCursor?.takeIf { it.isNotBlank() && cursors.add(it) }
                    ?: throw ReaderLoadException(ReaderErrorCategory.MALFORMED_RESPONSE) else null
            } while (cursor != null)
        }
        return Result(complete, sources.size.coerceAtMost(maxSources), visitedPages)
    }
    private fun String?.instant(): Instant? = this?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
