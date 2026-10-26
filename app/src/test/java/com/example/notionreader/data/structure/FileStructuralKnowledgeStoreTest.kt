package com.example.notionreader.data.structure

import com.example.notionreader.domain.document.StorageCategory
import com.example.notionreader.domain.document.StorageFailure
import com.example.notionreader.domain.document.StorageRefused
import com.example.notionreader.domain.document.StorageWriteGate
import com.example.notionreader.domain.structure.ChildCompleteness
import com.example.notionreader.domain.structure.ExplicitRelationship
import com.example.notionreader.domain.structure.ExplicitRelationshipKind
import com.example.notionreader.domain.structure.PageAccessibilityState
import com.example.notionreader.domain.structure.PendingStructuralWork
import com.example.notionreader.domain.structure.RelationshipProvenance
import com.example.notionreader.domain.structure.RelationshipTarget
import com.example.notionreader.domain.structure.StructuralBlock
import com.example.notionreader.domain.structure.StructuralEntityKind
import com.example.notionreader.domain.structure.StructuralNavigationAnchor
import com.example.notionreader.domain.structure.StructuralPage
import com.example.notionreader.domain.structure.StructuralPageRecord
import com.example.notionreader.domain.structure.StructuralParent
import com.example.notionreader.domain.structure.StructuralSection
import com.example.notionreader.domain.structure.SyncCompleteness
import com.example.notionreader.domain.structure.TargetResolutionStatus
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/**
 * JUnit 4 contract tests for [FileStructuralKnowledgeStore].
 *
 * Tests are written in failing-first order: each test targets a specific defect.
 * All IDs are valid 36-char dashed UUIDs.
 */
class FileStructuralKnowledgeStoreTest {

    @Test
    fun returningToAnOldSourceDoesNotAdmitItsOrphanedPayload() = runTest {
        val directory = tempFolder.newFolder()
        val store = FileStructuralKnowledgeStore(directory).also { it.initSource(sourceIdentityA, 1L) }
        store.writePage(sampleRecord(), sourceIdentityA, 1L)
        val payload = File(directory, "pages/$pageId.json").readBytes()
        store.initSource(sourceIdentityB, 2L)
        store.initSource(sourceIdentityA, 3L)
        File(directory, "pages/$pageId.json").writeBytes(payload)
        assertNull(FileStructuralKnowledgeStore(directory).readPage(pageId))
    }

