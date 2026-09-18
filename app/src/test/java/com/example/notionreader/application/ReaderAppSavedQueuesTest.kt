package com.example.notionreader.application

import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.queue.InMemorySavedQueueStore
import com.example.notionreader.domain.queue.QueueEdit
import com.example.notionreader.domain.queue.QueueEffect
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.queue.QueueRejection
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.domain.queue.SavedQueue
import com.example.notionreader.domain.queue.applyTo
import com.example.notionreader.player.FakeReaderEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Saved Queue operations, through the application object.
 *
 * The model's own semantics are `SavedQueueEditsTest`'s and `SavedQueuesTest`'s, and are not repeated here.
 * What this file is about is the part that only exists at this layer: **which list a surface ends up seeing**,
 * and what `Play` does to the session.
 *
 * The four rules worth stating, because each is invisible from the model's side:
 *
 * - an operation publishes its own outcome, so no second read can race the write;
 * - a no-op publishes nothing, because the list is already right;
 * - an **unreadable store publishes nothing**, which is the same rule and not a second one — and getting it
 *   wrong blanks a list the listener can see rather than a file they cannot;
 * - `Play` copies the members into the session instead of handing over the stored list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderAppSavedQueuesTest {

    private val demo = ReaderDocument.fromChunkTexts(
        pageId = "demo",
        title = "Demo document",
        texts = listOf("First.", "Second.")
    )

    private fun app(store: InMemorySavedQueueStore? = null, now: Long = NOW): ReaderApp = ReaderApp(
        session = ReaderSession(FakeReaderEngine(demo)),
        noteViewer = NoteViewer(demo),
        navigator = ReaderNavigator(ReaderNavigation(top = ReaderDestination.Queues)),
        loadDocument = DefaultLoadReaderDocument { pageId ->
            ReaderDocument.fromChunkTexts(pageId, "Note $pageId", listOf("First."))
        },
        savedQueueStore = store,
        clock = { now }
    )

    // --- Reading ------------------------------------------------------------------------------------

    @Test
    fun loadingPublishesWhatTheStoreHolds() = runTest {
        val store = InMemorySavedQueueStore(listOf(queue("a"), queue("b")))
        val app = app(store)

        app.loadSavedQueues()

        assertEquals(listOf("a", "b"), app.savedQueues.value.map { it.id })
    }

    @Test
    fun withNoStoreConfiguredThereAreNoQueuesAndNothingThrows() = runTest {
        // The nullable-store seam every existing test relies on. "No store" and "an unreadable store" have to
        // mean the same thing to a surface, and that meaning is an empty list rather than a failure.
        val app = app(store = null)

        app.loadSavedQueues()

        assertEquals(emptyList<SavedQueue>(), app.savedQueues.value)
        assertEquals(emptyList<SavedQueueCard>(), app.savedQueueCards())
        assertEquals(QueueEffect.StoreUnavailable, app.createSavedQueue("Reading").effect)
    }

    @Test
    fun anUnreadableStorePublishesNoQueuesAndLeavesThePublishedListAlone() = runTest {
        // **The rule that matters most here.** `QueueEditOutcome.unavailable` is `changed == false` with an
        // *empty* list, so the no-op rule already covers it — but only because publishing is keyed on
        // `changed` rather than on the operation having been attempted. Publishing the outcome
        // unconditionally would blank a list the listener is looking at, which is a defect the model's own
        // tests cannot see.
        val store = InMemorySavedQueueStore(listOf(queue("a"), queue("b")))
        val app = app(store)
        app.loadSavedQueues()
        assertEquals(listOf("a", "b"), app.savedQueues.value.map { it.id })

        store.failReads = true
        val outcome = app.createSavedQueue("Reading")

        assertEquals(QueueEffect.StoreUnavailable, outcome.effect)
        assertFalse(outcome.changed)
        assertEquals("the published list must not be blanked", listOf("a", "b"), app.savedQueues.value.map { it.id })
    }

    // --- Creating -----------------------------------------------------------------------------------

    @Test
    fun creatingAQueueAddsItToThePublishedListWithoutASecondRead() = runTest {
        val store = InMemorySavedQueueStore(listOf(queue("a")))
        val app = app(store)
        app.loadSavedQueues()
        val readsAfterLoad = store.readCount

        val outcome = app.createSavedQueue("Reading")

        assertTrue(outcome.effect is QueueEffect.Created)
        assertEquals(listOf("a", app.savedQueues.value.last().id), store.writes.single().map { it.id })
        assertEquals(2, app.savedQueues.value.size)
        // The outcome carried the new list, so nothing had to read it back — a read here would be the read
        // that races the write.
        assertEquals("creating must not read the store", readsAfterLoad, store.readCount)
    }

    @Test
    fun creatingAQueueUsesTheNameTrimmedAndProducesAnEmptyOne() = runTest {
        val store = InMemorySavedQueueStore()
        val app = app(store, now = NOW)

        app.createSavedQueue("  Reading  ")

        val created = app.savedQueues.value.single()
        assertEquals("Reading", created.name)
        assertEquals(emptyList<QueuedNote>(), created.notes)
        assertEquals(NOW, created.lastEditedAt)
    }

    @Test
    fun creatingAQueueGivesItAnIdentityThatIsNotItsName() = runTest {
        // The identity exists so a stored reference survives a rename, and it has to be assigned at creation
        // because that is the only moment it can be. Two queues named the same thing must still be two.
        val store = InMemorySavedQueueStore()
        val app = app(store)

        app.createSavedQueue("Reading")
        app.createSavedQueue("Reading")

        val ids = app.savedQueues.value.map { it.id }
        assertEquals(2, ids.size)
        assertEquals(2, ids.distinct().size)
        assertTrue("an id must not be the name", ids.none { it == "Reading" })
    }

    @Test
    fun creatingAQueueWithABlankNameIsRefusedAndPublishesNothing() = runTest {
        // The screen stays put on this answer and keeps the listener's typing, which is why the outcome is
        // returned rather than a Boolean.
        val store = InMemorySavedQueueStore()
        val app = app(store)

        val outcome = app.createSavedQueue("   ")

        assertEquals(QueueEffect.Rejected(QueueRejection.BlankName), outcome.effect)
        assertEquals(emptyList<SavedQueue>(), app.savedQueues.value)
        assertEquals(0, store.writeCount)
    }

    // --- Play ---------------------------------------------------------------------------------------

    @Test
    fun playSnapshotsTheQueueRatherThanHandingTheStoredListToTheSession() = runTest {
        // **The second source of a live list.** A Saved Queue is mutable storage, so handing the session its
        // own `notes` would let a later edit reach a playing queue — which the product contract forbids by
        // name. `ActiveQueue.snapshotOf` copies, and this asserts the copy really happened.
        val store = InMemorySavedQueueStore(listOf(queue("a", members = listOf(note("p1"), note("p2")))))
        val app = app(store)
        app.loadSavedQueues()

        app.playSavedQueue("a")
        assertEquals(listOf("p1", "p2"), app.session.queueState.value.queue?.items?.map { it.pageId })

        // The store is edited to a different membership, which is exactly what an edit from the details screen
        // will do. The edit goes through the store rather than through a method this slice does not have yet.
        store.transact { current -> QueueEdit.Remove("a", "p2").applyTo(current, 2L) }
        assertEquals(listOf("p1"), store.stored.single().notes.map { it.pageId })

        assertEquals(
            "a playing queue must not follow its source",
            listOf("p1", "p2"),
            app.session.queueState.value.queue?.items?.map { it.pageId }
        )
    }

    @Test
    fun playNamesTheQueueByItsIdentitySoARenameCannotInvalidateIt() = runTest {
        // The point of Phase 3's origin change, and this is its first constructor. A stored session has to
        // keep naming this queue after a rename, so the identity travels and the name is only a label.
        val store = InMemorySavedQueueStore(listOf(queue("q-7", name = "Reading", members = listOf(note("p1")))))
        val app = app(store)
        app.loadSavedQueues()

        app.playSavedQueue("q-7")

        assertEquals(
            QueueOrigin.Saved(queueId = "q-7", name = "Reading"),
            app.session.queueState.value.queue?.origin
        )
    }

    @Test
    fun playOnAnEmptyQueueIsRefused() = runTest {
        // "Play on an empty queue is not offered" — the screen disables the control on the same fact, and this
        // is the second half of the same rule rather than a second rule.
        val store = InMemorySavedQueueStore(listOf(queue("a", members = emptyList())))
        val app = app(store)
        app.loadSavedQueues()

        assertFalse(app.playSavedQueue("a"))
        assertNull(app.session.queueState.value.queue)
    }

    @Test
    fun playOnAQueueThatIsNotThereIsRefused() = runTest {
        val app = app(InMemorySavedQueueStore())
        app.loadSavedQueues()

        assertFalse(app.playSavedQueue("missing"))
        assertNull(app.session.queueState.value.queue)
    }

    @Test
    fun playOpensThePlayerRatherThanLeavingTheListenerOnTheList() = runTest {
        val store = InMemorySavedQueueStore(listOf(queue("a", members = listOf(note("p1")))))
        val app = app(store)
        app.loadSavedQueues()

        assertTrue(app.playSavedQueue("a"))

        assertEquals(ReaderDestination.Player, app.navigator.state.value.current)
    }

    @Test
    fun playStartsFromTheBeginningRatherThanResumingAPreviousQueueOfTheSameNotes() = runTest {
        // "Play must not mean resume: a queue that has been played before is played again from the
        // beginning." Played once, stepped to the second member, then played again.
        val store = InMemorySavedQueueStore(listOf(queue("a", members = listOf(note("p1"), note("p2")))))
        val app = app(store)
        app.loadSavedQueues()

        app.playSavedQueue("a")
        app.nextNote()
        assertEquals(1, app.session.queueState.value.index)

        app.playSavedQueue("a")

        assertEquals(0, app.session.queueState.value.index)
    }

    // --- Opening the two surfaces -------------------------------------------------------------------

    @Test
    fun openingADetailsScreenPutsTheQueueInTheSubjectAndNotInTheFocusId() = runTest {
        // The two ids are separate fields because the routes that differ are real: Home's *Your queues* will
        // open a queue while returning focus to a Home row. They coincide here and must still be two facts.
        val app = app(InMemorySavedQueueStore())

        app.openSavedQueueDetails(queueId = "q-7", fromFocusId = "q-7")

        val state = app.navigator.state.value
        assertEquals(ReaderDestination.SavedQueueDetails, state.current)
        assertEquals("q-7", state.currentSubjectId)
        // The element is recorded on the pushed stop, not in `focusTarget`: that field is the one-shot hint
        // for the screen Back returns *to*, and a forward push clears it so a hint that was never consumed
        // cannot fire on a later return.
        assertEquals("q-7", state.secondary.single().fromFocusId)
        assertNull(state.focusTarget)
    }

    @Test
    fun openingTheCreateScreenIsAboutNothingAndNamedByItsControl() = runTest {
        // The other side of the same distinction: there is no queue yet, so the push has a focus id and no
        // subject at all.
        val app = app(InMemorySavedQueueStore())

        app.openCreateSavedQueue()

        val state = app.navigator.state.value
        assertEquals(ReaderDestination.CreateSavedQueue, state.current)
        assertNull(state.currentSubjectId)
        assertEquals(ReaderFocus.SavedQueueCreate, state.secondary.single().fromFocusId)
    }

    @Test
    fun theDetailsScreenIsAboutTheQueueItWasOpenedForAndNotTheLastOne() = runTest {
        // Read from the pushed stop rather than held in a field, so opening a second queue cannot leave the
        // first one's identity behind.
        val app = app(InMemorySavedQueueStore(listOf(queue("a"), queue("b"))))

        app.openSavedQueueDetails("a")
        app.openSavedQueueDetails("b")

        assertEquals("b", app.navigator.state.value.currentSubjectId)
    }

    @Test
    fun backFromDetailsReturnsToTheQueueListWithTheQueueAsTheFocusTarget() = runTest {
        val app = app(InMemorySavedQueueStore(listOf(queue("q-7"))))
        app.openSavedQueueDetails(queueId = "q-7", fromFocusId = "q-7")

        assertTrue(app.back())

        val state = app.navigator.state.value
        assertEquals(ReaderDestination.Queues, state.current)
        assertEquals("q-7", state.focusTarget)
    }

    @Test
    fun theDetailsScreenResolvesItsOwnQueueOrFindsNothing() = runTest {
        val store = InMemorySavedQueueStore(listOf(queue("q-7", name = "Reading")))
        val app = app(store)
        app.loadSavedQueues()

        assertNotNull(app.savedQueueCard("q-7"))
        assertEquals("Reading", app.savedQueueCard("q-7")?.name)
        assertNull(app.savedQueueCard("missing"))
    }

    private companion object {
        const val NOW = 9_000L

        fun note(pageId: String, title: String = "Note $pageId") = QueuedNote(pageId, title)

        fun queue(
            id: String,
            name: String = "Queue $id",
            members: List<QueuedNote> = emptyList()
        ) = SavedQueue(id = id, name = name, notes = members, lastEditedAt = 1_000L)
    }
}
