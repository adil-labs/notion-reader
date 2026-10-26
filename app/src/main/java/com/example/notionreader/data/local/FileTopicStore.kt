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

/** Header-only decoding skips unknown payloads without constructing legacy topic memberships. */
@Serializable
private data class TopicStoreHeader(val schemaVersion: Int = 0, val engineVersion: Int = 0,
    val snapshot: TopicSnapshotMarker? = absentTopicSnapshot, val catalog: ConnectionCatalogManifest? = null,
    val catalogKey: String? = null) {
    // A missing key is legacy; an explicitly null key must still take strict envelope decoding.
    val isEnvelope: Boolean get() = snapshot !== absentTopicSnapshot || catalog != null || catalogKey != null
}

/** Unknown topic fields are skipped by the streaming decoder, never materialized. */
@Serializable
private data class TopicSnapshotMarker(val schemaVersion: Int = 0)

private val absentTopicSnapshot = TopicSnapshotMarker(-1)

private data class DecodedTopics(val snapshot: TopicSnapshot,
    val catalog: ConnectionCatalogManifest?, val catalogKey: String?)

@Serializable
private data class TopicStoreEnvelope(val snapshot: TopicSnapshot, val catalog: ConnectionCatalogManifest,
    val schemaVersion: Int = 2, val engineVersion: Int = SectionHierarchicalEngine.VERSION,
    val catalogKey: String? = null, val topicGraph: StoredTopicGraph? = null)

