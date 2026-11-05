package com.example.notionreader.application

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryWorkOrderTest {
    @Test fun cachedDiscoveryRunsBeforeAStalledStructuralRequest() = runBlocking {
        val discoveryRan = CompletableDeferred<Unit>()
        val structuralStarted = CompletableDeferred<Unit>()
        val blockedNetwork = CompletableDeferred<Unit>()
        val job = launch {
            discoveryBeforeStructuralWork(
                discovery = { discoveryRan.complete(Unit) },
                structural = { structuralStarted.complete(Unit); blockedNetwork.await() })
        }
        structuralStarted.await()
        assertTrue("Structural network work must not hold back cached discovery", discoveryRan.isCompleted)
        job.cancelAndJoin()
    }
}
