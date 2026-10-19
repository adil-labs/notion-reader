package com.example.notionreader.application

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryWorkCoordinatorTest {
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
}
