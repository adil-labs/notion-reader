package com.example.notionreader

import androidx.media3.common.Player
import com.example.notionreader.domain.queue.QueueState
import com.example.notionreader.player.ReaderState

/**
 * One entry of the queue, as a media session sees it.
 *
 * A plain type rather than Media3's `MediaItemData` so that everything worth getting wrong here is
 * testable on the JVM. `MediaItemData.Builder` is an Android-side container — the same distinction that
 * `Player.COMMAND_*` being compile-time ints while `Player.Commands.Builder` is not turned out to matter in
 * `PlayerMediaCommands.kt` — so the *shape* of the projection is decided here and only constructed there.
 *
 * It mirrors what the Player screen and the mini-player already show, because the three surfaces describe
 * one session and must not disagree: the Note's title, and beneath it the section when there is one.
 */
data class ProjectedQueueItem(
    /**
     * Identifies this **entry**, not this Note.
     *
     * ## Why the page id is not enough, which is the whole reason this type exists
     *
     * Media3 uses the uid to identify an item across state updates. `pageId` is the obvious choice and it is
     * wrong, because `ActiveQueue` deliberately permits duplicates:
     *
     * > Duplicates are allowed and not deduplicated. Two identical Notes in one queue is a legitimate thing
     * > for a user to have asked for.
     *
     * Two entries sharing a uid would be indistinguishable to Media3 when the current index moves between
     * them, so the position is part of the identity. That is the same distinction `QueuedNote` already
     * draws — it holds identity *and* position separately — applied one layer down, where the consumer is a
     * platform library rather than our own code.
     */
    val uid: String,
    /** The Note's stable identity, which is what a page id is for. */
    val pageId: String,
    val title: String,
    /** The section line, or the queue position — the same choice the Player makes. */
    val subtitle: String?
)

/**
 * The queue as a media session renders it, in order.
 *
 * The mapping is deliberately one-directional: **the Active Queue is the source and the playlist is a
 * projection of it.** Nothing reads a playlist back into the session, so a media controller cannot become a
 * second source of truth for what is being played — which is the invariant the whole two-front-ends design
 * rests on.
 *
 * Every Note gets a subtitle, and which subtitle depends on what is actually known:
 *
 * - **The current Note** gets the section line, because that is what the Player shows and what the session
 *   actually knows — the document is loaded, so its outline exists.
 * - **Every other Note** gets its queue position, because a section is not knowable for a document that has
 *   not been fetched. Note that this is the *opposite* of a guess: the spec's lazy loading means the section
 *   of Note 7 genuinely does not exist yet, and inventing one would be the "label the state cannot support"
 *   defect this project has fixed twice (`docs/decisions.md` §22.4 and §23.8's P2).
 */
fun projectedQueueItems(queue: QueueState, state: ReaderState): List<ProjectedQueueItem> {
    val items = queue.items
    if (items.isEmpty()) return emptyList()

    return items.mapIndexed { index, note ->
        ProjectedQueueItem(
            uid = "$index:${note.pageId}",
            pageId = note.pageId,
            title = note.title,
            subtitle = if (index == queue.index) currentSubtitle(queue, state) else positionSubtitle(queue, index)
        )
    }
}

/**
 * The current entry's subtitle: the section when there is one, otherwise the queue position.
 *
 * Built the same way the Player builds its section line, from the same two facts, so the lock screen and the
 * Player cannot describe the current Note differently.
 */
private fun currentSubtitle(queue: QueueState, state: ReaderState): String? {
    val sectionLine = listOfNotNull(state.sectionPositionDescription, state.currentSection?.title)
        .joinToString(" · ")

    return sectionLine.ifEmpty { positionSubtitle(queue, queue.index) }
}

/** "Note 3 of 8", or null for a queue of one — the same rule the Player uses. */
private fun positionSubtitle(queue: QueueState, index: Int): String? =
    if (queue.size > 1) "Note ${index + 1} of ${queue.size}" else null

/**
 * The playback state a media session should report.
 *
 * ## Why the queue decides this rather than the engine
 *
 * `Player.STATE_*` is about *whether content is ready to play*, and in this application that is a fact about
 * the queue's progress rather than about speech:
 *
 * | Queue | State | Why |
 * |---|---|---|
 * | no queue | `IDLE` | nothing has been played; no session to describe |
 * | a Note is being fetched | `BUFFERING` | the ordinary meaning of the word |
 * | the queue finished | `ENDED` | and the platform stops offering play, which is correct — a completed session is replayed from history, not resumed |
 * | a Note failed to load | `READY` | **deliberate**: the session is not retrying and is not going to on its own — the Player shows Retry. `BUFFERING` would claim progress that is not happening |
 * | otherwise | `READY` | a document is loaded |
 *
 * The stalled row is the one worth stating, because `BUFFERING` is the tempting answer and it would be a
 * false claim: a lock screen spinning forever on a Note that needs the listener to press something is worse
 * than one that says nothing about progress.
 */
fun playbackStateFor(queue: QueueState): Int = when {
    queue.queue == null -> Player.STATE_IDLE
    queue.completed -> Player.STATE_ENDED
    queue.awaiting -> Player.STATE_BUFFERING
    else -> Player.STATE_READY
}

/**
 * Whether the platform should show this as loading.
 *
 * True for a stall as well as a fetch, which is the honest reading of "we are waiting for something" — the
 * difference between the two is shown by the Player, where Retry exists, and not by a spinner.
 */
fun isLoadingFor(queue: QueueState): Boolean = queue.awaiting || queue.isStalled
