package com.example.notionreader.tooling

import com.example.notionreader.domain.ReaderChunkKind
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class PublicCorpusInputTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun corpusFingerprintMatchesThePythonFreezeFormatIncludingUnicodeAndNulls() {
        val value = buildJsonObject {
            put("z", "Café 🌱 — coast\n")
            put("a", buildJsonArray { add(1); add(true); add(JsonNull) })
        }
        assertEquals("fb34a4f71cba97bee348262494cff4545ea53d80e3c54315206de780207f5045",
            PublicCorpusInput.fingerprint(value))
    }

    @Test fun partialCorpusCannotProduceAnExportThatLooksComplete() {
        val folder = temporary.newFolder()
        val manifest = buildJsonObject { put("notes", 1) }
        File(folder, "corpus.json").writeText(buildJsonObject {
            put("manifest", manifest); put("notes", JsonArray(listOf(note())))
        }.toString())
        val lock = File(folder, "lock.json").apply { writeText(manifest.toString()) }
        try {
            PublicCorpusInput.main(arrayOf(folder.path, lock.path, "unneeded-vocabulary"))
            fail("Partial corpus must be rejected before any output")
        } catch (failure: IllegalArgumentException) {
            assertTrue(failure.message.orEmpty().contains("500-Note"))
        }
        assertFalse(File(folder, "passages.jsonl").exists())
        assertFalse(File(folder, "passage-manifest.json").exists())
    }
    private fun note() = Json.parseToJsonElement("""{
      "id":"public-a","title":"Source title","family":"ecology","partition":"held_out",
      "judgmentOrigin":"unrated","source":{"kind":"public","pageId":1,"revisionId":99,"license":"CC-BY-SA-4.0"},
      "sections":[{"title":"Body","paragraphs":[{"ordinal":3,"text":"Literal source paragraph."}]},
                  {"title":"Later section","paragraphs":[{"ordinal":7,"text":"A separate literal paragraph."}]}]
    }""").jsonObject

    @Test fun publicParagraphsKeepSectionContextWithoutInventingHeadingBodyEvidence() {
        val document = PublicCorpusInput.document(note())
        assertEquals("Source title", document.title)
        assertEquals(listOf("Body", "Later section"), document.sections.map { it.title })
        assertEquals(listOf("Literal source paragraph.", "A separate literal paragraph."), document.chunkTexts)
        assertTrue(document.chunks.all { it.kind == ReaderChunkKind.PARAGRAPH })
        assertTrue(document.chunks.all { it.sourceBlockId != document.sections.single { s -> it in s.chunks }.id })
        assertTrue(document.chunks.first().id.endsWith(":p3"))
        assertTrue(document.chunks.last().id.endsWith(":p7"))
    }

    @Test fun aChangedLiteralPassageChangesTheCorpusFingerprint() {
        val original = JsonArray(listOf(note()))
        val changed = JsonArray(listOf(JsonObject(note() + ("title" to JsonPrimitive("Changed")))))
        assertNotEquals(PublicCorpusInput.fingerprint(original), PublicCorpusInput.fingerprint(changed))
        val reordered = JsonArray(listOf(JsonObject(note().entries.reversed().associate { it.toPair() })))
        assertEquals(PublicCorpusInput.fingerprint(original), PublicCorpusInput.fingerprint(reordered))
    }

    @Test fun privateOrUnpinnedSourcesCannotEnterThePublicExporter() {
        for (source in listOf(buildJsonObject { put("kind", "private"); put("revisionId", 99) },
            buildJsonObject { put("kind", "public") })) {
            try {
                PublicCorpusInput.document(JsonObject(note() + ("source" to source)))
                fail("Public source provenance is required")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
