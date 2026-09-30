package com.example.notionreader.data.local

import com.example.notionreader.data.document.CachedDocument
import com.example.notionreader.data.document.DocumentCache
import com.example.notionreader.domain.document.DocumentCacheStats
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : DocumentCache {

    private val writeMutex = Mutex()

    init {
        if (!directory.exists()) {
            directory.mkdirs()
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
            writeMutex.withLock {
                file.delete()
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

        writeMutex.withLock {
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

            pruneLru()
        }
    }

    override suspend fun remove(pageId: String): Unit = withContext(ioDispatcher) {
        writeMutex.withLock {
            fileFor(pageId).delete()
        }
    }

    override suspend fun clear(): Unit = withContext(ioDispatcher) {
        writeMutex.withLock {
            directory.listFiles()?.forEach { it.delete() }
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
        val normalizedPreserved = preservePageIds.map { it.trim().lowercase().replace("-", "") }.toSet()
        var prunedCount = 0

        writeMutex.withLock {
            val files = directory.listFiles { file -> file.isFile && file.extension == "json" } ?: return@withLock
            for (file in files) {
                val cached = try {
                    ReaderDocumentCodec.decode(file.readText())
                } catch (_: Exception) {
                    null
                }

                if (cached == null) {
                    file.delete()
                    prunedCount++
                    continue
                }

                val canonicalPageId = cached.document.pageId.trim().lowercase().replace("-", "")
                if (canonicalPageId in normalizedPreserved) {
                    continue
                }

                val lastActivityEpoch = cached.validatedAt.toEpochMilli()
                val remoteEditEpoch = cached.remoteLastEditedAt?.toEpochMilli() ?: lastActivityEpoch
                val effectiveEpoch = maxOf(lastActivityEpoch, remoteEditEpoch)

                if (now - effectiveEpoch > maxAgeMillis) {
                    if (file.delete()) {
                        prunedCount++
                    }
                }
            }
        }

        prunedCount
    }

    private fun fileFor(pageId: String): File {
        val safeName = pageId.trim().lowercase().replace(Regex("[^a-z0-9-]"), "_")
        return File(directory, "$safeName.json")
    }

    private fun pruneLru() {
        val files = directory.listFiles { file -> file.isFile && file.extension == "json" } ?: return
        if (files.size <= maxEntries) return

        val sorted = files.sortedBy { it.lastModified() }
        val toRemove = files.size - maxEntries
        for (i in 0 until toRemove) {
            sorted[i].delete()
        }
    }
}
