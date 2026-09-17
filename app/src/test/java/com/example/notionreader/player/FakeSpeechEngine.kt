package com.example.notionreader.player

/**
 * Test double for [SpeechEngine].
 *
 * Records every utterance (text plus its completion callback) and how often playback was stopped, so
 * tests can drive the platform's completion callbacks in any order they like — including out of order,
 * which is what a late callback from a cancelled utterance looks like.
 */
class FakeSpeechEngine : SpeechEngine {

    data class Utterance(
        val text: String,
        val onComplete: (() -> Unit)?
    )

    val utterances = mutableListOf<Utterance>()

    /** The text of every utterance in the order it was requested. */
    val spoken: List<String>
        get() = utterances.map { it.text }

    var stopCount = 0

    var shutdownCount = 0

    private var pendingIndex: Int? = null

    override fun speak(text: String, onComplete: (() -> Unit)?) {
        utterances += Utterance(text, onComplete)
        pendingIndex = utterances.lastIndex
    }

    override fun stop() {
        stopCount++
        pendingIndex = null
    }

    override fun shutdown() {
        shutdownCount++
    }

    /** Simulates the platform reporting that the current utterance finished. */
    fun completeUtterance() {
        val index = pendingIndex ?: return
        pendingIndex = null
        utterances[index].onComplete?.invoke()
    }

    /**
     * Invokes the completion callback of an earlier utterance, simulating a platform callback that
     * arrives after the utterance was already superseded or cancelled.
     */
    fun deliverCompletionOf(utteranceIndex: Int) {
        utterances.getOrNull(utteranceIndex)?.onComplete?.invoke()
    }
}
