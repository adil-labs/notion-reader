package com.example.notionreader.domain.queue

/**
 * One of the four accessible reorder operations.
 *
 * ## Defined by its postcondition, not by index arithmetic
 *
 * The Queue Creation & Queue Management spec defines each move by where the member *ends up* rather than by
 * how far a cursor travels, and that is what this enum encodes:
 *
 * | Move | Postcondition |
 * |---|---|
 * | [Up] | the member is immediately above where it was; at the top, nothing changes |
 * | [Down] | the member is immediately below where it was; at the bottom, nothing changes |
 * | [Top] | the member is first; if already first, nothing changes |
 * | [Bottom] | the member is last; if already last, nothing changes |
 *
 * The boundary clauses fall out of the arithmetic rather than being special-cased: a target index is clamped
 * into the range the *shorter* list actually has, and inserting a removed element back at its own position
 * produces the list it came from. That is deliberate. It makes "a move at a boundary is a no-op" a
 * **consequence of the definition** rather than a second rule maintained beside it — and therefore a rule that
 * cannot be forgotten when a fifth move is added.
 */
enum class QueueMove {
    Up,
    Down,
    Top,
    Bottom;

    /**
     * The member order this move produces from [notes] and the [index] of the member being moved.
     *
     * Returns [notes] itself when [index] is not a member's index, so a caller cannot obtain a "moved" list
     * for an absent member — the postcondition of a move on nothing is that nothing moved.
     */
    fun applyTo(notes: List<QueuedNote>, index: Int): List<QueuedNote> {
        if (index !in notes.indices) return notes
        val remaining = notes.toMutableList()
        val moved = remaining.removeAt(index)
        val target = when (this) {
            Up -> index - 1
            Down -> index + 1
            Top -> 0
            Bottom -> remaining.size
        }
        remaining.add(target.coerceIn(0, remaining.size), moved)
        return remaining
    }
}

/**
 * One operation on the set of Saved Queues.
 *
 * ## Why the operations are values rather than six methods
 *
 * Because the rules they share are the ones most easily broken, and a value lets them be stated **once**:
 *
 * - **an operation that changes nothing performs no write** — see [applyTo], where this is a single
 *   comparison of the whole result against the whole input rather than a condition inside each operation;
 * - **a rejected operation performs no write** — the same comparison, since a rejection's result *is* its
 *   input;
 * - **an operation touches exactly one queue record**, and only that record's `lastEditedAt` moves;
 * - **an edit preserves every queue it did not touch**, because the result is derived from the input list
 *   rather than assembled from the operation alone.
 *
 * Six methods could each get one of those right and one of them wrong, and the wrong one would be invisible:
 * the queue still lists, it just is not what the listener asked for. As one entry point, a seventh operation
 * added later inherits all four rules without being written to satisfy them.
 */
sealed interface QueueEdit {

    /**
     * Creates an empty queue from [draft].
     *
     * [id] is supplied by the caller rather than generated here, which follows [SavedQueueDraft.toQueue]'s
     * existing decision: a value that embedded a clock or an identity source could not be tested. **The id must
     * therefore be fresh** — this operation does not check, because a collision is not settled by any governing
     * document and inventing a rejection for it would force a Create screen to explain an identity to a
     * listener. A `UUID` source cannot collide, so the assumption is safe where it is made.
     */
    data class Create(val draft: SavedQueueDraft, val id: String) : QueueEdit

    /** Changes [id]'s name, and nothing else. */
    data class Rename(val id: String, val name: String) : QueueEdit

    /** Appends [notes] to the end of [id], skipping any that are already members. */
    data class Add(val id: String, val notes: List<QueuedNote>) : QueueEdit

    /** Removes the member of [id] with this Note identity. */
    data class Remove(val id: String, val pageId: String) : QueueEdit

    /** Moves one member of [id] one step, or to an end. */
    data class Move(val id: String, val pageId: String, val move: QueueMove) : QueueEdit

    /**
     * Deletes [id] and its members.
     *
     * The Notes themselves are untouched: a queue holds identities, and deleting a playlist does not delete
     * the songs. Nor is any listening session affected — its snapshot is its own, and origin "must never cause
     * the Active Queue to re-evaluate or synchronize with its source".
     */
    data class Delete(val id: String) : QueueEdit
}

