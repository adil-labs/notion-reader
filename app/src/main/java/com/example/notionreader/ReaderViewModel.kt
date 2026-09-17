package com.example.notionreader

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.notionreader.application.DefaultLoadReaderDocument
import com.example.notionreader.application.LoadReaderDocument
import com.example.notionreader.application.NoteViewer
import com.example.notionreader.application.ReaderApp
import com.example.notionreader.application.ReaderNavigator
import com.example.notionreader.application.ReaderSession
import com.example.notionreader.data.local.DataStoreSessionStore
import com.example.notionreader.data.notion.KtorNotionApi
import com.example.notionreader.data.notion.NotionRepository
import com.example.notionreader.domain.PositionMatch
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.platform.AndroidSpeechEngine
import com.example.notionreader.player.ReaderEngineImpl
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The application's lifetime, and the I/O that surrounds its state machine.
 *
 * ## Why this exists: the session's lifetime is the task's, not a screen's
 *
 * Before this type the Activity constructed [ReaderApp] in `onCreate` and called `shutdown()` in
 * `onDestroy`, which made the listening session's lifetime exactly the Activity's — and an Activity is
 * destroyed and rebuilt by the system for every configuration change. The manifest declares no
 * `android:configChanges`, so rotating the phone, turning the device's font size up, or merely letting
 * the system switch to dark mode at dusk would:
 *
 * - shut the speech engine down, ending playback mid-sentence;
 * - discard the live session, so the queue, its position and its identity were gone;
 * - be replaced by a fresh session on [ReaderDocument.Demo];
 * - re-fetch the Browse index.
 *
 * None of that is navigation, and none of it is a process death, which the Active Queue & Reader Session
 * specification *does* handle. It was a plainly reachable loss of the listener's place, and the UI
 * contract's "playback may outlive the Player screen" is not satisfied by a session that dies whenever
 * the configuration does.
 *
 * A `ViewModel` is the platform's answer to exactly this question — "state whose lifetime is longer than a
 * screen's but shorter than the process's" — and it gives the session the right two boundaries:
 *
 * | Event | Session |
 * |---|---|
 * | configuration change / screen recreation | survives, untouched |
 * | Android Back out of the app, or the task finishing | `onCleared`, then [ReaderApp.shutdown] |
 * | process death | gone; history on disk is what restores a place, never playback |
 *
 * ## Why the orchestrators moved here too
 *
 * The three collectors and the two loaders used to live in the Activity on `lifecycleScope`, which is the
 * UI class performing I/O: subscribing to the queue, writing history, fetching the Browse index. Moving
 * them here leaves the Activity with one job — render state and forward intents — and puts the I/O where
 * its owner is, on [viewModelScope]. It also means they are subscribed once per task rather than
 * re-subscribed on every configuration change.
 *
 * The one ordering constraint that must survive any edit is in [observeHistory]: history has to be read
 * **before** the persistence collector subscribes, or the first write persists an empty history plus the
 * live session and erases everything the listener had.
 *
 * This class is the composition root's other half — it names `data`, `platform` and `application`
 * together, which only the root may do. It holds an [Application] rather than an Activity, so it cannot
 * leak the thing it outlives.
 */
