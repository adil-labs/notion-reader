package com.example.notionreader.application

import com.example.notionreader.data.notion.title
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
import com.example.notionreader.domain.queue.ActiveQueue
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.player.FakeReaderEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue state machine, tested synchronously.
 *
 * No dispatcher, no `runTest`, no waiting — because [ReaderSession] performs no I/O. That is a design
 * choice with a testing consequence worth stating: the between-Notes transition is the most intricate
 * part of a queue and the easiest to get wrong, and here it can be driven one step at a time and
 * inspected between steps. Every case below is a transition, not a scenario.
 */
class ReaderSessionQueueTest {

    private fun note(pageId: String, title: String) = ReaderDocument.fromChunkTexts(
        pageId = pageId,
        title = title,
        texts = listOf("$title first.", "$title second.")
    )

    private val alpha = note("alpha", "Alpha")
    private val beta = note("beta", "Beta")
    private val gamma = note("gamma", "Gamma")

    private fun queue(vararg documents: ReaderDocument) = ActiveQueue.snapshotOf(
        notes = documents.map { QueuedNote.of(it) },
        origin = QueueOrigin.BrowseResults
    )

    private fun failure(category: ReaderErrorCategory = ReaderErrorCategory.NETWORK_UNAVAILABLE) =
        ReaderLoadException(category)

    // --- Starting ---------------------------------------------------------------------------------

    @Test
    fun startingAQueueWaitsForTheFirstNoteRatherThanPlayingIt() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.start(queue(alpha, beta))

