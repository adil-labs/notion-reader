package com.example.notionreader.application

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryWorkCoordinatorTest {
    @Test fun startupInventoryCannotOverlapAScheduledDiscoveryWindow() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var windowStarted = false
        val coordinator = DiscoveryWorkCoordinator({ 0L }) {
            windowStarted = true
            DiscoveryWindowResult.COMPLETE
        }
        val inventory = launch { coordinator.prepareInventory { entered.complete(Unit); release.await() } }
        entered.await()
        val window = launch { coordinator.runWindow(100) }
        yield()
        assertFalse("A scheduled window must wait for source reconciliation", windowStarted)
        release.complete(Unit); inventory.join(); window.join()
        assertTrue(windowStarted)
    }
    @Test fun aSearchFailureDoesNotShortenAutomaticProcessingWindows() = runBlocking {
        val failure = "Semantic search failed. Try again or rebuild the index."
        val state = DiscoveryState(paused = false, searchFailure = failure, message = failure)
        var steps = 0
        val coordinator = DiscoveryWorkCoordinator({ 0L }) {
            discoveryWindowResult(true, state, true, ++steps < 3)
        }
        assertEquals(DiscoveryWindowResult.COMPLETE, coordinator.runWindow(100))
        assertEquals(3, steps)
        assertEquals(failure, state.searchFailure)
    }

    @Test fun processingFailuresStillDeferAutomaticWork() {
        val state = DiscoveryState(paused = false, indexFailure = "Storage is full")
        assertEquals(DiscoveryWindowResult.DEFERRED, discoveryWindowResult(true, state, true, true))
    }
    @Test fun aWindowCompletesPendingRoundsWithoutAnotherUserRequest() = runBlocking {
        var steps = 0
        val coordinator = DiscoveryWorkCoordinator({ 0L }) {
            if (++steps < 4) DiscoveryWindowResult.PENDING else DiscoveryWindowResult.COMPLETE
        }
        assertEquals(DiscoveryWindowResult.COMPLETE, coordinator.runWindow(100))
        assertEquals(4, steps)
        assertFalse(coordinator.windowOpen)
    }

    @Test fun simultaneousWindowsNeverOverlapAndCancellationReleasesThePermit() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var active = 0; var maximum = 0
        val coordinator = DiscoveryWorkCoordinator({ 0L }) {
            active++; maximum = maxOf(maximum, active)
            try { entered.complete(Unit); release.await(); DiscoveryWindowResult.COMPLETE }
            finally { active-- }
        }
        val first = launch { coordinator.runWindow(100) }
        entered.await()
        val second = launch { coordinator.runWindow(100) }
        yield(); assertEquals(1, maximum)
        first.cancelAndJoin(); release.complete(Unit); second.join()
        assertEquals(1, maximum); assertFalse(coordinator.windowOpen)
    }

    @Test fun expiredWindowsKeepPendingWorkAndDeferralsDoNotSpin() = runBlocking {
        var now = 0L; var steps = 0
        val coordinator = DiscoveryWorkCoordinator({ now }) {
            steps++; now += 5; DiscoveryWindowResult.PENDING
        }
        assertEquals(DiscoveryWindowResult.PENDING, coordinator.runWindow(10))
        assertEquals(2, steps)
        val deferred = DiscoveryWorkCoordinator({ 0L }) { DiscoveryWindowResult.DEFERRED }
        assertEquals(DiscoveryWindowResult.DEFERRED, deferred.runWindow(10))
        assertFalse(deferred.windowOpen)
    }

    @Test fun windowTeardownRunsOnAllTerminalResultsWithoutPerStepChurn() = runBlocking {
        var cleanups = 0
        var steps = 0
        val coordinator = DiscoveryWorkCoordinator({ 0L }, onWindowEnd = { cleanups++ }) {
            if (++steps < 4) DiscoveryWindowResult.PENDING else DiscoveryWindowResult.COMPLETE
        }
        assertEquals(DiscoveryWindowResult.COMPLETE, coordinator.runWindow(100))
        assertEquals(4, steps)
        assertEquals("Teardown must run exactly once at window end, not per step", 1, cleanups)

        val deferredCoordinator = DiscoveryWorkCoordinator({ 0L }, onWindowEnd = { cleanups++ }) {
            DiscoveryWindowResult.DEFERRED
        }
        assertEquals(DiscoveryWindowResult.DEFERRED, deferredCoordinator.runWindow(100))
        assertEquals(2, cleanups)

        var now = 0L
        val expiredCoordinator = DiscoveryWorkCoordinator({ now }, onWindowEnd = { cleanups++ }) {
            now += 200; DiscoveryWindowResult.PENDING
        }
        assertEquals(DiscoveryWindowResult.PENDING, expiredCoordinator.runWindow(100))
        assertEquals(3, cleanups)
    }

    @Test fun windowTeardownRunsOnCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val teardownRan = CompletableDeferred<Unit>()
        val coordinator = DiscoveryWorkCoordinator({ 0L }, onWindowEnd = { teardownRan.complete(Unit) }) {
            entered.complete(Unit)
            release.await()
            DiscoveryWindowResult.COMPLETE
        }
        val job = launch { coordinator.runWindow(100) }
        entered.await()
        job.cancelAndJoin()
        assertTrue("Teardown must execute when window execution is cancelled", teardownRan.isCompleted)
        assertFalse(coordinator.windowOpen)
    }
    @Test fun teardownFailureCannotBeReportedAsACompletedWindow() = runBlocking {
        val failure = IllegalStateException("Model cleanup failed")
        val coordinator = DiscoveryWorkCoordinator({ 0L }, onWindowEnd = { throw failure }) {
            DiscoveryWindowResult.COMPLETE
        }
        val result = runCatching { coordinator.runWindow(100) }
        assertTrue("The cleanup failure must remain observable", result.exceptionOrNull() is IllegalStateException)
        assertEquals(failure.message, result.exceptionOrNull()?.message)
        assertFalse(coordinator.windowOpen)
    }
    @Test fun teardownFailureDoesNotHideAnEarlierProcessingFailure() = runBlocking {
        val processing = IllegalArgumentException("Processing failed")
        val cleanup = IllegalStateException("Model cleanup failed")
        val coordinator = DiscoveryWorkCoordinator({ 0L }, onWindowEnd = { throw cleanup }) { throw processing }
        val result = runCatching { coordinator.runWindow(100) }
        assertSame(processing, result.exceptionOrNull())
        assertEquals(listOf(cleanup), processing.suppressed.toList())
        assertFalse(coordinator.windowOpen)
    }
}
