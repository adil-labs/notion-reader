package com.example.notionreader.data.local

import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.document.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable private data class StoredTopicRetrieval(val version: Int = 1, val progress: TopicRetrievalProgress)

/** Independent atomic matching metadata, looked up only by existing canonical topic ID. */
class FileTopicRetrievalStore(private val directory: File,
    private val writeGate: StorageWriteGate = StorageWriteGate.Unrestricted) : TopicRetrievalStore {
    private val lock = Mutex()
    private val json = Json { encodeDefaults = true }
    private val recordName = Regex("retrieval-[0-9a-f]{64}\\.json")
    private val maximumRecordBytes = 8L * 1024 * 1024
    init {
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Topic retrieval directory unavailable")
        ownedFiles().filter { it.extension == "tmp" }.forEach {
            if (!it.delete()) throw IOException("Topic retrieval staging cleanup failed")
        }
    }
    private fun target(topicId: String) = File(directory, "retrieval-${semanticHash(topicId)}.json")
    private fun ownedFiles(): List<File> = if (!directory.exists()) emptyList() else
        (directory.listFiles() ?: throw IOException("Topic retrieval directory unavailable")).filter {
            it.isFile && (recordName.matches(it.name) || it.name.startsWith("retrieval-") && it.extension == "tmp")
        }
    private fun valid(progress: TopicRetrievalProgress): Boolean {
        val query = progress.queryEmbedding.query
        return query.topicId.length in 1..512 &&
            (progress.lastAttemptedId == null || progress.lastAttemptedId.length in 1..512 &&
                progress.lastAttemptedId == canonicalSemanticId(progress.lastAttemptedId)) &&
            progress.scannedRevisions.all { (id, revision) ->
            id.length in 1..512 && id == canonicalSemanticId(id) && revision.length in 1..1024
        } && progress.matches.all { (id, match) ->
            id == match.pageId && progress.scannedRevisions[id] == match.revision &&
                match.passageId.length in 1..512 && match.score.isFinite() && match.score in query.policy.minimumScore..1f
        }
    }
    private fun readFile(file: File): TopicRetrievalProgress? = try {
        if (!file.isFile || file.length() !in 1..maximumRecordBytes) null else {
            val value = json.decodeFromString<StoredTopicRetrieval>(file.readText(Charsets.UTF_8))
            value.progress.takeIf { value.version == 1 && valid(it) && target(it.queryEmbedding.query.topicId).name == file.name }
        }
    } catch (_: Exception) { null }

    override suspend fun read(topicId: String): TopicRetrievalProgress? = withContext(Dispatchers.IO) {
        lock.withLock { readFile(target(topicId)) }
    }
    override suspend fun write(progress: TopicRetrievalProgress, stillCurrent: () -> Boolean): TopicRetrievalWriteResult =
        withContext(Dispatchers.IO) { lock.withLock {
            if (!valid(progress)) return@withLock TopicRetrievalWriteResult.Invalid
            if (!stillCurrent()) return@withLock TopicRetrievalWriteResult.Stale
            val payload = json.encodeToString(StoredTopicRetrieval(progress = progress)).toByteArray(Charsets.UTF_8)
            if (payload.size > maximumRecordBytes) return@withLock TopicRetrievalWriteResult.RecordMemoryLimit
            val destination = target(progress.queryEmbedding.query.topicId)
            val staged = File(directory, "${destination.name}.${UUID.randomUUID()}.tmp")
            var cleanupFailed = false
            val result = try {
                writeGate.write(StorageCategory.DISCOVERY, payload.size.toLong() + 4096) {
                    if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Topic retrieval directory unavailable")
                    FileOutputStream(staged).use { it.write(payload); it.fd.sync() }
                    currentCoroutineContext().ensureActive()
                    if (!stillCurrent()) return@write TopicRetrievalWriteResult.Stale
                    try { Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                    catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    TopicRetrievalWriteResult.Written
                }
            } catch (_: StorageRefused) { TopicRetrievalWriteResult.StorageRefused }
            catch (_: IOException) { TopicRetrievalWriteResult.IoFailure }
            catch (_: SecurityException) { TopicRetrievalWriteResult.IoFailure }
            finally { if (staged.exists() && !staged.delete()) cleanupFailed = true }
            if (cleanupFailed) TopicRetrievalWriteResult.IoFailure else result
        } }

    override suspend fun remove(topicId: String) = withContext(Dispatchers.IO) { lock.withLock {
        val file = target(topicId)
        if (file.exists() && !file.delete()) throw IOException("Topic retrieval removal failed")
    } }
    override suspend fun prune(topicIds: Set<String>) = withContext(Dispatchers.IO) { lock.withLock {
        val retained = topicIds.mapTo(hashSetOf()) { target(it).name }
        for (file in ownedFiles()) {
            currentCoroutineContext().ensureActive()
            if (file.name !in retained || readFile(file) == null)
                if (!file.delete()) throw IOException("Topic retrieval pruning failed")
        }
    } }
    override suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock {
        ownedFiles().forEach { if (!it.delete()) throw IOException("Topic retrieval reset failed") }
    } }
    override suspend fun bytes(): Long = withContext(Dispatchers.IO) { lock.withLock { ownedFiles().sumOf { it.length() } } }
}
