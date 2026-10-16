package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test

class TopicLabelTest {
    @Test fun commonLiteralPassagePhrasesDisambiguateGroupsWhoseSourceTitlesAreTheSame() {
        val identity = EmbeddingIdentity("label-test", 2)
        val texts = listOf("Frozen queue membership stays stable after playback starts.",
            "Frozen queue membership is independent of later source edits.",
            "Frozen queue membership survives navigation and restoration.")
        val records = texts.mapIndexed { i, text ->
            val p = SemanticPassage("p$i", "n$i", "s$i", null, "b$i", "c$i", 0, text.length, text, text, 0)
            SemanticRecord("n$i", "r$i", identity, SemanticMetadata("Shared document title"), listOf(EmbeddedPassage(p, listOf(1f, 0f))))
        }
        val topic = TopicBuilder(null, identity).apply { records.forEach(::accept) }.finish().topics.single()
        assertFalse(topic.label.startsWith("Notes related to "))
        assertTrue(texts.count { it.contains(topic.label, ignoreCase = true) } >= 2)
        assertEquals("p0", topic.labelSource.id)
    }
}
