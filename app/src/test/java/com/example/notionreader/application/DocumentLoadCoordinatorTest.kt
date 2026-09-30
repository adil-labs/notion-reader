package com.example.notionreader.application

import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DocumentLoadCoordinatorTest {

    private val sampleDoc = ReaderDocument.fromChunkTexts(
        pageId = "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
        title = "Test Page",
        texts = listOf("Paragraph 1")
    )

    @Test
    fun canonicalizePageIdMatchesUuidVariantsAndUrls() {
        val dashed = "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d"
        val undashed = "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d"
        val url = "https://www.notion.so/Workspace/Architecture-1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d?pvs=4"

        val expected = "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d"
        assertEquals(expected, DocumentLoadCoordinator.canonicalizePageId(dashed))
        assertEquals(expected, DocumentLoadCoordinator.canonicalizePageId(undashed))
        assertEquals(expected, DocumentLoadCoordinator.canonicalizePageId(url))
    }

    @Test
    fun concurrentLoadsForSamePageDeduplicateToSingleExecution() = runBlocking {
        val coordinator = DocumentLoadCoordinator()
        val executionCount = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        val jobs = (1..5).map {
            async(Dispatchers.Default) {
                coordinator.load(sampleDoc.pageId) {
                    executionCount.incrementAndGet()
                    gate.await()
                    sampleDoc
                }
            }
        }

        // Allow some time for all callers to queue on coordinator
        delay(50)
        gate.complete(Unit)

        val results = jobs.awaitAll()
        assertEquals(5, results.size)
        results.forEach { assertEquals(sampleDoc.title, it.title) }
        assertEquals(1, executionCount.get()) // Exactly one fetch was executed!
        assertEquals(0, coordinator.inFlightCount)
    }

    @Test
    fun cancellingOneCallerDoesNotCancelUnderlyingLoaderForOtherCallers() = runBlocking {
        val coordinator = DocumentLoadCoordinator()
        val loaderStarted = CompletableDeferred<Unit>()
        val finishLoader = CompletableDeferred<Unit>()

        var loaderFinished = false

        // Caller 1 starts the load
        val caller1 = async(Dispatchers.Default) {
            coordinator.load(sampleDoc.pageId) {
                loaderStarted.complete(Unit)
                finishLoader.await()
                loaderFinished = true
                sampleDoc
            }
        }

        loaderStarted.await()

        // Caller 2 joins the in-flight load
        val caller2 = async(Dispatchers.Default) {
            coordinator.load(sampleDoc.pageId) {
                fail("Caller 2 should have joined caller 1 rather than starting a new loader")
                sampleDoc
            }
        }

        delay(30)
        // Now cancel caller 1 (e.g. prefetch coroutine cancelled because user switched notes)
        caller1.cancelAndJoin()

        // Release the underlying loader
        finishLoader.complete(Unit)

        // Caller 2 should successfully complete with the result!
        val result2 = caller2.await()
        assertEquals(sampleDoc.title, result2.title)
        assertTrue(loaderFinished)
        assertEquals(0, coordinator.inFlightCount)
    }

    @Test
    fun forceRefreshStartsNewExecutionWhenNonForceLoadIsInFlight() = runBlocking {
        val coordinator = DocumentLoadCoordinator()
        val executions = AtomicInteger(0)
        val nonForceGate = CompletableDeferred<Unit>()
        val forceGate = CompletableDeferred<Unit>()

        val nonForceCaller = async(Dispatchers.Default) {
            coordinator.load(sampleDoc.pageId, forceRefresh = false) {
                executions.incrementAndGet()
                nonForceGate.await()
                sampleDoc
            }
        }

        delay(30)

        val forceCaller = async(Dispatchers.Default) {
            coordinator.load(sampleDoc.pageId, forceRefresh = true) {
                executions.incrementAndGet()
                forceGate.await()
                sampleDoc.copy(title = "Force Refreshed Page")
            }
        }

        delay(30)
        nonForceGate.complete(Unit)
        forceGate.complete(Unit)

        val nonForceResult = nonForceCaller.await()
        val forceResult = forceCaller.await()

        assertEquals("Test Page", nonForceResult.title)
        assertEquals("Force Refreshed Page", forceResult.title)
        assertEquals(2, executions.get()) // Two executions because forceRefresh supersedes
        assertEquals(0, coordinator.inFlightCount)
    }

    @Test
    fun nonForceCallersCanJoinAnInFlightForceRefresh() = runBlocking {
        val coordinator = DocumentLoadCoordinator()
        val executions = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        val forceCaller = async(Dispatchers.Default) {
            coordinator.load(sampleDoc.pageId, forceRefresh = true) {
                executions.incrementAndGet()
                gate.await()
                sampleDoc.copy(title = "Fresh Page")
            }
        }

        delay(30)

        val nonForceCaller = async(Dispatchers.Default) {
            coordinator.load(sampleDoc.pageId, forceRefresh = false) {
                fail("Should join existing in-flight forceRefresh")
                sampleDoc
            }
        }

        delay(30)
        gate.complete(Unit)

        val forceResult = forceCaller.await()
        val nonForceResult = nonForceCaller.await()

        assertEquals("Fresh Page", forceResult.title)
        assertEquals("Fresh Page", nonForceResult.title)
        assertEquals(1, executions.get())
        assertEquals(0, coordinator.inFlightCount)
    }

    @Test
    fun loaderFailurePropagatesAndCleansUpMapForSubsequentRetry() = runBlocking {
        val coordinator = DocumentLoadCoordinator()
        val shouldFail = AtomicInteger(1)

        try {
            coordinator.load(sampleDoc.pageId) {
                if (shouldFail.get() == 1) {
                    throw ReaderLoadException(ReaderErrorCategory.MALFORMED_RESPONSE, "Failed network load")
                }
                sampleDoc
            }
            fail("Expected exception")
        } catch (e: ReaderLoadException) {
            assertEquals(ReaderErrorCategory.MALFORMED_RESPONSE, e.category)
        }

        // Map must be cleaned up, so a retry can succeed
        assertEquals(0, coordinator.inFlightCount)

        shouldFail.set(0)
        val caller2 = coordinator.load(sampleDoc.pageId) {
            sampleDoc
        }
        assertEquals(sampleDoc.title, caller2.title)
    }

    @Test
    fun prefetchLoadIsPreemptedWhenPlaybackLoadArrivesForDifferentPage() = runBlocking {
        val coordinator = DocumentLoadCoordinator()
        val prefetchStarted = CompletableDeferred<Unit>()
        var prefetchCancelled = false

        val prefetchJob = async(Dispatchers.Default) {
            try {
                coordinator.load(
                    pageId = "page-prefetch-1",
                    purpose = com.example.notionreader.domain.document.DocumentLoadPurpose.PREFETCH
                ) {
                    prefetchStarted.complete(Unit)
                    delay(5000) // Simulating long slow background fetch
                    sampleDoc.copy(pageId = "page-prefetch-1")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                prefetchCancelled = true
                throw e
            }
        }

        prefetchStarted.await()
        assertEquals(1, coordinator.prefetchInFlightCount)

        // Foreground playback load arrives for a different page
        val playbackResult = coordinator.load(
            pageId = "page-playback-2",
            purpose = com.example.notionreader.domain.document.DocumentLoadPurpose.PLAYBACK
        ) {
            sampleDoc.copy(pageId = "page-playback-2", title = "Playback Note")
        }

        assertEquals("Playback Note", playbackResult.title)
        // Background prefetch load must have been preempted
        try {
            prefetchJob.await()
            fail("Prefetch job should have been cancelled by playback load")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertTrue(prefetchCancelled)
        }
        assertEquals(0, coordinator.prefetchInFlightCount)
    }

    @Test
    fun prefetchLoadIsPromotedAndJoinedWhenPlaybackLoadArrivesForSamePage() = runBlocking {
        val coordinator = DocumentLoadCoordinator()
        val executions = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        // Background prefetch begins for page A
        val prefetchCaller = async(Dispatchers.Default) {
            coordinator.load(
                pageId = sampleDoc.pageId,
                purpose = com.example.notionreader.domain.document.DocumentLoadPurpose.PREFETCH
            ) {
                executions.incrementAndGet()
                gate.await()
                sampleDoc
            }
        }

        delay(30)
        // Foreground playback arrives for the exact same page A
        val playbackCaller = async(Dispatchers.Default) {
            coordinator.load(
                pageId = sampleDoc.pageId,
                purpose = com.example.notionreader.domain.document.DocumentLoadPurpose.PLAYBACK
            ) {
                fail("Playback should join the in-flight prefetch instead of re-fetching")
                sampleDoc
            }
        }

        delay(30)
        // Now trigger preemption of prefetch loads for an unrelated page - sampleDoc load must NOT be cancelled
        // because it was promoted to PLAYBACK!
        coordinator.cancelPrefetchLoadsExcept("unrelated-page")

        gate.complete(Unit)
        val prefetchDoc = prefetchCaller.await()
        val playbackDoc = playbackCaller.await()

        assertEquals(sampleDoc.title, prefetchDoc.title)
        assertEquals(sampleDoc.title, playbackDoc.title)
        assertEquals(1, executions.get())
    }
}

