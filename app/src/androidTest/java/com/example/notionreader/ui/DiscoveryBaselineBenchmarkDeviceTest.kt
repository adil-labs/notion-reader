package com.example.notionreader.ui

import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notionreader.application.*
import com.example.notionreader.data.discovery.*
import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.discovery.*
import java.io.File
import java.time.Instant
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Comparable on-device baseline for Discovery inference work.
 *
 * Seeds the frozen [DiscoveryBenchmarkCorpus] in an isolated file root and records numeric
 * diagnostics only: no Note text and no generated labels reach the report. `cold` wipes the
 * root, `repeat` reuses the existing stores, and `paired` runs cold then repeat in one
 * invocation with the same model, backend and settings.
 *
 * Run through `tools/run-discovery-benchmark.ps1`, which pulls the JSONL report and records the
 * pinned APK/model identity and device conditions around the run.
 */
class DiscoveryBaselineBenchmarkDeviceTest {
    private class Metrics {
        val sums = LinkedHashMap<String, Long>()
        val counts = LinkedHashMap<String, Long>()
        fun accept(values: Map<String, Long>) {
            values.forEach { (key, value) ->
                sums.merge(key, value, Long::plus)
                counts.merge(key, 1L, Long::plus)
            }
        }
        fun putInto(json: JSONObject, prefix: String) {
            sums.forEach { (key, value) -> json.put("${prefix}_${key}", value) }
            counts.forEach { (key, value) -> json.put("${prefix}_${key}Count", value) }
        }
    }

    private data class Measurement(
        val mode: String,
        val elapsedMs: Long,
        val terminalReached: Boolean,
        val pendingAtEnd: Boolean,
        val firstVisibleTopicMs: Long?,
        val startingIndexed: Int,
        val startingIndexBytes: Long,
        val startingTopicBytes: Long,
        val startingProgressBytes: Long,
        val startingCachedDocuments: Int,
        val indexedAtEnd: Int,
        val cachedAtEnd: Int,
        val publications: Int,
        val newPublications: Int,
        val work: Metrics,
        val native: Metrics
    )

    @Test fun frozenHundredNoteCorpusRecordsColdAndRepeatRuns() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val models = File(context.noBackupFilesDir, "discovery-model")
        val topicBundle = File(models, "generative/current/${GenerativeModelPack.bundleFile}")
        val embeddingBundle = File(models, "current/${GemmaModelPack.bundleFile}")
        assumeTrue("Requires the installed Gemma topic model", topicBundle.isFile)
        assumeTrue("Requires the installed embedding model", embeddingBundle.isFile)
        check(Build.VERSION.SDK_INT >= 31) { "This benchmark expects the current device runtime" }

        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("discoveryBenchmarkMode")?.takeIf { it in setOf("cold", "repeat", "paired") } ?: "paired"
        val runs = (args.getString("discoveryBenchmarkRuns") ?: "1").toIntOrNull()?.coerceIn(1, 6) ?: 1
        val windowMs = (args.getString("discoveryBenchmarkWindowMs") ?: "120000").toLongOrNull()?.coerceIn(15_000, 900_000)
            ?: 120_000
        val root = File(context.filesDir, "discovery-benchmark-v${DiscoveryBenchmarkCorpus.version}")
        // App-internal and fsynced per record: a native inference crash must not take the
        // measurements already completed with it.
        val report = File(context.filesDir, "discovery-benchmark.jsonl")

