package com.example.notionreader.application

import com.example.notionreader.domain.settings.DownloadNetworkPolicy
import com.example.notionreader.domain.settings.ReaderSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrates background execution of [StructuralKnowledgeCoordinator] within
 * application and device resource boundaries.
 *
 * Honors playback priority (pauses/yields when audio is active), respects network
 * settings (WiFi-only policy), adheres to memory/resource constraints, and processes
 * durable pending work independently of whether any AI/discovery model is installed.
 */
class StructuralWorkScheduler(
    val coordinator: StructuralKnowledgeCoordinator,
    private val settings: () -> ReaderSettings,
    private val isPlaying: () -> Boolean,
    private val isUnmetered: () -> Boolean = { true },
    private val hasResources: () -> Boolean = { true }
) {
    private val windowMutex = Mutex()
    private var pipelineScope: CoroutineScope? = null
    private var pipelineJob: CompletableJob? = null
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
    private val externalWakeCount = java.util.concurrent.atomic.AtomicLong(0L)

    /** Also guards scheduled HTTP: a grant of worker time never overrides playback priority. */
    suspend fun <T : Any> whileEligible(block: suspend () -> T): T? = withContext(Dispatchers.IO) {
        if (!isEligible()) return@withContext null
        coroutineScope {
            val eligibilityLost = java.util.concurrent.atomic.AtomicBoolean(false)
            val work = async { block() }
            val monitor = launch {
                while (work.isActive) {
                    delay(100)
                    if (!isEligible()) { eligibilityLost.set(true); work.cancel(); break }
                }
            }
            try { work.await() }
            catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (!eligibilityLost.get()) throw cancelled
                null
            } finally { monitor.cancel() }
        }
    }

    /**
     * Determines whether background structural work may proceed under current constraints.
     */
    fun isEligible(): Boolean {
        if (isPlaying()) return false
        if (!hasResources()) return false
        val currentSettings = settings()
        if (!currentSettings.cacheNotesForOffline) return false
        if (currentSettings.downloadNetworkPolicy == DownloadNetworkPolicy.WIFI_ONLY && !isUnmetered()) {
            return false
        }
        return true
    }

    /**
     * Runs a time-bounded window of structural collection work.
     * Yields or halts immediately if conditions become ineligible (e.g., playback begins).
     */
    suspend fun runWindow(
        maxPages: Int = 1,
        timeBudgetMillis: Long = 120_000
    ): Boolean = windowMutex.withLock {
        if (!isEligible()) return false
        if (!coordinator.hasPendingWork()) return true

        whileEligible { coordinator.runWindow(
            maxPages = maxPages,
            timeBudgetMillis = timeBudgetMillis,
            mayContinue = { isEligible() }
        ) } == true
    }

    /**
     * Enqueues a page ID for durable structural indexing.
     */
    suspend fun queuePage(pageId: String, reason: String = "manual") {
        coordinator.request(pageId, reason)
    }

    /**
     * Enqueues a collection of page IDs (such as browse discovery results) for durable indexing.
     */
    suspend fun queuePages(pageIds: Collection<String>, reason: String = "browse-discovery") {
        for (id in pageIds) {
            coordinator.request(id, reason)
        }
    }

    fun requestWake() {
        externalWakeCount.incrementAndGet()
        wakeSignal.trySend(Unit)
    }

    fun startPipeline(
        scope: CoroutineScope,
        workRevision: Flow<Long> = coordinator.workRevision,
        resourceRevision: Flow<Long> = flowOf(0L),
        settingsEvents: Flow<ReaderSettings>,
        powerEvents: Flow<DiscoveryPowerState>,
        foregroundEvents: Flow<Boolean>,
        isPlayingEvents: Flow<Boolean>,
        onDiscoverResources: suspend () -> Unit = {},
        isDiscoveryPending: () -> Boolean = { false },
        coalesceDelayMillis: Long = 250L,
        onWindowComplete: ((Boolean) -> Unit)? = null
    ): Job {
        shutdownPipeline()
        val parentJob = scope.coroutineContext[Job]
        val supervisorJob = SupervisorJob(parentJob)
        val supervisor = CoroutineScope(scope.coroutineContext + supervisorJob)
        pipelineJob = supervisorJob
        pipelineScope = supervisor

        supervisor.launch { workRevision.collect { requestWake() } }
        supervisor.launch { resourceRevision.collect { requestWake() } }
        supervisor.launch { settingsEvents.collect { requestWake() } }
        supervisor.launch { powerEvents.collect { requestWake() } }
        supervisor.launch { foregroundEvents.collect { requestWake() } }
        supervisor.launch { isPlayingEvents.collect { requestWake() } }

        supervisor.launch {
            try {
                requestWake()
                while (currentCoroutineContext().isActive) {
                    wakeSignal.receive()
                    while (wakeSignal.tryReceive().isSuccess) {}

                    if (!isEligible()) {
                        continue
                    }

                    val wakeCountAtStart = externalWakeCount.get()
                    var productiveProgress = false

                    if (isDiscoveryPending()) {
                        try {
                            onDiscoverResources()
                            if (!isDiscoveryPending()) {
                                productiveProgress = true
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Discovery failed; not productive progress
                        }
                    }

                    if (coordinator.hasPendingWork() && isEligible()) {
                        if (coalesceDelayMillis > 0) delay(coalesceDelayMillis)
                        if (coordinator.hasPendingWork() && isEligible()) {
                            val ran = try {
                                runWindow(maxPages = 20, timeBudgetMillis = 120_000)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            onWindowComplete?.invoke(ran)
                            if (ran) {
                                productiveProgress = true
                            }
                        }
                    }

                    val externalWorkArrived = externalWakeCount.get() != wakeCountAtStart
                    val hasMoreWork = coordinator.hasPendingWork() || isDiscoveryPending()

                    if (isEligible() && (externalWorkArrived || (productiveProgress && hasMoreWork))) {
                        yield()
                        wakeSignal.trySend(Unit)
                    }
                }
            } finally {
                supervisorJob.cancel()
            }
        }

        return supervisorJob
    }

    fun shutdownPipeline() {
        val job = pipelineJob
        pipelineJob = null
        pipelineScope = null
        job?.cancel()
    }

    suspend fun shutdownPipelineAndJoin() {
        val job = pipelineJob
        pipelineJob = null
        pipelineScope = null
        job?.cancelAndJoin()
    }
}
