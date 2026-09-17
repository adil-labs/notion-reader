package com.example.notionreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import com.example.notionreader.reader.DefaultLoadReaderDocument
import com.example.notionreader.reader.DocumentLoadState
import com.example.notionreader.reader.LoadReaderDocument
import com.example.notionreader.reader.ReaderDocument
import com.example.notionreader.reader.ReaderEngine
import com.example.notionreader.reader.ReaderEngineImpl
import com.example.notionreader.reader.readerErrorCategory
import com.example.notionreader.reader.readerErrorMessage
import com.example.notionreader.speech.AndroidSpeechEngine
import com.example.notionreader.speech.SpeechEngine
import com.example.notionreader.ui.NoteScreen
import com.example.notionreader.ui.PlayerScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the reader engine and the speech engine, and wires them to the Compose UI.
 *
 * The reader starts on the hard-coded [ReaderDocument.Demo] so playback is available immediately and
 * works with no network and no credentials. When a Notion token is configured (see the README: a
 * `NOTION_TOKEN` entry in `local.properties`, or a `.env` file holding either that entry or the bare
 * token), the demo document is replaced by the Notion page without any change to the reader engine.
 *
 * A Notion failure never blocks reading: the demo document stays loaded and playable while the Note
 * screen reports what went wrong and, when the failure's category says it is worth it, offers Retry.
 */
class MainActivity : ComponentActivity() {

    private lateinit var speechEngine: SpeechEngine
    private lateinit var readerEngine: ReaderEngine

    private val loadState = MutableStateFlow<DocumentLoadState>(
        DocumentLoadState.Loaded(ReaderDocument.Demo)
    )

    /** Set when the device cannot speak at all, which no amount of retrying will fix. */
    private val speechMessage = MutableStateFlow<String?>(null)

    private val destination = MutableStateFlow(ReaderDestination.Note)

    private var loadJob: Job? = null

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
                    val current by destination.collectAsState()

                    // Only the Player can be left. Its Back contract is "returns to the previous visual
                    // context when possible" and "playback continues", so this deliberately does not
                    // touch the reader engine: moving between screens must never change what is read.
                    if (current == ReaderDestination.Player) {
                        BackHandler { destination.value = ReaderDestination.Note }
                    }

                    when (current) {
                        ReaderDestination.Note -> NoteScreen(
                            document = document,
                            loadState = load,
                            onPlay = {
                                // The contract's Play means "starts from the beginning" and "opens
                                // Player"; historical per-note progress is not resumed.
                                readerEngine.playFromStart()
                                destination.value = ReaderDestination.Player
                            },
                            onRetry = ::loadNotionDocument,
                            statusMessage = speech
                        )

                        ReaderDestination.Player -> PlayerScreen(
                            state = state,
                            documentTitle = document.title,
                            onPlayPause = {
                                if (state.isPlaying) readerEngine.pause() else readerEngine.play()
                            },
                            onNextSection = readerEngine::nextSection,
                            onPreviousSection = readerEngine::previousSection,
                            statusMessage = speech
                        )
                    }
                }
            }
        }

        if (BuildConfig.NOTION_TOKEN.isNotBlank()) loadNotionDocument()
    }

    override fun onDestroy() {
        readerEngine.shutdown()
        super.onDestroy()
    }

    /**
     * Loads the configured Notion page, or the most recently edited page when none is configured.
     *
     * This is also the Retry action, so it has to be safe to call again: any previous attempt is
     * cancelled first, and each attempt builds and closes its own client. Building a client per attempt
     * is deliberately wasteful rather than sharing one — a retry that reuses a closed client is a bug
     * that only appears on the failure path, which is the path this milestone exists to make correct.
     */
    private fun loadNotionDocument() {
        val loader: LoadReaderDocument = DefaultLoadReaderDocument { reference ->
            val repository = NotionRepository(KtorNotionApi(BuildConfig.NOTION_TOKEN))
            try {
                repository.getDocument(reference)
            } finally {
                repository.close()
            }
        }

        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            loadState.value = DocumentLoadState.Loading

            // Cancellation is thrown by this call rather than folded into a failure, so abandoning a
            // load cannot leave "could not load" on screen for a page the user merely navigated away
            // from. See DefaultLoadReaderDocument.
            val result = loader(BuildConfig.NOTION_PAGE_ID)

            result.fold(
                onSuccess = { document ->
                    readerEngine.setDocument(document)
                    loadState.value = DocumentLoadState.Loaded(document)
                },
                onFailure = { error ->
                    // The demo document stays loaded and playable; only the reported state changes.
                    loadState.value = DocumentLoadState.Error(
                        category = error.readerErrorCategory,
                        message = error.readerErrorMessage
                    )
                }
            )
        }
    }
}

/**
 * The two screens this milestone has.
 *
 * A hand-written pair rather than a navigation library: the UI contract's real graph is four
 * top-level destinations plus ten secondary screens, and the Note and Player are reached from surfaces
 * (Browse, Home, Queues) that do not exist yet. Introducing Navigation Compose to move between two
 * screens would freeze the shape of a graph before the screens that determine it are written.
 */
private enum class ReaderDestination {
    Note,
    Player
}
