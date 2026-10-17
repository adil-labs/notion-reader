package com.example.notionreader.data.local

import com.example.notionreader.domain.discovery.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Store under the app's noBackupFilesDir. Budget includes the old file during atomic staging. */
class FileTopicStore(private val directory: File, private val maxBytes: Long = 64L * 1024 * 1024,
    private val otherBytes: suspend () -> Long = { 0L }) : TopicStore {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file get() = File(directory, "topics.json")
    init {
        require(maxBytes > 0)
        directory.mkdirs()
        directory.listFiles { f -> f.extension == "tmp" }?.forEach { check(it.delete()) }
    }
    private fun validationFailure(snapshot: TopicSnapshot): TopicValidationReason? {
        val identity = snapshot.identity
        val anchors = snapshot.topics + snapshot.provisionalTopics
        if (snapshot.candidatePassLimit < 2 || snapshot.candidateSeeds.values.any { it !in 0..2 }) return TopicValidationReason.CandidateBound
        if (snapshot.engineVersion == 0 && anchors.size > 64) return TopicValidationReason.CandidateBound
        if (snapshot.engineVersion !in 0..SectionHierarchicalEngine.VERSION) return TopicValidationReason.InvalidIdentity
        snapshot.formationProgress?.let { work ->
            val indices = work.clusters.flatten()
            if (snapshot.engineVersion != SectionHierarchicalEngine.VERSION || snapshot.complete ||
                work.inputSignature.isBlank() || work.totalSections < 0 || work.clusters.size != work.totalSections ||
                indices.size != work.totalSections || indices.toSet() != (0 until work.totalSections).toSet() ||
                work.chain.distinct().size != work.chain.size ||
                work.chain.any { it !in work.clusters.indices || work.clusters[it].isEmpty() } ||
                work.outerCursor !in 0..work.clusters.size || work.neighborCursor !in 0..work.clusters.size ||
                work.bestNeighbor !in -1 until work.clusters.size || !work.bestDistance.isFinite() ||
                work.bestDistance !in 0f..2f || work.projectionCluster !in 0..work.candidateGroups.size ||
                work.projectionPassage < 0 || work.comparisonsCompleted < 0 ||
                work.candidateGroups.any { group -> group.isEmpty() || group.distinct().size != group.size || group.any { it !in 0 until work.totalSections } } ||
                work.groupingComplete && (work.outerCursor != work.totalSections || work.chain.isNotEmpty() || work.neighborCursor != 0 || work.bestNeighbor != -1) ||
                !work.groupingComplete && (work.projectionCluster != 0 || work.projectionPassage != 0 || work.projectionMembers.isNotEmpty() || anchors.isNotEmpty()) ||
                work.emptyProjectionClusters.distinct().size != work.emptyProjectionClusters.size ||
                work.emptyProjectionClusters.any { it !in 0 until work.projectionCluster || work.candidateGroups[it].isEmpty() } ||
                work.groupingComplete && anchors.size + work.emptyProjectionClusters.size != work.projectionCluster ||
                work.projectionCluster == work.candidateGroups.size && (work.projectionPassage != 0 || work.projectionMembers.isNotEmpty()) ||
                work.projectionMembers.size > work.projectionPassage ||
                work.projectionMembers.any { !it.score.isFinite() || it.score !in -1f..1f ||
                    canonicalSemanticId(it.pageId) != canonicalSemanticId(it.passage.pageId) })
                return TopicValidationReason.InvalidSnapshotProgress
        }
        if (anchors.map { it.id }.distinct().size != anchors.size) return TopicValidationReason.DuplicateTopicId
        if (snapshot.generation < 0) return TopicValidationReason.InvalidGeneration
        if (json.encodeToString(snapshot.continuity).toByteArray(Charsets.UTF_8).size > 1024 * 1024) return TopicValidationReason.ContinuityBound
        if (anchors.isNotEmpty() && (identity == null || identity.dimensions <= 0)) return TopicValidationReason.InvalidIdentity
        for (topic in anchors) {
            if (topic.id.isBlank() || topic.label.isBlank() || topic.anchor.vector.size != identity!!.dimensions ||
                topic.anchor.vector.any { !it.isFinite() } || topic.anchor.vector.none { it != 0f }) return TopicValidationReason.InvalidTopic
            if (topic.members.map { canonicalSemanticId(it.pageId) }.distinct().size != topic.members.size ||
                topic.members.any { member -> !member.score.isFinite() || member.score !in -1f..1f ||
                    canonicalSemanticId(member.passage.pageId) != canonicalSemanticId(member.pageId) ||
                    member.anchor != topic.anchor.passage }) return TopicValidationReason.InvalidMember
        }
        if (snapshot.connections.any { connection -> !connection.score.isFinite() ||
            !connection.left.score.isFinite() || !connection.right.score.isFinite() ||
            canonicalSemanticId(connection.left.pageId) == canonicalSemanticId(connection.right.pageId) }) return TopicValidationReason.InvalidConnection
        return null
    }
    override suspend fun read(): TopicSnapshot? = withContext(Dispatchers.IO) { mutex.withLock {
        if (!file.isFile) return@withLock null
        val result = runCatching {
            require(file.length() <= maxBytes)
            json.decodeFromString<TopicSnapshot>(file.readText()).also { require(validationFailure(it) == null) }
        }.getOrNull()
        if (result == null) check(file.delete() || !file.exists()) { "Unable to discard corrupt topic evidence" }
        result
    } }
    override suspend fun write(snapshot: TopicSnapshot): Boolean = writeDetailed(snapshot) == TopicWriteResult.Written
    override suspend fun writeDetailed(snapshot: TopicSnapshot): TopicWriteResult = withContext(Dispatchers.IO) { mutex.withLock {
        try {
            validationFailure(snapshot)?.let { return@withLock TopicWriteResult.Rejected(TopicWriteFailure.InvalidSnapshot, it) }
            val payload = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
            val existing = directory.listFiles()?.sumOf { it.length() } ?: 0L
            val external = otherBytes()
            if (external < 0 || payload.size.toLong() > maxBytes - existing - external)
                TopicWriteResult.Rejected(TopicWriteFailure.BudgetExceeded)
            else { atomicSemanticWrite(file, payload); TopicWriteResult.Written }
        } catch (_: java.io.IOException) {
            TopicWriteResult.Rejected(TopicWriteFailure.IoFailure)
        }
    } }
    override suspend fun clear(): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        directory.listFiles()?.forEach { check(it.delete()) { "Unable to clear topic evidence" } }
    } }
    override suspend fun bytes(): Long = withContext(Dispatchers.IO) { mutex.withLock {
        directory.listFiles()?.sumOf { it.length() } ?: 0L
    } }
}