/**
 * Why an operation did not happen.
 *
 * Two reasons, and both are ones the governing documents create rather than ones this model invented: a name
 * is required, and an operation needs something to operate on. A third reason a surface could not explain to a
 * listener would be worse than no check at all.
 */
enum class QueueRejection {

    /** The named queue is not among the stored queues. Also how an already-deleted queue's delete is answered. */
    NoSuchQueue,

    /** A queue's name is required, and blankness is judged after trimming. */
    BlankName
}

/**
 * What an operation did, in the terms a surface has to report it in.
 *
 * ## The effect describes the postcondition, not the delta
 *
 * [Added] reports both how many members were added and how many were already present, and reports them even
 * when nothing was added — because **"adding nothing is a success, not an error"**. This is the case the spec
 * spells out: `Add all to queue` over a result set that overlaps the destination is the *common* case, and a
 * listener who adds twelve things and is told nothing cannot tell "all twelve were already there" from "the app
 * failed". The same posture is what makes [MemberRemoved] and [MemberMoved] carry a null member rather than
 * having a separate "nothing happened" case: removing a Note that is not a member is a success, because the
 * postcondition the caller wanted already holds.
 *
 * ## Why an effect is a separate value from the outcome
 *
 * Because "nothing changed" and "nothing to say" are different answers, and the pair that distinguishes them is
 * real: an all-duplicates [Add] changes nothing and has something to say. Flattening the two into one field
 * would make one of those cases unrepresentable.
 */
sealed interface QueueEffect {

    data class Created(val queue: SavedQueue) : QueueEffect

    /** A queue is now named this — whether or not that changed the stored name. */
    data class Renamed(val queue: SavedQueue) : QueueEffect

    /** [added] members were appended and [alreadyPresent] were skipped as members already. */
    data class Added(val queue: SavedQueue, val added: Int, val alreadyPresent: Int) : QueueEffect

    /** [member] was removed, or null when it was not a member — in which case nothing was removed. */
    data class MemberRemoved(val queue: SavedQueue, val member: QueuedNote?) : QueueEffect

    /**
     * A member now sits at [position], counting from 1 as it would be spoken, or 0 when [member] is null
     * because it is not a member of this queue. Whether the order actually changed is the outcome's
     * [QueueEditOutcome.changed], deliberately not repeated here.
     */
    data class MemberMoved(val queue: SavedQueue, val member: QueuedNote?, val position: Int) : QueueEffect

    /** [queue] is gone. Its Notes are not. */
    data class Deleted(val queue: SavedQueue) : QueueEffect

    data class Rejected(val reason: QueueRejection) : QueueEffect

    /**
     * The store could not be read, so **no operation was performed and nothing was written**.
     *
     * A distinct case rather than a [Rejected], because a storage failure is not a failure of the operation
     * the listener performed: the spec forbids reporting one as the other, so the surface shows no error and
     * simply leaves the screen as it was. It exists because a store that reports an unreadable file as "no
     * queues" — which the spec requires — must not then have that empty answer written back over the queues it
     * failed to read. See [SavedQueueStore.transact], which is where that is made impossible.
     */
    data object StoreUnavailable : QueueEffect
}

/**
 * The result of an operation: the queues as they now stand, whether that differs from what was there, and what
 * there is to report.
 *
 * ## `changed` is the write decision, and it is made once
 *
 * Every rule of the form "this performs no write" reduces to `changed == false`, and `changed` is computed in
 * exactly one place ([applyTo]) by comparing the operation's whole result against its whole input. A caller
 * with a store writes when this is true and does nothing when it is false; nothing else needs to know which
 * operations are no-ops.
 *
 * ## `queues` is authoritative only when the effect is not [QueueEffect.StoreUnavailable]
 *
 * It is the full list the store should hold. Under [QueueEffect.StoreUnavailable] it is **empty and carries no
 * information** — the store could not be read, so there is no list this call can honestly report — and a
 * surface that needs to display queues should read them through the store rather than from an outcome that
 * describes an operation which did not happen.
 */
data class QueueEditOutcome(
    val queues: List<SavedQueue>,
    val changed: Boolean,
    val effect: QueueEffect
)

