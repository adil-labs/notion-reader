package com.example.notionreader.data.discovery

import org.junit.Assert.*
import org.junit.Test

class GemmaModelPackTest {
    @Test fun thePublishedBundleIncludesItsAuthoritativeTokenizer() {
        val artifacts = GemmaModelPack.artifacts
        assertEquals(164626432L, artifacts.sumOf { it.bytes })
        assertEquals(1, artifacts.size)
        assertEquals(GemmaModelPack.bundleFile, artifacts.first().remotePath)
        assertTrue(GemmaModelPack.description.startsWith("EmbeddingGemma 2 INT4"))
        assertEquals("bundled-sentencepiece:" + GemmaModelPack.tokenizerSha256, GemmaModelPack.identity.tokenizer)
        assertEquals(32768L, GemmaModelPack.tokenizerOffset)
        assertEquals(4689013, GemmaModelPack.tokenizerBytes)
        assertEquals("task: clustering | text: ", GemmaModelPack.instruction)
        assertEquals(4, GemmaModelPack.identity.preprocessing)
        assertTrue(artifacts.all { it.sha256.matches(Regex("[a-f0-9]{64}")) })
        assertEquals(768, GemmaModelPack.identity.dimensions)
        assertFalse(GemmaModelPack.identity.tokenizer.contains("bert"))
    }
}
