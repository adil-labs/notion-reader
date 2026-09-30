package com.example.notionreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import com.example.notionreader.application.ReaderApp
import com.example.notionreader.application.ReaderDestination
import com.example.notionreader.domain.queue.QueueMove
/**
 * The application shell: the destination the application is on, the mini-player, and the tab bar.
 *
 * ## Why this is a composable rather than the Activity's body
 *
 * It used to be written inline in `MainActivity.onCreate`, which made the shell the one surface in the
 * application with **no test at all**. Its correctness was established by `tools/probe-*.ps1` — a
 * scripted tap sequence whose result a human reads at the end of a round — and `docs/decisions.md` §19.9
 * recorded the cost in the round that fixed Browse's result position: the fix was four lines of Compose
 * runtime behaviour and it was verified *by construction*, because there was nowhere to assert it.
 *
 * Composed out here it can be rendered by a test with `setContent`, which is what every other screen in
 * the suite already does. That matters most for the three things a shell owns and nothing else does:
 * which destinations are tabs, whether the mini-player is on screen, and where Back goes. All three are
 * now assertions rather than readings of a log.
 *
 * ## The application object is a parameter, not a lookup
 *
 * The shell takes the [ReaderApp] it renders rather than building one, which is what makes the test
 * possible without a Notion token, a network, or a `ViewModel`. It names `application` types, which `ui`
 * is allowed to do.
 *
 * The five callbacks are the operations that perform I/O and therefore need a coroutine scope. They are
 * passed in rather than called, because a screen must not own a scope: the root package's `ReaderViewModel`
 * holds the jobs and cancels a superseded one, and the shell only forwards intent.
 *
 * ## What it deliberately does not do
 *
 * It holds no application state. What is being read aloud, what the Note screen is showing, and which
 * surface is on top all live above it, because a listening session must outlive the screen it was started
 * from — and this composable does not, since the system rebuilds the Activity for every configuration
 * change. See `ReaderViewModel` for that boundary.
 */
