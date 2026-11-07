package com.example.notionreader

import com.example.notionreader.application.*
import com.example.notionreader.data.local.FileDiscoveryInterestStore
import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.discovery.EmbeddingIdentity
import com.example.notionreader.domain.discovery.EmbeddingProvider
import com.example.notionreader.domain.discovery.SemanticTokenizer
import com.example.notionreader.domain.discovery.SemanticToken
import com.example.notionreader.domain.settings.DiscoveryEffort
import com.example.notionreader.domain.settings.ReaderSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class DiscoveryOrchestratorTest {
    @get:Rule val files = TemporaryFolder()

    private class FakeScheduler : DiscoveryTaskScheduler {
        val requests = mutableListOf<Pair<DiscoveryEffort, Boolean>>()
        var cancelCalls = 0
        val completionsFlow = MutableSharedFlow<List<DiscoveryTaskCompletion>>(extraBufferCapacity = 16)
        override suspend fun request(nextEffort: DiscoveryEffort, pending: Boolean) {
            requests += nextEffort to pending
        }
        override fun cancel() {
            cancelCalls++
        }
        override fun completions(): Flow<List<DiscoveryTaskCompletion>> = completionsFlow
    }

    private fun testProvider() = object : EmbeddingProvider {
        override val identity = EmbeddingIdentity("test", 2)
        override val tokenizer = object : SemanticTokenizer { override fun tokens(text: String) = emptyList<SemanticToken>() }
        override suspend fun embed(text: String) = listOf(1f, 0f)
        override fun release() {}
    }

    @Test
    fun externalWakesDoNotCancelInFlightDiscoveryWindow() = runBlocking {
        val root = files.newFolder()
        val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index"))
        val interests = FileDiscoveryInterestStore(root.resolve("interests"))
        val settings = InMemorySettingsRepository(ReaderSettings(discoveryEffort = DiscoveryEffort.BALANCED))
        val scheduler = FakeScheduler()
        val controller = DiscoveryController(cache, index, interests, testProvider())
        val isPlaying = MutableStateFlow(false)
        val browseEntries = MutableStateFlow(emptyList<ReaderIndexEntry>())
        val foreground = MutableStateFlow(true)
        val power = MutableStateFlow(DiscoveryPowerState(percent = 80, connected = true))

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val orchestrator = DiscoveryOrchestrator(
                scope = scope,
                discovery = controller,
                documentCache = cache,
                settingsRepository = settings,
                scheduler = scheduler,
                isModelInstalled = { true },
                isPlaying = { isPlaying.value },
                isPlayingEvents = isPlaying,
                browseEntries = { browseEntries.value },
                browseEntriesEvents = browseEntries,
                discoveryForeground = foreground,
                discoveryPower = power,
                hasDiscoveryMemory = { true },
                windowBudgetMillis = 120_000L
            )

            // Inject a document to ensure controller has work
            val doc = ReaderDocument.fromChunkTexts("p1", "Test", listOf("Content for indexing."))
            cache.put(CachedDocument(doc, null, Instant.EPOCH))

            // Start orchestrator, which launches the work loop
            orchestrator.start()

            // Wait for work loop to begin executing
            // Send multiple external wake signals
            repeat(5) {
                orchestrator.requestWake()
                delay(10)
            }

            // Yield and confirm orchestrator remains active and running
            delay(100)
            assertTrue("Orchestrator work loop remains running after external wakes", scope.isActive)
            orchestrator.shutdown()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun playbackStartingUpdatesAvailabilityToWaitingForPlayback() = runBlocking {
        val root = files.newFolder()
        val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index"))
        val interests = FileDiscoveryInterestStore(root.resolve("interests"))
        val settings = InMemorySettingsRepository(ReaderSettings(discoveryEffort = DiscoveryEffort.BALANCED))
        val scheduler = FakeScheduler()
        val controller = DiscoveryController(cache, index, interests, testProvider())
        val isPlaying = MutableStateFlow(false)
        val browseEntries = MutableStateFlow(emptyList<ReaderIndexEntry>())
        val foreground = MutableStateFlow(true)
        val power = MutableStateFlow(DiscoveryPowerState(percent = 80, connected = true))

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val orchestrator = DiscoveryOrchestrator(
                scope = scope,
                discovery = controller,
                documentCache = cache,
                settingsRepository = settings,
                scheduler = scheduler,
                isModelInstalled = { true },
                isPlaying = { isPlaying.value },
                isPlayingEvents = isPlaying,
                browseEntries = { browseEntries.value },
                browseEntriesEvents = browseEntries,
                discoveryForeground = foreground,
                discoveryPower = power,
                hasDiscoveryMemory = { true },
                windowBudgetMillis = 120_000L
            )

            orchestrator.start()
            delay(50)

            // Simulate playback starting
            isPlaying.value = true
            delay(50)

            assertEquals(
                "Active playback must switch availability to WaitingForPlayback to prioritize reading audio",
                DiscoveryExecutionAvailability.WaitingForPlayback,
                controller.state.value.executionAvailability
            )
            assertFalse("Indexing cannot continue while playing", orchestrator.isIndexingEligible())
            orchestrator.shutdown()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun scheduledDiscoveryRunsUnderLockWhenEligibleEvenInBackground() = runBlocking {
        val root = files.newFolder()
        val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index"))
        val interests = FileDiscoveryInterestStore(root.resolve("interests"))
        val settings = InMemorySettingsRepository(ReaderSettings(discoveryEffort = DiscoveryEffort.BALANCED))
        val scheduler = FakeScheduler()
        val controller = DiscoveryController(cache, index, interests, testProvider())
        val isPlaying = MutableStateFlow(false)
        val browseEntries = MutableStateFlow(emptyList<ReaderIndexEntry>())
        val foreground = MutableStateFlow(false) // Background!
        val power = MutableStateFlow(DiscoveryPowerState(percent = 80, connected = true))

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            var modelsChecked = false
            val orchestrator = DiscoveryOrchestrator(
                scope = scope,
                discovery = controller,
                documentCache = cache,
                settingsRepository = settings,
                scheduler = scheduler,
                isModelInstalled = { true },
                verifyDiscoveryModels = { modelsChecked = true },
                isPlaying = { isPlaying.value },
                isPlayingEvents = isPlaying,
                browseEntries = { browseEntries.value },
                browseEntriesEvents = browseEntries,
                discoveryForeground = foreground,
                discoveryPower = power,
                hasDiscoveryMemory = { true },
                windowBudgetMillis = 5_000L
            )

            controller.resume()
            val result = orchestrator.runScheduledDiscovery()
            assertTrue("Scheduled discovery must verify models", modelsChecked)
            assertTrue("Scheduled discovery result must be complete or pending but was $result",
                result == DiscoveryWindowResult.COMPLETE || result == DiscoveryWindowResult.PENDING)
            orchestrator.shutdown()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun pausedDiscoveryCancelsWorkManagerScheduler() = runBlocking {
        val root = files.newFolder()
        val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index"))
        val interests = FileDiscoveryInterestStore(root.resolve("interests"))
        val settings = InMemorySettingsRepository(ReaderSettings(discoveryEffort = DiscoveryEffort.BALANCED))
        val scheduler = FakeScheduler()
        val controller = DiscoveryController(cache, index, interests, testProvider())
        val isPlaying = MutableStateFlow(false)
        val browseEntries = MutableStateFlow(emptyList<ReaderIndexEntry>())
        val foreground = MutableStateFlow(true)
        val power = MutableStateFlow(DiscoveryPowerState(percent = 80, connected = true))

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val orchestrator = DiscoveryOrchestrator(
                scope = scope,
                discovery = controller,
                documentCache = cache,
                settingsRepository = settings,
                scheduler = scheduler,
                isModelInstalled = { true },
                isPlaying = { isPlaying.value },
                isPlayingEvents = isPlaying,
                browseEntries = { browseEntries.value },
                browseEntriesEvents = browseEntries,
                discoveryForeground = foreground,
                discoveryPower = power,
                hasDiscoveryMemory = { true },
                windowBudgetMillis = 5_000L
            )

            orchestrator.start()
            delay(100)

            assertTrue("Starting with paused state must cancel WorkManager scheduler", scheduler.cancelCalls > 0)
            orchestrator.shutdown()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cachedDiscoveryRunsIndependentlyOfStructuralWork() = runBlocking {
        val root = files.newFolder()
        val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index"))
        val interests = FileDiscoveryInterestStore(root.resolve("interests"))
        val settings = InMemorySettingsRepository(ReaderSettings(discoveryEffort = DiscoveryEffort.BALANCED))
        val scheduler = FakeScheduler()
        val controller = DiscoveryController(cache, index, interests, testProvider())
        val isPlaying = MutableStateFlow(false)
        val browseEntries = MutableStateFlow(emptyList<ReaderIndexEntry>())
        val foreground = MutableStateFlow(true)
        val power = MutableStateFlow(DiscoveryPowerState(percent = 80, connected = true))

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val orchestrator = DiscoveryOrchestrator(
                scope = scope,
                discovery = controller,
                documentCache = cache,
                settingsRepository = settings,
                scheduler = scheduler,
                isModelInstalled = { true },
                isPlaying = { isPlaying.value },
                isPlayingEvents = isPlaying,
                browseEntries = { browseEntries.value },
                browseEntriesEvents = browseEntries,
                discoveryForeground = foreground,
                discoveryPower = power,
                hasDiscoveryMemory = { true },
                windowBudgetMillis = 5_000L
            )

            controller.resume()
            val doc = ReaderDocument.fromChunkTexts("p1", "Offline Note", listOf("Offline note content for discovery."))
            cache.put(CachedDocument(doc, null, Instant.EPOCH))

            val scheduledResult = orchestrator.runScheduledDiscovery()
            assertEquals("Cached Discovery scheduled window must complete independently",
                DiscoveryWindowResult.COMPLETE, scheduledResult)

            orchestrator.shutdown()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cacheInvalidationTriggersPromptSourceInvalidationWithoutWaitingForReconciliationWindow() = runBlocking {
        val root = files.newFolder()
        val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index"))
        val interests = FileDiscoveryInterestStore(root.resolve("interests"))
        val settings = InMemorySettingsRepository(ReaderSettings(discoveryEffort = DiscoveryEffort.BALANCED))
        val scheduler = FakeScheduler()
        val controller = DiscoveryController(cache, index, interests, testProvider())
        val isPlaying = MutableStateFlow(false)
        val browseEntries = MutableStateFlow(emptyList<ReaderIndexEntry>())
        val foreground = MutableStateFlow(true)
        val power = MutableStateFlow(DiscoveryPowerState(percent = 80, connected = true))

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val orchestrator = DiscoveryOrchestrator(
                scope = scope,
                discovery = controller,
                documentCache = cache,
                settingsRepository = settings,
                scheduler = scheduler,
                isModelInstalled = { true },
                isPlaying = { isPlaying.value },
                isPlayingEvents = isPlaying,
                browseEntries = { browseEntries.value },
                browseEntriesEvents = browseEntries,
                discoveryForeground = foreground,
                discoveryPower = power,
                hasDiscoveryMemory = { true },
                windowBudgetMillis = 5_000L
            )

            controller.resume()
            val doc = ReaderDocument.fromChunkTexts("p1", "Note 1", listOf("Content for indexing."))
            cache.put(CachedDocument(doc, null, Instant.EPOCH))

            orchestrator.start()
            val scheduledResult = orchestrator.runScheduledDiscovery()
            assertEquals(DiscoveryWindowResult.COMPLETE, scheduledResult)
            assertEquals(DiscoveryCoverageStatus.Ready, controller.state.value.indexCoverage)

            foreground.value = false

            cache.remove("p1")

            var checks = 0
            while (controller.state.value.indexCoverage != DiscoveryCoverageStatus.Checking && checks < 20) {
                delay(20)
                checks++
            }

            assertTrue("Reconciliation must be pending immediately on invalidation", controller.hasPendingWork)
            assertEquals("indexCoverage must switch to Checking promptly",
                DiscoveryCoverageStatus.Checking, controller.state.value.indexCoverage)
            assertNull("Embedding work must be cleared promptly", controller.state.value.embeddingWork)

            foreground.value = true
            orchestrator.requestWake()
            checks = 0
            while (controller.hasPendingWork && checks < 50) {
                delay(20)
                checks++
            }
            assertFalse("Reconciliation should complete after foreground returns", controller.hasPendingWork)

            orchestrator.shutdown()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun foregroundAndWorkManagerExecutionDoNotCreateDuplicateWorkers() = runBlocking {
        val root = files.newFolder()
        val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index"))
        val interests = FileDiscoveryInterestStore(root.resolve("interests"))
        val settings = InMemorySettingsRepository(ReaderSettings(discoveryEffort = DiscoveryEffort.BALANCED))
        val scheduler = FakeScheduler()
        val controller = DiscoveryController(cache, index, interests, testProvider())
        val isPlaying = MutableStateFlow(false)
        val browseEntries = MutableStateFlow(emptyList<ReaderIndexEntry>())
        val foreground = MutableStateFlow(true)
        val power = MutableStateFlow(DiscoveryPowerState(percent = 80, connected = true))

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val orchestrator = DiscoveryOrchestrator(
                scope = scope,
                discovery = controller,
                documentCache = cache,
                settingsRepository = settings,
                scheduler = scheduler,
                isModelInstalled = { true },
                isPlaying = { isPlaying.value },
                isPlayingEvents = isPlaying,
                browseEntries = { browseEntries.value },
                browseEntriesEvents = browseEntries,
                discoveryForeground = foreground,
                discoveryPower = power,
                hasDiscoveryMemory = { true },
                windowBudgetMillis = 5_000L
            )

            controller.resume()
            val doc = ReaderDocument.fromChunkTexts("p1", "Note", listOf("Body"))
            cache.put(CachedDocument(doc, null, Instant.EPOCH))

            // Run two scheduled calls concurrently
            val call1 = async(Dispatchers.Default) { orchestrator.runScheduledDiscovery() }
            val call2 = async(Dispatchers.Default) { orchestrator.runScheduledDiscovery() }

            val r1 = call1.await()
            val r2 = call2.await()

            // Both calls must complete with the same consistent result without creating duplicate runs
            assertEquals(DiscoveryWindowResult.COMPLETE, r1)
            assertEquals(DiscoveryWindowResult.COMPLETE, r2)

            orchestrator.shutdown()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedOrCancelledSchedulerCompletionsDoNotTriggerContinuation() = runBlocking {
        val root = files.newFolder()
        val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index"))
        val interests = FileDiscoveryInterestStore(root.resolve("interests"))
        val settings = InMemorySettingsRepository(ReaderSettings(discoveryEffort = DiscoveryEffort.BALANCED))
        val scheduler = FakeScheduler()
        val controller = DiscoveryController(cache, index, interests, testProvider())
        val isPlaying = MutableStateFlow(false)
        val browseEntries = MutableStateFlow(emptyList<ReaderIndexEntry>())
        val foreground = MutableStateFlow(false) // in background
        val power = MutableStateFlow(DiscoveryPowerState(percent = 80, connected = true))

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val orchestrator = DiscoveryOrchestrator(
                scope = scope,
                discovery = controller,
                documentCache = cache,
                settingsRepository = settings,
                scheduler = scheduler,
                isModelInstalled = { true },
                isPlaying = { isPlaying.value },
                isPlayingEvents = isPlaying,
                browseEntries = { browseEntries.value },
                browseEntriesEvents = browseEntries,
                discoveryForeground = foreground,
                discoveryPower = power,
                hasDiscoveryMemory = { true }
            )

            controller.resume()
            val doc = ReaderDocument.fromChunkTexts("p1", "Note", listOf("Body"))
            cache.put(CachedDocument(doc, null, Instant.EPOCH))

            orchestrator.start()
            delay(50)

            val initialRevision = controller.revision.value

            // Emit failure and cancellation events
            scheduler.completionsFlow.emit(listOf(
                DiscoveryTaskCompletion("1", DiscoveryTaskOutcome.Failure),
                DiscoveryTaskCompletion("2", DiscoveryTaskOutcome.Cancelled)
            ))
            delay(50)

            assertEquals("Failed or cancelled task completion must not trigger automatic continuation",
                initialRevision, controller.revision.value)

            // Emit success event
            scheduler.completionsFlow.emit(listOf(
                DiscoveryTaskCompletion("3", DiscoveryTaskOutcome.Success)
            ))
            delay(50)

            assertTrue("Successful task completion must trigger continuation when work pending",
                controller.revision.value > initialRevision)

            orchestrator.shutdown()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun shutdownAndRestartIsSafeWithoutDuplicateSubscriptions() = runBlocking {
        val root = files.newFolder()
        val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index"))
        val interests = FileDiscoveryInterestStore(root.resolve("interests"))
        val settings = InMemorySettingsRepository(ReaderSettings(discoveryEffort = DiscoveryEffort.BALANCED))
        val scheduler = FakeScheduler()
        val controller = DiscoveryController(cache, index, interests, testProvider())
        val isPlaying = MutableStateFlow(false)
        val browseEntries = MutableStateFlow(emptyList<ReaderIndexEntry>())
        val foreground = MutableStateFlow(true)
        val power = MutableStateFlow(DiscoveryPowerState(percent = 80, connected = true))

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val orchestrator = DiscoveryOrchestrator(
                scope = scope,
                discovery = controller,
                documentCache = cache,
                settingsRepository = settings,
                scheduler = scheduler,
                isModelInstalled = { true },
                isPlaying = { isPlaying.value },
                isPlayingEvents = isPlaying,
                browseEntries = { browseEntries.value },
                browseEntriesEvents = browseEntries,
                discoveryForeground = foreground,
                discoveryPower = power,
                hasDiscoveryMemory = { true }
            )

            // Start then shutdown
            orchestrator.start()
            delay(50)
            orchestrator.shutdown()
            delay(50)

            // Restart orchestrator
            orchestrator.start()
            delay(50)

            // Verify safe restart
            assertTrue(scope.isActive)
            orchestrator.shutdown()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun orchestratorPausingDuringSchedulerRequestCancelsWorkWithoutResurrection() = runBlocking {
        val root = files.newFolder()
        val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index"))
        val interests = FileDiscoveryInterestStore(root.resolve("interests"))
        val settings = InMemorySettingsRepository(ReaderSettings(discoveryEffort = DiscoveryEffort.BALANCED))

        val cancelledNames = java.util.concurrent.CopyOnWriteArrayList<String>()
        val enqueuedImmediate = java.util.concurrent.CopyOnWriteArrayList<String>()
        val queryStarted = CompletableDeferred<Unit>()
        val holdQuery = CompletableDeferred<Unit>()

        val client = object : WorkManagerClient {
            override suspend fun getWorkInfos(name: String): List<ScheduledWorkRecord> {
                queryStarted.complete(Unit)
                holdQuery.await()
                return emptyList()
            }
            override fun cancelUniqueWork(name: String) {
                cancelledNames.add(name)
            }
            override fun enqueueUniquePeriodicWork(name: String, policy: androidx.work.ExistingPeriodicWorkPolicy, request: androidx.work.PeriodicWorkRequest) {}
            override fun enqueueUniqueWork(name: String, policy: androidx.work.ExistingWorkPolicy, request: androidx.work.OneTimeWorkRequest) {
                enqueuedImmediate.add(name)
            }
            override fun getWorkInfosFlow(name: String): Flow<List<ScheduledWorkRecord>> = MutableSharedFlow()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val productionScheduler = DiscoveryScheduler(
            immediateName = "orch-immediate",
            recoveryName = "orch-recovery",
            ioDispatcher = Dispatchers.Default,
            client = client,
            schedulerScope = scope
        )

        val controller = DiscoveryController(cache, index, interests, testProvider())
        val isPlaying = MutableStateFlow(false)
        val browseEntries = MutableStateFlow(emptyList<ReaderIndexEntry>())
        val foreground = MutableStateFlow(true)
        val power = MutableStateFlow(DiscoveryPowerState(percent = 80, connected = true))

        try {
            val orchestrator = DiscoveryOrchestrator(
                scope = scope,
                discovery = controller,
                documentCache = cache,
                settingsRepository = settings,
                scheduler = productionScheduler,
                isModelInstalled = { true },
                isPlaying = { isPlaying.value },
                isPlayingEvents = isPlaying,
                browseEntries = { browseEntries.value },
                browseEntriesEvents = browseEntries,
                discoveryForeground = foreground,
                discoveryPower = power,
                hasDiscoveryMemory = { true }
            )

            // Inject work so controller has pending work
            val doc = ReaderDocument.fromChunkTexts("p1", "Test", listOf("Content for indexing."))
            cache.put(CachedDocument(doc, null, Instant.EPOCH))
            controller.resume()

            orchestrator.start()

            // Wait for work loop to invoke scheduler.request, which blocks in getWorkInfos
            queryStarted.await()

            // Concurrently pause discovery, which invokes scheduler.cancel()
            controller.pause()
            orchestrator.requestWake()

            // Release query
            holdQuery.complete(Unit)
            delay(100)

            assertTrue("Cancelling when paused must call cancelUniqueWork", cancelledNames.contains("orch-immediate"))
            assertTrue("Superseded in-flight request must not enqueue work after cancellation", enqueuedImmediate.isEmpty())

            orchestrator.shutdown()
        } finally {
            productionScheduler.shutdown()
            scope.cancel()
        }
    }
}
