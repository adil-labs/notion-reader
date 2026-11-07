package com.example.notionreader.application

import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.data.structure.FileStructuralKnowledgeStore
import com.example.notionreader.domain.settings.DownloadNetworkPolicy
import com.example.notionreader.domain.settings.ReaderSettings
import com.example.notionreader.domain.structure.PageAccessibilityState
import com.example.notionreader.domain.structure.StructuralPage
import com.example.notionreader.domain.structure.StructuralPageRecord
import com.example.notionreader.domain.structure.StructuralParent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicBoolean

class StructuralWorkSchedulerTest {
    @Test fun productiveWindowsContinueUntilAllQueuedWorkCompletes() = runBlocking {
        val completed = CompletableDeferred<Unit>()
        val seen = java.util.concurrent.atomic.AtomicInteger()
        val coordinator = createCoordinator(collect = { id, source ->
            if (seen.incrementAndGet() == 21) completed.complete(Unit)
            record(id, source)
        })
        repeat(21) { coordinator.request(java.util.UUID(0, (it + 1).toLong()).toString()) }
        val scheduler = StructuralWorkScheduler(coordinator, { ReaderSettings() }, { false }, { true }, { true })
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            scheduler.startPipeline(scope, workRevision = kotlinx.coroutines.flow.emptyFlow(),
                resourceRevision = kotlinx.coroutines.flow.emptyFlow(), settingsEvents = kotlinx.coroutines.flow.emptyFlow(),
                powerEvents = kotlinx.coroutines.flow.emptyFlow(), foregroundEvents = kotlinx.coroutines.flow.emptyFlow(),
                isPlayingEvents = kotlinx.coroutines.flow.emptyFlow(), coalesceDelayMillis = 0)
            withTimeout(20000) { completed.await() }
            withTimeout(5000) { while (coordinator.hasPendingWork()) delay(20) }
            assertEquals(21, seen.get())
        } finally { scheduler.shutdownPipelineAndJoin(); scope.cancel() }
    }

    @Test fun failedStructuralCollectionDoesNotRetryWithoutAChange() = runBlocking {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val coordinator = createCoordinator(collect = { _, _ ->
            attempts.incrementAndGet(); entered.complete(Unit); error("offline")
        })
        coordinator.request(page1)
        val scheduler = StructuralWorkScheduler(coordinator, { ReaderSettings() }, { false }, { true }, { true })
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            scheduler.startPipeline(scope, settingsEvents = kotlinx.coroutines.flow.emptyFlow(),
                powerEvents = kotlinx.coroutines.flow.emptyFlow(), foregroundEvents = kotlinx.coroutines.flow.emptyFlow(),
                isPlayingEvents = kotlinx.coroutines.flow.emptyFlow(), coalesceDelayMillis = 0)
            withTimeout(5000) { entered.await() }
            delay(1000)
            val settled = attempts.get()
            delay(1000)
            assertEquals("A recoverable failure must wait for a new signal", settled, attempts.get())
            assertTrue(coordinator.hasPendingWork())
        } finally { scheduler.shutdownPipelineAndJoin(); scope.cancel() }
    }

    @Test fun cancellingParentStopsSuspendedProcessingAndCollectors() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val collectorStopped = CompletableDeferred<Unit>()
        val coordinator = createCoordinator(collect = { _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        })
        coordinator.request(page1)
        val scheduler = StructuralWorkScheduler(coordinator, { ReaderSettings() }, { false }, { true }, { true })
        val parent = Job()
        val events = kotlinx.coroutines.flow.flow<ReaderSettings> {
            try { awaitCancellation() } finally { collectorStopped.complete(Unit) }
        }
        val pipeline = scheduler.startPipeline(CoroutineScope(Dispatchers.Default + parent),
            settingsEvents = events, powerEvents = kotlinx.coroutines.flow.emptyFlow(),
            foregroundEvents = kotlinx.coroutines.flow.emptyFlow(), isPlayingEvents = kotlinx.coroutines.flow.emptyFlow(), coalesceDelayMillis = 0)
        withTimeout(5000) { entered.await() }
        parent.cancel()
        withTimeout(5000) { pipeline.join(); cancelled.await(); collectorStopped.await() }
        assertTrue(pipeline.isCancelled)
    }

    @get:Rule val temporary = TemporaryFolder()
    private val page1 = "11111111-1111-1111-1111-111111111111"
    private val page2 = "22222222-2222-2222-2222-222222222222"
    private val blankTitlePage = "33333333-3333-3333-3333-333333333333"

    private fun record(pageId: String, source: String) = StructuralPageRecord(
        page = StructuralPage(
            id = pageId,
            title = if (pageId == blankTitlePage) "" else "Page $pageId",
            parent = StructuralParent.Workspace,
            sourceIdentity = source,
            sourceRevision = "rev1",
            accessibilityState = PageAccessibilityState.ACCESSIBLE
        ),
        blocks = emptyList(),
        sections = emptyList(),
        outgoingRelationships = emptyList(),
        sourceIdentity = source
    )

    private fun createCoordinator(
        store: FileStructuralKnowledgeStore = FileStructuralKnowledgeStore(temporary.newFolder()),
        cache: FileDocumentCache = FileDocumentCache(temporary.newFolder()),
        collect: suspend (String, String) -> StructuralPageRecord = ::record
    ) = StructuralKnowledgeCoordinator(store, store, cache, { "test-source-sha" }, collect)

    @Test
    fun playbackActiveYieldsAndPreventsExecution() = runTest {
        val coordinator = createCoordinator()
        coordinator.request(page1)

        var playing = true
        val scheduler = StructuralWorkScheduler(
            coordinator = coordinator,
            settings = { ReaderSettings() },
            isPlaying = { playing },
            isUnmetered = { true },
            hasResources = { true }
        )

        assertFalse(scheduler.isEligible())
        val ran = scheduler.runWindow(maxPages = 1)
        assertFalse(ran)
        assertTrue(coordinator.hasPendingWork())

        playing = false
        assertTrue(scheduler.isEligible())
        val ranNow = scheduler.runWindow(maxPages = 1)
        assertTrue(ranNow)
        assertFalse(coordinator.hasPendingWork())
    }

    @Test
    fun wifiOnlyNetworkPolicyRespectsMeteredStatus() = runTest {
        val coordinator = createCoordinator()
        coordinator.request(page1)

        var unmetered = false
        val settings = ReaderSettings(downloadNetworkPolicy = DownloadNetworkPolicy.WIFI_ONLY)
        val scheduler = StructuralWorkScheduler(
            coordinator = coordinator,
            settings = { settings },
            isPlaying = { false },
            isUnmetered = { unmetered },
            hasResources = { true }
        )

        assertFalse(scheduler.isEligible())
        assertFalse(scheduler.runWindow(maxPages = 1))
        assertTrue(coordinator.hasPendingWork())

        unmetered = true
        assertTrue(scheduler.isEligible())
        assertTrue(scheduler.runWindow(maxPages = 1))
        assertFalse(coordinator.hasPendingWork())
    }

    @Test
    fun resourceExhaustionBlocksExecution() = runTest {
        val coordinator = createCoordinator()
        coordinator.request(page1)

        var resourcesAvailable = false
        val scheduler = StructuralWorkScheduler(
            coordinator = coordinator,
            settings = { ReaderSettings() },
            isPlaying = { false },
            isUnmetered = { true },
            hasResources = { resourcesAvailable }
        )

        assertFalse(scheduler.isEligible())
        assertFalse(scheduler.runWindow(maxPages = 1))
        assertTrue(coordinator.hasPendingWork())

        resourcesAvailable = true
        assertTrue(scheduler.isEligible())
        assertTrue(scheduler.runWindow(maxPages = 1))
        assertFalse(coordinator.hasPendingWork())
    }

    @Test
    fun queuePagesEnqueuesAllIdsIncludingBlankTitles() = runTest {
        val coordinator = createCoordinator()
        val scheduler = StructuralWorkScheduler(
            coordinator = coordinator,
            settings = { ReaderSettings() },
            isPlaying = { false },
            isUnmetered = { true },
            hasResources = { true }
        )

        assertFalse(coordinator.hasPendingWork())
        scheduler.queuePages(listOf(page1, page2, blankTitlePage), "browse-discovery")
        assertTrue(coordinator.hasPendingWork())

        scheduler.runWindow(maxPages = 5)
        assertFalse(coordinator.hasPendingWork())
        assertNotNull(coordinator.getPage(page1))
        assertNotNull(coordinator.getPage(page2))
        val blankPage = coordinator.getPage(blankTitlePage)
        assertNotNull(blankPage)
        assertEquals("", blankPage!!.title)
    }

    @Test
    fun processesDurableWorkWithoutAiModel() = runTest {
        val directory = temporary.newFolder()
        val cacheDir = temporary.newFolder()
        val store1 = FileStructuralKnowledgeStore(directory)
        val cache1 = FileDocumentCache(cacheDir)
        val coordinator1 = StructuralKnowledgeCoordinator(store1, store1, cache1, { "test-sha" }, ::record)

        coordinator1.request(page1, "offline-pending")

        val store2 = FileStructuralKnowledgeStore(directory)
        val cache2 = FileDocumentCache(cacheDir)
        val coordinator2 = StructuralKnowledgeCoordinator(store2, store2, cache2, { "test-sha" }, ::record)
        val scheduler = StructuralWorkScheduler(
            coordinator = coordinator2,
            settings = { ReaderSettings() },
            isPlaying = { false },
            isUnmetered = { true },
            hasResources = { true }
        )

        assertTrue(coordinator2.hasPendingWork())
        val executed = scheduler.runWindow(maxPages = 1)
        assertTrue(executed)
        assertFalse(coordinator2.hasPendingWork())
        assertEquals("Page $page1", coordinator2.getPage(page1)!!.title)
    }

    @Test
    fun playbackBeginsWhileHttpSuspendedCancelsInFlightWorkLeavingPendingAndNoCommittedPage() = runTest {
        val store = FileStructuralKnowledgeStore(temporary.newFolder())
        val cache = FileDocumentCache(temporary.newFolder())

        val collectEntered = CompletableDeferred<Unit>()
        val releaseCollect = CompletableDeferred<Unit>()

        val coordinator = StructuralKnowledgeCoordinator(
            store = store,
            queries = store,
            cache = cache,
            sourceIdentity = { "test-source-sha" },
            collectPage = { id, source ->
                collectEntered.complete(Unit)
                releaseCollect.await()
                record(id, source)
            }
        )

        coordinator.request(page1, "offline-pending")
        assertTrue(coordinator.hasPendingWork())

        val playing = AtomicBoolean(false)
        val scheduler = StructuralWorkScheduler(
            coordinator = coordinator,
            settings = { ReaderSettings() },
            isPlaying = { playing.get() },
            isUnmetered = { true },
            hasResources = { true }
        )

        val windowJob = async {
            scheduler.runWindow(maxPages = 1)
        }

        collectEntered.await()
        playing.set(true)

        val executed = windowJob.await()
        assertFalse("runWindow must return false when cancelled due to playback ineligibility", executed)

        assertTrue("Work must remain durably pending", coordinator.hasPendingWork())
        val pending = store.readPendingWork()
        assertEquals(1, pending.size)
        assertEquals(page1, pending.single().pageId)

        assertNull("Page record must not be committed to store", coordinator.getPage(page1))
        assertNull(store.readPage(page1))
    }

    @Test
    fun cancellationThrownByChildWhileEligiblePropagates() = runTest {
        val store = FileStructuralKnowledgeStore(temporary.newFolder())
        val cache = FileDocumentCache(temporary.newFolder())

        val coordinator = StructuralKnowledgeCoordinator(
            store = store,
            queries = store,
            cache = cache,
            sourceIdentity = { "test-source-sha" },
            collectPage = { _, _ ->
                throw CancellationException("child cancellation")
            }
        )

        coordinator.request(page1)

        val scheduler = StructuralWorkScheduler(
            coordinator = coordinator,
            settings = { ReaderSettings() },
            isPlaying = { false },
            isUnmetered = { true },
            hasResources = { true }
        )

        assertTrue(scheduler.isEligible())

        var thrown: Throwable? = null
        try {
            scheduler.runWindow(maxPages = 1)
        } catch (e: Throwable) {
            thrown = e
        }

        assertNotNull("Cancellation thrown while eligible must propagate", thrown)
        assertTrue("Exception must be CancellationException", thrown is CancellationException)
        assertEquals("child cancellation", thrown?.message)
    }

    @Test
    fun cacheNotesForOfflineFalseProhibitsWork() = runTest {
        val coordinator = createCoordinator()
        coordinator.request(page1)

        var offlineAllowed = false
        val scheduler = StructuralWorkScheduler(
            coordinator = coordinator,
            settings = { ReaderSettings(cacheNotesForOffline = offlineAllowed) },
            isPlaying = { false },
            isUnmetered = { true },
            hasResources = { true }
        )

        assertFalse("isEligible must be false when cacheNotesForOffline is false", scheduler.isEligible())
        val ran = scheduler.runWindow(maxPages = 1)
        assertFalse("runWindow must not execute when cacheNotesForOffline is false", ran)
        assertTrue("Pending work must remain untouched", coordinator.hasPendingWork())

        offlineAllowed = true
        assertTrue("isEligible must be true when cacheNotesForOffline is true", scheduler.isEligible())
        val ranNow = scheduler.runWindow(maxPages = 1)
        assertTrue("runWindow must execute when eligible", ranNow)
        assertFalse("Pending work must be completed", coordinator.hasPendingWork())
    }

    @Test
    fun pipelineExecutesImmediatelyWhenWorkArrivesWhileAlreadyEligible() = kotlinx.coroutines.runBlocking {
        val coordinator = createCoordinator()
        val scheduler = StructuralWorkScheduler(
            coordinator = coordinator,
            settings = { ReaderSettings() },
            isPlaying = { false },
            isUnmetered = { true },
            hasResources = { true }
        )

        val settingsFlow = kotlinx.coroutines.flow.MutableStateFlow(ReaderSettings())
        val powerFlow = kotlinx.coroutines.flow.MutableStateFlow(DiscoveryPowerState(80, true))
        val foregroundFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
        val playingFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        try {
            val windowFinished = CompletableDeferred<Unit>()
            scheduler.startPipeline(
                scope = scope,
                settingsEvents = settingsFlow,
                powerEvents = powerFlow,
                foregroundEvents = foregroundFlow,
                isPlayingEvents = playingFlow,
                coalesceDelayMillis = 10L,
                onWindowComplete = { windowFinished.complete(Unit) }
            )

            // Pipeline is active, eligible, and idle (no pending work)
            assertFalse(coordinator.hasPendingWork())

            // Now enqueue work: revision changes while eligibility remains true
            scheduler.queuePage(page1)
            assertTrue(coordinator.hasPendingWork())

            // Await execution
            windowFinished.await()
            assertFalse("Work must complete automatically when enqueued while eligible", coordinator.hasPendingWork())
            assertNotNull(coordinator.getPage(page1))

            scheduler.shutdownPipeline()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun workArrivingWhilePipelineIsRunningDoesNotCancelActiveWindowAndProcessesBoth() = kotlinx.coroutines.runBlocking {
        val page1Started = CompletableDeferred<Unit>()
        val page1Release = CompletableDeferred<Unit>()

        val store = FileStructuralKnowledgeStore(temporary.newFolder())
        val cache = FileDocumentCache(temporary.newFolder())
        val coordinator = StructuralKnowledgeCoordinator(
            store = store,
            queries = store,
            cache = cache,
            sourceIdentity = { "test-source-sha" },
            collectPage = { id, source ->
                if (id == page1) {
                    page1Started.complete(Unit)
                    page1Release.await()
                }
                record(id, source)
            }
        )

        val scheduler = StructuralWorkScheduler(
            coordinator = coordinator,
            settings = { ReaderSettings() },
            isPlaying = { false },
            isUnmetered = { true },
            hasResources = { true }
        )

        val settingsFlow = kotlinx.coroutines.flow.MutableStateFlow(ReaderSettings())
        val powerFlow = kotlinx.coroutines.flow.MutableStateFlow(DiscoveryPowerState(80, true))
        val foregroundFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
        val playingFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        try {
            scheduler.queuePage(page1)

            scheduler.startPipeline(
                scope = scope,
                settingsEvents = settingsFlow,
                powerEvents = powerFlow,
                foregroundEvents = foregroundFlow,
                isPlayingEvents = playingFlow,
                coalesceDelayMillis = 10L
            )

            // Wait for page1 to start execution
            page1Started.await()

            // Enqueue page2 while page1 is actively in-flight
            scheduler.queuePage(page2)

            // Yield to confirm page1's window is NOT cancelled by the new arrival
            kotlinx.coroutines.delay(50)
            assertTrue(coordinator.hasPendingWork())

            // Release page1
            page1Release.complete(Unit)

            // Wait until both pages are processed
            var checks = 0
            while (coordinator.hasPendingWork() && checks < 50) {
                kotlinx.coroutines.delay(20)
                checks++
            }

            assertFalse("Both pages must be completed without window cancellation", coordinator.hasPendingWork())
            assertNotNull(coordinator.getPage(page1))
            assertNotNull(coordinator.getPage(page2))

            scheduler.shutdownPipeline()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun workArrivingWhilePipelineIsDeferredWaitsForEligibility() = kotlinx.coroutines.runBlocking {
        val coordinator = createCoordinator()
        val isPlayingState = java.util.concurrent.atomic.AtomicBoolean(true) // Deferred!

        val scheduler = StructuralWorkScheduler(
            coordinator = coordinator,
            settings = { ReaderSettings() },
            isPlaying = { isPlayingState.get() },
            isUnmetered = { true },
            hasResources = { true }
        )

        val settingsFlow = kotlinx.coroutines.flow.MutableStateFlow(ReaderSettings())
        val powerFlow = kotlinx.coroutines.flow.MutableStateFlow(DiscoveryPowerState(80, true))
        val foregroundFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
        val playingFlow = kotlinx.coroutines.flow.MutableStateFlow(true)

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        try {
            scheduler.startPipeline(
                scope = scope,
                settingsEvents = settingsFlow,
                powerEvents = powerFlow,
                foregroundEvents = foregroundFlow,
                isPlayingEvents = playingFlow,
                coalesceDelayMillis = 10L
            )

            // Enqueue work while playing (deferred)
            scheduler.queuePage(page1)
            kotlinx.coroutines.delay(50)
            assertTrue("Work must remain pending while deferred", coordinator.hasPendingWork())

            // Restore eligibility: audio playback stops
            isPlayingState.set(false)
            playingFlow.value = false

            var checks = 0
            while (coordinator.hasPendingWork() && checks < 50) {
                kotlinx.coroutines.delay(20)
                checks++
            }

            assertFalse("Work must execute automatically when eligibility restored", coordinator.hasPendingWork())
            assertNotNull(coordinator.getPage(page1))

            scheduler.shutdownPipeline()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun workArrivingWhilePipelineIsCompletingExecutesCleanly() = kotlinx.coroutines.runBlocking {
        val coordinator = createCoordinator()
        val scheduler = StructuralWorkScheduler(
            coordinator = coordinator,
            settings = { ReaderSettings() },
            isPlaying = { false },
            isUnmetered = { true },
            hasResources = { true }
        )

        val settingsFlow = kotlinx.coroutines.flow.MutableStateFlow(ReaderSettings())
        val powerFlow = kotlinx.coroutines.flow.MutableStateFlow(DiscoveryPowerState(80, true))
        val foregroundFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
        val playingFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        try {
            var enqueuedSecond = false
            scheduler.startPipeline(
                scope = scope,
                settingsEvents = settingsFlow,
                powerEvents = powerFlow,
                foregroundEvents = foregroundFlow,
                isPlayingEvents = playingFlow,
                coalesceDelayMillis = 10L,
                onWindowComplete = {
                    if (!enqueuedSecond) {
                        enqueuedSecond = true
                        scope.launch { scheduler.queuePage(page2) }
                    }
                }
            )

            scheduler.queuePage(page1)

            var checks = 0
            while ((coordinator.hasPendingWork() || !enqueuedSecond || coordinator.getPage(page2) == null) && checks < 50) {
                kotlinx.coroutines.delay(20)
                checks++
            }

            assertFalse(coordinator.hasPendingWork())
            assertNotNull(coordinator.getPage(page1))
            assertNotNull(coordinator.getPage(page2))

            scheduler.shutdownPipeline()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun discoveryEffortSettingsChangeTriggersPipelineExecution() = kotlinx.coroutines.runBlocking {
        val coordinator = createCoordinator()
        val settingsFlow = kotlinx.coroutines.flow.MutableStateFlow(ReaderSettings(cacheNotesForOffline = false))

        val scheduler = StructuralWorkScheduler(
            coordinator = coordinator,
            settings = { settingsFlow.value },
            isPlaying = { false },
            isUnmetered = { true },
            hasResources = { true }
        )

        val powerFlow = kotlinx.coroutines.flow.MutableStateFlow(DiscoveryPowerState(80, true))
        val foregroundFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
        val playingFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        try {
            scheduler.startPipeline(
                scope = scope,
                settingsEvents = settingsFlow,
                powerEvents = powerFlow,
                foregroundEvents = foregroundFlow,
                isPlayingEvents = playingFlow,
                coalesceDelayMillis = 10L
            )

            scheduler.queuePage(page1)
            kotlinx.coroutines.delay(50)
            assertTrue("Work must remain pending while cacheNotesForOffline is false", coordinator.hasPendingWork())

            // Update settings flow to allow offline caching
            settingsFlow.value = ReaderSettings(cacheNotesForOffline = true)

            var checks = 0
            while (coordinator.hasPendingWork() && checks < 50) {
                kotlinx.coroutines.delay(20)
                checks++
            }

            assertFalse("Settings change must trigger pipeline execution", coordinator.hasPendingWork())
            assertNotNull(coordinator.getPage(page1))

            scheduler.shutdownPipeline()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun pipelineCancelsWhenParentScopeIsCancelled() = kotlinx.coroutines.runBlocking {
        val coordinator = createCoordinator()
        val scheduler = StructuralWorkScheduler(
            coordinator = coordinator,
            settings = { ReaderSettings() },
            isPlaying = { false },
            isUnmetered = { true },
            hasResources = { true }
        )

        val settingsFlow = kotlinx.coroutines.flow.MutableStateFlow(ReaderSettings())
        val powerFlow = kotlinx.coroutines.flow.MutableStateFlow(DiscoveryPowerState(80, true))
        val foregroundFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
        val playingFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

        val parentJob = Job()
        val parentScope = CoroutineScope(Dispatchers.Default + parentJob)

        val pipelineJob = scheduler.startPipeline(
            scope = parentScope,
            settingsEvents = settingsFlow,
            powerEvents = powerFlow,
            foregroundEvents = foregroundFlow,
            isPlayingEvents = playingFlow
        )

        assertTrue("Pipeline job must be active initially", pipelineJob.isActive)

        // Cancel parent scope
        parentJob.cancel()
        pipelineJob.join()

        assertFalse("Pipeline job must be cancelled when parent scope cancels", pipelineJob.isActive)
        assertTrue("Pipeline job must be in cancelled state", pipelineJob.isCancelled)
    }

    @Test
    fun stableBlockingDoesNotUnconditionallySelfWakeWhenWorkCannotProgress() = kotlinx.coroutines.runBlocking {
        val coordinator = createCoordinator()
        val scheduler = StructuralWorkScheduler(
            coordinator = coordinator,
            settings = { ReaderSettings() },
            isPlaying = { false },
            isUnmetered = { true },
            hasResources = { true }
        )

        val settingsFlow = kotlinx.coroutines.flow.MutableStateFlow(ReaderSettings())
        val powerFlow = kotlinx.coroutines.flow.MutableStateFlow(DiscoveryPowerState(80, true))
        val foregroundFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
        val playingFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

        val discoveryAttempts = java.util.concurrent.atomic.AtomicInteger(0)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            val pipelineJob = scheduler.startPipeline(
                scope = scope,
                workRevision = kotlinx.coroutines.flow.emptyFlow(),
                resourceRevision = kotlinx.coroutines.flow.emptyFlow(),
                settingsEvents = kotlinx.coroutines.flow.emptyFlow(),
                powerEvents = kotlinx.coroutines.flow.emptyFlow(),
                foregroundEvents = kotlinx.coroutines.flow.emptyFlow(),
                isPlayingEvents = kotlinx.coroutines.flow.emptyFlow(),
                isDiscoveryPending = { true },
                onDiscoverResources = {
                    discoveryAttempts.incrementAndGet()
                    throw RuntimeException("Resource discovery permanently blocked")
                },
                coalesceDelayMillis = 10L
            )

            // Allow initial attempt to execute and verify stable blocking
            delay(100)

            // It must run exactly once and NOT self-wake continuously in a busy loop
            assertEquals("Discovery must be attempted once and stably block without self-waking", 1, discoveryAttempts.get())

            // Delay further to confirm it remains stably blocked
            delay(100)
            assertEquals("Discovery must remain stably blocked and not spin", 1, discoveryAttempts.get())

            scheduler.shutdownPipelineAndJoin()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun externalWorkArrivingDuringExecutionIsPreservedAndProcessed() = kotlinx.coroutines.runBlocking {
        val page1Started = CompletableDeferred<Unit>()
        val page1Release = CompletableDeferred<Unit>()

        val store = FileStructuralKnowledgeStore(temporary.newFolder())
        val cache = FileDocumentCache(temporary.newFolder())
        val coordinator = StructuralKnowledgeCoordinator(
            store = store,
            queries = store,
            cache = cache,
            sourceIdentity = { "test-source-sha" },
            collectPage = { id, source ->
                if (id == page1) {
                    page1Started.complete(Unit)
                    page1Release.await()
                }
                record(id, source)
            }
        )

        val scheduler = StructuralWorkScheduler(
            coordinator = coordinator,
            settings = { ReaderSettings() },
            isPlaying = { false },
            isUnmetered = { true },
            hasResources = { true }
        )

        val settingsFlow = kotlinx.coroutines.flow.MutableStateFlow(ReaderSettings())
        val powerFlow = kotlinx.coroutines.flow.MutableStateFlow(DiscoveryPowerState(80, true))
        val foregroundFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
        val playingFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            scheduler.queuePage(page1)

            scheduler.startPipeline(
                scope = scope,
                settingsEvents = settingsFlow,
                powerEvents = powerFlow,
                foregroundEvents = foregroundFlow,
                isPlayingEvents = playingFlow,
                coalesceDelayMillis = 10L
            )

            page1Started.await()

            // External work arrives while page1 window is actively running
            scheduler.queuePage(page2)

            // Release page1
            page1Release.complete(Unit)

            // Both pages must be processed deterministically
            var checks = 0
            while ((coordinator.hasPendingWork() || coordinator.getPage(page2) == null) && checks < 50) {
                delay(20)
                checks++
            }

            assertFalse("All work must complete", coordinator.hasPendingWork())
            assertNotNull("Page 1 must be committed", coordinator.getPage(page1))
            assertNotNull("Page 2 must be committed", coordinator.getPage(page2))

            scheduler.shutdownPipelineAndJoin()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun eventualProgressResumesWhenEligibilityRestored() = kotlinx.coroutines.runBlocking {
        val coordinator = createCoordinator()
        val playingState = java.util.concurrent.atomic.AtomicBoolean(true)

        val scheduler = StructuralWorkScheduler(
            coordinator = coordinator,
            settings = { ReaderSettings() },
            isPlaying = { playingState.get() },
            isUnmetered = { true },
            hasResources = { true }
        )

        val settingsFlow = kotlinx.coroutines.flow.MutableStateFlow(ReaderSettings())
        val powerFlow = kotlinx.coroutines.flow.MutableStateFlow(DiscoveryPowerState(80, true))
        val foregroundFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
        val playingFlow = kotlinx.coroutines.flow.MutableStateFlow(true)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            scheduler.startPipeline(
                scope = scope,
                settingsEvents = settingsFlow,
                powerEvents = powerFlow,
                foregroundEvents = foregroundFlow,
                isPlayingEvents = playingFlow,
                coalesceDelayMillis = 10L
            )

            // Enqueue work while ineligible (playing)
            scheduler.queuePage(page1)
            scheduler.queuePage(page2)

            delay(50)
            assertTrue("Work must remain pending while ineligible", coordinator.hasPendingWork())
            assertNull(coordinator.getPage(page1))
            assertNull(coordinator.getPage(page2))

            // Restore eligibility
            playingState.set(false)
            playingFlow.value = false

            // Await eventual progress
            var checks = 0
            while (coordinator.hasPendingWork() && checks < 50) {
                delay(20)
                checks++
            }

            assertFalse("Eventual progress must process all pending work when eligible", coordinator.hasPendingWork())
            assertNotNull("Page 1 must be processed", coordinator.getPage(page1))
            assertNotNull("Page 2 must be processed", coordinator.getPage(page2))

            scheduler.shutdownPipelineAndJoin()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun shutdownPipelineCancelsAndJoinsOwnedJobs() = kotlinx.coroutines.runBlocking {
        val coordinator = createCoordinator()
        val scheduler = StructuralWorkScheduler(
            coordinator = coordinator,
            settings = { ReaderSettings() },
            isPlaying = { false },
            isUnmetered = { true },
            hasResources = { true }
        )

        val settingsFlow = kotlinx.coroutines.flow.MutableStateFlow(ReaderSettings())
        val powerFlow = kotlinx.coroutines.flow.MutableStateFlow(DiscoveryPowerState(80, true))
        val foregroundFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
        val playingFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val pipelineJob = scheduler.startPipeline(
                scope = scope,
                settingsEvents = settingsFlow,
                powerEvents = powerFlow,
                foregroundEvents = foregroundFlow,
                isPlayingEvents = playingFlow
            )

            assertTrue("Pipeline job must be active", pipelineJob.isActive)

            scheduler.shutdownPipelineAndJoin()

            assertFalse("Pipeline job must be cancelled after shutdown", pipelineJob.isActive)
            assertTrue("Pipeline job must be in cancelled state", pipelineJob.isCancelled)
        } finally {
            scope.cancel()
        }
    }
}
