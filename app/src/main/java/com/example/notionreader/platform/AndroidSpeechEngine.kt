package com.example.notionreader.platform

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.notionreader.player.SpeechEngine
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

/**
 * [SpeechEngine] backed by [TextToSpeech].
 *
 * Notes:
 * - TTS initialization is asynchronous, so a request that arrives before init completes is held in
 *   [pendingRequest] and spoken (or dropped, if a newer request replaces it) once ready.
 * - [UtteranceProgressListener] callbacks arrive on a TTS binder thread; the completion callback is
 *   therefore posted to the main thread before it is invoked.
 * - An utterance that is interrupted by [stop] never reports completion, so "reached the end" and
 *   "was stopped" stay distinguishable.
 * - When TTS is missing or unusable, [onUnavailable] is called instead of failing silently, because a
 *   reader that says nothing without explanation is unusable without sight.
 */
class AndroidSpeechEngine(
    context: Context,
    private val onUnavailable: ((String) -> Unit)? = null
) : SpeechEngine {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Only ever touched on the main thread. */
    private var tts: TextToSpeech? = null
    private var isReady = false
    private var isShutdown = false
    private var pendingRequest: PendingUtterance? = null
    private var utteranceCounter = 0

    private val completions = ConcurrentHashMap<String, () -> Unit>()

    private val progressListener = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            if (utteranceId == null) return
            mainHandler.post {
                completions.remove(utteranceId)?.invoke()
            }
        }

        // Overloads that add an error code / interruption flag are deprecated in favour of the single
        // argument versions, but the deprecated ones are what report failures on older platform versions.
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) {
            if (utteranceId != null) completions.remove(utteranceId)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            if (utteranceId != null) completions.remove(utteranceId)
        }
    }

    init {
        tts = TextToSpeech(context.applicationContext, ::onInitialized)
    }

    override fun speak(text: String, onComplete: (() -> Unit)?) {
        if (isShutdown || text.isBlank()) return

        if (!isReady) {
            pendingRequest = PendingUtterance(text, onComplete)
            return
        }

        speakNow(text, onComplete)
    }

    override fun stop() {
        pendingRequest = null
        completions.clear()
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    override fun shutdown() {
        isShutdown = true
        isReady = false
        pendingRequest = null
        completions.clear()

        val engine = tts
        tts = null
        try {
            engine?.stop()
            engine?.shutdown()
        } catch (_: Exception) {
        }
    }

    private fun onInitialized(status: Int) {
        val engine = tts ?: return

        if (isShutdown) {
            try {
                engine.shutdown()
            } catch (_: Exception) {
            }
            return
        }

        if (status != TextToSpeech.SUCCESS) {
            pendingRequest = null
            onUnavailable?.invoke(
                "Text to speech is not available on this device. Check the system text-to-speech settings."
            )
            return
        }

        // The device language may have no installed voice. Fall back so that pressing play speaks
        // something instead of nothing, which is the worst outcome for a reader used without sight.
        val localeResult = engine.setLanguage(Locale.getDefault())
        if (localeResult == TextToSpeech.LANG_MISSING_DATA ||
            localeResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            engine.language = Locale.US
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        engine.setAudioAttributes(audioAttributes)

        engine.setOnUtteranceProgressListener(progressListener)
        isReady = true

        val queued = pendingRequest
        pendingRequest = null
        if (queued != null) {
            speakNow(queued.text, queued.onComplete)
        }
    }

    private fun speakNow(text: String, onComplete: (() -> Unit)?) {
        val engine = tts ?: return

        utteranceCounter += 1
        val utteranceId = "notion-reader-$utteranceCounter"

        if (onComplete != null) {
            completions[utteranceId] = onComplete
        }

        try {
            val maxLength = TextToSpeech.getMaxSpeechInputLength()
            val safeText = if (text.length > maxLength) text.take(maxLength) else text
            engine.speak(safeText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } catch (_: Exception) {
            completions.remove(utteranceId)
            onComplete?.invoke()
        }
    }

    private data class PendingUtterance(
        val text: String,
        val onComplete: (() -> Unit)?
    )
}
