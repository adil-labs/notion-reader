package com.example.notionreader.tooling

import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.domain.discovery.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Explicit local review export. Never an automatic human quality verdict. */
object DiscoveryEngineReview {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 1 && args[0].isNotBlank()) { "Explicit private snapshot required" }
        val root = File(args[0]).canonicalFile
        val permitted = listOf("app/build/private-discovery-review", "build/private-discovery-review")
            .map { File(it).canonicalFile.toPath() }
        require(permitted.any { root.toPath().startsWith(it) }) { "Private review must stay under ignored build storage" }
        require(File(root, "snapshot.json").isFile) { "Original snapshot manifest required" }
        val cache = FileDocumentCache(File(root, "no_backup/document-cache"))
        val sources = cache.snapshot().associate { canonicalSemanticId(it.document.pageId) to semanticRevision(it.document) }
        val index = FileSemanticIndexStore(File(root, "no_backup/semantic-index"))
        val records = index.pageIds().sorted().mapNotNull { index.read(it) }
            .filter { sources[canonicalSemanticId(it.pageId)] == it.revision }
        require(records.isNotEmpty()) { "No cache-current records" }
        val identity = records.first().identity
        require(records.all { it.identity == identity }) { "Mixed model identities" }
        val fingerprint = semanticHash(identity.toString() + records.joinToString("|") { record ->
            canonicalSemanticId(record.pageId) + ":" + record.revision + ":" + record.passages.joinToString(",") {
                it.passage.id + ":" + it.vector.hashCode()
            }
        })
        val output = File(root, "review-${System.currentTimeMillis()}").apply { mkdirs() }
        val json = Json { prettyPrint = true; encodeDefaults = true }
        val policy = TopicPolicy()
        val name = "sectionHierarchical"
        val engine = SectionHierarchicalEngine(identity)
        run {
            val started = System.nanoTime()
            val snapshot = engine.discover(records, null, policy)
            val elapsedMs = (System.nanoTime() - started) / 1e6
            val published = publishableDiscoveryTopics(snapshot.topics)
            File(output, "$name.json").writeText(json.encodeToString(buildJsonObject {
                put("engine", name); put("corpusFingerprint", fingerprint)
                put("environment", "Host JVM; not Android performance or human quality acceptance")
                put("exposure", "Previously exposed private corpus; no held-out claim")
                put("humanQualityGate", "unmeasured"); put("androidGate", "unmeasured")
                put("notes", records.size); put("passages", records.sumOf { it.passages.size })
                put("hostElapsedMs", elapsedMs); put("groupingComplete", snapshot.complete)
                put("publishedCount", published.size)
                put("cases", JsonArray((snapshot.topics + snapshot.provisionalTopics).map { topic -> buildJsonObject {
                    put("caseId", semanticHash("$name:${topic.id}").take(16)); put("kind", "topic")
                    put("partition", "previouslyExposedReview"); put("humanJudgment", JsonNull)
                    put("content", buildJsonObject {
                        put("label", topic.label); put("availableCount", topic.members.size)
                        put("memberPageIds", JsonArray(topic.members.map { JsonPrimitive(canonicalSemanticId(it.pageId)) }))
                        put("members", JsonArray(topic.members.map { member -> buildJsonObject {
                            put("pageId", member.pageId); put("title", member.metadata.title)
                            put("section", member.passage.sectionTitle ?: "Body")
                            put("excerpt", member.passage.excerpt); put("passageId", member.passage.id)
                        } }))
                    })
                } }))
                put("candidates", JsonArray((snapshot.topics + snapshot.provisionalTopics).map { topic -> buildJsonObject {
                    put("topic", json.encodeToJsonElement(topic))
                    put("published", topic.id in published.map { it.id }.toSet())
                    put("judgmentOrigin", JsonNull); put("reviewerConfirmed", false); put("decision", JsonNull)
                    put("reviewDimensions", JsonArray(listOf("label", "membership", "usefulness", "duplicate", "referenceOnly")
                        .map(::JsonPrimitive)))
                } }))
                put("missedSubjects", JsonArray(emptyList()))
                put("missedSubjectReview", "Inspect all source sections independently; candidate absence does not establish recall")
            }))
            println("$name: candidates=${snapshot.topics.size + snapshot.provisionalTopics.size}, published=${published.size}, complete=${snapshot.complete}, hostMs=$elapsedMs")
        }
        println("Private sole-engine evidence saved under $output")
    }
}
