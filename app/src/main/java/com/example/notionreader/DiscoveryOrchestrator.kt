package com.example.notionreader

import com.example.notionreader.application.DiscoveryController
import com.example.notionreader.application.DiscoveryExecutionAvailability
import com.example.notionreader.application.DiscoveryPowerState
import com.example.notionreader.application.DiscoveryWindowResult
import com.example.notionreader.application.DiscoveryWorkCoordinator
import com.example.notionreader.application.discoveryWindowResult
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.domain.settings.DiscoveryEffort
import com.example.notionreader.domain.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-owned orchestrator for Discovery execution and scheduling.
 *
 * Runs cached-note Discovery independently of remote structural network collection.
 * Differentiates external scheduling events (wakes, configuration updates, invalidations)
 * from worker-internal continuation, eliminating the self-cancellation defect where in-window
 * progress or upstream flow emissions cancelled the active execution window.
 */
class DiscoveryOrchestrator(
    private val scope: CoroutineScope,
    private val discovery: DiscoveryController,
    private val documentCache: DocumentCache,
    private val settingsRepository: SettingsRepository,
    private val scheduler: DiscoveryTaskScheduler,
    private val isModelInstalled: () -> Boolean,
    private val verifyDiscoveryModels: suspend () -> Unit = {},
    private val isPlaying: () -> Boolean,
    private val isPlayingEvents: Flow<Boolean>,
    private val browseEntries: () -> List<ReaderIndexEntry>,
    private val browseEntriesEvents: Flow<List<ReaderIndexEntry>>,
    private val discoveryForeground: StateFlow<Boolean>,
    private val discoveryPower: StateFlow<DiscoveryPowerState>,
    private val hasDiscoveryMemory: (force: Boolean) -> Boolean,
    private val onRefreshStorageBudget: suspend () -> Unit = {},
    private val onRefreshRecentNotes: suspend () -> Unit = {},
    private val onStructuralTicket: suspend () -> Unit = {},
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val windowBudgetMillis: Long = 120_000L,
    private val logger: ((String) -> Unit)? = null
) {
    private val executionMutex = Mutex()
    @Volatile private var inFlightExecution: Deferred<DiscoveryWindowResult>? = null
    @Volatile private var isScheduledRunning = false

    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    val coordinator: DiscoveryWorkCoordinator = DiscoveryWorkCoordinator(
        monotonicMillis = monotonicMillis,
        onWindowEnd = { discovery.closeLabels() }
    ) {
        val effort = settingsRepository.settings.value.discoveryEffort
        onRefreshStorageBudget()
        val playing = isPlaying()
        val memoryOk = hasDiscoveryMemory(false)
        val foreground = discoveryForeground.value
        val scheduled = isScheduledRunning
        val powerAllows = discoveryPower.value.allows(effort)
        val installed = isModelInstalled()
        val eligible = (foreground || scheduled) && memoryOk && powerAllows && installed && !playing
        discovery.updateWorkAvailability(effort, when {
            playing -> DiscoveryExecutionAvailability.WaitingForPlayback
            !memoryOk -> DiscoveryExecutionAvailability.WaitingForResources
            !foreground && !scheduled -> DiscoveryExecutionAvailability.WaitingForAndroid
            else -> DiscoveryExecutionAvailability.Available
        })
        discovery.updatePowerState(discoveryPower.value)
        onRefreshRecentNotes()
        onStructuralTicket()
        discovery.reconcile(browseEntries(), eligible)
        discoveryWindowResult(installed, discovery.state.value, eligible, discovery.hasPendingWork)
    }

    fun isDiscoveryEligible(isScheduled: Boolean = isScheduledRunning): Boolean {
        val currentEffort = settingsRepository.settings.value.discoveryEffort
        val foregroundOrScheduled = discoveryForeground.value || isScheduled
        val memoryOk = hasDiscoveryMemory(false)
        val powerAllows = discoveryPower.value.allows(currentEffort)
        val installed = isModelInstalled()
        val playing = isPlaying()
        val notPaused = !discovery.state.value.paused
        return foregroundOrScheduled && memoryOk && powerAllows && installed && !playing && notPaused
    }

    fun isIndexingEligible(): Boolean {
        return coordinator.windowOpen && isDiscoveryEligible(isScheduledRunning)
    }

    suspend fun runScheduledDiscovery(): DiscoveryWindowResult {
        val existing = executionMutex.withLock { inFlightExecution }
        if (existing != null) {
            return try { existing.await() } catch (_: CancellationException) { currentCoroutineContext().ensureActive(); DiscoveryWindowResult.DEFERRED }
        }
        return executeWindow(isScheduled = true)
    }

    private suspend fun executeWindow(isScheduled: Boolean): DiscoveryWindowResult {
        val deferred = CompletableDeferred<DiscoveryWindowResult>()
        val startRun = executionMutex.withLock {
            val existing = inFlightExecution
            if (existing != null) {
                existing
            } else {
                inFlightExecution = deferred
                null
            }
        }
        if (startRun != null) {
            return try { startRun.await() } catch (_: CancellationException) { currentCoroutineContext().ensureActive(); DiscoveryWindowResult.DEFERRED }
        }

        try {
            if (isScheduled) isScheduledRunning = true
            verifyDiscoveryModels()
            if (isScheduled) hasDiscoveryMemory(true)
            val started = monotonicMillis()
            var outcome: DiscoveryWindowResult? = null
            val result = try {
                val res = coordinator.runWindow(windowBudgetMillis)
                outcome = res
                res
            } finally {
                logger?.invoke("window result=${outcome ?: "interrupted"} in ${monotonicMillis() - started}ms")
            }
            deferred.complete(result)
            return result
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            withContext(NonCancellable) {
                executionMutex.withLock {
                    if (inFlightExecution === deferred) {
                        inFlightExecution = null
                        if (isScheduled) isScheduledRunning = false
                    }
                }
            }
        }
    }

    fun requestWake() {
        wakeSignal.trySend(Unit)
    }

    private var sessionScope: CoroutineScope? = null

    fun start() {
        synchronized(this) {
            if (sessionScope != null) return
            val session = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
            sessionScope = session
            session.launch {
                verifyDiscoveryModels()
                coordinator.prepareInventory { discovery.reconcile(browseEntries(), false) }
                setupEventSubscriptions(session)
                runWorkLoop()
            }
        }
    }

    private fun setupEventSubscriptions(session: CoroutineScope) {
        session.launch {
            scheduler.completions().distinctUntilChanged().collect { completions ->
                if (completions.any { it.outcome == DiscoveryTaskOutcome.Success } && discovery.hasPendingWork && !discovery.state.value.paused) {
                    discovery.requestAutomaticContinuation()
                    requestWake()
                }
            }
        }
        session.launch {
            documentCache.revision.collect {
                discovery.invalidateSources()
                requestWake()
            }
        }
        session.launch {
            browseEntriesEvents.collect {
                requestWake()
            }
        }
        session.launch {
            discoveryForeground.collect {
                if (it) hasDiscoveryMemory(true)
                requestWake()
            }
        }
        session.launch {
            isPlayingEvents.collect { playing ->
                if (playing) {
                    discovery.updateWorkAvailability(
                        settingsRepository.settings.value.discoveryEffort,
                        DiscoveryExecutionAvailability.WaitingForPlayback
                    )
                }
                requestWake()
            }
        }
        session.launch {
            discoveryPower.collect {
                requestWake()
            }
        }
        session.launch {
            settingsRepository.settings.collect {
                requestWake()
            }
        }
        session.launch {
            discovery.revision.collect {
                requestWake()
            }
        }
    }

    private suspend fun runWorkLoop() {
        requestWake() // Initial kick
        while (currentCoroutineContext().isActive) {
            wakeSignal.receive()
            while (wakeSignal.tryReceive().isSuccess) {}

            val currentEffort = settingsRepository.settings.value.discoveryEffort
            val installed = isModelInstalled()
            val eligible = isDiscoveryEligible(isScheduled = false)

            onStructuralTicket()

            val windowResult = if (installed && eligible) {
                executeWindow(isScheduled = false)
            } else {
                null
            }

            val hasAnyPending = discovery.hasPendingWork
            val paused = discovery.state.value.paused
            if (!installed || paused) {
                scheduler.cancel()
            } else {
                scheduler.request(currentEffort, hasAnyPending)
            }
            if (!installed) {
                discovery.releaseModel()
            }

            // If work is still pending and conditions are eligible, continue automatically
            if (windowResult == DiscoveryWindowResult.PENDING && isDiscoveryEligible(isScheduled = false) && discovery.hasPendingWork && !paused) {
                yield()
                requestWake()
            }
        }
    }

    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            val effort = settingsRepository.settings.value.discoveryEffort
            discovery.updateWorkAvailability(
                effort,
                DiscoveryExecutionAvailability.WaitingForResources
            )
            scope.launch { discovery.releaseModel() }
        }
    }

    fun shutdown() {
        synchronized(this) {
            sessionScope?.cancel()
            sessionScope = null
        }
    }
}