        val notes = DiscoveryBenchmarkCorpus.notes()
        val documents = notes.map { (id, titleAndText) ->
            ReaderDocument.fromChunkTexts(id, titleAndText.first, listOf(titleAndText.second))
        }
        val entries = documents.map { ReaderIndexEntry(it.pageId, it.title) }
        val modelScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO +
            kotlinx.coroutines.SupervisorJob())
        val manager = GenerativeModelManager(models, modelScope, unmetered = { true })
        var measurementIndex = 0
        try {
            manager.checkInstalled()
            assumeTrue("Requires a verified installed Gemma topic bundle", manager.verifiedBundle() != null)
            repeat(runs) { iteration ->
                if (mode == "cold" || mode == "paired") {
                    measurementIndex++
                    measure("cold#$iteration", cold = true, root, models, documents, entries, manager, windowMs, report,
                        measurementIndex)
                }
                if (mode == "repeat" || mode == "paired") {
                    measurementIndex++
                    measure("repeat#$iteration", cold = false, root, models, documents, entries, manager, windowMs, report,
                        measurementIndex)
                }
            }
        } finally {
            modelScope.cancel()
            // The cold/repeat comparison needs the derived stores to survive the test process.
            if (mode == "cold") root.deleteRecursively()
        }
    }

    private suspend fun measure(label: String, cold: Boolean, root: File, models: File,
        documents: List<ReaderDocument>, entries: List<ReaderIndexEntry>, manager: GenerativeModelManager,
        windowMs: Long, report: File, measurementIndex: Int) {
        if (cold && root.exists() && !root.deleteRecursively()) throw java.io.IOException("Benchmark reset failed")
        root.mkdirs()
        val cache = FileDocumentCache(File(root, "cache"))
        val index = FileSemanticIndexStore(File(root, "index"))
        val topics = FileTopicStore(File(root, "topics"), allowFreshInitialization = true)
        val progress = FileProposalProgressStore(File(root, "progress"))
        val work = Metrics()
        val native = Metrics()
        val embedding = GemmaEmbeddingProvider(File(models, "current"), inferenceDiagnostics = { name, value ->
            native.accept(mapOf(name to value))
        })
        val labeler = GemmaTopicLabeler.create(manager, File(root, "runtime"), { true },
            backend = TopicLabelBackend.GPU, inferenceDiagnostics = { name, value ->
                native.accept(mapOf(name to value))
            })
        var windowStartedAt = 0L
        val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(File(root, "interests")),
            embedding, modelAvailable = { true }, topicPolicy = GemmaModelPack.topicPolicy,
            recommendationThreshold = GemmaModelPack.recommendationThreshold, topicStore = topics,
            topicLabelRuntime = labeler, topicLabelModelAvailable = { true },
            topicRetrievalStore = FileTopicRetrievalStore(File(root, "matches")),
            proposalProgressStore = progress,
            topicSupportIdentity = { GenerativeModelPack.supportIdentity(TopicLabelBackend.GPU.provenance) },
            topicProposalIdentity = { GenerativeModelPack.proposalIdentity(TopicLabelBackend.GPU.provenance) },
            // The production foreground guard bounds indexing; the benchmark reuses it so the
            // fixed window ends at a safe boundary instead of inside one long reconcile call.
            mayContinueIndexing = {
                windowStartedAt > 0 && SystemClock.elapsedRealtime() - windowStartedAt < windowMs
            },
            workDiagnostics = { values -> work.accept(values) })
        try {
            val startingIndexed = index.pageIds().size
            val startingIndexBytes = index.bytes()
            val startingTopicBytes = topics.bytes()
            val startingProgressBytes = progress.bytes()
            val startingCached = cache.count()
            val priorPublished = topics.readMetadata()?.topics?.map { it.id }?.toSet() ?: emptySet()
            // Reuse state only when it is the identical frozen corpus.
            val present = cache.snapshot().map { it.document.pageId }.toSet()
            documents.filter { it.pageId !in present }.forEach { cache.put(CachedDocument(it, null, Instant.EPOCH)) }
            val started = SystemClock.elapsedRealtime()
            windowStartedAt = started
            controller.resume()
            var firstVisibleTopicMs: Long? = null
            var terminalReached = false
            var idleChecks = 0
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - started
                if (elapsed >= windowMs) break
                controller.reconcile(entries, true)
                controller.refreshTopics()
                val published = controller.state.value.publishedTopics
                if (firstVisibleTopicMs == null && published.any { it.topic.id !in priorPublished })
                    firstVisibleTopicMs = SystemClock.elapsedRealtime() - started
                if (!controller.hasPendingWork) {
                    if (++idleChecks >= 3) { terminalReached = true; break }
                } else idleChecks = 0
                delay(200)
            }
            val elapsedMs = SystemClock.elapsedRealtime() - started
            val published = controller.state.value.publishedTopics
            val newPublications = published.count { it.topic.id !in priorPublished }
            val indexedAtEnd = controller.state.value.indexedCount
            val cachedAtEnd = controller.state.value.cachedPageIds.size
            val pendingAtEnd = controller.hasPendingWork
            val identity = GenerativeModelPack.proposalIdentity(TopicLabelBackend.GPU.provenance)
            val record = JSONObject().apply {
                put("corpusVersion", DiscoveryBenchmarkCorpus.version)
                put("measurementIndex", measurementIndex)
                put("mode", label.substringBefore('#'))
                put("iteration", label.substringAfter('#'))
                put("measuredAtEpochMs", System.currentTimeMillis())
                put("device", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("api", Build.VERSION.SDK_INT)
                put("backend", "GPU")
                put("modelArtifact", identity.artifact)
                put("modelPolicy", identity.policy)
                put("corpusNotes", documents.size)
                put("expectedUsefulSubjects", DiscoveryBenchmarkCorpus.expectedUsefulSubjects)
                put("windowMs", windowMs)
                put("elapsedMs", elapsedMs)
                put("terminalReached", terminalReached)
                put("pendingAtEnd", pendingAtEnd)
                put("firstVisibleTopicMs", firstVisibleTopicMs ?: JSONObject.NULL)
                put("startingIndexed", startingIndexed)
                put("startingIndexBytes", startingIndexBytes)
                put("startingTopicBytes", startingTopicBytes)
                put("startingProgressBytes", startingProgressBytes)
                put("startingCachedDocuments", startingCached)
                put("indexedAtEnd", indexedAtEnd)
                put("cachedAtEnd", cachedAtEnd)
                put("publications", published.size)
                put("newPublications", newPublications)
                put("newTopicsPerMinute", if (elapsedMs > 0) newPublications * 60_000.0 / elapsedMs else 0.0)
                work.putInto(this, "work")
                native.putInto(this, "native")
            }
            report.appendText(record.toString() + "\n", Charsets.UTF_8)
            java.io.FileOutputStream(report, true).use { it.flush(); it.fd.sync() }
            android.util.Log.i("DiscoveryBenchmark", record.toString())
            assertTrue("Cold and repeat runs must both exercise indexing", startingIndexed >= 0)
        } finally {
            controller.pause()
            controller.closeLabels()
            embedding.release()
        }
    }
}
