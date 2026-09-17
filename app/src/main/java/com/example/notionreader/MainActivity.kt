package com.example.notionreader

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.notionreader.app.DataStoreSessionStore
import com.example.notionreader.app.NoteViewer
import com.example.notionreader.app.ReaderApp
import com.example.notionreader.app.ReaderDestination
import com.example.notionreader.app.ReaderNavigator
import com.example.notionreader.notion.KtorNotionApi
import com.example.notionreader.notion.NotionRepository
import com.example.notionreader.reader.DefaultLoadReaderDocument
import com.example.notionreader.reader.LoadReaderDocument
import com.example.notionreader.reader.ReaderDocument
import com.example.notionreader.reader.ReaderEngineImpl
import com.example.notionreader.reader.ReaderSession
import com.example.notionreader.reader.PositionMatch
import com.example.notionreader.speech.AndroidSpeechEngine
import com.example.notionreader.speech.SpeechEngine
import com.example.notionreader.ui.BrowseScreen
import com.example.notionreader.ui.HomeScreen
import com.example.notionreader.ui.MiniPlayer
import com.example.notionreader.ui.NoteScreen
import com.example.notionreader.ui.PlayerScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Holds the application state above the surface being rendered, and wires it to the Compose UI.
 *
 * Everything of consequence lives in [ReaderApp] rather than here, and deliberately so: what is being
 * read aloud belongs to a listening session that must outlive whichever screen is on top, and this
 * Activity is the lowest place that outlives all of them. A screen that owned it would stop playback
 * on the way out, which the UI contract forbids twice ("playback may outlive the Player screen" and
 * "navigating away from Player does not stop playback").
 *
 * **Two documents, not one.** The Note screen shows what [NoteViewer] holds — the page as loaded —
 * while the listening session holds its own snapshot, taken when Play was pressed. They are the same
 * document only until the user does something. Before this split there was a single document, and
 * since `setDocument` stops speech and resets the position, loading a page to read it was also how the
 * app ended whatever was playing: this Activity starts a load after `setContent`, so tapping Play on
 * the demo document and letting the load finish silently killed playback, and Retry — the same call —
 * did it too. `ReaderAppTest` pins the fix.
 *
 * The Note screen starts on the hard-coded [ReaderDocument.Demo] and is replaced by a Notion page once
 * the user opens one, so the shell has something readable with no network and no credentials, and the
 * session has something to play. A failure never blocks reading: the previous document stays on screen
 * and the Note screen reports what went wrong and, when the failure's category says it is worth it,
 * offers Retry.
 *
 * **Nothing is fetched at launch.** There used to be an eager load of `BuildConfig.NOTION_PAGE_ID` that
 * opened the Note screen and then called [ReaderApp.openHome], and since Home is a top-level destination
 * whose whole job is to clear whatever was pushed on top of it, that load navigated to a screen it was
 * immediately navigated away from. The page was fetched on every launch and displayed nowhere. It was
 * left over from the two-screen architecture, where the Note screen *was* the start destination and so
 * had to be loaded to be useful; in the shell the Note screen is reached from a Browse result, which
 * names the page itself, so a page chosen at launch could only ever have been the wrong one. Deleting it
 * removes a network request, a wasted 40-second page fetch on the pinned spec page, and a stale
 * `NOTION_PAGE_ID` build field's last consumer — see `docs/decisions.md` §17.
 *
 * Navigating between the two screens is modelled by the app package rather than by a navigation
 * library. The reasoning is recorded in `docs/decisions.md` §12.6; the short version is that the UI
 * contract's real graph is four top-level destinations plus ten secondary screens, and three of them
 * have no content yet, so the shape that would be frozen is not yet known.
 */
class MainActivity : ComponentActivity() {

    private companion object {
        const val TAG = "NotionReader"
    }

    private lateinit var speechEngine: SpeechEngine
    private lateinit var app: ReaderApp

    /** Set when the device cannot speak at all, which no amount of retrying will fix. */
    private val speechMessage = MutableStateFlow<String?>(null)

    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        speechEngine = AndroidSpeechEngine(applicationContext) { message ->
            speechMessage.value = message
        }

        app = ReaderApp(
            session = ReaderSession(
                ReaderEngineImpl(
                    speechEngine = speechEngine,
                    initialDocument = ReaderDocument.Demo
                )
            ),
            noteViewer = NoteViewer(ReaderDocument.Demo),
            navigator = ReaderNavigator(),
            // One loader for every Note: the one the Note screen shows, and each Note in a queue, so
            // queue loading inherits the error taxonomy rather than growing a second, thinner path.
            loadDocument = notionLoader(),
            sessionStore = DataStoreSessionStore(applicationContext)
        )

