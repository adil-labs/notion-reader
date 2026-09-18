package com.example.notionreader

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.example.notionreader.player.PlayerCommand
import com.example.notionreader.player.playerCommandAvailability
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job

/**
 * The listening session, exposed to Android's media surfaces.
 *
 * ## What this is, and what it deliberately is not
 *
 * It is a **view** of `ReaderHost`'s session, not a second one. One direction only:
 *
 * ```plain
 * ReaderSession ──project──▶ SimpleBasePlayer.State   (what the lock screen renders)
 *       ▲                              │
 *       └──── handleSeek / handleSetPlayWhenReady ─────┘   (what the lock screen sends)
 * ```
 *
 * Nothing reads the playlist back into the session, and this class holds no playback state of its own — it
 * derives everything from the session on every call to [getState]. That is the specification's "one session
 * object, two front ends. Neither is a second source of state", and it is why a media controller cannot
 * desynchronise from the Player: both read the same two flows.
 *
 * `SimpleBasePlayer` is the documented base for "a custom player that you want to connect to a
 * `MediaSession`", which is exactly this: the playback engine is TTS, so there is nothing for ExoPlayer to
 * do and no media to decode.
 *
 * ## The library's default handlers throw, and two consequences follow
 *
 * Every handler this class does not override throws `IllegalStateException("Missing implementation to handle
 * COMMAND_X")`. That was read out of the library rather than assumed, and it is a good default for a
 * product whose failure mode is silence:
 *
 * - **Commands we refuse are refused loudly.** `handleSetMediaItems`, `handleAddMediaItems` and the rest are
 *   left un-overridden, so a controller that tries to add, remove or reorder a Note gets an exception rather
 *   than a quiet no-op. The Product & Architecture Contract's "Active Queues are played, not edited" is
 *   therefore enforced a fourth time, here, by doing nothing at all. `docs/decisions.md` §22.5's refusal
 *   needs no code from us, which is the strongest form it could take.
 * - **Commands we support must be overridden or they crash.** `handleSetPlayWhenReady`, `handleSeek`,
 *   `handleStop`, `handlePrepare` and `handleRelease` are all implemented below, and each exists for a
 *   reason given at its definition.
 *
 * ## What is not projected, on purpose
 *
 * **No duration and no seekable position.** Our content has no millisecond timeline: a Note is a sequence of
 * speakable parts, not a number of seconds. `setDurationUs(C.TIME_UNSET)` and `setIsSeekable(false)` say so
 * plainly, so the platform draws no progress bar rather than a bar that lies about how much is left. The
 * consequence is real and accepted: a lock screen cannot show "half way through", because being half way
 * through is not a fact this product has.
 */