/** Store under the app's noBackupFilesDir. Budget includes the old file during atomic staging. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class FileTopicStore(private val directory: File, private val maxBytes: Long = Long.MAX_VALUE,
    private val otherBytes: suspend () -> Long = { 0L },
    private val writeGate: com.example.notionreader.domain.document.StorageWriteGate = com.example.notionreader.domain.document.StorageWriteGate.Unrestricted) : TopicStore {
    private val mutex = Mutex()
    private companion object {
        const val MAX_RECORD_BYTES = 64L * 1024 * 1024
        // Separate from disk capacity: accommodates dense hundred-Note continuity and legacy identities.
        const val MAX_CONTINUITY_BYTES = 4 * 1024 * 1024
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val catalog = FileConnectionCatalog(directory, json)
    private val file get() = File(directory, "topics.json")
    private val catalogs = linkedMapOf<String, ConnectionCatalogManifest>()
    private var currentCatalogKey: String? = null
    private var pinnedCatalogKey: String? = null
    init {
        require(maxBytes > 0)
        directory.mkdirs()
        directory.listFiles { f -> f.extension == "tmp" }?.forEach { check(it.delete()) }
        val referenced = runCatching {
            if (!file.isFile) emptySet() else {
                require(file.length() <= MAX_RECORD_BYTES)
                val header = readHeader()
                if (header.isEnvelope) header.catalog?.pages.orEmpty().map { it.fileName }.toSet()
                else emptySet()
            }
        }.getOrDefault(emptySet())
        directory.listFiles { f -> f.name.startsWith("connections-") && f.name !in referenced }?.forEach {
            check(it.delete() || !it.exists()) { "Unable to remove uncommitted catalog evidence" }
        }
    }
    private fun validationFailure(snapshot: TopicSnapshot, checkActive: () -> Unit = {}): TopicValidationReason? {
        checkActive()
        if (snapshot.schemaVersion != 1 || snapshot.engineVersion != SectionHierarchicalEngine.VERSION)
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
        require(header.engineVersion == SectionHierarchicalEngine.VERSION && header.schemaVersion in 0..2)
        return if (header.isEnvelope) {
            val envelope = file.inputStream().buffered().use { json.decodeFromStream<TopicStoreEnvelope>(it) }
            require(envelope.snapshot.connections.isEmpty() && catalog.valid(envelope.catalog))
            require(envelope.schemaVersion != 2 || envelope.topicGraph != null)
            DecodedTopics(envelope.topicGraph?.restore(envelope.snapshot) ?: envelope.snapshot, envelope.catalog, envelope.catalogKey)
        } else DecodedTopics(file.inputStream().buffered().use { json.decodeFromStream<TopicSnapshot>(it) }, null, null)
    }

    private class RecordLimitExceeded : java.io.IOException()

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

    private suspend fun writeEnvelope(value: TopicStoreEnvelope, external: Long, checkActive: () -> Unit = {}): Boolean {
        val size = encode(value, checkActive = checkActive)
        if (!fits(size, external)) return false
        writeGate.write(com.example.notionreader.domain.document.StorageCategory.DISCOVERY, size) {
            atomicSemanticWrite(file) { encode(value, it, checkActive) }
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
        directory.listFiles()?.forEach { check(it.delete() || !it.exists()) { "Unable to discard corrupt topic evidence" } }
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

    override suspend fun readMetadata(): TopicSnapshot? = withContext(Dispatchers.IO) { mutex.withLock {
        if (!file.isFile) return@withLock null
        runCatching {
            require(file.length() <= MAX_RECORD_BYTES)
            val (snapshot, manifest, storedKey) = decode()
            require(validationFailure(snapshot) == null)
            if (manifest == null) snapshot else {
                val key = register(storedKey, snapshot, manifest)
                metadata(snapshot, key, catalogs.getValue(key))
            }
        }.getOrElse { discard(); null }
    } }
    override suspend fun connectionCatalog(snapshot: TopicSnapshot): List<NoteConnection> = withContext(Dispatchers.IO) { mutex.withLock {
        snapshot.catalogReference?.let { ref -> catalogs[ref.key]?.let { catalog.open(it) }
            ?: throw java.io.IOException("Connection catalog is unavailable") } ?: snapshot.connections
    } }
    override suspend fun connectionPage(snapshot: TopicSnapshot, offset: Int, limit: Int): ConnectionCatalogPageResult =
        withContext(Dispatchers.IO) { mutex.withLock {
            require(offset >= 0 && limit in 1..100)
            val ref = snapshot.catalogReference ?: return@withLock ConnectionCatalogPageResult(snapshot.connections.drop(offset).take(limit), snapshot.connections.size)
            val manifest = catalogs[ref.key] ?: return@withLock ConnectionCatalogPageResult(emptyList(), 0, false)
            runCatching { ConnectionCatalogPageResult(catalog.read(manifest, offset, limit), manifest.count) }
                .getOrElse { ConnectionCatalogPageResult(emptyList(), 0, false) }
        } }
    override suspend fun findConnection(snapshot: TopicSnapshot, id: String): ConnectionCatalogPageResult =
        withContext(Dispatchers.IO) { mutex.withLock {
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
            if (manifest != null) register(storedKey, stored, manifest)
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
        if (!file.isFile) return@withLock null
        val result = runCatching {
            require(file.length() <= MAX_RECORD_BYTES)
            // Engine zero is derived legacy state, never a reason to execute the old algorithm.
            // Deleting it is idempotent across interrupted upgrades and leaves all other stores alone.
            val (snapshot, manifest, storedKey) = decode()
            val result = if (manifest == null) snapshot else snapshot.copy(connections = catalog.read(manifest))
            require(validationFailure(result) == null)
            result
        }.getOrNull()
        if (result == null) discard()
        result
    } }
    override suspend fun readConnections(offset: Int, limit: Int): List<NoteConnection> = withContext(Dispatchers.IO) { mutex.withLock {
        require(offset >= 0 && limit in 1..100)
        if (!file.isFile) return@withLock emptyList()
        runCatching {
            require(file.length() <= MAX_RECORD_BYTES)
            val (snapshot, manifest) = decode()
            require(validationFailure(snapshot) == null)
            if (manifest == null) snapshot.connections.drop(offset).take(limit) else catalog.read(manifest, offset, limit)
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
    override suspend fun writeDetailed(snapshot: TopicSnapshot): TopicWriteResult = withContext(Dispatchers.IO) { mutex.withLock {
        val staged = mutableListOf<File>()
        var committed = false
        try {
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
            if (!writeEnvelope(envelope(snapshot.copy(connections = emptyList(), catalogReference = null), manifest,
                key) { context.ensureActive() }, external) { context.ensureActive() })
                return@withLock TopicWriteResult.Rejected(TopicWriteFailure.BudgetExceeded)
            committed = true
            catalogs[key] = manifest; currentCatalogKey = key
            retireCatalogs()
            TopicWriteResult.Written
        } catch (_: RecordLimitExceeded) {
            TopicWriteResult.Rejected(TopicWriteFailure.RecordMemoryLimit)
        } catch (_: com.example.notionreader.domain.document.StorageRefused) {
            TopicWriteResult.Rejected(TopicWriteFailure.BudgetExceeded)
        } catch (_: java.io.IOException) {
            TopicWriteResult.Rejected(TopicWriteFailure.IoFailure)
        } finally {
            if (!committed) staged.forEach { check(it.delete() || !it.exists()) { "Unable to remove catalog staging" } }
        }
    } }
    override suspend fun clear(): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        discard()
    } }
    override suspend fun bytes(): Long = withContext(Dispatchers.IO) { mutex.withLock {
        directory.listFiles()?.sumOf { it.length() } ?: 0L
    } }
}
