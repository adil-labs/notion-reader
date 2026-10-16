package com.example.notionreader.domain.discovery

import com.example.notionreader.domain.ReaderDocument
import java.security.MessageDigest

fun semanticHash(text: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(64) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 255
            append(hex[value ushr 4]); append(hex[value and 15])
        }
    }
}

/** Delimited lengths prevent ambiguous concatenations; metadata and validation times are excluded. */
fun semanticRevision(document: ReaderDocument): String = semanticHash(buildString {
    fun field(value: String) { append(value.length).append(':').append(value) }
    field(document.title)
    document.sections.forEach { section ->
        field(section.id); field(section.title.orEmpty())
        section.chunks.forEach { field(it.id); field(it.sourceBlockId); field(it.text) }
    }
})

class SemanticChunker(private val tokenizer: SemanticTokenizer, private val maxTokens: Int = 256) {
    fun chunk(document: ReaderDocument): List<SemanticPassage> = buildList {
        document.sections.forEach { section ->
            val contextText = listOfNotNull(document.title.takeIf { it.isNotBlank() }, section.title).distinct().joinToString("\n")
            val contextTokens = tokenizer.tokens(contextText)
            val context = contextTokens.take(32).lastOrNull()?.let { contextText.substring(0, it.end) }.orEmpty()
            val budget = maxTokens - tokenizer.tokens(context).size - 2
            require(budget > 32)
            section.chunks.forEach { chunk ->
                val tokens = tokenizer.tokens(chunk.text)
                var from = 0
                while (from < tokens.size) {
                    var to = minOf(from + budget, tokens.size)
                    val start = tokens[from].start
                    var end = tokens[to - 1].end
                    // Re-tokenization can differ at context boundaries; validate the actual input.
                    while (to > from && tokenizer.tokens("$context\n${chunk.text.substring(start, end)}").size + 2 > maxTokens) {
                        to--; end = if (to > from) tokens[to - 1].end else start
                    }
                    require(to > from) { "A token cannot fit the model input" }
                    val excerpt = chunk.text.substring(start, end)
                    add(SemanticPassage(
                        semanticHash("${canonicalSemanticId(document.pageId)}|${section.id}|${chunk.id}|$start|$end"),
                        canonicalSemanticId(document.pageId), section.id, section.title, chunk.sourceBlockId,
                        chunk.id, start, end, excerpt, "$context\n$excerpt".trim(), size
                    ))
                    if (to == tokens.size) break
                    from = maxOf(from + 1, to - 32)
                }
            }
        }
    }
}
