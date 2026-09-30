package com.example.notionreader.data.local

import com.example.notionreader.data.document.CachedDocument
import com.example.notionreader.data.document.DocumentCache
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * File-backed atomic document cache storing each [CachedDocument] as a JSON file.
 *
 * Writes to a temporary `.tmp` file and performs an atomic rename to guarantee that
 * an uncompleted write never corrupts the cached document on disk.
 */
class FileDocumentCache(
    private val directory: File,
    private val maxEntries: Int = 200,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : DocumentCache {

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
            file.delete()
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
        if (!directory.exists()) {
            directory.mkdirs()
        }

        val pageId = document.document.pageId
        val targetFile = fileFor(pageId)
        val tempFile = File(directory, "${targetFile.nameWithoutExtension}.tmp")

        val json = ReaderDocumentCodec.encode(document)

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
                // Fallback for filesystems that do not support ATOMIC_MOVE
                if (!tempFile.renameTo(targetFile)) {
                    targetFile.delete()
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

    override suspend fun remove(pageId: String): Unit = withContext(ioDispatcher) {
        fileFor(pageId).delete()
    }

    override suspend fun clear(): Unit = withContext(ioDispatcher) {
        directory.listFiles()?.forEach { it.delete() }
    }

    override suspend fun getMostRecent(): CachedDocument? = withContext(ioDispatcher) {
        val files = directory.listFiles { file -> file.isFile && file.extension == "json" } ?: return@withContext null
        val newest = files.maxByOrNull { it.lastModified() } ?: return@withContext null
        val content = try {
            newest.readText()
        } catch (_: Exception) {
            return@withContext null
        }
        ReaderDocumentCodec.decode(content)
    }

    override suspend fun count(): Int = withContext(ioDispatcher) {
        val files = directory.listFiles { file -> file.isFile && file.extension == "json" }
        files?.size ?: 0
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
