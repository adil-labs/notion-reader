package com.example.notionreader.ui

import com.example.notionreader.application.DefaultLoadReaderDocument
import com.example.notionreader.application.NoteViewer
import com.example.notionreader.application.ReaderApp
import com.example.notionreader.application.ReaderNavigator
import com.example.notionreader.application.ReaderSession
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.player.ReaderEngineImpl
import com.example.notionreader.player.SpeechEngine
import java.time.Instant
import kotlinx.coroutines.runBlocking

/**
 * The application object the shell tests render.
 *
 * ## Why the shell needs a real `ReaderApp` and the screens do not
 *
 * The other instrumented tests render one screen with the state they want passed in, because a screen
 * takes state and callbacks and nothing else. The shell is different in kind: what it asserts — which
 * destinations are tabs, whether the mini-player is on screen, where Back goes — are properties of the
 * *navigation*, and navigation is state that lives above the screens in `ReaderApp` and `ReaderNavigator`.
 * Faking it would replace the thing under test with a description of the thing under test.
 *
 * So this builds the real application object with the two seams that exist: a [SpeechEngine] that says
 * nothing (the reader's speech is not what is being asserted, and a device's TTS engine would make the
 * suite depend on which voices happen to be installed), and a loader that returns a fixture instead of
 * calling Notion (which is what makes the suite offline and deterministic).
 *
 * That is enough for the shell and deliberately not more: it is not a fake of the application, it *is*
 * the application, with its two external dependencies replaced. `FakeReaderEngine` in the JVM suite is the
 * opposite trade and for the opposite reason — those tests assert which engine calls happened, so they
 * need a recording double and cannot use a real engine.
 */
internal class ShellHarness {

    /** A document with a handful of paragraphs, so the Player has something to read. */
    fun document(pageId: String, title: String): ReaderDocument = ReaderDocument.fromChunkTexts(
        pageId = pageId,
        title = title,
        texts = listOf("First paragraph.", "Second paragraph.", "Third paragraph.")
    )

    /** A Browse result. `lastEditedAt` is fixed rather than read from a clock, so rows cannot reorder. */
    fun entry(index: Int, editedAt: Instant = Instant.parse("2026-09-01T00:00:00Z")) = ReaderIndexEntry(
        pageId = "page-$index",
        title = "Result $index",
        lastEditedAt = editedAt
    )

    /**
     * Builds the application, optionally with a Browse index and optionally with a live session.
     *
     * [withSession] is what puts the mini-player on screen: `Play all` creates the queue, and
     * `isSessionResumable` is then true — a session is surfaced "once playback has begun", and for a queue
     * that begins when it is created rather than when its first Note has been fetched (D25).
     */
    fun app(
        entries: List<ReaderIndexEntry> = emptyList(),
        withSession: Boolean = false
    ): ReaderApp {
        val app = ReaderApp(
            session = ReaderSession(
                ReaderEngineImpl(
                    speechEngine = SilentSpeechEngine,
                    initialDocument = document("demo", "Demo document")
                )
            ),
            noteViewer = NoteViewer(document("demo", "Demo document")),
            navigator = ReaderNavigator(),
            loadDocument = DefaultLoadReaderDocument { pageId -> document(pageId, "Note $pageId") }
        )

        if (entries.isNotEmpty()) runBlocking { app.loadBrowseIndex { entries } }

        if (withSession) app.playAll(listOf(QueuedNote("page-1", "Alpha"), QueuedNote("page-2", "Beta")))

        return app
    }
}

/**
 * A speech engine that accepts everything and says nothing.
 *
 * It records nothing, because nothing under test reads what was spoken: the shell's tests assert the
 * semantics tree, and the JVM suite's recording engine is the one that answers "was the engine called".
 * A device's own TTS engine would work here but would make the result depend on which voice data happens
 * to be installed.
 */
private object SilentSpeechEngine : SpeechEngine {
    override fun speak(text: String, onComplete: (() -> Unit)?) = Unit
    override fun stop() = Unit
    override fun shutdown() = Unit
}
