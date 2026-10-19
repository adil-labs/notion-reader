package com.example.notionreader.tooling

import com.example.notionreader.data.discovery.BertWordPieceTokenizer
import com.example.notionreader.domain.*
import com.example.notionreader.domain.discovery.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Public-only, explicit host tooling. Uses the shipping tokenizer and chunker. */
object PublicCorpusInput {
    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") {
            JsonPrimitive(it.key).toString() + ":" + canonical(it.value)
        }
        is JsonArray -> value.joinToString(",", "[", "]", transform = ::canonical)
        else -> value.toString()
    }

    internal fun fingerprint(value: JsonElement): String = semanticHash(canonical(value))

    internal fun document(note: JsonObject): ReaderDocument {
        val source = note.getValue("source").jsonObject
        require(source["kind"]?.jsonPrimitive?.content == "public" &&
            (source["revisionId"]?.jsonPrimitive?.longOrNull ?: 0) > 0 &&
            !source["license"]?.jsonPrimitive?.content.isNullOrBlank()) { "Pinned public source provenance required" }
        val id = note.getValue("id").jsonPrimitive.content
        require(id.isNotBlank())
        val sections = note.getValue("sections").jsonArray.mapIndexed { index, value ->
            val section = value.jsonObject
            val sectionId = "$id:s$index"
            ReaderSection(sectionId, section.getValue("title").jsonPrimitive.content, 1,
                section.getValue("paragraphs").jsonArray.map { paragraph ->
                    val p = paragraph.jsonObject
                    val block = "$sectionId:p${p.getValue("ordinal").jsonPrimitive.int}"
                    val text = p.getValue("text").jsonPrimitive.content
                    require(text.isNotBlank())
                    ReaderChunk(block, block, ReaderChunkKind.PARAGRAPH, text)
                })
        }
        require(sections.isNotEmpty() && sections.all { it.chunks.isNotEmpty() })
        return ReaderDocument(id, note.getValue("title").jsonPrimitive.content, sections)
    }

    private fun fileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3 && args.all { it.isNotBlank() }) { "Corpus directory, external lock and vocabulary paths required" }
        val directory = File(args[0]).canonicalFile
        val root = Json.parseToJsonElement(File(directory, "corpus.json").readText()).jsonObject
        val lock = Json.parseToJsonElement(File(args[1]).readText()).jsonObject
        require(root.getValue("manifest") == lock) { "External corpus lock mismatch" }
        val notes = root.getValue("notes").jsonArray
        require(notes.size == 500 && lock.getValue("notes").jsonPrimitive.int == 500) { "Complete 500-Note corpus required" }
        require(lock.getValue("humanJudgments").jsonPrimitive.int == 0 &&
            lock.getValue("humanQualityGate").jsonPrimitive.content == "unmeasured")
        require(fingerprint(notes) == lock.getValue("corpusSha256").jsonPrimitive.content) { "Corpus content fingerprint mismatch" }
        require(notes.map { it.jsonObject.getValue("id").jsonPrimitive.content }.distinct().size == 500)
        val families = mutableMapOf<String, String>()
        for (value in notes) {
            val note = value.jsonObject
            require(note.getValue("judgmentOrigin").jsonPrimitive.content == "unrated")
            val family = note.getValue("family").jsonPrimitive.content
            val split = note.getValue("partition").jsonPrimitive.content
            require(split in setOf("calibration", "held_out"))
            require(families.put(family, split)?.let { it == split } != false) { "Family partition leakage" }
            document(note)
        }
        val vocabulary = File(args[2]).canonicalFile
        val tokenizer = BertWordPieceTokenizer(vocabulary.readLines())
        val chunker = SemanticChunker(tokenizer, 256)
        val temporary = File(directory, "passages.jsonl.partial")
        var passages = 0
        var sections = 0
        temporary.bufferedWriter().use { output ->
            for (value in notes) {
                val note = value.jsonObject
                val document = document(note)
                val chunks = chunker.chunk(document)
                passages += chunks.size; sections += document.sections.size
                val line = buildJsonObject {
                    put("pageId", document.pageId); put("title", document.title)
                    put("revision", semanticRevision(document)); put("source", note.getValue("source"))
                    put("family", note.getValue("family")); put("partition", note.getValue("partition"))
                    put("passages", Json.encodeToJsonElement(chunks))
                    put("tokenIds", buildJsonArray { chunks.forEach { passage ->
                        add(buildJsonArray {
                            add(tokenizer.classificationId)
                            tokenizer.tokens(passage.input).forEach { add(it.id) }
                            add(tokenizer.separatorId)
                        })
                    } })
                }
                output.appendLine(line.toString())
            }
        }
        val hash = fileHash(temporary)
        Files.move(temporary.toPath(), File(directory, "passages.jsonl").toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        val manifest = buildJsonObject {
            put("schemaVersion", 1); put("corpusSha256", lock.getValue("corpusSha256"))
            put("passagesSha256", hash); put("vocabularySha256", fileHash(vocabulary))
            put("notes", 500); put("sections", sections); put("passages", passages)
            put("tokenizer", "shipping:BertWordPieceTokenizer"); put("chunker", "shipping:SemanticChunker")
            put("maxTokens", 256); put("humanQualityGate", "unmeasured"); put("androidGate", "unmeasured")
        }
        File(directory, "passage-manifest.json").writeText(manifest.toString())
        println("Exported 500 public Notes / $sections sections / $passages shipping passages; human and Android gates unmeasured")
    }
}
