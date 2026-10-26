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

@Serializable private data class StoredTopicLabel(val version: Int = 2, val label: GeneratedTopicLabel)
@Serializable private data class StoredLabelCursor(val version: Int = 1, val cursor: TopicLabelCursor)

/** Small independent label sidecars, never copies of topic passage bodies or vectors. */
class FileTopicLabelStore(private val directory: File,
    private val writeGate: StorageWriteGate = StorageWriteGate.Unrestricted) : TopicLabelStore {
    private val lock = Mutex()
    private val json = Json { encodeDefaults = true }
    private val cursorFile get() = File(directory, "label-progress.json")
    private val recordName = Regex("label-[0-9a-f]{64}\\.json")
    init {
        check(directory.mkdirs() || directory.isDirectory)
        managedFiles().filter { it.extension == "tmp" }.forEach { check(it.delete()) }
    }
    private fun target(topicId: String) = File(directory, "label-${semanticHash(topicId)}.json")
    private fun managedFiles(): List<File> = if (!directory.exists()) emptyList() else
        (directory.listFiles() ?: throw IOException("Label directory unavailable")).filter {
            it.isFile && (it.name == cursorFile.name || recordName.matches(it.name) || it.name.startsWith("label-") && it.extension == "tmp")
        }
    private fun valid(label: GeneratedTopicLabel): Boolean {
        val identity = label.identity
        if (label.topicId.length !in 1..512 || !Regex("[0-9a-f]{64}").matches(label.groupFingerprint) ||
            !Regex("[0-9a-f]{64}").matches(label.workKey) || label.evidenceIds.size !in 1..16 ||
            label.evidenceIds.any { it.length !in 1..512 } ||
            listOf(identity.artifact, identity.tokenizer, identity.template, identity.prompt, identity.schema,
                identity.policy, identity.effectiveBackend).any { it.length !in 1..1024 }) return false
        // Record-shape check only; current source fingerprint/work identity must be checked by the caller.
        val request = TopicLabelRequest(label.topicId, label.groupFingerprint, 0,
            label.evidenceIds.map { TopicLabelEvidence(it, "", "", "") }, identity)
        return validateTopicLabel(request, label.text, label.evidenceIds, label.groupFingerprint, 0, label.definition) is TopicLabelValidation.Accepted
    }
    private fun readFile(file: File): GeneratedTopicLabel? = try {
        if (!file.isFile || file.length() !in 1..65536) null else {
            val envelope = json.decodeFromString<StoredTopicLabel>(file.readText(Charsets.UTF_8))
            envelope.label.takeIf { envelope.version == 2 && valid(it) && target(it.topicId).name == file.name }
        }
    } catch (_: Exception) { null }

    override suspend fun read(topicId: String): GeneratedTopicLabel? = withContext(Dispatchers.IO) {
        lock.withLock { readFile(target(topicId)) }
    }
    override suspend fun write(label: GeneratedTopicLabel, stillCurrent: () -> Boolean): TopicLabelWriteResult = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!valid(label)) return@withLock TopicLabelWriteResult.Invalid
            if (!stillCurrent()) return@withLock TopicLabelWriteResult.Stale
            val payload = json.encodeToString(StoredTopicLabel(label = label)).toByteArray(Charsets.UTF_8)
            if (payload.size > 65536) return@withLock TopicLabelWriteResult.Invalid
            commit(target(label.topicId), payload, stillCurrent)
        }
    }
    /** The caller owns lock and has already checked the live ticket once. */
    private suspend fun commit(destination: File, payload: ByteArray, stillCurrent: () -> Boolean): TopicLabelWriteResult {
        val staged = File(directory, "${destination.name}.${UUID.randomUUID()}.tmp")
        return try {
            writeGate.write(StorageCategory.DISCOVERY, payload.size.toLong() + 4096) {
                if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Label directory unavailable")
                FileOutputStream(staged).use { it.write(payload); it.fd.sync() }
                currentCoroutineContext().ensureActive()
                if (!stillCurrent()) return@write TopicLabelWriteResult.Stale
                try { Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                TopicLabelWriteResult.Written
            }
        } catch (_: StorageRefused) { TopicLabelWriteResult.StorageRefused }
        catch (_: IOException) { TopicLabelWriteResult.IoFailure }
        finally { if (staged.exists()) check(staged.delete()) }
    }
    override suspend fun remove(topicId: String) = withContext(Dispatchers.IO) {
        lock.withLock { val file = target(topicId); check(!file.exists() || file.delete()) }
    }
    override suspend fun prune(eligibleFingerprints: Map<String, String>) = withContext(Dispatchers.IO) {
        lock.withLock {
            for (file in managedFiles()) {
                currentCoroutineContext().ensureActive()
                if (file.name == cursorFile.name) {
                    if (eligibleFingerprints.isEmpty()) check(file.delete())
                    continue
                }
                val label = readFile(file)
                if (label == null || eligibleFingerprints[label.topicId] != label.groupFingerprint) check(file.delete())
            }
        }
    }
    override suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock { managedFiles().forEach { check(it.delete()) } } }
    override suspend fun readCursor(): TopicLabelCursor? = withContext(Dispatchers.IO) { lock.withLock {
        try {
            if (!cursorFile.isFile || cursorFile.length() !in 1..4096) null else {
                val value = json.decodeFromString<StoredLabelCursor>(cursorFile.readText(Charsets.UTF_8))
                value.cursor.takeIf { value.version == 1 && it.createdOrder >= 0 && it.topicId.length in 1..512 }
            }
        } catch (_: Exception) { null }
    } }
    override suspend fun writeCursor(cursor: TopicLabelCursor, stillCurrent: () -> Boolean): TopicLabelWriteResult = withContext(Dispatchers.IO) { lock.withLock {
        if (cursor.createdOrder < 0 || cursor.topicId.length !in 1..512) return@withLock TopicLabelWriteResult.Invalid
        if (!stillCurrent()) return@withLock TopicLabelWriteResult.Stale
        val payload = json.encodeToString(StoredLabelCursor(cursor = cursor)).toByteArray(Charsets.UTF_8)
        if (payload.size > 4096) return@withLock TopicLabelWriteResult.Invalid
        commit(cursorFile, payload, stillCurrent)
    } }
    override suspend fun bytes(): Long = withContext(Dispatchers.IO) { lock.withLock { managedFiles().sumOf { it.length() } } }
}
