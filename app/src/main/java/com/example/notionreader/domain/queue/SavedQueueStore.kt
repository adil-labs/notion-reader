package com.example.notionreader.domain.queue

/**
 * Where Saved Queues live.
 *
 * ## The same shape as [com.example.notionreader.domain.session.SessionStore], deliberately
 *
 * The Queue Creation & Queue Management spec requires that a Saved Queue **reuse the session store's
 * technology and policy** rather than introduce a second one, and this interface is where that starts. A
 * second store with its own conventions is a second thing to get wrong: two implementations can drift on
 * whether unknown keys are dropped, on what an unknown schema version means, and on whether a malformed
 * record is reported or swallowed.
 *
 * What is *shared* is the pattern — an Android-free interface in `domain`, a pure codec beside it, a
 * device-backed implementation in `data.local`, and the version policy. What is *not* shared is the record:
 * sessions and queues version independently, so a change to one never forces a re-read decision about the
 * other.
 *
 * ## Both methods are suspend, and neither is called from a queue operation's caller
 *
 * Storage is the one part of this design that cannot be synchronous. The model that decides what a reorder
 * means is pure and synchronous, and the I/O that persists the result is behind this interface — the same
 * split `ReaderSession` uses, and for the same reason: it keeps the part most likely to be wrong testable
 * without waiting for anything.
 *
 * ## Failure is not the caller's problem to handle specially
 *
 * The spec is explicit: "no operation in this specification reports a storage failure to the listener as a
 * failure of the operation". A failed read is an empty list of queues; a failed write is a lost edit. Neither
 * is a condition a caller branches on, so **there is no `Result` and no error type here** — giving the
 * interface an error channel would invite exactly the branching the rule forbids. That is the same reasoning
 * `SessionStore` records, applied to a second record.
 */
interface SavedQueueStore {

    /**
     * Every retained Saved Queue, in no particular order.
     *
     * Ordering is the *surfaces'* business — the Saved tab lists most-recently-edited first — so that it is
     * one implementation regardless of where the bytes came from, and so it is testable without a store. An
     * unreadable or corrupt store returns an empty list.
     *
     * Records that [SavedQueue.isValid] rejects are not returned, and neither is a failure: a discarded
     * record is indistinguishable from one that was never written, which is the policy.
     */
    suspend fun read(): List<SavedQueue>

    /**
     * Replaces the stored queues with [queues].
     *
     * Whole-list replacement rather than per-queue mutation, and for a different reason than the session
     * store has. The session's reason is retention — it is a decision about the *set*, so a per-entry writer
     * would have to re-implement it. **Saved Queues are never pruned**, so there is no such decision here.
     *
     * The reason here is that a queue operation is defined by its *postcondition on the list*: "Move to top"
     * means "this member is first", which is a statement about an ordering, and writing the resulting list
     * whole is what makes the operation and its persistence the same thing. A per-member update would make
     * the operation's meaning depend on the store's ability to express a partial change.
     */
    suspend fun write(queues: List<SavedQueue>)
}
