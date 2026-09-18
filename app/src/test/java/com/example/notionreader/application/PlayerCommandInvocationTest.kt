package com.example.notionreader.application

import com.example.notionreader.data.notion.title
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.player.FakeReaderEngine
import com.example.notionreader.player.PlayerCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invoking a Player command: the single entry point every transport uses.
 *
 * The Player's own controls are one caller among several here rather than a special case, which is what the
 * Product & Architecture Contract requires — a command has to be "representable independently of individual
 * UI widgets so Android media controls, accessibility actions, hardware controls, and future voice control
 * can invoke the same behavior". So these tests stand in for the transports that do not exist yet.
 *
 * Two properties matter more than the per-command behaviour:
 *
 * - **A command that has become unavailable changes nothing.** A transport may act on a stale value — a
 *   headset button pressed at the instant the queue completed — so the precondition is re-checked at
 *   dispatch, and rejection is neither an error nor an announcement.
 * - **Moving never speaks when the listener has asked for silence.** Every move preserves the playing state,
 *   so the only command that causes speech in a paused session is `Play`.
 */
class PlayerCommandInvocationTest {

    private val demo = ReaderDocument.fromChunkTexts(
        pageId = "demo",
        title = "Demo document",
        texts = listOf("First.", "Second.", "Third.", "Fourth.")
    )

    private fun app(engine: FakeReaderEngine = FakeReaderEngine(demo)): ReaderApp = ReaderApp(
        session = ReaderSession(engine),
        noteViewer = NoteViewer(demo),
        navigator = ReaderNavigator(),
        loadDocument = DefaultLoadReaderDocument { pageId -> demo.copy(pageId = pageId) }
    )

    /** A started, playing, single-Note session — the state the Player is normally in. */
    private fun playingSession(engine: FakeReaderEngine = FakeReaderEngine(demo)): ReaderApp =
        app(engine).also { it.playNote() }

    // --- Rejection ---------------------------------------------------------------------------------

    @Test
    fun anUnavailableCommandChangesNothingAndAnnouncesNothing() {
        val engine = FakeReaderEngine(demo)
        val app = playingSession(engine)

        // At the first part there is nothing before it.
        val callsBefore = engine.callCount
        val announcementsBefore = engine.standaloneAnnouncements.size

        assertFalse("a boundary move is refused", app.invoke(PlayerCommand.PreviousPart))

        assertEquals(callsBefore, engine.callCount)
        assertEquals(announcementsBefore, engine.standaloneAnnouncements.size)
        assertEquals(0, app.session.state.value.currentParagraphIndex)
    }

    @Test
    fun restartIsRefusedOnceTheQueueHasCompleted() {
        val engine = FakeReaderEngine(demo)
        val app = playingSession(engine)

        app.session.onCurrentNoteFinished()
        assertTrue(app.session.queueState.value.completed)

        assertFalse("a completed session is not restartable", app.invoke(PlayerCommand.RestartNote))
        assertFalse("and it is not playable either", app.invoke(PlayerCommand.Play))
    }

    @Test
    fun aSurfaceActionIsNotInvokableThroughTheCommandEntryPoint() {
        val engine = FakeReaderEngine(demo)
        val app = playingSession(engine)
        val callsBefore = engine.callCount

        // The queue is non-empty, so this is *available* as a surface action — and still refused here,
        // because it changes what the phone is displaying rather than what is being read. That refusal is
        // what keeps it off a lock screen.
        assertFalse(app.invoke(PlayerCommand.OpenQueue))
        assertFalse(app.invoke(PlayerCommand.OpenMorePlaybackControls))
        assertEquals(callsBefore, engine.callCount)
    }

    @Test
    fun jumpWithoutADestinationIsRefusedRatherThanGuessedAt() {
        val app = playingSession()

        // The command is available; the *argument* is what is missing. "Jump somewhere" with no somewhere
        // is not a command, so it is refused instead of defaulting to a Note nobody asked for.
        assertFalse(app.invoke(PlayerCommand.JumpToNote))
    }

