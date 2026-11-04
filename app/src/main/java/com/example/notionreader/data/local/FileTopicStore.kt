package com.example.notionreader.data.local

import com.example.notionreader.domain.discovery.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.decodeFromStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable

/** Read catalog references without materializing topic evidence. */
@Serializable
private data class TopicStoreHeader(val schemaVersion: Int = 0, val engineVersion: Int = 0,
    val catalog: ConnectionCatalogManifest? = null)

private data class DecodedTopics(val snapshot: TopicSnapshot,
    val catalog: ConnectionCatalogManifest, val catalogKey: String?)

@Serializable
private data class TopicStoreEnvelope(val snapshot: TopicSnapshot, val catalog: ConnectionCatalogManifest,
    val schemaVersion: Int = 2, val engineVersion: Int = TOPIC_CATALOG_STORAGE_VERSION,
    val catalogKey: String? = null, val topicGraph: StoredTopicGraph? = null)

/** Store under the app's noBackupFilesDir. Budget includes the old file during atomic staging. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class FileTopicStore(private val directory: File, private val maxBytes: Long = Long.MAX_VALUE,
    private val otherBytes: suspend () -> Long = { 0L },
    private val writeGate: com.example.notionreader.domain.document.StorageWriteGate = com.example.notionreader.domain.document.StorageWriteGate.Unrestricted,
    private val allowFreshInitialization: Boolean = false) : TopicStore {
    private val directoryKey = directory.absoluteFile.normalize().path.lowercase(java.util.Locale.ROOT)
    private val mutex = directoryMutexes.computeIfAbsent(directoryKey) { Mutex() }
    private companion object {
        val directoryMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        val initializationLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        const val MAX_RECORD_BYTES = 64L * 1024 * 1024
        // Separate from disk capacity: accommodates dense hundred-Note continuity and legacy identities.
        const val MAX_CONTINUITY_BYTES = 4 * 1024 * 1024
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val catalog = FileConnectionCatalog(directory, json)
    private val file get() = File(directory, "topics.json")
    private val freshMarker get() = File(directory, "fresh-catalog.marker")
    private val consumedMarker get() = File(directory, "consumed-catalog.marker")
    private val resetMarker get() = File(directory, "reset-catalog.marker")
    private val catalogs = linkedMapOf<String, ConnectionCatalogManifest>()
    private var currentCatalogKey: String? = null
    private var pinnedCatalogKey: String? = null
    init {
        require(maxBytes > 0)
        synchronized(initializationLocks.computeIfAbsent(directoryKey) { Any() }) {
            val created = directory.mkdirs()
            check(created || directory.isDirectory) { "Topic directory unavailable" }
            // The empty marker and directory are created before any other store instance can open.
            if (created && allowFreshInitialization) {
                try { freshMarker.createNewFile() }
                catch (_: java.io.IOException) { /* Missing provenance fails closed. */ }
                catch (_: SecurityException) { /* Missing provenance fails closed. */ }
            }
        }
        directory.listFiles { f -> f.extension == "tmp" }?.forEach { check(it.delete()) }
        val referenced = runCatching {
            if (!file.isFile) emptySet() else {
                require(file.length() <= MAX_RECORD_BYTES)
                val header = readHeader()
                if (header.schemaVersion == 2) header.catalog?.pages.orEmpty().map { it.fileName }.toSet()
                else emptySet()
            }
        }.getOrDefault(emptySet())
        directory.listFiles { f -> f.name.startsWith("connections-") && f.name !in referenced }?.forEach {
            check(it.delete() || !it.exists()) { "Unable to remove uncommitted catalog evidence" }
        }
    }
    private fun validationFailure(snapshot: TopicSnapshot, checkActive: () -> Unit = {}): TopicValidationReason? {
        checkActive()
        if (snapshot.canonicalCatalogReady && (snapshot.formationProgress != null || snapshot.provisionalTopics.isNotEmpty()))
            return TopicValidationReason.InvalidSnapshotProgress
        if (snapshot.schemaVersion != 1 || snapshot.engineVersion != TOPIC_CATALOG_STORAGE_VERSION)
            return TopicValidationReason.InvalidIdentity
        val identity = snapshot.identity
        val anchors = snapshot.topics + snapshot.provisionalTopics
        snapshot.formationProgress?.let { work ->
            val indices = work.clusters.flatten()
            if (snapshot.engineVersion != SectionHierarchicalEngine.VERSION || snapshot.complete ||
                work.inputSignature.isBlank() || work.totalSections < 0 || work.totalSections > work.totalOpportunities || work.totalOpportunities < 0 || work.clusters.size != work.totalOpportunities ||
                indices.size != work.totalOpportunities || indices.toSet() != (0 until work.totalOpportunities).toSet() ||
                work.chain.distinct().size != work.chain.size ||
                work.chain.any { it !in work.clusters.indices || work.clusters[it].isEmpty() } ||
                work.outerCursor !in 0..work.clusters.size || work.neighborCursor !in 0..work.clusters.size ||
                work.bestNeighbor !in -1 until work.clusters.size || !work.bestDistance.isFinite() ||
                work.bestDistance !in 0f..2f || work.projectionCluster !in 0..work.candidateGroups.size ||
                work.projectionPassage < 0 || work.comparisonsCompleted < 0 ||
                work.candidateGroups.any { group -> group.isEmpty() || group.distinct().size != group.size || group.any { it !in 0 until work.totalOpportunities } } ||
                work.groupingComplete && (work.outerCursor != work.totalOpportunities || work.chain.isNotEmpty() || work.neighborCursor != 0 || work.bestNeighbor != -1) ||
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
        val incremental = snapshot.connectionWork.incrementalKey
        if (incremental != null && (!snapshot.canonicalCatalogReady || !Regex("[0-9a-f]{64}").matches(incremental) ||
                snapshot.connectionWork.inputs.keys != snapshot.acceptedRevisions.keys ||
                snapshot.connectionWork.titleKeys.keys != snapshot.acceptedRevisions.keys ||
                snapshot.connectionWork.inputs.values.any { !Regex("[0-9a-f]{64}").matches(it) } ||
                snapshot.connectionWork.titleKeys.values.any { !Regex("[0-9a-f]{64}").matches(it) } ||
                snapshot.connectionWork.changedIds.any { it !in snapshot.acceptedRevisions } ||
                snapshot.connectionWork.unfinishedSeedIds.any { it !in snapshot.acceptedRevisions } ||
                snapshot.connectionTargetCount != snapshot.acceptedRevisions.size ||
                snapshot.connectionCursor !in 0..snapshot.acceptedRevisions.size ||
                snapshot.connectionPassComplete && (snapshot.connectionCursor != snapshot.acceptedRevisions.size ||
                    snapshot.connectionWork.changedIds.isNotEmpty() || snapshot.connectionWork.unfinishedSeedIds.isNotEmpty())))
            return TopicValidationReason.InvalidSnapshotProgress
        if (snapshot.connectionCursor < 0 || snapshot.connectionPassageCursor < 0 ||
            snapshot.connectionTargetCursor !in 0..snapshot.connectionTargetCount)
            return TopicValidationReason.InvalidSnapshotProgress
        if (snapshot.generation < 0) return TopicValidationReason.InvalidGeneration
        if (json.encodeToString(snapshot.continuity).toByteArray(Charsets.UTF_8).size > MAX_CONTINUITY_BYTES) return TopicValidationReason.ContinuityBound
        if (anchors.isNotEmpty() && (identity == null || identity.dimensions <= 0)) return TopicValidationReason.InvalidIdentity
        for (topic in anchors) {
            if (topic.id.isBlank() || topic.label.isBlank() || topic.anchor.vector.size != identity!!.dimensions ||
                topic.anchor.vector.any { !it.isFinite() } || topic.anchor.vector.none { it != 0f } || topic.subjectVector.size != identity.dimensions ||
                topic.subjectVector.any { !it.isFinite() } || topic.subjectVector.none { it != 0f }) return TopicValidationReason.InvalidTopic
            topic.canonicalConcept?.let { concept ->
                val offered = concept.offered
                val query = concept.queryEmbedding?.query
                if (query != null && (query.topicId != topic.id || query.conceptWorkKey != concept.workKey ||
                        query.embedding != identity || query.text != "${concept.name.trim()}\n${concept.definition.trim()}"))
                    return TopicValidationReason.InvalidTopic
                if (concept.incomplete.any { it.blocksConceptAdmission() } ||
                    !Regex("[0-9a-f]{64}").matches(concept.workKey) || concept.name.isBlank() || concept.name.length > 96 ||
                    concept.definition.isBlank() || concept.definition.length > 512 || concept.listeningDescription.isBlank() ||
                    concept.listeningDescription.length > 512 || offered.size !in 1..16 ||
                    offered.map { it.passageId }.toSet().size != offered.size || offered.any {
                        it.pageId.isBlank() || canonicalSemanticId(it.pageId) != it.pageId || it.passageId.isBlank() ||
                            it.revision.isBlank() || snapshot.acceptedRevisions[it.pageId] != it.revision
                    } || concept.citations.isEmpty() || concept.citations.size > offered.size ||
                    concept.citations.map { it.evidence.passageId }.toSet().size != concept.citations.size ||
                    concept.citations.any { it.evidence !in offered || it.verifier != concept.verifier ||
                        it.topicId != "pending:${concept.workKey}" || it.conceptWorkKey != concept.workKey ||
                        !Regex("[0-9a-f]{64}").matches(it.workKey) || it.decision != SupportDecision.SUPPORTED } ||
                    concept.scannedRevisions.any { (id, revision) -> id.isBlank() || canonicalSemanticId(id) != id ||
                        revision.isBlank() || snapshot.acceptedRevisions[id] != revision } ||
                    concept.matchingComplete && concept.scannedRevisions != snapshot.acceptedRevisions ||
                    concept.supported.distinct().size != concept.supported.size || concept.supported.any {
                        it.pageId.isBlank() || canonicalSemanticId(it.pageId) != it.pageId || it.passageId.isBlank() ||
                            it.revision.isBlank() || concept.scannedRevisions[it.pageId] != it.revision })
                    return TopicValidationReason.InvalidTopic
            }
            if (topic.members.map { canonicalSemanticId(it.pageId) }.distinct().size != topic.members.size ||
                topic.members.any { member -> !member.score.isFinite() || member.score !in -1f..1f ||
                    canonicalSemanticId(member.passage.pageId) != canonicalSemanticId(member.pageId) ||
                    member.anchor != topic.anchor.passage }) return TopicValidationReason.InvalidMember
        }
        for ((index, connection) in snapshot.connections.withIndex()) {
            if (index % 64 == 0) checkActive()
            if (!connection.score.isFinite() ||
            !connection.left.score.isFinite() || !connection.right.score.isFinite() ||
            canonicalSemanticId(connection.left.pageId) == canonicalSemanticId(connection.right.pageId))
                return TopicValidationReason.InvalidConnection
        }
        if (snapshot.subjectRelationships.isNotEmpty() && validatedSubjectRelationships(publishableDiscoveryTopics(snapshot.topics), snapshot.subjectRelationships, snapshot.acceptedRevisions) != snapshot.subjectRelationships)
            return TopicValidationReason.InvalidRelationship
        return null
    }
    private fun readHeader(): TopicStoreHeader {
        require(file.length() <= MAX_RECORD_BYTES)
        return file.inputStream().buffered().use { json.decodeFromStream<TopicStoreHeader>(it) }
    }
    private fun decode(): DecodedTopics {
        val header = readHeader()
        require(header.engineVersion == TOPIC_CATALOG_STORAGE_VERSION && header.schemaVersion == 2)
        val envelope = file.inputStream().buffered().use { json.decodeFromStream<TopicStoreEnvelope>(it) }
        require(envelope.snapshot.connections.isEmpty() && catalog.valid(envelope.catalog))
        val graph = requireNotNull(envelope.topicGraph)
        return DecodedTopics(graph.restore(envelope.snapshot), envelope.catalog, envelope.catalogKey)
    }

    private class RecordLimitExceeded : java.io.IOException()
    private class StaleGuard : RuntimeException()

    /** Counts actual UTF-8 bytes without retaining JSON; stops before allocating a whole record. */
    private fun encode(value: TopicStoreEnvelope, output: OutputStream? = null,
        checkActive: () -> Unit = {}): Long {
        var count = 0L
        val bounded = object : OutputStream() {
            private fun admit(size: Int) {
                checkActive()
                if (size > MAX_RECORD_BYTES - count) throw RecordLimitExceeded()
                count += size
            }
            override fun write(value: Int) { admit(1); output?.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                admit(length); output?.write(bytes, offset, length)
            }
        }
        json.encodeToStream(value, bounded)
        return count
    }

    private suspend fun writeEnvelope(value: TopicStoreEnvelope, external: Long, checkActive: () -> Unit = {},
        beforeCommit: () -> Unit = {}): Boolean {
        val size = encode(value, checkActive = checkActive)
        if (!fits(size, external)) return false
        writeGate.write(com.example.notionreader.domain.document.StorageCategory.DISCOVERY, size) {
            atomicSemanticWrite(file, onStaged = { beforeCommit() }) { encode(value, it, checkActive) }
        }
        return true
    }
    private fun envelope(snapshot: TopicSnapshot, manifest: ConnectionCatalogManifest, key: String?,
        checkActive: () -> Unit = {}): TopicStoreEnvelope {
        val (base, graph) = StoredTopicGraph.separate(snapshot, checkActive)
        return TopicStoreEnvelope(base, manifest, catalogKey = key, topicGraph = graph)
    }
    private fun discard() {
        catalogs.clear(); currentCatalogKey = null; pinnedCatalogKey = null
        directory.listFiles()?.filter { it != resetMarker }?.forEach { check(it.delete() || !it.exists()) { "Unable to discard corrupt topic evidence" } }
    }

    private fun register(storedKey: String?, snapshot: TopicSnapshot, manifest: ConnectionCatalogManifest): String {
        val key = storedKey ?: semanticHash(json.encodeToString(manifest))
        var named = manifest.namedReferences
        var ranked = manifest.ranked
        if (named < 0) {
            named = 0; ranked = true
            var prior: NoteConnection? = null
            val order = compareByDescending<NoteConnection> { it.score }.thenBy { it.id }
            for (pair in catalog.open(manifest)) {
                if (pair.independentNamedReference) named++
                if (prior?.let { order.compare(it, pair) > 0 } == true) ranked = false
                prior = pair
            }
        }
        catalogs[key] = manifest.copy(identity = manifest.identity ?: snapshot.identity,
            namedReferences = named, ranked = ranked)
        currentCatalogKey = key
        return key
    }
    private fun preview(manifest: ConnectionCatalogManifest): List<NoteConnection> {
        if (manifest.ranked) return catalog.read(manifest, 0, minOf(2, manifest.count))
        val best = mutableListOf<NoteConnection>()
        val order = compareByDescending<NoteConnection> { it.score }.thenBy { it.id }
        for (pair in catalog.open(manifest)) {
            best += pair; best.sortWith(order)
            if (best.size > 2) best.removeAt(2)
        }
        return best
    }
    private fun metadata(snapshot: TopicSnapshot, key: String, manifest: ConnectionCatalogManifest): TopicSnapshot =
        snapshot.copy(connections = preview(manifest), catalogReference = ConnectionCatalogReference(key, manifest.count, manifest.namedReferences,
            semanticHash(manifest.pages.joinToString("|") { it.digest })))

    override suspend fun inspectMetadata(): TopicMetadataState = withContext(Dispatchers.IO) { mutex.withLock {
        if (resetMarker.exists()) return@withLock if (resetMarker.isFile && resetMarker.length() == 0L) TopicMetadataState.ResetPending else TopicMetadataState.Unknown
        if (!file.exists()) {
            if (!allowFreshInitialization) return@withLock TopicMetadataState.Missing
            if (consumedMarker.exists()) return@withLock TopicMetadataState.Unknown
            if (!freshMarker.exists()) return@withLock TopicMetadataState.Missing
            return@withLock if (freshMarker.isFile && freshMarker.length() == 0L) TopicMetadataState.Fresh else TopicMetadataState.Unknown
        }
        if (!file.isFile) return@withLock TopicMetadataState.Unreadable
        runCatching {
            require(file.length() <= MAX_RECORD_BYTES)
            val (snapshot, manifest, storedKey) = decode()
            require(validationFailure(snapshot) == null)
            val key = register(storedKey, snapshot, manifest)
            TopicMetadataState.Available(metadata(snapshot, key, catalogs.getValue(key)))
        }.getOrElse { TopicMetadataState.Unreadable }
    } }
    override suspend fun readMetadata(): TopicSnapshot? = withContext(Dispatchers.IO) { mutex.withLock {
        if (resetMarker.exists() || !file.isFile) return@withLock null
        runCatching {
            require(file.length() <= MAX_RECORD_BYTES)
            val (snapshot, manifest, storedKey) = decode()
            require(validationFailure(snapshot) == null)
            val key = register(storedKey, snapshot, manifest)
            metadata(snapshot, key, catalogs.getValue(key))
        }.getOrElse { discard(); null }
    } }
    override suspend fun connectionCatalog(snapshot: TopicSnapshot): List<NoteConnection> = withContext(Dispatchers.IO) { mutex.withLock {
        snapshot.catalogReference?.let { ref -> catalogs[ref.key]?.let { catalog.open(it) }
            ?: throw java.io.IOException("Connection catalog is unavailable") } ?: snapshot.connections
    } }
    override suspend fun connectionPage(snapshot: TopicSnapshot, offset: Int, limit: Int): ConnectionCatalogPageResult =
        withContext(Dispatchers.IO) { mutex.withLock {
            require(offset >= 0 && limit in 1..100)
            if (resetMarker.exists()) return@withLock ConnectionCatalogPageResult(emptyList(), 0, false)
            val ref = snapshot.catalogReference ?: return@withLock ConnectionCatalogPageResult(snapshot.connections.drop(offset).take(limit), snapshot.connections.size)
            val manifest = catalogs[ref.key] ?: return@withLock ConnectionCatalogPageResult(emptyList(), 0, false)
            runCatching { ConnectionCatalogPageResult(catalog.read(manifest, offset, limit), manifest.count) }
                .getOrElse { ConnectionCatalogPageResult(emptyList(), 0, false) }
        } }
    override suspend fun findConnection(snapshot: TopicSnapshot, id: String): ConnectionCatalogPageResult =
        withContext(Dispatchers.IO) { mutex.withLock {
            if (resetMarker.exists()) return@withLock ConnectionCatalogPageResult(emptyList(), 0, false)
            val ref = snapshot.catalogReference ?: return@withLock ConnectionCatalogPageResult(listOfNotNull(snapshot.connections.firstOrNull { it.id == id }), snapshot.connections.size)
            val manifest = catalogs[ref.key] ?: return@withLock ConnectionCatalogPageResult(emptyList(), 0, false)
            runCatching { ConnectionCatalogPageResult(listOfNotNull(catalog.find(manifest, id)), manifest.count) }
                .getOrElse { ConnectionCatalogPageResult(emptyList(), 0, false) }
        } }
    private fun retireCatalogs() {
        val keep = setOfNotNull(currentCatalogKey, pinnedCatalogKey)
        catalogs.keys.retainAll(keep)
        val files = catalogs.values.flatMap { it.pages }.map { it.fileName }.toSet()
        directory.listFiles { f -> f.name.startsWith("connections-") && f.name !in files }?.forEach {
            check(it.delete() || !it.exists()) { "Unable to retire catalog evidence" }
        }
    }
    override suspend fun pinInspection(snapshot: TopicSnapshot): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        if (currentCatalogKey == null && file.isFile) {
            val (stored, manifest, storedKey) = decode()
            register(storedKey, stored, manifest)
        }
        val key = snapshot.catalogReference?.key
        if (key != null && key !in catalogs) return@withLock false
        pinnedCatalogKey = key
        retireCatalogs()
        true
    } }
    override suspend fun refreshCatalogReference(snapshot: TopicSnapshot): TopicSnapshot = withContext(Dispatchers.IO) { mutex.withLock {
        val ref = snapshot.catalogReference ?: return@withLock snapshot
        val manifest = catalogs[ref.key] ?: return@withLock snapshot.copy(connections = emptyList(), catalogReference = null)
        metadata(snapshot, ref.key, manifest)
    } }
    override suspend fun read(): TopicSnapshot? = withContext(Dispatchers.IO) { mutex.withLock {
        if (resetMarker.exists() || !file.isFile) return@withLock null
        val result = runCatching {
            require(file.length() <= MAX_RECORD_BYTES)
            val (snapshot, manifest, storedKey) = decode()
            val result = snapshot.copy(connections = catalog.read(manifest))
            require(validationFailure(result) == null)
            result
        }.getOrNull()
        if (result == null) discard()
        result
    } }
    override suspend fun readConnections(offset: Int, limit: Int): List<NoteConnection> = withContext(Dispatchers.IO) { mutex.withLock {
        require(offset >= 0 && limit in 1..100)
        if (resetMarker.exists() || !file.isFile) return@withLock emptyList()
        runCatching {
            require(file.length() <= MAX_RECORD_BYTES)
            val (snapshot, manifest) = decode()
            require(validationFailure(snapshot) == null)
            catalog.read(manifest, offset, limit)
        }.getOrElse { discard(); emptyList() }
    } }
    override suspend fun write(snapshot: TopicSnapshot): Boolean = writeDetailed(snapshot) == TopicWriteResult.Written
    private fun fits(bytes: Long, external: Long): Boolean {
        val existing = directory.listFiles()?.sumOf { it.length() } ?: 0L
        return external >= 0 && bytes <= maxBytes - existing - external
    }
    private suspend fun stageCatalog(connections: List<NoteConnection>, identity: EmbeddingIdentity?,
        external: Long, staged: MutableList<File>): ConnectionCatalogManifest? {
        val generation = UUID.randomUUID().toString()
        val pages = mutableListOf<ConnectionCatalogPage>()
        var named = 0
        var ranked = true
        var prior: NoteConnection? = null
        val order = compareByDescending<NoteConnection> { it.score }.thenBy { it.id }
        for ((number, chunk) in connections.asSequence().chunked(FileConnectionCatalog.PAGE_SIZE).withIndex()) {
            currentCoroutineContext().ensureActive()
            for (pair in chunk) {
                if (pair.independentNamedReference) named++
                if (prior?.let { order.compare(it, pair) > 0 } == true) ranked = false
                prior = pair
            }
            val bytes = catalog.encode(chunk)
            if (!fits(bytes.size.toLong(), external)) return null
            val target = File(directory, "connections-$generation-$number.json")
            staged += target
            writeGate.write(com.example.notionreader.domain.document.StorageCategory.DISCOVERY, bytes.size.toLong()) {
                atomicSemanticWrite(target, bytes)
            }
            pages += ConnectionCatalogPage(target.name, chunk.size, semanticHash(bytes.toString(Charsets.UTF_8)), chunk.map { it.id })
        }
        return ConnectionCatalogManifest(pages, connections.size, identity, named, ranked)
    }
    override suspend fun reconcileCatalogs(sources: Map<String, String>, model: EmbeddingIdentity,
        titles: Map<String, String>): Boolean {
        val caller = currentCoroutineContext()
        return withContext(Dispatchers.IO + NonCancellable) { mutex.withLock {
        if (catalogs.isEmpty()) return@withLock true
        try {
            val external = otherBytes()
            val staged = mutableListOf<File>()
            var changed = false
            var scanned = 0
            for ((key, manifest) in catalogs.toMap()) {
                val original = catalog.open(manifest)
                val retained = filterConnectionCatalog(original) { pair ->
                    // Before any withdrawal starts changing disk, scanning is read-only.
                    if (!changed && scanned++ % FileConnectionCatalog.PAGE_SIZE == 0) caller.ensureActive()
                    manifest.identity == model && sources[canonicalSemanticId(pair.left.pageId)] == pair.left.revision &&
                        sources[canonicalSemanticId(pair.right.pageId)] == pair.right.revision &&
                        (!pair.independentNamedReference || classifyConnectionEvidence(pair, titles).kind == ConnectionEvidenceKind.NamedReference)
                }
                if (retained === original) continue
                changed = true
                val replacement = stageCatalog(retained, manifest.identity, external, staged)
                    ?: throw java.io.IOException("Insufficient storage for mandatory evidence withdrawal")
                catalogs[key] = replacement
            }
            if (!changed) return@withLock true
            if (file.isFile) {
                val (snapshot, _) = decode()
                val manifest = catalogs[currentCatalogKey]
                if (manifest != null) {
                    if (!writeEnvelope(envelope(snapshot.copy(catalogReference = null), manifest,
                        currentCatalogKey), external)) throw java.io.IOException("Insufficient storage for evidence withdrawal")
                }
            }
            retireCatalogs()
            true
        } catch (_: com.example.notionreader.domain.document.StorageRefused) { discard(); false }
        catch (_: java.io.IOException) { discard(); false }
        catch (_: IllegalArgumentException) { discard(); false }
    } } }
    override suspend fun writeDetailed(snapshot: TopicSnapshot): TopicWriteResult = writeInternal(snapshot, null)
    override suspend fun writeGuarded(snapshot: TopicSnapshot, current: () -> Boolean): TopicGuardedWriteResult {
        if (!current()) return TopicGuardedWriteResult.Stale
        return try {
            when (val result = writeInternal(snapshot, current)) {
                TopicWriteResult.Written -> TopicGuardedWriteResult.Written
                is TopicWriteResult.Rejected -> TopicGuardedWriteResult.Rejected(result)
            }
        } catch (_: StaleGuard) { TopicGuardedWriteResult.Stale }
    }
    override suspend fun initializeFresh(snapshot: TopicSnapshot, current: () -> Boolean): TopicGuardedWriteResult {
        if (!current()) return TopicGuardedWriteResult.Stale
        return try {
            when (val result = writeInternal(snapshot, current, requireFresh = true)) {
                TopicWriteResult.Written -> TopicGuardedWriteResult.Written
                is TopicWriteResult.Rejected -> TopicGuardedWriteResult.Rejected(result)
            }
        } catch (_: StaleGuard) { TopicGuardedWriteResult.Stale }
    }
    private suspend fun writeInternal(snapshot: TopicSnapshot, guard: (() -> Boolean)?, requireFresh: Boolean = false, finishingReset: Boolean = false): TopicWriteResult = withContext(Dispatchers.IO) { mutex.withLock {
        val staged = mutableListOf<File>()
        var committed = false
        var consumedFresh = false
        try {
            if (resetMarker.exists() && !finishingReset) return@withLock TopicWriteResult.Rejected(TopicWriteFailure.Unknown)
            if (finishingReset && (!resetMarker.isFile || resetMarker.length() != 0L)) return@withLock TopicWriteResult.Rejected(TopicWriteFailure.Unknown)
            if (requireFresh && (!allowFreshInitialization || file.exists() || consumedMarker.exists() ||
                    !freshMarker.isFile || freshMarker.length() != 0L)) return@withLock TopicWriteResult.Rejected(TopicWriteFailure.Unknown)
            if (requireFresh && (!snapshot.canonicalCatalogReady || snapshot.identity == null || snapshot.complete ||
                    snapshot.generation != 0L || snapshot.topics.isNotEmpty() || snapshot.provisionalTopics.isNotEmpty() ||
                    snapshot.continuity.isNotEmpty() || snapshot.acceptedRevisions.isNotEmpty() || snapshot.connections.isNotEmpty() ||
                    snapshot.catalogReference != null || snapshot.referenceTitles.isNotEmpty() || snapshot.subjectRelationships.isNotEmpty() ||
                    snapshot.formationProgress != null || snapshot.formationReuse != null || snapshot.connectionWork != ConnectionWork()))
                return@withLock TopicWriteResult.Rejected(TopicWriteFailure.InvalidSnapshot, TopicValidationReason.InvalidSnapshotProgress)
            val context = currentCoroutineContext()
            validationFailure(snapshot) { context.ensureActive() }?.let {
                return@withLock TopicWriteResult.Rejected(TopicWriteFailure.InvalidSnapshot, it)
            }
            val external = otherBytes()
            val ref = snapshot.catalogReference
            val opened = snapshot.connections as? OpenConnectionCatalog
            val reused = opened?.takeIf { it.ownerDirectory.absoluteFile == directory.absoluteFile }
                ?.let { view -> catalogs.entries.firstOrNull { it.value === view.manifest } }
            val manifest = if (ref != null) catalogs[ref.key]
                ?: return@withLock TopicWriteResult.Rejected(TopicWriteFailure.InvalidSnapshot, TopicValidationReason.InvalidConnection)
                else reused?.value ?: stageCatalog(snapshot.connections, snapshot.identity, external, staged)
                    ?: return@withLock TopicWriteResult.Rejected(TopicWriteFailure.BudgetExceeded)
            val key = ref?.key ?: reused?.key ?: UUID.randomUUID().toString()
            if (freshMarker.isFile && freshMarker.length() == 0L && !consumedMarker.exists()) {
                writeGate.write(com.example.notionreader.domain.document.StorageCategory.DISCOVERY, 0L) {
                    java.nio.file.Files.move(freshMarker.toPath(), consumedMarker.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                }
                consumedFresh = true
            }
            if (!writeEnvelope(envelope(snapshot.copy(connections = emptyList(), catalogReference = null), manifest,
                key) { context.ensureActive() }, external, { context.ensureActive() },
                { if (guard != null && !guard()) throw StaleGuard() }))
                return@withLock TopicWriteResult.Rejected(TopicWriteFailure.BudgetExceeded)
            committed = true
            catalogs[key] = manifest; currentCatalogKey = key
            retireCatalogs()
            if (finishingReset && !resetMarker.delete() && resetMarker.exists())
                return@withLock TopicWriteResult.Rejected(TopicWriteFailure.IoFailure)
            TopicWriteResult.Written
        } catch (_: RecordLimitExceeded) {
            TopicWriteResult.Rejected(TopicWriteFailure.RecordMemoryLimit)
        } catch (_: com.example.notionreader.domain.document.StorageRefused) {
            TopicWriteResult.Rejected(TopicWriteFailure.BudgetExceeded)
        } catch (_: java.io.IOException) {
            TopicWriteResult.Rejected(TopicWriteFailure.IoFailure)
        } finally {
            if (!committed) staged.forEach { check(it.delete() || !it.exists()) { "Unable to remove catalog staging" } }
            if (consumedFresh) withContext(NonCancellable) {
                if (committed) consumedMarker.delete()
                else if (!file.exists()) {
                    try {
                        writeGate.write(com.example.notionreader.domain.document.StorageCategory.DISCOVERY, 0L) {
                            java.nio.file.Files.move(consumedMarker.toPath(), freshMarker.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                        }
                    } catch (_: java.io.IOException) { /* A consumed marker is conservative after interruption. */ }
                    catch (_: com.example.notionreader.domain.document.StorageRefused) { /* Retry requires recovery. */ }
                }
            }
        }
    } }
    override suspend fun resetPending(): Boolean = withContext(Dispatchers.IO) { mutex.withLock { resetMarker.exists() } }
    override suspend fun beginReset(): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        try {
            if (resetMarker.exists()) return@withLock resetMarker.isFile && resetMarker.length() == 0L
            writeGate.write(com.example.notionreader.domain.document.StorageCategory.DISCOVERY, 0L) {
                atomicSemanticWrite(resetMarker, byteArrayOf())
            }
            true
        } catch (_: java.io.IOException) { false }
        catch (_: com.example.notionreader.domain.document.StorageRefused) { false }
    } }
    override suspend fun finishReset(snapshot: TopicSnapshot): TopicWriteResult {
        if (!snapshot.canonicalCatalogReady || snapshot.identity == null || snapshot.complete || snapshot.generation != 0L ||
            snapshot.topics.isNotEmpty() || snapshot.provisionalTopics.isNotEmpty() || snapshot.continuity.isNotEmpty() ||
            snapshot.acceptedRevisions.isNotEmpty() || snapshot.connections.isNotEmpty() || snapshot.catalogReference != null ||
            snapshot.referenceTitles.isNotEmpty() || snapshot.subjectRelationships.isNotEmpty() || snapshot.formationProgress != null ||
            snapshot.formationReuse != null || snapshot.connectionWork != ConnectionWork())
            return TopicWriteResult.Rejected(TopicWriteFailure.InvalidSnapshot, TopicValidationReason.InvalidSnapshotProgress)
        return writeInternal(snapshot, null, finishingReset = true)
    }
    override suspend fun clear(): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        discard()
    } }
    override suspend fun bytes(): Long = withContext(Dispatchers.IO) { mutex.withLock {
        directory.listFiles()?.sumOf { it.length() } ?: 0L
    } }
}
