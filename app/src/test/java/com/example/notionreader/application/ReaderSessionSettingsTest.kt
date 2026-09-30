package com.example.notionreader.application

import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.queue.ActiveQueue
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.domain.settings.AnnouncementVerbosity
import com.example.notionreader.player.FakeReaderEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSessionSettingsTest {

    private fun note(pageId: String, title: String) = ReaderDocument.fromChunkTexts(
        pageId = pageId,
        title = title,
        texts = listOf("$title paragraph 1", "$title paragraph 2")
    )

    private val noteA = note("a", "Alpha")
    private val noteB = note("b", "Beta")

    private fun twoNoteQueue() = ActiveQueue.snapshotOf(
        notes = listOf(QueuedNote.of(noteA), QueuedNote.of(noteB)),
        origin = QueueOrigin.BrowseResults
    )

    @Test
    fun whenContinueToNextNoteIsFalsePlaybackPausesAtEndOfNoteWithoutAdvancing() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.continueToNextNote = false

        session.start(twoNoteQueue())
        session.supplyDocument(noteA)
        engine.play()

        assertTrue(engine.state.value.isPlaying)
        assertEquals(0, session.queueState.value.index)
        assertFalse(session.queueState.value.awaiting)

        // Note finishes reading
        session.onCurrentNoteFinished()

        // Engine was paused, queue remains on Note 0, NOT awaiting Note 1
        assertEquals("Queue index must not advance when continueToNextNote is false", 0, session.queueState.value.index)
        assertFalse("Session must not be awaiting next note when continueToNextNote is false", session.queueState.value.awaiting)
        assertEquals(1, engine.pauseCount)
    }

    @Test
    fun detailedAnnouncementVerbosityIncludesQueueIndexAndTitle() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.announcementVerbosity = AnnouncementVerbosity.DETAILED

        session.start(twoNoteQueue())
        session.supplyDocument(noteA)

        // Advance to note B
        session.onCurrentNoteFinished()
        session.supplyDocument(noteB)

        assertEquals(
            listOf("Next Note. Note 2 of 2. Beta"),
            engine.announcements
        )
    }

    @Test
    fun minimalAnnouncementVerbosityOmitsTransitionAnnouncement() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.announcementVerbosity = AnnouncementVerbosity.MINIMAL

        session.start(twoNoteQueue())
        session.supplyDocument(noteA)

        // Advance to note B
        session.onCurrentNoteFinished()
        session.supplyDocument(noteB)

        assertTrue(
            "Minimal verbosity must not make an announcement for note transition",
            engine.announcements.isEmpty()
        )
    }
}
