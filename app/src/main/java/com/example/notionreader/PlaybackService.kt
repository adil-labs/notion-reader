package com.example.notionreader

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * The foreground service that lets playback outlive the task.
 *
 * ## Why a service, and why this one
 *
 * Android 17 enforces background audio hardening on every application that interacts with audio while it has
 * no visible activity — *whether or not it targets API 37*, so this is live on the device this project
 * develops against. The rules are read from the platform documentation rather than recalled, and the failure
 * mode is why they matter so much here: playback is suppressed **with no exception and no failure message
 * from any API**, and an audio focus request returns a failure code instead of throwing. An application that
 * is wrong about this does not look broken; it looks like one whose audio works intermittently.
 *
 * The platform's own guidance is that an app doing background audio should use Media3's `MediaSessionService`,
 * and that apps which do are "not likely to be impacted by the background hardening due to the library
 * assisting in managing the playback lifecycle". So this is that class, and not a hand-rolled foreground
 * service with an `AudioTrack` — the hand-rolled version is strictly more work and is where all the warnings
 * are aimed.
 *
 * The manifest declares `foregroundServiceType="mediaPlayback"` with the matching permissions, and the
 * service is started **while the application is visible** — which is what grants while-in-use capability, and
 * is also the honest description of what happened: the listener pressed Play in front of the app.
 *
 * ## The one deliberate divergence from the library's template
 *
 * Media3's documentation shows `onTaskRemoved` calling `pauseAllPlayersAndStopSelf()`. **That is not done
 * here**, and the reasoning is a product decision rather than a preference: clearing the task from recents is
 * a habitual gesture people perform for unrelated reasons, and for a listening application it would silently
 * stop the reading. The UI contract's rule is that "Back changes visual navigation, not playback state", and
 * dismissing the task is the largest possible instance of that. Leaving `onTaskRemoved` alone is therefore
 * the *implementation* of the rule, and it is stated here because an implementer following the documentation's
 * example would reasonably get this wrong.
 *
 * Nothing resumes playback on its own after a process death or a reboot. Media3 offers playback resumption;
 * the Active Queue & Reader Session spec forbids it — "launch never begins playback" — and a book that starts
 * reading itself aloud is not a convenience.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var player: ReaderSessionPlayer? = null

    /**
     * Built here rather than in the Application, because the media session's lifetime is the *service's*: it
     * exists while playback is active and goes away when the service stops.
     *
     * The session it renders is the opposite, and that asymmetry is the point — a process-scoped session with
     * a service-scoped view of it. A listener who returns to the app after the service has stopped finds the
     * session exactly where it was, and pressing Play starts a new service over the same session.
     */
    override fun onCreate() {
        super.onCreate()

        val host = (application as ReaderApplication).host
        val sessionPlayer = ReaderSessionPlayer(host, mainLooper)

        player = sessionPlayer
        session = MediaSession.Builder(this, sessionPlayer).build()
    }

    /**
     * Gives clients — the system's media controls, the lock screen, another application — the one session.
     *
     * A single session rather than one per controller, because there is one listening session: two
     * controllers observing two players over one `ReaderSession` would be the "second source of state" the
     * architecture forbids, arriving through the platform instead of through our own code.
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Intentionally not overridden — see the class comment.
     *
     * Written out rather than merely omitted, because the library's documentation recommends the opposite and
     * a silent absence would read as an oversight.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Deliberately empty: dismissing the task does not end the reading.
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Releases the *view* of the session, never the session.
     *
     * `ReaderSessionPlayer.handleRelease` cancels its subscription and stops there, so a stopped service
     * leaves the session untouched and resumable. `onDestroy` on a service is not a shutdown point for
     * anything with a longer lifetime, which is the same rule the ViewModel correction applied one layer up
     * (`docs/decisions.md` §23.16).
     */
    override fun onDestroy() {
        session?.run {
            player?.release()
            release()
        }
        session = null
        player = null

        super.onDestroy()
    }
}
