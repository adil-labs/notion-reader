package com.example.notionreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.notionreader.notion.KtorNotionApi
import com.example.notionreader.notion.NotionRepository
import com.example.notionreader.reader.DocumentLoadState
import com.example.notionreader.reader.ReaderDocument
import com.example.notionreader.reader.ReaderEngine
import com.example.notionreader.reader.ReaderEngineImpl
import com.example.notionreader.speech.AndroidSpeechEngine
import com.example.notionreader.speech.SpeechEngine
import com.example.notionreader.ui.ReaderScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the reader engine and the speech engine, and wires them to the Compose UI.
 *
 * The reader starts on the hard-coded [ReaderDocument.Demo] so playback is available immediately and
 * works with no network and no credentials. When a Notion token is configured (see the README: an
 * `NOTION_TOKEN` entry in `local.properties`, or a `.env` file holding either that entry or the bare
 * token), the demo document is replaced by the Notion page without any change to the reader engine.
 *
 * A Notion failure never blocks reading: the demo document stays loaded and the reason appears on the
 * reader screen, so the app is still testable with TalkBack offline or with a bad token.
 */
class MainActivity : ComponentActivity() {

    private lateinit var speechEngine: SpeechEngine
    private lateinit var readerEngine: ReaderEngine

    private val loadState = MutableStateFlow<DocumentLoadState>(
        DocumentLoadState.Loaded(ReaderDocument.Demo)
    )

    /** Set when the device cannot speak at all, which no amount of retrying will fix. */
    private val speechMessage = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        speechEngine = AndroidSpeechEngine(applicationContext) { message ->
            speechMessage.value = message
        }
        readerEngine = ReaderEngineImpl(
            speechEngine = speechEngine,
            initialDocument = ReaderDocument.Demo
        )

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by readerEngine.state.collectAsState()
                    val document by readerEngine.document.collectAsState()
                    val load by loadState.collectAsState()
                    val speech by speechMessage.collectAsState()

                    ReaderScreen(
                        state = state,
                        documentTitle = document.title,
                        onPlayPause = {
                            if (state.isPlaying) readerEngine.pause() else readerEngine.play()
                        },
                        onNext = readerEngine::nextParagraph,
                        onPrevious = readerEngine::previousParagraph,
                        statusMessage = joinMessages(load.statusMessage, speech)
                    )
                }
            }
        }

        loadNotionDocumentIfConfigured()
    }

    override fun onDestroy() {
        readerEngine.shutdown()
        super.onDestroy()
    }

    private fun loadNotionDocumentIfConfigured() {
        val token = BuildConfig.NOTION_TOKEN

        // No token configured: keep reading the hard-coded demo document and say nothing.
        if (token.isBlank()) return

        val pageReference = BuildConfig.NOTION_PAGE_ID
        val repository = NotionRepository(KtorNotionApi(token))

        lifecycleScope.launch {
            loadState.value = DocumentLoadState.Loading

            try {
                // A blank reference makes the repository read the most recently edited page the
                // integration can see, so a token-only configuration still loads real content.
                val document = repository.getDocument(pageReference)
                readerEngine.setDocument(document)
                loadState.value = DocumentLoadState.Loaded(document)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The demo document remains loaded and playable; only the message changes.
                loadState.value = DocumentLoadState.Error(
                    e.message ?: "The document could not be loaded."
                )
            } finally {
                repository.close()
            }
        }
    }
}

/**
 * Message shown on the reader screen for a given load state, or null when nothing needs saying.
 *
 * The loaded case says nothing, because the document title and position already describe it.
 */
private val DocumentLoadState.statusMessage: String?
    get() = when (this) {
        DocumentLoadState.Loading -> "Loading the Notion document."
        is DocumentLoadState.Error -> "Could not load the Notion document. $message"
        is DocumentLoadState.Loaded -> null
    }

/** Joins the messages that need to be shown, or null when there is nothing to say. */
private fun joinMessages(vararg messages: String?): String? =
    messages.filterNotNull().joinToString(" ").takeIf { it.isNotEmpty() }
