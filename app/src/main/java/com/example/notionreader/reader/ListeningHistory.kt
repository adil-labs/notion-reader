package com.example.notionreader.reader

/**
 * Listening history: which sessions are offered, in what order, and for how long.
 *
 * Pure functions over a list, with no store and no clock of their own, for two reasons. The ordering and
 * retention rules are the part of persistence most likely to be got subtly wrong, and they are the part
 * that needs no I/O to test. And retention is a decision about a *set*, so it belongs where the whole set
 * is visible.
 *
 * ## The limits, and where they come from
 *
 * The UI Screens & Navigation Contract defers these numbers explicitly: "The exact retention limit for
 * completed listening history belongs to the Active Queue & Reader Session specification." These are that
 * specification's answer.
 *
 * They are asymmetric on purpose. An **unfinished** session is a place the listener means to return to,
 * so losing one loses a place; the limit is generous and the live session can never be evicted by it. A
 * **completed** session is replayable from its beginning at any time and its value decays with age, so it
 * is pruned both by count and by age.
 *
 * ## Ordering
 *
 * Most recent listening activity first, for both lists. The contract states it once for Listening
 * ordering and it applies to both sections, which matters because a session that completes moves from one
 * list to the other and must not jump to a different position in the process.
 */
object ListeningHistory {

    /** How many unfinished sessions are kept. */
    const val MAX_UNFINISHED: Int = 10

    /** How many completed sessions are kept, regardless of age. */
    const val MAX_COMPLETED: Int = 20

    /** How old a completed session may be before it is dropped, regardless of count. */
    const val COMPLETED_MAX_AGE_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

    /**
     * The sessions to keep, deduplicated, ordered, and within the limits.
     *
     * [keepId] is the session that is currently in memory, and it is retained unconditionally. The
     * contract's rule is that "the session that is currently in memory is always retained regardless of
     * the limit", and it is enforced rather than assumed because the alternative — reasoning that the
     * live session is the most recently active and therefore survives anyway — is only true while the
     * clock moves forwards, which is not a property of a phone.
     *
     * **Deduplication is by [StoredSession.id] and takes the most recent record for each id.** Without it,
     * writing the live session would accumulate a second entry for it every time it was snapshotted, and
     * the same session would appear in Continue listening as many times as the listener had paused.
     */
    fun retain(
        sessions: List<StoredSession>,
        now: Long,
        keepId: String? = null
    ): List<StoredSession> {
        val valid = sessions.filter { it.isValid }
        val newestFirst = valid.sortedByDescending { it.lastActivityAt }.distinctBy { it.id }

        val unfinished = newestFirst.filterNot { it.completed }.take(MAX_UNFINISHED)

        val completed = newestFirst
            .filter { it.completed }
            .filter { now - it.lastActivityAt <= COMPLETED_MAX_AGE_MILLIS }
            .take(MAX_COMPLETED)

        val kept = unfinished + completed

        val pinned = keepId?.let { id -> valid.firstOrNull { it.id == id } }
        val all = if (pinned != null && kept.none { it.id == pinned.id }) kept + pinned else kept

        return all.sortedByDescending { it.lastActivityAt }
    }

    /** Unfinished sessions, most recently active first. What Continue listening offers. */
    fun unfinished(sessions: List<StoredSession>): List<StoredSession> =
        sessions
            .filter { it.isValid && !it.completed }
            .sortedByDescending { it.lastActivityAt }

    /** Completed sessions, most recently active first. What Recently completed lists. */
    fun completed(sessions: List<StoredSession>): List<StoredSession> =
        sessions
            .filter { it.isValid && it.completed }
            .sortedByDescending { it.lastActivityAt }
}
