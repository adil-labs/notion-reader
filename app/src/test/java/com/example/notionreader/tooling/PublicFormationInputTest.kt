package com.example.notionreader.tooling

import com.example.notionreader.domain.discovery.*
import java.io.DataOutputStream
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PublicFormationInputTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(): Pair<File, File> {
        val corpus = temporary.newFolder()
        val vectors = temporary.newFolder()
        val text = "Atomic database transactions preserve consistent records and recover committed changes after interruption."
        val passages = (0..1).map { SemanticPassage("a:p$it", "a", "a:s", "Database transactions", "a:b$it", "a:c$it",
            0, text.length, text, text, it) }
        val note = buildJsonObject {
            put("pageId", "a"); put("title", "Source"); put("revision", "r"); put("family", "systems"); put("partition", "held_out")
            put("source", buildJsonObject { put("kind", "public"); put("revisionId", 1) })
            put("passages", Json.encodeToJsonElement(passages))
        }
        val file = File(corpus, "passages.jsonl").apply { writeText(note.toString() + "\n") }
        val fingerprint = PublicFormationInput.hash(file)
        File(corpus, "passage-manifest.json").writeText(buildJsonObject {
            put("notes", 1); put("passages", 2); put("corpusSha256", "c"); put("passagesSha256", fingerprint)
        }.toString())
        val raw = File(vectors, "vectors.f32")
        DataOutputStream(raw.outputStream()).use { stream -> listOf(.6f, .8f, 0f, 1f).forEach {
            stream.writeInt(Integer.reverseBytes(it.toRawBits()))
        } }
        val rows = File(vectors, "rows.json").apply { writeText(JsonArray(passages.map { p -> buildJsonObject {
            put("pageId", "a"); put("passageId", p.id); put("input", text); put("family", "systems"); put("partition", "held_out")
        } }).toString()) }
        File(vectors, "vector-manifest.json").writeText(buildJsonObject {
            put("complete", true); put("passages", 2)
            put("identity", buildJsonObject {
                put("corpusSha256", "c"); put("passagesSha256", fingerprint); put("dimensions", 2); put("modelSha256", "m")
            })
            put("rowsFile", rows.name); put("rowsSha256", PublicFormationInput.hash(rows))
            put("rawVectorsFile", raw.name); put("rawVectorsSha256", PublicFormationInput.hash(raw))
        }.toString())
        return corpus to vectors
    }

    @Test fun jvmImportPreservesRawVectorBitsAndCanonicalPassageAlignment() {
        val (corpus, vectors) = fixture()
        val input = PublicFormationInput.load(corpus, vectors, expectedNotes = 1)
        val passages = input.records.single().passages
        assertEquals(listOf("a:p0", "a:p1"), passages.map { it.passage.id })
        assertEquals(.6f.toRawBits(), passages[0].vector[0].toRawBits())
        assertEquals(.8f.toRawBits(), passages[0].vector[1].toRawBits())
        assertEquals(0f.toRawBits(), passages[1].vector[0].toRawBits())
        assertEquals(2, prepareFormationInput(input.records, input.identity).opportunities.size)
    }

    @Test fun corruptPayloadAndSemanticallyReorderedRowsAreRejected() {
        val (corpus, vectors) = fixture()
        val manifestFile = File(vectors, "vector-manifest.json")
        val rowsFile = File(vectors, "rows.json")
        val rows = Json.parseToJsonElement(rowsFile.readText()).jsonArray
        rowsFile.writeText(JsonArray(rows.reversed()).toString())
        val manifest = Json.parseToJsonElement(manifestFile.readText()).jsonObject
        manifestFile.writeText(JsonObject(manifest + ("rowsSha256" to JsonPrimitive(PublicFormationInput.hash(rowsFile)))).toString())
        try { PublicFormationInput.load(corpus, vectors, 1); fail("Wrong passage alignment must fail even with a valid row-file hash") }
        catch (_: IllegalArgumentException) { }
        rowsFile.writeText(JsonArray(rows).toString())
        manifestFile.writeText(manifest.toString())
        File(vectors, "vectors.f32").writeText("corrupt")
        try { PublicFormationInput.load(corpus, vectors, 1); fail("Corrupt vectors must fail") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun theExportCommandCannotPublishAPartialCorpusAsAFullComparisonInput() {
        val (corpus, vectors) = fixture()
        val output = File(temporary.root, "not-published")
        try { PublicFormationInput.main(arrayOf(corpus.path, vectors.path, output.path)); fail("500 Notes required") }
        catch (_: IllegalArgumentException) { }
        assertFalse(output.exists())
        try { PublicExactFormation.main(arrayOf(corpus.path, vectors.path, output.path)); fail("Exact comparison also requires 500 Notes") }
        catch (_: IllegalArgumentException) { }
        assertFalse(output.exists())
    }
}