    @Test
    fun theFirstPartialSnapshotRemainsDurablyPendingAfterRestart() = runTest {
        val directory = tempFolder.newFolder()
        val store = FileStructuralKnowledgeStore(directory).also { it.initSource(sourceIdentityA, 1L) }
        val partial = sampleRecord().let { it.copy(page = it.page.copy(syncStatus = SyncCompleteness.PARTIAL_TRAVERSAL_INCOMPLETE)) }
        store.writePage(partial, sourceIdentityA, 1L)
        val restored = FileStructuralKnowledgeStore(directory)
        assertNotNull(restored.readPage(pageId))
        assertEquals(listOf(pageId), restored.readPendingWork().map { it.pageId })
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    // Valid UUIDs used throughout
    private val pageId    = "11111111-1111-1111-1111-111111111111"
    private val parentId  = "22222222-2222-2222-2222-222222222222"
    private val blockId1  = "33333333-3333-3333-3333-333333333333"
    private val child1Id  = "44444444-4444-4444-4444-444444444444"
    private val child2Id  = "55555555-5555-5555-5555-555555555555"

    private val sourceIdentityA = "workspace-token-A"
    private val sourceIdentityB = "workspace-token-B"

    /** Derive a stable unique edge UUID from the owning page ID (avoids collisions across pages). */
    private fun edgeIdFor(pageId: String): String {
        // XOR last 12 hex chars so each page gets a distinct edge UUID
        val suffix = pageId.replace("-", "").takeLast(12).padStart(12, '0')
        return "66666666-6666-6666-6666-$suffix"
    }

    private fun sampleRecord(
        id: String = pageId,
        revision: String = "rev-1",
        parent: String = parentId,
        fingerprint: String = "fp-1",
        edgeTarget: String = parentId,
        sourceIdentity: String = sourceIdentityA
    ): StructuralPageRecord {
        val page = StructuralPage(
            id = id,
            title = "Sample Note",
            parent = StructuralParent.Page(parent),
            sourceIdentity = sourceIdentity,
            createdAt = Instant.parse("2026-10-26T10:00:00Z"),
            lastEditedAt = Instant.parse("2026-10-26T12:00:00Z"),
            accessibilityState = PageAccessibilityState.ACCESSIBLE,
            sourceRevision = revision,
            syncStatus = SyncCompleteness.COMPLETE
        )
        val block = StructuralBlock(
            id = blockId1,
            pageId = id,
            parentBlockId = null,
            type = "paragraph",
            siblingOrder = 0,
            contentFingerprint = fingerprint,
            childCompleteness = ChildCompleteness.LEAF,
            hasChildren = false
        )
        val section = StructuralSection(
            sectionKey = "$id:intro",
            pageId = id,
            headingBlockId = null,
            headingLevel = 0,
            headingTitle = null,
            headingPath = listOf("Sample Note"),
            blockIds = listOf(blockId1),
            navigationAnchor = StructuralNavigationAnchor(
                sourceBlockId = blockId1,
                readerChunkId = "$blockId1:0"
            )
        )
        val edge = ExplicitRelationship(
            id = edgeIdFor(id),
            sourceId = id,
            sourceKind = StructuralEntityKind.PAGE,
            kind = ExplicitRelationshipKind.PAGE_PARENT_CHILD,
            target = RelationshipTarget.InternalPage(edgeTarget),
            provenance = RelationshipProvenance(
                sourcePageId = id,
                sourceIdentity = sourceIdentity,
                observedRevision = revision,
                extractedAt = Instant.now()
            ),
            resolutionStatus = TargetResolutionStatus.UNRESOLVED_PERMITTED
        )
        return StructuralPageRecord(
            page = page,
            blocks = listOf(block),
            sections = listOf(section),
            outgoingRelationships = listOf(edge),
            sourceIdentity = sourceIdentity
        )
    }

    // ---------------------------------------------------------------- Compile / basic round-trip

    @Test
    fun writesAndReadsPageRecordAtomically() = runTest {
        val store = FileStructuralKnowledgeStore(directory = tempFolder.newFolder()).also { it.initSource(sourceIdentityA, 1L) }

        val record = sampleRecord()
        store.writePage(record, sourceIdentity = sourceIdentityA, sourceEpoch = 1L)

        val restored = store.readPage(pageId)
        assertNotNull(restored)
        assertEquals(pageId, restored!!.page.id)
        assertEquals("Sample Note", restored.page.title)
        assertEquals(1, restored.blocks.size)
        assertEquals(1, restored.sections.size)
        assertEquals(1, restored.outgoingRelationships.size)
    }

    // ---------------------------------------------------------------- Source identity on records

    @Test
    fun sourceIdentityPersistedOnRecord() = runTest {
        val store = FileStructuralKnowledgeStore(directory = tempFolder.newFolder()).also { it.initSource(sourceIdentityA, 1L) }

        store.writePage(sampleRecord(sourceIdentity = sourceIdentityA), sourceIdentity = sourceIdentityA, sourceEpoch = 1L)

        val restored = store.readPage(pageId)
        assertNotNull(restored)
        assertEquals(
            "Source identity must survive round-trip on record",
            sourceIdentityA,
            restored!!.sourceIdentity
        )
    }

    @Test
    fun sourceIdentityPersistedOnPendingWork() = runTest {
        val store = FileStructuralKnowledgeStore(directory = tempFolder.newFolder()).also { it.initSource(sourceIdentityA, 1L) }

        val work = PendingStructuralWork(
            pageId = pageId,
            requestedAtEpochMilli = 1000L,
            reason = "initial-sync",
            sourceIdentity = sourceIdentityA
        )
        store.recordPendingWork(work)

        val pending = store.readPendingWork()
        assertEquals(1, pending.size)
        assertEquals(
            "Source identity must survive round-trip on pending work",
            sourceIdentityA,
            pending.single().sourceIdentity
        )
    }

    // ---------------------------------------------------------------- initSource + epoch admission

    @Test
    fun initSourceEstablishesCurrentSourceAndEpoch() = runTest {
        val store = FileStructuralKnowledgeStore(directory = tempFolder.newFolder()).also { it.initSource(sourceIdentityA, 1L) }

        store.initSource(sourceIdentity = sourceIdentityA, sourceEpoch = 5L)
        // Write at the current epoch succeeds
        store.writePage(sampleRecord(), sourceIdentity = sourceIdentityA, sourceEpoch = 5L)

        val index = store.readIndex()
        assertEquals(sourceIdentityA, index.sourceIdentity)
        assertEquals(5L, index.sourceEpoch)
        assertNotNull(store.readPage(pageId))
    }

    @Test
    fun initSourceSwitchInvalidatesOldContent() = runTest {
        val store = FileStructuralKnowledgeStore(directory = tempFolder.newFolder()).also { it.initSource(sourceIdentityA, 1L) }

        // Establish source A at epoch 1, write a page
        store.initSource(sourceIdentity = sourceIdentityA, sourceEpoch = 1L)
        store.writePage(sampleRecord(sourceIdentity = sourceIdentityA), sourceIdentity = sourceIdentityA, sourceEpoch = 1L)

        // Switch to source B at epoch 10 — old content must be invalidated
        store.initSource(sourceIdentity = sourceIdentityB, sourceEpoch = 10L)

        // A stale commit from source A at epoch 1 must be rejected
        store.writePage(sampleRecord(revision = "stale-A", sourceIdentity = sourceIdentityA), sourceIdentity = sourceIdentityA, sourceEpoch = 1L)

        val restored = store.readPage(pageId)
        assertNull("Source switch must make the old payload unreadable", restored)
        val index = store.readIndex()
        assertEquals("Index must reflect new source identity", sourceIdentityB, index.sourceIdentity)
    }

    @Test
    fun switchedSourceCannotReadOldContentAfterRestartAndIndexRecovery() = runTest {
        val directory = tempFolder.newFolder()
        val store = FileStructuralKnowledgeStore(directory).also { it.initSource(sourceIdentityA, 1L) }
        store.initSource(sourceIdentityA, 1L)
        store.writePage(sampleRecord(), sourceIdentityA, 1L)
        val pageFile = File(directory, "pages/$pageId.json")
        val previousBytes = pageFile.readBytes()
        store.initSource(sourceIdentityB, 2L)
        // A failed or interrupted unlink cannot override the durable source transition.
        pageFile.writeBytes(previousBytes)
        File(directory, "structural-index.json").writeText("corrupt")
        val restored = FileStructuralKnowledgeStore(directory)
        assertNull(restored.readPage(pageId))
        assertTrue(restored.getIncomingRelationships(parentId).isEmpty())
        assertNull(restored.resolveBlockAnchor(blockId1))
    }

    @Test
    fun durableRemovalHidesAPayloadLeftBehindByInterruptedDeletion() = runTest {
        val directory = tempFolder.newFolder()
        val store = FileStructuralKnowledgeStore(directory).also { it.initSource(sourceIdentityA, 1L) }
        store.initSource(sourceIdentityA, 1L)
        store.writePage(sampleRecord(), sourceIdentityA, 1L)
        val pageFile = File(directory, "pages/$pageId.json")
        val previousBytes = pageFile.readBytes()
        store.removePage(pageId, sourceIdentityA, 1L, PageAccessibilityState.RESTRICTED_PERMISSION)
        // Reproduce an interrupted unlink: the old file survives the durable invalidation.
        pageFile.writeBytes(previousBytes)
        File(directory, "structural-index.json").writeText("corrupt")
        val restored = FileStructuralKnowledgeStore(directory)
        assertNull(restored.readPage(pageId))
        assertTrue(restored.getIncomingRelationships(parentId).isEmpty())
        assertNull(restored.resolveBlockAnchor(blockId1))
    }

    @Test
    fun aDownloadSuspendedAcrossRemovalCannotPublishButNewWorkCanRecover() = runTest {
        val store = FileStructuralKnowledgeStore(tempFolder.newFolder())
        store.initSource(sourceIdentityA, 1L)
        store.writePage(sampleRecord(), sourceIdentityA, 1L)
        val ticket = store.readIndex().sourceEpoch
        val response = CompletableDeferred<Unit>()
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            response.await()
            store.writePage(sampleRecord(revision = "late"), sourceIdentityA, ticket)
        }
        store.removePage(pageId, sourceIdentityA, ticket, PageAccessibilityState.RESTRICTED_PERMISSION)
        response.complete(Unit)
        request.await()
        assertNull(store.readPage(pageId))
        val recoveryTicket = store.readIndex().sourceEpoch
        store.writePage(sampleRecord(revision = "recovered"), sourceIdentityA, recoveryTicket)
        assertEquals("recovered", store.readPage(pageId)!!.page.sourceRevision)
    }

