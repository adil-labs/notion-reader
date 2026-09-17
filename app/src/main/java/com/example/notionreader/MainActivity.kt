package com.example.notionreader

import android.os.Bundle
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.example.notionreader.application.ReaderDestination
import com.example.notionreader.ui.BrowseScreen
import com.example.notionreader.ui.HomeScreen
import com.example.notionreader.ui.MiniPlayer
import com.example.notionreader.ui.NoteScreen
import com.example.notionreader.ui.PlayerScreen
import com.example.notionreader.ui.QueuesScreen

/**
 * The Compose host, and nothing else: it draws the destination the application is on and forwards
 * intents to [ReaderViewModel].
 *
 * It deliberately owns no application state. What is being read aloud, what the Note screen is showing,
 * and which surface is on top all live in the ViewModel, because a listening session must outlive the
 * screen it was started from and *this Activity does not* — the system rebuilds it for every
 * configuration change. Until that moved, rotating the phone ended playback and discarded the session;
 * see [ReaderViewModel] for the boundary and what it fixes. The Activity's remaining job is the two
 * things a composition root must do: build the retained root of the object graph, and render it.
 *
 * **Two documents, not one.** The Note screen shows what the `NoteViewer` holds — the page as loaded —
 * while the listening session holds its own snapshot, taken when Play was pressed. They are the same
 * document only until the user does something. Before this split there was a single document, and
 * since `setDocument` stops speech and resets the position, loading a page to read it was also how the
 * app ended whatever was playing: a load started after `setContent` meant tapping Play on the demo
 * document and letting the load finish silently killed playback, and Retry — the same call — did it too.
 * `ReaderAppTest` pins the fix.
 *
 * The Note screen starts on the hard-coded demo document and is replaced by a Notion page once the user
 * opens one, so the shell has something readable with no network and no credentials, and the session has
 * something to play. A failure never blocks reading: the previous document stays on screen and the Note
 * screen reports what went wrong and, when the failure's category says it is worth it, offers Retry.
 *
 * **Nothing is fetched at launch.** There used to be an eager load of `BuildConfig.NOTION_PAGE_ID` that
 * opened the Note screen and then returned Home, and since Home is a top-level destination whose whole job
 * is to clear whatever was pushed on top of it, that load navigated to a screen it was immediately
 * navigated away from. The page was fetched on every launch and displayed nowhere. Deleting it removes a
 * network request, a wasted 40-second page fetch on the pinned spec page, and a stale `NOTION_PAGE_ID`
 * build field's last consumer — see `docs/decisions.md` §17. The Browse index *is* loaded at launch, which
 * is a request Browse's default result set needs and which now happens once per task rather than once per
 * Activity.
 *
 * Navigating between the screens is modelled by the app package rather than by a navigation library. The
 * reasoning is recorded in `docs/decisions.md` §12.6; the short version is that the UI contract's real
 * graph is four top-level destinations plus ten secondary screens, and three of them have no content yet,
 * so the shape that would be frozen is not yet known.
 */
class MainActivity : ComponentActivity() {

    /**
     * The retained owner of everything that must outlive this Activity.
     *
     * Obtained rather than constructed, and obtained with `ViewModelProvider` rather than with a delegate,
     * so that this class needs nothing but `lifecycle-viewmodel`: the Activity's own factory already knows
     * how to build an `AndroidViewModel` from the [android.app.Application].
     */
    private val model: ReaderViewModel by lazy { ViewModelProvider(this)[ReaderViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = model.app

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val sessionState by app.session.state.collectAsState()
                    val sessionDocument by app.session.document.collectAsState()
                    val queueState by app.session.queueState.collectAsState()
                    val noteDocument by app.noteViewer.document.collectAsState()
                    val noteLoad by app.noteViewer.loadState.collectAsState()
                    val browseState by app.browse.state.collectAsState()
                    val speech by model.speechMessage.collectAsState()
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

                    // Each destination keeps its own `rememberSaveable` state while another destination is
                    // on screen — Browse's position in its result list, most visibly. Without this the
                    // `when (current)` below removes the surface from composition and takes that state with
                    // it, which is what made `Browse -> Note -> Back` lose the user's place. The contract
                    // asks Browse to restore "search text; filters; sort; result position"; the query came
                    // from `BrowseModel` and was already safe, and this is what makes the position safe too.
                    //
                    // Keyed by the destination's name rather than by the enum itself, because the key is
                    // written into the saved-instance-state bundle and a string is unambiguous there.
                    val destinationState = rememberSaveableStateHolder()

                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) {
                            destinationState.SaveableStateProvider(current.name) {
                                when (current) {
                                    ReaderDestination.Home -> HomeScreen(
                                        // Reading `history` here is what makes Home recompose when the
                                        // session moves; the cards themselves are derived from it and from
                                        // the live session, so nothing here has to know which session is
                                        // which.
                                        continueListening = history.let { app.continueListeningCards() },
                                        onContinue = model::continueSession,
                                        onBrowse = app::openBrowse,
                                        onPlayPause = app::togglePlayPause
                                    )

                                    ReaderDestination.Browse -> BrowseScreen(
                                        state = browseState.results,
                                        isLoading = browseState.isLoading,
                                        error = browseState.error,
                                        queryText = browseState.query.text,
                                        onQueryChange = app.browse::setText,
                                        onOpenNote = { entry -> model.openNote(entry.pageId) },
                                        onPlayAll = app::playAllVisibleResults,
                                        onClearQuery = app.browse::clearQuery,
                                        onRetry = model::retryBrowseIndex
                                    )

                                    ReaderDestination.Queues -> QueuesScreen(
                                        // Both lists are read from `history`, which is collected above for
                                        // the same reason Home collects it: the entries are derived from it
                                        // and from the live session, so nothing here has to know which
                                        // session is which.
                                        continueListening = history.let { app.listeningContinueEntries() },
                                        recentlyCompleted = history.let { app.listeningCompletedEntries() },
                                        // Continue restores the named session; Play on a completed entry
                                        // starts a new one. Two different operations, so two different calls
                                        // rather than one with a flag.
                                        onContinue = model::continueSession,
                                        onReplay = model::replayCompleted
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
                                        onRetry = model::retryNoteLoad,
                                        statusMessage = speech
                                    )

                                    ReaderDestination.Player -> PlayerScreen(
                                        state = sessionState,
                                        // The session's title, not the Note screen's: the Player shows
                                        // what is being read, which is the document Play was pressed on.
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
                            NavigationBarItem(
                                selected = navigation.top == ReaderDestination.Queues,
                                onClick = { app.openQueues() },
                                icon = {},
                                label = { Text("Queues") }
                            )
                        }
                    }
                }
            }
        }
    }
}
