package com.example.notionreader.data.structure

import com.example.notionreader.data.notion.*
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
import com.example.notionreader.domain.structure.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant

/** Additional native inputs to the existing Browse/cache pipeline, never an authoritative workspace catalog. */
class StructuralDiscoveryContinuation {
    internal data class Position(var cursor: String? = null, val cursors: MutableSet<String> = mutableSetOf(),
        val pages: MutableSet<String> = mutableSetOf(), var requests: Int = 0)
    private var sourceIdentity = ""
    private var sourceEpoch = -1L
    private val positions = linkedMapOf<String, Position>()
    fun clear() { positions.clear(); sourceIdentity = ""; sourceEpoch = -1 }
    internal fun begin(identity: String, epoch: Long) {
        if (sourceIdentity != identity || sourceEpoch != epoch) { clear(); sourceIdentity = identity; sourceEpoch = epoch }
    }
    internal fun position(id: String): Position? {
        positions[id]?.let { return it }
        // Keep admitted cursors until completion; eviction before the next rotation
        // can otherwise restart every source forever when inventory exceeds capacity.
        if (positions.size >= 200) return null
        return Position().also { positions[id] = it }
    }
    internal fun retainSources(ids: Set<String>) { positions.keys.retainAll(ids) }
    internal fun complete(id: String) { positions.remove(id) }
}

class NotionStructuralDiscovery(private val api: NotionApi,
    private val continuation: StructuralDiscoveryContinuation = StructuralDiscoveryContinuation()) {
    data class Result(val complete: Boolean, val sources: Int, val pages: Int)
    suspend fun collect(identity: String, onContainer: suspend (StructuralContainer) -> Unit,
        onPage: suspend (StructuralPage) -> Unit, maxSources: Int = 200, maxPages: Int = 20_000,
        sourceEpoch: Long = 0, startAfterSourceId: String? = null, maxRequestsPerSource: Int = 5,
        onSourceAttempt: suspend (String) -> Unit = {}): Result {
        require(maxSources in 1..200 && maxPages in 1..20_000 && maxRequestsPerSource in 1..500)
        continuation.begin(identity, sourceEpoch)
        val sources = api.searchDataSources().sortedBy { normalizeNotionId(it.id) ?: it.id }
        // Search absence can discard an ephemeral cursor, never persisted source facts.
        continuation.retainSources(sources.mapNotNull { normalizeNotionId(it.id) }.toSet())
        val start = startAfterSourceId?.let { previous -> sources.indexOfFirst { (normalizeNotionId(it.id) ?: it.id) > previous } }
            ?.takeIf { it >= 0 } ?: 0
        val window = (sources.drop(start) + sources.take(start)).take(maxSources)
        var visitedPages = 0
        var complete = sources.size <= maxSources
        val databases = mutableSetOf<String>()
        for (source in window) {
            currentCoroutineContext().ensureActive()
            val id = normalizeNotionId(source.id) ?: throw ReaderLoadException(ReaderErrorCategory.MALFORMED_RESPONSE)
            onSourceAttempt(id) // Durable native identity advances even if this source consumes the window.
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
            val position = continuation.position(id)
            if (position == null) { complete = false; continue }
            var requestsInSlice = 0
            do {
                currentCoroutineContext().ensureActive()
                if (visitedPages >= maxPages) return Result(false, window.size, visitedPages)
                if (++requestsInSlice > maxRequestsPerSource) { complete = false; break }
                if (++position.requests > 500) { continuation.complete(id); throw ReaderLoadException(ReaderErrorCategory.MALFORMED_RESPONSE) }
                val response = try { api.queryDataSource(id, 100, position.cursor) }
                catch (failure: ReaderLoadException) {
                    if (failure.category == ReaderErrorCategory.MALFORMED_RESPONSE) continuation.complete(id)
                    if (failure.category !in setOf(ReaderErrorCategory.PERMISSION_DENIED, ReaderErrorCategory.PAGE_NOT_FOUND)) throw failure
                    // A denied resource cannot advance its cursor. Release only its
                    // ephemeral slot so it cannot starve newly discovered resources.
                    continuation.complete(id)
                    complete = false
                    break
                }
                response.validateEnvelope()
                val pageIds = mutableSetOf<String>()
                for (page in response.results) {
                    val metadata = page.structuralMetadata(identity) ?: throw ReaderLoadException(ReaderErrorCategory.MALFORMED_RESPONSE)
                    if (metadata.id in position.pages || !pageIds.add(metadata.id)) {
                        continuation.complete(id)
                        throw ReaderLoadException(ReaderErrorCategory.MALFORMED_RESPONSE)
                    }
                    if (++visitedPages > maxPages) return Result(false, sources.size.coerceAtMost(maxSources), visitedPages - 1)
                    onPage(metadata)
                }
                val cursor = if (response.hasMore) response.nextCursor?.takeIf { it.isNotBlank() && position.cursors.add(it) }
                    ?: run { continuation.complete(id); throw ReaderLoadException(ReaderErrorCategory.MALFORMED_RESPONSE) } else null
                position.pages += pageIds
                position.cursor = cursor
                if (cursor == null) continuation.complete(id)
            } while (position.cursor != null)
        }
        return Result(complete, sources.size.coerceAtMost(maxSources), visitedPages)
    }
    private fun String?.instant(): Instant? = this?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
