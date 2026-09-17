package com.example.notionreader.reader

/**
 * Where a restored session should start, and what the listener must be told first.
 *
 * This is the answer to the question the Notion Integration Implementation Spec explicitly refused to
 * answer:
 *
 * > The Notion integration layer must not itself decide whether playback resumes, where Continue falls
 * > back, or what the user hears when a saved position no longer exists. Those behaviors belong to the
 * > future Active Queue & Reader Session specification.
 *
 * The integration layer reports what survived ([ReaderPositionResolution]); this decides what to do about
 * it. The distinction is the whole point of that handoff, so nothing here inspects the document beyond
 * asking it to resolve a position.
 *
 * ## The rule, and its reason
 *
 * Resuming is **silent when it is exact** and **announced when it is not**.
 *
 * A listener who resumes inside a sentence does not need to be told anything: the text is the
 * continuation, and an announcement would be an interruption in the middle of what they were hearing. A
 * listener whose position moved does need the section named, because that is the only thing that tells
 * them where they now are — the alternative is that the reader appears to have jumped for no reason.
 *
 * ## Why a lost position starts from the beginning with an announcement
 *
 * Three behaviors were available and only one of them is honest:
 *
 * - **Start from the beginning silently** — the listener resumes mid-book, hears the first sentence, and
 *   cannot tell whether the app lost their place or did this on purpose. It did the second, and did not
 *   say so.
 * - **Skip the Note** — silently drops content the listener asked to hear, in the same breath as resuming.
 * - **Start from the beginning and say so** — chosen. The Note is still readable, the queue position is
 *   kept, and the one thing the listener needs to know is the one thing announced.
 */
object ResumePolicy {

    /**
     * What is spoken when a saved position could not be found at all.
     *
     * A constant in the reader package rather than a string resource, because it is *session vocabulary*
     * in the same sense that the Player's "Playing"/"Paused"/"Section 2 of 3" is: a decision about what
     * the listener hears, not a label on a control. It is deliberately short, because it delays content
     * rather than replacing it.
     */
    const val POSITION_LOST_ANNOUNCEMENT: String =
        "Previous position not found. Starting from the beginning."

    /** Start at the first chunk, saying nothing. What a Note with no saved position does — and it is silent. */
    val AtStart = ResumeDecision(startIndex = 0, announcement = null, match = null)

    /**
     * Where to start [document], given the position a session saved for it.
     *
     * [position] is null when the session never had one for this Note — a restored queue sitting on a Note
     * it never began, or a fresh Note. That case is **silent rather than announced**, and it is the reason
     * this takes a nullable position instead of a match: "no position yet" and "the position is gone" are
     * different facts that a listener experiences very differently, and collapsing them would make every
     * freshly started Note in a restored queue announce a loss that never happened.
     */
    fun decide(position: ReaderDocumentPosition?, document: ReaderDocument): ResumeDecision {
        if (position == null || !position.isUsable) return AtStart

        val resolution = document.resolve(position)

        // NONE carries no index by design — the integration spec is explicit that reporting "position
        // lost" *and* a number to resume at would invite a caller to resume at the number.
        if (resolution.isLost) {
            return ResumeDecision(
                startIndex = 0,
                announcement = POSITION_LOST_ANNOUNCEMENT,
                match = PositionMatch.NONE
            )
        }

        val index = resolution.paragraphIndex ?: return AtStart

        val announcement = when (resolution.match) {
            // Exact: nothing is said. See the class comment.
            PositionMatch.CHUNK -> null

            // The landing place moved, so name the section it moved to. An untitled section — the
            // implicit intro section has no title — has nothing to name, so nothing is said rather than
            // an empty announcement being spoken.
            PositionMatch.BLOCK, PositionMatch.SECTION ->
                resolution.resolvedSectionId
                    ?.let { id -> document.sections.firstOrNull { it.id == id }?.title }
                    ?.takeIf { it.isNotBlank() }

            PositionMatch.NONE -> POSITION_LOST_ANNOUNCEMENT
        }

        return ResumeDecision(startIndex = index, announcement = announcement, match = resolution.match)
    }
}

/**
 * What [ResumePolicy] decided.
 *
 * [match] is the resolution quality, or null when there was no saved position to resolve. It is carried
 * because recording it is required — the Active Queue & Reader Session spec requires the match quality to
 * be recorded as a fact on an inexact restore — and because a test asserting "this resume was inexact and
 * announced the section" needs to see both halves of that claim in one place.
 */
data class ResumeDecision(
    /** Index into the document's flat chunk list to start playback at. */
    val startIndex: Int,
    /** Spoken before any content, or null when the resume is silent. */
    val announcement: String?,
    /** How precisely the saved position was located, or null when none was saved. */
    val match: PositionMatch?
)
