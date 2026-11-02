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
@Serializable private data class StoredProposalConcept(val version: Int = 1, val concept: ProposalConceptRecord)
@Serializable private data class StoredPassageDelta(val version: Int = 1, val delta: PassageDeltaRecord)
@Serializable private data class StoredConceptScan(val version: Int = 1, val scan: ExistingConceptScanRecord)
@Serializable private data class StoredPendingNoteScan(val version: Int = 1, val scan: PendingNoteScanRecord)
@Serializable private data class StoredTopicSupport(val version: Int = 1, val assessment: TopicSupportAssessment)

class FileProposalProgressStore(private val directory: File,
    private val writeGate: StorageWriteGate = StorageWriteGate.Unrestricted) : ProposalProgressStore {
    private val lock = Mutex()
    private val json = Json { encodeDefaults = true }
    private val recordName = Regex("proposal-progress-[0-9a-f]{64}\\.json")
    private val abstentionName = Regex("proposal-abstention-[0-9a-f]{64}\\.json")
    private val conceptName = Regex("proposal-concept-[0-9a-f]{64}\\.json")
    private val deltaName = Regex("proposal-delta-[0-9a-f]{64}\\.json")
    private val scanName = Regex("proposal-concept-scan-[0-9a-f]{64}\\.json")
    private val pendingNoteScanName = Regex("proposal-pending-note-scan-[0-9a-f]{64}\\.json")
    private val supportName = Regex("proposal-support-[0-9a-f]{64}\\.json")
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
    private fun conceptTarget(workKey: String) = File(directory, "proposal-concept-$workKey.json")
    private fun deltaTarget(pageId: String) = File(directory, "proposal-delta-${semanticHash(canonicalSemanticId(pageId))}.json")
    private fun scanTarget(pageId: String) = File(directory, "proposal-concept-scan-${semanticHash(canonicalSemanticId(pageId))}.json")
    private fun pendingNoteScanTarget(workKey: String, pageId: String) = File(directory,
        "proposal-pending-note-scan-${semanticHash(listOf(workKey, canonicalSemanticId(pageId)).joinToString("") { "${it.length}:$it" })}.json")
    private fun supportTarget(workKey: String) = File(directory, "proposal-support-$workKey.json")
    private fun ownedFiles(): List<File> = if (!directory.exists()) emptyList() else
        (directory.listFiles() ?: throw IOException("Proposal progress directory unavailable")).filter {
            it.isFile && (recordName.matches(it.name) || abstentionName.matches(it.name) || conceptName.matches(it.name) || deltaName.matches(it.name) || scanName.matches(it.name) || pendingNoteScanName.matches(it.name) || supportName.matches(it.name) ||
                (it.name.startsWith("proposal-progress-") || it.name.startsWith("proposal-abstention-") ||
                    it.name.startsWith("proposal-support-") || it.name.startsWith("proposal-pending-note-scan-") || it.name.startsWith("proposal-concept-") || it.name.startsWith("proposal-delta-")) && it.extension == "tmp")
        }
    private fun validId(id: String) = id.length in 1..512 && id == canonicalSemanticId(id)
    private fun validRevision(value: String) = value.length in 1..1024
    private fun valid(record: ProposalAbstentionRecord): Boolean = Regex("[0-9a-f]{64}").matches(record.workKey) &&
        record.evidence.size in 1..16 && record.evidence.map { it.passageId }.toSet().size == record.evidence.size &&
        record.evidence.all { validId(it.pageId) && validRevision(it.revision) && it.passageId.length in 1..512 } &&
        record.evidence.groupBy { it.pageId }.values.all { refs -> refs.map { it.revision }.toSet().size == 1 }
    private fun valid(record: ProposalConceptRecord): Boolean =
        valid(ProposalAbstentionRecord(record.workKey, record.evidence, DiscoveryAbstentionReason.NO_USEFUL_TOPIC)) &&
        record.name.isNotBlank() && record.name.length <= 96 && record.definition.isNotBlank() && record.definition.length <= 512 &&
        record.listeningDescription.isNotBlank() && record.listeningDescription.length <= 512 &&
        record.evidenceIds.isNotEmpty() && record.evidenceIds.size == record.evidenceIds.toSet().size &&
        record.evidenceIds.all { id -> record.evidence.any { it.passageId == id } }
    private fun valid(record: PassageDeltaRecord): Boolean {
        val delta = record.delta
        val old = delta.removed + delta.rebound.keys
        val current = delta.changed + delta.rebound.values
        return validId(record.pageId) && validRevision(record.revision) && Regex("[0-9a-f]{64}").matches(record.fingerprint) && record.embedding.model.isNotBlank() &&
            record.embedding.dimensions > 0 && old.size <= 32768 && current.size <= 32768 &&
            delta.removed.intersect(delta.rebound.keys).isEmpty() && delta.changed.intersect(delta.rebound.values.toSet()).isEmpty() &&
            delta.rebound.values.toSet().size == delta.rebound.size && (old + current).all { it.length in 1..512 }
    }
    private fun valid(record: ExistingConceptScanRecord): Boolean {
        val cursor = record.next
        val passage = cursor?.passage
        return validId(record.pageId) && validRevision(record.revision) && record.sourceRevisions.size <= 32768 &&
            record.sourceRevisions[record.pageId] == record.revision &&
            record.sourceRevisions.all { (id, rev) -> validId(id) && validRevision(rev) } &&
            Regex("[0-9a-f]{64}").matches(record.scopeKey) &&
            (cursor == null || cursor.scopeKey == record.scopeKey && cursor.topicOffset >= 0) &&
            (passage == null || Regex("[0-9a-f]{64}").matches(passage.queryKey) && passage.pageId == record.pageId &&
                passage.revision == record.revision && passage.score.isFinite() && passage.score in -1f..1f && passage.passageId.length in 1..512) &&
            record.assessments.size <= 32768 && record.assessments.map { it.workKey }.toSet().size == record.assessments.size &&
            record.assessments.all { valid(it) && it.evidence.pageId == record.pageId && it.evidence.revision == record.revision &&
                it.decision != SupportDecision.UNSUPPORTED }
    }
    private fun valid(record: PendingNoteScanRecord): Boolean {
        val cursor = record.cursor
        return record.formatVersion in 1..2 && Regex("[0-9a-f]{64}").matches(record.workKey) && validId(record.pageId) && validRevision(record.revision) &&
            Regex("[0-9a-f]{64}").matches(record.binding) && record.sourceRevisions.size <= 32768 &&
            record.sourceRevisions[record.pageId] == record.revision &&
            record.sourceRevisions.all { (id, revision) -> validId(id) && validRevision(revision) } &&
            record.supported.size + record.uncertain.size <= 32768 &&
            record.supported.intersect(record.uncertain).isEmpty() &&
            (record.supported + record.uncertain).all { it.length in 1..512 } &&
            (cursor == null || Regex("[0-9a-f]{64}").matches(cursor.queryKey) && cursor.pageId == record.pageId &&
                cursor.revision == record.revision && cursor.score.isFinite() && cursor.score in -1f..1f && cursor.passageId.length in 1..512)
    }
    private fun valid(record: TopicSupportAssessment): Boolean = Regex("[0-9a-f]{64}").matches(record.workKey) &&
        record.topicId.length in 1..512 && record.conceptWorkKey.length in 1..512 &&
        validId(record.evidence.pageId) && validRevision(record.evidence.revision) && record.evidence.passageId.length in 1..512 &&
        listOf(record.verifier.artifact, record.verifier.tokenizer, record.verifier.template, record.verifier.prompt,
            record.verifier.schema, record.verifier.policy, record.verifier.effectiveBackend).all { it.length in 1..1024 }
    private fun valid(record: ProposalProgressRecord): Boolean {
        val cp = record.checkpoint
        if (!validId(record.pageId) || !validRevision(record.revision) || record.anchorOffset < 0 ||
            (record.lastAttemptedId != null && !validId(record.lastAttemptedId)) ||
            record.embedding.model.isBlank() || record.embedding.dimensions <= 0 ||
            !Regex("[0-9a-f]{64}").matches(cp.contextKey) || cp.candidates.size !in 1..128 ||
            (cp.scopeKey != null && !Regex("[0-9a-f]{64}").matches(cp.scopeKey)) ||
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
    private fun eligible(record: TopicSupportAssessment, sources: Map<String, String>): Boolean =
        sources[record.evidence.pageId] == record.evidence.revision
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
    private fun readConceptFile(file: File): ProposalConceptRecord? = try {
        if (!file.isFile || file.length() !in 1..maxBytes) null else {
            val stored = json.decodeFromString<StoredProposalConcept>(file.readText(Charsets.UTF_8))
            stored.concept.takeIf { stored.version == 1 && valid(it) && conceptTarget(it.workKey).name == file.name }
        }
    } catch (_: Exception) { null }
    private fun readDeltaFile(file: File): PassageDeltaRecord? = try {
        if (!file.isFile || file.length() !in 1..maxBytes) null else {
            val stored = json.decodeFromString<StoredPassageDelta>(file.readText(Charsets.UTF_8))
            stored.delta.takeIf { stored.version == 1 && valid(it) && deltaTarget(it.pageId).name == file.name }
        }
    } catch (_: Exception) { null }
    private fun readConceptScanFile(file: File): ExistingConceptScanRecord? = try {
        if (!file.isFile || file.length() !in 1..maxBytes) null else {
            val stored = json.decodeFromString<StoredConceptScan>(file.readText(Charsets.UTF_8))
            stored.scan.takeIf { stored.version == 1 && valid(it) && scanTarget(it.pageId).name == file.name }
        }
    } catch (_: Exception) { null }
    private fun readPendingNoteScanFile(file: File): PendingNoteScanRecord? = try {
        if (!file.isFile || file.length() !in 1..maxBytes) null else {
            val stored = json.decodeFromString<StoredPendingNoteScan>(file.readText(Charsets.UTF_8))
            stored.scan.takeIf { stored.version == 1 && valid(it) && pendingNoteScanTarget(it.workKey, it.pageId).name == file.name }
        }
    } catch (_: Exception) { null }
    private fun readSupportFile(file: File): TopicSupportAssessment? = try {
        if (!file.isFile || file.length() !in 1..maxBytes) null else {
            val stored = json.decodeFromString<StoredTopicSupport>(file.readText(Charsets.UTF_8))
            stored.assessment.takeIf { stored.version == 1 && valid(it) && supportTarget(it.workKey).name == file.name }
        }
    } catch (_: Exception) { null }

    override suspend fun read(pageId: String, anchorOffset: Int, sources: Map<String, String>): ProposalProgressRecord? =
        withContext(Dispatchers.IO) { lock.withLock {
            if (anchorOffset < 0) null else readFile(target(pageId, anchorOffset))?.takeIf { eligible(it, sources) }
        } }

    override suspend fun readAbstention(workKey: String, sources: Map<String, String>): ProposalAbstentionRecord? =
        withContext(Dispatchers.IO) { lock.withLock {
            if (!Regex("[0-9a-f]{64}").matches(workKey)) null
            else if (conceptTarget(workKey).exists()) null else readAbstentionFile(abstentionTarget(workKey))?.takeIf { eligible(it, sources) }
        } }

    override suspend fun readConcept(workKey: String, sources: Map<String, String>): ProposalConceptRecord? =
        withContext(Dispatchers.IO) { lock.withLock {
            if (!Regex("[0-9a-f]{64}").matches(workKey) || abstentionTarget(workKey).exists()) null
            else readConceptFile(conceptTarget(workKey))?.takeIf { record -> record.evidence.all { sources[it.pageId] == it.revision } }
        } }

    override suspend fun writeConcept(record: ProposalConceptRecord, stillCurrent: () -> Boolean): ProposalProgressWriteResult =
        withContext(Dispatchers.IO) { lock.withLock {
            if (!valid(record) || abstentionTarget(record.workKey).exists()) return@withLock ProposalProgressWriteResult.Invalid
            if (!stillCurrent()) return@withLock ProposalProgressWriteResult.Stale
            writeAtomic(conceptTarget(record.workKey), json.encodeToString(StoredProposalConcept(concept = record)).toByteArray(Charsets.UTF_8), stillCurrent)
        } }

    override suspend fun readDelta(pageId: String, revision: String, embedding: EmbeddingIdentity): PassageDeltaRecord? =
        withContext(Dispatchers.IO) { lock.withLock {
            if (!validId(pageId) || !validRevision(revision)) null
            else readDeltaFile(deltaTarget(pageId))?.takeIf { it.pageId == pageId && it.revision == revision && it.embedding == embedding }
        } }

    override suspend fun writeDelta(record: PassageDeltaRecord, stillCurrent: () -> Boolean): ProposalProgressWriteResult =
        withContext(Dispatchers.IO) { lock.withLock {
            if (!valid(record)) return@withLock ProposalProgressWriteResult.Invalid
            if (!stillCurrent()) return@withLock ProposalProgressWriteResult.Stale
            writeAtomic(deltaTarget(record.pageId), json.encodeToString(StoredPassageDelta(delta = record)).toByteArray(Charsets.UTF_8), stillCurrent)
        } }

    override suspend fun readConceptScan(pageId: String, sources: Map<String, String>): ExistingConceptScanRecord? =
        withContext(Dispatchers.IO) { lock.withLock {
            if (!validId(pageId)) null else readConceptScanFile(scanTarget(pageId))?.takeIf { it.sourceRevisions == sources }
        } }

    override suspend fun replaceConceptScan(expected: ExistingConceptScanRecord?, record: ExistingConceptScanRecord,
        stillCurrent: () -> Boolean): ProposalProgressWriteResult = withContext(Dispatchers.IO) { lock.withLock {
        if (!valid(record) || expected != null && (expected.pageId != record.pageId || expected.sourceRevisions != record.sourceRevisions))
            return@withLock ProposalProgressWriteResult.Invalid
        if (!stillCurrent()) return@withLock ProposalProgressWriteResult.Stale
        val observed = readConceptScanFile(scanTarget(record.pageId))?.takeIf { it.sourceRevisions == record.sourceRevisions }
        if (observed != expected) return@withLock ProposalProgressWriteResult.Stale
        writeAtomic(scanTarget(record.pageId), json.encodeToString(StoredConceptScan(scan = record)).toByteArray(Charsets.UTF_8), stillCurrent)
    } }

    override suspend fun readPendingNoteScan(workKey: String, pageId: String, sources: Map<String, String>): PendingNoteScanRecord? =
        withContext(Dispatchers.IO) { lock.withLock {
            if (!Regex("[0-9a-f]{64}").matches(workKey) || !validId(pageId)) null
            else readPendingNoteScanFile(pendingNoteScanTarget(workKey, pageId))?.takeIf { scan -> if (scan.formatVersion == 1) scan.sourceRevisions == sources else scan.sourceRevisions.all { (id, revision) -> sources[id] == revision } }
        } }

    override suspend fun replacePendingNoteScan(expected: PendingNoteScanRecord?, record: PendingNoteScanRecord,
        stillCurrent: () -> Boolean): ProposalProgressWriteResult = withContext(Dispatchers.IO) { lock.withLock {
        if (!valid(record) || expected != null && (expected.workKey != record.workKey || expected.pageId != record.pageId)) return@withLock ProposalProgressWriteResult.Invalid
        if (!stillCurrent()) return@withLock ProposalProgressWriteResult.Stale
        val file = pendingNoteScanTarget(record.workKey, record.pageId)
        val observed = readPendingNoteScanFile(file)
        if (observed != expected) return@withLock ProposalProgressWriteResult.Stale
        writeAtomic(file, json.encodeToString(StoredPendingNoteScan(scan = record)).toByteArray(Charsets.UTF_8), stillCurrent)
    } }
    override suspend fun readSupport(workKey: String, sources: Map<String, String>): TopicSupportAssessment? =
        withContext(Dispatchers.IO) { lock.withLock {
            if (!Regex("[0-9a-f]{64}").matches(workKey)) null
            else readSupportFile(supportTarget(workKey))?.takeIf { eligible(it, sources) }
        } }

    override suspend fun writeSupport(record: TopicSupportAssessment, stillCurrent: () -> Boolean): ProposalProgressWriteResult =
        withContext(Dispatchers.IO) { lock.withLock {
            if (!valid(record)) return@withLock ProposalProgressWriteResult.Invalid
            if (!stillCurrent()) return@withLock ProposalProgressWriteResult.Stale
            val previous = readSupportFile(supportTarget(record.workKey))
            if (!stillCurrent()) return@withLock ProposalProgressWriteResult.Stale
            if (previous == record) return@withLock ProposalProgressWriteResult.Written
            if (previous != null && previous.copy(decision = record.decision) == record)
                return@withLock ProposalProgressWriteResult.Invalid
            val payload = json.encodeToString(StoredTopicSupport(assessment = record)).toByteArray(Charsets.UTF_8)
            writeAtomic(supportTarget(record.workKey), payload, stillCurrent)
        } }

    override suspend fun writeAbstention(record: ProposalAbstentionRecord, stillCurrent: () -> Boolean): ProposalProgressWriteResult =
        withContext(Dispatchers.IO) { lock.withLock {
            if (!valid(record) || conceptTarget(record.workKey).exists()) return@withLock ProposalProgressWriteResult.Invalid
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
            val eligible = when {
                abstentionName.matches(file.name) -> readAbstentionFile(file)?.let { eligible(it, sources) } == true
                conceptName.matches(file.name) -> readConceptFile(file)?.evidence?.all { sources[it.pageId] == it.revision } == true
                deltaName.matches(file.name) -> readDeltaFile(file)?.let { sources[it.pageId] == it.revision } == true
                scanName.matches(file.name) -> readConceptScanFile(file)?.let { it.sourceRevisions == sources } == true
                pendingNoteScanName.matches(file.name) -> readPendingNoteScanFile(file)?.let { scan -> if (scan.formatVersion == 1) scan.sourceRevisions == sources else scan.sourceRevisions.all { (id, revision) -> sources[id] == revision } } == true
                supportName.matches(file.name) -> readSupportFile(file)?.let { eligible(it, sources) } == true
                else -> readFile(file)?.let { eligible(it, sources) } == true
            }
            if (!eligible)
                if (!file.delete()) throw IOException("Proposal progress withdrawal failed")
        }
    } }
    override suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock {
        ownedFiles().forEach { if (!it.delete()) throw IOException("Proposal progress reset failed") }
    } }
    override suspend fun bytes(): Long = withContext(Dispatchers.IO) { lock.withLock { ownedFiles().sumOf { it.length() } } }
}