        observeQueue()
        observeHistory()
        observeResumeQuality()
        loadBrowseIndex()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val sessionState by app.session.state.collectAsState()
                    val sessionDocument by app.session.document.collectAsState()
                    val queueState by app.session.queueState.collectAsState()
                    val noteDocument by app.noteViewer.document.collectAsState()
                    val noteLoad by app.noteViewer.loadState.collectAsState()
                    val browseState by app.browse.state.collectAsState()
                    val speech by speechMessage.collectAsState()
                    val navigation by app.navigator.state.collectAsState()
                    // Not used directly: collecting it is what makes the lambda below re-run when history
                    // changes, since `continueListeningCards()` reads values rather than being a flow.
                    val history by app.history.collectAsState()
                    val current = navigation.current

                    // Back is handled whenever it has somewhere visual to go, which is now most of the
                    // app rather than just the Player. ReaderNavigator holds no reference to the
                    // session, so this cannot become a Back that pauses, and it answers false when it is
                    // at Home with nothing pushed — at which point the system's normal behaviour applies.
                    if (navigation.canGoBack) {
                        BackHandler { app.back() }
                    }

                    // The mini-player is deliberately absent on the Player, whose full surface already
                    // owns the same controls, and absent when there is no resumable session.
                    val miniPlayerVisible = app.isSessionResumable && current != ReaderDestination.Player

                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) {
                            when (current) {
                                ReaderDestination.Home -> HomeScreen(
                                    // Reading `history` here is what makes Home recompose when the session
                                    // moves; the cards themselves are derived from it and from the live
                                    // session, so nothing here has to know which session is which.
                                    continueListening = history.let { app.continueListeningCards() },
                                    onContinue = { id -> lifecycleScope.launch { app.continueSession(id) } },
                                    onBrowse = app::openBrowse,
                                    onPlayPause = app::togglePlayPause
                                )

                                ReaderDestination.Browse -> BrowseScreen(
                                    state = browseState.results,
                                    isLoading = browseState.isLoading,
                                    error = browseState.error,
                                    queryText = browseState.query.text,
                                    onQueryChange = app.browse::setText,
                                    onOpenNote = { entry ->
                                        lifecycleScope.launch { app.openNote(entry.pageId) }
                                    },
                                    onPlayAll = app::playAllVisibleResults,
                                    onClearQuery = app.browse::clearQuery,
                                    onRetry = ::loadBrowseIndex
                                )

                                ReaderDestination.Note -> NoteScreen(
                                    // The inspected document, not the one being read. Loading another
                                    // Note must leave the session alone, and this is what makes that
                                    // visible.
                                    document = noteDocument,
                                    loadState = noteLoad,
                                    // Play means "creates a new Active Queue containing only this Note;
                                    // starts from the beginning; opens Player" — the one place a Note
                                    // becomes playback, which is why it is a named action rather than a
                                    // side effect of loading.
                                    onPlay = app::playNote,
                                    onRetry = ::retryNoteLoad,
                                    statusMessage = speech
                                )

                                ReaderDestination.Player -> PlayerScreen(
                                    state = sessionState,
                                    // The session's title, not the Note screen's: the Player shows what
                                    // is being read, which is the document Play was pressed on.
                                    documentTitle = sessionDocument.title,
                                    onPlayPause = app::togglePlayPause,
                                    onNextSection = app.session::nextSection,
                                    onPreviousSection = app.session::previousSection,
                                    statusMessage = speech,
                                    queueState = queueState,
                                    onRetryQueueNote = app::retryQueueNote
                                )
                            }
                        }

                        if (miniPlayerVisible) {
                            val card = app.continueListeningCard()
                            if (card != null) {
                                MiniPlayer(
                                    session = card,
                                    onOpenPlayer = app::continueListening,
                                    onPlayPause = app::togglePlayPause
                                )
                            }
                        }

                        // Only the top-level destinations are tabs. The Note and the Player are reached
                        // from them, which is the UI contract's "the Player is not a fifth
                        // bottom-navigation destination" and "the Note screen is not a top-level
                        // destination".
                        NavigationBar {
                            NavigationBarItem(
                                selected = navigation.top == ReaderDestination.Home,
                                onClick = { app.openHome() },
                                icon = {},
                                // Labels carry the accessible name, so no item is an unlabelled button.
                                label = { Text("Home") }
                            )
                            NavigationBarItem(
                                selected = navigation.top == ReaderDestination.Browse,
                                onClick = { app.openBrowse() },
                                icon = {},
                                label = { Text("Browse") }
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * The two signals the queue needs from outside itself.
     *
     * Both are observations rather than callbacks the session fires, because the session is deliberately
     * scope-free and synchronous: it cannot start a coroutine to load a Note, and the engine publishes
     * completion as state rather than as an event. These two collectors are the whole of that boundary.
     *
     * They are collected on the lifecycle scope, so a load in flight stops when the Activity does.
     */
    private fun observeQueue() {
        // A Note finished, so the queue may advance to the next one. `distinctUntilChanged` matters: the
        // engine keeps reporting `hasReachedEnd` until a new document is set, and without it the queue
        // would advance once per emission rather than once per Note.
        lifecycleScope.launch {
            app.session.state
                .map { it.hasReachedEnd }
                .distinctUntilChanged()
                .collect { finished -> if (finished) app.onNoteFinished() }
        }

        // While a Note is awaited, fetch it. One step per emission, and a failed load clears `awaiting`,
        // so this cannot spin: it stops until the user presses Retry, which sets `awaiting` again.
        lifecycleScope.launch {
            app.session.queueState
                .map { it.awaiting }
                .distinctUntilChanged()
                .collect { awaiting -> if (awaiting) app.loadPendingQueueNote() }
        }
    }

    /**
     * Records the live session into history.
     *
     * ## The load must finish before this subscribes, and that is a correctness requirement rather than a
     * ## nicety
     *
     * [ReaderApp.persistSession] writes the retained set, which is history **plus** the live session. If a
     * write could happen first, it would persist an empty history plus the live session and silently
     * discard every session already on disk — the listener's entire Continue listening list, erased by the
     * launch of the app. So history is loaded in the same coroutine, before the collector starts.
     *
     * Collecting the position rather than a timer is what makes a session interrupted *while playing*
     * resumable. Writing only on pause would mean the common case — the phone being taken out of the
     * listener's hand mid-Note — loses everything since the last pause.
     */
    private fun observeHistory() {
        lifecycleScope.launch {
            app.loadHistory()

            combine(
                app.session.state.map { listOf(it.currentParagraphIndex, it.isPlaying, it.hasReachedEnd) },
                app.session.queueState.map { listOf(it.index, it.awaiting, it.completed) }
            ) { reading, queue -> reading + queue }
                .distinctUntilChanged()
                .collect { app.persistSession() }
        }
    }

    /**
     * Records the match quality of a restore.
     *
     * The Active Queue & Reader Session spec requires this to be recorded, and it is the only way to tell an
     * exact resume from a fallback: both start playback at a plausible place, and the difference is
     * invisible in the audio until the listener notices they have heard a paragraph before. An exact resume
     * logs nothing, because there is nothing to say and a log line per resume would train the reader to
     * ignore the ones that matter.
     */
    private fun observeResumeQuality() {
        lifecycleScope.launch {
            // No `distinctUntilChanged`: the source is already a StateFlow, which conflates, so applying it
            // to the flow directly is a deprecated no-op. The filter below is what decides what is worth
            // recording.
            app.session.lastResume.collect { decision ->
                val match = decision?.match ?: return@collect
                val announcement = decision.announcement

                // An exact resume says nothing: the text is the continuation, and a log line per resume
                // would train the reader to ignore the ones that matter.
                if (match == PositionMatch.CHUNK && announcement == null) return@collect

                Log.i(
                    TAG,
                    "resume match=$match startIndex=${decision.startIndex} " +
                        "announcement=${announcement ?: "none"}"
                )
            }
        }
    }

    override fun onDestroy() {
        app.shutdown()
        super.onDestroy()
    }

    /**
     * Loads the Browse index.
     *
     * A no-op without a token: there is nothing to list, and the demo document keeps the rest of the app
     * usable. The index is otherwise a single request for the pages the integration can see, so it is
     * loaded when Browse opens rather than per keystroke.
     */
    private fun loadBrowseIndex() {
        if (BuildConfig.NOTION_TOKEN.isBlank()) return

        lifecycleScope.launch {
            app.loadBrowseIndex {
                val repository = NotionRepository(KtorNotionApi(BuildConfig.NOTION_TOKEN))
                try {
                    repository.getBrowseIndex()
                } finally {
                    repository.close()
                }
            }
        }
    }

    /**
     * Retries the Note screen's load.
     *
     * Re-loads the page the viewer asked for, not the configured page id — a Browse result can open any
     * page, so those are no longer the same thing.
     */
    private fun retryNoteLoad() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch { app.retryNoteLoad() }
    }

    /**
     * Builds the use case that fetches one page by reference.
     *
     * A fresh client per call, deliberately rather than sharing one: a retry that reuses a closed client
     * is a bug that only appears on the failure path, and this is now called once per Note in a
     * multi-Note queue rather than once per screen.
     */
    private fun notionLoader(): LoadReaderDocument = DefaultLoadReaderDocument { reference ->
        val repository = NotionRepository(KtorNotionApi(BuildConfig.NOTION_TOKEN))
        try {
            repository.getDocument(reference)
        } finally {
            repository.close()
        }
    }
}