    @Test
    fun anObsoleteSourceCannotReactivateItself() = runTest {
        val store = FileStructuralKnowledgeStore(tempFolder.newFolder())
        store.initSource(sourceIdentityA, 1L)
        store.writePage(sampleRecord(), sourceIdentityA, 1L)
        store.initSource(sourceIdentityB, 2L)
        store.initSource(sourceIdentityA, 1L)
        store.writePage(sampleRecord(), sourceIdentityA, 1L)
        assertEquals(sourceIdentityB, store.readIndex().sourceIdentity)
        assertNull(store.readPage(pageId))
    }

    @Test
    fun partialCollectionPreservesTheCompleteSnapshotAndReportsPendingWork() = runTest {
        val store = FileStructuralKnowledgeStore(tempFolder.newFolder())
        store.initSource(sourceIdentityA, 1L)
        val complete = sampleRecord()
        store.writePage(complete, sourceIdentityA, 1L)
        store.writePage(complete.copy(page = complete.page.copy(syncStatus = SyncCompleteness.PARTIAL_TRAVERSAL_INCOMPLETE),
            blocks = emptyList(), outgoingRelationships = emptyList()), sourceIdentityA, 1L)
        assertEquals(complete.blocks, store.readPage(pageId)!!.blocks)
        assertEquals(complete.outgoingRelationships.map { it.target }, store.readPage(pageId)!!.outgoingRelationships.map { it.target })
        assertEquals(SyncCompleteness.PARTIAL_TRAVERSAL_INCOMPLETE, store.getSyncStatus(pageId))
        assertEquals(listOf(pageId), store.readPendingWork().map { it.pageId })
    }

