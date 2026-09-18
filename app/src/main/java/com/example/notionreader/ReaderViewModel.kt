package com.example.notionreader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.notionreader.application.ReaderApp
import com.example.notionreader.data.notion.KtorNotionApi
import com.example.notionreader.data.notion.NotionRepository
import com.example.notionreader.domain.ReaderIndexEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
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
 * A `ViewModel` is the platform's answer to "state whose lifetime is longer than a screen's", and that was
 * enough while the only thing to survive was a configuration change. It is **not** the answer for a session
 * that must survive the *task*: `onCleared` runs when the Activity is finished, so a session owned here dies
 * when the listener swipes the app away — which is exactly what background playback exists to prevent.
 *
 * So the ownership was split along the line the two lifetimes actually have:
 *
 * | Event | Session and its feeding | This class |
 * |---|---|---|
 * | configuration change / screen recreation | survives, untouched | survives, untouched |
 * | Android Back out of the app, or the task dismissed | **survives** | cleared; its own I/O cancelled |
 * | process death | gone; history on disk restores a place, never playback | gone |
 *
 * The state and the loops that feed it now live in [ReaderHost], owned by [ReaderApplication], whose
 * lifetime is exactly the process's. What is left here is the I/O a screen's action starts — opening a
 * Note, retrying a load, fetching the Browse index — which *should* die with the task: nobody is waiting
 * for a page whose screen is gone. See [onCleared].
 *
 * The one ordering constraint that must survive any edit is in `ReaderHost.recordHistory`: history has to be
 * read **before** the persistence collector subscribes, or the first write persists an empty history plus
 * the live session and erases everything the listener had.
 */
class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * The process-scoped host, obtained rather than built.
     *
     * This is the correction R10 makes: the graph's lifetime is the process's, and this class no longer owns
     * it. A `ViewModel` dies when the task does, so anything it owns dies then too — and with background
     * playback that must not include the session, the queue, or the loop that fetches each Note in turn.
     */
    private val host: ReaderHost = (application as ReaderApplication).host

    /** The application's state, and the object every surface reads. */
    val app: ReaderApp = host.app

    /** A device-level speech failure, which outlives any screen that would report it. */
    val speechMessage: StateFlow<String?> = host.speechMessage

    /** The Note-screen load in flight, so superseding it cancels it rather than merely ignoring it. */
    private var noteLoadJob: Job? = null

    /** The Browse-index load in flight, for the same reason. */
    private var browseLoadJob: Job? = null

    init {
        // The session's own loops are not started here any more. Feeding the queue and recording history
        // belong to the process, because a task that is dismissed must not stop the reader from advancing to
        // its next Note — and a collector cannot outlive the scope it was launched in.
        //
        // What remains below is the I/O a *screen's* action starts, which is correctly task-scoped: closing
        // a Note or leaving Browse should cancel the fetch nobody is waiting for any more.
        loadBrowseIndex()
        startPlaybackServiceWhenReadingBegins()
    }

    /**
     * Starts the foreground service when reading actually begins — and only then.
     *
     * ## Why "playback began" is the trigger rather than "a play button was pressed"
     *
     * The platform's requirement is that a `mediaPlayback` foreground service is started in response to a
     * **user-initiated** operation while the application is visible, because that is what grants it
     * while-in-use capability. Android 17 blocks the audio APIs of a service started from the background, and
     * it does so silently: playback is suppressed with no exception and no failure message from any API.
     *
     * `isPlaying` becoming true is the tightest available expression of that. It cannot happen except from a
     * listener's action — nothing in this application starts speaking on its own, which is the Active Queue &
     * Reader Session spec's "launch never begins playback" — and it happens while the screen is necessarily
     * on, because the listener has just touched something. A trigger on the *intent* instead would start the
     * service for `Play all` before its first Note had even been fetched, and would then owe the platform a
     * foreground notification for a session that was only loading.
     *
     * ## Why this lives here rather than in [ReaderHost]
     *
     * Because starting an Android service needs a `Context`, and `ReaderHost` is deliberately Android-free so
     * that it can be tested on the JVM. It is also correctly *task*-scoped for a second reason: it is a
     * response to what a listener did on a screen, and the service it starts has its own lifetime that
     * outlives this class entirely — so a cleared ViewModel leaves the service running, which is the point.
     *
     * `distinctUntilChanged` matters: the engine re-emits state as it moves through a Note, and without it
     * every position change would ask the platform to start the service again.
     */
    private fun startPlaybackServiceWhenReadingBegins() {
        viewModelScope.launch {
            app.session.state
                .map { it.isPlaying }
                .distinctUntilChanged()
                .collect { playing -> if (playing) PlaybackService.start(getApplication()) }
        }
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
     * Deliberately **not** a shutdown point any more.
     *
     * This used to call `app.shutdown()`, which was the right answer to "nothing that must outlive a screen
     * lives in a screen" and the wrong one one level up. `onCleared` runs when the Activity is *finished* —
     * Back out of the app, or the task swiped from recents — and the session must survive that: background
     * playback exists precisely so that dismissing the task does not end the reading.
     *
     * What is cancelled here is only what `viewModelScope` owns, which is the I/O a *screen's* action
     * started: a Note load nobody is waiting for, a Browse index fetch for a screen that is gone. The
     * session, the queue, and the loops that feed them belong to `ReaderHost` and are untouched.
     */
    override fun onCleared() {
        super.onCleared()
    }
}
