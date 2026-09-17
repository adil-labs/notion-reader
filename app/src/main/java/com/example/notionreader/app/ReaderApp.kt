package com.example.notionreader.app

import com.example.notionreader.reader.ActiveQueue
import com.example.notionreader.reader.LoadReaderDocument
import com.example.notionreader.reader.QueuedNote
import com.example.notionreader.reader.QueueOrigin
import com.example.notionreader.reader.ReaderDocument
import com.example.notionreader.reader.ReaderIndexEntry
import com.example.notionreader.reader.ReaderSession
import com.example.notionreader.ui.ContinueListeningCard

/**
 * The application's state, and the only place the surfaces are wired together.
 *
 * ## Ownership
 *
 * The reason this type exists is that "who owns playback" is a decision that has to be made *once* and
 * then be visible, rather than being an emergent property of which screen happens to hold which
 * variable.
 *
 * | State | Owner | Lifetime |
 * |---|---|---|
 * | what is being read aloud, and where | [ReaderSession] | the process, across every navigation |
 * | what the Note screen is showing | [NoteViewer] | until another Note is loaded |
 * | which surface is on screen | [ReaderNavigator] | the process |
 *
 * Held by the Activity, above the surface being rendered — never by a screen. That is what makes
 * "playback may outlive the Player screen" true by construction, and it is the placement the mini-player
 * will need in order to observe the session from a second surface instead of starting a second one.
 *
 * ## The invariant worth remembering
 *
 * **[playNote] is the only operation in the application that hands a document to the listening
 * session.** Everything else either reads session state or writes non-session state. Load a different
 * Note while one is playing and the session keeps reading the old one — which is the product contract's
 * "Saved Queue edits must not mutate an Active Queue already created from that Saved Queue" and
 * "Browse filter or sort changes must not mutate an Active Queue already created from that result set",
 * appearing here at the scale of a single Note. The frozen snapshot is not a rule that was added; it is
 * what falling out of one transfer point looks like.
 *
 * ## What it deliberately does not do
 *
 * No queue, no persistence, no destination beyond Note and Player, and no `isResumable`/mini-player
 * presence rule. Each of those needs a consumer to be correct: a presence rule with no mini-player to
 * render it is a guess about what "resumable" should mean, and this project's precedent (the omitted
 * `Add to queue` button, the omitted empty Settings tab) is to leave a surface out until something
 * actually needs it.
 */
