package com.example.notionreader.tooling

import com.example.notionreader.domain.discovery.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.*

/** Host-only candidate input; projection/naming/evidence rules are the shipping functions. */
object PublicGraphProjection {
    internal fun membershipThreshold(value: String): Float = value.toFloat().also {
        require(it.isFinite() && it in TopicPolicy().retention..1f) { "Host membership threshold must respect evidence floor" }
    }
    internal fun partition(report: JsonObject, ids: List<String>, nativeHash: String): List<List<Int>> {
        require(report.getValue("algorithm").jsonPrimitive.content == "faiss-hnsw-leiden-rbconfiguration")
        val identity = report.getValue("input").jsonObject.getValue("identity").jsonObject
        require(identity.getValue("nativeOpportunityManifestSha256").jsonPrimitive.content == nativeHash)
        require(report.getValue("seedOpportunities").jsonPrimitive.int == ids.size)
        val positions = ids.withIndex().associate { it.value to it.index }
        val groups = report.getValue("seedGroups").jsonArray.map { group -> group.jsonArray.map { value ->
            requireNotNull(positions[value.jsonPrimitive.content]) { "Unknown graph opportunity" }
        } }
        val all = groups.flatten()
        require(groups.all { it.isNotEmpty() } && all.size == ids.size && all.toSet() == ids.indices.toSet()) {
            "Graph partition must preserve every opportunity exactly once"
        }
        return groups
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 4..5 && args.all { it.isNotBlank() }) { "Public corpus, vectors, native input and graph report required" }
        val membership = membershipThreshold(args.getOrElse(4) { "0.60" })
        val directory = File(args[2]).canonicalFile
        val verified = PublicExactFormation.loadVerified(File(args[0]), File(args[1]), directory)
        val graphFile = File(args[3]).canonicalFile
        val graph = Json.parseToJsonElement(graphFile.readText()).jsonObject
        val clusters = partition(graph, verified.ids, verified.nativeManifestHash)
        val started = System.nanoTime()
        val prepared = verified.prepared
        val vectors = prepared.evidence.associate { it.embedded.passage.id to it.vector }
        val groups = candidateSubjectGroups(prepared.opportunities, clusters)
        val topics = mutableListOf<DiscoveredTopic>()
        val provisional = mutableListOf<DiscoveredTopic>()
        val graphHash = PublicFormationInput.hash(graphFile)
        groups.forEachIndexed { index, group ->
            val projection = prepareSubjectProjection(prepared.opportunities, group, vectors, verified.input.identity.dimensions)
            val members = linkedMapOf<String, TopicMembership>()
            prepared.evidence.forEach { e -> considerSubjectMembership(e, projectionDot(projection.center, e.vector),
                projection.anchor.passage, members, membership) }
            if (members.isNotEmpty()) {
                val candidate = nameSubjectCandidate(semanticHash("graph:$graphHash:$index"), index + 1L, projection, members.values.toList())
                if (topicEvidenceIsValid(candidate)) topics += candidate else provisional += candidate
            }
        }
        val published = publishableDiscoveryTopics(topics)
        val relationships = buildSubjectRelationships(published, verified.input.records)
        val report = buildJsonObject {
            put("schemaVersion", 1); put("algorithm", "hnsw-leiden-with-shipping-focused-candidates-and-projection")
            put("complete", true); put("seedOpportunities", verified.ids.size); put("partitionGroups", clusters.size)
            put("candidateGroups", JsonArray(groups.map { group -> JsonArray(group.map { JsonPrimitive(verified.ids[it]) }) }))
            put("parameters", buildJsonObject { put("membershipThreshold", membership); put("policyVersion", TopicPolicy().version) })
            put("input", buildJsonObject {
                put("nativeOpportunityManifestSha256", verified.nativeManifestHash)
                put("sourceVectorManifestSha256", verified.input.vectorManifestHash)
                put("graphFormationReportSha256", graphHash); put("identity", verified.input.vectorManifest.getValue("identity"))
            })
            put("hostDownstreamSeconds", (System.nanoTime() - started) / 1e9)
            put("timingScope", "Shared focused candidates, global projection, naming, publication and hierarchy; graph formation and earlier input verification/preparation excluded")
            put("subjectRelationships", Json.encodeToJsonElement(relationships))
            put("humanQualityGate", "unmeasured"); put("androidGate", "unmeasured")
            put("scope", "Host common downstream evidence; no human quality verdict or production graph adoption")
            put("cases", JsonArray((topics + provisional).map { topic -> buildJsonObject {
                put("caseId", semanticHash("graph:" + graphHash + ":" + topic.id))
                put("published", published.any { it.id == topic.id }); put("judgmentOrigin", "unrated"); put("humanJudgment", JsonNull)
                put("topic", Json.encodeToJsonElement(topic))
            } }))
        }
        val temporary = File(directory, "graph-projection.json.partial").apply { writeText(report.toString()) }
        Files.move(temporary.toPath(), File(directory, "graph-projection.json").toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        println("Host graph candidates=${groups.size}, published=${published.size}; common downstream complete; human/Android unmeasured")
    }
}
