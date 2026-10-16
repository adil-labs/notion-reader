package com.example.notionreader.data.local

import com.example.notionreader.domain.discovery.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun atomicSemanticWrite(target: File, bytes: ByteArray) {
    target.parentFile!!.mkdirs()
    val staging = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
    try {
        FileOutputStream(staging).use { it.write(bytes); it.fd.sync() }
        try { Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    } finally { staging.delete() }
}

@Serializable
private data class SemanticRecordHeader(
    val pageId: String,
    val revision: String,
    val identity: EmbeddingIdentity,
    val metadata: SemanticMetadata,
    val passages: List<SemanticPassage>
)

/** Atomic document checkpoints: bounded JSON metadata followed by big-endian float32 vectors. */
class FileSemanticIndexStore(private val directory: File, private val maxBytes: Long = 64L * 1024 * 1024,
    private val otherBytes: suspend () -> Long = { 0L }) : SemanticIndexStore {
    private data class CachedHeader(val raw: ByteArray, val header: SemanticRecordHeader)
    private class HeaderCache {
        val entries = java.util.LinkedHashMap<Long, CachedHeader>(16, .75f, true)
        var bytes = 0L
        var passages = 0
    }
    @Volatile private var headers = HeaderCache()
    override fun releaseMemory() { headers = HeaderCache() }
    private fun headerKey(raw: ByteArray) = java.util.zip.CRC32().apply { update(raw) }.value
    private fun cacheHeader(raw: ByteArray, header: SemanticRecordHeader) {
        if (raw.size > 8 * 1024 * 1024 || header.passages.size > 20_000) return
        val cache = headers
        val key = headerKey(raw)
        cache.entries.remove(key)?.let { cache.bytes -= it.raw.size; cache.passages -= it.header.passages.size }
        cache.entries[key] = CachedHeader(raw, header)
        cache.bytes += raw.size; cache.passages += header.passages.size
        while (cache.bytes > 8 * 1024 * 1024 || cache.passages > 20_000 || cache.entries.size > 256) {
            val iterator = cache.entries.entries.iterator()
            val first = iterator.next().value
            cache.bytes -= first.raw.size; cache.passages -= first.header.passages.size
            iterator.remove()
        }
    }
    private fun discardHeader(pageId: String) {
        val cache = headers
        val iterator = cache.entries.entries.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next().value
            if (canonicalSemanticId(item.header.pageId) == canonicalSemanticId(pageId)) {
                cache.bytes -= item.raw.size; cache.passages -= item.header.passages.size; iterator.remove()
            }
        }
    }
    private companion object {
        const val MAGIC = 0x4e525332 // NRS2
        const val FORMAT = 2
        const val PREFIX_BYTES = 12
    }
    private val mutex = Mutex()
    // IDs only: vector payloads remain streamed per document. One writer owns this store.
    private var membership: MutableSet<String>? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    init {
        require(maxBytes > PREFIX_BYTES)
        directory.mkdirs()
        directory.listFiles { f -> f.extension == "tmp" }?.forEach { it.delete() }
    }
    private fun file(id: String) = File(directory, "${semanticHash(canonicalSemanticId(id))}.record")
    private fun validHeader(header: SemanticRecordHeader): Boolean = header.identity.storage == FORMAT &&
        header.identity.dimensions > 0 && header.passages.all { passage ->
            canonicalSemanticId(passage.pageId) == canonicalSemanticId(header.pageId) &&
                passage.start >= 0 && passage.end >= passage.start && passage.excerpt.length == passage.end - passage.start
        }
    private fun payloadBytes(header: SemanticRecordHeader): Long = Math.multiplyExact(
        Math.multiplyExact(header.passages.size.toLong(), header.identity.dimensions.toLong()), 4L)

    /** Reads only metadata; rejects truncated/extended payloads without parsing any vector. */
    private fun readHeader(input: DataInputStream, length: Long): SemanticRecordHeader {
        require(length in PREFIX_BYTES.toLong()..maxBytes)
        require(input.readInt() == MAGIC && input.readInt() == FORMAT)
        val headerSize = input.readInt()
        require(headerSize > 0 && headerSize.toLong() <= length - PREFIX_BYTES)
        val bytes = ByteArray(headerSize)
        input.readFully(bytes)
        val cached = headers.entries[headerKey(bytes)]?.takeIf { it.raw.contentEquals(bytes) }
        val header = cached?.header ?: json.decodeFromString<SemanticRecordHeader>(bytes.toString(Charsets.UTF_8))
        require(validHeader(header))
        require(Math.addExact(PREFIX_BYTES.toLong() + headerSize, payloadBytes(header)) == length)
        if (cached == null) cacheHeader(bytes, header)
        return header
    }

    private fun readLocked(id: String): SemanticRecord? {
        val target = file(id)
        if (!target.isFile) { membership?.remove(canonicalSemanticId(id)); discardHeader(id); return null }
        val record = runCatching {
            DataInputStream(target.inputStream().buffered()).use { input ->
                val header = readHeader(input, target.length())
                require(canonicalSemanticId(header.pageId) == canonicalSemanticId(id))
                val payloadSize = payloadBytes(header)
                require(payloadSize <= Int.MAX_VALUE)
                val payload = ByteArray(payloadSize.toInt())
                input.readFully(payload)
                val floats = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                val passages = header.passages.map { passage ->
                    val vector = PackedNormalizedVector.fromNormalized(FloatArray(header.identity.dimensions) { floats.float })
                    EmbeddedPassage(passage, vector)
                }
                SemanticRecord(header.pageId, header.revision, header.identity, header.metadata, passages)
            }
        }.getOrNull()
        if (record == null) {
            discardHeader(id)
            check(target.delete() || !target.exists()) { "Unable to invalidate corrupt semantic record" }
            membership?.remove(canonicalSemanticId(id))
        }
        return record
    }
    override suspend fun read(pageId: String): SemanticRecord? = withContext(Dispatchers.IO) { mutex.withLock { readLocked(pageId) } }
    override suspend fun pageIds(): Set<String> = withContext(Dispatchers.IO) { mutex.withLock {
        val files = directory.listFiles { f -> f.extension == "record" }.orEmpty()
        membership?.let { known ->
            if (files.map { it.name }.toSet() == known.map { file(it).name }.toSet()) return@withLock known.toSet()
        }
        buildSet {
            files.forEach { f ->
                val header = runCatching { DataInputStream(f.inputStream().buffered()).use { readHeader(it, f.length()) } }.getOrNull()
                if (header != null && file(header.pageId).name == f.name) add(canonicalSemanticId(header.pageId))
                else {
                    releaseMemory()
                    check(f.delete() || !f.exists()) { "Unable to invalidate corrupt semantic header" }
                }
            }
        }.also { membership = it.toMutableSet() }
    } }
    override suspend fun write(record: SemanticRecord): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        val target = file(record.pageId)
        val header = SemanticRecordHeader(record.pageId, record.revision, record.identity, record.metadata, record.passages.map { it.passage })
        require(validHeader(header))
        record.passages.forEach { embedded ->
            require(embedded.vector.size == record.identity.dimensions && embedded.vector.all { it.isFinite() })
            require(kotlin.math.abs(embedded.vector.sumOf { it.toDouble() * it } - 1.0) < .001)
        }
        val metadata = json.encodeToString(header).toByteArray(Charsets.UTF_8)
        val size = Math.addExact(PREFIX_BYTES.toLong() + metadata.size, payloadBytes(header))
        val other = directory.listFiles { f -> f.isFile && f != target }?.sumOf { it.length() } ?: 0
        val external = otherBytes()
        // The old record still exists while the replacement is staged atomically.
        if (external < 0 || size > Int.MAX_VALUE || size + other + target.length() + external > maxBytes) false else {
            val buffer = ByteBuffer.allocate(size.toInt()).order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(MAGIC).putInt(FORMAT).putInt(metadata.size).put(metadata)
            record.passages.forEach { embedded -> embedded.vector.forEach { buffer.putFloat(it) } }
            atomicSemanticWrite(target, buffer.array())
            discardHeader(record.pageId)
            cacheHeader(metadata, header)
            membership?.add(canonicalSemanticId(record.pageId))
            true
        }
    } }
    override suspend fun remove(pageId: String): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        val target = file(pageId)
        check(!target.exists() || target.delete()) { "Unable to remove semantic record" }
        discardHeader(pageId)
        membership?.remove(canonicalSemanticId(pageId))
    } }
    override suspend fun clear(): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        releaseMemory()
        atomicSemanticWrite(File(directory, "manifest.json"), "{\"version\":2,\"paused\":true}".toByteArray())
        directory.listFiles { f -> f.extension == "record" || f.extension == "tmp" }?.forEach { check(it.delete()) { "Unable to clear semantic record" } }
        membership?.clear()
    } }
    override suspend fun bytes(): Long = withContext(Dispatchers.IO) { mutex.withLock { directory.listFiles()?.sumOf { it.length() } ?: 0L } }
    override suspend fun isPaused(): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        val manifest = File(directory, "manifest.json")
        if (!manifest.exists()) true else runCatching {
            val obj = json.parseToJsonElement(manifest.readText()) as kotlinx.serialization.json.JsonObject
            require(obj["version"].toString() == "2")
            obj["paused"].toString() != "false"
        }.getOrDefault(true)
    } }
    override suspend fun setPaused(paused: Boolean): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        atomicSemanticWrite(File(directory, "manifest.json"), "{\"version\":2,\"paused\":$paused}".toByteArray())
    } }
}

class FileDiscoveryInterestStore(private val directory: File) : DiscoveryInterestStore {
    private val mutex = Mutex()
    private val file get() = File(directory, "interests.json")
    override suspend fun read(): List<DiscoveryInterest> = withContext(Dispatchers.IO) { mutex.withLock {
        if (!file.isFile) emptyList() else runCatching { Json.decodeFromString<List<DiscoveryInterest>>(file.readText()) }.getOrDefault(emptyList())
    } }
    override suspend fun write(interests: List<DiscoveryInterest>): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        atomicSemanticWrite(file, Json.encodeToString(interests).toByteArray())
    } }
}
