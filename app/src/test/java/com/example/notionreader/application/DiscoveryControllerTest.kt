package com.example.notionreader.application

import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.data.local.FileDiscoveryInterestStore
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.discovery.*
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.*
import org.junit.Test

class DiscoveryControllerTest {
    @Test fun increasingStorageCapacityRecoversWithoutAnExplicitResume() = runBlocking {
        val dir = Files.createTempDirectory("discovery-recover-space").toFile()
        try {
            var prefs = com.example.notionreader.domain.settings.ReaderSettings(discoveryStorageAutomatic = false, discoveryCustomLimitBytes = 1)
            val gate = StorageBudgetCoordinator({ prefs }, { com.example.notionreader.domain.document.StorageSnapshot(32L shl 30, 12L shl 30) }, { 0L }, { 0L })
            val cache = FileDocumentCache(dir.resolve("cache"))
            val store = FileSemanticIndexStore(dir.resolve("index"), writeGate = gate)
            val controller = DiscoveryController(cache, store, FileDiscoveryInterestStore(dir.resolve("interests")), Provider())
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p", "Title", listOf("Text")), null, Instant.EPOCH))
            store.setPaused(false)
            controller.reconcile(emptyList(), true)
            assertNotNull(controller.state.value.indexFailure)
            prefs = prefs.copy(discoveryStorageAutomatic = true)
            controller.reconcile(emptyList(), true)
            assertEquals(1, controller.state.value.indexedCount)
            assertNull(controller.state.value.indexFailure)
            assertNull(controller.state.value.message)
        } finally { dir.deleteRecursively() }
    }
    @Test fun storageWaitingDoesNotPersistAnExplicitUserPause() = runBlocking {
        val dir = Files.createTempDirectory("discovery-space").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val store = FileSemanticIndexStore(dir.resolve("index"), maxBytes = 13)
            val controller = DiscoveryController(cache, store, FileDiscoveryInterestStore(dir.resolve("interests")), Provider())
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p", "Title", listOf("Text")), null, Instant.EPOCH))
            store.setPaused(false)
            controller.reconcile(emptyList(), true)
            assertFalse("Capacity waiting must be retried without overriding user pause", store.isPaused())
            assertNotNull(controller.state.value.indexFailure)
        } finally { dir.deleteRecursively() }
    }
    @Test fun aCancelledInterestRefreshCannotLeaveMatchingNotesPermanentlyEmpty() = runBlocking {
        val dir = Files.createTempDirectory("interest-cancelled-refresh").toFile()
        val release = CompletableDeferred<Unit>()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p", "Forest", listOf("forest ecology")), null, Instant.EPOCH))
            val backing = FileSemanticIndexStore(dir.resolve("index"))
            val entered = CompletableDeferred<Unit>()
            var holdReads = false
            val index = object : SemanticIndexStore by backing {
                override suspend fun read(pageId: String): SemanticRecord? {
                    if (holdReads) { entered.complete(Unit); release.await() }
                    return backing.read(pageId)
                }
            }
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), Provider())
            index.setPaused(false)
            controller.reconcile(emptyList(), true)
            controller.saveInterest(null, "forest")
            val id = controller.state.value.interests.single().id
            controller.loadInterestMatches(id)
            assertEquals(listOf("p"), controller.state.value.interestMatch.matches.map { it.pageId })
            holdReads = true
            val refresh = launch { controller.loadInterestMatches(id, force = true) }
            try {
                withTimeout(5_000) { entered.await() }
                refresh.cancelAndJoin()
            } finally { release.complete(Unit); refresh.cancelAndJoin() }
            holdReads = false
            controller.loadInterestMatches(id)
            assertEquals("A cancelled refresh cannot leave a cached success key attached to empty results",
                listOf("p"), controller.state.value.interestMatch.matches.map { it.pageId })
        } finally { release.complete(Unit); dir.deleteRecursively() }
    }

    @Test fun aFailedCacheReadDoesNotRemainInTheCheckingState() = runBlocking {
        val dir = Files.createTempDirectory("discovery-cache-read-failure").toFile()
        try {
            val backing = FileDocumentCache(dir.resolve("cache"))
            val cache = object : com.example.notionreader.domain.document.DocumentCache by backing {
                override suspend fun snapshot(): List<CachedDocument> = error("Cache read failed")
            }
            val controller = DiscoveryController(cache, FileSemanticIndexStore(dir.resolve("index")),
                FileDiscoveryInterestStore(dir.resolve("interests")), Provider())
            controller.reconcile(emptyList(), false)
            assertFalse("A failed read must leave the pending state", controller.state.value.cacheStatus == DiscoveryCacheStatus.Checking)
            assertNotNull(controller.state.value.message)
        } finally { dir.deleteRecursively() }
    }

    @Test fun anOlderRelatedFailureCannotReplaceANewerRelatedRequest() = runBlocking {
        val dir = Files.createTempDirectory("related-stale-failure").toFile()
        val release = CompletableDeferred<Unit>()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("old", "Old", listOf("content")), null, Instant.EPOCH))
            val entered = CompletableDeferred<Unit>()
            val backing = FileSemanticIndexStore(dir.resolve("index"))
            val index = object : SemanticIndexStore by backing {
                override suspend fun read(pageId: String): SemanticRecord? {
                    entered.complete(Unit); release.await(); error("Delayed old read failed")
                }
            }
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), Provider())
            val old = async { controller.relatedNotes("old") }
            try {
                withTimeout(5_000) { entered.await() }
                controller.relatedNotes("newest")
                val newest = controller.state.value
                release.complete(Unit); old.await()
                assertEquals("A stale failure must not overwrite newer state", newest, controller.state.value)
            } finally { release.complete(Unit); old.await() }
        } finally { release.complete(Unit); dir.deleteRecursively() }
    }

    @Test fun clearingDiscoveryRejectsARelatedRequestWaitingForDispatch() = runBlocking {
        val dir = Files.createTempDirectory("related-reset-dispatch").toFile()
        try {
            val queued = java.util.concurrent.ConcurrentLinkedDeque<Runnable>()
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
            }
            val controller = DiscoveryController(FileDocumentCache(dir.resolve("cache")),
                FileSemanticIndexStore(dir.resolve("index")), FileDiscoveryInterestStore(dir.resolve("interests")),
                Provider(), workDispatcher = dispatcher)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { controller.relatedNotes("old") }
            controller.clearIndex()
            val cleared = controller.state.value
            withTimeout(5_000) { while (!pending.isCompleted) { queued.pollFirst()?.run(); yield() } }
            pending.await()
            assertEquals("A queued request must not republish after clearing", cleared, controller.state.value)
        } finally { dir.deleteRecursively() }
    }

    @Test fun theNewestRelatedRequestKeepsOwnershipWhenDispatchRunsInReverseOrder() = runBlocking {
        val dir = Files.createTempDirectory("related-reversed-dispatch").toFile()
        try {
            val queued = java.util.concurrent.ConcurrentLinkedDeque<Runnable>()
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
            }
            val controller = DiscoveryController(FileDocumentCache(dir.resolve("cache")),
                FileSemanticIndexStore(dir.resolve("index")), FileDiscoveryInterestStore(dir.resolve("interests")),
                Provider(), workDispatcher = dispatcher)
            val old = async(start = CoroutineStart.UNDISPATCHED) { controller.relatedNotes("old") }
            val newest = async(start = CoroutineStart.UNDISPATCHED) { controller.relatedNotes("newest") }
            withTimeout(5_000) { while (!old.isCompleted || !newest.isCompleted) { queued.pollLast()?.run(); yield() } }
            old.await(); newest.await()
            assertEquals("newest", controller.state.value.relatedPageId)
        } finally { dir.deleteRecursively() }
    }

    @Test fun searchAndSeedRetrievalRespectTheInjectedDispatcher() = runBlocking {
        val dir = Files.createTempDirectory("discovery-retrieval-dispatcher").toFile()
        try {
            val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    kotlinx.coroutines.Dispatchers.Default.dispatch(context, block)
                }
            }
            val cache = FileDocumentCache(dir.resolve("cache"))
            val backing = FileSemanticIndexStore(dir.resolve("index"))
            val observed = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
            val index = object : SemanticIndexStore by backing {
                override suspend fun read(pageId: String): SemanticRecord? {
                    observed += kotlinx.coroutines.currentCoroutineContext()[kotlin.coroutines.ContinuationInterceptor] === dispatcher
                    return backing.read(pageId)
                }
            }
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p", "Forest", listOf("forest ecology")), null, Instant.EPOCH))
            index.setPaused(false)
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")),
                Provider(), workDispatcher = dispatcher)
            controller.reconcile(emptyList(), true)
            observed.clear()
            controller.search("forest")
            assertTrue("Search must exercise an indexed result", controller.state.value.results.isNotEmpty())
            controller.relatedNotes("p")
            assertTrue("Both ranking and seed work must read indexed evidence", observed.size >= 2)
            assertTrue("Nested retrieval must retain the injected dispatcher", observed.all { it })
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancellationBeforeBackgroundDispatchDoesNotLeaveSearchBusy() = runBlocking {
        val dir = Files.createTempDirectory("discovery-search-cancel").toFile()
        try {
            val queued = java.util.concurrent.ConcurrentLinkedDeque<Runnable>()
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
            }
            val controller = DiscoveryController(FileDocumentCache(dir.resolve("cache")),
                FileSemanticIndexStore(dir.resolve("index")), FileDiscoveryInterestStore(dir.resolve("interests")),
                Provider(), workDispatcher = dispatcher)
            val search = async(start = CoroutineStart.UNDISPATCHED) { controller.search("forest") }
            assertTrue(controller.state.value.searching)
            search.cancel()
            withTimeout(5_000) { while (!search.isCompleted) { queued.pollFirst()?.run(); yield() } }
            assertFalse(controller.state.value.searching)
        } finally { dir.deleteRecursively() }
    }
    @Test fun theNewestSearchKeepsOwnershipWhenBackgroundDispatchRunsInReverseOrder() = runBlocking {
        val dir = Files.createTempDirectory("discovery-search-order").toFile()
        try {
            val queued = java.util.concurrent.ConcurrentLinkedDeque<Runnable>()
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
            }
            val controller = DiscoveryController(FileDocumentCache(dir.resolve("cache")),
                FileSemanticIndexStore(dir.resolve("index")), FileDiscoveryInterestStore(dir.resolve("interests")),
                Provider(), workDispatcher = dispatcher)
            val old = async(start = CoroutineStart.UNDISPATCHED) { controller.search("old") }
            val newest = async(start = CoroutineStart.UNDISPATCHED) { controller.search("newest") }
            withTimeout(5_000) {
                while (!old.isCompleted || !newest.isCompleted) { queued.pollLast()?.run(); yield() }
            }
            old.await(); newest.await()
            assertEquals("newest", controller.state.value.query)
            assertFalse(controller.state.value.searching)
        } finally { dir.deleteRecursively() }
    }

    @Test fun reconciliationPreservesAConcurrentSearchAndPreview() = runBlocking {
        val dir = Files.createTempDirectory("discovery-reconcile-query").toFile()
        val release = CompletableDeferred<Unit>()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val backing = FileSemanticIndexStore(dir.resolve("index"))
            val held = CompletableDeferred<Unit>()
            val armed = java.util.concurrent.atomic.AtomicBoolean(false)
            val index = object : SemanticIndexStore by backing {
                override suspend fun read(pageId: String): SemanticRecord? {
                    if (pageId == "a" && armed.compareAndSet(true, false)) { held.complete(Unit); release.await() }
                    return backing.read(pageId)
                }
            }
            val entries = listOf("a", "b").map { id ->
                cache.put(CachedDocument(ReaderDocument.fromChunkTexts(id, id, listOf("forest roots")), null, Instant.EPOCH))
                ReaderIndexEntry(id, id, area = id)
            }
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), Provider())
            index.setPaused(false); controller.reconcile(entries, true)
            controller.search("forest", BrowseQuery(selectedArea = "a"))
            controller.preparePreview(controller.state.value.results)
            armed.set(true)
            val reconciliation = async { controller.reconcile(entries, false) }
            withTimeout(5_000) { held.await() }
            controller.search("forest", BrowseQuery(selectedArea = "b"))
            assertEquals(listOf("b"), controller.state.value.results.map { it.pageId })
            controller.preparePreview(controller.state.value.results)
            release.complete(Unit); reconciliation.await()
            assertEquals("Reconciliation cannot overwrite newer results", listOf("b"), controller.state.value.results.map { it.pageId })
            assertEquals(listOf("b"), controller.state.value.preview.map { it.pageId })
            assertTrue(controller.state.value.unavailablePreviewIds.isEmpty())
            assertEquals("forest roots", controller.state.value.preview.single().passage.excerpt)
        } finally { release.complete(Unit); dir.deleteRecursively() }
    }
    @Test fun replacingCachedTextWithdrawsOldSearchAndPreviewEvidence() = runBlocking {
        val dir = Files.createTempDirectory("discovery-edited-evidence").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), Provider())
            val original = ReaderDocument.fromChunkTexts("p", "Title", listOf("forest one"))
            cache.put(CachedDocument(original, null, Instant.EPOCH)); index.setPaused(false)
            controller.reconcile(emptyList(), true); controller.search("forest")
            val match = controller.state.value.results.single()
            controller.preparePreview(listOf(match))
            val edited = original.copy(sections = original.sections.map { section ->
                section.copy(chunks = section.chunks.map { it.copy(text = "desert two") })
            })
            cache.put(CachedDocument(edited, null, Instant.EPOCH))
            controller.reconcile(emptyList(), false)
            assertTrue("Old excerpts cannot remain search evidence for an edited cached Note", controller.state.value.results.isEmpty())
            assertEquals(listOf("p"), controller.state.value.preview.map { it.pageId })
            assertEquals("", controller.state.value.preview.single().passage.excerpt)
            assertEquals(setOf("p"), controller.state.value.unavailablePreviewIds)
        } finally { dir.deleteRecursively() }
    }
    @Test fun searchInferenceDoesNotRunOnTheCallingUiThread() = runBlocking {
        val dir = Files.createTempDirectory("discovery-thread").toFile()
        try {
            val caller = Thread.currentThread()
            var inferenceThread: Thread? = null
            val provider = Provider().apply { onEmbed = { inferenceThread = Thread.currentThread() } }
            val controller = DiscoveryController(FileDocumentCache(dir.resolve("cache")),
                FileSemanticIndexStore(dir.resolve("index")), FileDiscoveryInterestStore(dir.resolve("interests")), provider)
            controller.search("forest")
            assertNotNull(inferenceThread)
            assertNotSame("Blocking inference must be main-safe even when its provider does not dispatch", caller, inferenceThread)
        } finally { dir.deleteRecursively() }
    }
    @Test fun searchTimingIncludesEmbeddingAndNeverContainsTheQuery() = runBlocking {
        val dir = Files.createTempDirectory("search-timing").toFile()
        try {
            val samples = mutableListOf<Map<String, Long>>()
            val provider = Provider().apply { onEmbed = { Thread.sleep(25) } }
            val controller = DiscoveryController(FileDocumentCache(dir.resolve("cache")),
                FileSemanticIndexStore(dir.resolve("index")), FileDiscoveryInterestStore(dir.resolve("interests")),
                provider, queryTimings = { samples += it })
            controller.search("private query text")
            val timing = samples.last()
            assertTrue("Whole search timing must include inference", timing.getValue("searchTotalNs") >= timing.getValue("embeddingNs"))
            assertTrue(timing.getValue("embeddingNs") >= 20_000_000L)
            assertEquals(1L, timing.getValue("completed"))
            assertEquals(setOf("searchTotalNs", "waitNs", "embeddingNs", "retrievalNs", "completed"), timing.keys)
        } finally { dir.deleteRecursively() }
    }
    @Test fun aFailingTimingObserverCannotTurnSuccessfulSearchIntoAnError() = runBlocking {
        val dir = Files.createTempDirectory("search-observer").toFile()
        try {
            val controller = DiscoveryController(FileDocumentCache(dir.resolve("cache")),
                FileSemanticIndexStore(dir.resolve("index")), FileDiscoveryInterestStore(dir.resolve("interests")),
                Provider(), queryTimings = { error("diagnostic failure") })
            controller.search("query")
            assertNull(controller.state.value.message)
            assertFalse(controller.state.value.searching)
        } finally { dir.deleteRecursively() }
    }
    @Test fun editingOneParagraphReusesUnchangedPassageEmbeddings() = runBlocking {
        val dir = Files.createTempDirectory("discovery-incremental").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val store = FileSemanticIndexStore(dir.resolve("index"))
            val provider = Provider()
            val controller = DiscoveryController(cache, store, FileDiscoveryInterestStore(dir.resolve("interests")), provider)
            val original = ReaderDocument.fromChunkTexts("p", "Title", listOf("first", "second"))
            cache.put(CachedDocument(original, null, Instant.EPOCH)); store.setPaused(false)
            controller.reconcile(emptyList(), true)
            assertEquals(2, provider.calls)
            val edited = original.copy(sections = original.sections.map { section -> section.copy(chunks = section.chunks.mapIndexed { i, c -> if (i == 0) c.copy(text = "edited") else c }) })
            cache.put(CachedDocument(edited, null, Instant.EPOCH))
            controller.reconcile(emptyList(), true)
            assertEquals(3, provider.calls)
        } finally { dir.deleteRecursively() }
    }

    @Test fun olderRecommendationsAreRankedAmongEligibleOlderNotes() = runBlocking {
        val dir = Files.createTempDirectory("discovery-older").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val store = FileSemanticIndexStore(dir.resolve("index"))
            val provider = Provider()
            val controller = DiscoveryController(cache, store, FileDiscoveryInterestStore(dir.resolve("interests")), provider,
                clock = { Instant.parse("2026-10-15T00:00:00Z") })
            val entries = (0..5).map { i ->
                val id = if (i == 5) "zOld" else "aRecent$i"
                cache.put(CachedDocument(ReaderDocument.fromChunkTexts(id, id, listOf("concept")), null, Instant.EPOCH))
                ReaderIndexEntry(id, id, Instant.parse(if (i == 5) "2020-01-01T00:00:00Z" else "2026-10-14T00:00:00Z"))
            }
            store.setPaused(false); controller.reconcile(entries, true)
            controller.saveInterest(null, "concept"); controller.refreshRecommendations()
            assertEquals(listOf("zold"), controller.state.value.older.map { it.pageId })
        } finally { dir.deleteRecursively() }
    }

    @Test fun identicalInputsAcrossNotesReuseInferenceButKeepCurrentIndependentEvidence() = runBlocking {
        val dir = Files.createTempDirectory("exact-embedding-reuse").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val provider = Provider()
            val entries = (1..3).map { n ->
                val id = "p$n"
                cache.put(CachedDocument(ReaderDocument.fromChunkTexts(id, "Shared title", listOf("Exact common body evidence")), null, Instant.EPOCH))
                ReaderIndexEntry(id, "Shared title", area = "area$n")
            }
            index.setPaused(false)
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), provider)
            controller.reconcile(entries, true)
            assertEquals(1, provider.calls)
            val records = index.pageIds().map { index.read(it)!! }
            assertEquals(3, records.size)
            assertEquals(3, records.flatMap { it.passages }.map { it.passage.id }.distinct().size)
            assertEquals(setOf("area1", "area2", "area3"), records.map { it.metadata.area }.toSet())
            records.forEach { record -> assertTrue(record.passages.all { it.passage.pageId == record.pageId }) }
        } finally { dir.deleteRecursively() }
    }

    private class Provider(private val version: String = "test") : EmbeddingProvider {
        var calls = 0
        var onEmbed: (suspend () -> Unit)? = null
        override val identity = EmbeddingIdentity(version, 2)
        override val tokenizer = object : SemanticTokenizer {
            override fun tokens(text: String) = Regex("\\S+").findAll(text).map { SemanticToken(1, it.range.first, it.range.last + 1) }.toList()
        }
        override suspend fun embed(text: String): List<Float> { calls++; onEmbed?.invoke(); return listOf(1f, 0f) }
    }

    @Test fun updatingANoteWhileWorkIsDeferredRetainsItsUnchangedPassageEmbeddings() = runBlocking {
        val dir = Files.createTempDirectory("incremental-deferred-update").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val progress = FileSemanticIndexStore(dir.resolve("progress"))
            val interests = FileDiscoveryInterestStore(dir.resolve("interests"))
            fun note(text: String) = CachedDocument(ReaderDocument.fromChunkTexts("p", "Title",
                listOf("Unchanged forest ecology evidence.", text)), null, Instant.EPOCH)
            val provider = Provider()
            cache.put(note("Original river habitat evidence.")); index.setPaused(false)
            DiscoveryController(cache, index, interests, provider, passageProgressStore = progress).reconcile(emptyList(), true)
            val originalCalls = provider.calls
            assertEquals(2, originalCalls)
            cache.put(note("Updated river habitat evidence."))
            DiscoveryController(cache, index, interests, provider, passageProgressStore = progress).reconcile(emptyList(), false)
            assertNull("Stale source evidence must be withdrawn while work is deferred", index.read("p"))
            DiscoveryController(cache, index, interests, provider, passageProgressStore = progress).reconcile(emptyList(), true)
            assertEquals("Only the changed passage requires inference after process recreation", originalCalls + 1, provider.calls)
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancellingAnUpdatedNoteBeforeItsFirstNewEmbeddingPreservesReusablePassages() = runBlocking {
        val dir = Files.createTempDirectory("incremental-cancelled-update").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val progress = FileSemanticIndexStore(dir.resolve("progress"))
            val interests = FileDiscoveryInterestStore(dir.resolve("interests"))
            fun note(text: String) = CachedDocument(ReaderDocument.fromChunkTexts("p", "Title",
                listOf("Unchanged forest ecology evidence.", text)), null, Instant.EPOCH)
            cache.put(note("Original river habitat evidence.")); index.setPaused(false)
            DiscoveryController(cache, index, interests, Provider(), passageProgressStore = progress).reconcile(emptyList(), true)
            cache.put(note("Updated river habitat evidence."))
            val interrupted = Provider().apply { onEmbed = { throw kotlinx.coroutines.CancellationException("interrupted") } }
            try {
                DiscoveryController(cache, index, interests, interrupted, passageProgressStore = progress).reconcile(emptyList(), true)
                fail("Cancellation must propagate")
            } catch (_: kotlinx.coroutines.CancellationException) { }
            val resumed = Provider()
            DiscoveryController(cache, index, interests, resumed, passageProgressStore = progress).reconcile(emptyList(), true)
            assertEquals("Unchanged passages survive cancellation before new inference completes", 1, resumed.calls)
        } finally { dir.deleteRecursively() }
    }

    @Test fun savingANewPassageCannotDiscardReusablePassagesLaterInTheNote() = runBlocking {
        val dir = Files.createTempDirectory("incremental-checkpoint-replacement").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val backing = FileSemanticIndexStore(dir.resolve("progress"))
            val interests = FileDiscoveryInterestStore(dir.resolve("interests"))
            fun note(middle: String) = CachedDocument(ReaderDocument.fromChunkTexts("p", "Title", listOf(
                "Unchanged opening forest evidence.", middle, "Unchanged closing river evidence.")), null, Instant.EPOCH)
            cache.put(note("Original middle evidence.")); index.setPaused(false)
            DiscoveryController(cache, index, interests, Provider(), passageProgressStore = backing).reconcile(emptyList(), true)
            cache.put(note("Changed middle evidence."))
            val progress = object : SemanticIndexStore by backing {
                override suspend fun write(record: SemanticRecord): Boolean {
                    val saved = backing.write(record)
                    if (record.passages.any { it.passage.input.contains("Changed middle") })
                        throw kotlinx.coroutines.CancellationException("interrupted after destructive checkpoint replacement")
                    return saved
                }
            }
            try {
                DiscoveryController(cache, index, interests, Provider(), passageProgressStore = progress).reconcile(emptyList(), true)
                fail("Cancellation must propagate")
            } catch (_: kotlinx.coroutines.CancellationException) { }
            val resumed = Provider()
            DiscoveryController(cache, index, interests, resumed, passageProgressStore = backing).reconcile(emptyList(), true)
            assertEquals("The replaced checkpoint must retain both earlier and later reusable evidence", 0, resumed.calls)
        } finally { dir.deleteRecursively() }
    }

    @Test fun unchangedContentReusesEmbeddingsAndMetadataOnlyChangesStillFilter() = runBlocking {
        val dir = Files.createTempDirectory("discovery-controller").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val store = FileSemanticIndexStore(dir.resolve("index"))
            val provider = Provider()
            val controller = DiscoveryController(cache, store, FileDiscoveryInterestStore(dir.resolve("interests")), provider)
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p", "Title", listOf("content")), null, Instant.EPOCH))
            store.setPaused(false)
            controller.reconcile(listOf(ReaderIndexEntry("p", "Title", area = "Old")), true)
            assertEquals(1, provider.calls)
            controller.reconcile(listOf(ReaderIndexEntry("p", "Title", area = "New")), true)
            assertEquals(1, provider.calls)
            controller.search("concept", BrowseQuery(selectedArea = "New"))
            assertEquals(listOf("p"), controller.state.value.results.map { it.pageId })
            cache.clear()
            controller.reconcile(emptyList(), false)
            assertTrue(controller.state.value.results.isEmpty())
            assertTrue(store.pageIds().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun evictionDuringInferenceCannotCommitOrPublishTheRemovedDocument() = runBlocking {
        val dir = Files.createTempDirectory("discovery-eviction").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val store = FileSemanticIndexStore(dir.resolve("index"))
            val provider = Provider().apply { onEmbed = { cache.clear() } }
            val controller = DiscoveryController(cache, store, FileDiscoveryInterestStore(dir.resolve("interests")), provider)
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p", "Title", listOf("secret")), null, Instant.EPOCH))
            store.setPaused(false)
            controller.reconcile(emptyList(), true)
            assertTrue(store.pageIds().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun clearingTheIndexCannotBeUndoneByAnInFlightEmbedding() = runBlocking {
        val dir = Files.createTempDirectory("discovery-reset").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val store = FileSemanticIndexStore(dir.resolve("index"))
            lateinit var controller: DiscoveryController
            val provider = Provider().apply { onEmbed = { controller.clearIndex() } }
            controller = DiscoveryController(cache, store, FileDiscoveryInterestStore(dir.resolve("interests")), provider)
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p", "Title", listOf("secret")), null, Instant.EPOCH))
            store.setPaused(false)
            controller.reconcile(emptyList(), true)
            assertTrue(store.pageIds().isEmpty())
            assertTrue(store.isPaused())
            assertTrue(controller.state.value.paused)
        } finally { dir.deleteRecursively() }
    }

    @Test fun pausingBulkWorkDoesNotGenerateEmbeddingsAndExplicitInterestsRoundTrip() = runBlocking {
        val dir = Files.createTempDirectory("discovery-pause").toFile()
        try {
            val provider = Provider()
            val controller = DiscoveryController(FileDocumentCache(dir.resolve("cache")), FileSemanticIndexStore(dir.resolve("index")), FileDiscoveryInterestStore(dir.resolve("interests")), provider)
            controller.reconcile(emptyList(), false)
            assertEquals(0, provider.calls)
            controller.saveInterest(null, "  motivation  ")
            assertEquals("motivation", controller.state.value.interests.single().text)
            controller.removeInterest(controller.state.value.interests.single().id)
            assertTrue(controller.state.value.interests.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun interruptedIndexingResumesFromCompletedDocumentsWithoutReembeddingThem() = runBlocking {
        val dir = Files.createTempDirectory("discovery-resume").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val interests = FileDiscoveryInterestStore(dir.resolve("interests"))
            listOf("a", "b").forEach { id -> cache.put(CachedDocument(ReaderDocument.fromChunkTexts(id, id, listOf("text")), null, Instant.EPOCH)) }
            index.setPaused(false)
            val interrupted = Provider().apply { onEmbed = { if (calls == 2) throw kotlinx.coroutines.CancellationException("interrupted") } }
            try {
                DiscoveryController(cache, index, interests, interrupted).reconcile(emptyList(), true)
                fail("Indexing must propagate cancellation")
            } catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals(1, index.pageIds().size)
            val resumed = Provider()
            DiscoveryController(cache, FileSemanticIndexStore(dir.resolve("index")), interests, resumed).reconcile(emptyList(), true)
            assertEquals(1, resumed.calls)
            assertEquals(2, index.pageIds().size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun changingTheModelRebuildsRecordsInsteadOfReusingIncompatibleVectors() = runBlocking {
        val dir = Files.createTempDirectory("discovery-model-change").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val interests = FileDiscoveryInterestStore(dir.resolve("interests"))
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p", "Title", listOf("text")), null, Instant.EPOCH))
            index.setPaused(false)
            DiscoveryController(cache, index, interests, Provider("old")).reconcile(emptyList(), true)
            val replacement = Provider("replacement")
            DiscoveryController(cache, index, interests, replacement).reconcile(emptyList(), true)
            assertEquals(1, replacement.calls)
            assertEquals(replacement.identity, index.read("p")!!.identity)
            assertEquals(1, cache.count())
        } finally { dir.deleteRecursively() }
    }
}