/**
 * The queues an edit produces from [to], at time [now].
 *
 * A free function rather than a method on a model object, because the answer depends on **both** the edit and
 * the stored list — a model holding one would have to be kept in step with the other, which is the class of bug
 * `QueueState.requestToken` documents.
 *
 * Pure and synchronous: no store, no dispatcher, no clock of its own. `now` is a parameter so that a test can
 * read a timestamp rather than infer one.
 *
 * ## The one place a no-op is decided
 *
 * The candidate list is computed without touching any timestamp, and then compared with [to]. Only if it
 * differs is the edited record stamped. That ordering is what makes "renaming to the name it already has
 * performs no write" work without a special case: stamping first would change `lastEditedAt`, which would make
 * the comparison report a change and the write happen for nothing. It is also what makes a rejection and an
 * absent-member removal no-ops by construction rather than by inspection.
 */
fun QueueEdit.applyTo(to: List<SavedQueue>, now: Long): QueueEditOutcome {
    val candidate = candidateFor(to, now)
    if (candidate.queues == to) {
        return QueueEditOutcome(queues = to, changed = false, effect = candidate.effect)
    }
    val final = candidate.queues.stamping(targetId, now)
    return QueueEditOutcome(
        queues = final,
        changed = true,
        effect = candidate.effect.naming(final)
    )
}

/**
 * The same effect, with its queue taken from [final].
 *
 * ## Why this exists rather than being avoided
 *
 * The write decision has to be made on the candidate **before** the edit's timestamp is applied: stamping first
 * would make "rename to the name it already has" report a change on the strength of the timestamp alone and
 * write for nothing. The stamping therefore happens after the decision — which would otherwise leave every
 * effect naming a record the outcome no longer contains, handing one caller two versions of one queue that
 * differ only in a timestamp. `SavedQueueEditsTest` caught exactly that, as a failed equality assertion between
 * an effect's queue and the outcome's.
 *
 * So the re-pointing is not tidying: it makes "an effect and its outcome name the same record" true by
 * construction rather than by both being derived from the same expression and hoping they stay in step.
 */
private fun QueueEffect.naming(final: List<SavedQueue>): QueueEffect = when (this) {
    is QueueEffect.Created -> copy(queue = final.record(queue.id))
    is QueueEffect.Renamed -> copy(queue = final.record(queue.id))
    is QueueEffect.Added -> copy(queue = final.record(queue.id))
    is QueueEffect.MemberRemoved -> copy(queue = final.record(queue.id))
    is QueueEffect.MemberMoved -> copy(queue = final.record(queue.id))
    // The deleted queue is not in [final], and a rejection names no queue at all.
    is QueueEffect.Deleted -> this
    is QueueEffect.Rejected -> this
    QueueEffect.StoreUnavailable -> this
}

private fun List<SavedQueue>.record(id: String): SavedQueue = first { it.id == id }

/**
 * Another queue that already has this name, for the Create and Rename screens to warn with.
 *
 * ## Why this is a warning and not a rule
 *
 * Two queues may share a name: enforcing uniqueness would mean rejecting a name a listener chose, and the queue
 * *identity* already disambiguates — the same posture as a duplicate Note in a queue, which is permitted and not
 * hidden. So this returns a queue rather than a boolean, because the warning has to say *which* one.
 *
 * ## Why the comparison is case-insensitive
 *
 * `"Reading"` and `"reading"` are two different names that a listener will read as the same one, so the warning
 * covers both. The comparison is **only** for the warning: nothing about identity or membership depends on it,
 * which is why a rename that changes only the case is a real change that writes.
 *
 * [excludingId] is the queue being renamed, which must not warn about itself.
 */
fun nameWarning(queues: List<SavedQueue>, name: String, excludingId: String? = null): SavedQueue? {
    val candidate = name.trim()
    if (candidate.isEmpty()) return null
    return queues.firstOrNull {
        it.id != excludingId && it.name.trim().equals(candidate, ignoreCase = true)
    }
}

/** A candidate result before the write decision has been made. Internal to [applyTo]'s two steps. */
private data class Candidate(val queues: List<SavedQueue>, val effect: QueueEffect)

/** The queue record this edit operates on, or null when there is none to stamp (a delete). */
private val QueueEdit.targetId: String?
    get() = when (this) {
        is QueueEdit.Create -> id
        is QueueEdit.Rename -> id
        is QueueEdit.Add -> id
        is QueueEdit.Remove -> id
        is QueueEdit.Move -> id
        is QueueEdit.Delete -> null
    }