    @Test
    fun movingAPageWithTheSameRevisionRemovesItsOldParentAndKeepsItsIdentity() = runTest {
        val store = FileStructuralKnowledgeStore(tempFolder.newFolder())
        store.initSource(sourceIdentityA, 1L)
        store.writePage(sampleRecord(), sourceIdentityA, 1L)
        store.writePage(sampleRecord(parent = child1Id), sourceIdentityA, 1L)
        assertTrue(store.getDirectChildren(parentId).isEmpty())
        assertEquals(listOf(pageId), store.getDirectChildren(child1Id).map { it.id })
    }

    @Test
    fun corruptedEligibilityFailsClosedWithoutDeletingTheContentFile() = runTest {
        val directory = tempFolder.newFolder()
        val store = FileStructuralKnowledgeStore(directory)
        store.initSource(sourceIdentityA, 1L)
        store.writePage(sampleRecord(), sourceIdentityA, 1L)
        File(directory, "source-state.json").writeText("{\"schemaVersion\":999}")
        val restored = FileStructuralKnowledgeStore(directory)
        assertTrue(runCatching { restored.readPage(pageId) }.isFailure)
        assertTrue(File(directory, "pages/$pageId.json").isFile)
    }

    @Test
    fun refusedReplacementKeepsThePriorOfflineSnapshot() = runTest {
        var limit = 1024L * 1024
        val store = FileStructuralKnowledgeStore(tempFolder.newFolder(), byteLimit = { limit })
        store.initSource(sourceIdentityA, 1L)
        store.writePage(sampleRecord(), sourceIdentityA, 1L)
        limit = 0L
        assertTrue(runCatching { store.writePage(sampleRecord(revision = "new"), sourceIdentityA, 1L) }.exceptionOrNull() is StorageRefused)
        assertEquals("rev-1", store.readPage(pageId)!!.page.sourceRevision)
    }