class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "NotionReader"
    }

    /** Set when the device cannot speak at all, which no amount of retrying will fix. */
    private val _speechMessage = MutableStateFlow<String?>(null)
    val speechMessage: StateFlow<String?> = _speechMessage.asStateFlow()

    private val speechEngine = AndroidSpeechEngine(application) { message ->
        _speechMessage.value = message
    }

    /**
     * The application's state, and the object every surface reads.
     *
     * Held here rather than in the Activity, and never rebuilt: see the class comment.
     */
    val app: ReaderApp = ReaderApp(
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
        sessionStore = DataStoreSessionStore(application)
    )

    /** The Note-screen load in flight, so superseding it cancels it rather than merely ignoring it. */
    private var noteLoadJob: Job? = null

    /** The Browse-index load in flight, for the same reason. */
    private var browseLoadJob: Job? = null

    init {
        observeQueue()
        observeHistory()
        observeResumeQuality()
        loadBrowseIndex()
    }

    /**
     * Opens a Note for inspection.
     *
     * The previous load is cancelled rather than left running: [NoteViewer] would refuse to publish a
     * superseded result anyway, so this is not what makes the screen correct — it is what stops the app
     * downloading a page nobody is waiting for. Both belts are deliberate. The guard is the correctness
     * property (it holds however the caller behaves, including a caller with no job to cancel), and the
     * cancellation is the cost saving.
     */
    fun openNote(pageId: String) {
        noteLoadJob?.cancel()
        noteLoadJob = viewModelScope.launch { app.openNote(pageId) }
    }

    /**
     * Retry for the Note screen.
     *
     * Re-loads the page the viewer last *asked* for rather than the configured page id. Those used to be
     * the same thing, and now they are not: a Browse result opens a Note that came from somewhere else
     * entirely, and a Retry that silently loaded a different page would look like the app ignoring the
     * tap.
     */
    fun retryNoteLoad() {
        noteLoadJob?.cancel()
        noteLoadJob = viewModelScope.launch { app.retryNoteLoad() }
    }

    /**
     * Retries the Browse index.
     *
     * Superseding cancels, for the same two reasons as [openNote]: `BrowseModel` drops a superseded
     * result, and a large workspace's index is not worth downloading twice.
     */
    fun retryBrowseIndex() {
        browseLoadJob?.cancel()
        browseLoadJob = viewModelScope.launch { app.loadBrowseIndex(::readBrowseIndex) }
    }

    /**
     * Continue: restores the named listening session.
     *
     * Here rather than in the surfaces because restoring a session performs I/O — the current Note is
     * fetched through the queue's own loader — and because the surfaces must not own a coroutine scope.
     * `Continue` does not cancel a Note-screen load and a Note-screen load does not cancel a restore:
     * they are different state, which is the whole point of [NoteViewer] being separate.
     */
    fun continueSession(id: String) {
        viewModelScope.launch { app.continueSession(id) }
    }

    /** Activating a completed session: a new session over the same Notes, from the beginning. */
    fun replayCompleted(id: String) {
        viewModelScope.launch { app.replayCompleted(id) }
    }

    /**
     * The two signals the queue needs from outside itself.
     *
     * Both are observations rather than callbacks the session fires, because the session is deliberately
     * scope-free and synchronous: it cannot start a coroutine to load a Note, and the engine publishes
     * completion as state rather than as an event. These two collectors are the whole of that boundary.
     */
    private fun observeQueue() {
        // A Note finished, so the queue may advance to the next one. `distinctUntilChanged` matters: the
        // engine keeps reporting `hasReachedEnd` until a new document is set, and without it the queue
        // would advance once per emission rather than once per Note.
        viewModelScope.launch {
            app.session.state
                .map { it.hasReachedEnd }
                .distinctUntilChanged()
                .collect { finished -> if (finished) app.onNoteFinished() }
        }

        // While a Note is awaited, fetch it. One step per emission, and a failed load clears `awaiting`,
        // so this cannot spin: it stops until the user presses Retry, which sets `awaiting` again.
        viewModelScope.launch {
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
        viewModelScope.launch {
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
     * The Active Queue & Reader Session spec requires this to be recorded, and it is the only way to tell
     * an exact resume from a fallback: both start playback at a plausible place, and the difference is
     * invisible in the audio until the listener notices they have heard a paragraph before. An exact resume
     * logs nothing, because there is nothing to say and a log line per resume would train the reader to
     * ignore the ones that matter.
     */
    private fun observeResumeQuality() {
        viewModelScope.launch {
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

    /**
     * Loads the Browse index.
     *
     * A no-op without a token: there is nothing to list, and the demo document keeps the rest of the app
     * usable. The index is otherwise a single request for the pages the integration can see, so it is
     * loaded once rather than per keystroke — and once per task rather than once per Activity, now that
     * this lives here.
     */
    private fun loadBrowseIndex() {
        if (BuildConfig.NOTION_TOKEN.isBlank()) return

        browseLoadJob = viewModelScope.launch { app.loadBrowseIndex(::readBrowseIndex) }
    }

    private suspend fun readBrowseIndex(): List<ReaderIndexEntry> {
        val repository = NotionRepository(KtorNotionApi(BuildConfig.NOTION_TOKEN))
        return try {
            repository.getBrowseIndex()
        } finally {
            repository.close()
        }
    }

    /**
     * Builds the use case that fetches one page by reference.
     *
     * A fresh client per call, deliberately rather than sharing one: a retry that reuses a closed client
     * is a bug that only appears on the failure path, and this is called once per Note in a multi-Note
     * queue rather than once per screen.
     */
    private fun notionLoader(): LoadReaderDocument = DefaultLoadReaderDocument { reference ->
        val repository = NotionRepository(KtorNotionApi(BuildConfig.NOTION_TOKEN))
        try {
            repository.getDocument(reference)
        } finally {
            repository.close()
        }
    }

    /**
     * The single shutdown point, and the reason the session's lifetime is now correct.
     *
     * `onCleared` runs when the Activity is *finished*, not when it is recreated, so this is called once
     * per listening session rather than once per configuration change. A session's last write may be
     * cancelled here; that is safe, because a write is a snapshot taken at every chunk boundary, so at
     * most the position within the current chunk is lost — and DataStore's writes are atomic, so a
     * cancelled one cannot corrupt the file.
     */
    override fun onCleared() {
        app.shutdown()
        super.onCleared()
    }
}
