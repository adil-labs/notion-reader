package com.example.notionreader.application

/**
 * A resumable listening session, as Home and the mini-player both need to describe it.
 *
 * ## Why this type is here and not in `ui`
 *
 * It was declared in `ui/HomeScreen.kt`, and [ReaderApp] imported it from there. That single import is
 * what made the application layer depend on the presentation layer while the presentation layer
 * depended on the application layer — a cycle, in which "which direction does this dependency point"
 * has no answer, the application cannot be exercised without the UI, and the only thing keeping the
 * arrangement readable was that nobody had looked at it.
 *
 * It belongs here because of what it *is*: a read model. It is produced by [ReaderApp] from session
 * state and consumed by two screens, and it contains no Compose type, no layout, and no formatting —
 * [lastActivityAt] is a raw instant precisely so that presentation decides how to say it. A value that
 * the application layer constructs and the UI layer renders is an application-layer value; that it was
 * once written down in the file of its first consumer is an accident of history, not a design.
 *
 * A small value rather than the session itself, so the screens stay display-only: they cannot pause,
 * stop, or reorder anything because they are not given the means to. [isPlaying] is a description for
 * the screen and its control, not a claim that the screen owns the state.
 *
 * [sessionId] exists because Home can show more than one session. Continue has to say which one it
 * means, and a title is not an identity — two sessions in one history can read the same Note, which the
 * Active Queue & Reader Session spec requires to be two entries.
 */
data class ContinueListeningCard(
    /** Identifies the session this describes. Never used for display. */
    val sessionId: String,
    /** The Note title. */
    val title: String,
    /** "Note 3 of 8", or null for a queue of one. */
    val queuePosition: String?,
    /** "Playing", "Paused", or "Loading…" — the same vocabulary the Player uses. */
    val stateDescription: String,
    val isPlaying: Boolean,
    /**
     * Epoch millis of the last listening activity.
     *
     * The timestamp is carried rather than a formatted string, because the UI contract requires relative
     * time and forbids a raw one, so the *formatting* belongs to `ui.describeLastActivity` and the *fact*
     * belongs here. Formatting it at the point the card is built would make it stale the moment it was
     * built, which for a session paused an hour ago is the whole point of the line.
     */
    val lastActivityAt: Long
)
