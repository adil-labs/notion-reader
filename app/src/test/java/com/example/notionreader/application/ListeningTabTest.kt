package com.example.notionreader.application

import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.queuedNote
import com.example.notionreader.domain.session.InMemorySessionStore
import com.example.notionreader.domain.session.SessionStore
import com.example.notionreader.domain.storedSession
import com.example.notionreader.player.FakeReaderEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Listening tab's read models.
 *
 * These assert the *surface-independent* half of the Queues slice: which sessions each section lists,
 * in what order, and what each entry says about itself. The composables are asserted separately by the
 * instrumented tests, because what a screen looks like and what it is given are different questions and
 * only the second one can be answered without a device.
 *
 * The distinction this file exists to pin is the one the Active Queue & Reader Session spec makes
 * normative: **Continue listening and Recently completed are two lists, and an entry's membership in one
 * is decided by completion, not by whether it happens to have been written to disk yet.** A session that
 * has just finished is completed *now*.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListeningTabTest {

    private var now = 1_000L

    private fun document(pageId: String, title: String = "Queued $pageId") =
        ReaderDocument.fromChunkTexts(
            pageId = pageId,
            title = title,
            texts = listOf("First.", "Second.")
        )

    private fun app(store: SessionStore?): ReaderApp {
        val engine = FakeReaderEngine(document("demo", "Demo"))
        return ReaderApp(
            session = ReaderSession(engine),
            noteViewer = NoteViewer(document("demo", "Demo")),
            navigator = ReaderNavigator(ReaderNavigation(top = ReaderDestination.Home)),
            loadDocument = DefaultLoadReaderDocument { pageId -> document(pageId) },
            sessionStore = store,
            clock = { now }
        )
    }

    private fun unfinished(id: String, at: Long, notes: Int = 1, index: Int = 0) = storedSession(
        id = id,
        notes = (1..notes).map { queuedNote("p$it") },
        origin = if (notes > 1) QueueOrigin.BrowseResults else QueueOrigin.SingleNote,
        index = index,
        lastActivityAt = at
    )

    private fun completed(id: String, at: Long, notes: Int) = storedSession(
        id = id,
        notes = (1..notes).map { queuedNote("p$it") },
        origin = if (notes > 1) QueueOrigin.BrowseResults else QueueOrigin.SingleNote,
        completed = true,
        lastActivityAt = at
    )

    // ------------------------------------------------------------------ which sessions, in what order

    @Test
    fun continueListeningListsEveryUnfinishedSessionMostRecentlyActiveFirst() = runTest {
        val store = InMemorySessionStore(
            listOf(unfinished("old", at = 10L), unfinished("new", at = 30L), unfinished("mid", at = 20L))
        )
        val app = app(store)
        app.loadHistory()

        assertEquals(listOf("new", "mid", "old"), app.listeningContinueEntries().map { it.sessionId })
    }

    @Test
    fun recentlyCompletedListsOnlyCompletedSessionsMostRecentlyActiveFirst() = runTest {
        val store = InMemorySessionStore(
            listOf(
                unfinished("open", at = 40L),
                completed("finished-old", at = 10L, notes = 3),
                completed("finished-new", at = 30L, notes = 2)
            )
        )
        val app = app(store)
        app.loadHistory()

        assertEquals(
            listOf("finished-new", "finished-old"),
            app.listeningCompletedEntries().map { it.sessionId }
        )
    }

    @Test
    fun theTwoSectionsPartitionHistoryBetweenThem() = runTest {
        // The spec: "Both are ordered by most recent listening activity first. A session moves from the
        // first list to the second when it completes." A session may be in neither list for no window at
        // all, which is what makes Continue listening a complete answer to "what have I not finished".
        val store = InMemorySessionStore(
            listOf(
                unfinished("a", at = 10L),
                completed("b", at = 20L, notes = 2),
                unfinished("c", at = 30L)
            )
        )
        val app = app(store)
        app.loadHistory()

        val unfinishedIds = app.listeningContinueEntries().map { it.sessionId }.toSet()
        val completedIds = app.listeningCompletedEntries().map { it.sessionId }.toSet()

        assertEquals(setOf("a", "c"), unfinishedIds)
        assertEquals(setOf("b"), completedIds)
        assertTrue((unfinishedIds intersect completedIds).isEmpty())
    }

    @Test
    fun historyIsListedInFullEvenThoughHomeShowsOnlyASubset() = runTest {
        // The UI contract gives Home "a small useful subset" and the full history to Queues -> Listening.
        // If the Listening tab inherited Home's cap, the listener would have nowhere to see session four.
        val store = InMemorySessionStore((1..8).map { unfinished("s$it", at = it.toLong()) })
        val app = app(store)
        app.loadHistory()

        assertEquals(HOME_HISTORY_LIMIT, app.continueListeningCards().size)
        assertEquals(8, app.listeningContinueEntries().size)
    }

    // --------------------------------------------------------------------------- how an entry describes itself

    @Test
    fun anUnfinishedSessionInAMultiNoteQueueStatesItsPosition() = runTest {
        val store = InMemorySessionStore(listOf(unfinished("s1", at = 10L, notes = 8, index = 2)))
        val app = app(store)
        app.loadHistory()

        val entry = app.listeningContinueEntries().single()

        assertEquals("Note 3 of 8", entry.membership)
        assertEquals("Paused", entry.stateDescription)
        assertFalse(entry.isCompleted)
        assertFalse(entry.isPlaying)
    }

    @Test
    fun aSingleNoteSessionHasNoMembershipLine() = runTest {
        // "Note 1 of 1" says nothing the title has not, and Home's card omits it for the same reason, so
        // the two surfaces agree about a one-Note session rather than describing it two ways.
        val store = InMemorySessionStore(listOf(unfinished("s1", at = 10L, notes = 1)))
        val app = app(store)
        app.loadHistory()

        assertNull(app.listeningContinueEntries().single().membership)
    }

    @Test
    fun aCompletedSessionStatesItsNoteCountAndCompletionSingularIncluded() = runTest {
        val store = InMemorySessionStore(
            listOf(completed("many", at = 30L, notes = 8), completed("one", at = 20L, notes = 1))
        )
        val app = app(store)
        app.loadHistory()

        val entries = app.listeningCompletedEntries()

        // A finished queue's count is the only thing that distinguishes it from a finished Note, so a
        // completed session always states one - which is why this is not the same rule as an unfinished
        // single-Note session's omitted position.
        assertEquals("8 notes", entries.first { it.sessionId == "many" }.membership)
        assertEquals("1 note", entries.first { it.sessionId == "one" }.membership)

        assertTrue(entries.all { it.stateDescription == "Completed" })
        assertTrue(entries.all { it.isCompleted })
        assertTrue(entries.all { !it.isPlaying })
    }

    @Test
    fun anEntryCarriesActivityAsAnInstantRatherThanAFormattedString() = runTest {
        // The UI contract requires relative time and forbids a raw timestamp, so the *fact* is carried and
        // the wording belongs to the surface. A formatted string here would be stale the moment it was
        // built, which for a session paused an hour ago is the whole point of the line.
        val store = InMemorySessionStore(listOf(unfinished("s1", at = 4_242L)))
        val app = app(store)
        app.loadHistory()

        assertEquals(4_242L, app.listeningContinueEntries().single().lastActivityAt)
    }

    @Test
    fun aSessionThatHasNeverBeenWrittenIsStillListed() = runTest {
        // The live session is history's source, so a listener who starts something and looks at Queues
        // sees it before any write happens - the same rule Home's list follows.
        val app = app(InMemorySessionStore())

        app.playNote()
        app.session.pause()

        assertEquals(1, app.listeningContinueEntries().size)
        assertTrue(app.listeningCompletedEntries().isEmpty())
    }

    // ------------------------------------------------------------------------------ the shell's new destination

    @Test
    fun queuesIsTopLevelAndBackFromItReturnsHome() = runTest {
        // The same rule §12.5 settled for Browse, applied to the third destination: Back reverses visual
        // navigation and returns to the start destination rather than finishing the app.
        val app = app(InMemorySessionStore())

        app.openQueues()
        assertEquals(ReaderDestination.Queues, app.navigator.state.value.top)
        assertTrue(ReaderDestination.Queues.isTopLevel)
        assertTrue(app.back())
        assertEquals(ReaderDestination.Home, app.navigator.state.value.top)
    }

    @Test
    fun openingQueuesDoesNotTouchWhatIsBeingReadAloud() = runTest {
        // ReaderNavigator holds no reference to the session, which is what makes "Back must never
        // implicitly mean Pause, Stop, Previous section, Previous Note" true by construction rather than
        // by discipline. Asserted for the new destination too, because a new destination is a new chance
        // to break it.
        val app = app(InMemorySessionStore())
        app.playNote()
        val playingBefore = app.session.isPlaying

        app.openQueues()

        assertEquals(playingBefore, app.session.isPlaying)
    }
}