@Composable
fun ReaderShell(
    app: ReaderApp,
    /** The one thing that comes from outside the application object: a device-level speech failure. */
    speechMessage: String?,
    onOpenNote: (String) -> Unit,
    onRetryNoteLoad: () -> Unit,
    onRetryBrowseIndex: () -> Unit,
    onContinueSession: (String) -> Unit,
    onReplayCompleted: (String) -> Unit,
    /**
     * `Create saved queue`, which performs I/O and so needs a scope.
     *
     * The callback is responsible for **both** halves — writing the queue and, when one was created, leaving
     * the Create screen — because only it can see the outcome. A rejected name has to leave the listener on
     * the screen with their typing intact, and a Boolean handed to this composable would make the screen
     * decide that from a value that cannot distinguish "created" from "refused".
     */
    onCreateSavedQueue: (String) -> Unit,
    /**
     * The Note screen's `Add to queue`, which captures the one Note it is showing.
     *
     * **Two callbacks rather than one**, because the two controls capture different things: Browse's reads
     * its own visible result set from the model, and the Note's reads the document the viewer holds. A
     * single callback would have to guess which screen was asking, and the shell's `when` already knows —
     * which is why the distinction is made here, at the one place that has both facts.
     */
    onAddNoteToQueue: () -> Unit = {},
    /** `Add all to queue` on Browse, which captures the visible result set. */
    onAddAllToQueue: () -> Unit = {},
    /** Choosing a destination, which places the captured Notes and reports what that did. */
    onChooseQueueDestination: (String) -> Unit = {},
    /** Edit mode's four moves, for one member of the queue the details screen is about. */
    onMoveQueueMember: (String, String, QueueMove) -> Unit = { _, _, _ -> },
    /** Edit mode's `Remove`, for one member of the queue the details screen is about. */
    onRemoveQueueMember: (String, String) -> Unit = { _, _ -> },
    /** Rename a saved queue. */
    onRenameQueue: (String, String) -> Unit = { _, _ -> },
    /** Delete a saved queue. */
    onDeleteQueue: (String) -> Unit = {},
    /** Create a smart queue. */
    onCreateSmartQueue: (String, String) -> Unit = { _, _ -> },
    /** Rename a smart queue. */
    onRenameSmartQueue: (String, String) -> Unit = { _, _ -> },
    /** Delete a smart queue. */
    onDeleteSmartQueue: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val sessionState by app.session.state.collectAsState()
    val sessionDocument by app.session.document.collectAsState()
    val queueState by app.session.queueState.collectAsState()
    val noteDocument by app.noteViewer.document.collectAsState()
    val noteLoad by app.noteViewer.loadState.collectAsState()
    val browseState by app.browse.state.collectAsState()
    val navigation by app.navigator.state.collectAsState()
    // Not used directly: collecting it is what makes the lambda below re-run when history changes, since
    // `continueListeningCards()` reads values rather than being a flow.
    val history by app.history.collectAsState()
    // Collected for the same reason `history` is: the Saved tab's cards are a projection of the queue
    // records, and reading the flow here is what makes the tab recompose when one is created or edited.
    val savedQueueRecords by app.savedQueues.collectAsState()
    val smartQueueRecords by app.smartQueues.collectAsState()
    // The pending set and its message, both of which Queue Destination and the Details screen it hands over
    // to need. Collected here because the shell is what decides when they are rendered and consumed.
    val queueMessage by app.queueMessage.collectAsState()
    val current = navigation.current

    // Back is handled whenever it has somewhere visual to go, which is now most of the app rather than
    // just the Player. ReaderNavigator holds no reference to the session, so this cannot become a Back
    // that pauses, and it answers false when it is at Home with nothing pushed — at which point the
    // system's normal behaviour applies.
    if (navigation.canGoBack) {
        BackHandler { app.back() }
    }

    // The mini-player is deliberately absent on the Player, whose full surface already owns the same
    // controls, and absent when there is no resumable session.
    val miniPlayerVisible = app.isSessionResumable && current != ReaderDestination.Player

    // Each destination keeps its own `rememberSaveable` state while another destination is on screen —
    // Browse's position in its result list, most visibly. Without this the `when (current)` below removes
    // the surface from composition and takes that state with it, which is what made
    // `Browse -> Note -> Back` lose the user's place. The contract asks Browse to restore "search text;
    // filters; sort; result position"; the query came from `BrowseModel` and was already safe, and this is
    // what makes the position safe too.
    //
    // Keyed by the destination's name rather than by the enum itself, because the key is written into the
    // saved-instance-state bundle and a string is unambiguous there.
    val destinationState = rememberSaveableStateHolder()

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            destinationState.SaveableStateProvider(current.name) {
                when (current) {
                    ReaderDestination.Home -> HomeScreen(
                        // Reading `history` here is what makes Home recompose when the session moves; the
                        // cards themselves are derived from it and from the live session, so nothing here
                        // has to know which session is which.
                        continueListening = history.let { app.continueListeningCards() },
                        yourQueues = savedQueueRecords.let { app.savedQueueCards().take(5) },
                        exploreCards = app.exploreCards(),
                        onContinue = onContinueSession,
                        onOpenQueue = { queueId -> app.openSavedQueueDetails(queueId, queueId) },
                        onSeeAllQueues = app::openQueues,
                        onExplore = app::openExplore,
                        onBrowse = app::openBrowse,
                        onPlayPause = app::togglePlayPause,
                        // Where focus goes when Back returns from the Player.
                        focusTarget = navigation.focusTarget,
                        onFocusHandled = app::focusHandled
                    )

                    ReaderDestination.Browse -> BrowseScreen(
                        state = browseState.results,
                        allEntries = browseState.entries,
                        isLoading = browseState.isLoading,
                        error = browseState.error,
                        queryText = browseState.query.text,
                        query = browseState.query,
                        onQueryChange = app.browse::setText,
                        onSortChange = app.browse::setSort,
                        onTypeFilterChange = app.browse::setTypeFilter,
                        onAreaFilterChange = app.browse::setAreaFilter,
                        onTopicFilterChange = app.browse::setTopicFilter,
                        onProjectFilterChange = app.browse::setProjectFilter,
                        onStatusFilterChange = app.browse::setStatusFilter,
                        onClearFilters = app.browse::clearFilters,
                        onOpenNote = { entry -> onOpenNote(entry.pageId) },
                        onPlayAll = app::playAllVisibleResults,
                        onAddAllToQueue = onAddAllToQueue,
                        onClearQuery = app.browse::clearQuery,
                        onRetry = onRetryBrowseIndex,
                        focusTarget = navigation.focusTarget,
                        onFocusHandled = app::focusHandled
                    )

                    ReaderDestination.Queues -> QueuesScreen(
                        // Both lists are read from `history`, which is collected above for the same reason
                        // Home collects it: the entries are derived from it and from the live session, so
                        // nothing here has to know which session is which.
                        continueListening = history.let { app.listeningContinueEntries() },
                        recentlyCompleted = history.let { app.listeningCompletedEntries() },
                        // Continue restores the named session; Play on a completed entry starts a new one.
                        // Two different operations, so two different calls rather than one with a flag.
                        onContinue = onContinueSession,
                        onReplay = onReplayCompleted,
                        focusTarget = navigation.focusTarget,
                        onFocusHandled = app::focusHandled,
                        // The ordering is applied by the application layer, so the tab renders the list it
                        // is given and cannot disagree with Home's *Your queues* about which is newest.
                        savedQueues = savedQueueRecords.let { app.savedQueueCards() },
                        onOpenSavedQueue = { queueId -> app.openSavedQueueDetails(queueId, queueId) },
                        onCreateSavedQueue = app::openCreateSavedQueue,
                        smartQueues = smartQueueRecords.let { app.smartQueueCards() },
                        onOpenSmartQueue = { queueId -> app.openSmartQueueDetails(queueId, queueId) },
                        onCreateSmartQueue = app::openCreateSmartQueue
                    )

                    ReaderDestination.SavedQueueDetails -> SavedQueueDetailsScreen(
                        // Read from the navigation state rather than held here: the queue the screen is
                        // about is the pushed stop's subject, and a second copy in the shell would be a
                        // second source of truth for it.
                        queue = navigation.currentSubjectId?.let { app.savedQueueCard(it) },
                        onOpenNote = { pageId -> onOpenNote(pageId) },
                        onPlay = { queueId -> app.playSavedQueue(queueId) },
                        // Edit mode's two operations. They perform I/O, so they need the scope the shell
                        // does not have — hence two callbacks rather than a pass-through. The queue is read
                        // from the navigation state here as well, because it is the shell that knows which
                        // queue is open.
                        onMove = { pageId, move ->
                            navigation.currentSubjectId?.let { onMoveQueueMember(it, pageId, move) }
                        },
                        onRemove = { pageId ->
                            navigation.currentSubjectId?.let { onRemoveQueueMember(it, pageId) }
                        },
                        onRename = { queueId, newName -> onRenameQueue(queueId, newName) },
                        onDelete = { queueId -> onDeleteQueue(queueId) },
                        // The operation's own message, which is read here because the Add navigates to this
                        // screen and a move or a remove happens on it.
                        report = queueMessage,
                        onReportShown = app::consumeQueueMessage,
                        focusTarget = navigation.focusTarget,
                        onFocusHandled = app::focusHandled
                    )

                    ReaderDestination.SmartQueueDetails -> {
                        val smartQueue = navigation.currentSubjectId?.let { app.smartQueueCard(it) }
                        SmartQueueDetailsScreen(
                            queue = smartQueue,
                            matchingNotes = smartQueue?.let { app.matchingNotesForSmartQueue(it.queryText) } ?: emptyList(),
                            onOpenNote = { pageId -> onOpenNote(pageId) },
                            onPlay = { queueId -> app.playSmartQueue(queueId) },
                            onRename = { queueId, newName -> onRenameSmartQueue(queueId, newName) },
                            onDelete = { queueId -> onDeleteSmartQueue(queueId) },
                            report = queueMessage,
                            onReportShown = app::consumeQueueMessage,
                            focusTarget = navigation.focusTarget,
                            onFocusHandled = app::focusHandled
                        )
                    }

                    ReaderDestination.QueueDestination -> QueueDestinationScreen(
                        queues = savedQueueRecords.let { app.savedQueueCards() },
                        pendingCount = app.pendingAddCount,
                        onCreateNewQueue = app::openCreateSavedQueue,
                        onChooseQueue = onChooseQueueDestination,
                        report = queueMessage,
                        focusTarget = navigation.focusTarget,
                        onFocusHandled = app::focusHandled
                    )

                    ReaderDestination.CreateSavedQueue -> CreateSavedQueueScreen(
                        onCreate = onCreateSavedQueue
                    )

                    ReaderDestination.CreateSmartQueue -> CreateSmartQueueScreen(
                        onCreate = onCreateSmartQueue
                    )

                    ReaderDestination.Note -> NoteScreen(
                        // The inspected document, not the one being read. Loading another Note must leave
                        // the session alone, and this is what makes that visible.
                        document = noteDocument,
                        loadState = noteLoad,
                        // Play means "creates a new Active Queue containing only this Note; starts from the
                        // beginning; opens Player" — the one place a Note becomes playback, which is why it
                        // is a named action rather than a side effect of loading.
                        onPlay = app::playNote,
                        onRetry = onRetryNoteLoad,
                        // The Note's own `Add to queue`, which captures this one Note.
                        onAddToQueue = onAddNoteToQueue,
                        statusMessage = speechMessage,
                        focusTarget = navigation.focusTarget,
                        onFocusHandled = app::focusHandled
                    )

                    ReaderDestination.Player -> PlayerScreen(
                        state = sessionState,
                        // The session's title, not the Note screen's: the Player shows what is being read,
                        // which is the document Play was pressed on.
                        documentTitle = sessionDocument.title,
                        // One entry point for every Player action, so the media session the Background
                        // Playback spec adds becomes a transport rather than a second implementation.
                        onCommand = { command, index -> app.invoke(command, index) },
                        statusMessage = speechMessage,
                        queueState = queueState,
                        onRetryQueueNote = app::retryQueueNote,
                        onJumpToSection = app::jumpToSection
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

        // Only the top-level destinations are tabs. The Note and the Player are reached from them, which
        // is the UI contract's "the Player is not a fifth bottom-navigation destination" and "the Note
        // screen is not a top-level destination".
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
