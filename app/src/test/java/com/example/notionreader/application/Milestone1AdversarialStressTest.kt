package com.example.notionreader.application

import com.example.notionreader.application.DefaultLoadReaderDocument
import com.example.notionreader.application.DocumentLoadState
import com.example.notionreader.application.LoadReaderDocument
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
import com.example.notionreader.domain.queue.InMemorySavedQueueStore
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.domain.queue.QueueEffect
import com.example.notionreader.domain.queue.SavedQueue
import com.example.notionreader.player.FakeReaderEngine
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Milestone1AdversarialStressTest {

    private val noteA = ReaderDocument.fromChunkTexts(
        pageId = "note-A",
        title = "Note Alpha",
        texts = listOf("Alpha chunk 1", "Alpha chunk 2")
    )

    private val noteB = ReaderDocument.fromChunkTexts(
        pageId = "note-B",
        title = "Note Beta",
        texts = listOf("Beta chunk 1", "Beta chunk 2", "Beta chunk 3")
    )

    private fun note(pageId: String, title: String = "Title $pageId") = ReaderDocument.fromChunkTexts(
        pageId = pageId,
        title = title,
        texts = listOf("Chunk 1 for $pageId")
    )

    private fun queuedNote(id: String) = QueuedNote(id, "Title $id", "Snippet $id")

    private fun createSavedQueueCoordinator(
        initialQueues: List<SavedQueue> = listOf(SavedQueue("q1", "Queue 1", emptyList(), 0L))
    ): Triple<SavedQueueCoordinator, ReaderNavigator, ReaderSession> {
        val navigator = ReaderNavigator(ReaderNavigation(top = ReaderDestination.Queues))
        val session = ReaderSession(FakeReaderEngine(noteA))
        val store = InMemorySavedQueueStore(initialQueues)
        val coordinator = SavedQueueCoordinator(
            savedQueueStore = store,
            session = session,
            navigator = navigator,
            clock = { 1000L }
        )
        return Triple(coordinator, navigator, session)
    }

    // =============================================================================================
    // SECTION 1: Note Navigation & Scroll Bucket Keying Stress Tests
    // =============================================================================================

    @Test
    fun rapidBackAndForthNavigationAlternatesSubjectIdCorrectly() = runTest {
        val engine = FakeReaderEngine(noteA)
        val navigator = ReaderNavigator(ReaderNavigation(top = ReaderDestination.Browse))
        val viewer = NoteViewer(noteA)
        val app = ReaderApp(
            session = ReaderSession(engine),
            noteViewer = viewer,
            navigator = navigator,
            loadDocument = DefaultLoadReaderDocument { pageId ->
                when (pageId) {
                    "note-A" -> noteA
                    "note-B" -> noteB
                    else -> note(pageId)
                }
            }
        )

        // Rapid forward and back sequence between Note A, Note B, and Browse
        for (i in 1..20) {
            app.openNote("note-A")
            assertEquals(ReaderDestination.Note, app.navigator.state.value.current)
            assertEquals("note-A", app.navigator.state.value.currentSubjectId)

            app.openNote("note-B")
            assertEquals(ReaderDestination.Note, app.navigator.state.value.current)
            assertEquals("note-B", app.navigator.state.value.currentSubjectId)

            assertTrue("Back should pop note-B", app.back())
            assertEquals(ReaderDestination.Note, app.navigator.state.value.current)
            assertEquals("note-A", app.navigator.state.value.currentSubjectId)

            assertTrue("Back should pop note-A to Browse", app.back())
            assertEquals(ReaderDestination.Browse, app.navigator.state.value.current)
            assertNull(app.navigator.state.value.currentSubjectId)
        }
    }

    @Test
    fun openNoteParameterlessPreservesSubjectIdFromViewer() = runTest {
        val engine = FakeReaderEngine(noteA)
        val navigator = ReaderNavigator(ReaderNavigation(top = ReaderDestination.Home))
        val viewer = NoteViewer(noteA)
        val app = ReaderApp(
            session = ReaderSession(engine),
            noteViewer = viewer,
            navigator = navigator,
            loadDocument = DefaultLoadReaderDocument { note(it) }
        )

        // When document is present:
        app.openNote()
        assertEquals(ReaderDestination.Note, app.navigator.state.value.current)
        assertEquals("note-A", app.navigator.state.value.currentSubjectId)

        // When document is empty but viewer.pageId is set:
        app.back()
        val emptyViewer = NoteViewer(ReaderDocument.Empty)
        val appWithEmpty = ReaderApp(
            session = ReaderSession(engine),
            noteViewer = emptyViewer,
            navigator = navigator,
            loadDocument = DefaultLoadReaderDocument { note(it) }
        )
        // If emptyViewer has no pageId and empty document:
        appWithEmpty.openNote()
        assertEquals(ReaderDestination.Note, appWithEmpty.navigator.state.value.current)
        assertNull(appWithEmpty.navigator.state.value.currentSubjectId)
    }

    @Test
    fun deepNavigationPreservesHierarchyAndRestoresSubjectId() = runTest {
        val engine = FakeReaderEngine(noteA)
        val navigator = ReaderNavigator(ReaderNavigation(top = ReaderDestination.Browse))
        val viewer = NoteViewer(noteA)
        val app = ReaderApp(
            session = ReaderSession(engine),
            noteViewer = viewer,
            navigator = navigator,
            loadDocument = DefaultLoadReaderDocument { pageId ->
                if (pageId == "note-A") noteA else noteB
            }
        )

        // Browse -> Note A -> RelatedNotes A -> Note B -> Player -> Back chain
        app.openNote("note-A")
        assertEquals("note-A", app.navigator.state.value.currentSubjectId)

        app.openRelatedNotes("note-A")
        assertEquals(ReaderDestination.RelatedNotes, app.navigator.state.value.current)
        assertEquals("note-A", app.navigator.state.value.currentSubjectId)

        app.openNote("note-B")
        assertEquals(ReaderDestination.Note, app.navigator.state.value.current)
        assertEquals("note-B", app.navigator.state.value.currentSubjectId)

        app.playNote()
        assertEquals(ReaderDestination.Player, app.navigator.state.value.current)

        // Pop Player -> back to Note B
        assertTrue(app.back())
        assertEquals(ReaderDestination.Note, app.navigator.state.value.current)
        assertEquals("note-B", app.navigator.state.value.currentSubjectId)

        // Pop Note B -> back to RelatedNotes A
        assertTrue(app.back())
        assertEquals(ReaderDestination.RelatedNotes, app.navigator.state.value.current)
        assertEquals("note-A", app.navigator.state.value.currentSubjectId)

        // Pop RelatedNotes A -> back to Note A
        assertTrue(app.back())
        assertEquals(ReaderDestination.Note, app.navigator.state.value.current)
        assertEquals("note-A", app.navigator.state.value.currentSubjectId)

        // Pop Note A -> back to Browse
        assertTrue(app.back())
        assertEquals(ReaderDestination.Browse, app.navigator.state.value.current)
        assertNull(app.navigator.state.value.currentSubjectId)
    }

    // =============================================================================================
    // SECTION 2: NoteViewer Failure & Refresh Recovery Stress Tests
    // =============================================================================================

    @Test
    fun noteViewerSuccessFailureSuccessCycle() = runTest {
        val viewer = NoteViewer(ReaderDocument.Empty)
        val successLoaderA: LoadReaderDocument = DefaultLoadReaderDocument { noteA }
        val successLoaderB: LoadReaderDocument = DefaultLoadReaderDocument { noteB }
        val failingLoader: LoadReaderDocument = DefaultLoadReaderDocument {
            throw IOException("Simulated network outage")
        }

        // Cycle through 10 iterations of: Success A -> Fail B -> Success B -> Fail A -> Success A
        for (i in 1..10) {
            // 1. Load Note A succeeds
            val s1 = viewer.load("note-A", successLoaderA)
            assertTrue(s1)
            assertEquals("note-A", viewer.document.value.pageId)
            assertEquals("Note Alpha", viewer.document.value.title)
            assertTrue(viewer.loadState.value is DocumentLoadState.Loaded)

            // 2. Load Note B fails -> must reset document to Empty(note-B)
            val s2 = viewer.load("note-B", failingLoader)
            assertFalse(s2)
            assertEquals("note-B", viewer.document.value.pageId)
            assertEquals("", viewer.document.value.title) // ghost content eliminated
            assertTrue(viewer.loadState.value is DocumentLoadState.Error)

            // 3. Load Note B succeeds -> populated
            val s3 = viewer.load("note-B", successLoaderB)
            assertTrue(s3)
            assertEquals("note-B", viewer.document.value.pageId)
            assertEquals("Note Beta", viewer.document.value.title)
            assertTrue(viewer.loadState.value is DocumentLoadState.Loaded)

            // 4. Load Note A fails -> must reset document to Empty(note-A)
            val s4 = viewer.load("note-A", failingLoader)
            assertFalse(s4)
            assertEquals("note-A", viewer.document.value.pageId)
            assertEquals("", viewer.document.value.title)
            assertTrue(viewer.loadState.value is DocumentLoadState.Error)

            // 5. Load Note A succeeds -> populated
            val s5 = viewer.load("note-A", successLoaderA)
            assertTrue(s5)
            assertEquals("note-A", viewer.document.value.pageId)
            assertEquals("Note Alpha", viewer.document.value.title)
            assertTrue(viewer.loadState.value is DocumentLoadState.Loaded)
        }
    }

    @Test
    fun offlineRefreshFailurePreservesExistingContent() = runTest {
        val viewer = NoteViewer(noteA)
        val failingRefresh: LoadReaderDocument = DefaultLoadReaderDocument {
            throw ReaderLoadException(ReaderErrorCategory.NETWORK_UNAVAILABLE)
        }

        // Note A is loaded. Refresh it offline multiple times.
        for (attempt in 1..5) {
            val success = viewer.load("note-A", failingRefresh, forceRefresh = true)
            assertFalse("Failed refresh should return false", success)

            // Invariant: Content must remain readable for offline reading
            assertEquals("note-A", viewer.document.value.pageId)
            assertEquals("Note Alpha", viewer.document.value.title)
            assertEquals(2, viewer.document.value.chunks.size)
            assertEquals("Alpha chunk 1", viewer.document.value.chunks[0].text)

            // Load state must report Error with canRetry = true
            val errorState = viewer.loadState.value
            assertTrue(errorState is DocumentLoadState.Error)
            assertEquals(ReaderErrorCategory.NETWORK_UNAVAILABLE, (errorState as DocumentLoadState.Error).category)
            assertTrue(errorState.canRetry)
        }

        // Recovery: online refresh succeeds
        val onlineRefresh: LoadReaderDocument = DefaultLoadReaderDocument {
            ReaderDocument.fromChunkTexts("note-A", "Note Alpha Updated", listOf("New content"))
        }
        val recovered = viewer.load("note-A", onlineRefresh, forceRefresh = true)
        assertTrue(recovered)
        assertEquals("Note Alpha Updated", viewer.document.value.title)
        assertTrue(viewer.loadState.value is DocumentLoadState.Loaded)
    }

    @Test
    fun slowSupersededLoadIsDroppedEvenIfItFails() = runTest {
        val viewer = NoteViewer(noteA)

        val slowFailingLoader: LoadReaderDocument = DefaultLoadReaderDocument {
            delay(100)
            throw IOException("Late error")
        }
        val fastSuccessLoader: LoadReaderDocument = DefaultLoadReaderDocument {
            noteB
        }

        // Launch slow load A
        var jobAComplete = false
        var resultA: Boolean? = null
        val jobA = launch {
            resultA = viewer.load("note-A", slowFailingLoader)
            jobAComplete = true
        }
        testScheduler.runCurrent()

        // Fast load B supersedes load A
        val resultB = viewer.load("note-B", fastSuccessLoader)
        assertTrue(resultB)
        assertEquals("note-B", viewer.document.value.pageId)
        assertTrue(viewer.loadState.value is DocumentLoadState.Loaded)

        // Wait for slow job A
        jobA.join()
        assertTrue(jobAComplete)
        assertFalse("Superseded load must return false", resultA!!)

        // Note B's state must not be corrupted by A's late error
        assertEquals("note-B", viewer.document.value.pageId)
        assertEquals("Note Beta", viewer.document.value.title)
        assertTrue(viewer.loadState.value is DocumentLoadState.Loaded)
    }

    // =============================================================================================
    // SECTION 3: SavedQueueCoordinator StateFlow Concurrency Stress Tests
    // =============================================================================================

    @Test
    fun pendingAddAndCountRemainSynchronizedUnderSequentialMutations() = runTest {
        val (coordinator, _, _) = createSavedQueueCoordinator()

        for (i in 1..50) {
            val list = (1..i).map { queuedNote("note-$it") }
            coordinator.beginAddToQueue(list)
            assertEquals(i, coordinator.pendingAdd.value.size)
            assertEquals(i, coordinator.pendingAddCount)

            coordinator.cancelPendingAdd()
            assertEquals(0, coordinator.pendingAdd.value.size)
            assertEquals(0, coordinator.pendingAddCount)
        }
    }

    @Test
    fun concurrentBeginAddAndCancelPendingAddMaintainsInvariant() = runTest {
        val (coordinator, _, _) = createSavedQueueCoordinator()

        // Stress harness: concurrent operations across Dispatchers.Default
        val coroutineCount = 50
        val jobs = (1..coroutineCount).map { i ->
            launch(Dispatchers.Default) {
                if (i % 2 == 0) {
                    coordinator.beginAddToQueue(listOf(queuedNote("n-$i"), queuedNote("n-${i}_2")))
                } else {
                    coordinator.cancelPendingAdd()
                }
            }
        }
        jobs.joinAll()

        // Invariant after quiescence: pendingAdd.value.size must strictly equal pendingAddCount.value
        val finalCount = coordinator.pendingAddCount
        val finalSize = coordinator.pendingAdd.value.size
        assertEquals(
            "StateFlow size and count must be equal at quiescence. count=$finalCount, size=$finalSize",
            finalSize,
            finalCount
        )
    }

    @Test
    fun concurrentAddToQueueOperationsExecuteWithoutException() = runTest {
        val (coordinator, _, _) = createSavedQueueCoordinator()
        coordinator.loadSavedQueues()

        val exceptionCount = AtomicInteger(0)
        val coroutineCount = 40

        val jobs = (1..coroutineCount).map { i ->
            launch(Dispatchers.Default) {
                try {
                    when (i % 3) {
                        0 -> coordinator.beginAddToQueue(listOf(queuedNote("concurrent-$i")))
                        1 -> coordinator.addPendingToQueue("q1")
                        else -> coordinator.cancelPendingAdd()
                    }
                } catch (t: Throwable) {
                    exceptionCount.incrementAndGet()
                }
            }
        }
        jobs.joinAll()

        assertEquals("No uncaught exceptions should occur under concurrent operations", 0, exceptionCount.get())

        // Invariant check
        assertEquals(
            coordinator.pendingAdd.value.size,
            coordinator.pendingAddCount
        )
    }

    @Test
    fun beginAddToQueueWithEmptyListReturnsFalseAndLeavesStateUnchanged() = runTest {
        val (coordinator, _, _) = createSavedQueueCoordinator()

        val initialNotes = listOf(queuedNote("initial-1"))
        coordinator.beginAddToQueue(initialNotes)
        assertEquals(1, coordinator.pendingAddCount)

        // Attempting to beginAddToQueue with empty list should be rejected
        val result = coordinator.beginAddToQueue(emptyList())
        assertFalse(result)
        // Previous pending items preserved
        assertEquals(1, coordinator.pendingAddCount)
        assertEquals(initialNotes, coordinator.pendingAdd.value)
    }

    @Test
    fun createSavedQueueWithPendingItemsAtomicallyPopulatesAndClearsPending() = runTest {
        val (coordinator, _, _) = createSavedQueueCoordinator()

        val notes = listOf(queuedNote("n1"), queuedNote("n2"))
        coordinator.beginAddToQueue(notes)
        assertEquals(2, coordinator.pendingAddCount)

        val outcome = coordinator.createSavedQueue("My New Queue")
        assertTrue(outcome.changed)
        assertTrue(outcome.effect is QueueEffect.Added)

        // After creation and addition, pending add must be cleared
        assertEquals(0, coordinator.pendingAddCount)
        assertEquals(emptyList<QueuedNote>(), coordinator.pendingAdd.value)

        // The new queue should contain the 2 notes
        val createdQueue = coordinator.savedQueues.value.firstOrNull { it.name == "My New Queue" }
        assertNotNull(createdQueue)
        assertEquals(2, createdQueue!!.notes.size)
    }
}
