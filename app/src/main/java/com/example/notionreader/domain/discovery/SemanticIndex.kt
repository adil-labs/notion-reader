package com.example.notionreader.domain.discovery

import com.example.notionreader.domain.ReaderIndexEntry
import kotlinx.serialization.Serializable

@Serializable
data class EmbeddingIdentity(
    val model: String,
    val dimensions: Int,
    val tokenizer: String = "bert-uncased-v1",
    val preprocessing: Int = 1,
    val chunker: Int = 1,
    val storage: Int = 2
)

data class SemanticToken(val id: Int, val start: Int, val end: Int)
interface SemanticTokenizer { fun tokens(text: String): List<SemanticToken> }

interface EmbeddingProvider {
    val identity: EmbeddingIdentity
    val tokenizer: SemanticTokenizer
    suspend fun embed(text: String): List<Float>
    fun release() {}
}

@Serializable
data class SemanticPassage(
    val id: String, val pageId: String, val sectionId: String, val sectionTitle: String?,
    val sourceBlockId: String, val readerChunkId: String, val start: Int, val end: Int,
    val excerpt: String, val input: String, val order: Int
)

@Serializable
data class SemanticMetadata(
    val title: String, val lastEditedAt: String? = null, val kind: String? = null,
    val area: String? = null, val topics: Set<String> = emptySet(), val project: String? = null,
    val status: String? = null
) {
    fun entry(pageId: String) = ReaderIndexEntry(pageId, title,
        lastEditedAt?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() },
        kind, area, topics, project, status)
    companion object {
        fun from(entry: ReaderIndexEntry) = SemanticMetadata(entry.title, entry.lastEditedAt?.toString(),
            entry.kind, entry.area, entry.topics, entry.project, entry.status)
    }
}

@Serializable
data class EmbeddedPassage(val passage: SemanticPassage, val vector: List<Float>)

/** Stable across Browse ordering; callers supply only cache-eligible metadata. */
fun semanticMetadataHash(metadata: Map<String, SemanticMetadata>): Int =
    metadata.toSortedMap().entries.toList().hashCode()

@Serializable
data class SemanticRecord(val pageId: String, val revision: String, val identity: EmbeddingIdentity,
    val metadata: SemanticMetadata, val passages: List<EmbeddedPassage>)

data class SemanticMatch(val pageId: String, val metadata: SemanticMetadata,
    val passage: SemanticPassage, val score: Float)

interface SemanticIndexStore {
    /** Drop optional read acceleration under resource pressure; never changes persisted data. */
    fun releaseMemory() {}
    suspend fun pageIds(): Set<String>
    suspend fun read(pageId: String): SemanticRecord?
    /** False means the complete record would exceed the storage budget. */
    suspend fun write(record: SemanticRecord): Boolean
    suspend fun remove(pageId: String)
    suspend fun clear()
    suspend fun bytes(): Long
    suspend fun isPaused(): Boolean
    suspend fun setPaused(paused: Boolean)
}

@Serializable
data class DiscoveryInterest(val id: String, val text: String = "", val seedPageId: String? = null,
    val displayName: String? = null, val originTopicId: String? = null) {
    init { require((text.isNotBlank()) != (seedPageId != null)) }
}
interface DiscoveryInterestStore {
    suspend fun read(): List<DiscoveryInterest>
    suspend fun write(interests: List<DiscoveryInterest>)
}

fun canonicalSemanticId(id: String): String = id.trim().lowercase(java.util.Locale.ROOT).replace("-", "")
