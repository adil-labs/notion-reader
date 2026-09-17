package com.example.notionreader.data.content

/**
 * Splits long block text into speech-sized parts.
 *
 * The integration spec requires that "very long text blocks should be split at sentence boundaries
 * where possible". The hedge implies a fallback chain, which this implements in order:
 *
 * 1. pack whole sentences greedily up to [maxChars];
 * 2. if a single sentence alone exceeds [maxChars], split at the last whitespace before the limit;
 * 3. if a single word alone exceeds [maxChars], hard-split at the limit.
 *
 * Steps 2 and 3 are why the fixture has blocks 24 (one 422-character sentence, so step 2 must run)
 * and 25 (382 characters with no full stop, so step 1 has nothing to match).
 *
 * The threshold itself is deliberately not settled: no governing document supplies one, so it stays
 * an implementation constant (docs/decisions.md D11). Tests therefore assert the threshold-independent
 * properties — at least two parts, ends at a sentence or word boundary, lossless concatenation —
 * rather than exact counts.
 *
 * Splitting never drops or duplicates characters: [joining] the parts always reproduces the input,
 * modulo the whitespace trimmed from part boundaries.
 */
class TextChunker(private val maxChars: Int = DEFAULT_MAX_CHUNK_CHARS) {

    /** Returns one entry for ordinary text, or several when [text] is too long. */
    fun split(text: String): List<String> {
        if (text.length <= maxChars) return listOf(text)

        val parts = mutableListOf<String>()
        var current = StringBuilder()

        for (sentence in splitSentences(text)) {
            // Step 2 and 3: a single sentence that cannot fit on its own.
            if (sentence.length > maxChars) {
                if (current.isNotEmpty()) {
                    parts += current.toString()
                    current = StringBuilder()
                }
                parts += splitOversizedSentence(sentence)
                continue
            }

            val projected = if (current.isEmpty()) sentence.length else current.length + 1 + sentence.length
            if (projected > maxChars) {
                parts += current.toString()
                current = StringBuilder()
            }

            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }

        if (current.isNotEmpty()) parts += current.toString()

        return parts.ifEmpty { listOf(text) }
    }

    /** Step 2 then step 3 for a sentence that is too long to keep whole. */
    private fun splitOversizedSentence(sentence: String): List<String> {
        val parts = mutableListOf<String>()
        var remaining = sentence

        while (remaining.length > maxChars) {
            // Step 2: last whitespace at or before the limit, so the break lands between words.
            val window = remaining.substring(0, maxChars)
            val breakAt = window.indexOfLast { it == ' ' }

            if (breakAt > 0) {
                parts += remaining.substring(0, breakAt).trimEnd()
                remaining = remaining.substring(breakAt + 1).trimStart()
            } else {
                // Step 3: a single word longer than the limit. Nothing left to try.
                parts += window
                remaining = remaining.substring(maxChars).trimStart()
            }
        }

        if (remaining.isNotEmpty()) parts += remaining
        return parts
    }

    /**
     * Splits on sentence-ending punctuation, keeping the punctuation with its sentence.
     *
     * A terminator only ends a sentence when followed by whitespace or the end of the text, so
     * decimals and abbreviations do not fragment the text unnecessarily.
     */
    private fun splitSentences(text: String): List<String> {
        val sentences = mutableListOf<String>()
        val current = StringBuilder()

        for ((index, ch) in text.withIndex()) {
            current.append(ch)

            val isTerminator = ch == '.' || ch == '!' || ch == '?'
            if (!isTerminator) continue

            val next = text.getOrNull(index + 1)
            if (next == null || next.isWhitespace()) {
                val sentence = current.toString().trim()
                if (sentence.isNotEmpty()) sentences += sentence
                current.clear()
            }
        }

        val tail = current.toString().trim()
        if (tail.isNotEmpty()) sentences += tail

        return sentences.ifEmpty { listOf(text) }
    }
}

/**
 * Threshold for splitting an overlong block, in characters.
 *
 * NOT a specification value. No governing document supplies a number, so this is an implementation
 * constant to be revisited once the Phase 0 fixture's fallback blocks have been exercised
 * (docs/decisions.md D11).
 */
const val DEFAULT_MAX_CHUNK_CHARS = 300
