package com.example.notionreader.application

import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.queue.InMemorySavedQueueStore
import com.example.notionreader.domain.queue.QueueEffect
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
 * The Add path: `Add to queue`, `Add all to queue`, and Queue Destination.
 *
 * The queue-operation semantics are the model's and are not repeated here. What this file covers is the part
 * that only exists at this layer:
 *
 * - **the captured set is a capture**, not a reference to a list that can change under it;
 * - **the pending set has a lifetime** — it is placed or abandoned, and a stale one must not be placed by a
 *   later tap;
 * - **the report exists**, because the specification makes the de-duplication rule and the reporting of it one
 *   requirement: "a listener who adds twelve things and is told nothing cannot tell 'all twelve were already
 *   there' from 'the app failed'";
 * - **creating a queue from the chooser receives the captured Notes**, which is the contract's
 *   create-if-empty path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderAppAddToQueueTest {

    private val demo = ReaderDocument.fromChunkTexts("demo", "Demo document", listOf("First."))

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

    // --- Capturing ----------------------------------------------------------------------------------

    @Test
    fun addingCapturesTheNotesAndOpensTheDestination() = runTest {
        val app = app(InMemorySavedQueueStore())

        val opened = app.beginAddToQueue(listOf(note("p1")), fromFocusId = "note:addToQueue")

        assertTrue(opened)
        assertEquals(1, app.pendingAddCount)
        assertEquals(ReaderDestination.QueueDestination, app.navigator.state.value.current)
    }

    @Test
    fun addingNothingDoesNothingAtAll() = runTest {
        // No navigation and no pending set: a destination screen with nothing to place would offer a listener
        // queues to add nothing to, which is a control that cannot do what its name says.
        val app = app(InMemorySavedQueueStore())
        app.openQueues()

        val opened = app.beginAddToQueue(emptyList())

        assertFalse(opened)
        assertEquals(0, app.pendingAddCount)
        assertEquals(ReaderDestination.Queues, app.navigator.state.value.current)
    }

    @Test
    fun theCapturedSetIsACopyRatherThanAReferenceToTheCallersList() = runTest {
        // **The capture is the point.** Browse's visible result set is a list that re-sorts, so capturing a
        // reference would place whatever it happened to hold when the destination was finally chosen — the
        // same class of defect as handing a live queue to a session, at the other end of the flow.
        val store = InMemorySavedQueueStore(listOf(queue("q1")))
        val app = app(store)
        app.loadSavedQueues()

        val source = mutableListOf(note("p1"), note("p2"))
        app.beginAddToQueue(source)
        source.add(note("p3"))
        source.removeAt(0)
        app.addPendingToQueue("q1")

        assertEquals(listOf("p1", "p2"), store.stored.single().notes.map { it.pageId })
    }

    @Test
    fun aSecondAddReplacesTheFirstRatherThanAccumulating() = runTest {
        // Two captures in a row is a listener who changed their mind about what is being placed. Accumulating
        // would silently add the abandoned set as well, which is content they did not ask for.
        val store = InMemorySavedQueueStore(listOf(queue("q1")))
        val app = app(store)
        app.loadSavedQueues()

        app.beginAddToQueue(listOf(note("p1")))
        app.beginAddToQueue(listOf(note("p2")))
        app.addPendingToQueue("q1")

        assertEquals(listOf("p2"), store.stored.single().notes.map { it.pageId })
    }

    // --- Placing ------------------------------------------------------------------------------------

    @Test
    fun placingThePendingNotesAddsThemAndReportsWhatItDid() = runTest {
        val store = InMemorySavedQueueStore(listOf(queue("q1")))
        val app = app(store)
        app.loadSavedQueues()
        app.beginAddToQueue(listOf(note("p1"), note("p2")))

        val outcome = app.addPendingToQueue("q1")

        assertTrue(outcome.changed)
        assertEquals(listOf("p1", "p2"), store.stored.single().notes.map { it.pageId })
        assertEquals("Added 2 notes.", app.queueMessage.value)
    }

    @Test
    fun placingNotesThatAreAllAlreadyThereReportsSuccessRatherThanSilence() = runTest {
        // The case the specification singles out, and the whole reason a report exists. It is a *success* that
        // changed nothing, and it must not read like a failure or like nothing happened.
        val store = InMemorySavedQueueStore(listOf(queue("q1", members = listOf(note("p1")))))
        val app = app(store)
        app.loadSavedQueues()
        app.beginAddToQueue(listOf(note("p1")))

        val outcome = app.addPendingToQueue("q1")

        assertFalse(outcome.changed)
        assertEquals(
            "Nothing was added, because 1 note was already in this queue.",
            app.queueMessage.value
        )
    }

    @Test
    fun theAllDuplicatesReportSaysWhatWasSkippedOnceAndNotTwice() = runTest {
        // The first version of this message was "Nothing was added, because every note was already in this
        // queue." followed by "3 were already in this queue." — the same fact twice, which a test caught.
        val store = InMemorySavedQueueStore(
            listOf(queue("q1", members = listOf(note("p1"), note("p2"), note("p3"))))
        )
        val app = app(store)
        app.loadSavedQueues()
        app.beginAddToQueue(listOf(note("p1"), note("p2"), note("p3")))

        app.addPendingToQueue("q1")

        assertEquals(
            "Nothing was added, because all 3 notes were already in this queue.",
            app.queueMessage.value
        )
    }

    @Test
    fun aPlacementWithNothingPendingSaysNothingAtAll() = runTest {
        // Reachable: a second destination chosen after the first placement. There is nothing to report, because
        // the listener did not ask for anything — so a no-op that nobody initiated produces no message.
        val store = InMemorySavedQueueStore(listOf(queue("q1"), queue("q2")))
        val app = app(store)
        app.loadSavedQueues()

        app.addPendingToQueue("q1")

        assertNull(app.queueMessage.value)
    }

    @Test
    fun theReportStatesBothWhatWentInAndWhatWasSkipped() = runTest {
        // The number a listener needs when a result set overlaps the destination: "4 notes" alone leaves them
        // unable to tell whether the eight they selected were handled.
        val store = InMemorySavedQueueStore(
            listOf(queue("q1", members = listOf(note("p3"), note("p4"), note("p5"))))
        )
        val app = app(store)
        app.loadSavedQueues()
        app.beginAddToQueue(listOf(note("p1"), note("p2"), note("p3"), note("p4")))

        app.addPendingToQueue("q1")

        assertEquals("Added 2 notes. 2 were already in this queue.", app.queueMessage.value)
    }

    @Test
    fun aSingleAddedNoteIsReportedInTheSingular() = runTest {
        val store = InMemorySavedQueueStore(listOf(queue("q1")))
        val app = app(store)
        app.loadSavedQueues()

        app.beginAddToQueue(listOf(note("p1")))
        app.addPendingToQueue("q1")

        assertEquals("Added 1 note.", app.queueMessage.value)
    }

    @Test
    fun aSingleSkippedNoteIsReportedInTheSingular() = runTest {
        // Both halves of the message have a singular form, and only the second is easy to forget: the first
        // version wrote "1 was already" correctly and would have written "1 notes" for the other half.
        val store = InMemorySavedQueueStore(listOf(queue("q1", members = listOf(note("p9")))))
        val app = app(store)
        app.loadSavedQueues()

        app.beginAddToQueue(listOf(note("p1"), note("p9")))
        app.addPendingToQueue("q1")

        assertEquals("Added 1 note. 1 was already in this queue.", app.queueMessage.value)
    }

    @Test
    fun aReportWithNothingSkippedSaysNothingAboutSkipping() = runTest {
        // "0 were already in this queue" is the template showing through.
        val store = InMemorySavedQueueStore(listOf(queue("q1")))
        val app = app(store)
        app.loadSavedQueues()

        app.beginAddToQueue(listOf(note("p1"), note("p2"), note("p3")))
        app.addPendingToQueue("q1")

        assertEquals("Added 3 notes.", app.queueMessage.value)
    }

    @Test
    fun placingConsumesThePendingSetSoALaterTapCannotPlaceItTwice() = runTest {
        // The lifetime rule. A pending set that survived its own placement would be placed again by the next
        // destination chosen — content appearing in a queue the listener did not put it in.
        val store = InMemorySavedQueueStore(listOf(queue("q1"), queue("q2")))
        val app = app(store)
        app.loadSavedQueues()

        app.beginAddToQueue(listOf(note("p1")))
        app.addPendingToQueue("q1")
        assertEquals(0, app.pendingAddCount)

        // A second placement with nothing pending is a no-op against the destination, not a repeat.
        app.addPendingToQueue("q2")

        assertEquals(listOf("p1"), store.stored.first { it.id == "q1" }.notes.map { it.pageId })
        assertEquals(emptyList<String>(), store.stored.first { it.id == "q2" }.notes.map { it.pageId })
    }

    @Test
    fun abandoningAPendingSetLeavesNoTraceOfIt() = runTest {
        // Back from the destination. A pending set that outlived the screen it was captured for would be
        // placed by an unrelated later visit.
        val store = InMemorySavedQueueStore(listOf(queue("q1")))
        val app = app(store)
        app.loadSavedQueues()

        app.beginAddToQueue(listOf(note("p1")))
        app.cancelPendingAdd()
        app.addPendingToQueue("q1")

        assertEquals(0, app.pendingAddCount)
        assertEquals(emptyList<String>(), store.stored.single().notes.map { it.pageId })
    }

    @Test
    fun placingIntoAQueueThatIsGoneStillClearsThePendingSet() = runTest {
        // Reachable: the chooser's list is not frozen. The set is cleared by the application before the write,
        // so a refusal cannot leave it to be placed later by a tap the listener did not make.
        val app = app(InMemorySavedQueueStore(listOf(queue("q1"))))
        app.loadSavedQueues()
        app.beginAddToQueue(listOf(note("p1")))

        app.addPendingToQueue("missing")

        assertEquals(0, app.pendingAddCount)
    }

    @Test
    fun aReportFromAnEarlierAddIsClearedWhenANewOneBegins() = runTest {
        // Otherwise the destination's next arrival explains something that did not happen.
        val store = InMemorySavedQueueStore(listOf(queue("q1")))
        val app = app(store)
        app.loadSavedQueues()

        app.beginAddToQueue(listOf(note("p1")))
        app.addPendingToQueue("q1")
        assertEquals("Added 1 note.", app.queueMessage.value)

        app.beginAddToQueue(listOf(note("p2")))

        assertNull(app.queueMessage.value)
    }

    @Test
    fun theReportIsConsumedOnceTheSurfaceShowingItHasDoneSo() = runTest {
        val store = InMemorySavedQueueStore(listOf(queue("q1")))
        val app = app(store)
        app.loadSavedQueues()
        app.beginAddToQueue(listOf(note("p1")))
        app.addPendingToQueue("q1")

        app.consumeQueueMessage()

        assertNull(app.queueMessage.value)
    }

    @Test
    fun anUnreadableStorePlacesNothingAndReportsNothing() = runTest {
        // A storage failure is not a failure of the operation the listener performed, so there is no report —
        // there is nothing truthful to say about what happened to their notes.
        val store = InMemorySavedQueueStore(listOf(queue("q1"))).apply { failReads = true }
        val app = app(store)
        app.beginAddToQueue(listOf(note("p1")))

        val outcome = app.addPendingToQueue("q1")

        assertEquals(QueueEffect.StoreUnavailable, outcome.effect)
        assertNull(app.queueMessage.value)
        assertEquals(0, app.pendingAddCount)
    }

    // --- Creating a destination, which receives the pending Notes -------------------------------------

    @Test
    fun aQueueCreatedFromTheChooserReceivesThePendingNotes() = runTest {
        // The contract's create-if-empty path: "The new queue receives the incoming Notes, so the listener's
        // intent — 'these things, into a queue' — is completed rather than abandoned at a screen with nothing
        // on it."
        val store = InMemorySavedQueueStore()
        val app = app(store)

        app.beginAddToQueue(listOf(note("p1"), note("p2")))
        val outcome = app.createSavedQueue("Reading")

        val created = app.savedQueues.value.single()
        assertEquals("Reading", created.name)
        assertEquals(listOf("p1", "p2"), created.notes.map { it.pageId })
        assertTrue(outcome.changed)
        assertEquals("Added 2 notes.", app.queueMessage.value)
    }

    @Test
    fun aQueueCreatedFromTheSavedTabIsEmptyBecauseNothingIsPending() = runTest {
        // The other half of the same operation: no pending set means plain creation, which is what the Saved
        // tab's own control does. Without this, "create also adds" would be indistinguishable from "create
        // always adds something".
        val app = app(InMemorySavedQueueStore())

        app.createSavedQueue("Reading")

        assertEquals(emptyList<QueuedNote>(), app.savedQueues.value.single().notes)
        assertNull(app.queueMessage.value)
    }

    @Test
    fun creatingWithAPendingSetConsumesIt() = runTest {
        val store = InMemorySavedQueueStore()
        val app = app(store)

        app.beginAddToQueue(listOf(note("p1")))
        app.createSavedQueue("Reading")

        assertEquals(0, app.pendingAddCount)

        // A second queue created immediately afterwards is empty, because the set was placed into the first.
        app.createSavedQueue("Second")
        val second = app.savedQueues.value.first { it.name == "Second" }
        assertEquals(emptyList<QueuedNote>(), second.notes)
    }

    @Test
    fun aRejectedNameLeavesThePendingNotesCapturedForAnotherAttempt() = runTest {
        // The listener typed spaces by mistake: nothing is created, and the notes they had captured must still
        // be waiting when they correct the name. Losing them here would be the same defect as losing the
        // typing, which is why the Create screen stays put on a refusal.
        val store = InMemorySavedQueueStore()
        val app = app(store)

        app.beginAddToQueue(listOf(note("p1")))
        val outcome = app.createSavedQueue("   ")

        assertFalse(outcome.changed)
        assertEquals(1, app.pendingAddCount)
        assertEquals(emptyList<SavedQueue>(), app.savedQueues.value)

        // And the corrected attempt still receives them.
        app.createSavedQueue("Reading")
        assertEquals(listOf("p1"), app.savedQueues.value.single().notes.map { it.pageId })
    }

    private companion object {
        const val NOW = 9_000L

        fun note(pageId: String, title: String = "Note $pageId") = QueuedNote(pageId, title)

        fun queue(id: String, name: String = "Queue $id", members: List<QueuedNote> = emptyList()) =
            SavedQueue(id = id, name = name, notes = members, lastEditedAt = 1_000L)
    }
}