    // --- The moves ---------------------------------------------------------------------------------

    @Test
    fun thePartCommandsMoveOnePartAndNeverCrossIntoAnotherNote() {
        val engine = FakeReaderEngine(demo)
        val app = playingSession(engine)

        val noteBefore = app.session.queueState.value.index
        assertTrue(app.invoke(PlayerCommand.NextPart))

        assertEquals(1, app.session.state.value.currentParagraphIndex)
        // Crossing Notes is `NextNote`, which announces the new Note's title. A part command that also did
        // it would make the two controls mean the same thing where a listener most needs to tell them apart.
        assertEquals(noteBefore, app.session.queueState.value.index)
    }

    @Test
    fun aPartCommandIsUnavailableAtTheEndOfTheNoteRatherThanLoadingTheNextOne() {
        val engine = FakeReaderEngine(demo)
        val app = playingSession(engine)

        // Walk to the last part.
        repeat(3) { app.invoke(PlayerCommand.NextPart) }
        assertEquals(3, app.session.state.value.currentParagraphIndex)

        assertFalse("there is no next part in this Note", app.invoke(PlayerCommand.NextPart))
    }

    @Test
    fun restartingReturnsToTheFirstPartOfTheCurrentNote() {
        val engine = FakeReaderEngine(demo)
        val app = playingSession(engine)

        app.invoke(PlayerCommand.NextPart)
        app.invoke(PlayerCommand.NextPart)
        assertEquals(2, app.session.state.value.currentParagraphIndex)

        assertTrue(app.invoke(PlayerCommand.RestartNote))

        assertEquals(0, app.session.state.value.currentParagraphIndex)
        // It does not restart the queue: membership and position are untouched, and it is not a new session.
        assertEquals(0, app.session.queueState.value.index)
        assertEquals(1, app.session.queueState.value.size)
    }

    @Test
    fun restartingWhilePausedMovesAndStaysSilent() {
        val engine = FakeReaderEngine(demo)
        val app = playingSession(engine)

        app.invoke(PlayerCommand.NextPart)
        app.invoke(PlayerCommand.Pause)
        assertFalse(app.session.isPlaying)

        val spokenBefore = engine.speakCount
        assertTrue(app.invoke(PlayerCommand.RestartNote))

        assertEquals(0, app.session.state.value.currentParagraphIndex)
        assertFalse(app.session.isPlaying)
        // The rule sections already follow — "while paused they only move the position" — applied to Notes
        // and to restart, so there is one rule rather than three.
        assertEquals(spokenBefore, engine.speakCount)
    }

    @Test
    fun movingWhilePlayingContinuesReadingFromTheNewPosition() {
        val engine = FakeReaderEngine(demo)
        val app = playingSession(engine)

        assertTrue(app.session.isPlaying)
        val spokenBefore = engine.speakCount

        assertTrue(app.invoke(PlayerCommand.NextSection))

        assertTrue(app.session.isPlaying)
        assertTrue("reading continues", engine.speakCount > spokenBefore)
    }

    // --- Toggle ------------------------------------------------------------------------------------

    @Test
    fun togglingPausesWhilePlayingAndPlaysWhilePaused() {
        val app = playingSession()
        assertTrue(app.session.isPlaying)

        assertTrue(app.invoke(PlayerCommand.TogglePlayback))
        assertFalse(app.session.isPlaying)

        assertTrue(app.invoke(PlayerCommand.TogglePlayback))
        assertTrue(app.session.isPlaying)
    }

    @Test
    fun playAndPauseAreTheTwoHalvesToggleStandsFor() {
        val app = playingSession()

        assertTrue(app.invoke(PlayerCommand.Pause))
        assertFalse(app.session.isPlaying)

        assertTrue(app.invoke(PlayerCommand.Play))
        assertTrue(app.session.isPlaying)
    }
}
