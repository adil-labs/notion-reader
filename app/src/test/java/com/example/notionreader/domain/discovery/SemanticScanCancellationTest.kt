package com.example.notionreader.domain.discovery

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SemanticScanCancellationTest {
    @Test fun passageAdmissionRunsBeforeRankingWithoutMaterializingACopy() {
        val model = EmbeddingIdentity("admission", 2)
        val text = "Forest ecosystems protect woodland habitat and support diverse animal populations."
        val body = SemanticPassage("body", "note", "section", "Ecology", "body-block", "body-chunk", 0, text.length, text, text, 1)
        val heading = body.copy(id = "heading", sourceBlockId = "section", order = 0)
        val record = SemanticRecord("note", "r", model, SemanticMetadata("Note"), listOf(
            EmbeddedPassage(heading, listOf(1f, 0f)), EmbeddedPassage(body, listOf(.8f, .6f))))
        val result = rankSemanticRecords(listOf(1f, 0f), model, sequenceOf(record), passageFilter = { it.isBodyEvidence() })
        assertEquals("body", result.single().passage.id)
    }

    @Test fun cancellationInterruptsOneLongNoteWithoutConsumingItsRemainingPassages() {
        var reads = 0
        val passages = object : AbstractList<EmbeddedPassage>() {
            override val size = 10_000
            override fun get(index: Int): EmbeddedPassage {
                reads++
                val text = "Forest ecosystems protect woodland habitat and support diverse animal populations."
                val p = SemanticPassage("p$index", "note", "s", "Ecology", "b$index", "c$index", 0, text.length, text, text, index)
                return EmbeddedPassage(p, listOf(1f, 0f))
            }
        }
        val model = EmbeddingIdentity("scan-cancellation", 2)
        val record = SemanticRecord("note", "r", model, SemanticMetadata("Note"), passages)
        try {
            rankSemanticRecords(listOf(1f, 0f), model, sequenceOf(record), checkActive = {
                if (reads >= 64) throw CancellationException("Pause the scan")
            })
            fail("Cancellation must propagate rather than finish or publish partial matches")
        } catch (_: CancellationException) {
            assertTrue("Scoring must check cancellation within 64 further passages; read $reads", reads <= 65)
        }
    }
}
