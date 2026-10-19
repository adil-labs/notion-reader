package com.example.notionreader.application

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

enum class DiscoveryWindowResult { COMPLETE, PENDING, DEFERRED, PAUSED, UNAVAILABLE }

/** Serializes foreground and scheduled windows; the step retains durable domain progress. */
class DiscoveryWorkCoordinator(
    private val monotonicMillis: () -> Long,
    private val step: suspend () -> DiscoveryWindowResult
) {
    private val execution = Mutex()
    @Volatile private var deadline: Long? = null
    val windowOpen: Boolean get() = deadline?.let { monotonicMillis() < it } == true

    suspend fun runWindow(maximumMillis: Long): DiscoveryWindowResult {
        require(maximumMillis > 0)
        return execution.withLock {
            deadline = monotonicMillis() + maximumMillis
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!windowOpen) return@withLock DiscoveryWindowResult.PENDING
                    val result = step()
                    if (result != DiscoveryWindowResult.PENDING) return@withLock result
                    yield()
                }
                @Suppress("UNREACHABLE_CODE") DiscoveryWindowResult.PENDING
            } finally { deadline = null }
        }
    }
}
