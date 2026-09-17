package com.example.notionreader.domain.session



/**
 * Where listening history lives.
 *
 * An interface in the reader package, with the device-backed implementation behind it, for the reason the
 * Active Queue & Reader Session spec gives in "Test strategy": persistence logic must be unit-testable on
 * the JVM with no device, no dispatcher and no network. Everything in this package is Android-free, so
 * restore, retention and resume behavior can be proven without a phone.
 *
 * ## Both methods are suspend, and neither is called from the session
 *
 * Storage is the one thing in this design that cannot be synchronous, so it is the one thing that is
 * *outside* the session. [ReaderSession] is scope-free and synchronous and exposes its state as a value;
 * a caller snapshots that value and writes it. That split is what keeps the session's state machine —
 * including the restore path, which is the part most likely to be wrong — testable without waiting for
 * anything.
 *
 * ## Failure is not the caller's problem to handle specially
 *
 * The spec is explicit that persistence is never on the playback path: "a failed write must not stop,
 * pause, or alter playback", and a failed read "is reported as an empty history" with no error state for
 * a feature the listener did not ask for. So this interface has no `Result` and no error type. An
 * implementation that cannot read reports no sessions; an implementation that cannot write drops the
 * record. Neither is a condition a caller branches on, and giving the interface an error channel would
 * invite exactly that branching.
 */
interface SessionStore {

    /**
     * Every retained session, in no particular order.
     *
     * Ordering and retention are [ListeningHistory]'s job rather than the store's, so that they are the
     * same code regardless of where the bytes came from, and so they are testable without a store at all.
     * An unreadable or corrupt store returns an empty list.
     */
    suspend fun read(): List<StoredSession>

    /**
     * Replaces the stored history with [sessions].
     *
     * Whole-list replacement rather than per-entry mutation, because retention is a decision about the
     * *set* — the oldest entry is dropped to make room for the newest — and a store that was asked to
     * write entries one at a time would have to re-implement that decision to know what to delete.
     */
    suspend fun write(sessions: List<StoredSession>)
}
