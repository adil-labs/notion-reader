package com.example.notionreader.application

/**
 * One listening-history entry, as the Listening tab describes it.
 *
 * ## Why this is a separate type from [ContinueListeningCard]
 *
 * Because the two surfaces show different things about the same sessions, and merging them would make
 * each one carry a field it does not use. Home's card is "a resumable session" — it is what the
 * mini-player also renders, it always has an `isPlaying` state to offer a Play/Pause control for, and it
 * describes at most [HOME_HISTORY_LIMIT] sessions. A history entry can be **completed**, which is a
 * session with no playback state to describe, no position to resume, and a different control — activating
 * one starts a new session rather than continuing this one.
 *
 * The Active Queue & Reader Session spec makes the distinction normative rather than presentational: a
 * completed session is "history, not resumable state", and the two lists are ordered and retained
 * separately. So the two types are two consequences of one rule, not two spellings of one thing.
 *
 * [lastActivityAt] is a raw instant and the description is not, for the reason [ContinueListeningCard]
 * records: the UI contract requires relative time and forbids a raw timestamp, so the fact is carried and
 * the wording belongs to the surface.
 */
data class HistoryEntry(
    /** Identifies the session this describes. A title is not an identity; two sessions can read one Note. */
    val sessionId: String,
    val title: String,
    /**
     * "Note 3 of 8" while unfinished, or "8 notes" once completed, or null for a queue of one.
     *
     * Null for a single-Note unfinished session because "Note 1 of 1" says nothing the title has not. A
     * completed session always has a count, because the count is the only thing that distinguishes a
     * finished queue from a finished Note.
     */
    val membership: String?,
    /** "Playing", "Paused", "Loading…", "Could not load", or "Completed". */
    val stateDescription: String,
    val isPlaying: Boolean,
    /** True for a session whose queue ended: it is replayable, not resumable. */
    val isCompleted: Boolean,
    val lastActivityAt: Long
)
