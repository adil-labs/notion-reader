package com.example.notionreader.data.discovery

import android.content.Context
import android.os.SystemClock
import com.example.notionreader.application.*
import com.example.notionreader.data.local.*
import com.example.notionreader.domain.*
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.settings.DiscoveryBackend
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Frozen public source families through the actual worker; no Reader storage or credentials. */
internal object ProductionSemanticProbe {
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(65536)
            while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun run(context: Context, backendName: String): JSONObject = runBlocking {
        val models = requireNotNull(context.getExternalFilesDir(null))
        val corpusFile = File(models, "fresh-naming-cases-v1.json")
        require(corpusFile.length() in 1..8L * 1024 * 1024)
        val corpus = JSONObject(corpusFile.readText())
        val sourceCases = corpus.getJSONArray("cases")
        require(sourceCases.length() >= 20)
        // Evaluate the held-out families together. Isolated three-Note cases would hide
        // false merges and competition with other subjects in the same library.
        val jointPassages = JSONArray()
        for (c in 0 until sourceCases.length()) {
            val sourceCase = sourceCases.getJSONObject(c)
            val passages = sourceCase.getJSONArray("passages")
            for (p in 0 until passages.length()) {
                val passage = JSONObject(passages.getJSONObject(p).toString())
                passage.put("note_id", "${sourceCase.getString("id")}:${passage.getString("note_id")}")
                jointPassages.put(passage)
            }
        }
        val cases = JSONArray().put(JSONObject().put("id", "joint-library").put("passages", jointPassages))
        for (artifact in listOf(GenerativeModelPack.artifact, GemmaModelPack.artifacts.single())) {
            val bundle = File(models, artifact.path)
            require(bundle.length() == artifact.bytes && hash(bundle) == artifact.sha256)
        }
        val root = File(context.filesDir, "public-semantic-fixture")
        check(!root.exists() || root.deleteRecursively()); check(root.mkdirs())
        val backend = if (backendName == "GPU") TopicLabelBackend.GPU else TopicLabelBackend.CPU
        val identity = GenerativeModelPack.identity(backend.provenance)
        val embedding = GemmaEmbeddingProvider(models).also { it.selectBackend(DiscoveryBackend.CPU) }
        val memory = DiscoveryMemoryGate({
            val facts = android.app.ActivityManager.MemoryInfo()
            context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(facts)
            DiscoveryDeviceMemory(facts.availMem, facts.totalMem, facts.threshold, facts.lowMemory)
        }, SystemClock::elapsedRealtime)
        val realLabeler = GemmaTopicLabeler(identity, { true }, {
            LiteRtTopicLabelEngine(File(models, GenerativeModelPack.bundleFile), context.cacheDir, backend)
        }, { memory.allows(force = true) }, Dispatchers.IO)
        val outputs = JSONArray()
        val result = JSONObject().put("corpusSha256", hash(corpusFile)).put("labelIdentity", identity.toString())
            .put("sourceFamilies", sourceCases.length()).put("sourcePassages", jointPassages.length())
            .put("embeddingIdentity", GemmaModelPack.identity.toString()).put("groupPolicy", GemmaModelPack.topicPolicy.toString())
            .put("scope", "shipping controller/tokenizer/embedding/grouping/labeler/stores; purpose-built fresh families; not scale acceptance")
            .put("readerActivationGateChanged", false).put("outputs", outputs)
        val target = File(models, "fresh-semantic-progress.json")
        fun save() {
            val temporary = File(models, "fresh-semantic-progress.json.tmp")
            temporary.writeText(result.toString(2)); check(temporary.renameTo(target))
        }
        save()
        try {
            for (c in 0 until cases.length()) {
                val case = cases.getJSONObject(c)
                val caseId = case.getString("id")
                require(Regex("[a-z0-9-]+").matches(caseId))
                val dir = File(root, caseId).also { check(it.mkdirs()) }
                val cache = FileDocumentCache(File(dir, "cache"))
                val passages = case.getJSONArray("passages")
                val byNote = (0 until passages.length()).map { passages.getJSONObject(it) }.groupBy { it.getString("note_id") }
                for ((note, bodies) in byNote) {
                    require(bodies.all { it.getBoolean("eligible") }) // Never silently filter source evidence.
                    val document = ReaderDocument("$caseId:$note", "Public source", bodies.map { p ->
                        ReaderSection("${p.getString("id")}:section", p.getString("heading"), 1,
                            listOf(ReaderChunk(p.getString("id"), p.getString("id"), ReaderChunkKind.PARAGRAPH, p.getString("body"))))
                    })
                    cache.put(CachedDocument(document, null, Instant.EPOCH))
                }
                val attempts = JSONArray()
                val labeler = object : TopicLabelRuntime {
                    override suspend fun label(request: TopicLabelRequest): TopicLabelRun {
                        val start = SystemClock.elapsedRealtime()
                        val response = realLabeler.label(request)
                        attempts.put(JSONObject().put("topicId", request.topicId).put("workKey", topicLabelWorkKey(request))
                            .put("offeredEvidence", JSONArray(request.evidence.map { evidence -> JSONObject()
                                .put("id", evidence.passageId).put("pageId", evidence.pageId).put("revision", evidence.revision).put("body", evidence.body) }))
                            .put("result", response.toString()).put("elapsedMillis", SystemClock.elapsedRealtime() - start))
                        return response
                    }
                    override suspend fun close() = realLabeler.close()
                }
                val active = DiscoveryController(cache, FileSemanticIndexStore(File(dir, "index")),
                    FileDiscoveryInterestStore(File(dir, "interests")), embedding,
                    topicStore = FileTopicStore(File(dir, "topics")), topicCheckpointStore = FileTopicStore(File(dir, "checkpoint")),
                    passageProgressStore = FileSemanticIndexStore(File(dir, "passages")), topicPolicy = GemmaModelPack.topicPolicy,
                    recommendationThreshold = GemmaModelPack.recommendationThreshold, topicLabelRuntime = labeler,
                    topicLabelModelAvailable = { true }, topicLabelIdentity = { identity }, topicLabelStore = FileTopicLabelStore(File(dir, "labels")),
                    topicRetrievalStore = FileTopicRetrievalStore(File(dir, "matches")),
                    mayContinueIndexing = { memory.allows() })
                val row = JSONObject().put("case_id", caseId).put("attempts", attempts)
                outputs.put(row); save()
                val started = SystemClock.elapsedRealtime()
                try {
                    active.resume()
                    var passes = 0
                    do { active.reconcile(emptyList(), true); passes++ }
                    while (active.hasPendingWork && active.state.value.processingFailure == null && passes < 16)
                    active.refreshTopics()
                    val snapshot = active.state.value.topicSnapshot
                    val published = publishableDiscoveryTopics(snapshot.topics).map { it.id }.toSet()
                    row.put("passes", passes).put("pending", active.hasPendingWork)
                        .put("processingFailure", active.state.value.processingFailure ?: JSONObject.NULL)
                        .put("namingStatus", active.state.value.topicNaming.toString())
                        .put("topics", JSONArray(snapshot.topics.map { topic -> JSONObject()
                            .put("id", topic.id).put("published", topic.id in published)
                            .put("label", topic.currentGeneratedLabel?.text ?: JSONObject.NULL)
                            .put("definition", topic.currentGeneratedLabel?.definition ?: JSONObject.NULL)
                            .put("neutralDisplay", topic.effectiveLabel)
                            .put("matchingComplete", topic.currentTopicMatches != null)
                            .put("seed_passage_ids", JSONArray(topic.members.map { it.passage.sourceBlockId }.distinct()))
                            .put("passage_ids", JSONArray(topic.matchingMembers.map { it.passage.sourceBlockId }.distinct()))
                            .put("memberNotes", JSONArray(topic.matchingMembers.map { it.pageId }.distinct()))
                            .put("evidenceIds", JSONArray(topic.currentGeneratedLabel?.evidenceIds ?: emptyList<String>())) }))
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    row.put("error", failure.javaClass.name).put("message", failure.message)
                } finally {
                    active.releaseTopicLabelModel(); embedding.release()
                    row.put("elapsedMillis", SystemClock.elapsedRealtime() - started)
                    save()
                }
            }
            result.put("stage", "completed"); save(); result
        } finally { realLabeler.close(); embedding.release() }
    }
}