private fun List<SavedQueue>.stamping(id: String?, now: Long): List<SavedQueue> =
    if (id == null) this else map { if (it.id == id) it.copy(lastEditedAt = now) else it }

/**
 * The list this edit would produce, with no timestamps altered.
 *
 * Every branch either returns [to] unchanged or returns a list built from it, so "an edit preserves every queue
 * it did not touch" holds by construction: no branch can drop a record other than the one it deletes.
 */
private fun QueueEdit.candidateFor(to: List<SavedQueue>, now: Long): Candidate = when (this) {

    is QueueEdit.Create ->
        // A blank name cannot become a queue, which is what makes the rejection reachable rather than
        // theoretical: the draft's own rule decides it, so this and the Create screen cannot disagree.
        draft.toQueue(id = id, now = now)
            ?.let { Candidate(to + it, QueueEffect.Created(it)) }
            ?: Candidate(to, QueueEffect.Rejected(QueueRejection.BlankName))

    is QueueEdit.Rename -> {
        val index = to.indexOfFirst { it.id == id }
        val trimmed = name.trim()
        when {
            index < 0 -> Candidate(to, QueueEffect.Rejected(QueueRejection.NoSuchQueue))
            trimmed.isEmpty() -> Candidate(to, QueueEffect.Rejected(QueueRejection.BlankName))
            else -> {
                val renamed = to[index].copy(name = trimmed)
                Candidate(to.replacing(index, renamed), QueueEffect.Renamed(renamed))
            }
        }
    }

    is QueueEdit.Add -> {
        val index = to.indexOfFirst { it.id == id }
        if (index < 0) {
            Candidate(to, QueueEffect.Rejected(QueueRejection.NoSuchQueue))
        } else {
            val queue = to[index]
            // The key is the page id alone. The title is not part of a Note's identity for this purpose —
            // the spec's rule is that two Notes sharing a title are *two* members — and the comparison is
            // exactly case-sensitive, because a page id is opaque and is not a label anyone types.
            val present = queue.notes.mapTo(HashSet()) { it.pageId }
            val appended = mutableListOf<QueuedNote>()
            var alreadyPresent = 0
            for (note in notes) {
                // `add` is false for a note already in the queue *or* already appended from this same call,
                // so a request naming one page twice appends it once — which is what "a Note may appear at
                // most once" requires of every path that adds members.
                if (present.add(note.pageId)) appended += note else alreadyPresent++
            }
            val updated = queue.copy(notes = queue.notes + appended)
            Candidate(
                to.replacing(index, updated),
                QueueEffect.Added(updated, added = appended.size, alreadyPresent = alreadyPresent)
            )
        }
    }

    is QueueEdit.Remove -> {
        val index = to.indexOfFirst { it.id == id }
        if (index < 0) {
            Candidate(to, QueueEffect.Rejected(QueueRejection.NoSuchQueue))
        } else {
            val queue = to[index]
            val member = queue.notes.firstOrNull { it.pageId == pageId }
            val updated = queue.copy(notes = queue.notes.filterNot { it.pageId == pageId })
            Candidate(to.replacing(index, updated), QueueEffect.MemberRemoved(updated, member))
        }
    }

    is QueueEdit.Move -> {
        val index = to.indexOfFirst { it.id == id }
        if (index < 0) {
            Candidate(to, QueueEffect.Rejected(QueueRejection.NoSuchQueue))
        } else {
            val queue = to[index]
            val from = queue.notes.indexOfFirst { it.pageId == pageId }
            val reordered = move.applyTo(queue.notes, from)
            val updated = queue.copy(notes = reordered)
            val member = queue.notes.getOrNull(from)
            Candidate(
                to.replacing(index, updated),
                QueueEffect.MemberMoved(
                    queue = updated,
                    member = member,
                    position = if (member == null) 0 else reordered.indexOfFirst { it.pageId == pageId } + 1
                )
            )
        }
    }

    is QueueEdit.Delete -> {
        val index = to.indexOfFirst { it.id == id }
        if (index < 0) {
            Candidate(to, QueueEffect.Rejected(QueueRejection.NoSuchQueue))
        } else {
            Candidate(to.filterNot { it.id == id }, QueueEffect.Deleted(to[index]))
        }
    }
}

private fun List<SavedQueue>.replacing(index: Int, queue: SavedQueue): List<SavedQueue> =
    toMutableList().also { it[index] = queue }
