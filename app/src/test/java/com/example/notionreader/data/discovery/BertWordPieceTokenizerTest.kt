package com.example.notionreader.data.discovery

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BertWordPieceTokenizerTest {
    private val tokenizer = BertWordPieceTokenizer(javaClass.getResourceAsStream("/discovery/vocab.txt")!!.bufferedReader().readLines())

    @Test fun tokensMatchThePinnedPublisherTokenizer() {
        val probes = Json.parseToJsonElement(javaClass.getResource("/discovery/tokenizer-golden.json")!!.readText()).jsonArray
        for (probe in probes) {
            val text = probe.jsonObject.getValue("text").jsonPrimitive.content
            val expected = probe.jsonObject.getValue("input_ids").jsonArray.map { it.jsonPrimitive.int }.drop(1).dropLast(1)
            assertEquals(text, expected, tokenizer.tokens(text).map { it.id })
            tokenizer.tokens(text).forEach { assertTrue(it.start >= 0 && it.end > it.start && it.end <= text.length) }
        }
    }

    @Test fun sourceOffsetsStayAttachedToOriginalAccentedText() {
        val text = "A café."
        val tokens = tokenizer.tokens(text)
        assertEquals(listOf("A", "café", "."), tokens.map { text.substring(it.start, it.end) })
    }

    @Test fun tokenizationNeverSilentlyTruncatesLongSourceText() {
        assertEquals(300, tokenizer.tokens(List(300) { "hello" }.joinToString(" ")).size)
    }
}
