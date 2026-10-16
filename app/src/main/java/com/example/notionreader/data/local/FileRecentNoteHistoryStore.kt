package com.example.notionreader.data.local

import com.example.notionreader.domain.session.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Composition root supplies a directory below noBackupFilesDir. */
class FileRecentNoteHistoryStore(private val directory: File) : RecentNoteHistoryStore {
    private val file get() = File(directory, "recent-notes.json")
    override suspend fun clear() = withContext(Dispatchers.IO) {
        java.nio.file.Files.deleteIfExists(file.toPath())
        java.nio.file.Files.deleteIfExists(File(directory, "recent-notes.tmp").toPath())
        Unit
    }
    override suspend fun read(): List<RecentNoteReference> = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext emptyList()
        runCatching {
            require(file.length() <= 32_768)
            Json.decodeFromString<List<RecentNoteReference>>(file.readText()).also { records ->
                require(records.size <= 20 && records.all { it.pageId.isNotBlank() && it.openedAt >= 0 })
            }
        }.getOrElse { check(file.delete() || !file.exists()); emptyList() }
    }
    override suspend fun write(references: List<RecentNoteReference>) = withContext(Dispatchers.IO) {
        require(references.size <= 20)
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File(directory, "recent-notes.tmp")
        temporary.outputStream().use { stream ->
            stream.write(Json.encodeToString(references).toByteArray(Charsets.UTF_8)); stream.fd.sync()
        }
        java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        Unit
    }
}
