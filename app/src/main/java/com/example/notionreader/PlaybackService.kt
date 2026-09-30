package com.example.notionreader

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
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

    companion object {
        /**
         * Starts the foreground service, which is what allows playback to continue once the screen is off.
         *
         * `startForegroundService` rather than `startService`, because the service must call
         * `startForeground` promptly — the platform kills a service that fails to within about five seconds.
         * `MediaSessionService` does that itself once it has a session with something to play, which it has
         * by the time this is called: the trigger is playback actually having begun.
         *
         * **This is called only while the application is visible**, and that is a requirement rather than an
         * incidental detail. Android 17 grants while-in-use capability to a foreground service started in
         * response to a user-initiated operation or while the app is visible; a service of this type started
         * from the background would be refused the audio APIs, silently. The listener pressed Play in front of
         * the app, which is exactly the interaction the platform is asking about.
         *
         * Starting an already-running service is a no-op rather than an error, so the pause-then-play cycle
         * calls this safely every time.
         */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
        }
    }

    private var session: MediaSession? = null
    private var player: ReaderSessionPlayer? = null

    /**
     * Pauses playback when headphones are disconnected.
     *
     * The Background Playback spec: "Becoming noisy stops the reader silently, and that is the one place this
     * specification accepts silence. The listener's headphones have been unplugged; the alternative to silence
     * is to speak through the loudspeaker, which broadcasts reading to everyone in the room."
     */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                val host = (application as? ReaderApplication)?.host
                host?.app?.session?.pause()
            }
        }
    }

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
        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val sessionInstance = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
        session = sessionInstance
        addSession(sessionInstance)

        val noisyFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(noisyReceiver, noisyFilter)
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
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: Exception) {
        }

        session?.run {
            player.release()
            removeSession(this)
            release()
        }
        session = null
        player = null

        super.onDestroy()
    }
}
