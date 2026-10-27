package com.example.notionreader.application

import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.data.structure.FileStructuralKnowledgeStore
import com.example.notionreader.domain.structure.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StructuralSourceLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "11111111-1111-1111-1111-111111111111"
    private fun record(identity: String) = StructuralPageRecord(StructuralPage(id, "Fixture", StructuralParent.Workspace,
        sourceIdentity = identity), emptyList(), emptyList(), emptyList(), sourceIdentity = identity)

    @Test fun anAuthorizationFailureWithdrawsIdleStructureWithoutRequiringAPendingPage() = runTest {
        val store = FileStructuralKnowledgeStore(temporary.newFolder())
        store.initSource("first", 1)
        store.writePage(record("first"), "first", 1)
        val worker = StructuralKnowledgeCoordinator(store, store, FileDocumentCache(temporary.newFolder()), { "first" }) { _, owner -> record(owner) }
        assertFalse(worker.hasPendingWork())
        worker.invalidateAuthorizationIfCurrent("first", 1)
        assertNull(worker.getPage(id))
        assertTrue(store.readIndex().sourceEpoch > 1)
    }

    @Test fun anOldAuthorizationFailureCannotWithdrawTheReplacementSource() = runTest {
        val store = FileStructuralKnowledgeStore(temporary.newFolder())
        store.initSource("first", 1)
        store.initSource("second", 2)
        store.writePage(record("second"), "second", 2)
        val worker = StructuralKnowledgeCoordinator(store, store, FileDocumentCache(temporary.newFolder()), { "second" }) { _, owner -> record(owner) }
        worker.invalidateAuthorizationIfCurrent("first", 1)
        assertEquals("Fixture", worker.getPage(id)!!.title)
        assertEquals("second", store.readIndex().sourceIdentity)
    }

    @Test fun resourceRotationRestoresOnlyNativeIdentityAndClearsWithItsSource() = runTest {
        val directory = temporary.newFolder()
        val store = FileStructuralKnowledgeStore(directory)
        store.initSource("first", 1)
        store.recordResourcePosition(id, "first", 1)
        val restored = FileStructuralKnowledgeStore(directory)
        assertEquals(id, restored.readResourcePosition())
        restored.clearSource("first", 1)
        restored.recordResourcePosition(id, "first", 1)
        assertNull(restored.readResourcePosition())
    }
}
