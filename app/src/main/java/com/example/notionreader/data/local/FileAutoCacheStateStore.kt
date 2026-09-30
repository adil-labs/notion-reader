package com.example.notionreader.data.local

import com.example.notionreader.domain.discovery.AutoCacheState
import com.example.notionreader.domain.discovery.AutoCacheStateStore
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

private const val AUTO_CACHE_STATE_SCHEMA_VERSION = 1

@Serializable
internal data class AutoCacheStateDto(
    val schemaVersion: Int = AUTO_CACHE_STATE_SCHEMA_VERSION,
    val managedPageIds: List<String> = emptyList(),
    val lastReconciledAtEpochMilli: Long? = null
)

/**
 * File-backed implementation of [AutoCacheStateStore] tracking the set of notes
 * managed by the auto-cache offline planner.
 */
class FileAutoCacheStateStore(
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AutoCacheStateStore {

    private val mutex = Mutex()
    private val stateFile = File(directory, "auto_cache_state.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    init {
        directory.mkdirs()
    }

    override suspend fun read(): AutoCacheState = withContext(ioDispatcher) {
        mutex.withLock {
            if (!stateFile.exists() || !stateFile.isFile) return@withLock AutoCacheState()
            val content = try {
                stateFile.readText()
            } catch (_: Exception) {
                stateFile.delete()
                return@withLock AutoCacheState()
            }

            val dto = try {
                json.decodeFromString(AutoCacheStateDto.serializer(), content)
            } catch (_: SerializationException) {
                stateFile.delete()
                return@withLock AutoCacheState()
            } catch (_: IllegalArgumentException) {
                stateFile.delete()
                return@withLock AutoCacheState()
            }

            if (dto.schemaVersion != AUTO_CACHE_STATE_SCHEMA_VERSION) {
                stateFile.delete()
                return@withLock AutoCacheState()
            }

            AutoCacheState(
                managedPageIds = dto.managedPageIds.toSet(),
                lastReconciledAt = dto.lastReconciledAtEpochMilli?.let { Instant.ofEpochMilli(it) }
            )
        }
    }

    override suspend fun write(state: AutoCacheState): Unit = withContext(ioDispatcher) {
        val tempFile = File(directory, "auto_cache_state.tmp.${UUID.randomUUID()}")
        val dto = AutoCacheStateDto(
            schemaVersion = AUTO_CACHE_STATE_SCHEMA_VERSION,
            managedPageIds = state.managedPageIds.toList(),
            lastReconciledAtEpochMilli = state.lastReconciledAt?.toEpochMilli()
        )
        val jsonString = json.encodeToString(AutoCacheStateDto.serializer(), dto)

        mutex.withLock {
            if (!directory.exists()) {
                directory.mkdirs()
            }

            try {
                FileOutputStream(tempFile).use { fos ->
                    val bytes = jsonString.toByteArray(Charsets.UTF_8)
                    fos.write(bytes)
                    fos.flush()
                    try {
                        fos.fd.sync()
                    } catch (_: Exception) {
                        // Ignored if sync not supported on test/virtual FS
                    }
                }

                try {
                    Files.move(
                        tempFile.toPath(),
                        stateFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: Exception) {
                    try {
                        Files.move(
                            tempFile.toPath(),
                            stateFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    } catch (_: Exception) {
                        tempFile.renameTo(stateFile)
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
            stateFile.delete()
            directory.listFiles { file -> file.name.startsWith("auto_cache_state.tmp") }?.forEach { it.delete() }
        }
    }
}
