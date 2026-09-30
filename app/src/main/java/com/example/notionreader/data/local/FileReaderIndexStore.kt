package com.example.notionreader.data.local

import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.discovery.CachedReaderIndex
import com.example.notionreader.domain.discovery.READER_INDEX_CACHE_SCHEMA_VERSION
import com.example.notionreader.domain.discovery.ReaderIndexStore
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
internal data class CachedReaderIndexDto(
    val schemaVersion: Int,
    val fetchedAtEpochMilli: Long,
    val entries: List<ReaderIndexEntryDto>
)

@Serializable
internal data class ReaderIndexEntryDto(
    val pageId: String,
    val title: String,
    val lastEditedAtEpochMilli: Long? = null,
    val kind: String? = null,
    val area: String? = null,
    val topics: List<String> = emptyList(),
    val project: String? = null,
    val status: String? = null
)

/**
 * Encodes and decodes [CachedReaderIndex] to and from JSON.
 */
object ReaderIndexCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun encode(index: CachedReaderIndex): String {
        val dto = CachedReaderIndexDto(
            schemaVersion = index.schemaVersion,
            fetchedAtEpochMilli = index.fetchedAt.toEpochMilli(),
            entries = index.entries.map { entry ->
                ReaderIndexEntryDto(
                    pageId = entry.pageId,
                    title = entry.title,
                    lastEditedAtEpochMilli = entry.lastEditedAt?.toEpochMilli(),
                    kind = entry.kind,
                    area = entry.area,
                    topics = entry.topics.toList(),
                    project = entry.project,
                    status = entry.status
                )
            }
        )
        return json.encodeToString(CachedReaderIndexDto.serializer(), dto)
    }

    fun decode(raw: String): CachedReaderIndex? {
        val dto = try {
            json.decodeFromString(CachedReaderIndexDto.serializer(), raw)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }

        if (dto.schemaVersion != READER_INDEX_CACHE_SCHEMA_VERSION) return null

        val entries = dto.entries.map { entryDto ->
            ReaderIndexEntry(
                pageId = entryDto.pageId,
                title = entryDto.title,
                lastEditedAt = entryDto.lastEditedAtEpochMilli?.let { Instant.ofEpochMilli(it) },
                kind = entryDto.kind,
                area = entryDto.area,
                topics = entryDto.topics.toSet(),
                project = entryDto.project,
                status = entryDto.status
            )
        }

        return CachedReaderIndex(
            entries = entries,
            fetchedAt = Instant.ofEpochMilli(dto.fetchedAtEpochMilli),
            schemaVersion = dto.schemaVersion
        )
    }
}

/**
 * File-backed implementation of [ReaderIndexStore] writing atomic JSON snapshots
 * to the specified [directory].
 */
class FileReaderIndexStore(
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ReaderIndexStore {

    private val mutex = Mutex()
    private val indexFile = File(directory, "reader_index.json")

    init {
        directory.mkdirs()
    }

    override suspend fun read(): CachedReaderIndex? = withContext(ioDispatcher) {
        mutex.withLock {
            if (!indexFile.exists() || !indexFile.isFile) return@withLock null
            val content = try {
                indexFile.readText()
            } catch (_: Exception) {
                indexFile.delete()
                return@withLock null
            }
            val decoded = ReaderIndexCodec.decode(content)
            if (decoded == null) {
                indexFile.delete()
            }
            decoded
        }
    }

    override suspend fun write(index: CachedReaderIndex): Unit = withContext(ioDispatcher) {
        val tempFile = File(directory, "reader_index.tmp.${UUID.randomUUID()}")
        val json = ReaderIndexCodec.encode(index)

        mutex.withLock {
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
                        indexFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: Exception) {
                    try {
                        Files.move(
                            tempFile.toPath(),
                            indexFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    } catch (_: Exception) {
                        tempFile.renameTo(indexFile)
                    }
                }
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }
    }

    override suspend fun clear(): Unit = withContext(ioDispatcher) {
        mutex.withLock {
            indexFile.delete()
            directory.listFiles { file -> file.name.startsWith("reader_index.tmp") }?.forEach { it.delete() }
        }
    }
}
