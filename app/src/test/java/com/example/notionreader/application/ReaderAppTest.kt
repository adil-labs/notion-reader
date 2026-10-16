package com.example.notionreader.application

import com.example.notionreader.application.DefaultLoadReaderDocument
import com.example.notionreader.application.DocumentLoadState
import com.example.notionreader.application.ReaderSession
import com.example.notionreader.data.notion.title
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadCancelledException
import com.example.notionreader.domain.ReaderLoadException
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.player.FakeReaderEngine
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Ownership tests: what each operation is allowed to touch.
 *
 * The UI contract's navigation rules are mostly statements about **who may change what**, so they are
 * tested as such — against a recording engine, asserting that a call did *not* happen. That is the
 * literal form of "navigation does not own playback" and of "manual reading and spoken playback are
 * separate modes", where the alternative is to assert on a screen and hope the coupling stays absent.
 *
 * The first two tests are regressions for a defect that shipped. `MainActivity` starts its Notion load
 * after `setContent`, so the UI is live while a load is in flight; the loader then called
 * `setDocument`, which stops speech and resets the position. Tapping Play on the demo document and
 * letting the load finish silently killed playback, and Retry — the same code path — made it worse,
 * because Retry is pressed exactly when a load is slow. Nothing caught it: the instrumented suite
 * asserts semantics trees, and no test played while a load was in flight.
 *
 * Reintroducing the coupling fails exactly the five tests that assert it is absent, which is how these
 * were shown to have teeth rather than merely to pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderAppTest {

    private val demo = note("demo", "Demo document")
    private val notionPage = note("page-2", "Notion page")

    private fun note(pageId: String, title: String) = ReaderDocument.fromChunkTexts(
        pageId = pageId,
        title = title,
        texts = listOf("First.", "Second.", "Third.", "Fourth.")
    )

    private fun appWith(engine: FakeReaderEngine, inspected: ReaderDocument): ReaderApp =
        ReaderApp(
            session = ReaderSession(engine),
            noteViewer = NoteViewer(inspected),
            navigator = ReaderNavigator(ReaderNavigation(top = ReaderDestination.Browse)),
            loadDocument = DefaultLoadReaderDocument { pageId -> note(pageId, "Queued $pageId") }
        )

    // --- Loading a Note must not touch playback -------------------------------------------------

    @Test
    fun loadingANoteWhilePlayingLeavesTheSessionAlone() = runTest {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        app.playNote()
        engine.nextParagraph()
        engine.nextParagraph()

        val callsBefore = engine.callCount
        val stopsBefore = engine.stopCount
        val positionBefore = engine.state.value.currentParagraphIndex
        assertTrue(engine.state.value.isPlaying)
        assertEquals(2, positionBefore)

        app.loadNote("page-2", DefaultLoadReaderDocument { notionPage })

        // The regression. Before the fix this was 1 stop and a position of 0, with no message.
        assertEquals(callsBefore, engine.callCount)
        assertEquals(stopsBefore, engine.stopCount)
        assertEquals(positionBefore, engine.state.value.currentParagraphIndex)
        assertTrue(engine.state.value.isPlaying)

        // ...and the fix is not "loading stopped working": the Note screen did change, and the
        // session kept reading the document it was given.
        assertEquals(notionPage, app.noteViewer.document.value)
        assertEquals(demo, engine.currentDocument)
    }

    @Test
    fun retryingAFailedLoadWhilePlayingDoesNotInterruptPlayback() = runTest {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        app.playNote()
        val callsBefore = engine.callCount

        val failure = DefaultLoadReaderDocument { throw ReaderLoadException(ReaderErrorCategory.NETWORK_UNAVAILABLE) }
        app.loadNote("page-2", failure)

        assertTrue(app.noteViewer.loadState.value is DocumentLoadState.Error)
        assertTrue(engine.state.value.isPlaying)

        app.loadNote("page-2", DefaultLoadReaderDocument { notionPage })

        assertEquals(callsBefore, engine.callCount)
        assertEquals(notionPage, app.noteViewer.document.value)
        assertEquals(demo, engine.currentDocument)
    }

    @Test
    fun aFailedLoadReportsTheCategoryWithoutBlankingTheNote() = runTest {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        val permission = DefaultLoadReaderDocument { throw ReaderLoadException(ReaderErrorCategory.AUTHORIZATION_REQUIRED) }
        app.loadNote("page-2", permission)

        val state = app.noteViewer.loadState.value
        assertTrue(state is DocumentLoadState.Error)
        state as DocumentLoadState.Error

        // The category survives, so a permission problem is not collapsed into a generic network
        // error and Retry is not offered for something retrying cannot fix.
        assertEquals(ReaderErrorCategory.AUTHORIZATION_REQUIRED, state.category)
        assertFalse(state.canRetry)

        // Content the user can already read is not blanked by a failed refresh of it.
        assertEquals(demo, app.noteViewer.document.value)
        assertEquals(0, engine.callCount)
    }

    @Test
    fun aLoadInFlightReportsLoadingWhileThePreviousNoteStaysReadable() = runTest {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        val gate = CompletableDeferred<Unit>()
        val loader = DefaultLoadReaderDocument {
            gate.await()
            notionPage
        }

        val job = launch { app.loadNote("page-2", loader) }
        runCurrent()

        // "Loading Notes..." is not equivalent to "No matching Notes": the state says loading, and a
        // document is still on screen rather than an empty one.
        assertEquals(DocumentLoadState.Loading, app.noteViewer.loadState.value)
        assertEquals(demo, app.noteViewer.document.value)

        gate.complete(Unit)
        job.join()

        assertEquals(DocumentLoadState.Loaded(notionPage), app.noteViewer.loadState.value)
        assertEquals(0, engine.callCount)
    }

    /**
     * "Last to finish" is not "last requested", and the two only diverge when one load is slower than the
     * next — which for a Notion page is not exotic: opening one is several requests, so a small page
     * routinely overtakes a large one that was asked for first.
     *
     * The damage was not only a stale screen: the viewer wrote its `pageId` when the load *started* and its
     * `document` when it finished, so a late result left those two describing different pages and Retry
     * re-loaded the wrong one.
     */
    @Test
    fun aSupersededNoteLoadCannotOverwriteTheNewerOne() = runTest {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        val slow = CompletableDeferred<Unit>()
        val slowPage = note("page-slow", "Slow page")
        val fastPage = note("page-fast", "Fast page")

        val first = launch { app.loadNote("page-slow", DefaultLoadReaderDocument { slow.await(); slowPage }) }
        runCurrent()

        val second = launch { app.loadNote("page-fast", DefaultLoadReaderDocument { fastPage }) }
        second.join()
        assertEquals(fastPage, app.noteViewer.document.value)

        slow.complete(Unit)
        first.join()

        assertEquals("the late answer is dropped, not applied", fastPage, app.noteViewer.document.value)
        assertEquals("page-fast", app.noteViewer.pageId.value)
        assertEquals(DocumentLoadState.Loaded(fastPage), app.noteViewer.loadState.value)
    }

    @Test
    fun aSupersededFailureCannotReportAnErrorOverTheNewerNote() = runTest {
        val app = appWith(FakeReaderEngine(demo), inspected = demo)

        val slow = CompletableDeferred<Unit>()
        val fastPage = note("page-fast", "Fast page")

        val failing = launch {
            app.loadNote(
                "page-slow",
                DefaultLoadReaderDocument {
                    slow.await()
                    throw ReaderLoadException(ReaderErrorCategory.NETWORK_UNAVAILABLE)
                }
            )
        }
        runCurrent()

        val succeeding = launch { app.loadNote("page-fast", DefaultLoadReaderDocument { fastPage }) }
        succeeding.join()

        slow.complete(Unit)
        failing.join()

        // An error from a request the user has moved on from must not be put over a Note they can read.
        assertEquals(fastPage, app.noteViewer.document.value)
        assertFalse(app.noteViewer.loadState.value is DocumentLoadState.Error)
    }

    @Test
    fun cancellingALoadIsNotReportedAsAFailure() = runTest {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        val loader = DefaultLoadReaderDocument { throw ReaderLoadCancelledException() }
        val thrown = runCatching { app.loadNote("page-2", loader) }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertFalse(app.noteViewer.loadState.value is DocumentLoadState.Error)
        assertEquals(0, engine.callCount)
    }

    // --- Play freezes a snapshot ----------------------------------------------------------------

    @Test
    fun playFromTheNoteScreenStartsANewSessionFromTheBeginning() {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        // Put the session somewhere other than the start, so "from the beginning" is observable.
        engine.nextParagraph()
        engine.nextParagraph()
        assertEquals(2, engine.state.value.currentParagraphIndex)
        assertFalse(engine.state.value.isPlaying)

        app.playNote()

        // Play was pressed on the Note screen, so the Note is the previous visual context.
        assertEquals(ReaderDestination.Player, app.navigator.state.value.current)
        assertEquals(0, engine.state.value.currentParagraphIndex)
        assertTrue(engine.state.value.isPlaying)
        assertEquals(1, engine.setDocumentCount)
    }

    @Test
    fun playFreezesTheInspectedNoteSoALaterLoadCannotChangeWhatIsRead() = runTest {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        app.playNote()
        assertEquals(demo, engine.currentDocument)

        app.loadNote("page-2", DefaultLoadReaderDocument { notionPage })

        // One transfer point, so the snapshot cannot be re-read: the same shape as "Saved Queue edits
        // must not mutate an Active Queue already created from that Saved Queue", at one-Note scale.
        assertEquals(demo, engine.currentDocument)
        assertEquals(notionPage, app.noteViewer.document.value)
    }

    // --- Navigation does not own playback -------------------------------------------------------

    @Test
    fun navigatingBetweenSurfacesNeverCallsTheEngine() {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        app.openNote()
        app.openBrowse()
        app.openHome()
        app.openBrowse()
        app.back()

        assertEquals(0, engine.callCount)
        assertEquals(ReaderDestination.Home, app.navigator.state.value.current)
    }

    @Test
    fun backFromThePlayerReturnsToTheContextItWasOpenedFrom() {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        // Play from the Note screen: the Note is the previous visual context, not the tab underneath it.
        app.openNote()
        app.playNote()
        assertEquals(ReaderDestination.Player, app.navigator.state.value.current)

        val callsBefore = engine.callCount
        val positionBefore = engine.state.value.currentParagraphIndex

        assertTrue(app.back())

        assertEquals(ReaderDestination.Note, app.navigator.state.value.current)
        // "Back reverses visual navigation. It does not reverse playback."
        assertTrue(engine.state.value.isPlaying)
        assertEquals(positionBefore, engine.state.value.currentParagraphIndex)
        assertEquals(callsBefore, engine.callCount)
    }

    @Test
    fun backFromAPlayAllPlayerReturnsToBrowse() {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        app.playAll(queued("Alpha", "Beta"))
        assertEquals(ReaderDestination.Player, app.navigator.state.value.current)

        assertTrue(app.back())

        // The result set is the context: this is the contract's worked example
        // (Browse -> Play all -> Player -> Back -> Browse), with playback continuing.
        assertEquals(ReaderDestination.Browse, app.navigator.state.value.current)
        assertEquals(ReaderDestination.Browse, app.navigator.state.value.top)
    }

    @Test
    fun backAtHomeIsLeftToTheSystem() {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        app.openHome()

        // Not consumed, so the platform's normal behaviour applies rather than a Back that pauses.
        assertFalse(app.back())
        assertEquals(ReaderDestination.Home, app.navigator.state.value.current)
        assertEquals(0, engine.callCount)
    }

    @Test
    fun backFromANonHomeTabReturnsToHome() {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        app.openBrowse()
        assertTrue(app.back())

        // Product decision (§12.5): bottom-navigation convention rather than finishing the app.
        assertEquals(ReaderDestination.Home, app.navigator.state.value.current)
        assertEquals(0, engine.callCount)
    }

    @Test
    fun thePlayerControlResumesTheExistingSessionRatherThanStartingANewOne() {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        app.playNote()
        engine.nextParagraph()
        val positionBefore = engine.state.value.currentParagraphIndex

        app.togglePlayPause()
        assertFalse(engine.state.value.isPlaying)

        app.togglePlayPause()
        assertTrue(engine.state.value.isPlaying)

        // A new session would have reset the position and called setDocument a second time.
        assertEquals(positionBefore, engine.state.value.currentParagraphIndex)
        assertEquals(1, engine.setDocumentCount)
    }

    // --- Play all and the queue -------------------------------------------------------------------

    /** A queue of identities, as a Browse result set is. Titles only; content is fetched lazily. */
    private fun queued(vararg titles: String) =
        titles.map { QueuedNote(pageId = "page-$it", title = it) }

    @Test
    fun playAllSnapshotsTheVisibleOrderAndOpensThePlayer() {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        app.playAll(queued("Alpha", "Beta", "Gamma"))

        assertEquals(ReaderDestination.Player, app.navigator.state.value.current)
        assertEquals(3, app.session.queueState.value.size)
        assertEquals("Alpha", app.session.queueState.value.currentItem?.title)
        assertEquals(QueueOrigin.BrowseResults, app.session.queueState.value.queue?.origin)

        // Nothing is loaded or played yet: the first Note is awaited, exactly like every later one.
        assertEquals(0, engine.callCount)
        assertTrue(app.session.queueState.value.awaiting)
    }

    @Test
    fun playAllOnAnEmptyResultSetStartsNothing() {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        app.playAll(emptyList())

        // "Play all and Add all to queue must not appear when there are no results" — and if the action is
        // somehow reached anyway, it must not open a Player over an empty queue.
        assertEquals(ReaderDestination.Browse, app.navigator.state.value.current)
        assertNull(app.session.queueState.value.queue)
        assertEquals(0, engine.callCount)
    }

    @Test
    fun theQueuePlaysEachNoteInOrderAndCompletesAtTheEnd() = runTest {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        app.playAll(queued("Alpha", "Beta", "Gamma"))

        // Note 1: loaded and played, not announced.
        assertTrue(app.loadPendingQueueNote())
        assertEquals("page-Alpha", engine.currentDocument.pageId)
        assertEquals(1, engine.playCount)
        assertTrue(engine.announcements.isEmpty())

        // Note 1 ends -> the queue advances, then the next Note is fetched and announced.
        app.onNoteFinished()
        assertTrue(app.session.queueState.value.awaiting)
        assertTrue(app.loadPendingQueueNote())
        assertEquals("page-Beta", engine.currentDocument.pageId)
        assertEquals(listOf("Beta"), engine.announcements)

        // Note 2 ends the same way, and announces the Note that actually started.
        app.onNoteFinished()
        assertTrue(app.loadPendingQueueNote())
        assertEquals("page-Gamma", engine.currentDocument.pageId)
        assertEquals(listOf("Beta", "Gamma"), engine.announcements)

        // The last Note completes the queue rather than advancing past it.
        app.onNoteFinished()
        assertTrue(app.session.queueState.value.completed)
        assertFalse(app.session.queueState.value.awaiting)
        assertFalse(app.session.canPlay)
    }

    @Test
    fun aQueueNoteThatFailsToLoadStallsTheQueueAtThatNote() = runTest {
        val engine = FakeReaderEngine(demo)
        // Alpha succeeds; Beta fails; Gamma would succeed but must not be reached.
        val app = ReaderApp(
            session = ReaderSession(engine),
            noteViewer = NoteViewer(demo),
            navigator = ReaderNavigator(ReaderNavigation(top = ReaderDestination.Browse)),
            loadDocument = DefaultLoadReaderDocument { pageId ->
                if (pageId == "page-Beta") {
                    throw ReaderLoadException(ReaderErrorCategory.NETWORK_UNAVAILABLE)
                }
                note(pageId, "Queued $pageId")
            }
        )

        app.playAll(queued("Alpha", "Beta", "Gamma"))
        assertTrue(app.loadPendingQueueNote())
        app.onNoteFinished()

        assertTrue(app.loadPendingQueueNote())

        // The queue is stalled on Beta: not skipped to Gamma, and not aborted back to nothing.
        val stalled = app.session.queueState.value
        assertTrue(stalled.isStalled)
        assertEquals("Beta", stalled.currentItem?.title)
        assertEquals(1, stalled.index)
        assertEquals(ReaderErrorCategory.NETWORK_UNAVAILABLE, stalled.failure?.category)

        // A load attempt stops while stalled, so a caller looping on `awaiting` cannot spin.
        assertFalse(app.loadPendingQueueNote())
    }

    @Test
    fun aQueueNoteWithNothingToReadStallsInsteadOfStallingSilently() = runTest {
        val engine = FakeReaderEngine(demo)
        // A titled but empty Notion page. The integration returns a *valid* document for one — a page with
        // no blocks is an empty document rather than a failure — and Browse lists it, so `Play all` can
        // reach it.
        val app = ReaderApp(
            session = ReaderSession(engine),
            noteViewer = NoteViewer(demo),
            navigator = ReaderNavigator(ReaderNavigation(top = ReaderDestination.Browse)),
            loadDocument = DefaultLoadReaderDocument { pageId ->
                if (pageId == "page-Alpha") ReaderDocument(pageId = pageId, title = "Alpha")
                else note(pageId, "Queued $pageId")
            }
        )

        app.playAll(queued("Alpha", "Beta"))
        assertTrue(app.loadPendingQueueNote())

        // Reported, not accepted. An empty document never produces the completion signal the queue
        // advances on — the engine returns immediately when there is nothing to speak and `hasReachedEnd`
        // stays false — so accepting it left the session on that Note for the life of the process, with
        // an enabled Play button that did nothing and no explanation.
        val stalled = app.session.queueState.value
        assertTrue(stalled.isStalled)
        assertEquals("Alpha", stalled.currentItem?.title)
        assertEquals(0, stalled.index)
        assertEquals(ReaderErrorCategory.UNSUPPORTED_DOCUMENT_STRUCTURE, stalled.failure?.category)
        assertFalse(app.session.canPlay)
        assertEquals("nothing was handed to the engine", 0, engine.setDocumentCount)
    }

    /**
     * Two callers, one Note.
     *
     * `Continue` restores a session *and* the queue observer sees the same state change, so both reach the
     * loader for the same request. The duplicate is not merely wasteful — a page is several HTTP requests —
     * it is inconsistent: if exactly one of the two attempts failed, the queue would stall on a Note that
     * had in fact arrived, because a failure cannot be told from a request that is still running.
     */
    @Test
    fun aNoteIsFetchedOnceEvenWhenItIsAskedForTwice() = runTest {
        val engine = FakeReaderEngine(demo)
        var fetches = 0
        val gate = CompletableDeferred<Unit>()
        val app = ReaderApp(
            session = ReaderSession(engine),
            noteViewer = NoteViewer(demo),
            navigator = ReaderNavigator(ReaderNavigation(top = ReaderDestination.Browse)),
            loadDocument = DefaultLoadReaderDocument { pageId ->
                fetches += 1
                gate.await()
                note(pageId, "Queued $pageId")
            }
        )

        app.playAll(queued("Alpha", "Beta"))

        val first = launch { app.loadPendingQueueNote() }
        runCurrent()
        assertEquals("the first ask is fetching", 1, fetches)

        val second = launch { app.loadPendingQueueNote() }
        runCurrent()
        assertEquals("the second ask is the same request, so it starts nothing", 1, fetches)

        gate.complete(Unit)
        first.join()
        second.join()

        assertEquals(1, fetches)
        assertFalse(app.session.queueState.value.awaiting)
        assertEquals("page-Alpha", engine.currentDocument.pageId)
    }

    @Test
    fun retryingAStalledQueueNoteResumesFromThatNote() = runTest {
        val engine = FakeReaderEngine(demo)
        // The *second* Note fails to load, the first time it is asked for.
        var betaIsBroken = true
        val app = ReaderApp(
            session = ReaderSession(engine),
            noteViewer = NoteViewer(demo),
            navigator = ReaderNavigator(ReaderNavigation(top = ReaderDestination.Browse)),
            loadDocument = DefaultLoadReaderDocument { pageId ->
                if (pageId == "page-Beta" && betaIsBroken) {
                    throw ReaderLoadException(ReaderErrorCategory.NETWORK_UNAVAILABLE)
                }
                note(pageId, "Queued $pageId")
            }
        )

        app.playAll(queued("Alpha", "Beta"))
        assertTrue(app.loadPendingQueueNote())
        assertEquals("page-Alpha", engine.currentDocument.pageId)

        app.onNoteFinished()
        assertTrue(app.loadPendingQueueNote())
        assertTrue(app.session.queueState.value.isStalled)

        betaIsBroken = false
        app.retryQueueNote()
        assertTrue(app.loadPendingQueueNote())

        // Same Note as before the failure, resumed rather than restarted or skipped.
        assertEquals("page-Beta", engine.currentDocument.pageId)
        assertFalse(app.session.queueState.value.isStalled)
        assertEquals(1, app.session.queueState.value.index)
    }

    @Test
    fun loadingIsSkippedWhenNoQueueNoteIsWaiting() = runTest {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        // Idle: no queue at all.
        assertFalse(app.loadPendingQueueNote())
        assertEquals(0, engine.callCount)

        // A single-Note session: loaded and playing, so nothing is awaited.
        app.playNote()
        assertFalse(app.loadPendingQueueNote())
    }

    @Test
    fun reSortingTheSourceAfterPlayAllCannotChangeWhatIsQueued() = runTest {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        val results = queued("Alpha", "Beta").toMutableList()
        app.playAll(results)

        // The contract's guarantee: "Browse filter or sort changes must not mutate an Active Queue
        // already created from that result set." Re-sorting the source is that, at one remove.
        results.reverse()
        results.clear()

        assertEquals(2, app.session.queueState.value.size)
        assertTrue(app.loadPendingQueueNote())
        assertEquals("page-Alpha", engine.currentDocument.pageId)
    }

    @Test
    fun loadingAQueueNoteNeverTouchesTheNoteScreen() = runTest {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        app.playAll(queued("Alpha"))
        assertTrue(app.loadPendingQueueNote())

        // Same separation as the Note screen's own load: the inspected Note and the listened Note are
        // different state, so a queue transition cannot rewrite what the user is reading.
        assertEquals(demo, app.noteViewer.document.value)
    }

    // --- What Back returns focus to -----------------------------------------------------------------
    //
    // The navigator's own rules are `ReaderNavigatorTest`; these assert that each *route* records the right
    // identity, which is the half the navigator cannot check. A route that passed the wrong id would look
    // correct in isolation and focus the wrong thing on a real device — and focus is not something a JVM
    // test can see, so the id is asserted here and its effect is asserted in the instrumented suite.

    @Test
    fun openingABrowseResultRemembersTheRowToReturnTo() = runTest {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        app.openNote("page-2")

        // Navigating forward must not leave a hint pending, or it would fire on the next return.
        assertNull(app.navigator.state.value.focusTarget)

        app.back()

        // The page id: a result row's identity, and therefore the row the listener can be sent back to.
        assertEquals("page-2", app.navigator.state.value.focusTarget)
    }

    @Test
    fun backFromThePlayerReturnsFocusOneScreenAtATime() = runTest {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        app.openNote("page-2")
        app.playNote()

        assertEquals(ReaderDestination.Player, app.navigator.state.value.current)

        // Back to the Note: Play is the control that was pressed, and it is a named control because the
        // Play buttons of two different Notes are the same button.
        app.back()
        assertEquals(ReaderDestination.Note, app.navigator.state.value.current)
        assertEquals(ReaderFocus.NotePlay, app.navigator.state.value.focusTarget)

        // Back to Browse: now the row it was opened from.
        app.focusHandled()
        app.back()
        assertEquals(ReaderDestination.Browse, app.navigator.state.value.current)
        assertEquals("page-2", app.navigator.state.value.focusTarget)
    }

    @Test
    fun backFromPlayAllReturnsFocusToTheSetLevelAction() = runTest {
        val engine = FakeReaderEngine(demo)
        val app = appWith(engine, inspected = demo)

        app.playAll(queued("Alpha", "Beta"))
        app.back()

        // Nothing here has a content identity — `Play all` acts on the whole result set — so the control
        // is what is remembered. The alternative would be no hint at all, leaving the listener at the top
        // of a list they had deliberately left from its foot.
        assertEquals(ReaderFocus.BrowsePlayAll, app.navigator.state.value.focusTarget)
    }

    @Test
    fun continueRemembersTheHistoryEntryItWasPressedOn() = runTest {
        val engine = FakeReaderEngine(demo)
        val app = ReaderApp(
            session = ReaderSession(engine),
            noteViewer = NoteViewer(demo),
            navigator = ReaderNavigator(ReaderNavigation(top = ReaderDestination.Queues)),
            loadDocument = DefaultLoadReaderDocument { pageId -> note(pageId, "Queued $pageId") }
        )

        app.playAll(queued("Alpha"))
        val sessionId = app.session.id
        app.loadPendingQueueNote()
        app.back()

        app.continueSession(sessionId!!)
        app.back()

        // A history entry is identified by its session and never by its title, which is the same identity
        // the entry itself uses — so Continue and Back cannot disagree about which entry is which.
        assertEquals(sessionId, app.navigator.state.value.focusTarget)
    }

    @Test
    fun systemSpeechRateAndPitchAreResolvedWhenConfiguredAsSystemDefault() = runTest {
        val fakeSpeech = com.example.notionreader.player.FakeSpeechEngine()
        fakeSpeech.systemRateToReturn = 1.3f
        fakeSpeech.systemPitchToReturn = 0.85f

        val engine = FakeReaderEngine(demo)
        val settingsRepo = InMemorySettingsRepository()
        settingsRepo.updateSettings {
            it.copy(
                speechRate = com.example.notionreader.domain.settings.SpeechRate.SYSTEM_DEFAULT,
                voicePitch = com.example.notionreader.domain.settings.VoicePitch.SYSTEM_DEFAULT
            )
        }

        val app = ReaderApp(
            session = ReaderSession(engine),
            noteViewer = NoteViewer(demo),
            navigator = ReaderNavigator(),
            settingsRepository = settingsRepo,
            loadDocument = DefaultLoadReaderDocument { pageId -> note(pageId, "Queued $pageId") },
            speechEngine = fakeSpeech
        )

        app.applySettings(settingsRepo.settings.value)
        assertEquals(1.3f, fakeSpeech.lastConfiguredRate)
        assertEquals(0.85f, fakeSpeech.lastConfiguredPitch)
    }

    @Test
    fun clearHistoryWhilePlayingKeepsPlaybackButOnlyCurrentSessionMayReappear() = runTest {
        val now = 1_000_000L
        val engine = FakeReaderEngine(demo)
        val store = com.example.notionreader.domain.session.InMemorySessionStore()
        val oldSession = com.example.notionreader.domain.session.StoredSession(
            id = "historical-session-old",
            notes = listOf(QueuedNote("old-1", "Old Note")),
            origin = QueueOrigin.SingleNote,
            index = 0,
            position = null,
            completed = true,
            wasPlaying = false,
            lastActivityAt = now - 5_000L
        )
        store.write(listOf(oldSession))

        val app = ReaderApp(
            session = ReaderSession(engine),
            noteViewer = NoteViewer(demo),
            navigator = ReaderNavigator(),
            loadDocument = DefaultLoadReaderDocument { pageId -> note(pageId, "Queued $pageId") },
            sessionStore = store,
            clock = { now }
        )

        app.loadHistory()
        assertEquals(1, app.completedSessions.size)

        // Start a live playback session
        app.playAll(listOf(QueuedNote(demo.pageId, demo.title)))
        assertTrue(app.isSessionResumable)

        // Clear history: old history is purged, live session playback remains untouched
        app.clearHistory()
        assertEquals(0, store.read().size)
        assertTrue("Live session remains resumable/active", app.isSessionResumable)
        assertEquals("Historical completed sessions are wiped", 0, app.completedSessions.size)

        // Later persist of the live active session records only this session
        app.persistSession()
        val persisted = store.read()
        assertEquals("Only the active session reappears in store", 1, persisted.size)
        assertEquals(app.session.id!!, persisted.first().id)
    }

    @Test
    fun openExploreCardAppliesFacetFiltersDirectly() = runTest {
        val engine = FakeReaderEngine(demo)
        val app = ReaderApp(
            session = ReaderSession(engine),
            noteViewer = NoteViewer(demo),
            navigator = ReaderNavigator(),
            loadDocument = DefaultLoadReaderDocument { pageId -> note(pageId, "Queued $pageId") }
        )

        val topicCard = ExploreCard(title = "Kotlin", noteCount = 5, queryText = "Kotlin", topic = "Kotlin")
        app.openExploreCard(topicCard)
        assertEquals(ReaderDestination.Browse, app.navigator.state.value.current)
        assertEquals("Kotlin", app.browse.state.value.query.selectedTopic)
        assertNull(app.browse.state.value.query.selectedArea)

        val areaCard = ExploreCard(title = "Engineering", noteCount = 3, queryText = "Engineering", area = "Engineering")
        app.openExploreCard(areaCard)
        assertEquals(ReaderDestination.Browse, app.navigator.state.value.current)
        assertEquals("Engineering", app.browse.state.value.query.selectedArea)
        assertNull(app.browse.state.value.query.selectedTopic)
    }

    @Test
    fun exploreCatalogKeepsEveryAreaDiscoverableWhenTopicsDominateOneArea() {
        val app = appWith(FakeReaderEngine(demo), inspected = demo)
        app.browse.setLoadedEntries(
            listOf(
                ReaderIndexEntry("chess-1", "Chess one", Instant.EPOCH, area = "Chess", topics = setOf("Openings", "Tactics")),
                ReaderIndexEntry("chess-2", "Chess two", Instant.EPOCH, area = "Chess", topics = setOf("Endgames", "Strategy")),
                ReaderIndexEntry("business-1", "Business", Instant.EPOCH, area = "Business & Strategy", topics = setOf("Unit economics")),
                ReaderIndexEntry("mind-1", "Mind", Instant.EPOCH, area = "Mind & Learning", topics = setOf("Mindfulness"))
            )
        )

        val catalog = app.exploreCatalog()

        assertEquals(listOf("Business & Strategy", "Chess", "Mind & Learning"), catalog.areas.map { it.title })
        assertEquals(1, catalog.areas.first().noteCount)
        assertEquals(listOf("Endgames", "Mindfulness", "Openings", "Strategy", "Tactics", "Unit economics"), catalog.topics.map { it.title })
    }

    @Test
    fun exploreCatalogCountsOneNoteOnceWhenItRepeatsATopic() {
        val app = appWith(FakeReaderEngine(demo), inspected = demo)
        app.browse.setLoadedEntries(
            listOf(
                ReaderIndexEntry("one", "First", Instant.EPOCH, area = "Engineering", topics = setOf("Kotlin")),
                ReaderIndexEntry("two", "Second", Instant.EPOCH, area = "Engineering", topics = setOf("Kotlin"))
            )
        )

        assertEquals(2, app.exploreCatalog().topics.single { it.title == "Kotlin" }.noteCount)
    }

    @Test
    fun exploreCatalogIsASecondaryScreenThatReturnsToBrowse() {
        val engine = FakeReaderEngine(demo)
        val app = ReaderApp(
            session = ReaderSession(engine),
            noteViewer = NoteViewer(demo),
            navigator = ReaderNavigator(),
            loadDocument = DefaultLoadReaderDocument { pageId -> note(pageId, "Queued $pageId") }
        )

        app.openBrowse()
        app.openExploreCatalog()
        assertEquals(ReaderDestination.ExploreCatalog, app.navigator.state.value.current)
        assertTrue(app.back())
        assertEquals(ReaderDestination.Browse, app.navigator.state.value.current)
        assertFalse(engine.state.value.isPlaying)
    }
}
