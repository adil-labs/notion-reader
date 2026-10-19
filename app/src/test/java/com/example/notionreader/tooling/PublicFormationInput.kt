package com.example.notionreader.tooling

import com.example.notionreader.domain.discovery.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Transport adapter only: all eligibility and direction preparation is shipping domain code. */
object PublicFormationInput {
    internal fun opportunityId(opportunity: FormationOpportunity) =
        semanticHash("opportunity:" + opportunity.passages.joinToString("|") { it.passage.id })
    internal fun hash(file: File): String = file.inputStream().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    internal fun checkedFile(root: File, manifest: JsonObject, name: String, checksum: String): File {
        val file = File(root, manifest.getValue(name).jsonPrimitive.content).canonicalFile
        require(file.parentFile == root.canonicalFile && hash(file) == manifest.getValue(checksum).jsonPrimitive.content) {
            "Artifact path/hash mismatch"
        }
        return file
    }
    internal data class Input(val records: List<SemanticRecord>, val identity: EmbeddingIdentity,
        val metadata: Map<String, JsonObject>, val vectorManifest: JsonObject, val vectorManifestHash: String)

    internal fun load(corpus: File, vectors: File, expectedNotes: Int = 500): Input {
        val source = Json.parseToJsonElement(File(corpus, "passage-manifest.json").readText()).jsonObject
        val path = File(vectors, "vector-manifest.json")
        val manifest = Json.parseToJsonElement(path.readText()).jsonObject
        require(manifest.getValue("complete").jsonPrimitive.boolean)
        val identity = manifest.getValue("identity").jsonObject
        require(source.getValue("notes").jsonPrimitive.int == expectedNotes)
        require(source.getValue("corpusSha256") == identity.getValue("corpusSha256"))
        val passages = File(corpus, "passages.jsonl")
        require(hash(passages) == source.getValue("passagesSha256").jsonPrimitive.content &&
            source.getValue("passagesSha256") == identity.getValue("passagesSha256"))
        val rowsFile = checkedFile(vectors, manifest, "rowsFile", "rowsSha256")
        val raw = checkedFile(vectors, manifest, "rawVectorsFile", "rawVectorsSha256")
        val rows = Json.parseToJsonElement(rowsFile.readText()).jsonArray
        val count = manifest.getValue("passages").jsonPrimitive.int
        val dimensions = identity.getValue("dimensions").jsonPrimitive.int
        require(dimensions > 0 && rows.size == count && count == source.getValue("passages").jsonPrimitive.int &&
            raw.length() == count.toLong() * dimensions * 4)
        val model = EmbeddingIdentity("public:" + identity.getValue("modelSha256").jsonPrimitive.content, dimensions)
        val metadata = linkedMapOf<String, JsonObject>()
        val families = mutableMapOf<String, String>()
        val records = mutableListOf<SemanticRecord>()
        var position = 0
        val seen = hashSetOf<String>()
        DataInputStream(raw.inputStream().buffered()).use { input -> passages.useLines { lines -> lines.forEach { line ->
            val note = Json.parseToJsonElement(line).jsonObject
            val page = note.getValue("pageId").jsonPrimitive.content
            val sourceNote = note.getValue("source").jsonObject
            require(sourceNote.getValue("kind").jsonPrimitive.content == "public" && sourceNote.getValue("revisionId").jsonPrimitive.long > 0)
            require(page == canonicalSemanticId(page) && metadata.put(page, note) == null)
            val family = note.getValue("family").jsonPrimitive.content
            val split = note.getValue("partition").jsonPrimitive.content
            require(family.isNotBlank() && split in setOf("calibration", "held_out") &&
                families.put(family, split)?.let { it == split } != false)
            val embedded = note.getValue("passages").jsonArray.map { value ->
                val passage = Json.decodeFromJsonElement<SemanticPassage>(value)
                val row = rows[position++].jsonObject
                require(seen.add(passage.id) && passage.pageId == page && row.getValue("pageId").jsonPrimitive.content == page &&
                    row.getValue("passageId").jsonPrimitive.content == passage.id && row.getValue("input").jsonPrimitive.content == passage.input &&
                    row.getValue("family") == note.getValue("family") && row.getValue("partition") == note.getValue("partition"))
                val vector = FloatArray(dimensions) { Float.fromBits(Integer.reverseBytes(input.readInt())) }
                EmbeddedPassage(passage, PackedNormalizedVector.fromNormalized(vector))
            }
            require(embedded.isNotEmpty())
            records += SemanticRecord(page, note.getValue("revision").jsonPrimitive.content, model,
                SemanticMetadata(note.getValue("title").jsonPrimitive.content), embedded)
        } } }
        require(records.size == expectedNotes && position == count)
        return Input(records, model, metadata, manifest, hash(path))
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3 && args.all { it.isNotBlank() }) { "Public corpus, vector and output directories required" }
        val input = load(File(args[0]), File(args[1]))
        val prepared = prepareFormationInput(input.records, input.identity)
        require(prepared.opportunities.isNotEmpty())
        val output = File(args[2]).canonicalFile.apply { mkdirs() }
        val temporary = File(output, "opportunities.f32.partial")
        val rows = mutableListOf<JsonElement>()
        DataOutputStream(temporary.outputStream().buffered()).use { stream ->
            for ((index, opportunity) in prepared.opportunities.withIndex()) {
                opportunity.vector.forEach { stream.writeInt(Integer.reverseBytes(it.toRawBits())) }
                val passage = opportunity.passages.first().passage
                val metadata = input.metadata.getValue(passage.pageId)
                rows += buildJsonObject {
                    put("passageId", opportunityId(opportunity))
                    put("opportunityIndex", index); put("pageId", passage.pageId); put("sectionId", passage.sectionId)
                    put("memberPassageIds", JsonArray(opportunity.passages.map { JsonPrimitive(it.passage.id) }))
                    put("input", passage.input); put("family", metadata.getValue("family")); put("partition", metadata.getValue("partition"))
                }
            }
        }
        val rawHash = hash(temporary)
        val raw = File(output, "opportunities-$rawHash.f32")
        java.nio.file.Files.move(temporary.toPath(), raw.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val rowTemporary = File(output, "opportunity-rows.json.partial").apply { writeText(JsonArray(rows).toString()) }
        val rowHash = hash(rowTemporary)
        val rowFile = File(output, "opportunity-rows-$rowHash.json")
        java.nio.file.Files.move(rowTemporary.toPath(), rowFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val manifest = buildJsonObject {
            put("complete", true); put("notes", input.records.size); put("opportunities", rows.size)
            put("eligiblePassages", prepared.evidence.size); put("authoredSections", prepared.authoredSections)
            put("formationOpportunityPolicy", "shipping:SectionHierarchicalEngine:v${SectionHierarchicalEngine.VERSION}")
            put("sourceVectorManifestSha256", input.vectorManifestHash); put("identity", input.vectorManifest.getValue("identity"))
            put("rawVectorsFile", raw.name); put("rawVectorsSha256", rawHash)
            put("rowsFile", rowFile.name); put("rowsSha256", rowHash)
            put("humanQualityGate", "unmeasured"); put("androidGate", "unmeasured")
        }
        val commit = File(output, "opportunity-manifest.json.partial").apply { writeText(manifest.toString()) }
        java.nio.file.Files.move(commit.toPath(), File(output, "opportunity-manifest.json").toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        println("Exported ${rows.size} shared shipping opportunities; ${prepared.evidence.size} eligible passages; quality/Android unmeasured")
    }
}
