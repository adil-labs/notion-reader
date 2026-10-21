package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class GroupedConnectionPage(val page: ConnectionCatalogPageResult, val namedCount: Int)

/** A reading-order projection; worker ranking and stored evidence stay unchanged. */
internal class ConnectionCatalogProjection(private val readPage: suspend (TopicSnapshot, Int, Int) -> ConnectionCatalogPageResult) {
    private data class Key(val reference: ConnectionCatalogReference?, val inline: List<NoteConnection>?, val token: DiscoveryReadToken)
    private data class Routes(val key: Key, val positions: IntArray, val namedCount: Int)
    private val mutex = Mutex()
    @Volatile private var routes: Routes? = null
    fun clear() { routes = null }

    suspend fun read(snapshot: TopicSnapshot, titles: Map<String, String>, token: DiscoveryReadToken,
        offset: Int, limit: Int, isCurrent: () -> Boolean): GroupedConnectionPage = mutex.withLock {
        require(offset >= 0 && limit >= 0)
        val total = snapshot.catalogReference?.count ?: snapshot.connections.size
        fun unavailable() = GroupedConnectionPage(ConnectionCatalogPageResult(emptyList(), total, false), 0)
        if (!isCurrent()) return@withLock unavailable()
        val key = Key(snapshot.catalogReference, snapshot.connections.takeIf { snapshot.catalogReference == null }, token)
        var projection = routes?.takeIf { it.key == key }
        if (projection == null) {
            val named = ArrayList<Int>()
            val similar = ArrayList<Int>()
            var position = 0
            while (position < total) {
                currentCoroutineContext().ensureActive()
                val size = minOf(64, total - position)
                val page = readPage(snapshot, position, size)
                if (!isCurrent() || !page.available || page.totalCount != total || page.connections.size != size)
                    return@withLock unavailable()
                for ((index, pair) in page.connections.withIndex()) {
                    val kind = classifyConnectionEvidence(pair, titles).kind
                    if (pair.independentNamedReference && kind != ConnectionEvidenceKind.NamedReference)
                        return@withLock unavailable()
                    if (kind == ConnectionEvidenceKind.NamedReference) named += position + index
                    else similar += position + index
                }
                position += size
            }
            projection = Routes(key, (named + similar).toIntArray(), named.size)
            if (!isCurrent()) return@withLock unavailable()
            routes = projection
        }
        val end = minOf(total.toLong(), offset.toLong() + limit).toInt()
        val requested = (minOf(offset, total) until end).map { projection.positions[it] }
        val rows = HashMap<Int, NoteConnection>()
        // Read each source page once, even when the group's ranked positions are interleaved.
        // Only requested rows survive this loop; other literal evidence is immediately released.
        for ((block, positions) in requested.groupBy { it / 64 }) {
            currentCoroutineContext().ensureActive()
            val start = block * 64
            val size = minOf(64, total - start)
            val page = readPage(snapshot, start, size)
            if (!isCurrent() || !page.available || page.totalCount != total || page.connections.size != size)
                return@withLock unavailable()
            positions.forEach { rows[it] = page.connections[it - start] }
        }
        if (!isCurrent()) return@withLock unavailable()
        GroupedConnectionPage(ConnectionCatalogPageResult(requested.map { rows.getValue(it) }, total), projection.namedCount)
    }
}
