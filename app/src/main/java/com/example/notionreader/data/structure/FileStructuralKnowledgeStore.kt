package com.example.notionreader.data.structure

import com.example.notionreader.domain.document.StorageCategory
import com.example.notionreader.domain.document.StorageFailure
import com.example.notionreader.domain.document.StorageRefused
import com.example.notionreader.domain.document.StorageWriteGate
import com.example.notionreader.domain.structure.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Page files own content; the atomic, content-free state owns eligibility. Indexes are disposable. */
class FileStructuralKnowledgeStore(
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val writeGate: StorageWriteGate = StorageWriteGate.Unrestricted,
    private val byteLimit: () -> Long = { 16L * 1024 * 1024 },
    private val maxPages: Int = 200
) : StructuralKnowledgeStore, StructuralKnowledgeRepository {
    private val mutex = Mutex()
    private val pagesDir = File(directory, "pages")
    private val stateFile = File(directory, "source-state.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var cachedIndex: StructuralIndex? = null
    private var cachedOwners: Map<String, String> = emptyMap()
    private var cachedSectionAnchors: Map<String, StructuralNavigationAnchor> = emptyMap()

    /** Includes abandoned staging files; the composition root includes this directory in its budget. */
    fun actualBytes(): Long = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(ioDispatcher) {
        mutex.withLock { currentCoroutineContext().ensureActive(); block() }
    }

    private fun state(): SourceState {
        if (!stateFile.exists()) return SourceState()
        check(stateFile.length() <= MAX_STATE_BYTES) { "Structural source state exceeds its bound" }
        val value = json.decodeFromString(SourceState.serializer(), stateFile.readText())
        check(value.schemaVersion == CURRENT_SCHEMA_VERSION && value.epoch >= 0 && value.minimumEpoch in 0..value.epoch &&
            value.removals.size <= MAX_IDENTITIES && value.containerRemovals.size <= MAX_IDENTITIES && value.pending.size <= MAX_PENDING &&
            value.blocked.size <= MAX_PENDING && value.blocked.all { canonical(it.key) == it.key && it.value.length <= 256 } &&
            value.removals.all { canonical(it.key) == it.key && runCatching { PageAccessibilityState.valueOf(it.value) }.isSuccess } &&
            value.containerRemovals.all { canonical(it) == it } && (value.lastResourceSourceId == null || canonical(value.lastResourceSourceId) == value.lastResourceSourceId) && value.pending.all { canonical(it.pageId) == it.pageId && it.sourceIdentity == value.identity }) {
            "Unsupported structural source state"
        }
        return value
    }

    private fun saveState(value: SourceState) {
        val bytes = json.encodeToString(SourceState.serializer(), value).toByteArray()
        check(bytes.size <= MAX_STATE_BYTES) { "Structural source state exceeds its bound" }
        atomicWrite(stateFile, bytes)
        cachedIndex = null
        cachedOwners = emptyMap()
    }

    override suspend fun initSource(sourceIdentity: String, sourceEpoch: Long): Unit = locked {
        require(sourceIdentity.isNotBlank() && sourceIdentity.length <= 256 && sourceEpoch >= 0)
        val old = state()
        if (old.identity == sourceIdentity && old.epoch >= sourceEpoch) return@locked
        if (old.identity.isNotEmpty() && sourceEpoch <= old.epoch) return@locked
        val changed = old.identity != sourceIdentity
        val next = if (changed) SourceState(identity = sourceIdentity, epoch = sourceEpoch, minimumEpoch = sourceEpoch)
            else old.copy(epoch = sourceEpoch)
        // Eligibility changes first. A crash or failed unlink cannot expose the previous identity.
        saveState(next)
        if (changed && old.identity.isNotEmpty()) pagesDir.listFiles()?.forEach { it.delete() }
        // Old experimental indexes are never used as eligibility authority.
        File(directory, "structural-index.json").delete()
        File(directory, "source-epoch.json").delete()
        File(directory, "pending-work.json").delete()
    }

    private fun canonical(id: String): String? = normalizeNotionId(id)
    private fun pageFile(id: String) = File(pagesDir, "$id.json")
    private fun containerFile(id: String) = File(pagesDir, "container-$id.json")

    private fun readContainer(id: String, active: SourceState): StructuralContainer? {
        if (active.identity.isEmpty() || id in active.containerRemovals) return null
        val file = containerFile(id)
        if (!file.isFile || file.length() > 64 * 1024) return null
        val value = runCatching { json.decodeFromString(StructuralContainerDto.serializer(), file.readText()) }.getOrNull() ?: return null
        if (value.schemaVersion != CURRENT_SCHEMA_VERSION || value.epoch < active.minimumEpoch ||
            value.sourceIdentity != active.identity || canonical(value.id) != id) return null
        return runCatching { value.toDomain() }.getOrNull()
    }

    override suspend fun getContainer(containerId: String): StructuralContainer? = locked {
        canonical(containerId)?.let { readContainer(it, state()) }
    }

    override suspend fun writeContainer(container: StructuralContainer, sourceIdentity: String, sourceEpoch: Long): Unit = locked { writeContainerLocked(container, sourceIdentity, sourceEpoch) }

    private suspend fun writeContainerLocked(container: StructuralContainer, sourceIdentity: String, sourceEpoch: Long): Unit {
        val id = canonical(container.id) ?: return
        val active = state()
        if (active.identity != sourceIdentity || active.epoch != sourceEpoch || container.sourceIdentity != sourceIdentity) return
        if (container.accessibilityState != PageAccessibilityState.ACCESSIBLE) {
            if (id !in active.containerRemovals) {
                check(active.epoch < Long.MAX_VALUE && active.containerRemovals.size < MAX_IDENTITIES)
                saveState(active.copy(epoch = active.epoch + 1, containerRemovals = active.containerRemovals + id))
            }
            containerFile(id).delete()
            return
        }
        if (readContainer(id, active) == container) return
        val file = containerFile(id)
        if (!file.exists() && pagesDir.listFiles { it.name.startsWith("container-") && it.extension == "json" }.orEmpty().size >= maxPages)
            throw StorageRefused(StorageFailure.CATEGORY_LIMIT)
        val bytes = json.encodeToString(StructuralContainerDto.serializer(), container.toDto(active.epoch)).toByteArray()
        if (bytes.size > 64 * 1024 || actualBytes() + bytes.size > byteLimit()) throw StorageRefused(StorageFailure.CATEGORY_LIMIT)
        writeGate.write(StorageCategory.NOTES, bytes.size.toLong()) {
            atomicWrite(file, bytes)
            if (id in active.containerRemovals) saveState(active.copy(containerRemovals = active.containerRemovals - id))
            cachedIndex = null
        }
    }

    private fun readCurrent(id: String, active: SourceState): StructuralPageRecord? {
        if (active.identity.isEmpty() || id in active.removals) return null
        val file = pageFile(id)
        if (!file.isFile || file.length() > MAX_PAGE_BYTES) return null
        val dto = runCatching { json.decodeFromString(StructuralPageRecordDto.serializer(), file.readText()) }.getOrNull() ?: return null
        if (dto.schemaVersion != CURRENT_SCHEMA_VERSION || dto.eligibilityEpoch < active.minimumEpoch || dto.sourceIdentity != active.identity ||
            dto.page.sourceIdentity != active.identity || canonical(dto.page.id) != id) return null
        val record = runCatching { dto.toDomain() }.getOrNull() ?: return null
        if (record.page.accessibilityState != PageAccessibilityState.ACCESSIBLE ||
            record.blocks.size > MAX_BLOCKS || record.outgoingRelationships.size > MAX_EDGES ||
            record.blocks.any { canonical(it.pageId) != id || canonical(it.id) == null } ||
            record.blocks.map { it.id }.distinct().size != record.blocks.size ||
            record.outgoingRelationships.any { canonical(it.provenance.sourcePageId) != id || it.provenance.sourceIdentity != active.identity }) return null
        return record
    }

    override suspend fun readPage(pageId: String): StructuralPageRecord? = locked {
        canonical(pageId)?.let { readCurrent(it, state()) }
    }

    override suspend fun writePage(record: StructuralPageRecord, sourceIdentity: String, sourceEpoch: Long): Unit = locked { writePageLocked(record, sourceIdentity, sourceEpoch) }

    private suspend fun writePageLocked(record: StructuralPageRecord, sourceIdentity: String, sourceEpoch: Long): Unit {
        val id = canonical(record.page.id) ?: return
        val active = state()
        if (active.identity != sourceIdentity || active.epoch != sourceEpoch || sourceIdentity.isEmpty()) return
        require(record.schemaVersion == CURRENT_SCHEMA_VERSION && record.sourceIdentity == sourceIdentity &&
            record.page.sourceIdentity == sourceIdentity && record.outgoingRelationships.all {
                canonical(it.provenance.sourcePageId) == id && it.provenance.sourceIdentity == sourceIdentity
            }) { "Structural source mismatch" }
        if (record.blocks.size > MAX_BLOCKS || record.outgoingRelationships.size > MAX_EDGES) throw StructuralPageLimitExceeded(record.page.sourceRevision)
        require(record.blocks.all { canonical(it.id) != null && canonical(it.pageId) == id } &&
            record.blocks.map { canonical(it.id) }.distinct().size == record.blocks.size) { "Invalid structural block identity" }
        val previous = readCurrent(id, active)
        if (record.page.syncStatus != SyncCompleteness.COMPLETE && previous != null) {
            if (record.page.syncStatus == SyncCompleteness.LIMIT_EXCEEDED) {
                blockPageWorkLocked(id, record.page.sourceRevision, sourceIdentity, sourceEpoch)
                return
            }
            enqueue(active, PendingStructuralWork(id, Instant.now().toEpochMilli(), "partial", sourceIdentity))
            return
        }
        if (record.page.accessibilityState != PageAccessibilityState.ACCESSIBLE) return
        val dto = record.toDto().copy(eligibilityEpoch = active.epoch)
        val bytes = json.encodeToString(StructuralPageRecordDto.serializer(), dto).toByteArray()
        if (bytes.size > MAX_PAGE_BYTES) throw StructuralPageLimitExceeded(record.page.sourceRevision)
        val existingCount = pagesDir.listFiles { file -> file.isFile && canonical(file.nameWithoutExtension) != null }?.size ?: 0
        if (!pageFile(id).exists() && existingCount >= maxPages) throw StorageRefused(StorageFailure.CATEGORY_LIMIT)
        val priorDto = previous?.toDto()?.copy(eligibilityEpoch = active.epoch)
        // Extraction observation time is not a source fact. Everything else participates in equality.
        fun factual(value: StructuralPageRecordDto) = value.copy(outgoingRelationships = value.outgoingRelationships.map {
            it.copy(provenanceExtractedAtEpochMilli = 0)
        })
        val unchanged = priorDto != null && factual(priorDto) == factual(dto)
        val pending = active.pending.filterNot { it.pageId == id }
        val limited = record.page.syncStatus == SyncCompleteness.LIMIT_EXCEEDED
        val next = active.copy(removals = active.removals - id,
            blocked = if (limited) active.blocked + (id to record.page.sourceRevision.take(256)) else active.blocked - id,
            pending = if (record.page.syncStatus == SyncCompleteness.COMPLETE || limited) pending
            else pending + PendingWorkItemDto(id, System.currentTimeMillis(), "partial", sourceIdentity))
        val stateBytes = json.encodeToString(SourceState.serializer(), next).toByteArray()
        val peak = (if (unchanged) 0 else bytes.size).toLong() + stateBytes.size
        if (actualBytes() + peak > byteLimit().coerceAtLeast(0)) throw StorageRefused(StorageFailure.CATEGORY_LIMIT)
        writeGate.write(StorageCategory.NOTES, peak) {
            currentCoroutineContext().ensureActive()
            if (!unchanged) atomicWrite(pageFile(id), bytes)
            // For recovery, publish the new file before clearing its durable tombstone.
            if (next != active) saveState(next)
            cachedIndex = null
            cachedOwners = emptyMap()
        }
    }

    override suspend fun removePage(pageId: String, sourceIdentity: String, sourceEpoch: Long, reason: PageAccessibilityState): Unit = locked {
        val id = canonical(pageId) ?: return@locked
        val active = state()
        if (active.identity != sourceIdentity || sourceEpoch < active.epoch) return@locked
        check(active.removals.size < MAX_IDENTITIES || id in active.removals)
        check(active.epoch < Long.MAX_VALUE)
        val epoch = maxOf(sourceEpoch, active.epoch + 1)
        // This bounded content-free safety write must work even when download admission is refused.
        saveState(active.copy(epoch = epoch, removals = active.removals + (id to reason.name), blocked = active.blocked - id,
            pending = active.pending.filterNot { it.pageId == id }))
        pageFile(id).delete()
    }

    override suspend fun clearSource(sourceIdentity: String, sourceEpoch: Long): Unit = locked {
        val active = state()
        if (active.identity != sourceIdentity || sourceEpoch < active.epoch) return@locked
        check(active.epoch < Long.MAX_VALUE)
        val epoch = maxOf(sourceEpoch, active.epoch + 1)
        saveState(active.copy(epoch = epoch, minimumEpoch = epoch, removals = emptyMap(), containerRemovals = emptyList(), pending = emptyList(), blocked = emptyMap(), lastResourceSourceId = null))
        pagesDir.listFiles()?.forEach { it.delete() }
    }

    override suspend fun invalidateTargets(pages: Map<String, PageAccessibilityState>, containers: Set<String>, sourceIdentity: String, sourceEpoch: Long): Unit = locked { invalidateTargetsLocked(pages, containers, sourceIdentity, sourceEpoch) }

    private suspend fun invalidateTargetsLocked(pages: Map<String, PageAccessibilityState>, containers: Set<String>, sourceIdentity: String, sourceEpoch: Long): Unit {
        val active = state()
        if (active.identity != sourceIdentity || active.epoch != sourceEpoch) return
        val pageStates = pages.filter { canonical(it.key) == it.key && it.value != PageAccessibilityState.ACCESSIBLE }
        val containerIds = containers.filter { canonical(it) == it }.toSet()
        if (pageStates.isEmpty() && containerIds.isEmpty()) return
        val removals = active.removals + pageStates.mapValues { it.value.name }
        val containerRemovals = (active.containerRemovals + containerIds).distinct()
        if (removals == active.removals && containerRemovals.toSet() == active.containerRemovals.toSet()) return
        check(removals.size <= MAX_IDENTITIES && containerRemovals.size <= MAX_IDENTITIES && active.epoch < Long.MAX_VALUE)
        saveState(active.copy(epoch = active.epoch + 1, removals = removals, containerRemovals = containerRemovals, blocked = active.blocked - pageStates.keys,
            pending = active.pending.filterNot { it.pageId in pageStates }))
        pageStates.keys.forEach { pageFile(it).delete() }
        containerIds.forEach { containerFile(it).delete() }
    }

    /** Only this locked admission may renew its own ticket after withdrawing related targets. */
    override suspend fun applyObservation(observation: StructuralObservation, sourceIdentity: String, sourceEpoch: Long): Boolean = locked {
        val active = state()
        if (active.identity != sourceIdentity || active.epoch != sourceEpoch) return@locked false
        val record = observation.record
        val id = canonical(record.page.id) ?: return@locked false
        require(record.sourceIdentity == sourceIdentity && record.page.sourceIdentity == sourceIdentity &&
            observation.targetPages.all { it.sourceIdentity == sourceIdentity } && observation.containers.all { it.sourceIdentity == sourceIdentity })
        val unavailablePages = observation.targetPages.filter { it.accessibilityState != PageAccessibilityState.ACCESSIBLE }
            .associate { it.id to it.accessibilityState }
        val unavailableContainers = observation.containers.filter { it.accessibilityState != PageAccessibilityState.ACCESSIBLE }.map { it.id }.toSet()
        invalidateTargetsLocked(unavailablePages, unavailableContainers, sourceIdentity, sourceEpoch)
        if (record.page.accessibilityState != PageAccessibilityState.ACCESSIBLE || id in unavailablePages) return@locked false
        // The same mutex excludes an external epoch mutation between initial admission and renewal.
        val admittedEpoch = state().epoch
        // The source owns this work item. Optional related metadata must not make its
        // valid snapshot fail or turn a target's intrinsic limit into a source limit.
        writePageLocked(record, sourceIdentity, admittedEpoch)
        for (container in observation.containers.filter { it.accessibilityState == PageAccessibilityState.ACCESSIBLE && it.id !in unavailableContainers }) {
            try { writeContainerLocked(container, sourceIdentity, admittedEpoch) }
            catch (_: StorageRefused) { /* Bounded related metadata remains unresolved. */ }
        }
        for (page in observation.targetPages.filter { it.accessibilityState == PageAccessibilityState.ACCESSIBLE && it.id != id && it.id !in unavailablePages }) {
            if (state().blocked[page.id] == page.sourceRevision) continue
            if (readCurrent(page.id, state()) == null) {
                try { writePageLocked(StructuralPageRecord(page, emptyList(), emptyList(), emptyList(),
                    sourceIdentity = sourceIdentity), sourceIdentity, admittedEpoch) }
                catch (limit: StructuralPageLimitExceeded) {
                    blockPageWorkLocked(page.id, limit.sourceRevision, sourceIdentity, admittedEpoch)
                }
                catch (_: StorageRefused) { /* Preserve the admitted source when related metadata exceeds quota. */ }
            }
        }
        true
    }

    override suspend fun readIndex(): StructuralIndex = locked { index(state()) }
    override suspend fun rebuildIndex(): StructuralIndex = locked {
        cachedIndex = null; cachedOwners = emptyMap(); index(state())
    }
    override suspend fun releaseMemory() = locked { cachedIndex = null; cachedOwners = emptyMap() }

    private suspend fun index(active: SourceState): StructuralIndex {
        cachedIndex?.let { if (it.sourceEpoch == active.epoch && it.sourceIdentity == active.identity) return it }
        val summaries = linkedMapOf<String, PageStructuralSummary>()
        val parents = linkedMapOf<String, MutableList<String>>()
        val incoming = linkedMapOf<String, MutableList<ExplicitRelationship>>()
        val anchors = linkedMapOf<String, StructuralNavigationAnchor>()
        val sectionAnchors = linkedMapOf<String, StructuralNavigationAnchor>()
        val owners = linkedMapOf<String, String>()
        val files = pagesDir.listFiles { file -> file.isFile && canonical(file.nameWithoutExtension) != null && file.extension == "json" }.orEmpty()
        check(files.size <= maxPages) { "Structural page count exceeds its bound" }
        check(files.sumOf { it.length() } <= 64L * 1024 * 1024) { "Structural index input exceeds its bound" }
        for (file in files.sortedBy { it.name }) {
            currentCoroutineContext().ensureActive()
            val id = canonical(file.nameWithoutExtension) ?: continue
            val record = readCurrent(id, active) ?: continue
            val page = record.page
            summaries[id] = PageStructuralSummary(id, page.title, page.parent, page.databaseId, page.dataSourceId,
                active.identity, page.lastEditedAt?.toEpochMilli(), page.accessibilityState, page.sourceRevision, page.syncStatus)
            val parentId = when (val parent = page.parent) {
                is StructuralParent.Page -> parent.pageId
                is StructuralParent.Block -> parent.blockId
                is StructuralParent.Database -> parent.databaseId
                is StructuralParent.DataSource -> parent.dataSourceId
                else -> null
            }
            parentId?.let { parents.getOrPut(canonical(it) ?: it) { mutableListOf() }.add(id) }
            record.blocks.forEach { block ->
                owners[block.id] = id
                anchors[block.id] = StructuralNavigationAnchor(block.id)
            }
            record.sections.forEach { section ->
                anchors[section.navigationAnchor.sourceBlockId] = section.navigationAnchor
                sectionAnchors[section.sectionKey] = section.navigationAnchor
            }
            record.outgoingRelationships.forEach { edge -> targetId(edge.target)?.let { target ->
                incoming.getOrPut(canonical(target) ?: target) { mutableListOf() }.add(edge)
            } }
        }
        active.removals.forEach { (id, reason) ->
            summaries[id] = PageStructuralSummary(id, "", StructuralParent.Unknown("unavailable", null), null, null,
                active.identity, null, PageAccessibilityState.valueOf(reason), "", SyncCompleteness.UNKNOWN)
        }
        incoming.values.forEach { edges -> edges.removeAll { edge ->
            val child = (edge.target as? RelationshipTarget.InternalPage)?.pageId
            val known = child?.let { summaries[it] }
            edge.kind == ExplicitRelationshipKind.PAGE_PARENT_CHILD && known != null &&
                known.accessibilityState == PageAccessibilityState.ACCESSIBLE && known.parent != StructuralParent.Page(edge.sourceId)
        } }
        cachedOwners = owners
        cachedSectionAnchors = sectionAnchors
        return StructuralIndex(sourceIdentity = active.identity, sourceEpoch = active.epoch, pages = summaries,
            parentChildIndex = parents, incomingEdgeMap = incoming, blockAnchorIndex = anchors).also { cachedIndex = it }
    }

    private fun targetId(target: RelationshipTarget): String? = when (target) {
        is RelationshipTarget.InternalPage -> target.pageId
        is RelationshipTarget.InternalBlock -> target.blockId
        is RelationshipTarget.InternalDatabase -> target.databaseId
        is RelationshipTarget.InternalDataSource -> target.dataSourceId
        else -> null
    }

    override suspend fun readPendingWork(): List<PendingStructuralWork> = locked {
        val active = state()
        active.pending.filter { it.sourceIdentity == active.identity }.map { PendingStructuralWork(it.pageId, it.requestedAtEpochMilli, it.reason, it.sourceIdentity) }
    }
    override suspend fun readResourcePosition(): String? = locked { state().lastResourceSourceId }
    override suspend fun recordResourcePosition(sourceId: String, sourceIdentity: String, sourceEpoch: Long): Unit = locked {
        val active = state()
        val id = canonical(sourceId) ?: return@locked
        if (active.identity != sourceIdentity || active.epoch != sourceEpoch || active.lastResourceSourceId == id) return@locked
        val next = active.copy(lastResourceSourceId = id)
        val size = json.encodeToString(SourceState.serializer(), next).toByteArray().size.toLong()
        if (actualBytes() + size > byteLimit()) throw StorageRefused(StorageFailure.CATEGORY_LIMIT)
        writeGate.write(StorageCategory.NOTES, size) { saveState(next) }
    }
    private suspend fun enqueue(active: SourceState, work: PendingStructuralWork) {
        val id = canonical(work.pageId) ?: return
        if (work.sourceIdentity != active.identity || active.identity.isEmpty()) return
        val pending = active.pending.filterNot { it.pageId == id } + PendingWorkItemDto(id, work.requestedAtEpochMilli, work.reason.take(128), active.identity)
        if (pending.size > MAX_PENDING) throw StorageRefused(StorageFailure.CATEGORY_LIMIT)
        val next = active.copy(pending = pending, blocked = active.blocked - id)
        val size = json.encodeToString(SourceState.serializer(), next).toByteArray().size.toLong()
        if (actualBytes() + size > byteLimit()) throw StorageRefused(StorageFailure.CATEGORY_LIMIT)
        writeGate.write(StorageCategory.NOTES, size) { saveState(next) }
    }
    override suspend fun recordPendingWork(work: PendingStructuralWork): Unit = locked { enqueue(state(), work) }
    override suspend fun completePendingWork(pageId: String): Unit = locked {
        val id = canonical(pageId) ?: return@locked
        val active = state()
        val next = active.copy(pending = active.pending.filterNot { it.pageId == id })
        if (next != active) saveState(next)
    }
    override suspend fun blockPageWork(pageId: String, sourceRevision: String, sourceIdentity: String, sourceEpoch: Long): Unit =
        locked { blockPageWorkLocked(pageId, sourceRevision, sourceIdentity, sourceEpoch) }
    override suspend fun blockedSourceRevision(pageId: String): String? = locked { canonical(pageId)?.let { state().blocked[it] } }

    private fun blockPageWorkLocked(pageId: String, sourceRevision: String, sourceIdentity: String, sourceEpoch: Long) {
        val id = canonical(pageId) ?: return
        val active = state()
        if (active.identity != sourceIdentity || active.epoch != sourceEpoch) return
        check(active.blocked.size < MAX_PENDING || id in active.blocked)
        saveState(active.copy(blocked = active.blocked + (id to sourceRevision.take(256)), pending = active.pending.filterNot { it.pageId == id }))
    }
    override suspend fun getPage(pageId: String): StructuralPage? = locked {
        val id = canonical(pageId) ?: return@locked null
        val active = state()
        readCurrent(id, active)?.page ?: active.removals[id]?.let {
            StructuralPage(id, "", StructuralParent.Unknown("unavailable", null), sourceIdentity = active.identity,
                accessibilityState = PageAccessibilityState.valueOf(it), syncStatus = SyncCompleteness.UNKNOWN)
        }
    }
    override suspend fun getAncestors(pageId: String): List<StructuralPage> = locked {
        val active = state()
        index(active)
        val result = mutableListOf<StructuralPage>()
        var id = canonical(pageId) ?: return@locked result
        val visited = mutableSetOf(id)
        repeat(maxPages) {
            var parent = readCurrent(id, active)?.page?.parent ?: readContainer(id, active)?.parent ?: return@locked result
            var containerSteps = 0
            while (parent is StructuralParent.DataSource || parent is StructuralParent.Database) {
                if (++containerSteps > maxPages) return@locked result
                val containerId = when (parent) {
                    is StructuralParent.DataSource -> parent.dataSourceId
                    is StructuralParent.Database -> parent.databaseId
                }
                if (!visited.add(containerId)) return@locked result
                parent = readContainer(containerId, active)?.parent ?: return@locked result
            }
            id = when (parent) {
                is StructuralParent.Page -> canonical(parent.pageId)
                is StructuralParent.Block -> canonical(parent.blockId)?.let { cachedOwners[it] }
                else -> null
            } ?: return@locked result
            if (!visited.add(id)) return@locked result
            result += readCurrent(id, active)?.page ?: return@locked result
        }
        result
    }
    override suspend fun getDirectChildren(pageId: String): List<StructuralPage> = locked {
        val active = state()
        val id = canonical(pageId) ?: return@locked emptyList()
        val view = index(active)
        val known = view.parentChildIndex[id].orEmpty().mapNotNull { readCurrent(it, active)?.page }
        val referenced = readCurrent(id, active)?.outgoingRelationships.orEmpty()
            .filter { it.kind == ExplicitRelationshipKind.PAGE_PARENT_CHILD && canonical(it.sourceId) == id }
            .mapNotNull { (it.target as? RelationshipTarget.InternalPage)?.pageId }
            .filter { it !in active.removals }
            .filter { child -> readCurrent(child, active)?.page?.parent?.let { it == StructuralParent.Page(id) } ?: true }
            .map { child -> readCurrent(child, active)?.page ?: StructuralPage(child, "", StructuralParent.Page(id),
                sourceIdentity = active.identity, accessibilityState = PageAccessibilityState.UNFETCHED) }
        (known + referenced).distinctBy { it.id }
    }
    override suspend fun getBlocks(pageId: String) = readPage(pageId)?.blocks.orEmpty()
    override suspend fun getSections(pageId: String) = readPage(pageId)?.sections.orEmpty()
    override suspend fun getOutgoingRelationships(sourceId: String): List<ExplicitRelationship> = locked {
        val active = state()
        val id = canonical(sourceId) ?: return@locked emptyList()
        val view = index(active)
        if (id in active.removals || id in active.containerRemovals) return@locked emptyList()
        val own = readCurrent(id, active)?.outgoingRelationships
            ?: cachedOwners[id]?.let { owner -> readCurrent(owner, active)?.outgoingRelationships?.filter { canonical(it.sourceId) == id } }.orEmpty()
        val observed = view.incomingEdgeMap.values.flatten().filter { canonical(it.sourceId) == id }
        (own + observed).distinctBy { it.id }.filter { edge ->
            val child = (edge.target as? RelationshipTarget.InternalPage)?.pageId
            val known = child?.let { view.pages[it] }
            edge.kind != ExplicitRelationshipKind.PAGE_PARENT_CHILD || known == null ||
                known.accessibilityState != PageAccessibilityState.ACCESSIBLE || known.parent == StructuralParent.Page(edge.sourceId)
        }.map { edge ->
            val target = targetId(edge.target)
            if (target in active.removals || target in active.containerRemovals)
                edge.copy(resolutionStatus = TargetResolutionStatus.UNRESOLVED_INACCESSIBLE) else edge
        }
    }
    override suspend fun getIncomingRelationships(targetId: String): List<ExplicitRelationship> = locked {
        val active = state()
        val id = canonical(targetId) ?: return@locked emptyList()
        index(active).incomingEdgeMap[id].orEmpty().map { edge ->
            if (id in active.removals || id in active.containerRemovals) edge.copy(resolutionStatus = TargetResolutionStatus.UNRESOLVED_INACCESSIBLE) else edge
        }
    }
    override suspend fun resolveNavigationAnchor(sectionKey: String): StructuralNavigationAnchor? = locked {
        index(state())
        cachedSectionAnchors[sectionKey]
    }
    override suspend fun resolveBlockAnchor(blockId: String): StructuralNavigationAnchor? = locked {
        canonical(blockId)?.let { index(state()).blockAnchorIndex[it] }
    }
    override suspend fun getSyncStatus(pageId: String): SyncCompleteness = locked {
        val id = canonical(pageId) ?: return@locked SyncCompleteness.UNKNOWN
        val active = state()
        val pending = active.pending.firstOrNull { it.pageId == id }
        if (id in active.blocked) SyncCompleteness.LIMIT_EXCEEDED
        else if (pending != null) when (pending.reason) {
            "recoverable" -> SyncCompleteness.FAILED_RECOVERABLE
            "partial" -> SyncCompleteness.PARTIAL_TRAVERSAL_INCOMPLETE
            else -> if (readCurrent(id, active) != null) SyncCompleteness.STALE else SyncCompleteness.UNKNOWN
        }
        else readCurrent(id, active)?.page?.syncStatus ?: SyncCompleteness.UNKNOWN
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        requireNotNull(target.parentFile).mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        private const val MAX_PAGE_BYTES = 4 * 1024 * 1024L
        private const val MAX_STATE_BYTES = 1024 * 1024
        private const val MAX_IDENTITIES = 10000
        private const val MAX_PENDING = 1000
        private const val MAX_BLOCKS = 10000
        private const val MAX_EDGES = 20000
    }
}

@Serializable
private data class SourceState(
    val schemaVersion: Int = 1,
    val identity: String = "",
    val epoch: Long = 0,
    val minimumEpoch: Long = 0,
    val removals: Map<String, String> = emptyMap(),
    val containerRemovals: List<String> = emptyList(),
    val pending: List<PendingWorkItemDto> = emptyList(),
    val lastResourceSourceId: String? = null,
    val blocked: Map<String, String> = emptyMap()
)
