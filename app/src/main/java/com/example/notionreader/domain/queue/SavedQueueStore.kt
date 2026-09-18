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
     *
     * **This is for display, not for mutation.** An empty answer here cannot be told apart from a store that
     * could not be read, so a caller that is *changing* queues must use [transact] — writing a list derived
     * from this answer would remove whatever could not be read. The two methods differ deliberately on their
     * failure answers, and the reason is in [transact].
     */
    suspend fun read(): List<SavedQueue>

    /**
     * Reads the stored queues, computes the next state with [decide], and writes it — **as one operation**, and
     * only when [decide] reports that something changed.
     *
     * Returns what [decide] produced, or [QueueEffect.StoreUnavailable] when the store could not be read.
     *
     * ## Why this and not `read` followed by `write`
     *
     * This method exists because a two-step version had a data-loss defect, and the shape of it is worth
     * keeping recorded rather than rediscovered.
     *
     * [read] is required to report a store it cannot read as "no queues". That is right for a caller that is
     * *displaying* queues, where "nothing" is a safe answer. It is catastrophic for a caller that is
     * *changing* them, because the natural next step is to write the result back wholesale — and a whole-list
     * write derived from a failed read removes every queue the store actually holds. Of the six operations,
     * exactly one reaches this: `Create`, whose result does not depend on what was read, so it alone would
     * have replaced a listener's whole library with the single queue they had just made, leaving an app that
     * looks fine.
     *
     * Doing the read and the write in one call closes it structurally rather than by remembering something: an
     * implementation performs both inside a single transaction, so a read that fails means the transaction never
     * runs and nothing is written at all. A caller cannot get the sequence wrong because it cannot express the
     * sequence.
     *
     * ## What it deliberately does not do
     *
     * No `Result` and no error type, matching [read]: an unreadable store is reported as
     * [QueueEffect.StoreUnavailable], which is a statement about what this call did — nothing — rather than a
     * failure for a caller to branch on. And the write decision is [decide]'s, not this method's: "an operation
     * that changes nothing performs no write" is a rule about queue operations, and it belongs where operations
     * are defined, not repeated in every storage backend.
     */
    suspend fun transact(decide: (List<SavedQueue>) -> QueueEditOutcome): QueueEditOutcome
}