    @Test
    fun invalidPageIdentifiersCannotEscapeTheStoreDirectory() = runTest {
        val store = FileStructuralKnowledgeStore(tempFolder.newFolder())
        store.initSource(sourceIdentityA, 1L)
        assertNull(store.readPage("../../private"))
        store.writePage(sampleRecord(id = "../outside"), sourceIdentityA, 1L)
        assertTrue(store.readIndex().pages.isEmpty())
    }

    @Test
    fun clearingStructureInvalidatesAllInFlightWorkBeforeDeletingFiles() = runTest {
        val directory = tempFolder.newFolder()
        val store = FileStructuralKnowledgeStore(directory)
        store.initSource(sourceIdentityA, 1L)
        store.writePage(sampleRecord(), sourceIdentityA, 1L)
        store.writePage(sampleRecord(id = child1Id), sourceIdentityA, 1L)
        val oldPage = File(directory, "pages/$pageId.json").readBytes()
        store.clearSource(sourceIdentityA, 1L)
        File(directory, "pages/$pageId.json").writeBytes(oldPage)
        store.writePage(sampleRecord(revision = "late"), sourceIdentityA, 1L)
        val restored = FileStructuralKnowledgeStore(directory)
        assertNull(restored.readPage(pageId))
        assertNull(restored.readPage(child1Id))
        assertTrue(restored.getIncomingRelationships(parentId).isEmpty())
        assertTrue(restored.readPendingWork().isEmpty())
        assertTrue(restored.readIndex().sourceEpoch > 1L)
    }

    @Test
    fun lateWriteAtSameEpochAfterRemoveIsRejected() = runTest {
        val store = FileStructuralKnowledgeStore(directory = tempFolder.newFolder()).also { it.initSource(sourceIdentityA, 1L) }

        store.initSource(sourceIdentity = sourceIdentityA, sourceEpoch = 1L)
        store.writePage(sampleRecord(revision = "original"), sourceIdentity = sourceIdentityA, sourceEpoch = 1L)

        // Remove with a higher generation/epoch to invalidate
        store.removePage(pageId, sourceIdentity = sourceIdentityA, sourceEpoch = 2L, reason = PageAccessibilityState.NOT_FOUND_OR_UNSHARED)

        // Suspended write from same epoch-1 job (simulated by writing at epoch 1 after remove at epoch 2)
        store.writePage(sampleRecord(revision = "late-write"), sourceIdentity = sourceIdentityA, sourceEpoch = 1L)

        // The late write at epoch 1 must be rejected because remove bumped the page generation
        val restored = store.readPage(pageId)
        assertTrue(
            "Late write at old epoch after remove must not resurrect content",
            restored == null || restored.page.sourceRevision != "late-write"
        )
    }

    // ---------------------------------------------------------------- Epoch rejection

    @Test
    fun durableSourceEpochRejectsStaleCommitAfterReset() = runTest {
        val store = FileStructuralKnowledgeStore(directory = tempFolder.newFolder()).also { it.initSource(sourceIdentityA, 1L) }

        store.initSource(sourceIdentityA, 2L)
        // Epoch 2 committed
        store.writePage(sampleRecord(revision = "rev-epoch-2"), sourceIdentity = sourceIdentityA, sourceEpoch = 2L)

        // Stale commit from epoch 1 arrives
        store.writePage(sampleRecord(revision = "rev-epoch-1"), sourceIdentity = sourceIdentityA, sourceEpoch = 1L)

        val restored = store.readPage(pageId)
        assertEquals("rev-epoch-2", restored!!.page.sourceRevision)
    }

