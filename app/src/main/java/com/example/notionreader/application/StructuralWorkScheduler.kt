package com.example.notionreader.application

import com.example.notionreader.domain.settings.DownloadNetworkPolicy
import com.example.notionreader.domain.settings.ReaderSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.*

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

    /** Also guards scheduled HTTP: a grant of worker time never overrides playback priority. */
    suspend fun <T : Any> whileEligible(block: suspend () -> T): T? = withContext(Dispatchers.IO) {
        if (!isEligible()) return@withContext null
        coroutineScope {
            val work = async { block() }
            val monitor = launch {
                while (work.isActive) {
                    delay(100)
                    if (!isEligible()) { work.cancel(); break }
                }
            }
            try { work.await() }
            catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
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
        ) } != null
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
}
