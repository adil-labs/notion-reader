package com.example.notionreader.application

import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.domain.queue.SavedQueue

/**
 * A Saved Queue, as the Saved tab, Home's *Your queues* and Saved Queue Details all describe it.
 *
 * ## Why this is a read model rather than the queue itself
 *
 * For the same reason [ContinueListeningCard] is: the screens stay display-only. A surface given a
 * [SavedQueue] is being handed the store's record, which is a thing that can be written; a surface given a
 * card is handed a description. It also puts the decisions the screens must not each make in one place —
 * the ordering, and how a member count is spoken — both of which are testable with no device.
 *
 * ## Why it carries the members rather than a count
 *
 * Because a count is `members.size`, and the Queue Creation & Queue Management spec's own rule is that the
 * stored record holds **no member count**: "it is `notes.size`, and a stored copy is a second thing to keep
 * in step". The same argument applies one layer up, so a card holding both a count and a member list would
 * be that same second thing — except this one could disagree at runtime rather than only on disk.
 *
 * It carries them as [QueuedNote] rather than as formatted strings because Saved Queue Details lists the
 * members and each row opens its Note, so the page identity is needed and not just the title. That is the
 * difference from [HistoryEntry], which is a summary and formats its membership to a line.
 *
 * ## Ordering, and why it is here rather than in the store
 *
 * The contract requires the Saved tab to list queues "most recently edited first". The store deliberately
 * returns them "in no particular order", so that the ordering is one implementation regardless of which
 * storage backend produced the bytes — and so that it can be tested without a store, which is what
 * [toCards] is tested for.
 *
 * ## Why [lastEditedAt] is carried as an instant and not as a string
 *
 * The same argument [ContinueListeningCard] records: the contract requires relative time and forbids a raw
 * one, so the *formatting* belongs to `ui.describeLastActivity` and the *fact* belongs here. Formatting at
 * the point the card is built would make it stale the moment it was built.
 */
data class SavedQueueCard(
    /** The queue's stable identity. Never displayed; it is what a screen refers back to. */
    val queueId: String,
    val name: String,
    /** The members, in the listener's order. The order is theirs; nothing here reorders them. */
    val members: List<QueuedNote>,
    /** Epoch millis of the last edit. Ordering, and the relative time if a surface shows one. */
    val lastEditedAt: Long
) {

    val memberCount: Int get() = members.size

    /**
     * The member count as a listener reads it: `11 notes`, and `1 note` for one.
     *
     * Singular-aware on purpose, because the contract's examples are all plural and `1 notes` is the kind
     * of thing that is only ever written by an implementation that never handled the case. Zero reads as
     * `0 notes` rather than as a word like "Empty": it is the same statement as every other count, and
     * inventing vocabulary for one value would make the line mean something different depending on which
     * queue it describes.
     */
    val memberDescription: String
        get() = if (memberCount == 1) "1 note" else "$memberCount notes"

    /**
     * Whether `Play` is offered for this queue.
     *
     * **Play on an empty queue is not offered** — the control is disabled rather than inert, because a Play
     * that can do nothing is a control a listener cannot use, and "this queue is empty" is the information
     * they actually need. The rule lives here so that the surface and the operation cannot disagree about
     * it: the screen disables the control on this, and `ReaderApp.playSavedQueue` refuses on the same fact.
     */
    val isPlayable: Boolean get() = members.isNotEmpty()
}

/** One queue as a card. The single mapping, so a single queue and a list of them cannot describe one differently. */
fun SavedQueue.toCard(): SavedQueueCard = SavedQueueCard(
    queueId = id,
    name = name,
    members = notes,
    lastEditedAt = lastEditedAt
)

/**
 * Every queue as a card, most recently edited first.
 *
 * The sort is by `lastEditedAt` descending and is deliberately *stable*: two queues edited in the same
 * millisecond keep the order the store gave them, rather than swapping on every recomposition. That
 * matters because a surface that reordered two equal-timestamped rows on redraw would look like it had
 * lost the listener's place.
 *
 * A queue with no name is impossible — [SavedQueue.isValid] rejects one — so the name is used as it
 * stands rather than being guarded here a second time.
 */
fun List<SavedQueue>.toCards(): List<SavedQueueCard> =
    sortedByDescending { it.lastEditedAt }.map { it.toCard() }
