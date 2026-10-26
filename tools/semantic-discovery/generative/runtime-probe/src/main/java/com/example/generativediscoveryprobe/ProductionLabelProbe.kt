package com.example.notionreader.data.discovery

import android.content.Context
import android.os.SystemClock
import com.example.generativediscoveryprobe.ProbeMemory
import com.example.notionreader.domain.discovery.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import org.json.JSONArray
import org.json.JSONObject

/** Runs shipping port + native adapter on purpose-built public text, outside Reader data. */
internal object ProductionLabelProbe {
    fun run(context: Context, backendName: String, cancelAfterMillis: Int): JSONObject = runBlocking {
        val backend = if (backendName == "GPU") TopicLabelBackend.GPU else TopicLabelBackend.CPU
        val model = File(context.getExternalFilesDir(null), GenerativeModelPack.bundleFile)
        require(model.length() == GenerativeModelPack.artifact.bytes)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        model.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == GenerativeModelPack.artifact.sha256)
        val identity = GenerativeModelPack.identity(backend.provenance)
        val request = TopicLabelRequest("public-germination", "public-source-v1", 1, listOf(
            TopicLabelEvidence("p0", "note1", "1", "Seed germination requires suitable moisture and temperature."),
            TopicLabelEvidence("p1", "note2", "1", "A dry seed needs water to begin germination; unsuitable temperatures can prevent sprouting.")), identity)
        val startedSignal = AtomicReference(CompletableDeferred<Unit>())
        val terminalAt = AtomicReference<Long?>(null)
        val nativeCancelled = AtomicReference(false)
        val tokenCount = AtomicReference(0)
        val diagnostics = JSONArray()
        val runtime = GemmaTopicLabeler(identity, { true }, {
            val engine = LiteRtTopicLabelEngine(model, context.cacheDir, backend)
            object : NativeTopicLabelEngine {
                override fun conversation(): NativeTopicLabelSession {
                    val session = engine.conversation()
                    val signal = startedSignal.get()
                    return object : NativeTopicLabelSession {
                        override fun countPromptTokens(prompt: String) = session.countPromptTokens(prompt).also { tokenCount.set(it) }
                        override fun start(prompt: String, chunk: (String) -> Unit, done: (Throwable?) -> Unit) {
                            session.start(prompt, chunk) { failure ->
                                terminalAt.set(SystemClock.elapsedRealtime())
                                nativeCancelled.set(failure is CancellationException)
                                done(failure)
                            }
                            signal.complete(Unit)
                        }
                        override fun cancel() = session.cancel()
                        override fun close() = session.close()
                    }
                }
                override fun close() = engine.close()
            }
        }, { !ProbeMemory.snapshot(context).getBoolean("deviceLowMemory") }, Dispatchers.IO, { stage, failure ->
            diagnostics.put(JSONObject().put("stage", stage).put("class", failure.javaClass.name)
                .put("message", failure.message).put("stack", failure.stackTrace.take(8).joinToString("\n")))
        })
        val result = JSONObject().put("modelSha256", GenerativeModelPack.artifact.sha256)
            .put("labelIdentity", identity.toString())
            .put("promptIdentity", identity.prompt).put("schemaIdentity", identity.schema)
            .put("backend", backend.provenance).put("beforeLoad", ProbeMemory.snapshot(context))
        val runs = JSONArray()
        try {
            repeat(2) { iteration ->
                startedSignal.set(CompletableDeferred())
                terminalAt.set(null); nativeCancelled.set(false)
                val start = SystemClock.elapsedRealtime()
                val operation = async(Dispatchers.IO) { runtime.label(request) }
                var requestedAt: Long? = null
                if (cancelAfterMillis > 0) {
                    select<Unit> {
                        startedSignal.get().onAwait { }
                        operation.onJoin { error("Native generation never started: ${operation.await()}") }
                    }
                    delay(cancelAfterMillis.toLong())
                    check(!operation.isCompleted) { "Generation completed before cancellation" }
                    requestedAt = SystemClock.elapsedRealtime()
                    operation.cancelAndJoin()
                    check(nativeCancelled.get()) { "Native cancellation exception was not observed" }
                }
                // Await the production operation before reading its token count or elapsed time.
                val response = if (requestedAt == null) operation.await() else null
                val row = JSONObject().put("iteration", iteration).put("promptTokens", tokenCount.get())
                    .put("totalMillis", SystemClock.elapsedRealtime() - start)
                    .put("nativeCancellationException", nativeCancelled.get())
                if (requestedAt != null) row.put("cancelToNativeTerminalMillis", checkNotNull(terminalAt.get()) - requestedAt)
                    .put("cancelToPortCompletedMillis", SystemClock.elapsedRealtime() - requestedAt)
                else {
                    check(response is TopicLabelRun.Completed) { "Production port returned $response; public-probe diagnostics: $diagnostics" }
                    row.put("label", response.candidate.text).put("definition", response.candidate.definition)
                        .put("evidenceIds", JSONArray(response.candidate.evidenceIds))
                }
                runs.put(row)
            }
        } finally { runtime.close() }
        result.put("runs", runs).put("afterClose", ProbeMemory.snapshot(context))
    }
}