class ReaderApp(
    val session: ReaderSession,
    val noteViewer: NoteViewer,
    val navigator: ReaderNavigator,
    /** How any Note is fetched — the one the Note screen shows, and any Note in a queue. */
    private val loadDocument: LoadReaderDocument,
    val browse: BrowseModel = BrowseModel()
) {

    private val queueLoader = QueueLoader(session, loadDocument)

    fun openNote() = navigator.open(ReaderDestination.Note)

    fun openHome() = navigator.openTopLevel(ReaderDestination.Home)

    fun openBrowse() = navigator.openTopLevel(ReaderDestination.Browse)

    /**
     * The Note screen's Play: "creates a new Active Queue containing only this Note; starts from the
     * beginning; opens Player".
     *
     * The snapshot is taken here, from the Note screen's *current* document, and it is a snapshot in
     * the strict sense — the session copies the value and never re-reads [NoteViewer]. So a load that
     * completes after this point changes the Note screen and leaves the session alone.
     */
    fun playNote() {
        session.start(noteViewer.document.value)
        navigator.open(ReaderDestination.Player)
    }

    /**
     * Opens a Note from a list — a Browse result today, a queue row later.
     *
     * Navigation happens **before** the load, so a Note that fails to load still lands on the Note screen
     * showing its error, rather than leaving the user on the list with nothing to explain the tap.
     *
     * The loaded document goes to [NoteViewer] and nowhere else. Opening a Note for inspection must not
     * touch the listening session, which is the UI contract's "opening a Note never automatically starts
     * playback" and "if playback is occurring while another Note is manually inspected, the existing
     * playback session remains unchanged".
     */
    suspend fun openNote(pageId: String) {
        navigator.open(ReaderDestination.Note)
        noteViewer.load(pageId, loadDocument)
    }

    /**
     * Retry for the Note screen.
     *
     * Re-loads the page the viewer last *asked* for rather than the configured page id. Those used to be
     * the same thing, and now they are not: a Browse result opens a Note that came from somewhere else
     * entirely, and a Retry that silently loaded a different page would look like the app ignoring the
     * tap.
     */
    suspend fun retryNoteLoad() {
        noteViewer.pageId.value?.let { noteViewer.load(it, loadDocument) }
    }

    /** Loads the Browse index. Cancellation is the caller's business; see [BrowseModel.load]. */
    suspend fun loadBrowseIndex(loader: suspend () -> List<ReaderIndexEntry>) {
        browse.load(loader)
    }

    /**
     * `Play all` on what Browse is currently showing.
     *
     * The result set is read *here*, from the visible results, rather than being passed in — so the queue
     * is a snapshot of what the user can see. The contract is precise about this: "takes the currently
     * visible result set; preserves the visible order".
     */
    fun playAllVisibleResults() = playAll(browse.state.value.results.toQueuedNotes())

    /**
     * Whether a session can be resumed — the mini-player's presence rule.
     *
     * Product decision D25 (`docs/decisions.md` §12.3): a session is *surfaced* once playback has begun,
     * and stops being surfaced when the queue completes. A queue exists only after a Play or Play all, so
     * "has begun" needs no separate flag, and a completed queue is omitted because it belongs to
     * `Queues → Listening → Recently completed` rather than to a control for resuming it.
     */
    val isSessionResumable: Boolean
        get() = session.queueState.value.let { it.queue != null && !it.completed }

    /** The session as Home and the mini-player describe it, or null when there is nothing to resume. */
    fun continueListeningCard(): ContinueListeningCard? {
        if (!isSessionResumable) return null

        val queueState = session.queueState.value
        val state = session.state.value

        return ContinueListeningCard(
            title = queueState.currentItem?.title ?: session.document.value.title,
            queuePosition = queueState.positionDescription,
            // The same three-part vocabulary the Player uses, for the same reason: "stopped at the end"
            // is not "Paused", and the difference tells the listener why the reading stopped.
            stateDescription = when {
                queueState.isStalled -> "Could not load"
                queueState.awaiting -> "Loading…"
                else -> state.playbackStateDescription
            },
            isPlaying = state.isPlaying
        )
    }

    /** Opens the Player on the existing session. Does not start or change anything. */
    fun continueListening() {
        if (isSessionResumable) navigator.open(ReaderDestination.Player)
    }

    /** The Player's Play / Pause control. Resumes the existing session; it does not start a new one. */
    fun togglePlayPause() {
        if (session.isPlaying) session.pause() else session.play()
    }

    /**
     * `Play all`: snapshots [notes] in the order given and starts a new session over them.
     *
     * The contract's steps, in order: take the currently visible result set; preserve the visible order;
     * create a snapshot; create a new Active Queue; start a new listening session. The snapshot is taken
     * here, eagerly, so that re-sorting or re-filtering the source afterwards cannot change what is
     * played — the source is not consulted again, and the queue holds no reference back to it.
     *
     * Playback does not start immediately: the first Note's document is not known, so the session waits
     * for it and the loading loop supplies it. Every later Note takes the same path.
     */
    fun playAll(notes: List<QueuedNote>, origin: QueueOrigin = QueueOrigin.BrowseResults) {
        if (notes.isEmpty()) return

        session.start(ActiveQueue.snapshotOf(notes, origin))
        navigator.open(ReaderDestination.Player)
    }

    /**
     * The engine has finished reading the current Note.
     *
     * Only advances the queue's *state*. Whether the next Note can actually be loaded is decided by
     * [loadPendingQueueNote], so a failure can be handled without unwinding a half-advanced position.
     */
    fun onNoteFinished() = session.onCurrentNoteFinished()

    /**
     * Loads the Note the queue is waiting for, if any.
     *
     * Exposed because the caller owns the coroutine scope — the session and the loader are both
     * scope-free, which is what keeps their logic synchronous and testable.
     */
    suspend fun loadPendingQueueNote(): Boolean = queueLoader.loadPendingQueueNote()

    /**
     * Retry for a Note that failed to load mid-queue.
     *
     * The queue keeps its position, so this is the same Note again rather than a restart or a skip. See
     * [ReaderSession.failCurrentNote] for why stalling rather than skipping is the chosen behaviour.
     */
    fun retryQueueNote() {
        session.retryCurrentNote()
    }

    /**
     * Visual Back. Returns true when it was consumed.
     *
     * Note what this cannot do: [ReaderNavigator] has no access to the session, so Back cannot pause,
     * stop, or change what is being read — the contract's "Back must never implicitly mean: Pause,
     * Stop, Previous section, Previous Note".
     */
    fun back(): Boolean = navigator.back()

    /** Loads a Note for inspection. Never affects what is being read aloud. See [NoteViewer]. */
    suspend fun loadNote(pageId: String, loader: LoadReaderDocument) = noteViewer.load(pageId, loader)

    fun shutdown() = session.shutdown()
}