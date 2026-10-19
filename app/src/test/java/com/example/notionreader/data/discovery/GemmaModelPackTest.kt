package com.example.notionreader.data.discovery

import org.junit.Assert.*
import org.junit.Test

class GemmaModelPackTest {
    @Test fun externalWeightsAreDownloadedBesideTheirPinnedGraph() {
        val artifacts = GemmaModelPack.artifacts
        assertEquals(206696682L, artifacts.sumOf { it.bytes })
        assertEquals(5, artifacts.size)
        assertEquals("onnx/model_q4.onnx_data", artifacts.single { it.path == "model_q4.onnx_data" }.remotePath)
        assertEquals("onnx/model_q4.onnx", artifacts.single { it.path == "model_q4.onnx" }.remotePath)
        assertEquals("model_q4.onnx", GemmaModelPack.graph)
        assertEquals("f9eeba97acddf139b8ee2ddf04bc30dceafa88de93fadf74d7644e0d61a477a9", artifacts[0].sha256)
        assertEquals("c3975f2d1ab7a1878ae31a7d7a9b7804a827aff3800b60dfceafce21cac3df49", artifacts[1].sha256)
        assertTrue(GemmaModelPack.identity.model.startsWith("embeddinggemma-2:int4:"))
        assertTrue(GemmaModelPack.description.startsWith("EmbeddingGemma 2 INT4"))
        assertEquals("tokenizer.json", artifacts.single { it.path == "tokenizer.json" }.remotePath)
        assertTrue(artifacts.all { it.sha256.matches(Regex("[a-f0-9]{64}")) })
        assertEquals(768, GemmaModelPack.identity.dimensions)
        assertFalse(GemmaModelPack.identity.tokenizer.contains("bert"))
    }
}