    // ---------------------------------------------------------------- Future schema

    @Test
    fun futureSchemaVersionIsSafelyRejectedWithoutCorruptingStore() = runTest {
        val dir = tempFolder.newFolder()
        val pagesDir = File(dir, "pages").apply { mkdirs() }
        val targetFile = File(pagesDir, "$pageId.json")

        val futureJson = """
            {
                "schemaVersion": 999,
                "page": {
                    "id": "$pageId",
                    "title": "Future Page",
                    "parentType": "workspace",
                    "accessibilityState": "ACCESSIBLE",
                    "sourceRevision": "rev-999",
                    "sourceIdentity": "",
                    "syncStatus": "COMPLETE"
                },
                "blocks": [],
                "sections": [],
                "outgoingRelationships": [],
                "sourceIdentity": ""
            }
        """.trimIndent()
        targetFile.writeText(futureJson)

        val store = FileStructuralKnowledgeStore(directory = dir).also { it.initSource(sourceIdentityA, 1L) }
        val result = store.readPage(pageId)

        assertNull("Future schema versions must be safely rejected", result)
    }

    @Test
    fun corruptIndexDoesNotResetEpochToZero() = runTest {
        val dir = tempFolder.newFolder()
        // Write a page to establish epoch 5
        val store1 = FileStructuralKnowledgeStore(directory = dir)
        store1.initSource(sourceIdentity = sourceIdentityA, sourceEpoch = 5L)
        store1.writePage(sampleRecord(), sourceIdentity = sourceIdentityA, sourceEpoch = 5L)

        // Now corrupt the index file
        File(dir, "structural-index.json").writeText("{\"schemaVersion\": 999, \"corrupt\": true}")

        // New store instance loads the corrupt index
        val store2 = FileStructuralKnowledgeStore(directory = dir)

        // A stale commit at epoch 0 must NOT succeed even with corrupt index
        store2.writePage(sampleRecord(revision = "stale-after-corrupt"), sourceIdentity = sourceIdentityA, sourceEpoch = 0L)

        // The page file on disk should NOT be overwritten with epoch-0 content
        // (page file from store1 still exists, so readPage returns original, not stale)
        val restored = store2.readPage(pageId)
        assertTrue(
            "Corrupt index must not allow epoch-0 commit to overwrite existing page content",
            restored == null || restored.page.sourceRevision != "stale-after-corrupt"
        )
    }

    // ---------------------------------------------------------------- Write elision

    @Test
    fun fingerprintEqualityBypassesRedundantWrites() = runTest {
        val dir = tempFolder.newFolder()
        val store = FileStructuralKnowledgeStore(directory = dir).also { it.initSource(sourceIdentityA, 1L) }
        val record = sampleRecord()

        store.writePage(record, sourceIdentity = sourceIdentityA, sourceEpoch = 1L)
        val pageFile = File(dir, "pages/$pageId.json")
        val firstModified = pageFile.lastModified()

        Thread.sleep(10)
        store.writePage(record, sourceIdentity = sourceIdentityA, sourceEpoch = 1L)

        assertEquals("Redundant identical write should be elided", firstModified, pageFile.lastModified())
    }

    @Test
    fun sameEdgeCountDifferentTargetTriggersRewrite() = runTest {
        val dir = tempFolder.newFolder()
        val store = FileStructuralKnowledgeStore(directory = dir).also { it.initSource(sourceIdentityA, 1L) }

        val target1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val target2 = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

        // Write record with edge pointing to target1
        store.writePage(sampleRecord(edgeTarget = target1), sourceIdentity = sourceIdentityA, sourceEpoch = 1L)
        val pageFile = File(dir, "pages/$pageId.json")
        val firstModified = pageFile.lastModified()

        Thread.sleep(10)
        // Write record with same edge count but different target (relation replacement)
        store.writePage(sampleRecord(edgeTarget = target2), sourceIdentity = sourceIdentityA, sourceEpoch = 1L)

        assertTrue(
            "Same-count relation replacement must trigger rewrite",
            pageFile.lastModified() > firstModified
        )
    }

