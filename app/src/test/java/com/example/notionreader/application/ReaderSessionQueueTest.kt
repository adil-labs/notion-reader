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
import org.junit.Assert.assertNotEquals
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
    fun theEndOfAQueueIsSpokenRatherThanOnlyShown() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.start(queue(alpha))
        session.supplyDocument(alpha)

        session.onCurrentNoteFinished()

        // Phase 2 of the Core Player & TTS Playback spec. The Player's "End of queue" line is a live
        // region, and a live region is read to nobody when no screen reader is running. With the screen off
        // the reader's own voice is the listener's only channel, so a queue that merely stopped would be
        // indistinguishable by ear from a reader that had failed.
        assertEquals(listOf("End of queue"), engine.standaloneAnnouncements)

        // And announcing is an utterance, not a move: it must not start a reading pass and must not stop
        // one. Asserted against the engine's call counts rather than against `session.isPlaying`, because
        // this test drives the completion signal directly and so the engine is still in whatever state
        // `supplyDocument` left it — the same mistake as asserting about a node that was never drawn.
        assertEquals(1, engine.playCount)
        assertEquals(0, engine.pauseCount)
        assertTrue(session.queueState.value.completed)
    }

    @Test
    fun theEndOfAQueueIsSpokenOnceHoweverOftenTheCompletionSignalArrives() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.start(queue(alpha))
        session.supplyDocument(alpha)

        // The signal arrives from an observer of engine state, and the engine keeps reporting the end of a
        // document until a new one is set — so a repeated signal is the normal case, not a hypothetical
        // one. "Announce that the queue has ended" means once, not once per emission.
        session.onCurrentNoteFinished()
        session.onCurrentNoteFinished()
        session.onCurrentNoteFinished()

        assertEquals(1, engine.standaloneAnnouncements.size)
    }

    @Test
    fun advancingMidQueueSaysNothingAboutTheEndOfTheQueue() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.start(queue(alpha, beta))
        session.supplyDocument(alpha)

        session.onCurrentNoteFinished()

        // A Note transition announces the *next Note's title*, and it does so when that document arrives
        // rather than as the queue moves. So nothing is uttered at the moment of the transition — and in
        // particular the queue that has not ended must not say that it has.
        assertTrue(engine.standaloneAnnouncements.isEmpty())
        assertEquals(1, session.queueState.value.index)
        assertFalse(session.queueState.value.completed)
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

    // --- Moving between Notes by hand -----------------------------------------------------------------
    //
    // The Player's `Next note` / `Previous note` / queue-row controls. Every case below is a transition, so
    // it is driven one step at a time and inspected between steps like the rest of this file.

    @Test
    fun nextNoteMovesToTheFollowingNoteAndWaitsForIt() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.start(queue(alpha, beta, gamma))
        session.supplyDocument(alpha)

        assertTrue(session.nextNote())

        assertEquals(1, session.queueState.value.index)
        assertEquals("beta", session.queueState.value.currentItem?.pageId)
        assertTrue(session.queueState.value.awaiting)

        session.supplyDocument(beta)
        assertFalse(session.queueState.value.awaiting)
    }

    @Test
    fun nextNoteAtTheEndIsRefusedAndChangesNothing() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.start(queue(alpha, beta))
        session.supplyDocument(alpha)
        session.onCurrentNoteFinished()
        session.supplyDocument(beta)

        assertFalse(session.nextNote())

        // The button is disabled at the boundary, and a disabled control that was nonetheless reached must
        // not move the session — the same rule the section controls follow.
        assertEquals(1, session.queueState.value.index)
        assertFalse(session.queueState.value.awaiting)
    }

    @Test
    fun previousNoteStepsBackAWholeNote() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.start(queue(alpha, beta))
        session.supplyDocument(alpha)
        session.onCurrentNoteFinished()
        session.supplyDocument(beta)

        assertTrue(session.previousNote())

        assertEquals(0, session.queueState.value.index)
        assertEquals("alpha", session.queueState.value.currentItem?.pageId)
        assertTrue(session.queueState.value.awaiting)
    }

    @Test
    fun previousNoteAtTheFirstNoteIsRefused() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.start(queue(alpha, beta))
        session.supplyDocument(alpha)

        assertFalse(session.previousNote())
        assertEquals(0, session.queueState.value.index)
    }

    @Test
    fun aManualMoveStopsTheNoteItIsLeaving() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.start(queue(alpha, beta))
        session.supplyDocument(alpha)
        assertTrue(session.isPlaying)

        session.nextNote()

        // Not a cross-fade: leaving the previous Note speaking while the next one loads would read content
        // the listener has just navigated away from.
        assertFalse(session.isPlaying)
        assertEquals(1, engine.pauseCount)
    }

    /**
     * The defect this round found, and the reason `playWhenSupplied` exists.
     *
     * `Next note` while paused used to start speaking the following Note, because the load path called
     * `engine.play()` unconditionally. That contradicts the behaviour the section controls already had and
     * the README documents ("while paused they only move the position"), and it makes a *navigation*
     * control own playback — the product contract's "navigation does not own playback" in its plainest
     * form: the listener had just said they did not want to hear anything.
     */
    @Test
    fun movingWhilePausedLoadsTheNoteWithoutStartingIt() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.start(queue(alpha, beta))
        session.supplyDocument(alpha)
        session.pause()

        val speaksBefore = engine.speakCount
        assertTrue(session.nextNote())
        session.supplyDocument(beta)

        // Loaded and on screen, so the Player can show the new Note and its position...
        assertEquals("beta", engine.currentDocument.pageId)
        assertFalse(session.queueState.value.awaiting)

        // ...and silent: nothing spoken, nothing announced, still paused.
        assertFalse(session.isPlaying)
        assertEquals(speaksBefore, engine.speakCount)
        assertEquals(0, engine.announceThenPlayCount)
    }

    @Test
    fun movingWhilePlayingKeepsReadingTheNewNote() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.start(queue(alpha, beta))
        session.supplyDocument(alpha)
        assertTrue(session.isPlaying)

        session.nextNote()
        session.supplyDocument(beta)

        // Hands-free reading continues across a manual move, and the new Note is announced because an
        // unannounced Note change is indistinguishable from the reader having stopped.
        assertTrue(session.isPlaying)
        assertEquals(listOf("Beta"), engine.announcements)
    }

    /**
     * The bug that would have been introduced by the fix above, caught before it shipped.
     *
     * A silent load leaves "stay silent" recorded. If the *next*, automatic transition inherited it, the
     * Note after a paused manual move would load silently too — so a listener who pressed `Next note` while
     * paused, then `Play`, would have hands-free reading stop dead at the end of that Note.
     */
    @Test
    fun aSilentlyLoadedNoteStillAdvancesLoudlyWhenItFinishes() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.start(queue(alpha, beta, gamma))

        session.supplyDocument(alpha)
        session.pause()
        session.nextNote()
        session.supplyDocument(beta)
        assertFalse(session.isPlaying)

        // The listener resumes, reads beta to its end, and the queue advances on its own.
        session.play()
        assertTrue(session.isPlaying)
        session.onCurrentNoteFinished()
        session.supplyDocument(gamma)

        assertTrue("automatic progression must keep reading", session.isPlaying)
        assertEquals(listOf("Gamma"), engine.announcements)
    }

    @Test
    fun jumpingToANoteMovesToIt() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.start(queue(alpha, beta, gamma))
        session.supplyDocument(alpha)

        assertTrue(session.jumpToNote(2))

        assertEquals(2, session.queueState.value.index)
        assertEquals("gamma", session.queueState.value.currentItem?.pageId)
        assertTrue(session.queueState.value.awaiting)
    }

    @Test
    fun jumpingOutsideTheQueueIsRefused() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.start(queue(alpha, beta))
        session.supplyDocument(alpha)

        assertFalse(session.jumpToNote(2))
        assertFalse(session.jumpToNote(-1))
        assertEquals(0, session.queueState.value.index)
    }

    /**
     * Selecting the row the listener is already on must not rewind it.
     *
     * The queue view's rows are how a listener moves the position, and a row that is already the position
     * says so. The first version re-loaded the current Note from its first chunk, so tapping that row threw
     * away the listener's place in the Note they were listening to — a destructive reading of an action
     * that only says "go here".
     */
    @Test
    fun jumpingToTheCurrentNoteIsNotATransition() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.start(queue(alpha, beta))
        session.supplyDocument(alpha)
        engine.nextParagraph()

        val positionBefore = engine.state.value.currentParagraphIndex
        val callsBefore = engine.callCount

        assertTrue(session.jumpToNote(0))

        assertFalse(session.queueState.value.awaiting)
        assertEquals(callsBefore, engine.callCount)
        assertEquals(positionBefore, engine.state.value.currentParagraphIndex)
    }

    /**
     * The starvation case, expressed as the thing an observer actually collects.
     *
     * The queue is `[alpha, alpha, alpha]` deliberately: three entries for **one page**, so the page id is
     * identical at every index and only the request token distinguishes them. This is the exact queue
     * `ReaderViewModel` walks, and the property the trigger depends on is that a move which replaces an
     * in-flight fetch produces a *different* [PendingNoteRequest]. Without that, the trigger sees no change,
     * the new Note is never fetched, and the superseded arrival is refused by the identity guard — so the
     * session waits forever for a request nobody made, with the Player showing "Loading…" and every control
     * disabled. Two presses of `Next note` on a slow page is all it takes.
     */
    @Test
    fun aMoveThatReplacesAnInFlightFetchIsVisibleToAnObserver() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.start(queue(alpha, alpha, alpha))
        session.supplyDocument(alpha)

        session.nextNote()
        val fetchingTheFirstMove = session.queueState.value.pendingRequest

        session.nextNote()
        val fetchingTheSecondMove = session.queueState.value.pendingRequest

        // The same page is awaited both times, and `awaiting` is true on both sides of the transition...
        assertNotNull(fetchingTheFirstMove)
        assertNotNull(fetchingTheSecondMove)
        assertEquals(fetchingTheFirstMove?.pageId, fetchingTheSecondMove?.pageId)

        // ...so the request token is the only thing that makes the replacement observable, and it is what
        // lets the trigger fire a second time rather than waiting for a request that no longer exists.
        assertNotEquals(fetchingTheFirstMove, fetchingTheSecondMove)
    }

    @Test
    fun movingOnWhileAFetchIsInFlightAsksForTheNewNoteRatherThanTheOld() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.start(queue(alpha, beta, gamma))
        session.supplyDocument(alpha)

        session.nextNote()
        val first = session.queueState.value.pendingRequest

        // Replaced before the first fetch returns.
        session.nextNote()
        val second = session.queueState.value.pendingRequest

        assertEquals("beta", first?.pageId)
        assertEquals(2, session.queueState.value.index)
        assertEquals("gamma", second?.pageId)

        // The superseded arrival is refused by identity, so the queue keeps waiting for the request it
        // actually made instead of being filled by one it did not.
        session.supplyDocument(beta)
        assertTrue(session.queueState.value.awaiting)
        assertEquals("gamma", session.queueState.value.currentItem?.pageId)

        session.supplyDocument(gamma)
        assertFalse(session.queueState.value.awaiting)
        assertEquals("gamma", engine.currentDocument.pageId)
    }
}
