package com.example.notionreader.speech

/**
 * Abstraction over text-to-speech.
 *
 * [com.example.notionreader.reader.ReaderEngine] knows only about this interface, never about
 * `android.speech.tts.TextToSpeech`, which keeps reader logic unit-testable on the JVM.
 */
interface SpeechEngine {

    /**
     * Speaks [text] from the beginning.
     *
     * [onComplete] is invoked when the utterance finishes. It is not invoked if the utterance is
     * interrupted by [stop] (or by another [speak] call), so callers can treat it as
     * "played to the end".
     */
    fun speak(text: String, onComplete: (() -> Unit)? = null)

    /** Stops the current utterance. Android TTS has no reliable pause, so pause means stop. */
    fun stop()

    /** Releases the underlying TTS engine. Must be called when the owner is destroyed. */
    fun shutdown()
}
