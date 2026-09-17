package com.example.notionreader.player

import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.player.SpeechEngine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    /**
     * Starts a fresh reading pass from the first paragraph and begins playing.
     *
     * This is what the Note screen's Play action means: "Play creates a new Active Queue containing
     * only this Note, starts from the beginning". Historical position is deliberately not resumed.
     */
    fun playFromStart()

    /**
     * Speaks [text] and then reads the loaded document from [startParagraphIndex].
     *
     * This exists because a queue moving to the next Note must say *which* Note started. The document
     * carries no title chunk — the spec's decision D9 is that the title is announced rather than read
     * as content, so the announcement is not something the document can supply.
     *
     * Speech, rather than a live region, because the failure case matters: a listener with the screen off
     * has TalkBack silent and the reader's TTS as their only channel, and to them an unannounced Note
     * change is indistinguishable from the reader having stopped.
     *
     * **[startParagraphIndex] is not decoration.** The first version of this operation always began the
     * document at its first chunk, which was correct for the only caller it had — a Note transition, where
     * the document has just been replaced and has no position yet. Resuming an inexact position is the
     * second caller, and it announces a section and then reads from that section, not from the top. Without
     * this parameter the announcement would be followed by the document's first sentence, which is the
     * worst of both behaviors: it tells the listener where they are and then reads from somewhere else.
     */
    fun announceThenPlay(text: String, startParagraphIndex: Int = 0)

    /** Stops the current utterance and keeps the reading position. */
    fun pause()

    fun nextParagraph()

    fun previousParagraph()

    /** Moves to the next section, or to the last paragraph when already in the final section. */
    fun nextSection()

    /** Moves to the start of the current section, or to the previous section when already there. */
    fun previousSection()

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
        initialDocument.stateAt(initialParagraphIndex)
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

    override fun playFromStart() {
        val current = _state.value
        if (current.paragraphs.isEmpty()) return

        invalidateUtterance()
        speechEngine.stop()
        _state.value = current.copy(currentParagraphIndex = 0, hasReachedEnd = false).play()
        speakCurrentParagraph()
    }

    override fun announceThenPlay(text: String, startParagraphIndex: Int) {
        val current = _state.value
        if (current.paragraphs.isEmpty()) return

        invalidateUtterance()
        speechEngine.stop()
        _state.value = current
            .copy(
                currentParagraphIndex = startParagraphIndex.coerceIn(0, current.paragraphs.size - 1),
                hasReachedEnd = false
            )
            .play()

        // The state says Playing while the announcement is spoken, which is accurate: the session is
        // playing, it is announcing. The announcement shares the utterance token, so moving away during
        // it discards the start of the document the same way any other cancellation does.
        val token = utteranceToken
        speechEngine.speak(text) {
            if (token != utteranceToken) return@speak
            speakCurrentParagraph()
        }
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

    override fun nextSection() = move { it.nextSection() }

    override fun previousSection() = move { it.previousSection() }

    override fun setDocument(document: ReaderDocument, startParagraphIndex: Int) {
        invalidateUtterance()
        speechEngine.stop()
        _document.value = document
        _state.value = document.stateAt(startParagraphIndex)
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
            // Stopped because the note ended, not because the user paused: the Player has to be able
            // to announce which of the two happened.
            _state.value = current.finish()
            return
        }

        _state.value = current.nextParagraph()
        speakCurrentParagraph()
    }

    /** Makes any in-flight completion callback stale. */
    private fun invalidateUtterance() {
        utteranceToken += 1
    }

    /** The state for a document, with its section outline and the start index clamped into range. */
    private fun ReaderDocument.stateAt(startParagraphIndex: Int): ReaderState {
        val paragraphs = chunkTexts
        return ReaderState(
            paragraphs = paragraphs,
            currentParagraphIndex = startParagraphIndex.coerceInIndex(paragraphs),
            sections = sectionSpans()
        )
    }

    private fun Int.coerceInIndex(paragraphs: List<String>): Int =
        coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
}