class ReaderSessionPlayer(
    private val host: ReaderHost,
    looper: Looper
) : SimpleBasePlayer(looper) {

    /**
     * Re-derives the state whenever the session changes.
     *
     * Subscribing rather than being pushed to is what keeps the two front ends from drifting: this class
     * never computes a state of its own and never caches one, so there is no stale copy to go wrong.
     */
    private val sessionObserver: Job = host.observeSession { invalidateState() }

    override fun getState(): State {
        val queue = host.app.session.queueState.value
        val reading = host.app.session.state.value
        val availability = playerCommandAvailability(reading, queue)
        val items = projectedQueueItems(queue, reading)

        // Media3 requires the index to be valid for the playlist, and an empty playlist admits only 0. The
        // queue is empty only before anything has been played, which is a real state: the app launches on
        // Home with a demo document loaded and no session.
        val index = if (items.isEmpty()) 0 else queue.index.coerceIn(0, items.size - 1)

        return State.Builder()
            // The single published availability value, so a lock screen cannot offer what the Player has
            // disabled and the Player cannot disable what the lock screen still offers.
            .setAvailableCommands(mediaCommandsFor(availability))
            .setPlaylist(items.map { it.toMediaItemData() })
            .setCurrentMediaItemIndex(index)
            .setPlayWhenReady(reading.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackStateFor(queue))
            .setIsLoading(isLoadingFor(queue))
            // Honest rather than convenient: there is no position inside a Note in any unit the platform
            // understands, so the position is zero and the item is not seekable.
            .setContentPositionMs(0L)
            .build()
    }

    /**
     * Play and pause from a transport.
     *
     * `BasePlayer.play()` and `pause()` both funnel here through `setPlayWhenReady`, which was read out of
     * the bytecode — so this one method is the whole of transport play/pause.
     *
     * It dispatches through [com.example.notionreader.application.ReaderApp.invoke], like every other
     * transport, so the precondition is re-checked at dispatch and an unavailable command changes nothing.
     */
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        host.app.invoke(if (playWhenReady) PlayerCommand.Play else PlayerCommand.Pause)
        return Futures.immediateVoidFuture()
    }

    /**
     * Every seek a transport can send, dispatched **on the command rather than on the position**.
     *
     * ## Why the command is the only trustworthy part of the arguments
     *
     * `handleSeek(mediaItemIndex, positionMs, seekCommand)` offers three things and only two are usable here:
     *
     * - **`seekCommand`** says which command the platform is carrying, and it survives the trip intact.
     * - **`mediaItemIndex`** is meaningful for the item-level commands, because a queue entry *is* a media
     *   item — that is what makes next/previous/jump exact.
     * - **`positionMs`** is **meaningless**, and acting on it would be the bug this class exists to avoid.
     *   `seekForward()` compiles to `seekToOffset(getSeekForwardIncrement(), …)`, a millisecond distance the
     *   *transport* chose, and a Note has no millisecond timeline.
     *
     * So anything that is not an item-level command is ignored. Those commands are never advertised — see
     * `mediaCommandFor` — and the only way one arrives is a client sending it anyway, in which case doing
     * nothing is the honest answer.
     *
     * The `else` branch is deliberately not a fallback to "move one part": that would be a transport
     * silently meaning something other than what was asked.
     */
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (commandForMediaCommand(seekCommand)) {
            PlayerCommand.NextNote -> host.app.invoke(PlayerCommand.NextNote)
            PlayerCommand.PreviousNote -> host.app.invoke(PlayerCommand.PreviousNote)
            PlayerCommand.RestartNote -> host.app.invoke(PlayerCommand.RestartNote)
            // The only command whose meaning needs the index: "jump somewhere" with no destination is not a
            // command, which is why it is dispatched with one here and refused without one elsewhere.
            PlayerCommand.JumpToNote -> host.app.invoke(PlayerCommand.JumpToNote, mediaItemIndex)
            else -> Unit
        }
        return Futures.immediateVoidFuture()
    }

    /**
     * `stop` from a transport, treated as pause.
     *
     * The Core Player & TTS Playback spec defines no `Stop` command — "a transport that can only express a
     * stop must map it to `Pause`" — because stopping would imply discarding a position, and discarding a
     * position is not a capability this product has. `COMMAND_STOP` is never advertised, so this is reachable
     * only from a client that sends it anyway; mapping it to pause is the nearest true thing, and it also
     * stops the library's default (an exception) from turning a stray stop into a crash.
     */
    override fun handleStop(): ListenableFuture<*> {
        host.app.invoke(PlayerCommand.Pause)
        return Futures.immediateVoidFuture()
    }

    /**
     * Nothing to prepare, and that is a fact rather than a shrug.
     *
     * There is no buffering to start and no source to open: a Note's content is fetched by the queue loader
     * and the parts are already in memory once the document is set. Answering immediately is the truthful
     * response, and it keeps `COMMAND_PREPARE` — which is never advertised — from throwing if a controller
     * sends it.
     */
    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    /**
     * Releases **this view**, and explicitly not the session.
     *
     * This is the one override whose absence would be a genuine defect rather than a crash: the library's
     * default throws, and the service calls `release()` when it stops. But the more important point is what
     * it deliberately does *not* do — it does not call `host.shutdown()`.
     *
     * The player is a rendering of the session, and the session outlives every rendering of it: it is owned
     * by the process, and the media session comes and goes with the foreground service. A `release()` that
     * shut the session down would end the reading when the service stopped, which is precisely the ownership
     * mistake `docs/decisions.md` §23.16 corrected one layer up.
     */
    override fun handleRelease(): ListenableFuture<*> {
        sessionObserver.cancel()
        return Futures.immediateVoidFuture()
    }

    /** The queue entry as Media3's item data — the only mechanical step in this class. */
    private fun ProjectedQueueItem.toMediaItemData(): MediaItemData =
        MediaItemData.Builder(uid)
            .setMediaItem(
                MediaItem.Builder()
                    // The page id is the media id, because it is the Note's stable identity — the uid above
                    // is the *entry's* identity, and the two are different things when a queue repeats a
                    // Note.
                    .setMediaId(pageId)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(title)
                            .setSubtitle(subtitle)
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .build()
                    )
                    .build()
            )
            .setDurationUs(C.TIME_UNSET)
            .setIsSeekable(false)
            .build()
}
