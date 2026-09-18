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
import androidx.lifecycle.ViewModelProvider
import com.example.notionreader.ui.ReaderShell

/**
 * The composition root's rendering half: it builds the retained root of the object graph, collects the
 * one value that does not come from it, and hands both to [ReaderShell].
 *
 * That is deliberately all it does. It owns no application state, and it draws no surface — the shell,
 * the destinations, the mini-player and the tab bar all live in `ui`, which is what lets them be rendered
 * by a test. Until this round the shell was written inline here, and it was therefore the one surface in
 * the application with no instrumentation at all: its correctness was established by scripted tap
 * sequences whose output a human read (`tools/probe-*.ps1`), and `docs/decisions.md` §19.9 recorded the
 * cost — Browse's result-position fix was four lines of Compose runtime behaviour that could only be
 * verified by construction, because there was nowhere to assert it.
 *
 * ## Why [ReaderViewModel] and not this Activity
 *
 * A listening session must outlive the screen it was started from, and this Activity does not: the system
 * destroys and rebuilds it for every configuration change — rotating the phone, raising the font size, or
 * merely letting the system switch to dark mode at dusk. While the session was owned here, any of those
 * ended playback mid-sentence, discarded the queue and its position, and started a fresh session on the
 * demo document (`docs/decisions.md` §19.4). The ViewModel is the platform's answer to that lifetime, and
 * `onCleared` — called when the Activity is *finished*, not when it is recreated — is the single shutdown
 * point.
 *
 * The ViewModel also owns the I/O that surrounds the state machine: the queue, history and resume
 * collectors, the Note and Browse loaders, and the job slots that cancel a superseded fetch. The five
 * callbacks below are that boundary — the shell forwards intent, and the owner with the coroutine scope
 * performs it.
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
                    val speech by model.speechMessage.collectAsState()

                    ReaderShell(
                        app = app,
                        speechMessage = speech,
                        onOpenNote = model::openNote,
                        onRetryNoteLoad = model::retryNoteLoad,
                        onRetryBrowseIndex = model::retryBrowseIndex,
                        onContinueSession = model::continueSession,
                        onReplayCompleted = model::replayCompleted
                    )
                }
            }
        }
    }
}

