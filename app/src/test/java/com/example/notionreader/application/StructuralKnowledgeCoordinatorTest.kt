package com.example.notionreader.application

import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.data.structure.FileStructuralKnowledgeStore
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
import com.example.notionreader.domain.document.CacheRemovalReason
import com.example.notionreader.domain.structure.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StructuralKnowledgeCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "11111111-1111-1111-1111-111111111111"
    private val other = "22222222-2222-2222-2222-222222222222"
    private fun record(pageId: String, source: String) = StructuralPageRecord(
        StructuralPage(pageId, "Public fixture", StructuralParent.Workspace, sourceIdentity = source,
            sourceRevision = "one"), emptyList(), emptyList(), emptyList(), sourceIdentity = source)

    @Test fun relatedMetadataSharesThePageAdmissionTicketAndRestoresOffline() = runTest {
        val directory = temporary.newFolder()
        val store = FileStructuralKnowledgeStore(directory)
        val cache = FileDocumentCache(temporary.newFolder())
        val containerId = "33333333-3333-3333-3333-333333333333"
        val container = StructuralContainer(containerId, StructuralContainerKind.DATABASE, "Fixture database", StructuralParent.Workspace, "source")
        val worker = StructuralKnowledgeCoordinator.observing(store, store, cache, { "source" }) { pageId, source ->
            StructuralObservation(record(pageId, source), listOf(container),
                listOf(StructuralPage(other, "Fixture target", StructuralParent.Workspace, sourceIdentity = source, syncStatus = SyncCompleteness.UNKNOWN)))
        }
        worker.request(id); worker.runWindow()
        val restored = FileStructuralKnowledgeStore(directory)
        assertEquals(container, restored.getContainer(containerId))
        assertEquals("Fixture target", restored.getPage(other)!!.title)
        assertEquals(listOf(other), restored.readPendingWork().map { it.pageId })
    }

    @Test fun oneObservationPurgesAllWithdrawnTargetsBeforeRetryingItsPage() = runTest {
        val store = FileStructuralKnowledgeStore(temporary.newFolder())
        store.initSource("source", 1)
        val cache = FileDocumentCache(temporary.newFolder())
        val second = "33333333-3333-3333-3333-333333333333"
        val containerId = "44444444-4444-4444-4444-444444444444"
        val container = StructuralContainer(containerId, StructuralContainerKind.DATABASE, "Private title", StructuralParent.Workspace, "source")
        store.writePage(record(other, "source"), "source", 1)
        store.writePage(record(second, "source"), "source", 1)
        store.writeContainer(container, "source", 1)
        val worker = StructuralKnowledgeCoordinator.observing(store, store, cache, { "source" }) { pageId, source ->
            StructuralObservation(record(pageId, source), listOf(container.copy(accessibilityState = PageAccessibilityState.RESTRICTED_PERMISSION)),
                listOf(other, second).map { StructuralPage(it, "", StructuralParent.Unknown("unavailable", null), sourceIdentity = source,
                    accessibilityState = PageAccessibilityState.CONFIRMED_TRASHED, syncStatus = SyncCompleteness.UNKNOWN) })
        }
        worker.request(id); worker.runWindow()
        assertEquals("", worker.getPage(other)!!.title)
        assertEquals(PageAccessibilityState.CONFIRMED_TRASHED, worker.getPage(second)!!.accessibilityState)
        assertNull(worker.getContainer(containerId))
        assertNull(store.readPage(other)); assertNull(store.readPage(second))
        assertTrue(store.readPendingWork().any { it.pageId == id })
        worker.runWindow()
        assertNotNull(worker.getPage(id))
        assertTrue(store.readPendingWork().isEmpty())
    }

    @Test fun aCallbackQueuedBeforeClearCannotRequestWorkInTheNewEpoch() = runTest {
        val store = FileStructuralKnowledgeStore(temporary.newFolder())
        val cache = FileDocumentCache(temporary.newFolder())
        val worker = StructuralKnowledgeCoordinator(store, store, cache, { "source" }, ::record)
        worker.refreshLifecycle()
        val ticket = store.readIndex()
        cache.clear()
        assertFalse(worker.requestIfCurrent(id, ticket.sourceIdentity, ticket.sourceEpoch, "late-callback"))
        assertTrue(store.readPendingWork().isEmpty())
    }

    @Test fun aCacheClearDuringAQueryCannotReturnItsStalePrivateResult() = runTest {
        val store = FileStructuralKnowledgeStore(temporary.newFolder())
        val cache = FileDocumentCache(temporary.newFolder())
        val original = StructuralKnowledgeCoordinator(store, store, cache, { "source" }, ::record)
        original.request(id); original.runWindow()
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val delayedQueries = object : StructuralKnowledgeRepository by store {
            override suspend fun getPage(pageId: String): StructuralPage? {
                val result = store.getPage(pageId)
                started.complete(Unit); response.await()
                return result
            }
        }
        val worker = StructuralKnowledgeCoordinator(store, delayedQueries, cache, { "source" }, ::record)
        val result = async(start = CoroutineStart.UNDISPATCHED) { worker.getPage(id) }
        started.await(); cache.clear(); response.complete(Unit)
        assertNull(result.await())
    }

    @Test fun queuedWorkRestoresWithoutDependingOnAnAiModel() = runTest {
        val directory = temporary.newFolder()
        val cache = FileDocumentCache(temporary.newFolder())
        val first = FileStructuralKnowledgeStore(directory)
        StructuralKnowledgeCoordinator(first, first, cache, { "source" }, ::record).request(id)
        val restored = FileStructuralKnowledgeStore(directory)
        val worker = StructuralKnowledgeCoordinator(restored, restored, cache, { "source" }, ::record)
        worker.runWindow(1)
        assertEquals("Public fixture", worker.getPage(id)!!.title)
        assertTrue(restored.readPendingWork().isEmpty())
    }

    @Test fun aCacheClearDuringCollectionCannotRepublishTheResponse() = runTest {
        val store = FileStructuralKnowledgeStore(temporary.newFolder())
        val cache = FileDocumentCache(temporary.newFolder())
        val response = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val worker = StructuralKnowledgeCoordinator(store, store, cache, { "source" }) { pageId, source ->
            started.complete(Unit); response.await(); record(pageId, source)
        }
        worker.request(id)
        val job = async(start = CoroutineStart.UNDISPATCHED) { worker.runWindow(1) }
        started.await()
        cache.clear()
        response.complete(Unit)
        job.await()
        assertNull(worker.getPage(id))
        assertTrue(store.readPendingWork().isEmpty())
        assertEquals(cache.invalidations.value.maxOf { it.generation }, worker.processedInvalidationGeneration)
    }

    @Test fun permissionWithdrawalPurgesStructureEvenWhenTheDocumentWasAlreadyAbsent() = runTest {
        val store = FileStructuralKnowledgeStore(temporary.newFolder())
        val cache = FileDocumentCache(temporary.newFolder())
        val worker = StructuralKnowledgeCoordinator(store, store, cache, { "source" }, ::record)
        worker.request(id); worker.runWindow(1)
        cache.remove(id, CacheRemovalReason.PERMISSION_DENIED)
        assertEquals("", worker.getPage(id)!!.title)
        assertEquals(PageAccessibilityState.RESTRICTED_PERMISSION, worker.getPage(id)!!.accessibilityState)
        assertNull(store.readPage(id))
    }

    @Test fun aRecoverableFailureKeepsOfflineStructureAndRotatesPastTheFailedPage() = runTest {
        val store = FileStructuralKnowledgeStore(temporary.newFolder())
        val cache = FileDocumentCache(temporary.newFolder())
        var fail = false
        val calls = mutableListOf<String>()
        val worker = StructuralKnowledgeCoordinator(store, store, cache, { "source" }) { pageId, source ->
            calls += pageId
            if (fail && pageId == id) throw ReaderLoadException(ReaderErrorCategory.NETWORK_UNAVAILABLE)
            record(pageId, source)
        }
        worker.request(id); worker.runWindow(1)
        fail = true
        worker.request(id); worker.request(other)
        worker.runWindow(1); worker.runWindow(1)
        assertEquals(listOf(id, id, other), calls)
        assertEquals("Public fixture", worker.getPage(id)!!.title)
        assertEquals("Public fixture", worker.getPage(other)!!.title)
        assertEquals(listOf(id), store.readPendingWork().map { it.pageId })
    }

    @Test fun cancellingAWindowLeavesDurableWorkForTheNextProcess() = runTest {
        val store = FileStructuralKnowledgeStore(temporary.newFolder())
        val cache = FileDocumentCache(temporary.newFolder())
        val response = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val worker = StructuralKnowledgeCoordinator(store, store, cache, { "source" }) { pageId, source ->
            started.complete(Unit); response.await(); record(pageId, source)
        }
        worker.request(id)
        val job = async(start = CoroutineStart.UNDISPATCHED) { worker.runWindow(1) }
        started.await()
        job.cancel(); job.join()
        assertEquals(listOf(id), store.readPendingWork().map { it.pageId })
    }

    @Test fun oldCredentialsCannotPublishAfterASourceTransition() = runTest {
        val store = FileStructuralKnowledgeStore(temporary.newFolder())
        val cache = FileDocumentCache(temporary.newFolder())
        var source = "first"
        val response = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val worker = StructuralKnowledgeCoordinator(store, store, cache, { source }) { pageId, identity ->
            started.complete(Unit); response.await(); record(pageId, identity)
        }
        worker.request(id)
        val job = async(start = CoroutineStart.UNDISPATCHED) { worker.runWindow(1) }
        started.await()
        source = "second"
        worker.refreshLifecycle()
        response.complete(Unit); job.await()
        assertNull(worker.getPage(id))
        assertEquals("second", store.readIndex().sourceIdentity)
    }
}
