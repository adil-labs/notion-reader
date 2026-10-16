package com.example.notionreader.application

import com.example.notionreader.domain.document.*
import com.example.notionreader.domain.discovery.canonicalSemanticId
import com.example.notionreader.domain.session.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RecentNoteCard(val pageId: String, val title: String, val openedAt: Long)

class RecentNotesController(private val store: RecentNoteHistoryStore, private val cache: DocumentCache,
    private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val mutex = Mutex()
    private var loaded = false
    private var references = emptyList<RecentNoteReference>()
    private var dirty = false
    @Volatile var generation = 0L
        private set
    private val mutable = MutableStateFlow<List<RecentNoteCard>>(emptyList())
    val cards = mutable.asStateFlow()
    private val mutableCount = MutableStateFlow(0)
    val referenceCount = mutableCount.asStateFlow()
    @Volatile var processedInvalidationGeneration = 0L
        private set
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()

    private suspend fun load() {
        if (!loaded) {
            references = try { store.read() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
            loaded = true
        }
    }
    private suspend fun persist(): Boolean {
        if (!dirty) return true
        return try {
            if (references.isEmpty()) store.clear() else store.write(references)
            dirty = false; true
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
    private suspend fun publish() {
        mutableCount.value = references.size
        val eligible = cache.snapshot().associateBy { canonicalSemanticId(it.document.pageId) }
        mutable.value = references.mapNotNull { ref -> eligible[canonicalSemanticId(ref.pageId)]?.document?.let {
            RecentNoteCard(it.pageId, it.title, ref.openedAt)
        } }.take(5)
    }
    /** Must run before the semantic worker acknowledges durable source-removal events. */
    suspend fun refresh(): Boolean = mutex.withLock {
        load()
        val events = cache.invalidations.value
        val removed = events.filter { it.reason == CacheRemovalReason.DELETED || it.reason == CacheRemovalReason.PERMISSION_DENIED }
            .mapNotNull { it.pageId?.let(::canonicalSemanticId) }.toSet()
        val next = if (events.any { it.reason == CacheRemovalReason.RESET }) emptyList() else
            references.filterNot { canonicalSemanticId(it.pageId) in removed }
        if (next != references) { generation++; references = next; dirty = true }
        publish()
        val persisted = persist()
        if (persisted) {
            processedInvalidationGeneration = maxOf(processedInvalidationGeneration, events.maxOfOrNull { it.generation } ?: 0L)
            mutableMessage.value = null
        }
        persisted
    }
    suspend fun recordSuccessfulOpen(pageId: String, expectedGeneration: Long) = mutex.withLock {
        load()
        if (expectedGeneration != generation) return@withLock
        val key = canonicalSemanticId(pageId)
        references = (listOf(RecentNoteReference(pageId, clock())) + references.filterNot {
            canonicalSemanticId(it.pageId) == key
        }).take(20)
        dirty = true
        publish(); persist()
    }
    suspend fun clear() = mutex.withLock {
        generation++; loaded = true; references = emptyList(); dirty = true
        mutable.value = emptyList()
        mutableCount.value = 0
        val persisted = persist()
        mutableMessage.value = if (persisted) null else "Recently opened Notes could not be cleared from storage. Retry clearing history."
        persisted
    }
}
