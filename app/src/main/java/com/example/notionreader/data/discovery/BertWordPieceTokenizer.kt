package com.example.notionreader.data.discovery

import com.example.notionreader.domain.discovery.SemanticToken
import com.example.notionreader.domain.discovery.SemanticTokenizer
import java.text.Normalizer
import java.util.Locale

class BertWordPieceTokenizer(vocabulary: List<String>) : SemanticTokenizer {
    private val vocabulary = vocabulary.withIndex().associate { it.value to it.index }
    private val unknown = requireNotNull(this.vocabulary["[UNK]"])
    val classificationId = requireNotNull(this.vocabulary["[CLS]"])
    val separatorId = requireNotNull(this.vocabulary["[SEP]"])

    override fun tokens(text: String): List<SemanticToken> {
        val result = mutableListOf<SemanticToken>()
        var start = 0
        Regex("\\[(?:PAD|UNK|CLS|SEP|MASK)]").findAll(text).forEach { match ->
            result += basicTokens(text.substring(start,match.range.first)).map { it.copy(start=it.start+start,end=it.end+start) }
            result += SemanticToken(requireNotNull(vocabulary[match.value]),match.range.first,match.range.last+1)
            start=match.range.last+1
        }
        result += basicTokens(text.substring(start)).map { it.copy(start=it.start+start,end=it.end+start) }
        return result
    }

    private fun basicTokens(text: String): List<SemanticToken> {
        data class UnitChar(val value: Char, val start: Int, val end: Int)
        val words = mutableListOf<List<UnitChar>>()
        val current = mutableListOf<UnitChar>()
        fun flush() { if (current.isNotEmpty()) { words += current.toList(); current.clear() } }
        var offset = 0
        while (offset < text.length) {
            val cp = text.codePointAt(offset)
            val end = offset + Character.charCount(cp)
            val type = Character.getType(cp)
            if (cp == 0 || cp == 0xfffd || (type == Character.CONTROL.toInt() && cp !in listOf(9,10,13)) || type == Character.FORMAT.toInt()) {
                offset = end; continue
            }
            if (Character.isWhitespace(cp) || type == Character.SPACE_SEPARATOR.toInt()) { flush(); offset = end; continue }
            val punctuation = cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126 || type in listOf(Character.CONNECTOR_PUNCTUATION.toInt(),Character.DASH_PUNCTUATION.toInt(),Character.START_PUNCTUATION.toInt(),Character.END_PUNCTUATION.toInt(),Character.INITIAL_QUOTE_PUNCTUATION.toInt(),Character.FINAL_QUOTE_PUNCTUATION.toInt(),Character.OTHER_PUNCTUATION.toInt())
            val chinese = cp in 0x4e00..0x9fff || cp in 0x3400..0x4dbf || cp in 0x20000..0x2a6df || cp in 0x2a700..0x2b73f || cp in 0x2b740..0x2b81f || cp in 0x2b820..0x2ceaf || cp in 0xf900..0xfaff || cp in 0x2f800..0x2fa1f
            if (punctuation || chinese) flush()
            val cleaned = Normalizer.normalize(String(Character.toChars(cp)).lowercase(Locale.ROOT),Normalizer.Form.NFD)
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            cleaned.forEach { current += UnitChar(it,offset,end) }
            if (punctuation || chinese) flush()
            offset = end
        }
        flush()
        return words.flatMap { word ->
            val value = word.joinToString("") { it.value.toString() }
            if (value.length > 100) listOf(SemanticToken(unknown,word.first().start,word.last().end))
            else {
                val pieces = mutableListOf<SemanticToken>()
                var start = 0
                while (start < value.length) {
                    var end = value.length
                    var id: Int? = null
                    while (end > start) {
                        id = vocabulary[(if (start > 0) "##" else "") + value.substring(start,end)]
                        if (id != null) break
                        end--
                    }
                    if (id == null) { pieces.clear(); pieces += SemanticToken(unknown,word.first().start,word.last().end); break }
                    pieces += SemanticToken(id,word[start].start,word[end-1].end)
                    start = end
                }
                pieces
            }
        }
    }
}
