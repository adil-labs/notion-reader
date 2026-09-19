package com.example.notionreader.application

import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.queue.InMemorySavedQueueStore
import com.example.notionreader.domain.queue.QueueMove
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.domain.queue.SavedQueue
import com.example.notionreader.player.FakeReaderEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edit mode's two operations, through the application object.
 *
 * The reordering semantics are `SavedQueueEditsTest`'s and are not repeated. What is tested here is what only
 * exists at this layer: **what the listener is told**, and — the subtle half — what they are *not* told when
 * an operation turned out to be a no-op.
 *
 * That second half is the reason these tests exist at all. A move at its own boundary changes nothing and
 * performs no write, so announcing "Moved Alpha to position 1 of 5" would be a false statement about the
 * listener's queue, made by the application about an operation that did not happen. The control is disabled
 * there, so the message is unreachable through the UI — which is exactly why it needs a test: nothing else
 * would ever notice it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderAppQueueEditsTest {

    private val demo = ReaderDocument.fromChunkTexts("demo", "Demo document", listOf("First."))

    private fun app(store: InMemorySavedQueueStore, now: Long = NOW): ReaderApp = ReaderApp(
        session = ReaderSession(FakeReaderEngine(demo)),
        noteViewer = NoteViewer(demo),
        navigator = ReaderNavigator(ReaderNavigation(top = ReaderDestination.Queues)),
        loadDocument = DefaultLoadReaderDocument { pageId ->
            ReaderDocument.fromChunkTexts(pageId, "Note $pageId", listOf("First."))
        },
        savedQueueStore = store,
        clock = { now }
    )

    private fun store(vararg members: String) = InMemorySavedQueueStore(
        listOf(
            SavedQueue(
                id = "q1",
                name = "Reading",
                notes = members.mapIndexed { index, pageId -> QueuedNote(pageId, "Note $pageId") },
                lastEditedAt = 1_000L
            )
        )
    )

    // --- Moving -------------------------------------------------------------------------------------

    @Test
    fun movingAMemberAnnouncesItsNameAndItsNewPosition() = runTest {
        // The spec: "the announcement is the member's name and its new position, brief and once". The
        // position counts from 1 because that is the number a listener is counting in.
        val store = store("p1", "p2", "p3")
        val app = app(store)
        app.loadSavedQueues()

        val outcome = app.moveSavedQueueMember("q1", "p3", QueueMove.Top)

        assertTrue(outcome.changed)
        assertEquals("Moved Note p3 to position 1 of 3.", app.queueMessage.value)
        assertEquals(listOf("p3", "p1", "p2"), store.stored.single().notes.map { it.pageId })
    }

    @Test
    fun eachMoveAnnouncesThePositionItActuallyReached() = runTest {
        // One assertion per move, because the four are defined by postconditions rather than by arithmetic
        // and the *message* is the part a listener hears. A move that reordered correctly but reported the
        // old position would be a lie that no ordering assertion could catch.
        val expected = mapOf(
            QueueMove.Up to ("p2" to 1),
            QueueMove.Down to ("p2" to 3),
            QueueMove.Top to ("p3" to 1),
            QueueMove.Bottom to ("p1" to 3)
        )

        for ((move, expectation) in expected) {
            val (pageId, position) = expectation
            val store = store("p1", "p2", "p3")
            val app = app(store)
            app.loadSavedQueues()

            app.moveSavedQueueMember("q1", pageId, move)

            assertEquals("$move of $pageId", "Moved Note $pageId to position $position of 3.", app.queueMessage.value)
        }
    }

    @Test
    fun aMoveAtItsOwnBoundarySaysNothingAtAll() = runTest {
        // The case this file exists for. The control is disabled here, so a press would be stale — and a
        // message would claim a reorder that did not happen.
        val store = store("p1", "p2", "p3")
        val app = app(store)
        app.loadSavedQueues()

        val outcome = app.moveSavedQueueMember("q1", "p1", QueueMove.Up)

        assertFalse(outcome.changed)
        assertNull("a no-op move must not be announced", app.queueMessage.value)
    }

    @Test
    fun everyBoundaryMoveOfEveryKindSaysNothing() = runTest {
        // One property over all four rather than four cases, because the rule is about boundary moves as a
        // class: each is a no-op, and each must be silent.
        val boundaryMoves = listOf(
            "p1" to QueueMove.Up,
            "p3" to QueueMove.Down,
            "p1" to QueueMove.Top,
            "p3" to QueueMove.Bottom
        )

        for ((pageId, move) in boundaryMoves) {
            val store = store("p1", "p2", "p3")
            val app = app(store)
            app.loadSavedQueues()

            app.moveSavedQueueMember("q1", pageId, move)

            assertNull("$move of $pageId is at its boundary and should say nothing", app.queueMessage.value)
        }
    }

    @Test
    fun movingAMemberThatIsNotInTheQueueSaysNothing() = runTest {
        val store = store("p1", "p2")
        val app = app(store)
        app.loadSavedQueues()

        val outcome = app.moveSavedQueueMember("q1", "missing", QueueMove.Top)

        assertFalse(outcome.changed)
        assertNull(app.queueMessage.value)
    }

    @Test
    fun movingInAQueueThatIsGoneSaysNothing() = runTest {
        val store = store("p1", "p2")
        val app = app(store)
        app.loadSavedQueues()

        val outcome = app.moveSavedQueueMember("missing", "p1", QueueMove.Top)

        assertFalse(outcome.changed)
        assertNull(app.queueMessage.value)
    }

    // --- Removing -----------------------------------------------------------------------------------

    @Test
    fun removingAMemberAnnouncesWhichMemberWent() = runTest {
        val store = store("p1", "p2", "p3")
        val app = app(store)
        app.loadSavedQueues()

        val outcome = app.removeSavedQueueMember("q1", "p2")

        assertTrue(outcome.changed)
        assertEquals("Removed Note p2.", app.queueMessage.value)
        assertEquals(listOf("p1", "p3"), store.stored.single().notes.map { it.pageId })
    }

    @Test
    fun removingTheOnlyMemberLeavesAnEmptyQueueAndSaysSo() = runTest {
        // Emptied, not deleted: the queue is still there with 0 notes, and the message is about the member
        // rather than about the queue going away.
        val store = store("p1")
        val app = app(store)
        app.loadSavedQueues()

        app.removeSavedQueueMember("q1", "p1")

        assertEquals("Removed Note p1.", app.queueMessage.value)
        assertEquals(emptyList<QueuedNote>(), store.stored.single().notes)
        assertEquals("the queue itself must survive", listOf("q1"), store.stored.map { it.id })
    }

    @Test
    fun removingANoteThatIsNotAMemberSaysNothing() = runTest {
        val store = store("p1", "p2")
        val app = app(store)
        app.loadSavedQueues()

        val outcome = app.removeSavedQueueMember("q1", "missing")

        assertFalse(outcome.changed)
        assertNull(app.queueMessage.value)
    }

    // --- What the message channel is, and is not ----------------------------------------------------

    @Test
    fun theAnnouncementIsReplacedByTheNextOperationRatherThanAccumulating() = runTest {
        // One slot, not a log: a listener hears the last thing that happened. Two messages at once would be
        // two live-region changes, and the second would arrive while the first was being read.
        val store = store("p1", "p2", "p3")
        val app = app(store)
        app.loadSavedQueues()

        app.moveSavedQueueMember("q1", "p3", QueueMove.Top)
        assertEquals("Moved Note p3 to position 1 of 3.", app.queueMessage.value)

        app.removeSavedQueueMember("q1", "p1")
        assertEquals("Removed Note p1.", app.queueMessage.value)
    }

    @Test
    fun anUnreadableStoreSaysNothingAboutAMove() = runTest {
        // A storage failure is not a failure of what the listener did, and it also has no truthful outcome to
        // report — there is nothing to say about where the member went.
        val store = store("p1", "p2").apply { failReads = true }
        val app = app(store)

        val outcome = app.moveSavedQueueMember("q1", "p1", QueueMove.Bottom)

        assertFalse(outcome.changed)
        assertNull(app.queueMessage.value)
    }

    @Test
    fun theMessageIsClearedOnceTheSurfaceShowingItHasDoneSo() = runTest {
        val store = store("p1", "p2")
        val app = app(store)
        app.loadSavedQueues()
        app.moveSavedQueueMember("q1", "p1", QueueMove.Bottom)
        assertEquals("Moved Note p1 to position 2 of 2.", app.queueMessage.value)

        app.consumeQueueMessage()

        assertNull(app.queueMessage.value)
    }

    @Test
    fun anEditPublishesTheQueueSoTheScreenSeesTheNewOrderWithoutARead() = runTest {
        // The order on screen comes from the published list, so an edit that wrote without publishing would
        // leave the listener looking at the order they just changed away from.
        val store = store("p1", "p2", "p3")
        val app = app(store)
        app.loadSavedQueues()

        app.moveSavedQueueMember("q1", "p3", QueueMove.Top)

        assertEquals(listOf("p3", "p1", "p2"), app.savedQueueCard("q1")?.members?.map { it.pageId })
    }

    private companion object {
        const val NOW = 9_000L
    }
}