    // ---------------------------------------------------------------- removePage privacy

    @Test
    fun removePageClearsPrivateContentFromIndex() = runTest {
        val store = FileStructuralKnowledgeStore(directory = tempFolder.newFolder()).also { it.initSource(sourceIdentityA, 1L) }

        store.writePage(sampleRecord(), sourceIdentity = sourceIdentityA, sourceEpoch = 1L)
        store.removePage(pageId, sourceIdentity = sourceIdentityA, sourceEpoch = 2L, reason = PageAccessibilityState.NOT_FOUND_OR_UNSHARED)

        val index = store.readIndex()
        val summary = index.pages[pageId]
        if (summary != null) {
            assertTrue(
                "Tombstone must not retain the original title",
                summary.title.isEmpty()
            )
        }
        // Incoming edges pointing to other pages that were added by this page must be removed
        val incomingToParent = index.incomingEdgeMap[parentId].orEmpty()
        assertTrue(
            "Incoming edges from removed page must be cleared",
            incomingToParent.none { it.sourceId == pageId }
        )
    }

    @Test
    fun removePageClearsFromParentChildIndex() = runTest {
        val store = FileStructuralKnowledgeStore(directory = tempFolder.newFolder()).also { it.initSource(sourceIdentityA, 1L) }

        store.writePage(sampleRecord(id = pageId, parent = parentId), sourceIdentity = sourceIdentityA, sourceEpoch = 1L)
        val childrenBefore = store.getDirectChildren(parentId)
        assertTrue(childrenBefore.any { it.id == pageId })

        store.removePage(pageId, sourceIdentity = sourceIdentityA, sourceEpoch = 2L, reason = PageAccessibilityState.RESTRICTED_PERMISSION)
        val childrenAfter = store.getDirectChildren(parentId)
        assertTrue(
            "Removed page must be absent from parent-child index",
            childrenAfter.none { it.id == pageId }
        )
    }

    // ---------------------------------------------------------------- Pending work

