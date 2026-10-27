package com.example.notionreader.data.structure

import com.example.notionreader.application.StructuralKnowledgeCoordinator
import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.data.notion.*
import com.example.notionreader.domain.*
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.structure.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class StructuralRecoveryIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val source = "11111111-1111-1111-1111-111111111111"
    private val target = "22222222-2222-2222-2222-222222222222"
    private val unrelated = "33333333-3333-3333-3333-333333333333"
    private val block = "44444444-4444-4444-4444-444444444444"
    private val child = "55555555-5555-5555-5555-555555555555"
    private val identity = "public-fixture"
    private fun record(id: String, title: String = "Public fixture", revision: String = "one") = StructuralPageRecord(
        StructuralPage(id, title, StructuralParent.Workspace, sourceIdentity = identity, sourceRevision = revision),
        emptyList(), emptyList(), emptyList(), sourceIdentity = identity)

    private inner class Api : NotionApi {
        var targetFailure: ReaderErrorCategory? = null
        var targetTrashed = false
        var childFailure = false
        var nested = false
        var sourceRequests = 0
        override suspend fun retrievePage(pageId: String): NotionPage {
            if (pageId == source) {
                sourceRequests++
                return NotionPage(source, lastEditedTime = "2026-10-27T01:00:00Z", properties = mapOf(
                    "title" to NotionProperty(type = "title", title = listOf(NotionRichText(plainText = "Public source"))),
                    "References" to NotionProperty(type = "relation", relation = listOf(NotionRelationTarget(target)))))
            }
            targetFailure?.let { throw ReaderLoadException(it) }
            return NotionPage(target, inTrash = targetTrashed)
        }
        override suspend fun retrieveBlockChildren(blockId: String): List<NotionBlock> {
            if (blockId == source) return listOf(NotionBlock(block, if (nested) "toggle" else "paragraph", hasChildren = nested,
                paragraph = NotionTextBlock(listOf(NotionRichText(plainText = "Public body")))))
            if (childFailure) throw ReaderLoadException(ReaderErrorCategory.NETWORK_UNAVAILABLE)
            return listOf(NotionBlock(child, "paragraph", paragraph = NotionTextBlock(listOf(NotionRichText(plainText = "Nested body")))))
        }
        override suspend fun searchMostRecentPage(): NotionPage? = null
        override suspend fun searchPages(): List<NotionPage> = emptyList()
        override fun close() = Unit
    }
    private fun worker(store: FileStructuralKnowledgeStore, cache: FileDocumentCache, api: Api) =
        StructuralKnowledgeCoordinator.observing(store, store, cache, { identity }) { id, owner ->
            val result = NotionStructuralCollector(api).collectPageStructure(id, owner)
            StructuralObservation(result.pageRecord, targetPages = result.targetPages)
        }
    private suspend fun cached(cache: FileDocumentCache, id: String) {
        cache.put(CachedDocument(ReaderDocument(id, "Public offline fixture"), null, Instant.EPOCH))
    }

    @Test fun aNeverAccessible404TargetDoesNotCreateADocumentDeletionEvent() = runTest {
        val store = FileStructuralKnowledgeStore(temporary.newFolder())
        val cache = FileDocumentCache(temporary.newFolder())
        val api = Api().apply { targetFailure = ReaderErrorCategory.PAGE_NOT_FOUND }
        val worker = worker(store, cache, api)
        worker.request(source); worker.runWindow()
        assertNotNull(worker.getPage(source))
        assertEquals(PageAccessibilityState.NOT_FOUND_OR_UNSHARED, worker.getPage(target)!!.accessibilityState)
        assertTrue(cache.invalidations.value.isEmpty())
        assertEquals(1, api.sourceRequests)
        assertTrue(store.readPendingWork().isEmpty())
    }

    @Test fun forbiddenMissingAndTrashedTargetsPreserveUnrelatedOfflineNotes() = runTest {
        for ((failure, trashed, expected) in listOf(
            Triple(ReaderErrorCategory.PERMISSION_DENIED, false, PageAccessibilityState.RESTRICTED_PERMISSION),
            Triple(ReaderErrorCategory.PAGE_NOT_FOUND, false, PageAccessibilityState.NOT_FOUND_OR_UNSHARED),
            Triple(null, true, PageAccessibilityState.CONFIRMED_TRASHED))) {
            val store = FileStructuralKnowledgeStore(temporary.newFolder()).also { it.initSource(identity, 1) }
            val cache = FileDocumentCache(temporary.newFolder())
            store.writePage(record(target), identity, 1); store.writePage(record(unrelated), identity, 1)
            cached(cache, target); cached(cache, unrelated)
            val api = Api().apply { targetFailure = failure; targetTrashed = trashed }
            val worker = worker(store, cache, api)
            worker.request(source); worker.runWindow()
            assertNotNull(worker.getPage(source))
            assertEquals(expected, worker.getPage(target)!!.accessibilityState)
            assertNull(cache.get(target))
            assertNotNull(cache.get(unrelated)); assertEquals("Public fixture", worker.getPage(unrelated)!!.title)
            assertTrue(worker.getOutgoingRelationships(source).any { it.kind == ExplicitRelationshipKind.NOTION_RELATION })
            assertEquals(1, api.sourceRequests)
        }
    }

    @Test fun aTransientTargetFailureDoesNotWithdrawItsLastKnownReadableEvidence() = runTest {
        val store = FileStructuralKnowledgeStore(temporary.newFolder()).also { it.initSource(identity, 1) }
        val cache = FileDocumentCache(temporary.newFolder())
        store.writePage(record(target), identity, 1); cached(cache, target)
        val api = Api().apply { targetFailure = ReaderErrorCategory.NETWORK_UNAVAILABLE }
        val worker = worker(store, cache, api)
        worker.request(source); worker.runWindow()
        assertEquals(PageAccessibilityState.ACCESSIBLE, worker.getPage(target)!!.accessibilityState)
        assertNotNull(cache.get(target)); assertTrue(cache.invalidations.value.isEmpty())
        assertEquals(TargetResolutionStatus.UNRESOLVED_PERMITTED,
            worker.getOutgoingRelationships(source).single { it.kind == ExplicitRelationshipKind.NOTION_RELATION }.resolutionStatus)
    }

    @Test fun firstPartialNestedContentRestoresThenACompleteRetryClearsThePendingStatus() = runTest {
        val directory = temporary.newFolder()
        val cache = FileDocumentCache(temporary.newFolder())
        val store = FileStructuralKnowledgeStore(directory)
        val api = Api().apply { nested = true; childFailure = true }
        val first = worker(store, cache, api)
        first.request(source); first.runWindow()
        assertEquals(SyncCompleteness.PARTIAL_TRAVERSAL_INCOMPLETE, first.getSyncStatus(source))
        assertEquals(ChildCompleteness.PAGINATION_INCOMPLETE, first.getBlocks(source).single().childCompleteness)
        val restored = FileStructuralKnowledgeStore(directory)
        assertTrue(restored.readPendingWork().any { it.pageId == source })
        api.childFailure = false
        val second = worker(restored, cache, api)
        second.runWindow(maxPages = 2)
        assertEquals(SyncCompleteness.COMPLETE, second.getSyncStatus(source))
        assertEquals(listOf(block, child), second.getBlocks(source).map { it.id })
        assertFalse(restored.readPendingWork().any { it.pageId == source })
    }

    @Test fun anOversizedPageBlocksAutomaticRetriesUntilItsRevisionChanges() = runTest {
        val directory = temporary.newFolder()
        val store = FileStructuralKnowledgeStore(directory)
        val cache = FileDocumentCache(temporary.newFolder())
        var title = "x".repeat(4 * 1024 * 1024)
        var revision = "one"
        var collections = 0
        val worker = StructuralKnowledgeCoordinator(store, store, cache, { identity }) { id, _ ->
            collections++; record(id, title, revision)
        }
        worker.request(source); worker.runWindow()
        assertEquals(SyncCompleteness.LIMIT_EXCEEDED, worker.getSyncStatus(source))
        assertTrue(store.readPendingWork().isEmpty()); assertNull(store.readPage(source))
        val restored = FileStructuralKnowledgeStore(directory)
        assertEquals(SyncCompleteness.LIMIT_EXCEEDED, restored.getSyncStatus(source))
        assertFalse(worker.requestMetadataIfChanged(record(source, "Title", "one").page, identity, store.readIndex().sourceEpoch))
        worker.runWindow(); assertEquals(1, collections)
        title = "Now supported"; revision = "two"
        assertTrue(worker.requestMetadataIfChanged(record(source, title, revision).page, identity, store.readIndex().sourceEpoch))
        worker.runWindow()
        assertEquals(2, collections); assertEquals(SyncCompleteness.COMPLETE, worker.getSyncStatus(source))
        assertEquals(title, worker.getPage(source)!!.title)
    }
}
