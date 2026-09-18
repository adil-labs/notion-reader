package com.example.notionreader.domain.queue

import com.example.notionreader.domain.discovery.BrowseResults
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderLoadException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * One Note inside a queue.
 *
 * This is **identity plus a label, not content**. A queue that spanned many Notes cannot hold every
 * Note's document: `Play all` over a Browse result set may name hundreds of pages, and each one is
 * several HTTP requests (the block children, then nested children). Holding them all upfront would mean
 * fetching content the user may never reach, and would make starting playback wait on the whole set.
 *
 * [title] is carried anyway, and deliberately. The document is loaded lazily, but the title is needed
 * *before* that happens: the Player shows "Note 3 of 8", and the between-Notes announcement has to name
 * the Note that is starting — in both cases while its document is still being fetched. It is cheap
 * because it arrives with the page response that produced this identity.
 *
 * **Known limitation:** identity is `pageId` + `title`, which is enough to read a Note and not enough to
 * describe it. The integration spec's `ReaderLibraryEntry` — kind, area, topics, dates, status — is the
 * eventual discovery identity, and the workspace schema facts recorded in `docs/decisions.md` §5 mean
 * several of its fields have nothing behind them yet. That type will **widen** this one when the Content
 * Metadata & Workspace Schema Adapters spec exists; it will not replace it, because "which Note" and
 * "what else is known about it" are different questions.
 *
 * `@Serializable` because this is the pair of fields a stored session persists per Note. The annotation
 * is on the domain type rather than on a separate transport type deliberately: a second type would be a
 * second definition of "which Note", and the two could drift.
 */
@Serializable
data class QueuedNote(
    val pageId: String,
    val title: String
) {
    /** The identity of the queued document, for asserting that a snapshot froze rather than aliased. */
    companion object {
        fun of(document: ReaderDocument) = QueuedNote(document.pageId, document.title)
    }
}

/**
 * Why a queue exists — descriptive only.
 *
 * The product contract lists exactly these four origins and adds the constraint that makes them safe to
 * store: "Origin information is descriptive. It must never cause the Active Queue to re-evaluate or
 * synchronize with its source while playback is active or resumable."
 *
 * That constraint is why this is a value and not a reference. A `SmartQueue` origin holds the queue's
 * *name*, not its query: a query would invite re-evaluation, and re-evaluating is what the contract
 * spends four invariants forbidding.
 *
 * `@Serializable`, with an explicit `@SerialName` per variant. The wire names are not the class names, so
 * renaming a class cannot silently change what an existing stored record means; if a wire name ever has
 * to change, that is a `SESSION_SCHEMA_VERSION` change and old records are discarded rather than
 * reinterpreted. An origin this build does not recognise therefore fails to decode, which the store
 * treats as an unusable record — acceptable because origin is descriptive, so nothing restorable is lost
 * with it.
 */
@Serializable
sealed interface QueueOrigin {

    /** `Play` on one Note. A queue of one. */
    @Serializable
    @SerialName("single_note")
    data object SingleNote : QueueOrigin

    /** `Play all` on a Browse result set. */
    @Serializable
    @SerialName("browse_results")
    data object BrowseResults : QueueOrigin

    @Serializable
    @SerialName("saved")
    data class Saved(val name: String) : QueueOrigin

    @Serializable
    @SerialName("smart")
    data class Smart(val name: String) : QueueOrigin
}

/**
 * An ordered, frozen list of Notes being listened to, with its origin.
 *
 * The product contract calls this "an ephemeral, immutable-membership playback snapshot associated with
 * one listening session", and the immutability is the whole design: `items` is a `List` that nothing in
 * this file can add to, remove from, or reorder after construction. "Once created, its membership and
 * order remain fixed for that listening session."
 *
 * The rule it exists to enforce is about *sources*, not about the queue: edits to a Saved Queue, changes
 * to a Smart Queue's results, and re-sorting a Browse result set must all leave a queue that was already
 * created from them untouched. Since a queue can be built only from a snapshot of the source and holds
 * no reference back to it, that is not a discipline anyone has to maintain — there is nothing to
 * synchronize with.
 *
 * Duplicates are allowed and not deduplicated. Two identical Notes in one queue is a legitimate thing
 * for a user to have asked for (a Smart Queue whose filters match a page twice is not, but that is the
 * query's problem); silently collapsing them would change the membership the user snapshotted, which is
 * exactly what the contract forbids. `Add to queue` deduplicates, and that is a different operation with
 * a different rule.
 */
