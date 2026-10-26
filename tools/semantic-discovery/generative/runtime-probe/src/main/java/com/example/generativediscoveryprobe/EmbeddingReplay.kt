package com.example.generativediscoveryprobe

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.*
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/** Small public-fixture replay; inputs come from the shipping host tokenizer/chunker. */
internal object EmbeddingReplay {
    fun run(context: Context): JSONObject {
        val directory = context.getExternalFilesDir(null)!!
        val input = File(directory, "baseline-inputs.json")
        require(input.length() in 1..8L * 1024 * 1024)
        val source = JSONObject(input.readText())
        require(source.getString("stage") == "prepared")
        val artifact = source.getJSONObject("artifact")
        val name = artifact.getString("file")
        require(name == "embeddinggemma-2-text-270m.litertlm")
        val bundle = File(directory, name)
        require(bundle.length() == artifact.getLong("bytes"))
        val digest = MessageDigest.getInstance("SHA-256")
        bundle.inputStream().buffered().use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == artifact.getString("sha256"))
        val target = File(directory, "baseline-embedded.json")
        source.put("stage", "embedding-running")
        target.writeText(source.toString())
        val started = SystemClock.elapsedRealtime()
        var count = 0
        EmbeddingEngine(EmbeddingEngineConfig(modelPath = bundle.absolutePath,
            backend = Backend.CPU(threadCount = 2), maxInputLength = 8192)).use { engine ->
            engine.initialize()
            val cases = source.getJSONArray("cases")
            for (c in 0 until cases.length()) {
                val records = cases.getJSONObject(c).getJSONArray("records")
                for (r in 0 until records.length()) {
                    val passages = records.getJSONObject(r).getJSONArray("passages")
                    for (p in 0 until passages.length()) {
                        val entry = passages.getJSONObject(p)
                        val text = entry.getJSONObject("passage").getString("input")
                        val vector = engine.computeEmbedding(
                            listOf(InputData.Text(artifact.getString("instruction") + text.trim())),
                            EmbeddingOptions(normalize = true, insertSpecialTokens = true, outputSize = 768)).embedding
                        require(vector.size == 768 && vector.all { it.isFinite() })
                        val norm = sqrt(vector.sumOf { it.toDouble() * it })
                        require(norm > 0 && norm.isFinite())
                        entry.put("vector", JSONArray(vector.map { it / norm }))
                        count++
                    }
                }
                target.writeText(source.toString())
            }
        }
        source.put("stage", "embedded").put("runtime", "LiteRT-LM 0.18.1")
            .put("backend", "CPU").put("device", android.os.Build.MODEL)
            .put("embeddingMillis", SystemClock.elapsedRealtime() - started).put("passages", count)
        target.writeText(source.toString())
        return JSONObject().put("passages", count).put("embeddingMillis", source.getLong("embeddingMillis"))
            .put("output", target.name)
    }
}
