package com.example.notionreader.application

import com.example.notionreader.domain.queue.ActiveQueue
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.domain.queue.QueueEdit
import com.example.notionreader.domain.queue.QueueEditOutcome
import com.example.notionreader.domain.queue.QueueEffect
import com.example.notionreader.domain.queue.QueueMove
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.queue.SavedQueue
import com.example.notionreader.domain.queue.SavedQueueDraft
import com.example.notionreader.domain.queue.SavedQueueStore
import com.example.notionreader.domain.queue.SavedQueues
import com.example.notionreader.domain.queue.SmartQueue
import com.example.notionreader.domain.queue.SmartQueueStore
import com.example.notionreader.domain.queue.SmartQueues
import com.example.notionreader.domain.queue.applyTo
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.session.ListeningHistory
import com.example.notionreader.domain.session.SessionStore
import com.example.notionreader.domain.session.StoredSession
import com.example.notionreader.domain.settings.ReaderSettings
import com.example.notionreader.domain.settings.SettingsRepository
import com.example.notionreader.player.PlayerCommand
import com.example.notionreader.player.SpeechEngine
import com.example.notionreader.player.playerCommandAvailability
import com.example.notionreader.player.ReaderSectionSpan
import java.util.UUID
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How many sessions Home offers. The contract asks for "a small useful subset"; the rest live under Queues. */
const val HOME_HISTORY_LIMIT: Int = 3

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
    val browse: BrowseModel = BrowseModel(),
    /**
     * Where listening history lives, or null when the app has none.
     *
     * Nullable rather than mandatory so that every existing test — and the JVM-only composition — keeps
     * working with no store at all. "No store" has to mean the same thing as "an empty store", because
     * the spec requires a history that cannot be read to be indistinguishable from one that is empty.
     */
    private val sessionStore: SessionStore? = null,
    /**
     * Where Saved Queues live, or null when the app has none.
     *
     * Nullable and defaulted for the same reason [sessionStore] is: every existing test, and the JVM-only
     * composition, keeps working with no store at all. A missing store and an unreadable one have to mean
     * the same thing to a surface — nothing could be read and nothing was written, and neither is an error
     * the listener sees — so both answer [QueueEditOutcome.unavailable].
     */
    private val savedQueueStore: SavedQueueStore? = null,
    private val smartQueueStore: SmartQueueStore? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val speechEngine: SpeechEngine? = null,
    /** Injected so that retention and recency are assertable without waiting for a clock. */
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    val settingsRepo: SettingsRepository = settingsRepository ?: InMemorySettingsRepository()
    val settings: StateFlow<ReaderSettings> = settingsRepo.settings

    init {
        applySettings(settingsRepo.settings.value)
    }

    private val queueLoader = QueueLoader(session, loadDocument)

    /**
     * The queue operations, over the store — or null when there is no store.
     *
     * Nullable rather than given a no-op store, because a no-op store would be an implementation of an
     * interface this project already has two of, existing only to say "nothing", and the next person to
     * read it would have to work out whether it was real. A null here says the same thing in one word.
     */
    private val queues: SavedQueues? = savedQueueStore?.let { SavedQueues(it, clock) }

    private val _savedQueues = MutableStateFlow<List<SavedQueue>>(emptyList())

    /**
     * The stored Saved Queues, in no particular order — [savedQueueCards] is what orders them.
     *
     * Exposed as state for the same reason [history] is: a surface has to recompose when a queue is created
     * or edited, and the list is the one thing every Saved-Queue surface reads.
     */
    val savedQueues: StateFlow<List<SavedQueue>> = _savedQueues.asStateFlow()

    private val smartQueuesManager: SmartQueues? = smartQueueStore?.let { SmartQueues(it, clock) }

    private val _smartQueues = MutableStateFlow<List<SmartQueue>>(emptyList())

    /**
     * Stored Smart Queues.
     */
    val smartQueues: StateFlow<List<SmartQueue>> = _smartQueues.asStateFlow()

    private val _history = MutableStateFlow<List<StoredSession>>(emptyList())

    /**
     * Retained sessions, most recently active first.
     *
     * Exposed as state so a surface recomposes when history changes, and so the live session appears in
     * it immediately: [historyIncludingLiveSession] merges the in-memory session in ahead of any write, on
     * purpose. The spec requires that a listener who pauses and immediately looks at Home sees the session
     * they just paused, and waiting for a disk write to say so would make that a race.
     */
    val history: StateFlow<List<StoredSession>> = _history.asStateFlow()

    /** Reads history and holds it. A store that cannot be read yields an empty history, by design. */
    suspend fun loadHistory() {
        val stored = sessionStore?.read() ?: return
        _history.value = ListeningHistory.retain(stored, clock())
    }

    /**
     * Writes the live session's state into history.
     *
     * Called on every position change, which is what makes a session interrupted *while playing* resumable
     * rather than only a session that was properly paused. A no-op with no queue, so the initial launch
     * emission cannot write an empty record.
     */
    suspend fun persistSession() {
        if (session.queueState.value.queue == null) return

        val next = historyIncludingLiveSession()
        _history.value = next
        sessionStore?.write(next)
    }

    private fun historyIncludingLiveSession(): List<StoredSession> {
        val live = session.snapshot(clock()) ?: return _history.value
        return ListeningHistory.retain(_history.value + live, clock(), keepId = live.id)
    }

    /** Unfinished sessions, most recently active first. What Continue listening offers. */
    val resumableSessions: List<StoredSession>
        get() = ListeningHistory.unfinished(historyIncludingLiveSession())

    /**
     * Completed sessions, most recently active first. What the Listening tab's second section lists.
     *
     * Merged with the live session for the same reason [resumableSessions] is: a session that has just
     * finished is completed *now*, and making it wait for a disk write before it appears under Recently
     * completed would mean a listener who finishes a queue and looks at history sees it still listed as
     * unfinished. Both lists read the same merged set, so a session can never be in neither.
     */
    val completedSessions: List<StoredSession>
        get() = ListeningHistory.completed(historyIncludingLiveSession())

    /** Home's Continue listening entries. */
    fun continueListeningCards(limit: Int = HOME_HISTORY_LIMIT): List<ContinueListeningCard> =
        resumableSessions.take(limit).map { it.toCard() }

    /**
     * The Listening tab's first section: every unfinished session, most recently active first.
     *
     * Deliberately not [continueListeningCards], and the difference is the point of having both surfaces.
     * The UI contract gives Home "a small useful subset" and the full history to `Queues -> Listening`, so
     * this list is uncapped while Home's is capped at [HOME_HISTORY_LIMIT]. They are also different read
     * models, because a history entry can be completed and a mini-player card cannot.
     */
    fun listeningContinueEntries(): List<HistoryEntry> = resumableSessions.map { it.toHistoryEntry() }

    /** The Listening tab's second section: every completed session, most recently active first. */
    fun listeningCompletedEntries(): List<HistoryEntry> = completedSessions.map { it.toHistoryEntry() }

    /**
     * Continue: restores the named session, or opens the Player if it is already the live one.
     *
     * The two arms are genuinely different operations and the difference is the point of having both
     * verbs. If the named session is already in memory, Continue merely shows it — the session is where it
     * was and re-adopting it would discard whatever the listener has done since. If it is a stored
     * session, Continue adopts it, which is the only operation in the application that does.
     */
    suspend fun continueSession(id: String) {
        val record = resumableSessions.firstOrNull { it.id == id } ?: return

        if (record.id != session.id) session.restore(record)

        // The session id is the activated element: a history entry is identified by its session, not by
        // its title, which is why Back from the Player can find the very entry Continue was pressed on.
        navigator.open(ReaderDestination.Player, fromFocusId = id)

        // Restoring the *state* is offline and immediate; restoring *playback* needs the current Note, and
        // may therefore fail like any other load - which is why it goes through the queue's own loader and
        // stalls rather than being reported here.
        if (session.queueState.value.awaiting) loadPendingQueueNote()
    }

    /**
     * Activating a completed session: a new session over the same Notes, from the beginning.
     *
     * Not a restore, and deliberately not routed through [continueSession]. A completed session has no
     * position to resume, and reusing its identity would overwrite the history entry the listener was
     * looking at.
     */
    suspend fun replayCompleted(id: String) {
        val record = ListeningHistory.completed(_history.value).firstOrNull { it.id == id } ?: return

        session.replay(record)
        navigator.open(ReaderDestination.Player, fromFocusId = id)
        loadPendingQueueNote()
    }

    /**
     * A stored session as the surfaces describe it.
     *
     * A session that is not the live one is always "Paused": it is not playing, and no record remembers
     * having been — `wasPlaying` is written for diagnosis and deliberately not acted on, because the spec
     * is explicit that launch never begins playback.
     */
    private fun StoredSession.toCard(): ContinueListeningCard {
        val isLive = id == session.id

        return ContinueListeningCard(
            sessionId = id,
            title = currentNote?.title ?: notes.firstOrNull()?.title.orEmpty(),
            queuePosition = if (notes.size > 1) "Note ${index + 1} of ${notes.size}" else null,
            stateDescription = if (isLive) describeLive().stateDescription else "Paused",
            isPlaying = isLive && session.isPlaying,
            lastActivityAt = lastActivityAt
        )
    }

    /**
     * A stored session as a history entry.
     *
     * The same one implementation for both Listening sections, so the two lists cannot describe the same
     * session differently — and so that the completion state, which is what decides which section an entry
     * appears in, is also what decides how it reads.
     */
    private fun StoredSession.toHistoryEntry(): HistoryEntry {
        val isLive = id == session.id

        return HistoryEntry(
            sessionId = id,
            title = currentNote?.title ?: notes.firstOrNull()?.title.orEmpty(),
            membership = when {
                // A finished queue's count is the only thing distinguishing it from a finished Note, so a
                // completed session always states it, singular included.
                completed -> if (notes.size == 1) "1 note" else "${notes.size} notes"
                // An unfinished session's position is what Continue will resume, so it is stated whenever
                // there is more than one Note to be positioned within.
                notes.size > 1 -> "Note ${index + 1} of ${notes.size}"
                else -> null
            },
            stateDescription = when {
                completed -> "Completed"
                isLive -> describeLive().stateDescription
                else -> "Paused"
            },
            isPlaying = isLive && session.isPlaying && !completed,
            isCompleted = completed,
            lastActivityAt = lastActivityAt
        )
    }

    fun openNote() = navigator.open(ReaderDestination.Note, fromFocusId = ReaderFocus.NotePlay)

    fun openHome() = navigator.openTopLevel(ReaderDestination.Home)

    fun openBrowse() = navigator.openTopLevel(ReaderDestination.Browse)

    fun openQueues() = navigator.openTopLevel(ReaderDestination.Queues)

    fun openSettings() = navigator.openTopLevel(ReaderDestination.Settings)

    fun openSettingsPlayback(fromFocusId: String? = "settings_row_playback") =
        navigator.open(ReaderDestination.SettingsPlayback, fromFocusId = fromFocusId)

    fun openSettingsSpeech(fromFocusId: String? = "settings_row_speech") =
        navigator.open(ReaderDestination.SettingsSpeech, fromFocusId = fromFocusId)

    fun openSettingsOffline(fromFocusId: String? = "settings_row_offline") =
        navigator.open(ReaderDestination.SettingsOffline, fromFocusId = fromFocusId)

    fun openSettingsAccessibility(fromFocusId: String? = "settings_row_accessibility") =
        navigator.open(ReaderDestination.SettingsAccessibility, fromFocusId = fromFocusId)

    fun openSettingsAppearance(fromFocusId: String? = "settings_row_appearance") =
        navigator.open(ReaderDestination.SettingsAppearance, fromFocusId = fromFocusId)

    fun openSettingsNotion(fromFocusId: String? = "settings_row_notion") =
        navigator.open(ReaderDestination.SettingsNotion, fromFocusId = fromFocusId)

    fun openSettingsPrivacy(fromFocusId: String? = "settings_row_privacy") =
        navigator.open(ReaderDestination.SettingsPrivacy, fromFocusId = fromFocusId)

    fun openSettingsAbout(fromFocusId: String? = "settings_row_about") =
        navigator.open(ReaderDestination.SettingsAbout, fromFocusId = fromFocusId)

    suspend fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        settingsRepo.updateSettings(transform)
        applySettings(settingsRepo.settings.value)
    }

    fun applySettings(current: ReaderSettings) {
        session.continueToNextNote = current.continueToNextNote
        session.pauseBetweenNotesMs = current.pauseBetweenNotes.delayMs
        session.announcementVerbosity = current.playbackAnnouncements
        speechEngine?.setSpeechRate(if (current.useSystemSpeechSettings) 1.0f else current.speechRate.factor)
        speechEngine?.setPitch(if (current.useSystemSpeechSettings) 1.0f else current.voicePitch.factor)
    }

    /**
     * Deterministic discovery shortcuts for the Explore section on Home.
     * Generates shortcuts transparently based on loaded Browse entries.
     */
    fun exploreCards(): List<ExploreCard> {
        val entries = browse.state.value.entries
        val candidates = listOf("Mindfulness", "Reading", "Chess", "Android", "Architecture")
        val fromEntries = candidates.mapNotNull { candidate ->
            val count = entries.count { it.title.contains(candidate, ignoreCase = true) }
            if (count > 0) ExploreCard(title = candidate, noteCount = count, queryText = candidate) else null
        }
        if (fromEntries.isNotEmpty()) return fromEntries

        return emptyList()
    }

    /**
     * Activates an Explore shortcut from Home, navigating to Browse pre-filtered with [queryText].
     */
    fun openExplore(queryText: String) {
        browse.setText(queryText)
        navigator.open(ReaderDestination.Browse)
    }

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
        // Play is the control that was activated, so it is where focus returns if the listener comes
        // back from the Player. It is a named id rather than the Note's page id because the *button* is
        // what was pressed, and the buttons of two different Notes are the same control.
        navigator.open(ReaderDestination.Player, fromFocusId = ReaderFocus.NotePlay)
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
    suspend fun openNote(pageId: String, knownRemoteLastEditedAt: java.time.Instant? = null) {
        // The page id is the activated element: a Browse row is identified by the page it opens, which is
        // also its `key` in the list, so Back can find the row the listener actually left.
        navigator.open(ReaderDestination.Note, fromFocusId = pageId)
        noteViewer.load(pageId, loadDocument, knownRemoteLastEditedAt)
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
    fun continueListeningCard(): ContinueListeningCard? =
        if (isSessionResumable) describeLive() else null

    /**
     * The live session, described.
     *
     * One implementation for the mini-player and for the entry the live session has in Home's list, so the
     * two cannot drift into describing the same session differently — which is the reason the mini-player
     * and Home's card share a type in the first place.
     */
    private fun describeLive(): ContinueListeningCard {
        val queueState = session.queueState.value
        val state = session.state.value

        return ContinueListeningCard(
            sessionId = session.id.orEmpty(),
            title = queueState.currentItem?.title ?: session.document.value.title,
            queuePosition = queueState.positionDescription,
            // The same three-part vocabulary the Player uses, for the same reason: "stopped at the end"
            // is not "Paused", and the difference tells the listener why the reading stopped.
            stateDescription = when {
                queueState.isStalled -> "Could not load"
                queueState.awaiting -> "Loading…"
                else -> state.playbackStateDescription
            },
            isPlaying = state.isPlaying,
            lastActivityAt = clock()
        )
    }

    /**
     * Opens the Player on the existing session. Does not start or change anything.
     *
     * No focus target, unlike [continueSession]: what was activated here is the mini-player, which is a
     * region rather than an identified element and is on screen on every top-level destination anyway. A
     * listener who leaves the Player with Back is at most one swipe from it, so there is nothing worth
     * restoring and no id that would be honest to record.
     */
    fun continueListening() {
        if (isSessionResumable) navigator.open(ReaderDestination.Player)
    }

    /** The Player's Play / Pause control. Resumes the existing session; it does not start a new one. */
    fun togglePlayPause() {
        if (session.isPlaying) session.pause() else session.play()
    }

    /**
     * Invokes a Player command — the single entry point every transport uses.
     *
     * ## Why the Player's buttons come through here too
     *
     * The Product & Architecture Contract requires a command to be invokable without the widget it is
     * normally drawn on, so that "Android media controls, accessibility actions, hardware controls, and
     * future voice control can invoke the same behavior". The Player's own controls are therefore one
     * caller among several rather than a special case: they read availability from
     * [playerCommandAvailability] and invoke through this, which is what will make the media session added
     * by the Background Playback spec a transport rather than a second implementation.
     *
     * ## Availability is checked here, not by the caller
     *
     * A transport may act on a stale value — a headset button pressed at the instant the queue completed —
     * so the precondition is re-checked at dispatch. A command that has become unavailable is **rejected**:
     * it changes nothing, plays nothing, and announces nothing. Rejection is not an error, because a stale
     * invocation is an ordinary race between a transport and playback rather than a fault.
     *
     * Returns true when the command was carried out.
     */
    fun invoke(command: PlayerCommand, index: Int? = null): Boolean {
        val availability = playerCommandAvailability(session.state.value, session.queueState.value)
        if (!availability.allows(command)) return false

        return when (command) {
            PlayerCommand.Play -> {
                session.play()
                true
            }
            PlayerCommand.Pause -> {
                session.pause()
                true
            }
            PlayerCommand.TogglePlayback -> {
                togglePlayPause()
                true
            }
            PlayerCommand.NextSection -> {
                session.nextSection()
                true
            }
            PlayerCommand.PreviousSection -> {
                session.previousSection()
                true
            }
            PlayerCommand.NextPart -> {
                session.nextPart()
                true
            }
            PlayerCommand.PreviousPart -> {
                session.previousPart()
                true
            }
            PlayerCommand.RestartNote -> {
                session.restartNote()
                true
            }
            PlayerCommand.NextNote -> nextNote()
            PlayerCommand.PreviousNote -> previousNote()
            // The one command that cannot act without an argument: "jump somewhere" with no destination is
            // not a command, so it is refused rather than guessed at.
            PlayerCommand.JumpToNote -> index?.let { jumpToNote(it) } ?: false
            // Surface actions. They change what the phone is displaying rather than what is being read, so
            // they belong to the surface holding that state and are refused here by design — which is also
            // what keeps them off a lock screen.
            PlayerCommand.OpenQueue,
            PlayerCommand.OpenMorePlaybackControls -> false
        }
    }

    /**
     * Jumps directly to a specific section in the current Note, preserving the playing state.
     */
    fun jumpToSection(span: ReaderSectionSpan) {
        session.jumpToSection(span)
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
        // Nothing here has a content identity — `Play all` acts on the result *set* — so it is named by
        // the control instead, which is also what the contract's focus rule wants: the listener comes
        // back to the button they pressed.
        navigator.open(ReaderDestination.Player, fromFocusId = ReaderFocus.BrowsePlayAll)
    }

    /**
     * The engine has finished reading the current Note.
     *
     * Only advances the queue's *state*. Whether the next Note can actually be loaded is decided by
     * [loadPendingQueueNote], so a failure can be handled without unwinding a half-advanced position.
     */
    fun onNoteFinished() = session.onCurrentNoteFinished()

    /** Advances to the next Note in the queue if available. */
    fun nextNote(): Boolean = session.nextNote()

    /** Steps back to the previous Note in the queue if available. */
    fun previousNote(): Boolean = session.previousNote()

    /** Jumps to a specific Note in the active queue by index. */
    fun jumpToNote(index: Int): Boolean = session.jumpToNote(index)

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
     * The destination has put focus where the user left it.
     *
     * Forwarded rather than reimplemented, so there is exactly one place that knows a focus hint is
     * one-shot. Read by the shell and by whichever screen consumed it.
     */
    fun focusHandled() = navigator.focusHandled()

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

    /**
     * Prefetches a Note into cache if a loader is configured.
     * Non-destructive and safe for background execution.
     */
    suspend fun prefetchNote(pageId: String, knownRemoteLastEditedAt: java.time.Instant? = null): Result<ReaderDocument> =
        loadDocument(pageId, knownRemoteLastEditedAt)

    // --- Saved Queues -------------------------------------------------------------------------------

    /**
     * Reads the stored queues and publishes them.
     *
     * The only operation here that *reads* the store. Everything that changes it goes through
     * [transact], because `QueueEditOutcome.queues` is authoritative: an operation that changed something
     * already hands back the whole list it produced, so a second read would be a second source of truth
     * for the same fact — and would be the read that races the write.
     */
    suspend fun loadSavedQueues() {
        _savedQueues.value = queues?.all() ?: return
    }

    /**
     * `Create saved queue`: a new, empty queue named [name].
     *
     * Returns the outcome rather than a Boolean, because the surface has to tell two answers apart that a
     * Boolean cannot: a queue was created (so leave the screen), and a name was rejected as blank (so stay,
     * and the listener's typing is still there). Navigating back on a rejection would make the screen look
     * like it had simply done nothing.
     *
     * The identity is generated here rather than inside the model, following the same rule
     * [SavedQueueDraft.toQueue] states: a value that embedded an identity source could not be tested. A
     * random UUID cannot collide, so the model's "the id must be fresh" assumption holds at the only place
     * it is made.
     *
     * ## It also receives any pending Add, which is the create-if-empty path
     *
     * The UI contract: "If no Saved Queue exists, the application may directly open Create Saved Queue rather
     * than showing an empty destination screen. The new queue receives the incoming Notes, so the listener's
     * intent — 'these things, into a queue' — is completed rather than abandoned at a screen with nothing on
     * it."
     *
     * So this is deliberately **one operation with two effects** rather than two operations a caller
     * sequences, because a caller that created the queue and forgot the Notes would leave the listener with
     * an empty queue and no indication that anything was lost. The two writes are separate `transact` calls
     * rather than a combined edit — the model has no "create with members" operation, and inventing one for
     * this would be a second way to do `Add` — and a failure between them is the same lost edit as any other
     * write failure, with the queue already created and reported as such.
     */
    suspend fun createSavedQueue(name: String): QueueEditOutcome {
        val incoming = pendingAdd
        val edit = QueueEdit.Create(SavedQueueDraft(name), id = UUID.randomUUID().toString())
        val outcome = transact(edit)

        val created = (outcome.effect as? QueueEffect.Created)?.queue ?: return outcome
        if (incoming.isEmpty()) return outcome

        // The pending set is put back and placed by the same operation a chosen destination uses, so the
        // report a listener sees after creating is worded by exactly the same code as the report after
        // choosing — the two paths cannot describe one de-duplication differently. `addPendingToQueue` clears
        // the set, which is why the restore has to happen first.
        pendingAdd = incoming
        return addPendingToQueue(created.id)
    }

    /**
     * `Play` on a Saved Queue: snapshot its current membership and order into a new session.
     *
     * The contract's steps, and this is where the second source of a live list would be — a Saved Queue is
     * mutable storage, so handing its own `notes` list to the session would let a later edit reach a
     * playing queue. [ActiveQueue.snapshotOf] copies, which is what makes that impossible rather than
     * merely avoided.
     *
     * `Play` does not mean resume: the queue is played from the beginning and any existing session is
     * replaced. Returns false when there is nothing to play — no such queue, or an empty one, which is the
     * rule `Play` is *disabled* on rather than being offered and doing nothing.
     */
    fun playSavedQueue(queueId: String): Boolean {
        val queue = _savedQueues.value.firstOrNull { it.id == queueId } ?: return false
        if (queue.notes.isEmpty()) return false

        session.start(
            ActiveQueue.snapshotOf(
                queue.notes,
                // The identity, not the name — which is the whole reason Phase 3 changed this type. A
                // session stored now still names this queue after the listener renames it.
                QueueOrigin.Saved(queueId = queue.id, name = queue.name)
            )
        )
        navigator.open(ReaderDestination.Player, fromFocusId = ReaderFocus.SavedQueuePlay)
        return true
    }

    /**
     * Opens one Saved Queue's details.
     *
     * The queue's identity goes in [ReaderStop.subjectId] rather than in `fromFocusId`: the screen is about
     * the queue, while focus returns to whichever row the listener came from — which is the queue's row on
     * the Saved tab, and will be a different row entirely from Home's *Your queues*.
     */
    fun openSavedQueueDetails(queueId: String, fromFocusId: String? = null) {
        navigator.open(ReaderDestination.SavedQueueDetails, fromFocusId = fromFocusId, subjectId = queueId)
    }

    /** The Saved tab's `Create saved queue`. Named by the control, because the screen is about nothing yet. */
    fun openCreateSavedQueue() {
        navigator.open(ReaderDestination.CreateSavedQueue, fromFocusId = ReaderFocus.SavedQueueCreate)
    }

    /** Every queue as a surface describes it, most recently edited first. */
    fun savedQueueCards(): List<SavedQueueCard> = _savedQueues.value.toCards()

    /** The queue with [id] as a surface describes it, or null when it is gone. */
    fun savedQueueCard(id: String): SavedQueueCard? = _savedQueues.value.firstOrNull { it.id == id }?.toCard()

    // --- Add: the path that gives a queue members ---------------------------------------------------

    /**
     * The Notes `Add to queue` or `Add all to queue` has captured and not yet placed.
     *
     * ## Why this is application state and not a navigation subject
     *
     * Queue Destination is about *a set of Notes to place*, and `ReaderStop.subjectId` carries an identity —
     * a string — because every other destination is about something that already exists and can be named. A
     * pending set is not: it is a payload, and encoding a list of Notes into a navigation id would be a second
     * serialization of the domain, in the one place nothing validates it.
     *
     * Holding it here also makes the *lifetime* explicit, which is the property that matters. A pending set
     * exists from the moment the control is pressed until it is placed or abandoned, and both of those are
     * things this class does. A payload living in a navigation stop would have no owner to clear it.
     *
     * ## Why it is not exposed
     *
     * A surface cannot do anything useful with the Notes themselves — the destination only needs to know
     * whether there is something to place, and [pendingAddCount] is that fact. Exposing the list would invite
     * a screen to render it, which is the Active Queue view's job and not this screen's.
     */
    private var pendingAdd: List<QueuedNote> = emptyList()

    /** How many Notes are waiting to be placed, or 0 when nothing is pending. */
    val pendingAddCount: Int get() = pendingAdd.size

    private val _queueMessage = MutableStateFlow<String?>(null)

    /**
     * What the last queue operation did, for the listener who asked for it, or null when there is nothing to
     * say.
     *
     * ## Why an operation has to report itself
     *
     * The spec is emphatic about the Add, and for a reason that is not decoration: "the common case is
     * `Add all to queue` over a result set that overlaps the destination, and a listener who adds twelve
     * things and is told nothing cannot tell 'all twelve were already there' from 'the app failed'." The
     * de-duplication rule and the report are one requirement — the dedupe without the report is
     * indistinguishable from a failure.
     *
     * The reorder has its own version of the same requirement: "a listener who cannot see the list has no
     * other way to know where the member went", so a move announces the member and its new position.
     *
     * ## Why one channel rather than two
     *
     * Because a listener experiences one operation at a time. Two flows would be two things for a surface to
     * render in one place, and the only question a second one could answer is "which kind of message is
     * this", which nothing acts on differently.
     *
     * It was called `queueReport` when the Add was the only operation that spoke; the name changed with the
     * second operation rather than the mechanism, because "report" had come to mean the Add specifically.
     *
     * ## Its lifetime, which is a *visit* rather than a frame
     *
     * Cleared by [consumeQueueMessage] when the listener leaves the screen showing it. Consuming on render
     * was the first version and it flashed the sentence for one frame — see `SavedQueueDetailsScreen`, where
     * the reasoning is, including why a listener who wants it read twice needs it to stay.
     */
    val queueMessage: StateFlow<String?> = _queueMessage.asStateFlow()

    /**
     * `Add to queue` / `Add all to queue`: capture [notes] and open Queue Destination.
     *
     * Returns false when there is nothing to add, in which case **nothing happens at all** — no navigation,
     * no pending set. A destination screen with no incoming Notes would offer a listener queues to add
     * nothing to, which is a control that cannot do what its name says.
     *
     * [fromFocusId] is the control that was pressed, which is what focus returns to if the listener backs out
     * without choosing a queue.
     */
    fun beginAddToQueue(notes: List<QueuedNote>, fromFocusId: String? = null): Boolean {
        if (notes.isEmpty()) return false

        // Captured, not referenced: the caller's list is Browse's visible result set, which re-sorts. Adding
        // from a live list would place whatever it happened to hold when the destination was finally
        // chosen — the same class of defect as handing a session a live queue, at the other end of the flow.
        pendingAdd = notes.toList()
        // A message from an earlier operation is not this one's, and leaving it would make the destination's
        // next arrival explain something that did not happen.
        _queueMessage.value = null

        navigator.open(ReaderDestination.QueueDestination, fromFocusId = fromFocusId)
        return true
    }

    /** Abandons a pending Add, which is what Back from Queue Destination does. */
    fun cancelPendingAdd() {
        pendingAdd = emptyList()
    }

    /**
     * Places the pending Notes into [queueId] and reports what that did.
     *
     * Returns the outcome so a caller that has to navigate on it can, which is the same rule
     * [createSavedQueue] follows: only the caller can see the result, so only the caller can decide what
     * happens next.
     */
    suspend fun addPendingToQueue(queueId: String): QueueEditOutcome {
        val incoming = pendingAdd
        // Cleared before the write so an operation that fails cannot leave the set pending for a second
        // attempt the listener did not ask for. A lost write is a lost edit, not a retry.
        pendingAdd = emptyList()

        val outcome = transact(QueueEdit.Add(queueId, incoming))
        val added = outcome.effect as? QueueEffect.Added
        // Only a placement the listener actually asked for is reported. An empty pending set is a no-op
        // nobody initiated — reachable when a second destination is chosen after the first placement — and
        // telling the listener "nothing was added" about an action they did not take would be worse than
        // saying nothing.
        if (added != null && added.added + added.alreadyPresent > 0) {
            _queueMessage.value = describeAdd(added)
        }
        return outcome
    }

    /** Clears the message, once the surface showing it has done so. */
    fun consumeQueueMessage() {
        _queueMessage.value = null
    }

    // --- Editing a queue's membership, which is what Edit mode drives -----------------------------------

    /**
     * `Move up` / `Move down` / `Move to top` / `Move to bottom`: the four accessible moves.
     *
     * Returns the outcome, which the caller needs for one thing the screen cannot work out for itself:
     * whether anything actually moved. A move at its own boundary is a no-op and performs no write — the
     * control is *disabled* there, so an arrival would mean a stale press — and a no-op must not be announced,
     * because "Moved Alpha to position 1 of 5" when nothing moved is a false statement about the listener's
     * queue.
     *
     * ## `outcome.changed &&` is load-bearing here, and it is not in the Remove below
     *
     * The two guards look identical and are not, which is worth stating because the tempting edit is to
     * "simplify" one to match the other. A boundary move produces a **non-null** member with `changed = false`,
     * so without the `changed &&` a reorder that did nothing would be announced as though it had happened —
     * and a failing-first proof confirms exactly that, by removing it.
     *
     * [removeSavedQueueMember]'s guard is the opposite case: `QueueEdit.Remove` yields a null member for a Note
     * that is not a member, so `removed?.member != null` already implies `changed`. Removing the `changed &&`
     * there changes no behaviour, and a proof that tried to demonstrate otherwise produced **zero failures** —
     * correct, and a finding rather than a blank. It is kept for uniformity, and this note is why it is not
     * evidence of anything.
     *
     * ## The announcement
     *
     * The Queue Creation & Queue Management spec: "Moving a member changes the list, and a listener who cannot
     * see the list has no other way to know where the member went. The announcement is the member's name and
     * its new position, brief and once." The position counts from 1 because that is the number a surface
     * speaks and the number a listener is counting in — and it is read from the *effect* rather than computed
     * here, so the number announced is the number the model reached.
     */
    suspend fun moveSavedQueueMember(
        queueId: String,
        pageId: String,
        move: QueueMove
    ): QueueEditOutcome {
        val outcome = transact(QueueEdit.Move(queueId, pageId, move))
        val moved = outcome.effect as? QueueEffect.MemberMoved
        if (outcome.changed && moved?.member != null) {
            _queueMessage.value = "Moved ${moved.member.title} to position ${moved.position} of ${moved.queue.notes.size}."
        }
        return outcome
    }

    /**
     * `Remove`: takes one member out of the queue.
     *
     * Removing the only member leaves an empty queue, which is valid — it is not a delete. Removing a Note
     * that is not a member is a no-op and is announced as nothing, for the same reason a boundary move is.
     */
    suspend fun removeSavedQueueMember(queueId: String, pageId: String): QueueEditOutcome {
        val outcome = transact(QueueEdit.Remove(queueId, pageId))
        val removed = outcome.effect as? QueueEffect.MemberRemoved
        if (outcome.changed && removed?.member != null) {
            _queueMessage.value = "Removed ${removed.member.title}."
        }
        return outcome
    }

    /**
     * `Rename`: changes the name of a Saved Queue.
     *
     * A rename to the same name is a no-op and does not write, which the model enforces.
     */
    suspend fun renameSavedQueue(queueId: String, newName: String): QueueEditOutcome {
        val outcome = transact(QueueEdit.Rename(queueId, newName))
        val renamed = outcome.effect as? QueueEffect.Renamed
        if (outcome.changed && renamed != null) {
            _queueMessage.value = "Renamed queue to ${renamed.queue.name}."
        }
        return outcome
    }

    /**
     * `Delete`: removes a Saved Queue and its members.
     *
     * The Notes themselves are untouched, and any active listening session created from it continues.
     */
    suspend fun deleteSavedQueue(queueId: String): QueueEditOutcome {
        val outcome = transact(QueueEdit.Delete(queueId))
        val deleted = outcome.effect as? QueueEffect.Deleted
        if (outcome.changed && deleted != null) {
            _queueMessage.value = "Deleted ${deleted.queue.name}."
            if (navigator.state.value.current == ReaderDestination.SavedQueueDetails &&
                navigator.state.value.currentSubjectId == queueId
            ) {
                navigator.back()
            }
        }
        return outcome
    }

    // --- Smart Queues -------------------------------------------------------------------------------

    /** Reads the stored Smart Queues and publishes them. */
    suspend fun loadSmartQueues() {
        _smartQueues.value = smartQueuesManager?.list() ?: return
    }

    /** Every Smart Queue as a surface describes it, most recently edited first. */
    fun smartQueueCards(): List<SmartQueueCard> {
        val entries = browse.state.value.entries
        return _smartQueues.value.map { queue ->
            val count = entries.count { it.title.contains(queue.queryText, ignoreCase = true) }
            val memberDesc = if (count == 1) "1 note" else "$count notes"
            SmartQueueCard(
                queueId = queue.id,
                name = queue.name,
                queryText = queue.queryText,
                memberDescription = memberDesc,
                lastEditedAt = queue.lastEditedAt
            )
        }
    }

    /** The Smart Queue with [id] as a surface describes it, or null when it is gone. */
    fun smartQueueCard(id: String): SmartQueueCard? = smartQueueCards().firstOrNull { it.queueId == id }

    /** Create a new Smart Queue. */
    suspend fun createSmartQueue(name: String, queryText: String): com.example.notionreader.domain.queue.SmartQueueEditOutcome {
        val manager = smartQueuesManager ?: return com.example.notionreader.domain.queue.SmartQueueEditOutcome.unavailable()
        val outcome = manager.create(name, queryText)
        if (outcome.effect is com.example.notionreader.domain.queue.SmartQueueEffect.Created) {
            _smartQueues.value = manager.list()
            _queueMessage.value = "Created Smart Queue ${name.trim()}."
        }
        return outcome
    }

    /** Rename a Smart Queue. */
    suspend fun renameSmartQueue(id: String, newName: String): com.example.notionreader.domain.queue.SmartQueueEditOutcome {
        val manager = smartQueuesManager ?: return com.example.notionreader.domain.queue.SmartQueueEditOutcome.unavailable()
        val outcome = manager.rename(id, newName)
        if (outcome.effect is com.example.notionreader.domain.queue.SmartQueueEffect.Renamed) {
            _smartQueues.value = manager.list()
            _queueMessage.value = "Renamed Smart Queue to ${newName.trim()}."
        }
        return outcome
    }

    /** Delete a Smart Queue. */
    suspend fun deleteSmartQueue(id: String): com.example.notionreader.domain.queue.SmartQueueEditOutcome {
        val manager = smartQueuesManager ?: return com.example.notionreader.domain.queue.SmartQueueEditOutcome.unavailable()
        val queue = _smartQueues.value.firstOrNull { it.id == id }
        val outcome = manager.delete(id)
        if (outcome.effect is com.example.notionreader.domain.queue.SmartQueueEffect.Deleted) {
            _smartQueues.value = manager.list()
            if (queue != null) {
                _queueMessage.value = "Deleted Smart Queue ${queue.name}."
            }
            if (navigator.state.value.current == ReaderDestination.SmartQueueDetails &&
                navigator.state.value.currentSubjectId == id
            ) {
                navigator.back()
            }
        }
        return outcome
    }

    /**
     * Play a Smart Queue: evaluates the query against the current index, snapshots matching Notes,
     * and begins a new listening session.
     */
    fun playSmartQueue(queueId: String): Boolean {
        val queue = _smartQueues.value.firstOrNull { it.id == queueId } ?: return false
        val entries = browse.state.value.entries
        val matching = entries.filter { it.title.contains(queue.queryText, ignoreCase = true) }
        val notes = if (matching.isNotEmpty()) {
            matching.map { QueuedNote(it.pageId, it.title) }
        } else {
            // Transparent POC fallback if index is empty
            listOf(QueuedNote(pageId = "smart-$queueId", title = "${queue.name} (${queue.queryText})"))
        }

        session.start(
            ActiveQueue.snapshotOf(
                notes,
                QueueOrigin.Smart(queueId = queue.id, name = queue.name)
            )
        )
        navigator.open(ReaderDestination.Player)
        return true
    }

    /** Opens one Smart Queue's details. */
    fun openSmartQueueDetails(queueId: String, fromFocusId: String? = null) {
        navigator.open(ReaderDestination.SmartQueueDetails, fromFocusId = fromFocusId, subjectId = queueId)
    }

    /** Opens Create Smart Queue destination. */
    fun openCreateSmartQueue() {
        navigator.open(ReaderDestination.CreateSmartQueue, fromFocusId = ReaderFocus.SmartQueueCreate)
    }

    /** Notes currently in the Browse index that match this Smart Queue's query. */
    fun matchingNotesForSmartQueue(queryText: String): List<ReaderIndexEntry> {
        val entries = browse.state.value.entries
        return entries.filter { it.title.contains(queryText, ignoreCase = true) }
    }

    /**
     * How an Add describes itself, in one or two sentences.
     *
     * Written as separate sentences rather than one template with substitutions, because each of the three
     * facts that vary has a case that reads as nonsense if it is templated:
     *
     * - **added 0** is the *successful* all-duplicates case, which the spec singles out as the common one,
     *   and it must not read like a failure. Its count belongs in the "nothing" sentence rather than beside
     *   it — a first version appended "1 was already in this queue" to "...every note was already in this
     *   queue", which says the same thing twice, and a test caught it.
     * - **one** anything is "1 note", not "1 notes".
     * - **nothing skipped** says nothing about skipping, rather than "0 were already in this queue".
     */
    private fun describeAdd(added: QueueEffect.Added): String {
        val skipped = added.alreadyPresent

        if (added.added == 0) {
            return if (skipped == 1) {
                "Nothing was added, because 1 note was already in this queue."
            } else {
                "Nothing was added, because all $skipped notes were already in this queue."
            }
        }

        val placed = if (added.added == 1) "Added 1 note." else "Added ${added.added} notes."
        if (skipped == 0) return placed

        val tail = if (skipped == 1) {
            "1 was already in this queue."
        } else {
            "$skipped were already in this queue."
        }
        return "$placed $tail"
    }

    /**
     * Runs one queue operation, publishing the result.
     *
     * **The one publishing rule.** A list is published only when the operation changed something, which is
     * also what makes an unreadable store safe: [QueueEditOutcome.unavailable] is `changed == false` with an
     * *empty* `queues`, and that empty list carries no information — it is what a store that could not be
     * read looks like. Publishing it would blank a list the listener can see, so the rule that handles a
     * no-op handles this too, and there is no second condition to remember.
     */
    private suspend fun transact(edit: QueueEdit): QueueEditOutcome {
        val outcome = queues?.apply(edit) ?: QueueEditOutcome.unavailable()
        if (outcome.changed) _savedQueues.value = outcome.queues
        return outcome
    }

    fun shutdown() = session.shutdown()
}
