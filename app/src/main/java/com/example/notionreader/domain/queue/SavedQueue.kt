package com.example.notionreader.domain.queue

import kotlinx.serialization.Serializable

/**
 * The schema version of a stored Saved Queue record.
 *
 * Deliberately its own constant rather than reusing the session's, even though both are `1` today and both
 * follow the same policy. They version two different records, and tying them together would mean a change to
 * one forces a re-read decision about the other — which is exactly the kind of coupling the Queue Creation &
 * Queue Management spec's "the store, reused rather than invented" section is careful to avoid: the
 * *technology and the policy* are shared, the *records* are not.
 *
 * A record whose version is not this value is **discarded rather than migrated**, for the reason the session
 * spec states: a migration that guesses wrong hands a listener a queue they did not build, and losing a queue
 * is visible and recoverable while a silently wrong one is neither.
 */
const val SAVED_QUEUE_SCHEMA_VERSION: Int = 1

/**
 * One Saved Queue, as persisted.
 *
 * ## What it stores, and what it deliberately does not
 *
 * A queue stores **identities and labels**: its own stable identity, a display name, and an ordered list of
 * Note identities each carrying a title. It stores no Note content and no playback position, and both
 * exclusions are decisions rather than omissions:
 *
 * - **No content.** A stored copy is a second source of truth that can disagree with the workspace, which is
 *   the failure a reading app must never have silently. The session spec forbids it for its own records and
 *   the Queue Creation spec forbids it here.
 * - **No position.** Resume state belongs to a listening session. The Product & Architecture Contract lists
 *   "maintain permanent resume positions for individual Notes independently of listening sessions" as a
 *   non-goal, and "where a listener got to in this queue" is a fact about a session, not about the queue.
 *
 * It stores no member count either: that is [notes]`.size`, and a stored copy would be a second thing to keep
 * in step.
 *
 * ## Why the title is stored per member
 *
 * The same reason the session record stores one: **a page id cannot be spoken.** The Saved tab and the
 * queue's own list must name a member *before* its document has been fetched, and a queue is a list of names
 * to the listener. Like the session's, a stored title can go stale after the source page is renamed; that is
 * accepted, and the alternative — fetching every member to list a queue — is the eager loading this design
 * avoids everywhere else.
 *
 * ## What identity is, and why it is not the name
 *
 * [id] is assigned when the queue is created and never changes. [name] is a label that a listener reads and
 * can change. They are separate because three things are easy to conflate and must not be: the *queue's*
 * identity, the *queue's* name, and the *members'* identities. Ordering and display depend on the last; a
 * stored reference depends on the first; and a rename must not invalidate a reference — which is the whole
 * reason `QueueOrigin.Saved` carries this identity rather than the name.
 */
@Serializable
data class SavedQueue(
    /** The queue's stable identity. Never its name, and never derived from its contents. */
    val id: String,
    /** The display label. Required, and validated by [SavedQueueDraft] rather than here. */
    val name: String,
    /**
     * The members, in the order the listener put them.
     *
     * Reusing [QueuedNote] rather than introducing a second "a Note, named" type: the pair is the same pair,
     * and a second type would be a second definition of "which Note" that could drift. That is the same
     * argument `ActiveQueue` makes for keeping `@Serializable` on the domain type.
     *
     * Duplicates are not permitted *here*, unlike in an `ActiveQueue` — the Product & Architecture Contract
     * deduplicates Saved Queue membership by stable identity. It is not enforced by this type, because a
     * stored record is data from disk rather than a value this application just built; it is enforced by the
     * operations that add members, and [isValid] only rejects what cannot be used at all.
     */
    val notes: List<QueuedNote>,
    /** Epoch millis of the last edit. The Saved tab's ordering key. */
    val lastEditedAt: Long,
    val schemaVersion: Int = SAVED_QUEUE_SCHEMA_VERSION
) {

    /**
     * Whether this record can be used at all.
     *
     * Two things make a record unusable: a schema version this build does not understand, and a missing
     * identity or name — a queue with no name cannot be listed, and one with no identity cannot be referred
     * to.
     *
     * **An empty [notes] is valid**, and that is the point of checking only identity and name. "Create saved
     * queue" deliberately produces an empty queue, and the contract names creating one as a supported
     * operation. A validity predicate that rejected an empty queue would make the feature's own first step
     * produce an invalid record.
     *
     * The version check lives here rather than at the read site so that no future reader can forget it, which
     * is the shape `StoredSession.isValid` already uses.
     */
    val isValid: Boolean
        get() = schemaVersion == SAVED_QUEUE_SCHEMA_VERSION && id.isNotBlank() && name.isNotBlank()

    /**
     * Whether every member identity is present exactly once.
     *
     * A **diagnostic**, not a gate. A record that fails this is still usable — the duplicate would simply
     * appear twice — and discarding a listener's whole queue over it would be a disproportionate response to
     * something that is only reachable through a bug in the add path. Named so a test can assert the rule the
     * operations are supposed to maintain, without the store having to enforce it.
     */
    val hasUniqueMembers: Boolean
        get() = notes.map { it.pageId }.toSet().size == notes.size
}

/**
 * A queue being created or renamed, before it has an identity or a name that has been accepted.
 *
 * ## Why this is separate from [SavedQueue]
 *
 * Because the invalid inputs are *reachable* — a listener can type nothing, or only spaces — and the rule
 * about them belongs somewhere a test can call. A `SavedQueue` with a blank name cannot represent "the user
 * is part-way through typing", so the draft is the type that can hold it, and [toQueue] is the only way one
 * becomes the other.
 *
 * It is deliberately not a `SavedQueue` with a nullable name or a placeholder identity: a type that can exist
 * in an invalid state and is written to disk is how an invalid record gets stored, and the check that
 * prevents it would then have to live at the write site where it is easy to forget.
 */
data class SavedQueueDraft(
    val name: String
) {

    /**
     * The name this draft would produce: trimmed.
     *
     * Trimming rather than rejecting is the right default for a required text field — "  Reading  " is
     * unambiguously the name the listener meant — and the Queue Creation spec settles that the name is
     * trimmed.
     */
    val trimmedName: String get() = name.trim()

    /**
     * Whether this draft can become a queue.
     *
     * Blankness is checked *after* trimming, so a name of spaces is rejected. That is the whole reason the
     * check is a property of the draft rather than of the raw text.
     */
    val isCompletable: Boolean get() = trimmedName.isNotEmpty()

    /**
     * The queue this draft describes, or null when it cannot be completed.
     *
     * [id] and [now] are parameters rather than generated here, because this type is a pure value and
     * embedding a clock or a UUID generator in it would make it untestable for no benefit. A caller that has
     * decided the draft is completable supplies both.
     */
    fun toQueue(id: String, now: Long): SavedQueue? =
        if (!isCompletable) {
            null
        } else {
            SavedQueue(id = id, name = trimmedName, notes = emptyList(), lastEditedAt = now)
        }
}
