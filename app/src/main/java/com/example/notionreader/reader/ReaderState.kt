package com.example.notionreader.reader

/**
 * Where one [ReaderSection] sits in the flat, spoken paragraph list.
 *
 * [ReaderDocument] is modelled as sections of chunks because that is how a Notion page is written,
 * but playback and progress are flat: "paragraph 12 of 28". Section-aware navigation and the Player's
 * "current section" line both need the translation between the two views, and doing it in exactly one
 * place is what stops that arithmetic being reinvented (and subtly mis-computed) in the UI.
 *
 * Indices are inclusive and refer to positions in [ReaderState.paragraphs], not to chunk ids: the UI
 * only ever needs positions, and stable identities are the session layer's concern.
 */
data class ReaderSectionSpan(
    val id: String,
    val title: String?,
    val level: Int,
    val firstParagraphIndex: Int,
    val lastParagraphIndex: Int
)

/**
 * Projects every section that produced at least one chunk onto its paragraph range.
 *
 * A section with no chunks cannot occur in a mapped document (the mapper closes a section only when
 * it has content), but such a section is skipped rather than assumed absent, so the ranges stay
 * correct if a future mapping policy ever produces one.
 */
fun ReaderDocument.sectionSpans(): List<ReaderSectionSpan> {
    val spans = mutableListOf<ReaderSectionSpan>()
    var nextIndex = 0

    for (section in sections) {
        if (section.chunks.isEmpty()) continue

        val first = nextIndex
        nextIndex += section.chunks.size
        spans += ReaderSectionSpan(
            id = section.id,
            title = section.title,
            level = section.level,
            firstParagraphIndex = first,
            lastParagraphIndex = nextIndex - 1
        )
    }

    return spans
}

/**
 * Immutable view of the current logical reading position.
 *
 * All operations return a new state; the receiver is never mutated. An operation that moves nothing
 * returns the receiver itself, so a boundary press (next at the end) cannot produce a state change
 * that has to be filtered out downstream.
 *
 * An empty document is valid: [currentParagraph] is "" and navigation is a no-op.
 *
 * [sections] is an outline, used for section navigation and for the Player's "current section" line.
 * It is empty by default so that a hand-built state — and every existing test of one — stays valid;
 * [ReaderEngineImpl] always populates it from the document it is given.
 */
data class ReaderState(
    val paragraphs: List<String>,
    val currentParagraphIndex: Int = 0,
    val isPlaying: Boolean = false,
    val sections: List<ReaderSectionSpan> = emptyList(),
    /**
     * True when playback stopped because it read the last paragraph, rather than because the user
     * paused. The difference has to be spoken — "end of note" is a required announcement — and it
     * cannot be derived afterwards: [isAtLastParagraph] is also true while the last paragraph is
     * still being read.
     */
    val hasReachedEnd: Boolean = false
) {
    val currentParagraph: String
        get() = paragraphs.getOrElse(currentParagraphIndex) { "" }

    val paragraphCount: Int
        get() = paragraphs.size

    /** Human readable position, e.g. "Paragraph 2 of 8". Empty documents report "No paragraphs". */
    val positionDescription: String
        get() = if (paragraphs.isEmpty()) {
            "No paragraphs"
        } else {
            "Paragraph ${currentParagraphIndex + 1} of ${paragraphs.size}"
        }

    /** The section the reading position currently falls in, or null when there is no outline. */
    val currentSection: ReaderSectionSpan?
        get() = sections.lastOrNull { it.firstParagraphIndex <= currentParagraphIndex }

    /** Position within the outline, e.g. "Section 2 of 4". Null when the state has no outline. */
    val sectionPositionDescription: String?
        get() {
            if (sections.isEmpty()) return null
            val index = sections.indexOfFirst { it.id == currentSection?.id }
            return if (index < 0) null else "Section ${index + 1} of ${sections.size}"
        }

    /**
     * What the Player shows and announces as playback state.
     *
     * Three distinct states, not two: collapsing "stopped at the end" into "Paused" would tell a
     * listener the wrong thing about why the reading stopped.
     */
    val playbackStateDescription: String
        get() = when {
            isPlaying -> "Playing"
            hasReachedEnd -> "End of note"
            else -> "Paused"
        }

    val isAtFirstParagraph: Boolean
        get() = currentParagraphIndex <= 0

    val isAtLastParagraph: Boolean
        get() = paragraphs.isEmpty() || currentParagraphIndex >= paragraphs.size - 1

    /** Moves forward one paragraph. At the end it stays put; it never wraps or overflows. */
    fun nextParagraph(): ReaderState = movedTo((currentParagraphIndex + 1).coerceAtMost(lastIndex))

    /** Moves backward one paragraph. At the beginning it stays put; it never wraps. */
    fun previousParagraph(): ReaderState = movedTo((currentParagraphIndex - 1).coerceAtLeast(0))

    /**
     * Moves to the first paragraph of the next section, or to the last paragraph when already in the
     * final section.
     *
     * Landing on the next section's first paragraph — rather than its second — is deliberate: a
     * heading is a spoken chunk, so "next section" should begin by reading that heading. With no
     * outline this degrades to a single paragraph, which keeps the control meaningful rather than dead.
     */
    fun nextSection(): ReaderState {
        val section = currentSection ?: return nextParagraph()

        val next = sections.firstOrNull { it.firstParagraphIndex > section.firstParagraphIndex }
        return movedTo(next?.firstParagraphIndex ?: lastIndex)
    }

    /**
     * Returns to the start of the current section, or to the start of the previous one when already
     * there. That is what makes the control usable while reading: one press re-reads the current
     * section from its heading, a second press steps back a section.
     */
    fun previousSection(): ReaderState {
        val section = currentSection ?: return previousParagraph()

        if (currentParagraphIndex > section.firstParagraphIndex) {
            return movedTo(section.firstParagraphIndex)
        }

        val previous = sections.lastOrNull { it.firstParagraphIndex < section.firstParagraphIndex }
        return movedTo(previous?.firstParagraphIndex ?: 0)
    }

    fun play(): ReaderState = copy(
        isPlaying = paragraphs.isNotEmpty(),
        hasReachedEnd = false
    )

    /** Stops reading at the user's request. Distinct from [finish], which means the note ended. */
    fun pause(): ReaderState = copy(isPlaying = false)

    /** Playback reached the end of the document: stopped, and stopped *because* it reached the end. */
    fun finish(): ReaderState = copy(isPlaying = false, hasReachedEnd = true)

    private fun movedTo(index: Int): ReaderState =
        if (index == currentParagraphIndex) {
            this
        } else {
            copy(currentParagraphIndex = index, hasReachedEnd = false)
        }

    private val lastIndex: Int
        get() = (paragraphs.size - 1).coerceAtLeast(0)
}