        // Nothing is playing and nothing has been handed to the engine: the first Note's document is not
        // known yet. The first transition is therefore the same path as every later one.
        assertEquals(0, engine.callCount)
        assertTrue(session.queueState.value.awaiting)
        assertEquals(0, session.queueState.value.index)
        assertEquals(2, session.queueState.value.size)
    }

    @Test
    fun startingAQueueStopsWhateverWasPlaying() {
        val engine = FakeReaderEngine(alpha)
        val session = ReaderSession(engine)

        session.start(alpha)
        assertTrue(session.isPlaying)

        session.start(queue(beta, gamma))

        // "Play starts a new listening session" — replacing a session is not a resume.
        assertEquals(1, engine.pauseCount)
        assertFalse(session.isPlaying)
        assertTrue(session.queueState.value.awaiting)
    }

    @Test
    fun theQueueIsASnapshotOfTheGivenOrder() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        val source = mutableListOf(QueuedNote.of(gamma), QueuedNote.of(alpha))
        session.start(ActiveQueue.snapshotOf(source, QueueOrigin.BrowseResults))

        // The caller's list is not the queue: a later mutation of the source cannot reorder a session.
        source.clear()

        assertEquals(2, session.queueState.value.size)
        assertEquals("gamma", session.queueState.value.queue?.itemAt(0)?.pageId)
        assertEquals("alpha", session.queueState.value.queue?.itemAt(1)?.pageId)
    }

    @Test
    fun aSingleNoteSessionIsAQueueOfOne() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.start(alpha)

        // "Playing one Note therefore creates an Active Queue containing one item."
        assertEquals(1, session.queueState.value.size)
        assertEquals(QueuedNote.of(alpha), session.queueState.value.currentItem)
        // Null rather than "Note 1 of 1": every Play is a queue of one, so saying so would be noise.
        assertNull(session.queueState.value.positionDescription)
    }

    @Test
    fun queuePositionIsStatedOnlyWhenThereIsMoreThanOneNote() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.start(queue(alpha, beta, gamma))
        session.supplyDocument(alpha)
        assertEquals("Note 1 of 3", session.queueState.value.positionDescription)

        session.onCurrentNoteFinished()
        session.supplyDocument(beta)
        assertEquals("Note 2 of 3", session.queueState.value.positionDescription)
    }

    // --- Supplying documents ----------------------------------------------------------------------

    /**
     * The stale-result rule, at the level of identity rather than of timing.
     *
     * `awaiting` says a Note is wanted; it does not say which. The gap is reachable without any unusual
     * timing: the first Note of a Notion page takes several HTTP requests, so pressing `Play all` from
     * Browse, going Back, changing the search and pressing `Play all` again leaves the first fetch in
     * flight. Before this rule the first fetch's document was accepted by the *second* session — which
     * then read a Note the listener had not queued, at a queue position claiming it was note 1.
     */
    @Test
    fun aDocumentForANoteTheQueueIsNotWaitingForIsRefused() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.start(queue(alpha, beta))
        session.start(queue(gamma))

        // The answer to a question the session has stopped asking.
        session.supplyDocument(alpha)

        assertEquals(0, engine.setDocumentCount)
        assertEquals(QueuedNote.of(gamma), session.queueState.value.currentItem)
        assertTrue("the newer session is still waiting for its own Note", session.queueState.value.awaiting)

        // ...and the rule is not a brick wall: the Note this session *is* waiting for is accepted.
        session.supplyDocument(gamma)
        assertEquals(1, engine.setDocumentCount)
        assertFalse(session.queueState.value.awaiting)
    }

    @Test
    fun aFailureForANoteTheQueueIsNotWaitingForIsIgnored() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.start(queue(alpha, beta))
        session.start(queue(gamma))

        // A slow failure belonging to the replaced session must not stall the one that replaced it, or
        // the Player would offer Retry for a Note this session never asked for.
        session.failCurrentNote("alpha", failure())

        assertFalse(session.queueState.value.isStalled)
        assertTrue(session.queueState.value.awaiting)
    }

    @Test
    fun supplyingTheFirstNotePlaysItWithoutAnnouncingItsTitle() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.start(queue(alpha, beta))
        session.supplyDocument(alpha)

        assertEquals(1, engine.setDocumentCount)
        assertEquals(1, engine.playCount)
        // The listener chose this Note a moment ago and it is on screen; announcing it would be repetition.
        assertTrue(engine.announcements.isEmpty())
        assertFalse(session.queueState.value.awaiting)
        assertTrue(session.isPlaying)
    }

    @Test
    fun supplyingALaterNoteAnnouncesItsTitleBeforeReading() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.start(queue(alpha, beta))
        session.supplyDocument(alpha)
        val playCountAfterFirstNote = engine.playCount
        session.onCurrentNoteFinished()
        session.supplyDocument(beta)

        // "A brief title announcement may identify the next Note." Without it, a listener with the screen
        // off cannot tell a Note change from the reader having stopped.
        assertEquals(listOf("Beta"), engine.announcements)
        // The announcement replaces the plain play for this transition, rather than preceding it.
        assertEquals(playCountAfterFirstNote, engine.playCount)
        assertEquals(2, engine.setDocumentCount)
    }

    @Test
    fun aDocumentArrivingThatNobodyIsWaitingForIsDropped() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.start(queue(alpha, beta))
        session.supplyDocument(alpha)
        val callsAfterFirstLoad = engine.callCount

        // A second supply with nothing awaited is a stale result. Applying it would restart the Note the
        // user is listening to — the same stale-callback problem the engine solves with its token.
        session.supplyDocument(gamma)

        assertEquals(callsAfterFirstLoad, engine.callCount)
        assertEquals(QueuedNote.of(alpha), session.queueState.value.currentItem)
    }

    // --- Advancing --------------------------------------------------------------------------------

    @Test
    fun finishingANoteAdvancesToTheNextWithoutCallingTheEngine() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.start(queue(alpha, beta, gamma))
        session.supplyDocument(alpha)
        val callsBefore = engine.callCount

        session.onCurrentNoteFinished()

        // The engine stopped itself at the end of the document, so there is nothing for the queue to stop.
        assertEquals(callsBefore, engine.callCount)
        assertEquals(1, session.queueState.value.index)
        assertTrue(session.queueState.value.awaiting)
    }

    @Test
    fun finishingTheLastNoteCompletesTheQueue() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.start(queue(alpha))
        session.supplyDocument(alpha)

        session.onCurrentNoteFinished()

        assertTrue(session.queueState.value.completed)
        assertFalse(session.queueState.value.awaiting)
        // Completed, not advanced past the end.
        assertEquals(0, session.queueState.value.index)
        assertFalse(session.queueState.value.hasNext)
    }

    @Test
    fun finishingIsIgnoredWhileWaitingForADocument() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.start(queue(alpha, beta))

        // The engine still shows the *previous* selection's end state at this moment, so the completion
        // signal can arrive while the queue is waiting. Advancing here would skip a Note.
        session.onCurrentNoteFinished()

        assertEquals(0, session.queueState.value.index)
        assertTrue(session.queueState.value.awaiting)
    }

    @Test
    fun finishingIsIgnoredWhenTheQueueIsStalledOrCompleted() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.start(queue(alpha, beta))
        session.failCurrentNote("alpha", failure())
        session.onCurrentNoteFinished()
        assertEquals(0, session.queueState.value.index)
        assertTrue(session.queueState.value.isStalled)

        val second = ReaderSession(FakeReaderEngine())
        second.start(queue(alpha))
        second.supplyDocument(alpha)
        second.onCurrentNoteFinished()
        second.onCurrentNoteFinished()
        assertTrue(second.queueState.value.completed)
        assertEquals(0, second.queueState.value.index)
    }

    // --- Failure ----------------------------------------------------------------------------------

    @Test
    fun aFailedNoteStallsTheQueueAndKeepsItsPosition() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.start(queue(alpha, beta, gamma))
        session.supplyDocument(alpha)
        session.onCurrentNoteFinished()

        session.failCurrentNote("beta", failure())

        // Stalled, not skipped and not aborted: the Note the user asked for is still the current one.
        assertEquals(1, session.queueState.value.index)
        assertEquals(QueuedNote.of(beta), session.queueState.value.currentItem)
        assertTrue(session.queueState.value.isStalled)
        assertFalse(session.queueState.value.awaiting)
        assertFalse(session.queueState.value.completed)
    }

    @Test
    fun retryAsksForTheSameNoteAgain() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.start(queue(alpha, beta))
        session.supplyDocument(alpha)
        session.onCurrentNoteFinished()
        session.failCurrentNote("beta", failure())

        session.retryCurrentNote()

        assertEquals(1, session.queueState.value.index)
        assertTrue(session.queueState.value.awaiting)
        assertNull(session.queueState.value.failure)
        assertNotNull(session.queueState.value.currentItem)
    }

    @Test
    fun retryOnAnUnstalledQueueDoesNothing() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.start(queue(alpha, beta))
        session.supplyDocument(alpha)

        session.retryCurrentNote()

        // Already loading a Note the user is listening to; "retry" must not disturb it.
        assertFalse(session.queueState.value.awaiting)
        assertFalse(session.queueState.value.isStalled)
    }

    @Test
    fun aFailureIsIgnoredWhenThereIsNoQueue() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.failCurrentNote("alpha", failure())

        assertNull(session.queueState.value.queue)
        assertFalse(session.queueState.value.isStalled)
    }

    // --- Play----------------------------------------------------------------------------------------

    @Test
    fun playIsRefusedWhenTheQueueCannotProceed() {
        val engine = FakeReaderEngine(alpha)
        val session = ReaderSession(engine)

        // Awaiting the first document.
        session.start(queue(alpha, beta))
        session.play()
        assertEquals(0, engine.playCount)
        assertFalse(session.canPlay)

        // Stalled on a failed Note.
        session.supplyDocument(alpha)
        session.onCurrentNoteFinished()
        session.failCurrentNote("beta", failure())
        val callsWhileStalled = engine.callCount
        session.play()
        assertEquals(callsWhileStalled, engine.callCount)
        assertFalse(session.canPlay)

        // Completed.
        val completingEngine = FakeReaderEngine(alpha)
        val second = ReaderSession(completingEngine)
        second.start(alpha)
        second.onCurrentNoteFinished()
        assertTrue(second.queueState.value.completed)
        val callsWhenComplete = completingEngine.callCount
        second.play()
        assertEquals(callsWhenComplete, completingEngine.callCount)
        assertFalse(second.canPlay)
    }

    @Test
    fun playResumesANormalSession() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.start(alpha)
        session.pause()

        session.play()

        assertTrue(session.canPlay)
        assertTrue(session.isPlaying)
    }
}
