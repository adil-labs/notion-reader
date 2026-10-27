package com.example.notionreader.application

import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.data.structure.FileStructuralKnowledgeStore
import com.example.notionreader.domain.settings.DownloadNetworkPolicy
import com.example.notionreader.domain.settings.ReaderSettings
import com.example.notionreader.domain.structure.PageAccessibilityState
import com.example.notionreader.domain.structure.StructuralPage
import com.example.notionreader.domain.structure.StructuralPageRecord
import com.example.notionreader.domain.structure.StructuralParent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicBoolean

class StructuralWorkSchedulerTest {
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
}
