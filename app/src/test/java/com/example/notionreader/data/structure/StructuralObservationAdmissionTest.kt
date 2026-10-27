package com.example.notionreader.data.structure

import com.example.notionreader.application.StructuralKnowledgeCoordinator
import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.domain.document.*
import com.example.notionreader.domain.structure.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicBoolean

class StructuralObservationAdmissionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val source = "11111111-1111-1111-1111-111111111111"
    private val target = "22222222-2222-2222-2222-222222222222"
    private fun record(id: String) = StructuralPageRecord(StructuralPage(id, "Public fixture", StructuralParent.Workspace,
        sourceIdentity = "fixture"), emptyList(), emptyList(), emptyList(), sourceIdentity = "fixture")

    @Test fun anUnchangedReferencedTargetKeepsItsIntrinsicLimitBlock() = runTest {
        val store = FileStructuralKnowledgeStore(temporary.newFolder())
        store.initSource("fixture", 1)
        store.blockPageWork(target, "revision-one", "fixture", 1)
        val header = record(target).page.copy(sourceRevision = "revision-one", syncStatus = SyncCompleteness.UNKNOWN)
        assertTrue(store.applyObservation(StructuralObservation(record(source), targetPages = listOf(header)), "fixture", 1))
        assertEquals("revision-one", store.blockedSourceRevision(target))
        assertTrue(store.readPendingWork().none { it.pageId == target })
        assertNotNull(store.readPage(source))
        store.applyObservation(StructuralObservation(record(source), targetPages = listOf(header.copy(sourceRevision = "revision-two"))), "fixture", 1)
        assertNull(store.blockedSourceRevision(target))
        assertTrue(store.readPendingWork().any { it.pageId == target })
    }

    @Test fun anOversizedRelatedHeaderCannotBlockItsValidSource() = runTest {
        val store = FileStructuralKnowledgeStore(temporary.newFolder())
        store.initSource("fixture", 1)
        val header = record(target).page.copy(title = "x".repeat(4 * 1024 * 1024), sourceRevision = "large-target")
        assertTrue(store.applyObservation(StructuralObservation(record(source), targetPages = listOf(header)), "fixture", 1))
        assertNotNull(store.readPage(source))
        assertNull(store.blockedSourceRevision(source))
        assertEquals("large-target", store.blockedSourceRevision(target))
    }

    @Test fun aWithdrawnObservationCannotRenewItsTicketAfterAnExternalClear() = runTest {
        val store = FileStructuralKnowledgeStore(temporary.newFolder())
        store.initSource("fixture", 1)
        store.clearSource("fixture", 1)
        assertFalse(store.applyObservation(StructuralObservation(record(source)), "fixture", 1))
        assertNull(store.readPage(source))
    }

    @Test fun aClearDuringAtomicAdmissionWinsOverTheSourceAndItsRelatedWithdrawals() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val armed = AtomicBoolean(false)
        val gate = object : StorageWriteGate {
            override suspend fun <T> write(category: StorageCategory, additionalPeakBytes: Long, block: suspend () -> T): T {
                if (armed.get()) { entered.complete(Unit); release.await() }
                return block()
            }
        }
        val store = FileStructuralKnowledgeStore(temporary.newFolder(), writeGate = gate)
        store.initSource("fixture", 1)
        store.writePage(record(target), "fixture", 1)
        val cache = FileDocumentCache(temporary.newFolder())
        val worker = StructuralKnowledgeCoordinator.observing(store, store, cache, { "fixture" }) { _, _ ->
            StructuralObservation(record(source), targetPages = listOf(record(target).page.copy(title = "",
                accessibilityState = PageAccessibilityState.RESTRICTED_PERMISSION)))
        }
        worker.request(source)
        armed.set(true)
        val work = async { worker.runWindow() }
        entered.await(); cache.clear(); release.complete(Unit); work.await()
        assertNull(worker.getPage(source)); assertNull(worker.getPage(target))
        assertTrue(store.readPendingWork().isEmpty())
        assertTrue(store.readIndex().pages.isEmpty())
    }
}