    @Test
    fun persistsAndCompletesPendingWorkWithoutOpaqueCursors() = runTest {
        val store = FileStructuralKnowledgeStore(directory = tempFolder.newFolder()).also { it.initSource(sourceIdentityA, 1L) }

        val workA = PendingStructuralWork(pageId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", requestedAtEpochMilli = 1000L, reason = "initial-sync", sourceIdentity = sourceIdentityA)
        val workB = PendingStructuralWork(pageId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", requestedAtEpochMilli = 2000L, reason = "retry-timeout", sourceIdentity = sourceIdentityA)
        store.recordPendingWork(workA)
        store.recordPendingWork(workB)

        val pending = store.readPendingWork()
        assertEquals(2, pending.size)
        assertTrue(pending.any { it.pageId == workA.pageId })
        assertTrue(pending.any { it.pageId == workB.pageId })

        store.completePendingWork(workA.pageId)
        val remaining = store.readPendingWork()
        assertEquals(1, remaining.size)
        assertEquals(workB.pageId, remaining.single().pageId)
    }

    // ---------------------------------------------------------------- Index queries

    @Test
    fun indexProvidesFastParentChildAndIncomingEdgeLookups() = runTest {
        val store = FileStructuralKnowledgeStore(directory = tempFolder.newFolder()).also { it.initSource(sourceIdentityA, 1L) }

        store.writePage(sampleRecord(id = child1Id, parent = parentId), sourceIdentity = sourceIdentityA, sourceEpoch = 1L)
        store.writePage(sampleRecord(id = child2Id, parent = parentId), sourceIdentity = sourceIdentityA, sourceEpoch = 1L)

        val directChildren = store.getDirectChildren(parentId)
        assertEquals(2, directChildren.size)
        assertTrue(directChildren.any { it.id == child1Id })
        assertTrue(directChildren.any { it.id == child2Id })

        val incomingEdges = store.getIncomingRelationships(parentId)
        assertEquals(2, incomingEdges.size)
    }

    @Test
    fun rebuildIndexRestoresFromPageFiles() = runTest {
        val dir = tempFolder.newFolder()
        val store = FileStructuralKnowledgeStore(directory = dir).also { it.initSource(sourceIdentityA, 1L) }

        store.writePage(sampleRecord(id = child1Id, parent = parentId), sourceIdentity = sourceIdentityA, sourceEpoch = 1L)
        store.writePage(sampleRecord(id = child2Id, parent = parentId), sourceIdentity = sourceIdentityA, sourceEpoch = 1L)

        // Corrupt/delete the index to force rebuild
        File(dir, "structural-index.json").delete()
        store.releaseMemory()

        val rebuilt = store.rebuildIndex()
        assertTrue("Rebuilt index must contain child1", rebuilt.pages.containsKey(child1Id))
        assertTrue("Rebuilt index must contain child2", rebuilt.pages.containsKey(child2Id))
        val children = rebuilt.parentChildIndex[parentId].orEmpty()
        assertTrue(children.containsAll(listOf(child1Id, child2Id)))
    }

    // ---------------------------------------------------------------- Block anchor resolution

    @Test
    fun resolveBlockAnchorByBlockId() = runTest {
        val store = FileStructuralKnowledgeStore(directory = tempFolder.newFolder()).also { it.initSource(sourceIdentityA, 1L) }

        store.writePage(sampleRecord(), sourceIdentity = sourceIdentityA, sourceEpoch = 1L)

        val anchor = store.resolveBlockAnchor(blockId1)
        assertNotNull("Block anchor must resolve by block ID", anchor)
        assertEquals(blockId1, anchor!!.sourceBlockId)
    }

    @Test
    fun resolveNavigationAnchorByRealSectionKey() = runTest {
        val store = FileStructuralKnowledgeStore(directory = tempFolder.newFolder()).also { it.initSource(sourceIdentityA, 1L) }
        store.writePage(sampleRecord(), sourceIdentity = sourceIdentityA, sourceEpoch = 1L)

        // The sectionKey is "$pageId:intro" — resolveNavigationAnchor must work with it directly
        val anchor = store.resolveNavigationAnchor("$pageId:intro")
        assertNotNull("Navigation anchor must resolve by full sectionKey", anchor)
    }

    // ---------------------------------------------------------------- Storage gate

    @Test
    fun honorsStorageWriteGateAndRespectsRefusal() = runTest {
        val refusingGate = object : StorageWriteGate {
            override suspend fun <T> write(category: StorageCategory, additionalPeakBytes: Long, block: suspend () -> T): T {
                throw StorageRefused(StorageFailure.CATEGORY_LIMIT)
            }
        }

        val store = FileStructuralKnowledgeStore(directory = tempFolder.newFolder(), writeGate = refusingGate).also { it.initSource(sourceIdentityA, 1L) }

        try {
            store.writePage(sampleRecord(), sourceIdentity = sourceIdentityA, sourceEpoch = 1L)
            fail("Expected StorageRefused exception")
        } catch (e: StorageRefused) {
            assertEquals(StorageFailure.CATEGORY_LIMIT, e.reason)
        }

        assertNull("Page record must not be written if gate refused", store.readPage(pageId))
    }

    @Test
    fun storageGateReceivesNotesCategory() = runTest {
        val categoriesUsed = mutableListOf<StorageCategory>()
        val observingGate = object : StorageWriteGate {
            override suspend fun <T> write(category: StorageCategory, additionalPeakBytes: Long, block: suspend () -> T): T {
                categoriesUsed.add(category)
                return block()
            }
        }

        val store = FileStructuralKnowledgeStore(directory = tempFolder.newFolder(), writeGate = observingGate).also { it.initSource(sourceIdentityA, 1L) }
        store.writePage(sampleRecord(), sourceIdentity = sourceIdentityA, sourceEpoch = 1L)

        assertTrue(
            "Structural store must use NOTES storage category, not DISCOVERY",
            categoriesUsed.all { it == StorageCategory.NOTES }
        )
    }
}

