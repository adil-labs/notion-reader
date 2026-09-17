package com.example.notionreader.reader

/**
 * Immutable view of the current logical reading position.
 *
 * All operations return a new state; the receiver is never mutated.
 * An empty document is valid: [currentParagraph] is "" and navigation is a no-op.
 */
data class ReaderState(
    val paragraphs: List<String>,
    val currentParagraphIndex: Int = 0,
    val isPlaying: Boolean = false
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

    val isAtFirstParagraph: Boolean
        get() = currentParagraphIndex <= 0

    val isAtLastParagraph: Boolean
        get() = paragraphs.isEmpty() || currentParagraphIndex >= paragraphs.size - 1

    /** Moves forward one paragraph. At the end it stays put; it never wraps or overflows. */
    fun nextParagraph(): ReaderState =
        copy(currentParagraphIndex = (currentParagraphIndex + 1).coerceAtMost(lastIndex))

    /** Moves backward one paragraph. At the beginning it stays put; it never wraps. */
    fun previousParagraph(): ReaderState =
        copy(currentParagraphIndex = (currentParagraphIndex - 1).coerceAtLeast(0))

    fun play(): ReaderState = copy(isPlaying = paragraphs.isNotEmpty())

    fun pause(): ReaderState = copy(isPlaying = false)

    private val lastIndex: Int
        get() = (paragraphs.size - 1).coerceAtLeast(0)
}
