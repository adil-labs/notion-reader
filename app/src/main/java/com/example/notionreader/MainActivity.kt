package com.example.notionreader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.notionreader.application.ReaderApp
import com.example.notionreader.ui.ReaderShell
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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

    /**
     * The notification permission request, with a **no-op result**.
     *
     * The result is deliberately discarded, and that is a requirement rather than laziness: the Background
     * Playback specification says a denial "must not stop playback, must not fail a command, and must not be
     * reported as a playback error". It degrades the listener's control surface and nothing else, so there
     * is no branch to take — the same posture the Active Queue & Reader Session spec takes toward a history
     * write failure.
     */
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = model.app
        requestNotificationPermissionOnFirstPlayback(app)

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

    /**
     * Asks for the notification permission when playback first begins.
     *
     * ## Why here and why at that moment
     *
     * **Here**, because a runtime permission request needs an Activity, and this class is the composition
     * root's rendering half. The alternative — asking from a `Context` held by the ViewModel — would need an
     * Activity reference where the whole point of that split is that the ViewModel holds only an
     * `Application`.
     *
     * **At the first playback**, because that is when the request is meaningful: the permission exists so the
     * media notification can become the foreground claim, and the notification exists so playback can
     * continue once the screen is off. Asking at launch would be asking before the listener has expressed any
     * interest in that, which is the pattern that trains people to deny.
     *
     * It is `isPlaying` rather than a button press, for the same reason the service start is: nothing in this
     * application starts speaking on its own, so that transition cannot be anything but a listener's action.
     * The request is made **at most once per Activity**, because `registerForActivityResult` cannot be called
     * twice for the same request in a row and a second call would simply be dropped — recording that here
     * because a repeated launch looks like a bug rather than a no-op.
     *
     * `Build.VERSION_CODES.TIRAMISU` is where the permission starts being required; below it, notification
     * permission is granted at install time and asking would throw. `minSdk` is 26, so the guard is load-bearing
     * rather than defensive.
     */
    private fun requestNotificationPermissionOnFirstPlayback(app: ReaderApp) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return

        var asked = false
        lifecycleScope.launch {
            app.session.state
                .map { it.isPlaying }
                .distinctUntilChanged()
                .collect { playing ->
                    if (playing && !asked) {
                        asked = true
                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
        }
    }
}

