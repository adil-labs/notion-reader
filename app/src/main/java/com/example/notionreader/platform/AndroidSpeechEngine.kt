package com.example.notionreader.platform

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.notionreader.player.SpeechEngine
import com.example.notionreader.player.SpeechEngineInfo
import com.example.notionreader.player.SpeechEngineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

/**
 * [SpeechEngine] backed by Android [TextToSpeech].
 *
 * Supports in-app engine switching with generation tokens, safe fallback to system default
 * if a selected package fails or is uninstalled, and explicit rate/pitch configuration.
 */
class AndroidSpeechEngine(
    private val context: Context,
    private val onUnavailable: ((String) -> Unit)? = null
) : SpeechEngine {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _engineState = MutableStateFlow(SpeechEngineState())
    override val engineState: StateFlow<SpeechEngineState> = _engineState.asStateFlow()

    /** Only ever touched on the main thread. */
    private var tts: TextToSpeech? = null
    private var isReady = false
    private var isShutdown = false
    private var pendingRequest: PendingUtterance? = null
    private var utteranceCounter = 0
    private var currentSpeechRate: Float = 1.0f
    private var currentPitch: Float = 1.0f
    private var currentEnginePackage: String? = null
    private var engineGeneration = 0

    private val completions = ConcurrentHashMap<String, () -> Unit>()

    private val progressListener = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            if (utteranceId == null) return
            mainHandler.post {
                completions.remove(utteranceId)?.invoke()
            }
        }

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
        initializeEngine(null, engineGeneration)
    }

    override fun getAvailableEngines(): List<SpeechEngineInfo> {
        val engines = try {
            tts?.engines?.map { SpeechEngineInfo(it.name, it.label) } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        if (engines.isNotEmpty() && _engineState.value.availableEngines != engines) {
            _engineState.value = _engineState.value.copy(availableEngines = engines)
        }
        return engines
    }

    override fun setEngine(packageName: String?) {
        if (isShutdown) return
        if (currentEnginePackage == packageName && isReady) return

        val gen = ++engineGeneration
        currentEnginePackage = packageName
        isReady = false
        _engineState.value = _engineState.value.copy(requestedPackage = packageName)

        val oldEngine = tts
        tts = null
        try {
            oldEngine?.stop()
            oldEngine?.shutdown()
        } catch (_: Exception) {
        }

        initializeEngine(packageName, gen)
    }

    private fun initializeEngine(packageName: String?, generation: Int) {
        try {
            tts = if (packageName != null) {
                TextToSpeech(context.applicationContext, { status ->
                    onInitialized(status, generation, packageName)
                }, packageName)
            } else {
                TextToSpeech(context.applicationContext) { status ->
                    onInitialized(status, generation, null)
                }
            }
        } catch (_: Exception) {
            if (packageName != null) {
                // Fall back immediately to system default if construction fails
                val failed = tts
                tts = null
                try {
                    failed?.shutdown()
                } catch (_: Exception) {}
                _engineState.value = _engineState.value.copy(
                    requestedPackage = packageName,
                    isFallback = true
                )
                initializeEngine(null, generation)
            } else {
                onUnavailable?.invoke("Text to speech is not available on this device.")
            }
        }
    }

    override fun setSpeechRate(rate: Float) {
        currentSpeechRate = rate
        try {
            tts?.setSpeechRate(rate)
        } catch (_: Exception) {
        }
    }

    override fun setPitch(pitch: Float) {
        currentPitch = pitch
        try {
            tts?.setPitch(pitch)
        } catch (_: Exception) {
        }
    }

    override fun systemSpeechRate(): Float = resolveSystemSpeechRate()

    override fun systemPitch(): Float = resolveSystemPitch()

    fun resolveSystemSpeechRate(): Float {
        return try {
            val rateInt = android.provider.Settings.Secure.getInt(
                context.contentResolver,
                android.provider.Settings.Secure.TTS_DEFAULT_RATE,
                100
            )
            rateInt / 100.0f
        } catch (_: Exception) {
            1.0f
        }
    }

    fun resolveSystemPitch(): Float {
        return try {
            val pitchInt = android.provider.Settings.Secure.getInt(
                context.contentResolver,
                android.provider.Settings.Secure.TTS_DEFAULT_PITCH,
                100
            )
            pitchInt / 100.0f
        } catch (_: Exception) {
            1.0f
        }
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

    private fun onInitialized(status: Int, generation: Int, requestedPackage: String?) {
        if (generation != engineGeneration || isShutdown) {
            return
        }

        val engine = tts ?: return

        if (status != TextToSpeech.SUCCESS) {
            if (requestedPackage != null) {
                // Fall back to system default engine if explicit selection failed
                currentEnginePackage = null
                val failedEngine = tts
                tts = null
                try {
                    failedEngine?.shutdown()
                } catch (_: Exception) {}
                _engineState.value = _engineState.value.copy(
                    requestedPackage = requestedPackage,
                    activePackage = null,
                    isFallback = true
                )
                initializeEngine(null, generation)
                return
            }

            pendingRequest = null
            onUnavailable?.invoke(
                "Text to speech is not available on this device. Check the system text-to-speech settings."
            )
            return
        }

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

        engine.setSpeechRate(currentSpeechRate)
        engine.setPitch(currentPitch)

        engine.setOnUtteranceProgressListener(progressListener)
        isReady = true

        val engines = getAvailableEngines()
        val defaultPkg = try { engine.defaultEngine } catch (_: Exception) { null }
        val activePkg = requestedPackage ?: defaultPkg ?: currentEnginePackage
        _engineState.value = SpeechEngineState(
            requestedPackage = requestedPackage,
            activePackage = activePkg,
            availableEngines = engines,
            isFallback = requestedPackage != null && activePkg != requestedPackage
        )

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