data class ActiveQueue private constructor(
    val items: List<QueuedNote>,
    val origin: QueueOrigin
) {
    val size: Int get() = items.size

    val isEmpty: Boolean get() = items.isEmpty()

    fun itemAt(index: Int): QueuedNote? = items.getOrNull(index)

    companion object {

        /**
         * Snapshots [notes] as a queue.
         *
         * **The copy is the point.** The primary constructor is private because passing a caller's list
         * straight in — which the first version of this class did — makes the "snapshot" a live view of
         * that list: `Play all` over a Browse result set would then re-sort itself whenever the results
         * were re-sorted, which is precisely what the contract forbids and what `ActiveQueueTest`
         * caught. A queue that shares storage with its source is not frozen, however immutable it looks.
         */
        fun snapshotOf(notes: List<QueuedNote>, origin: QueueOrigin) =
            ActiveQueue(items = notes.toList(), origin = origin)

        /** The queue `Play` creates on a single Note: "an Active Queue containing one item". */
        fun of(document: ReaderDocument, origin: QueueOrigin = QueueOrigin.SingleNote) =
            ActiveQueue(listOf(QueuedNote.of(document)), origin)
    }
}

/**
 * Where a queue session is, and whether it can proceed.
 *
 * Kept separate from [ReaderState], which describes the *document* being read. A Player needs both:
 * "Paragraph 2 of 349" comes from the document, "Note 3 of 8" comes from here, and neither can be
 * derived from the other — a document does not know it is the third of eight, and a queue does not know
 * which paragraph is being spoken.
 *
 * ## The states, and why `awaiting` is a state rather than a flag
 *
 * Because content is loaded lazily, there is a real interval where the queue knows which Note is next
 * and does not have it yet. That interval has to be visible: something has to be on screen while the
 * next Note loads, and "the previous Note is still displayed and the controls still work" is the wrong
 * thing to show, since the position and section lines would describe the Note that just ended.
 *
 * | State | Meaning | Player shows |
 * |---|---|---|
 * | `awaiting` | the current Note's document is being fetched | the upcoming title, and that it is loading |
 * | `failure != null` | that fetch failed; the queue is stalled | the failure and Retry |
 * | neither | the current Note is loaded and playing or paused | the document |
 * | `completed` | the last Note finished | end of queue |
 */
data class QueueState(
    val queue: ActiveQueue? = null,
    val index: Int = 0,
    val awaiting: Boolean = false,
    val completed: Boolean = false,
    val failure: ReaderLoadException? = null,
    /**
     * Which request for a Note this state represents.
     *
     * ## Why this is published rather than kept private
     *
     * The session increments a token on every transition that issues a request, so that a fetch which
     * completes after the session moved on can be told apart from one that is still current — and so that
     * the *same page* can be asked for twice in a row (a retry, or a queue whose first two Notes are the
     * same page) and still count as two requests.
     *
     * It used to be private, and that made it invisible to anything observing the queue. An observer that
     * asked "is a Note awaited?" saw `true` both before and after a superseding transition, so a request
     * that replaced an in-flight one produced **no observable change at all** and nothing ever fetched it —
     * while the arrival of the superseded one was correctly refused by the identity guard. The queue then
     * sat awaiting a Note that no request existed for. Publishing the token is what lets "which Note do I
     * need?" be an observable question rather than a hidden one.
     *
     * It is part of the *state* rather than a separate channel on purpose: a token that lived anywhere else
     * would be a second thing to keep in step with `index` and `awaiting`, which is exactly the class of
     * bug it exists to prevent.
     */
    val requestToken: Int = 0
) {

    val size: Int get() = queue?.size ?: 0

    /**
     * Every Note in the queue, in frozen order — the whole snapshot, for a surface that renders it.
     *
     * Empty rather than null with no queue, so a caller cannot render "no queue" as a crash and does not
     * have to distinguish the two before drawing a list. The distinction is still available where it
     * matters: [queue] is null when there is no session at all.
     */
    val items: List<QueuedNote> get() = queue?.items ?: emptyList()

    val currentItem: QueuedNote? get() = queue?.itemAt(index)

    /** The Note after the current one, for describing what comes next. */
    val nextItem: QueuedNote? get() = queue?.itemAt(index + 1)

    val hasNext: Boolean get() = index < size - 1

    val hasPrevious: Boolean get() = index > 0

    val isStalled: Boolean get() = failure != null


    /**
     * "Note 3 of 8", or null for a queue of one.
     *
     * Null rather than "Note 1 of 1" because a single-Note queue is how every `Play` and every opening
     * of the Note screen is expressed, and saying "Note 1 of 1" on the common path would be noise. The
     * UI contract asks for "queue position such as Note 3 of 8" precisely because that is the case where
     * the information is new.
     */
    val positionDescription: String?
        get() = if (size > 1) "Note ${index + 1} of $size" else null
}
