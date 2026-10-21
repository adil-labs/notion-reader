package com.example.notionreader.data.local

import com.example.notionreader.data.document.CachedDocument
import com.example.notionreader.data.document.DocumentCache
import com.example.notionreader.domain.document.DocumentCacheStats
import com.example.notionreader.domain.document.CacheRemovalReason
import com.example.notionreader.domain.document.CacheInvalidation
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * File-backed atomic document cache storing each [CachedDocument] as a JSON file.
 *
 * Writes to a uniquely named temporary `.tmp` file and performs an atomic rename to guarantee that
 * an uncompleted write never corrupts the cached document on disk. All write mutations are serialized
 * via a process-scoped [Mutex] to eliminate file collision between concurrent loads and prefetches.
 */
class FileDocumentCache(
    private val directory: File,
    private val maxEntries: Int = 200,
    private val maxBytes: Long = com.example.notionreader.domain.document.StorageDefaults.NOTE_BYTES,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val byteLimit: () -> Long = { maxBytes },
    private val protectedPageIds: () -> Set<String> = { emptySet() },
    private val writeGate: com.example.notionreader.domain.document.StorageWriteGate = com.example.notionreader.domain.document.StorageWriteGate.Unrestricted
) : DocumentCache {

    private val writeMutex = Mutex()
    private val pending = kotlinx.coroutines.flow.MutableStateFlow<List<CacheInvalidation>>(emptyList())
    override val invalidations: kotlinx.coroutines.flow.StateFlow<List<CacheInvalidation>> = pending
    private val journal get() = File(directory, ".invalidations")

    // Write-ahead records make permanent access loss recoverable after process death. Never silently
    // truncate unacknowledged work: backpressure preserves the evidence needed to purge provenance.
    private fun persistEvents(events: List<CacheInvalidation>, generation: Long) {
        val text = buildString {
            append(generation).append('\n')
            events.forEach { event ->
                append(event.generation).append('\t').append(event.reason.name).append('\t')
                append(event.pageId?.let { java.util.Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) } ?: "-")
                append('\n')
            }
        }
        check(text.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "Cache invalidation backlog requires acknowledgement" }
        val temp = File(directory, ".invalidations.tmp")
        FileOutputStream(temp).use { out -> out.write(text.toByteArray(Charsets.UTF_8)); out.fd.sync() }
        try {
            Files.move(temp.toPath(), journal.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), journal.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun record(pageId: String?, reason: CacheRemovalReason) {
        val generation = mutations.value + 1
        val events = pending.value + CacheInvalidation(pageId?.trim()?.lowercase()?.replace("-", ""), reason, generation)
        persistEvents(events, generation)
        mutations.value = generation
        pending.value = events
    }

    private fun removeFile(file: File, reason: CacheRemovalReason, pageId: String = file.nameWithoutExtension): Boolean {
        if (!file.exists()) return false
        record(pageId, reason)
        return file.delete()
    }

    override suspend fun acknowledgeInvalidations(throughGeneration: Long): Unit = withContext(ioDispatcher) {
        writeMutex.withLock {
            val retained = pending.value.filter { it.generation > throughGeneration }
            persistEvents(retained, mutations.value)
            pending.value = retained
        }
    }
    private val mutations = kotlinx.coroutines.flow.MutableStateFlow(0L)
    override val revision: kotlinx.coroutines.flow.StateFlow<Long> = mutations

    private suspend fun <T> mutate(action: suspend () -> T): T = writeMutex.withLock {
        val initialGeneration = mutations.value
        var admissionRefused = false
        try { action() } catch (refused: com.example.notionreader.domain.document.StorageRefused) {
            admissionRefused = mutations.value == initialGeneration
            throw refused
        } finally {
            if (!admissionRefused) {
            val generation = mutations.value + 1
            persistEvents(pending.value, generation)
            mutations.value = generation
            }
        }
    }

    override suspend fun snapshot(): List<com.example.notionreader.domain.document.CachedDocument> = withContext(ioDispatcher) {
        writeMutex.withLock {
            directory.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty().mapNotNull { f ->
                val cached = runCatching { ReaderDocumentCodec.decode(f.readText()) }.getOrNull()
                if (cached == null) removeFile(f, CacheRemovalReason.CORRUPT)
                cached
            }
        }
    }


    init {
        if (!directory.exists()) {
            directory.mkdirs()
        }
        if (journal.exists()) {
            // A malformed lifecycle journal must fail closed rather than forget revocations.
            val lines = journal.readLines()
            mutations.value = lines.first().toLong()
            pending.value = lines.drop(1).filter { it.isNotBlank() }.map { line ->
                val parts = line.split('\t')
                CacheInvalidation(if (parts[2] == "-") null else String(java.util.Base64.getDecoder().decode(parts[2]), Charsets.UTF_8),
                    CacheRemovalReason.valueOf(parts[1]), parts[0].toLong())
            }
            val removed = pending.value.mapNotNull { it.pageId }.toSet()
            val cleared = pending.value.any { it.pageId == null }
            directory.listFiles { file -> file.isFile && file.extension == "json" }.orEmpty().forEach { file ->
                if (cleared || file.nameWithoutExtension.lowercase().replace("-", "") in removed) {
                    check(file.delete()) { "Cannot finish pending cache removal" }
                }
            }
        }
    }

    override suspend fun get(pageId: String): CachedDocument? = withContext(ioDispatcher) {
        val file = fileFor(pageId)
        if (!file.exists() || !file.isFile) return@withContext null

        val content = try {
            file.readText()
        } catch (_: Exception) {
            return@withContext null
        }

        val cached = ReaderDocumentCodec.decode(content)
        if (cached == null) {
            // Delete corrupt or incompatible cache file
            mutate {
                removeFile(file, CacheRemovalReason.CORRUPT, pageId)
            }
            return@withContext null
        }

        // Update touch timestamp for LRU
        try {
            file.setLastModified(System.currentTimeMillis())
        } catch (_: Exception) {
            // Non-fatal if filesystem doesn't allow updating timestamp
        }

        cached
    }

    override suspend fun put(document: CachedDocument): Unit = withContext(ioDispatcher) {
        val pageId = document.document.pageId
        val targetFile = fileFor(pageId)
        // Unique temporary file to avoid collisions between concurrent writes for the same page
        val tempFile = File(directory, "${targetFile.nameWithoutExtension}-${UUID.randomUUID()}.tmp")
        val json = ReaderDocumentCodec.encode(document)

        mutate {
            writeGate.write(com.example.notionreader.domain.document.StorageCategory.NOTES, json.toByteArray(Charsets.UTF_8).size.toLong() + 2L*1024*1024) {
            if (!directory.exists()) {
                directory.mkdirs()
            }

            try {
                FileOutputStream(tempFile).use { fos ->
                    val bytes = json.toByteArray(Charsets.UTF_8)
                    fos.write(bytes)
                    fos.flush()
                    try {
                        fos.fd.sync()
                    } catch (_: Exception) {
                        // Ignored if sync not supported by test/virtual FS
                    }
                }

                try {
                    Files.move(
                        tempFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: Exception) {
                    // Fallback for filesystems that do not support ATOMIC_MOVE.
                    // Replace existing directly without prematurely deleting targetFile.
                    try {
                        Files.move(
                            tempFile.toPath(),
                            targetFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    } catch (_: Exception) {
                        tempFile.renameTo(targetFile)
                    }
                }
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }

            enforceBudgetLocked(protectedPageIds())
            }
        }
    }

    override suspend fun remove(pageId: String) = remove(pageId, CacheRemovalReason.EVICTED)

    override suspend fun remove(pageId: String, reason: CacheRemovalReason): Unit = withContext(ioDispatcher) {
        mutate {
            val file = fileFor(pageId)
            if (reason == CacheRemovalReason.DELETED || reason == CacheRemovalReason.PERMISSION_DENIED) {
                record(pageId, reason)
                file.delete()
            } else {
                removeFile(file, reason, pageId)
            }
        }
    }

    override suspend fun clear() = clear(CacheRemovalReason.CLEARED)

    override suspend fun clear(reason: CacheRemovalReason): Unit = withContext(ioDispatcher) {
        require(reason == CacheRemovalReason.CLEARED || reason == CacheRemovalReason.RESET)
        mutate {
            record(null, reason)
            directory.listFiles { file -> file.extension == "json" || file.extension == "tmp" }?.forEach { it.delete() }
        }
    }

    override suspend fun getMostRecentlyUsed(): CachedDocument? = withContext(ioDispatcher) {
        val files = directory.listFiles { file -> file.isFile && file.extension == "json" } ?: return@withContext null
        val newest = files.maxByOrNull { it.lastModified() } ?: return@withContext null
        val content = try {
            newest.readText()
        } catch (_: Exception) {
            return@withContext null
        }
        ReaderDocumentCodec.decode(content)
    }

    override suspend fun getNewestCached(): CachedDocument? = withContext(ioDispatcher) {
        val files = directory.listFiles { file -> file.isFile && file.extension == "json" } ?: return@withContext null
        val documents = files.mapNotNull { file ->
            try {
                ReaderDocumentCodec.decode(file.readText())
            } catch (_: Exception) {
                null
            }
        }
        documents.maxByOrNull { it.remoteLastEditedAt ?: it.cachedAt }
    }

    override suspend fun getMostRecent(): CachedDocument? = getMostRecentlyUsed()

    override suspend fun count(): Int = withContext(ioDispatcher) {
        val files = directory.listFiles { file -> file.isFile && file.extension == "json" }
        files?.size ?: 0
    }

    override suspend fun stats(): DocumentCacheStats = withContext(ioDispatcher) {
        val files = directory.listFiles { file -> file.isFile && file.extension == "json" } ?: emptyArray()
        val totalBytes = files.sumOf { it.length() }
        DocumentCacheStats(noteCount = files.size, totalBytes = totalBytes)
    }

    override suspend fun pruneOlderThan(maxAgeMillis: Long, preservePageIds: Set<String>): Int = withContext(ioDispatcher) {
        if (maxAgeMillis <= 0L) return@withContext 0
        val now = System.currentTimeMillis()
        val normalizedPreserved = (preservePageIds + protectedPageIds()).map { it.trim().lowercase().replace("-", "") }.toSet()
        var prunedCount = 0

        mutate {
            val files = directory.listFiles { file -> file.isFile && file.extension == "json" } ?: return@mutate
            for (file in files) {
                val cached = try {
                    ReaderDocumentCodec.decode(file.readText())
                } catch (_: Exception) {
                    null
                }

                if (cached == null) {
                    if (removeFile(file, CacheRemovalReason.CORRUPT)) prunedCount++
                    continue
                }

                val canonicalPageId = cached.document.pageId.trim().lowercase().replace("-", "")
                if (canonicalPageId in normalizedPreserved) {
                    continue
                }

                val retentionInstant = cached.remoteLastEditedAt ?: cached.fetchedAt
                val effectiveEpoch = retentionInstant.toEpochMilli()

                if (now - effectiveEpoch > maxAgeMillis) {
                    if (removeFile(file, CacheRemovalReason.AGED_OUT, cached.document.pageId)) {
                        prunedCount++
                    }
                }
            }
        }

        prunedCount
    }

    override suspend fun pruneExcept(keepPageIds: Set<String>): Int = withContext(ioDispatcher) {
        val normalizedKeep = (keepPageIds + protectedPageIds()).map { it.trim().lowercase().replace("-", "") }.toSet()
        var prunedCount = 0

        mutate {
            val files = directory.listFiles { file -> file.isFile && file.extension == "json" } ?: return@mutate
            for (file in files) {
                val cached = try {
                    ReaderDocumentCodec.decode(file.readText())
                } catch (_: Exception) {
                    null
                }

                if (cached == null) {
                    if (removeFile(file, CacheRemovalReason.CORRUPT)) prunedCount++
                    continue
                }

                val canonicalPageId = cached.document.pageId.trim().lowercase().replace("-", "")
                if (canonicalPageId !in normalizedKeep) {
                    if (removeFile(file, CacheRemovalReason.EVICTED, cached.document.pageId)) {
                        prunedCount++
                    }
                }
            }
        }

        prunedCount
    }

    override suspend fun removeAll(pageIds: Set<String>): Int = withContext(ioDispatcher) {
        if (pageIds.isEmpty()) return@withContext 0
        val normalized = pageIds.map { it.trim().lowercase().replace("-", "") }.toSet()
        var removedCount = 0

        mutate {
            val files = directory.listFiles { file -> file.isFile && file.extension == "json" } ?: return@mutate
            for (file in files) {
                val nameWithoutExt = file.nameWithoutExtension.lowercase().replace("-", "")
                if (nameWithoutExt in normalized) {
                    if (removeFile(file, CacheRemovalReason.EVICTED)) {
                        removedCount++
                    }
                } else {
                    val cached = try {
                        ReaderDocumentCodec.decode(file.readText())
                    } catch (_: Exception) {
                        null
                    }
                    if (cached != null) {
                        val canonical = cached.document.pageId.trim().lowercase().replace("-", "")
                        if (canonical in normalized) {
                            if (removeFile(file, CacheRemovalReason.EVICTED, cached.document.pageId)) {
                                removedCount++
                            }
                        }
                    }
                }
            }
        }

        removedCount
    }

    private fun fileFor(pageId: String): File {
        val safeName = pageId.trim().lowercase().replace(Regex("[^a-z0-9-]"), "_")
        return File(directory, "$safeName.json")
    }

    override suspend fun enforceBudget(preservePageIds: Set<String>): Int = withContext(ioDispatcher) {
        mutate {
            enforceBudgetLocked(preservePageIds)
        }
    }

    private fun enforceBudgetLocked(preservePageIds: Set<String>): Int {
        val maxBytes = byteLimit().coerceAtLeast(0L)
        val files = directory.listFiles { file -> file.isFile && file.extension == "json" } ?: return 0
        var totalBytes = files.sumOf { it.length() }
        var totalEntries = files.size
        if (totalBytes <= maxBytes && totalEntries <= maxEntries) return 0

        val normalizedPreserved = (preservePageIds + protectedPageIds()).map { it.trim().lowercase().replace("-", "") }.toSet()

        data class FileMeta(
            val file: File,
            val size: Long,
            val lastModified: Long,
            val canonicalPageId: String,
            val isPrefetch: Boolean
        )

        val metaList = files.mapNotNull { file ->
            val cached = try {
                ReaderDocumentCodec.decode(file.readText())
            } catch (_: Exception) {
                null
            }
            if (cached == null) {
                val size = file.length()
                if (removeFile(file, CacheRemovalReason.CORRUPT)) {
                    totalBytes -= size
                    totalEntries--
                }
                null
            } else {
                val canonical = cached.document.pageId.trim().lowercase().replace("-", "")
                FileMeta(
                    file = file,
                    size = file.length(),
                    lastModified = file.lastModified(),
                    canonicalPageId = canonical,
                    isPrefetch = cached.origin == com.example.notionreader.domain.document.CacheOrigin.PREFETCH
                )
            }
        }

        if (totalBytes <= maxBytes && totalEntries <= maxEntries) return 0

        val unpreserved = metaList.filter { it.canonicalPageId !in normalizedPreserved }
        val prefetchCandidates = unpreserved.filter { it.isPrefetch }.sortedBy { it.lastModified }
        val userCandidates = unpreserved.filter { !it.isPrefetch }.sortedBy { it.lastModified }

        val evictionPlan = prefetchCandidates + userCandidates
        var evictedCount = 0

        for (candidate in evictionPlan) {
            if (totalBytes <= maxBytes && totalEntries <= maxEntries) break
            if (removeFile(candidate.file, CacheRemovalReason.EVICTED, candidate.canonicalPageId)) {
                totalBytes -= candidate.size
                totalEntries--
                evictedCount++
            }
        }

        return evictedCount
    }
}
