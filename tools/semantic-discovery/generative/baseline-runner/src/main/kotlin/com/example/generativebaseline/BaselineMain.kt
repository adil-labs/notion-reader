package com.example.generativebaseline

import com.example.notionreader.data.discovery.GemmaModelPack
import com.example.notionreader.data.discovery.GemmaTokenizer
import com.example.notionreader.domain.*
import com.example.notionreader.domain.discovery.*
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

private val json = Json { prettyPrint = true }
private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "prepare <cases> <bundle> <native-library> <output>; replay <embedded-records> <output>" }
    when (args[0]) {
        "prepare" -> {
            require(args.size == 5)
            val corpusFile = File(args[1])
            val bundle = File(args[2])
            val artifact = GemmaModelPack.artifacts.single()
            require(bundle.length() == artifact.bytes)
            val digest = MessageDigest.getInstance("SHA-256")
            bundle.inputStream().buffered().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) } == artifact.sha256)
            System.setProperty("discovery.tokenizer.library", File(args[3]).absolutePath)
            val cases = json.parseToJsonElement(corpusFile.readText()).jsonObject.getValue("cases").jsonArray
            val prepared = GemmaTokenizer(bundle).use { tokenizer ->
                val chunker = SemanticChunker(tokenizer)
                cases.map { entry ->
                    val case = entry.jsonObject
                    val eligible = case.getValue("passages").jsonArray.map { it.jsonObject }
                        .filter { it.getValue("eligible").jsonPrimitive.boolean }
                    val records = eligible.groupBy { it.text("note_id") }.map { (note, passages) ->
                        val document = ReaderDocument("${case.text("id")}:$note", "", passages.map { p ->
                            ReaderSection("${p.text("id")}:section", p.text("heading"), 1,
                                listOf(ReaderChunk(p.text("id"), p.text("id"), ReaderChunkKind.PARAGRAPH, p.text("body"))))
                        })
                        val chunks = chunker.chunk(document)
                        require(chunks.map { it.sourceBlockId }.toSet() == passages.map { it.text("id") }.toSet())
                        SemanticRecord(canonicalSemanticId(document.pageId), semanticRevision(document),
                            GemmaModelPack.identity, SemanticMetadata(document.title),
                            chunks.map { EmbeddedPassage(it, emptyList()) })
                    }
                    buildJsonObject {
                        put("case_id", case.text("id")); put("records", json.encodeToJsonElement(records))
                    }
                }
            }
            File(args[4]).apply { parentFile?.mkdirs() }.writeText(json.encodeToString(buildJsonObject {
                put("stage", "prepared"); put("corpusSha256", semanticHash(corpusFile.readText()))
                put("artifact", buildJsonObject {
                    put("file", artifact.path); put("bytes", artifact.bytes); put("sha256", artifact.sha256)
                    put("instruction", GemmaModelPack.instruction)
                })
                put("identity", json.encodeToJsonElement(GemmaModelPack.identity)); put("cases", JsonArray(prepared))
            }))
            println("Prepared ${prepared.size} public cases with the shipping tokenizer/chunker and embedding identity")
        }
        "replay" -> {
            require(args.size == 3)
            val source = json.parseToJsonElement(File(args[1]).readText()).jsonObject
            require(source.text("stage") == "embedded")
            require(json.decodeFromJsonElement<EmbeddingIdentity>(source.getValue("identity")) == GemmaModelPack.identity)
            val policy = GemmaModelPack.topicPolicy
            val results = source.getValue("cases").jsonArray.map { entry ->
                val case = entry.jsonObject
                val records = json.decodeFromJsonElement<List<SemanticRecord>>(case.getValue("records"))
                require(records.all { it.identity == GemmaModelPack.identity && it.passages.all { p ->
                    p.vector.size == GemmaModelPack.identity.dimensions && p.vector.all(Float::isFinite) &&
                        p.vector.sumOf { value -> value.toDouble() * value } in .99..1.01
                } })
                val snapshot = SectionHierarchicalEngine(GemmaModelPack.identity,
                    distanceThreshold = policy.formationDistance, membershipThreshold = policy.membership)
                    .discover(records, null, policy)
                require(snapshot.complete)
                buildJsonObject {
                    put("case_id", case.text("case_id")); put("snapshot", json.encodeToJsonElement(snapshot))
                }
            }
            File(args[2]).apply { parentFile?.mkdirs() }.writeText(json.encodeToString(buildJsonObject {
                put("stage", "baseline"); put("corpusSha256", source.getValue("corpusSha256"))
                put("engineVersion", SectionHierarchicalEngine.VERSION)
                put("identity", source.getValue("identity")); put("policyVersion", policy.version)
                put("formationDistance", policy.formationDistance); put("membership", policy.membership)
                put("retention", policy.retention); put("cases", JsonArray(results))
            }))
            println("Replayed ${results.size} cases through the shipping formation engine and shipping policy")
        }
        else -> error("Unknown operation ${args[0]}")
    }
}
