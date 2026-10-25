package com.example.notionreader.data.discovery

import org.junit.Assert.*
import org.junit.Test

class LiteRtModelPackTest {
    @Test fun theShippingModelUsesThePublishedTextOnlyLiteRtBundle() {
        assertEquals("litert-community/embeddinggemma-2-text-270m-litert-lm", GemmaModelPack.repository)
        assertEquals("9be6e8b90982095dc05c2bd162e4b954ee4dbac7", GemmaModelPack.revision)
        val model = GemmaModelPack.artifacts.first()
        assertEquals("embeddinggemma-2-text-270m.litertlm", model.path)
        assertEquals(164626432L, model.bytes)
        assertEquals("2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb", model.sha256)
        assertTrue(GemmaModelPack.artifacts.none { it.path.contains("onnx") })
        assertTrue(GemmaModelPack.identity.model.startsWith("embeddinggemma-2:litert:int4:"))
        assertEquals(768, GemmaModelPack.identity.dimensions)
    }
}
