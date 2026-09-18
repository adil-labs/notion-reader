package com.example.notionreader.player

import com.example.notionreader.domain.ReaderDocument
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Test double for [ReaderEngine] that records every invocation.
 *
 * [FakeSpeechEngine] already counts speech calls, and tests that exercise the engine use the real
 * [ReaderEngineImpl] over it — that pairing is still the right one for reader behaviour. This fake
 * answers a different question: **did anything call the engine at all?** Navigation and Note loading
 * are supposed to leave playback completely untouched, and "zero calls" is a statement about this
 * boundary, so it is asserted here rather than inferred from speech calls further down.
 *
 * It deliberately **mirrors the real engine's destructive `setDocument`** — it stops speech and resets
 * the position, exactly as [ReaderEngineImpl] does — because a fake that quietly made `setDocument`
 * harmless would make the regression it exists to catch impossible to reproduce. A test double that
 * cannot fail the way the real dependency fails is not evidence.
 *
 * Invocations are counted on entry, before any early return. A call that happens to be a no-op still
 * counts, so a navigation path that "helpfully" calls `pause()` on an idle session is visible rather
 * than invisible.
 */
class FakeReaderEngine(initialDocument: ReaderDocument = ReaderDocument.Empty) : ReaderEngine {

    private val _document = MutableStateFlow(initialDocument)
    override val document: StateFlow<ReaderDocument> = _document.asStateFlow()

    private val _state = MutableStateFlow(stateFor(initialDocument, 0))
    override val state: StateFlow<ReaderState> = _state.asStateFlow()

    var setDocumentCount = 0
        private set
    var playCount = 0
        private set
    var playFromStartCount = 0
        private set
    var announceThenPlayCount = 0
        private set
    var pauseCount = 0
        private set
    var nextParagraphCount = 0
        private set
    var previousParagraphCount = 0
        private set
    var nextSectionCount = 0
        private set
    var previousSectionCount = 0
        private set
    var restartNoteCount = 0
        private set
    var shutdownCount = 0
        private set

    /** Utterances handed to the speech engine. Named to match [FakeSpeechEngine]. */
    var speakCount = 0
        private set

    /** The texts passed to [announceThenPlay], so a queue transition's announcement is assertable. */
    val announcements = mutableListOf<String>()

    /**
     * The texts passed to [announce] — standalone utterances with nothing read after them.
     *
     * A separate list from [announcements] rather than a shared one, because the two operations mean
     * different things and a test that asserted "something was announced" would pass for either. The one
     * caller today is the end of a queue, which must be distinguishable from a Note transition.
     */
    val standaloneAnnouncements = mutableListOf<String>()

    /** Speech stopped — by [setDocument], or by a pause or position change while playing. */
    var stopCount = 0
        private set

    /**
     * Every engine-level invocation, which is what "navigation does not touch playback" should be
     * asserted against. Speech effects are excluded so they are not counted twice.
     */
    val callCount: Int
        get() = setDocumentCount +
            playCount +
            playFromStartCount +
            announceThenPlayCount +
            pauseCount +
            nextParagraphCount +
            previousParagraphCount +
            nextSectionCount +
            previousSectionCount +
            restartNoteCount +
            shutdownCount

    /** The document the engine is reading, for asserting a snapshot was frozen rather than aliased. */
    val currentDocument: ReaderDocument
        get() = _document.value

    override fun play() {
        playCount++
        if (_state.value.paragraphs.isEmpty()) return
        _state.value = _state.value.play()
        speakCount++
    }

    override fun playFromStart() {
        playFromStartCount++
        if (_state.value.paragraphs.isEmpty()) return
        stopCount++
        _state.value = _state.value.copy(currentParagraphIndex = 0, hasReachedEnd = false).play()
        speakCount++
    }

    override fun announceThenPlay(text: String, startParagraphIndex: Int) {
        announceThenPlayCount++
        announcements += text
        if (_state.value.paragraphs.isEmpty()) return
        stopCount++
        val paragraphs = _state.value.paragraphs
        _state.value = _state.value
            .copy(
                currentParagraphIndex = startParagraphIndex.coerceIn(0, paragraphs.size - 1),
                hasReachedEnd = false
            )
            .play()
        // The announcement is a speech utterance of its own, then the document starts.
        speakCount += 2
    }

    override fun pause() {
        pauseCount++
        if (!_state.value.isPlaying) return
        _state.value = _state.value.pause()
        stopCount++
    }

    /**
     * Mirrors [ReaderEngineImpl.announce]: an utterance, with no state and no position change.
     *
     * Counted as speech rather than as an engine invocation, so the [callCount] assertion — "navigation
     * does not touch playback" — keeps meaning what it says. An announcement is not navigation.
     */
    override fun announce(text: String) {
        standaloneAnnouncements += text
        speakCount++
    }

    override fun nextParagraph() {
        nextParagraphCount++
        move { it.nextParagraph() }
    }

    override fun previousParagraph() {
        previousParagraphCount++
        move { it.previousParagraph() }
    }

    override fun nextSection() {
        nextSectionCount++
        move { it.nextSection() }
    }

    override fun previousSection() {
        previousSectionCount++
        move { it.previousSection() }
    }

    override fun restartNote() {
        restartNoteCount++
        move { it.movedToStart() }
    }

    override fun setDocument(document: ReaderDocument, startParagraphIndex: Int) {
        setDocumentCount++
        // Mirrors ReaderEngineImpl: this is the operation that ends a listening session.
        stopCount++
        _document.value = document
        _state.value = stateFor(document, startParagraphIndex)
    }

    override fun shutdown() {
        shutdownCount++
        stopCount++
    }

    /** Mirrors [ReaderEngineImpl]'s move: speech only restarts when the position actually moved. */
    private fun move(transform: (ReaderState) -> ReaderState) {
        val current = _state.value
        val next = transform(current)
        if (next.currentParagraphIndex == current.currentParagraphIndex) return

        if (current.isPlaying) stopCount++

        _state.value = next

        if (next.isPlaying) speakCount++
    }

    private fun stateFor(document: ReaderDocument, startParagraphIndex: Int): ReaderState {
        val paragraphs = document.chunkTexts
        return ReaderState(
            paragraphs = paragraphs,
            currentParagraphIndex = startParagraphIndex.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0)),
            sections = document.sectionSpans()
        )
    }
}
