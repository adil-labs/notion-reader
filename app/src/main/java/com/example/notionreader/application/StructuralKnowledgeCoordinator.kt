package com.example.notionreader.application

import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
import com.example.notionreader.domain.document.CacheRemovalReason
import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.domain.structure.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/** Extends document/cache reconciliation; owns no Activity, model, player or independent workspace catalog. */
class StructuralKnowledgeCoordinator(
    private val store: StructuralKnowledgeStore,
    private val queries: StructuralKnowledgeRepository,
    private val cache: DocumentCache,
    private val sourceIdentity: suspend () -> String,
    private val collectPage: suspend (String, String) -> StructuralPageRecord
) : StructuralKnowledgeRepository {
    private var collectObservation: (suspend (String, String) -> StructuralObservation)? = null
    companion object {
        fun observing(store: StructuralKnowledgeStore, queries: StructuralKnowledgeRepository, cache: DocumentCache,
            sourceIdentity: suspend () -> String, collect: suspend (String, String) -> StructuralObservation) =
            StructuralKnowledgeCoordinator(store, queries, cache, sourceIdentity, { _, _ -> error("Observation collector required") })
                .also { it.collectObservation = collect }
    }
    private val stateMutex = Mutex()
    private val workMutex = Mutex()
    private val mutableWorkRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val workRevision: kotlinx.coroutines.flow.StateFlow<Long> = mutableWorkRevision
    @Volatile var processedInvalidationGeneration: Long = 0L
        private set

    private suspend fun refreshLocked(): StructuralIndex {
        val identity = sourceIdentity()
        require(identity.isNotBlank())
        var current = store.readIndex()
        if (current.sourceIdentity != identity) {
            check(current.sourceEpoch < Long.MAX_VALUE)
            store.initSource(identity, current.sourceEpoch + 1)
        }
        for (event in cache.invalidations.value.sortedBy { it.generation }) {
            if (event.generation <= processedInvalidationGeneration) continue
            current = store.readIndex()
            if (event.pageId == null) store.clearSource(identity, current.sourceEpoch)
            else store.removePage(event.pageId, identity, current.sourceEpoch, when (event.reason) {
                CacheRemovalReason.PERMISSION_DENIED -> PageAccessibilityState.RESTRICTED_PERMISSION
                // The reader's DELETED category also includes Notion's ambiguous object_not_found.
                CacheRemovalReason.DELETED -> PageAccessibilityState.NOT_FOUND_OR_UNSHARED
                else -> PageAccessibilityState.UNFETCHED
            })
            // Other consumers may acknowledge only through this completed generation.
            processedInvalidationGeneration = event.generation
        }
        return store.readIndex()
    }

    suspend fun refreshLifecycle() { stateMutex.withLock { refreshLocked() } }

    suspend fun request(pageId: String, reason: String = "refresh") {
        val id = normalizeNotionId(pageId) ?: return
        stateMutex.withLock {
            val current = refreshLocked()
            store.recordPendingWork(PendingStructuralWork(id, System.currentTimeMillis(), reason, current.sourceIdentity))
            mutableWorkRevision.value++
        }
    }

    /** A queued callback belongs to the source lifecycle in which its HTTP request began. */
    suspend fun requestIfCurrent(pageId: String, identity: String, epoch: Long, reason: String): Boolean = stateMutex.withLock {
        val id = normalizeNotionId(pageId) ?: return@withLock false
        val current = refreshLocked()
        if (current.sourceIdentity != identity || current.sourceEpoch != epoch) return@withLock false
        store.recordPendingWork(PendingStructuralWork(id, System.currentTimeMillis(), reason, identity))
        mutableWorkRevision.value++
        true
    }

    suspend fun requestMetadataIfChanged(page: StructuralPage, identity: String, epoch: Long, signal: Boolean = true): Boolean = stateMutex.withLock {
        val current = refreshLocked()
        if (current.sourceIdentity != identity || current.sourceEpoch != epoch || page.sourceIdentity != identity) return@withLock false
        val existing = store.readPage(page.id)?.page
        if (existing != null && existing.syncStatus == SyncCompleteness.COMPLETE && page.sourceRevision.isNotBlank() &&
            existing.sourceRevision == page.sourceRevision && existing.title == page.title && existing.parent == page.parent &&
            existing.dataSourceId == page.dataSourceId && existing.accessibilityState == page.accessibilityState) return@withLock false
        if (store.readPendingWork().any { it.pageId == page.id }) return@withLock false
        val id = normalizeNotionId(page.id) ?: return@withLock false
        store.recordPendingWork(PendingStructuralWork(id, System.currentTimeMillis(), "metadata-change", identity))
        if (signal) mutableWorkRevision.value++
        true
    }

    suspend fun recordContainerIfCurrent(container: StructuralContainer, identity: String, epoch: Long) = stateMutex.withLock {
        val current = refreshLocked()
        if (current.sourceIdentity == identity && current.sourceEpoch == epoch)
            store.writeContainer(container, identity, epoch)
    }
    suspend fun resourcePosition(): String? = stateMutex.withLock { refreshLocked(); store.readResourcePosition() }
    suspend fun recordResourcePosition(id: String, identity: String, epoch: Long) = stateMutex.withLock {
        val current = refreshLocked()
        if (current.sourceIdentity == identity && current.sourceEpoch == epoch) store.recordResourcePosition(id, identity, epoch)
    }
    suspend fun invalidateAuthorizationIfCurrent(identity: String, epoch: Long) = stateMutex.withLock {
        val current = refreshLocked()
        if (current.sourceIdentity == identity && current.sourceEpoch == epoch) store.clearSource(identity, epoch)
    }

    suspend fun hasPendingWork(): Boolean = stateMutex.withLock {
        refreshLocked()
        store.readPendingWork().isNotEmpty()
    }

    /** Work identities survive cancellation/recreation; API cursors never become durable identities. */
    suspend fun runWindow(maxPages: Int = 1, timeBudgetMillis: Long = 120_000, mayContinue: () -> Boolean = { true }) {
        require(maxPages in 1..20 && timeBudgetMillis > 0)
        withContext(Dispatchers.IO) { workMutex.withLock {
            withTimeoutOrNull(timeBudgetMillis) {
                val work = stateMutex.withLock { refreshLocked(); store.readPendingWork().take(maxPages) }
                for (item in work) {
                    if (!mayContinue()) break
                    val ticket = stateMutex.withLock { refreshLocked() }
                    if (ticket.sourceIdentity != item.sourceIdentity) continue
                    try {
                        val observation = collectObservation?.invoke(item.pageId, ticket.sourceIdentity)
                            ?: StructuralObservation(collectPage(item.pageId, ticket.sourceIdentity))
                        val record = observation.record
                        stateMutex.withLock commit@{
                            val current = refreshLocked()
                            if (current.sourceIdentity != ticket.sourceIdentity || current.sourceEpoch != ticket.sourceEpoch) return@commit
                            require(normalizeNotionId(record.page.id) == item.pageId)
                            require(observation.containers.size <= 100 && observation.targetPages.size <= 100 &&
                                observation.containers.all { it.sourceIdentity == ticket.sourceIdentity && normalizeNotionId(it.id) == it.id } &&
                                observation.targetPages.all { it.sourceIdentity == ticket.sourceIdentity && normalizeNotionId(it.id) == it.id })
                            if (record.page.accessibilityState != PageAccessibilityState.ACCESSIBLE) {
                                val confirmedTrash = record.page.accessibilityState == PageAccessibilityState.CONFIRMED_TRASHED
                                cache.remove(item.pageId, if (confirmedTrash) CacheRemovalReason.DELETED else CacheRemovalReason.PERMISSION_DENIED)
                                val afterRemoval = refreshLocked()
                                store.removePage(item.pageId, afterRemoval.sourceIdentity, afterRemoval.sourceEpoch, record.page.accessibilityState)
                            } else {
                                val cacheGeneration = cache.revision.value
                                val unavailablePages = observation.targetPages.filter { it.accessibilityState != PageAccessibilityState.ACCESSIBLE }
                                    .associate { it.id to it.accessibilityState }
                                val newUnavailablePages = unavailablePages.filter { (id, access) -> queries.getPage(id)?.accessibilityState != access }
                                val unavailableContainers = observation.containers.filter { it.accessibilityState != PageAccessibilityState.ACCESSIBLE }.map { it.id }.toSet()
                                store.invalidateTargets(unavailablePages, unavailableContainers, ticket.sourceIdentity, ticket.sourceEpoch)
                                for ((id, access) in newUnavailablePages) cache.remove(id,
                                    if (access == PageAccessibilityState.RESTRICTED_PERMISSION) CacheRemovalReason.PERMISSION_DENIED else CacheRemovalReason.DELETED)
                                val afterTargets = refreshLocked()
                                if (afterTargets.sourceIdentity == ticket.sourceIdentity && newUnavailablePages.isNotEmpty())
                                    store.invalidateTargets(newUnavailablePages, emptySet(), ticket.sourceIdentity, afterTargets.sourceEpoch)
                                if (afterTargets.sourceEpoch != ticket.sourceEpoch || afterTargets.sourceIdentity != ticket.sourceIdentity) return@commit
                                for (container in observation.containers.filter { it.accessibilityState == PageAccessibilityState.ACCESSIBLE })
                                    store.writeContainer(container, ticket.sourceIdentity, ticket.sourceEpoch)
                                for (target in observation.targetPages.filter { it.accessibilityState == PageAccessibilityState.ACCESSIBLE && it.id != item.pageId }) {
                                    if (store.readPage(target.id) == null) store.writePage(StructuralPageRecord(target,
                                        emptyList(), emptyList(), emptyList(), sourceIdentity = ticket.sourceIdentity), ticket.sourceIdentity, ticket.sourceEpoch)
                                }
                                store.writePage(record, ticket.sourceIdentity, ticket.sourceEpoch)
                                if (cache.revision.value != cacheGeneration) refreshLocked()
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        stateMutex.withLock recovery@{
                            val current = refreshLocked()
                            if (current.sourceIdentity != ticket.sourceIdentity || current.sourceEpoch != ticket.sourceEpoch) return@recovery
                            val category = (failure as? ReaderLoadException)?.category
                            when (category) {
                                ReaderErrorCategory.PAGE_NOT_FOUND, ReaderErrorCategory.PERMISSION_DENIED -> {
                                    cache.remove(item.pageId, if (category == ReaderErrorCategory.PERMISSION_DENIED)
                                        CacheRemovalReason.PERMISSION_DENIED else CacheRemovalReason.DELETED)
                                    refreshLocked()
                                }
                                ReaderErrorCategory.AUTHORIZATION_REQUIRED -> store.clearSource(current.sourceIdentity, current.sourceEpoch)
                                else -> store.recordPendingWork(item.copy(requestedAtEpochMilli = System.currentTimeMillis(), reason = "recoverable"))
                            }
                        }
                    }
                    if (maxPages > 1) delay(350)
                }
            }
        } }
    }

    private suspend fun <T> read(query: suspend (StructuralKnowledgeRepository) -> T): T = stateMutex.withLock {
        var current = refreshLocked()
        var generation = cache.revision.value
        var result = query(queries)
        while (cache.revision.value != generation || sourceIdentity() != current.sourceIdentity) {
            current = refreshLocked()
            generation = cache.revision.value
            result = query(queries)
        }
        result
    }
    override suspend fun getPage(pageId: String) = read { it.getPage(pageId) }
    override suspend fun getContainer(containerId: String) = read { it.getContainer(containerId) }
    override suspend fun getAncestors(pageId: String) = read { it.getAncestors(pageId) }
    override suspend fun getDirectChildren(pageId: String) = read { it.getDirectChildren(pageId) }
    override suspend fun getBlocks(pageId: String) = read { it.getBlocks(pageId) }
    override suspend fun getSections(pageId: String) = read { it.getSections(pageId) }
    override suspend fun getOutgoingRelationships(sourceId: String) = read { it.getOutgoingRelationships(sourceId) }
    override suspend fun getIncomingRelationships(targetId: String) = read { it.getIncomingRelationships(targetId) }
    override suspend fun resolveNavigationAnchor(sectionKey: String) = read { it.resolveNavigationAnchor(sectionKey) }
    override suspend fun resolveBlockAnchor(blockId: String) = read { it.resolveBlockAnchor(blockId) }
    override suspend fun getSyncStatus(pageId: String) = read { it.getSyncStatus(pageId) }
}

