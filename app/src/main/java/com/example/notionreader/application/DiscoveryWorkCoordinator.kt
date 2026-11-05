package com.example.notionreader.application

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

enum class DiscoveryWindowResult { COMPLETE, PENDING, DEFERRED, PAUSED, UNAVAILABLE }

internal fun discoveryWindowResult(modelInstalled: Boolean, state: DiscoveryState,
    eligible: Boolean, hasPendingWork: Boolean): DiscoveryWindowResult = when {
    !modelInstalled -> DiscoveryWindowResult.UNAVAILABLE
    state.paused -> DiscoveryWindowResult.PAUSED
    !eligible -> DiscoveryWindowResult.DEFERRED
    state.processingFailure != null -> DiscoveryWindowResult.DEFERRED
    state.topicBlock != null -> DiscoveryWindowResult.DEFERRED
    hasPendingWork -> DiscoveryWindowResult.PENDING
    else -> DiscoveryWindowResult.COMPLETE
}

/** Serializes foreground and scheduled windows; the step retains durable domain progress. */
class DiscoveryWorkCoordinator(
    private val monotonicMillis: () -> Long,
    private val onWindowEnd: suspend () -> Unit = {},
    private val step: suspend () -> DiscoveryWindowResult
) {
    private val execution = Mutex()
    @Volatile private var deadline: Long? = null
    val windowOpen: Boolean get() = deadline?.let { monotonicMillis() < it } == true

    /** Publish local cache inventory before potentially slow structural network work. */
    suspend fun prepareInventory(action: suspend () -> Unit) = execution.withLock { action() }

    suspend fun runWindow(maximumMillis: Long): DiscoveryWindowResult {
        require(maximumMillis > 0)
        return execution.withLock {
            deadline = monotonicMillis() + maximumMillis
            var primaryFailure: Throwable? = null
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!windowOpen) return@withLock DiscoveryWindowResult.PENDING
                    val result = step()
                    if (result != DiscoveryWindowResult.PENDING) return@withLock result
                    yield()
                }
                @Suppress("UNREACHABLE_CODE") DiscoveryWindowResult.PENDING
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                deadline = null
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    try {
                        onWindowEnd()
                    } catch (cleanupFailure: Throwable) {
                        val original = primaryFailure
                        if (original == null) throw cleanupFailure
                        if (original !== cleanupFailure) original.addSuppressed(cleanupFailure)
                    }
                }
            }
        }
    }
}
