package com.example.notionreader.tooling

import com.example.notionreader.application.TopicCoordinator
import com.example.notionreader.data.local.*
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

/** Explicit developer task only, not a JUnit test or application runtime. All content stays in ignored storage. */
object PrivateDiscoveryReview {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size in 1..3 && args[0].isNotBlank()) { "Pass -PdiscoveryReviewDirectory=app/build/private-discovery-review/<snapshot>" }
        val roundLimit = args.getOrNull(1)?.toInt() ?: 32
        val secondsLimit = args.getOrNull(2)?.toLong() ?: 60
        require(roundLimit in 1..4096 && secondsLimit in 1..1800) { "Replay budgets must be 1–4096 rounds and 1–1800 seconds" }
        val root = File(args[0]).canonicalFile
        require(root.toPath().startsWith(File("app/build/private-discovery-review").canonicalFile.toPath()) ||
            root.toPath().startsWith(File("build/private-discovery-review").canonicalFile.toPath())) { "Private data must stay under ignored build storage" }
        require(File(root, "snapshot.json").isFile) { "Explicit private snapshot required" }
        val original = File(root, "no_backup")
        val work = File(root, "replay-${System.currentTimeMillis()}")
        original.copyRecursively(work)
        val indexDir = File(work, "semantic-index"); val topicDir = File(work, "discovery-topics"); val checkpointDir = File(work, "discovery-topic-checkpoint")
        fun bytes(dir: File) = dir.listFiles()?.sumOf { it.length() } ?: 0L
        val index = FileSemanticIndexStore(indexDir)
        val cache = FileDocumentCache(File(work, "document-cache"))
        val documents = cache.snapshot()
        val sources = documents.associate { canonicalSemanticId(it.document.pageId) to semanticRevision(it.document) }
        val cachedTitles = documents.associate { canonicalSemanticId(it.document.pageId) to it.document.title }
        val records = index.pageIds().sorted().mapNotNull { index.read(it) }.filter { sources[it.pageId] == it.revision }
        require(records.isNotEmpty()) { "No cache-current vector records in snapshot" }
        val model = records.first().identity
        require(records.all { it.identity == model })
        val initial = FileTopicStore(topicDir).read() ?: TopicSnapshot()
        val publishedTopics = publishableDiscoveryTopics(initial.topics)
        val reviewFile = File(root, "review.json")
        if (!reviewFile.exists()) {
            val cases = mutableListOf<JsonObject>()
            fun evidence(m: TopicMembership) = buildJsonObject {
                put("pageId", m.pageId); put("title", m.metadata.title); put("section", m.passage.sectionTitle)
                put("excerpt", m.passage.excerpt); put("passageId", m.passage.id)
            }
            fun add(kind: String, stableId: String, content: JsonObject) {
                cases += buildJsonObject {
                    put("caseId", semanticHash("$kind:$stableId").take(12)); put("kind", kind)
                    put("partition", "privateReviewPendingExposureAudit"); put("humanJudgment", JsonNull); put("assistantAssessment", JsonNull)
                    put("content", content)
                }
            }
            val topicChoices = publishedTopics.distinctBy { it.labelSource.pageId }.take(6).toMutableList()
            publishedTopics.filter { it !in topicChoices }.take(6 - topicChoices.size).forEach(topicChoices::add)
            topicChoices.forEach { t -> add("topic", t.id, buildJsonObject {
                val memberIds = t.members.map { canonicalSemanticId(it.pageId) }.distinct().sorted()
                put("label", t.label); put("topicId", t.id); put("availableCount", memberIds.size)
                put("memberPageIds", JsonArray(memberIds.map(::JsonPrimitive)))
                put("members", JsonArray(t.members.take(6).map(::evidence)))
            }) }
            initial.connections.take(8).forEach { p -> add("connection", p.id, buildJsonObject {
                put("left", evidence(p.left)); put("right", evidence(p.right)); put("cosine", p.score)
                put("evidenceKind", classifyConnectionEvidence(p, cachedTitles).kind.name)
            }) }
            val displayedPairs = initial.connections.map { setOf(canonicalSemanticId(it.left.pageId), canonicalSemanticId(it.right.pageId)) }.toSet()
            val eligible = records.map { it.copy(passages = it.passages.filter { p -> p.passage.isBodyEvidence() }) }
            val missed = mutableMapOf<String, NoteConnection>()
            for (record in eligible) for (seed in record.passages.distinctBy { it.passage.sectionId }.take(8)) {
                val matches = rankSemanticRecords(seed.vector, model, eligible.asSequence(), limit = 5,
                    excludePageId = record.pageId, threshold = TopicPolicy().connection)
                matches.forEach { match ->
                    if (setOf(record.pageId, match.pageId) !in displayedPairs) {
                        val pairId = semanticHash(listOf(record.pageId, match.pageId).sorted().joinToString("|"))
                        val left = TopicMembership(record.pageId, record.metadata, seed.passage, match.score, match.passage)
                        val right = TopicMembership(match.pageId, match.metadata, match.passage, match.score, seed.passage)
                        val pair = NoteConnection(pairId, left, right, match.score)
                        if (pair.score > (missed[pairId]?.score ?: -1f)) missed[pairId] = pair
                    }
                }
            }
            selectDiscoveryConnections(missed.values, limit = 20 - cases.size.coerceAtMost(20)).forEach { p ->
                add("potentialMissedConnection", p.id, buildJsonObject {
                    put("left", evidence(p.left)); put("right", evidence(p.right)); put("cosine", p.score)
                    put("evidenceKind", classifyConnectionEvidence(p, cachedTitles).kind.name)
                    put("caution", "Candidate for judgment; absence from the feed is not proof of a missed useful connection.")
                })
            }
            reviewFile.writeText(json.encodeToString(buildJsonObject {
                put("privacy", "PRIVATE LOCAL REVIEW. Never commit/upload. No automatic human precision score.")
                put("policyVersion", TopicPolicy().version); put("notes", records.size)
                put("connectionEvidencePolicyVersion", CONNECTION_EVIDENCE_POLICY_VERSION)
                put("calibration", "Public synthetic corpus only; private cases are frozen local reviews. Audit prior exposure before any held-out claim.")
                put("cases", JsonArray(cases))
            }))
        }
        val failures = mutableListOf<JsonObject>()
        fun traced(label: String, delegate: TopicStore): TopicStore = object : TopicStore by delegate {
            override suspend fun writeDetailed(snapshot: TopicSnapshot): TopicWriteResult {
                val result = delegate.writeDetailed(snapshot)
                if (result is TopicWriteResult.Rejected) {
                    failures += buildJsonObject {
                        put("stage", label); put("failure", result.failure.name); put("reason", result.validationReason?.name)
                        put("topics", snapshot.topics.size); put("uniqueTopicIds", snapshot.topics.map { it.id }.distinct().size)
                        put("continuityBytes", json.encodeToString(snapshot.continuity).toByteArray().size)
                    }
                    File(work, "rejected-$label.json").writeText(json.encodeToString(snapshot))
                }
                return result
            }
        }
        val topics = traced("topics", FileTopicStore(topicDir, otherBytes = { bytes(indexDir) + bytes(checkpointDir) }))
        val checkpoint = traced("checkpoint", FileTopicStore(checkpointDir, otherBytes = { bytes(indexDir) + bytes(topicDir) }))
        index.setPaused(false)
        val coordinator = TopicCoordinator(cache, index, topics, checkpoint, { model }, TopicPolicy(), Mutex(),
            { failures.isEmpty() }, {}, {})
        coordinator.invalidate(sources)
        val rounds = mutableListOf<JsonObject>(); val started = System.nanoTime()
        // New shipping signatures use cache-eligible persisted metadata, not Browse list order.
        // Exact signature agreement is required before calling this an unchanged-input resume.
        val metadataComplete = records.map { it.pageId }.toSet() == sources.keys
        val replayMetadataVersion = semanticMetadataHash(records.associate { it.pageId to it.metadata })
        val replaySignature = semanticHash(model.toString() + TopicPolicy().toString() + replayMetadataVersion + sources.toSortedMap().toString())
        val inputSignatureMatches = metadataComplete && initial.sourceSignature == replaySignature
        var stopReason = "roundBudget"
        for (round in 0 until roundLimit) {
            if (round % 16 == 0) coordinator.resetWorkBudget()
            val before = topics.read()
            val begin = System.nanoTime()
            coordinator.build(sources, replayMetadataVersion, cache.revision.value)
            val elapsedMs = (System.nanoTime() - begin) / 1e6
            val after = topics.read()
            val savedWork = checkpoint.read()
            rounds += buildJsonObject {
                put("round", round); put("elapsedMs", elapsedMs); put("needsMoreWork", coordinator.needsMoreWork)
                put("stage", when {
                    after?.generation != before?.generation && after?.groupingPass != before?.groupingPass -> "groupingAndConnections"
                    after?.generation != before?.generation -> "connections"
                    savedWork != null -> "checkpointOnly"
                    else -> "noPublication"
                })
                put("generation", after?.generation); put("groupingPass", after?.groupingPass)
                put("candidatePassLimit", after?.candidatePassLimit); put("connectionCursor", after?.connectionCursor)
                put("connectionPassComplete", after?.connectionPassComplete ?: false)
                put("candidateTopics", after?.topics?.size ?: 0)
                put("publishedTopics", publishableDiscoveryTopics(after?.topics.orEmpty()).size)
                put("checkpointAcceptedNotes", savedWork?.acceptedRevisions?.size ?: 0)
                put("checkpointPendingNotes", savedWork?.pendingRevisions?.size ?: 0)
            }
            if ((round + 1) % 16 == 0) println("Replay progress: rounds=${round + 1}, groupingPass=${after?.groupingPass}, moreWork=${coordinator.needsMoreWork}")
            stopReason = when {
                failures.isNotEmpty() -> "persistenceFailure"
                !coordinator.needsMoreWork -> "workerStopped"
                System.nanoTime() - started > secondsLimit * 1_000_000_000L -> "timeBudget"
                else -> "roundBudget"
            }
            if (stopReason != "roundBudget") break
        }
        val finalSnapshot = topics.read()
        File(work, "replay-report.json").writeText(json.encodeToString(buildJsonObject {
            put("environment", "Host JVM replay of copied records; not Android timing evidence")
            put("roundLimit", roundLimit); put("secondsLimit", secondsLimit); put("stopReason", stopReason)
            put("finalNeedsMoreWork", coordinator.needsMoreWork)
            put("finalGroupingComplete", finalSnapshot?.complete ?: false)
            put("finalConnectionComplete", finalSnapshot?.connectionPassComplete ?: false)
            put("finalGroupingPass", finalSnapshot?.groupingPass)
            put("finalCandidatePassLimit", finalSnapshot?.candidatePassLimit)
            put("inputSignatureMatchesSnapshot", inputSignatureMatches)
            put("resumeEvidence", if (inputSignatureMatches) "Exact shipping signature reconstructed from cache-current records"
                else "Input signature differs or metadata is incomplete; not unchanged-input resume evidence")
            put("replayMetadataVersion", replayMetadataVersion)
            put("initialCandidateTopics", initial.topics.size); put("initialPublishedTopics", publishedTopics.size)
            put("finalSnapshotAvailable", finalSnapshot != null)
            put("finalCandidateTopics", finalSnapshot?.topics?.size ?: 0)
            put("finalPublishedTopics", publishableDiscoveryTopics(finalSnapshot?.topics.orEmpty()).size)
            put("notes", records.size); put("passages", records.sumOf { it.passages.size }); put("failures", JsonArray(failures)); put("rounds", JsonArray(rounds))
        }))
        println("Private review/replay complete: notes=${records.size}, cases=${Json.parseToJsonElement(reviewFile.readText()).jsonObject.getValue("cases").jsonArray.size}, failures=${failures.size}, directory=$root")
        failures.forEach { println("Structural outcome: stage=${it["stage"]}, failure=${it["failure"]}, reason=${it["reason"]}") }
        check(failures.isEmpty()) { "Private replay rejected a checkpoint; inspect the local replay report" }
    }
}
