package com.example.notionreader.tooling

import com.example.notionreader.domain.discovery.*
import com.example.notionreader.data.discovery.GemmaModelPack
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Observes the shipping exact formation phase and retains actual downstream review evidence. */
object PublicExactFormation {
    internal data class VerifiedInput(val input: PublicFormationInput.Input, val prepared: PreparedFormationInput,
        val ids: List<String>, val nativeManifestHash: String, val vectorHash: String)

    internal fun loadVerified(corpus: File, vectors: File, native: File): VerifiedInput {
        val input = PublicFormationInput.load(corpus, vectors)
        val directory = native.canonicalFile
        val path = File(directory, "opportunity-manifest.json")
        val manifest = Json.parseToJsonElement(path.readText()).jsonObject
        require(manifest.getValue("complete").jsonPrimitive.boolean && manifest.getValue("notes").jsonPrimitive.int == 500)
        require(manifest.getValue("sourceVectorManifestSha256").jsonPrimitive.content == input.vectorManifestHash &&
            manifest.getValue("formationOpportunityPolicy").jsonPrimitive.content == "shipping:SectionHierarchicalEngine:v${SectionHierarchicalEngine.VERSION}")
        val prepared = prepareFormationInput(input.records, input.identity)
        require(manifest.getValue("opportunities").jsonPrimitive.int == prepared.opportunities.size &&
            manifest.getValue("eligiblePassages").jsonPrimitive.int == prepared.evidence.size)
        val rowsFile = PublicFormationInput.checkedFile(directory, manifest, "rowsFile", "rowsSha256")
        PublicFormationInput.checkedFile(directory, manifest, "rawVectorsFile", "rawVectorsSha256")
        val rows = Json.parseToJsonElement(rowsFile.readText()).jsonArray
        val ids = prepared.opportunities.map(PublicFormationInput::opportunityId)
        require(rows.size == ids.size && ids.distinct().size == ids.size)
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteBuffer.allocate(input.identity.dimensions * 4).order(ByteOrder.LITTLE_ENDIAN)
        prepared.opportunities.forEachIndexed { i, opportunity ->
            val row = rows[i].jsonObject
            require(row.getValue("passageId").jsonPrimitive.content == ids[i] && row.getValue("opportunityIndex").jsonPrimitive.int == i &&
                row.getValue("memberPassageIds") == JsonArray(opportunity.passages.map { JsonPrimitive(it.passage.id) }))
            bytes.clear(); opportunity.vector.forEach { bytes.putFloat(it) }; digest.update(bytes.array())
        }
        val vectorHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(vectorHash == manifest.getValue("rawVectorsSha256").jsonPrimitive.content) { "Native graph opportunities differ from exact shipping inputs" }
        return VerifiedInput(input, prepared, ids, PublicFormationInput.hash(path), vectorHash)
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 3..5 && args.all { it.isNotBlank() }) { "Public corpus, vector and native opportunity directories, optional distance/membership required" }
        val distance = args.getOrElse(3) { "0.35" }.toFloat().also { require(it.isFinite() && it in 0f..2f) }
        val membership = args.getOrElse(4) { "0.60" }.toFloat().also { require(it.isFinite() && it in 0f..1f) }
        val directory = File(args[2]).canonicalFile
        val verified = loadVerified(File(args[0]), File(args[1]), directory)
        val input = verified.input
        val ids = verified.ids
        val vectorHash = verified.vectorHash
        val started = System.nanoTime()
        var groupingSeconds = 0.0
        var observed: ExactSeedFormation? = null
        val policy = if (input.identity.model == "public:" + GemmaModelPack.artifacts.first().sha256)
            GemmaModelPack.topicPolicy else TopicPolicy()
        val bounded = System.getProperty("discovery.formationBounds", "true").toBooleanStrict()
        val engine = SectionHierarchicalEngine(input.identity, distanceThreshold = distance, membershipThreshold = membership,
            metadataBoundsEnabled = bounded, formationBoundsEnabled = bounded)
        val snapshot = engine.evaluate(input.records, policy, onFormation = {
                groupingSeconds = (System.nanoTime() - started) / 1e9
                observed = it
            })
        val formation = checkNotNull(observed)
        check(formation.opportunities == ids.size && formation.groups.flatten().toSet() == ids.indices.toSet())
        val published = publishableDiscoveryTopics(snapshot.topics).map { it.id }.toSet()
        val report = buildJsonObject {
            put("schemaVersion", 1); put("algorithm", "shipping-exact-average-linkage-with-focused-candidates")
            put("seedOpportunities", ids.size)
            put("seedGroups", JsonArray(formation.groups.map { group -> JsonArray(group.map { JsonPrimitive(ids[it]) }) }))
            put("groupsMayOverlap", true); put("comparisons", formation.comparisons)
            put("metadataPrunedProjectionComparisons", engine.prunedProjectionComparisons)
            put("avoidedFormationComponents", engine.avoidedFormationComponents)
            put("conservativeBoundsEnabled", bounded)
            put("hostFormationSeconds", groupingSeconds); put("hostTotalSeconds", (System.nanoTime() - started) / 1e9)
            put("timingScope", "Formation includes shipping preparation and focused candidates; total also includes shipping downstream projection/publication")
            put("parameters", buildJsonObject {
                put("distanceThreshold", distance); put("membershipThreshold", membership); put("policyVersion", policy.version)
                put("engineVersion", SectionHierarchicalEngine.VERSION)
            })
            put("input", buildJsonObject {
                put("sourceVectorManifestSha256", input.vectorManifestHash)
                put("nativeOpportunityManifestSha256", verified.nativeManifestHash)
                put("rawPreparedVectorsSha256", vectorHash); put("identity", input.vectorManifest.getValue("identity"))
            })
            put("complete", snapshot.complete); put("humanQualityGate", "unmeasured"); put("androidGate", "unmeasured")
            put("subjectRelationships", Json.encodeToJsonElement(snapshot.subjectRelationships))
            put("scope", "Host exact seed formation plus shipping downstream evidence; no algorithm winner or human quality claim")
            put("cases", JsonArray((snapshot.topics + snapshot.provisionalTopics).map { topic -> buildJsonObject {
                put("caseId", semanticHash("exact:" + input.vectorManifestHash + ":" + topic.anchor.passage.id + ":" +
                    topic.members.joinToString("|") { it.passage.id }))
                put("published", topic.id in published); put("judgmentOrigin", "unrated"); put("humanJudgment", JsonNull)
                put("topic", Json.encodeToJsonElement(topic))
            } }))
        }
        val suffix = if (bounded) "" else "-exhaustive"
        val reportName = if (args.size == 3) "exact-formation$suffix.json" else "exact-formation-d$distance-m$membership$suffix.json"
        val temporary = File(directory, "$reportName.partial").apply { writeText(report.toString()) }
        Files.move(temporary.toPath(), File(directory, reportName).toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        println("Host exact opportunities=${ids.size}, candidateGroups=${formation.groups.size}, complete=${snapshot.complete}; human/Android unmeasured")
    }
}
