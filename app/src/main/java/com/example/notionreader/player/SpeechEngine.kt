package com.example.notionreader.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SpeechEngineInfo(
    val packageName: String,
    val label: String
)

data class SpeechEngineState(
    val requestedPackage: String? = null,
    val activePackage: String? = null,
    val availableEngines: List<SpeechEngineInfo> = emptyList(),
    val isFallback: Boolean = false
)

/**
 * Abstraction over text-to-speech.
 *
 * [com.example.notionreader.player.ReaderEngine] knows only about this interface, never about
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

    /** Sets the speech synthesis rate factor (e.g. 1.0f for normal, 1.5f for 1.5x). */
    fun setSpeechRate(rate: Float) {}

    /** Sets the speech pitch factor (e.g. 1.0f for normal, 0.8f for low, 1.2f for high). */
    fun setPitch(pitch: Float) {}

    /** Observable runtime state of TTS engines, active selection, and fallback status. */
    val engineState: StateFlow<SpeechEngineState> get() = MutableStateFlow(SpeechEngineState())

    /** Returns the underlying platform's configured default speech rate factor. */
    fun systemSpeechRate(): Float = 1.0f

    /** Returns the underlying platform's configured default voice pitch factor. */
    fun systemPitch(): Float = 1.0f

    /** Returns all installed TTS engines discovered on the platform. */
    fun getAvailableEngines(): List<SpeechEngineInfo> = emptyList()

    /**
     * Switches the active TTS engine by package name.
     * Null indicates the system-default engine.
     */
    fun setEngine(packageName: String?) {}
}
