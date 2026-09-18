package com.example.notionreader.application

import com.example.notionreader.application.DefaultLoadReaderDocument
import com.example.notionreader.application.ReaderSession
import com.example.notionreader.data.notion.title
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.positionAt
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.queuedNote
import com.example.notionreader.domain.session.InMemorySessionStore
import com.example.notionreader.domain.session.SessionStore
import com.example.notionreader.domain.storedSession
import com.example.notionreader.player.FakeReaderEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Listening history at the application layer: what is offered, what is written, and what Continue does.
 *
 * These are the surface-independent halves of the Active Queue & Reader Session spec — retention,
 * ordering, the difference between Continue and Play, and the rule that restoring a session never
 * re-evaluates what the queue was created from. They run on the JVM against an in-memory store, which is
 * what the spec's test strategy asks for: "persistence logic must be unit-testable on the JVM with no
 * device, no dispatcher, and no network."
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderAppHistoryTest {

    /** A fixed clock, so recency and retention are assertable rather than dependent on real time. */
    private var now = 1_000L

    private fun document(pageId: String, title: String = "Queued $pageId") =
        ReaderDocument.fromChunkTexts(
            pageId = pageId,
            title = title,
            texts = listOf("First.", "Second.", "Third.", "Fourth.")
        )

    private fun app(store: SessionStore?): Pair<ReaderApp, FakeReaderEngine> {
        val engine = FakeReaderEngine(document("demo", "Demo"))
        val app = ReaderApp(
            session = ReaderSession(engine),
            noteViewer = NoteViewer(document("demo", "Demo")),
            navigator = ReaderNavigator(ReaderNavigation(top = ReaderDestination.Home)),
            loadDocument = DefaultLoadReaderDocument { pageId -> document(pageId) },
            sessionStore = store,
            clock = { now }
        )
        return app to engine
    }

    private fun finished(id: String, at: Long) =
        storedSession(id = id, notes = listOf(queuedNote("p1")), lastActivityAt = at)

    private fun unfinished(id: String, at: Long) =
        storedSession(id = id, notes = listOf(queuedNote("p1")), lastActivityAt = at)

    // ---------------------------------------------------------------- reading and offering history

    @Test
    fun historyIsOfferedMostRecentlyActiveFirst() = runTest {
        val store = InMemorySessionStore(
            listOf(unfinished("old", at = 10L), unfinished("new", at = 30L), unfinished("mid", at = 20L))
        )
        val (app, _) = app(store)

        app.loadHistory()

        assertEquals(listOf("new", "mid", "old"), app.resumableSessions.map { it.id })
    }

    @Test
    fun aSessionInMemoryAppearsInHistoryWithoutWaitingForAWrite() = runTest {
        // The spec: a listener who pauses and immediately looks at Home must see the session they just
        // paused. Waiting for a disk write to say so would make that a race.
        val store = InMemorySessionStore()
        val (app, _) = app(store)
        app.playNote()
        app.session.pause()

        assertEquals(1, app.continueListeningCards().size)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun continueListeningIsOnlyASmallSubsetOfHistory() = runTest {
        val store = InMemorySessionStore((1..8).map { unfinished("s$it", at = it.toLong()) })
        val (app, _) = app(store)
        app.loadHistory()

        assertEquals(HOME_HISTORY_LIMIT, app.continueListeningCards().size)
        assertEquals(8, app.resumableSessions.size)
    }

    @Test
    fun aCardNamesTheSessionItDescribesAndCarriesItsRecency() = runTest {
        val store = InMemorySessionStore(listOf(unfinished("only", at = 500L)))
        val (app, _) = app(store)
        app.loadHistory()

        val card = app.continueListeningCards().single()

        assertEquals("only", card.sessionId)
        assertEquals("Note p1", card.title)
        assertEquals(500L, card.lastActivityAt)
        assertEquals("Paused", card.stateDescription)
    }

    // ---------------------------------------------------------------- writing

    @Test
    fun persistingWritesTheWholeRetainedSetAndKeepsEarlierSessions() = runTest {
        val store = InMemorySessionStore(listOf(finished("earlier", at = 10L)))
        val (app, _) = app(store)
        app.loadHistory()

        now = 20L
        app.playNote()
        app.persistSession()

        assertEquals(
            "an earlier session must not be discarded by writing the live one",
            listOf("live-or-earlier", "earlier").size,
            store.stored.size
        )
        assertTrue(store.stored.any { it.id == "earlier" })
        assertTrue(store.stored.any { it.id == app.session.id })
    }

    @Test
    fun persistingDoesNothingWhenThereIsNoQueue() = runTest {
        val store = InMemorySessionStore(listOf(unfinished("earlier", at = 10L)))
        val (app, _) = app(store)
        app.loadHistory()

        app.persistSession()

        assertEquals("viewing Home must not write, or launch would rewrite history", 0, store.writeCount)
        assertEquals(listOf("earlier"), store.stored.map { it.id })
    }

    @Test
    fun aFailedWriteLeavesHistoryAndPlaybackUntouched() = runTest {
        val store = InMemorySessionStore(listOf(unfinished("earlier", at = 10L)))
        val (app, engine) = app(store)
        app.loadHistory()
        store.failWrites = true

        app.playNote()
        val playCount = engine.playCount
        app.persistSession()

        assertEquals("playback is never on the persistence path", playCount, engine.playCount)
        assertTrue(engine.state.value.isPlaying)
        assertEquals("the stored history is unchanged, not truncated", listOf("earlier"), store.stored.map { it.id })
    }

    // ---------------------------------------------------------------- Continue versus Play

    @Test
    fun continueOnAStoredSessionRestoresItsQueuePositionAndNote() = runTest {
        val loaded = document("p1")
        val store = InMemorySessionStore(
            listOf(
                storedSession(
                    id = "stored",
                    notes = listOf(queuedNote("p1"), queuedNote("p2")),
                    origin = QueueOrigin.BrowseResults,
                    index = 0,
                    position = loaded.positionAt(2),
                    lastActivityAt = 10L
                )
            )
        )
        val (app, _) = app(store)
        app.loadHistory()

        app.continueSession("stored")

        assertEquals("the session adopted is the stored one", "stored", app.session.id)
        assertEquals(ReaderDestination.Player, app.navigator.state.value.current)
        assertEquals("restored to the saved chunk, not the top", 2, app.session.state.value.currentParagraphIndex)
        assertTrue(app.session.state.value.isPlaying)
    }

    @Test
    fun continueOnTheLiveSessionOnlyOpensThePlayer() = runTest {
        val (app, engine) = app(InMemorySessionStore())
        app.playNote()
        engine.nextParagraph()
        val positionBefore = app.session.state.value.currentParagraphIndex
        val sessionId = app.session.id!!

        app.continueSession(sessionId)

        assertEquals(ReaderDestination.Player, app.navigator.state.value.current)
        assertEquals("re-adopting the live session would discard where it is", positionBefore, app.session.state.value.currentParagraphIndex)
        assertEquals("and would fetch the Note again", 1, engine.setDocumentCount)
    }

    @Test
    fun replayingACompletedSessionStartsANewSessionFromTheBeginning() = runTest {
        val store = InMemorySessionStore(
            listOf(
                storedSession(
                    id = "done",
                    notes = listOf(queuedNote("p1"), queuedNote("p2")),
                    origin = QueueOrigin.Smart(queueId = "smart-1", name = "Weekly review"),
                    index = 1,
                    completed = true,
                    lastActivityAt = 10L
                )
            )
        )
        val (app, _) = app(store)
        app.loadHistory()

        app.replayCompleted("done")

        assertEquals("a new session, so the historical record is not overwritten", "done" != app.session.id, true)
        assertNotEquals("done", app.session.id)
        assertEquals("from the beginning of the snapshot", 0, app.session.queueState.value.index)
        assertEquals(
            "the same membership, and not re-evaluated from the Smart origin",
            listOf("p1", "p2"),
            app.session.queueState.value.queue?.items?.map { it.pageId }
        )
        assertEquals(ReaderDestination.Player, app.navigator.state.value.current)
    }

    @Test
    fun replayingACompletedSessionLeavesTheHistoricalRecordAlone() = runTest {
        val historical = storedSession(id = "done", completed = true, lastActivityAt = 10L)
        val store = InMemorySessionStore(listOf(historical))
        val (app, _) = app(store)
        app.loadHistory()

        app.replayCompleted("done")

        assertEquals("the record the listener was looking at is untouched", listOf(historical), store.stored)
    }

    @Test
    fun continueOnAnUnknownSessionDoesNothing() = runTest {
        val store = InMemorySessionStore(listOf(unfinished("known", at = 10L)))
        val (app, engine) = app(store)
        app.loadHistory()

        app.continueSession("not-a-session")

        assertNull(app.session.id)
        assertEquals(0, engine.setDocumentCount)
        assertEquals("and does not navigate anywhere", ReaderDestination.Home, app.navigator.state.value.current)
    }

    @Test
    fun aSessionThatCompletedIsNoLongerOfferedAsContinueListening() = runTest {
        val (app, _) = app(InMemorySessionStore())
        app.playNote()

        app.onNoteFinished()

        assertTrue("the queue ended, so there is nothing to continue", app.resumableSessions.isEmpty())
        assertEquals("but it is still history", 1, app.completedSessions.size)
    }

    @Test
    fun aStoreThatCannotBeReadIsIndistinguishableFromAnEmptyOne() = runTest {
        val store = InMemorySessionStore(listOf(unfinished("unreachable", at = 10L)))
        store.failReads = true
        val (app, _) = app(store)

        app.loadHistory()

        assertTrue(app.resumableSessions.isEmpty())
        assertTrue(app.continueListeningCards().isEmpty())
    }

    @Test
    fun aStoreThatCannotBeReadLeavesTheAppUsable() = runTest {
        val store = InMemorySessionStore(listOf(unfinished("unreachable", at = 10L)))
        store.failReads = true
        val (app, engine) = app(store)
        app.loadHistory()

        app.playNote()

        assertTrue("an unreadable history is not an error state", engine.state.value.isPlaying)
    }
}
