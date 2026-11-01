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

@Serializable private data class StoredProposalProgress(val version: Int = 1, val progress: ProposalProgressRecord)
@Serializable private data class StoredProposalAbstention(val version: Int = 1, val decision: ProposalAbstentionRecord)

class FileProposalProgressStore(private val directory: File,
    private val writeGate: StorageWriteGate = StorageWriteGate.Unrestricted) : ProposalProgressStore {
    private val lock = Mutex()
    private val json = Json { encodeDefaults = true }
    private val recordName = Regex("proposal-progress-[0-9a-f]{64}\\.json")
    private val abstentionName = Regex("proposal-abstention-[0-9a-f]{64}\\.json")
    private val maxBytes = 8L * 1024 * 1024
    init {
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Proposal progress directory unavailable")
        ownedFiles().filter { it.extension == "tmp" }.forEach {
            if (!it.delete()) throw IOException("Proposal progress staging cleanup failed")
        }
    }
    private fun target(pageId: String, offset: Int): File {
        val id = canonicalSemanticId(pageId)
        val key = listOf("proposal-progress-v1", id, offset.toString()).joinToString("") { "${it.length}:$it" }
        return File(directory, "proposal-progress-${semanticHash(key)}.json")
    }
    private fun abstentionTarget(workKey: String) = File(directory, "proposal-abstention-$workKey.json")
    private fun ownedFiles(): List<File> = if (!directory.exists()) emptyList() else
        (directory.listFiles() ?: throw IOException("Proposal progress directory unavailable")).filter {
            it.isFile && (recordName.matches(it.name) || abstentionName.matches(it.name) ||
                (it.name.startsWith("proposal-progress-") || it.name.startsWith("proposal-abstention-")) && it.extension == "tmp")
        }
    private fun validId(id: String) = id.length in 1..512 && id == canonicalSemanticId(id)
    private fun validRevision(value: String) = value.length in 1..1024
    private fun valid(record: ProposalAbstentionRecord): Boolean = Regex("[0-9a-f]{64}").matches(record.workKey) &&
        record.evidence.size in 1..16 && record.evidence.map { it.passageId }.toSet().size == record.evidence.size &&
        record.evidence.all { validId(it.pageId) && validRevision(it.revision) && it.passageId.length in 1..512 } &&
        record.evidence.groupBy { it.pageId }.values.all { refs -> refs.map { it.revision }.toSet().size == 1 }
    private fun valid(record: ProposalProgressRecord): Boolean {
        val cp = record.checkpoint
        if (!validId(record.pageId) || !validRevision(record.revision) || record.anchorOffset < 0 ||
            record.embedding.model.isBlank() || record.embedding.dimensions <= 0 ||
            !Regex("[0-9a-f]{64}").matches(cp.contextKey) || cp.candidates.size !in 1..128 ||
            cp.truncatedAnchors.any { it !in cp.candidates } ||
            cp.scannedRevisions.any { (id, revision) -> !validId(id) || id == record.pageId || !validRevision(revision) }) return false
        return cp.candidates.all { (anchor, candidates) ->
            anchor.length in 1..512 && candidates.size <= 31 &&
                candidates.map { it.ref.pageId to it.ref.passageId }.toSet().size == candidates.size &&
                candidates.all { candidate ->
                    val ref = candidate.ref
                    validId(ref.pageId) && validRevision(ref.revision) && ref.passageId.length in 1..512 &&
                        candidate.score.isFinite() && when (candidate.reason) {
                            ProposalReason.SAME_SECTION -> ref.pageId == record.pageId && ref.revision == record.revision && candidate.score == 2f
                            ProposalReason.SEMANTIC_NEIGHBOR, ProposalReason.EXPLICIT_REFERENCE ->
                                ref.pageId != record.pageId && cp.scannedRevisions[ref.pageId] == ref.revision && candidate.score in -1f..1f
                            ProposalReason.CHANGED_BODY -> false
                        }
                }
        }
    }
    private fun eligible(record: ProposalProgressRecord, sources: Map<String, String>): Boolean =
        sources[record.pageId] == record.revision && record.checkpoint.scannedRevisions.all { (id, rev) -> sources[id] == rev }
    private fun eligible(record: ProposalAbstentionRecord, sources: Map<String, String>): Boolean =
        record.evidence.all { sources[it.pageId] == it.revision }
    private fun readFile(file: File): ProposalProgressRecord? = try {
        if (!file.isFile || file.length() !in 1..maxBytes) null else {
            val stored = json.decodeFromString<StoredProposalProgress>(file.readText(Charsets.UTF_8))
            stored.progress.takeIf { stored.version == 1 && valid(it) && target(it.pageId, it.anchorOffset).name == file.name }
        }
    } catch (_: Exception) { null }
    private fun readAbstentionFile(file: File): ProposalAbstentionRecord? = try {
        if (!file.isFile || file.length() !in 1..maxBytes) null else {
            val stored = json.decodeFromString<StoredProposalAbstention>(file.readText(Charsets.UTF_8))
            stored.decision.takeIf { stored.version == 1 && valid(it) && abstentionTarget(it.workKey).name == file.name }
        }
    } catch (_: Exception) { null }

    override suspend fun read(pageId: String, anchorOffset: Int, sources: Map<String, String>): ProposalProgressRecord? =
        withContext(Dispatchers.IO) { lock.withLock {
            if (anchorOffset < 0) null else readFile(target(pageId, anchorOffset))?.takeIf { eligible(it, sources) }
        } }

    override suspend fun readAbstention(workKey: String, sources: Map<String, String>): ProposalAbstentionRecord? =
        withContext(Dispatchers.IO) { lock.withLock {
            if (!Regex("[0-9a-f]{64}").matches(workKey)) null
            else readAbstentionFile(abstentionTarget(workKey))?.takeIf { eligible(it, sources) }
        } }

    override suspend fun writeAbstention(record: ProposalAbstentionRecord, stillCurrent: () -> Boolean): ProposalProgressWriteResult =
        withContext(Dispatchers.IO) { lock.withLock {
            if (!valid(record)) return@withLock ProposalProgressWriteResult.Invalid
            if (!stillCurrent()) return@withLock ProposalProgressWriteResult.Stale
            val payload = json.encodeToString(StoredProposalAbstention(decision = record)).toByteArray(Charsets.UTF_8)
            writeAtomic(abstentionTarget(record.workKey), payload, stillCurrent)
        } }

    override suspend fun write(record: ProposalProgressRecord, stillCurrent: () -> Boolean): ProposalProgressWriteResult =
        withContext(Dispatchers.IO) { lock.withLock {
            if (!valid(record)) return@withLock ProposalProgressWriteResult.Invalid
            if (!stillCurrent()) return@withLock ProposalProgressWriteResult.Stale
            val payload = json.encodeToString(StoredProposalProgress(progress = record)).toByteArray(Charsets.UTF_8)
            writeAtomic(target(record.pageId, record.anchorOffset), payload, stillCurrent)
        } }

    private suspend fun writeAtomic(destination: File, payload: ByteArray, stillCurrent: () -> Boolean): ProposalProgressWriteResult {
        if (payload.size > maxBytes) return ProposalProgressWriteResult.RecordMemoryLimit
        val staged = File(directory, "${destination.name}.${UUID.randomUUID()}.tmp")
        var cleanupFailed = false
        val result = try {
            writeGate.write(StorageCategory.DISCOVERY, payload.size.toLong() + 4096) {
                if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Proposal progress directory unavailable")
                FileOutputStream(staged).use { it.write(payload); it.fd.sync() }
                currentCoroutineContext().ensureActive()
                if (!stillCurrent()) return@write ProposalProgressWriteResult.Stale
                try { Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                ProposalProgressWriteResult.Written
            }
        } catch (_: StorageRefused) { ProposalProgressWriteResult.StorageRefused }
        catch (_: IOException) { ProposalProgressWriteResult.IoFailure }
        catch (_: SecurityException) { ProposalProgressWriteResult.IoFailure }
        finally { if (staged.exists() && !staged.delete()) cleanupFailed = true }
        return if (cleanupFailed) ProposalProgressWriteResult.IoFailure else result
    }

    override suspend fun reconcile(sources: Map<String, String>) = withContext(Dispatchers.IO) { lock.withLock {
        for (file in ownedFiles()) {
            currentCoroutineContext().ensureActive()
            val eligible = if (abstentionName.matches(file.name)) readAbstentionFile(file)?.let { eligible(it, sources) } == true
                else readFile(file)?.let { eligible(it, sources) } == true
            if (!eligible)
                if (!file.delete()) throw IOException("Proposal progress withdrawal failed")
        }
    } }
    override suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock {
        ownedFiles().forEach { if (!it.delete()) throw IOException("Proposal progress reset failed") }
    } }
    override suspend fun bytes(): Long = withContext(Dispatchers.IO) { lock.withLock { ownedFiles().sumOf { it.length() } } }
}
