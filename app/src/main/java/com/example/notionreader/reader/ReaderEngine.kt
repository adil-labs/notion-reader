package com.example.notionreader.reader

import com.example.notionreader.speech.SpeechEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates navigation and playback for a single document.
 *
 * The engine owns all reader state; the UI only observes [state] and calls these operations.
 * It depends on [SpeechEngine] rather than Android TTS.
 *
 * When playing, the engine reads continuously: each finished paragraph advances to the next one, and
 * playback stops on its own after the last paragraph. That is what makes the reader hands-free.
 */
interface ReaderEngine {

    val state: StateFlow<ReaderState>

    /** The document currently loaded, for the UI title. */
    val document: StateFlow<ReaderDocument>

    /** Starts reading [ReaderState.currentParagraph] from the beginning, then continues automatically. */
    fun play()

    /** Stops the current utterance and keeps the reading position. */
    fun pause()

    fun nextParagraph()

    fun previousParagraph()

    /** Replaces the current document and resets the reading position. */
    fun setDocument(document: ReaderDocument, startParagraphIndex: Int = 0)

    /** Releases speech resources. The engine must not be used afterwards. */
    fun shutdown()
}

/**
 * Default [ReaderEngine].
 *
 * State transitions live in [ReaderState]; this class only decides when to talk to the
 * [SpeechEngine].
 */
class ReaderEngineImpl(
    private val speechEngine: SpeechEngine,
    initialDocument: ReaderDocument = ReaderDocument.Empty,
    initialParagraphIndex: Int = 0
) : ReaderEngine {

    private val _document = MutableStateFlow(initialDocument)
    override val document: StateFlow<ReaderDocument> = _document.asStateFlow()

    private val _state = MutableStateFlow(
        ReaderState(
            paragraphs = initialDocument.paragraphs,
            currentParagraphIndex = initialParagraphIndex.coerceInIndex(initialDocument.paragraphs)
        )
    )
    override val state: StateFlow<ReaderState> = _state.asStateFlow()

    /**
     * Identifies the utterance the engine is currently waiting on.
     *
     * A completion callback carries the token it was created with, so a callback that arrives after
     * the utterance was cancelled (pause, next, previous, document swap) is ignored instead of moving
     * the reader a paragraph the user did not ask for.
     */
    private var utteranceToken = 0

    override fun play() {
        val current = _state.value
        if (current.paragraphs.isEmpty()) return

        _state.value = current.play()
        speakCurrentParagraph()
    }

    override fun pause() {
        val current = _state.value
        if (!current.isPlaying) return

        invalidateUtterance()
        _state.value = current.pause()
        speechEngine.stop()
    }

    override fun nextParagraph() = move { it.nextParagraph() }

    override fun previousParagraph() = move { it.previousParagraph() }

    override fun setDocument(document: ReaderDocument, startParagraphIndex: Int) {
        invalidateUtterance()
        speechEngine.stop()
        _document.value = document
        _state.value = ReaderState(
            paragraphs = document.paragraphs,
            currentParagraphIndex = startParagraphIndex.coerceInIndex(document.paragraphs)
        )
    }

    override fun shutdown() {
        invalidateUtterance()
        speechEngine.stop()
        speechEngine.shutdown()
    }

    /**
     * Applies a navigation transform. Speech is only restarted when the position actually moved,
     * which keeps boundary presses (next at the end) and paused navigation silent.
     */
    private fun move(transform: (ReaderState) -> ReaderState) {
        val current = _state.value
        val next = transform(current)
        if (next.currentParagraphIndex == current.currentParagraphIndex) return

        if (current.isPlaying) {
            invalidateUtterance()
            speechEngine.stop()
        }

        _state.value = next

        if (next.isPlaying) speakCurrentParagraph()
    }

    /** Speaks the current paragraph, arranging for the next one to follow once it finishes. */
    private fun speakCurrentParagraph() {
        invalidateUtterance()
        val token = utteranceToken
        speechEngine.speak(_state.value.currentParagraph) { onUtteranceFinished(token) }
    }

    /**
     * Continues reading after a paragraph is spoken all the way through.
     *
     * Reaching the end of the document ends playback instead of wrapping, and a callback whose token is
     * stale (its utterance was cancelled) is discarded.
     */
    private fun onUtteranceFinished(token: Int) {
        if (token != utteranceToken) return

        val current = _state.value
        if (!current.isPlaying) return

        if (current.isAtLastParagraph) {
            _state.value = current.pause()
            return
        }

        _state.value = current.nextParagraph()
        speakCurrentParagraph()
    }

    /** Makes any in-flight completion callback stale. */
    private fun invalidateUtterance() {
        utteranceToken += 1
    }

    private fun Int.coerceInIndex(paragraphs: List<String>): Int =
        coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
}
