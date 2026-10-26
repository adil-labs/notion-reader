package com.example.notionreader.data.discovery

import android.content.Context
import android.os.SystemClock
import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.settings.DiscoveryBackend
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Diagnostic only: actual generated names against every actual fresh-v1 source vector. */
internal object TopicQueryProbe {
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(65536)
            while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun run(context: Context): JSONObject = runBlocking {
        val models = requireNotNull(context.getExternalFilesDir(null))
        val originFile = File(models, "fresh-semantic-progress.json")
        val origin = JSONObject(originFile.readText())
        val corpusFile = File(models, "fresh-naming-cases-v1.json")
        require(origin.getString("stage") == "completed" && origin.getString("corpusSha256") == hash(corpusFile))
        val artifact = GemmaModelPack.artifacts.single()
        require(File(models, artifact.path).length() == artifact.bytes && hash(File(models, artifact.path)) == artifact.sha256)
        val root = File(context.filesDir, "public-semantic-fixture/joint-library")
        val cache = FileDocumentCache(File(root, "cache"))
        val index = FileSemanticIndexStore(File(root, "index"))
        val sources = cache.snapshot().associate { canonicalSemanticId(it.document.pageId) to semanticRevision(it.document) }
        val records = index.pageIds().sorted().map { id -> requireNotNull(index.read(id)).also {
            require(it.identity == GemmaModelPack.identity && sources[id] == it.revision)
        } }
        require(records.size == origin.getInt("sourcePassages") && records.size == sources.size)
        val topics = origin.getJSONArray("outputs").getJSONObject(0).getJSONArray("topics")
        val outputs = JSONArray()
        val result = JSONObject().put("mode", "name-only diagnostic on seen source families; no acceptance or production change")
            .put("corpusSha256", hash(corpusFile)).put("originSha256", hash(originFile))
            .put("embeddingIdentity", GemmaModelPack.identity.toString()).put("sourceRecords", records.size)
            .put("readerActivationGateChanged", false).put("queries", outputs)
        val provider = GemmaEmbeddingProvider(models).also { it.selectBackend(DiscoveryBackend.CPU) }
        try {
            for (i in 0 until topics.length()) {
                val topic = topics.getJSONObject(i)
                val row = JSONObject().put("topicId", topic.getString("id"))
                outputs.put(row)
                if (topic.isNull("label")) { row.put("skipped", "No generated name; remains a missed query"); continue }
                val query = topic.getString("label")
                val started = SystemClock.elapsedRealtime()
                val vector = provider.embed(query)
                val scored = records.map { record ->
                    val hit = record.passages.maxBy { cosineSimilarity(vector, it.vector) }
                    JSONObject().put("pageId", record.pageId).put("passageId", hit.passage.sourceBlockId)
                        .put("score", cosineSimilarity(vector, hit.vector))
                }.sortedWith(compareByDescending<JSONObject> { it.getDouble("score") }.thenBy { it.getString("pageId") })
                row.put("query", query).put("candidates", JSONArray(scored))
                    .put("elapsedMillis", SystemClock.elapsedRealtime() - started)
            }
            result
        } finally { provider.release() }
    }
}
