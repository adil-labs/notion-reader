package com.example.notionreader.data.local

import com.example.notionreader.domain.discovery.NoteConnection
import com.example.notionreader.domain.discovery.semanticHash
import com.example.notionreader.domain.discovery.EmbeddingIdentity
import com.example.notionreader.domain.discovery.RankedConnectionCatalog
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class ConnectionCatalogPage(val fileName: String, val count: Int, val digest: String,
    val pairIds: List<String> = emptyList())

@Serializable
internal data class ConnectionCatalogManifest(val pages: List<ConnectionCatalogPage>, val count: Int,
    val identity: EmbeddingIdentity? = null, val namedReferences: Int = -1, val ranked: Boolean = false)

internal interface OpenConnectionCatalog : List<NoteConnection> {
    val manifest: ConnectionCatalogManifest
    val ownerDirectory: File
}

/** Immutable pages become visible only when FileTopicStore commits their manifest. */
internal class FileConnectionCatalog(private val directory: File, private val json: Json) {
    companion object { const val PAGE_SIZE = 64 }
    private val pageName = Regex("connections-[a-f0-9-]{36}-[0-9]+\\.json")

    fun valid(manifest: ConnectionCatalogManifest): Boolean = manifest.count >= 0 &&
        manifest.namedReferences in -1..manifest.count &&
        manifest.pages.sumOf { it.count.toLong() } == manifest.count.toLong() &&
        manifest.pages.map { it.fileName }.distinct().size == manifest.pages.size &&
        manifest.pages.all { pageName.matches(it.fileName) && it.count in 1..PAGE_SIZE &&
            File(directory, it.fileName).isFile }

    fun encode(connections: List<NoteConnection>): ByteArray = json.encodeToString(connections).toByteArray(Charsets.UTF_8)

    private fun pageValues(page: ConnectionCatalogPage): List<NoteConnection> {
        val text = File(directory, page.fileName).readText()
        require(semanticHash(text) == page.digest) { "Corrupt connection catalog page" }
        val values = json.decodeFromString<List<NoteConnection>>(text)
        require(values.size == page.count && (page.pairIds.isEmpty() || values.map { it.id } == page.pairIds)) {
            "Incomplete connection catalog page"
        }
        return values
    }

    /** Worker-local view: no file-backed list is published to UI state. Retains one decoded page. */
    fun open(manifest: ConnectionCatalogManifest): List<NoteConnection> {
        require(valid(manifest)) { "Invalid connection catalog" }
        val descriptor = manifest
        open class View : AbstractList<NoteConnection>(), OpenConnectionCatalog {
            override val manifest = descriptor
            override val ownerDirectory = directory
            override val size = manifest.count
            private var start = -1
            private var values = emptyList<NoteConnection>()
            @Synchronized override fun get(index: Int): NoteConnection {
                if (index !in 0 until size) throw IndexOutOfBoundsException(index.toString())
                if (start >= 0 && index in start until start + values.size) return values[index - start]
                var offset = 0
                for (page in manifest.pages) {
                    if (index < offset + page.count) {
                        values = pageValues(page); start = offset
                        return values[index - start]
                    }
                    offset += page.count
                }
                error("Incomplete catalog")
            }
        }
        return if (manifest.ranked) object : View(), RankedConnectionCatalog {} else View()
    }

    fun find(manifest: ConnectionCatalogManifest, id: String): NoteConnection? {
        require(valid(manifest))
        for (page in manifest.pages) {
            if (page.pairIds.isNotEmpty() && id !in page.pairIds) continue
            pageValues(page).firstOrNull { it.id == id }?.let { return it }
        }
        return null
    }

    fun read(manifest: ConnectionCatalogManifest, offset: Int = 0, limit: Int = manifest.count): List<NoteConnection> {
        require(valid(manifest)) { "Invalid connection catalog" }
        if (offset >= manifest.count || limit == 0) return emptyList()
        val result = mutableListOf<NoteConnection>()
        var position = 0L
        val end = minOf(manifest.count.toLong(), offset.toLong() + limit)
        for (page in manifest.pages) {
            val next = position + page.count
            if (next > offset && position < end) {
                val values = pageValues(page)
                result.addAll(values.subList(maxOf(0L, offset - position).toInt(), minOf(page.count.toLong(), end - position).toInt()))
            }
            position = next
            if (position >= end) break
        }
        require(result.size.toLong() == end - offset) { "Incomplete connection catalog" }
        return result
    }
}
