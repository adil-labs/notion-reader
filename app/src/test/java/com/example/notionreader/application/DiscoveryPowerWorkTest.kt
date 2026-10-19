package com.example.notionreader.application

import com.example.notionreader.data.local.*
import com.example.notionreader.domain.*
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.discovery.*
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryPowerWorkTest {
    @Test fun unfinishedPassageEvidenceIsWithdrawnOnEvictionModelChangeAndIndexClear() = runBlocking {
        val dir = Files.createTempDirectory("passage-progress-privacy").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val progress = FileSemanticIndexStore(dir.resolve("progress"))
            val provider = Provider()
            val doc = ReaderDocument.fromChunkTexts("a", "Forest ecology", listOf("Forest habitats support wildlife populations."))
            val passage = SemanticChunker(provider.tokenizer).chunk(doc).single()
            val record = SemanticRecord("a", semanticRevision(doc), provider.identity, SemanticMetadata(doc.title),
                listOf(EmbeddedPassage(passage, listOf(1f, 0f))))
            fun controller(model: EmbeddingProvider = provider) = DiscoveryController(cache, index,
                FileDiscoveryInterestStore(dir.resolve("interests")), model, passageProgressStore = progress)
            cache.put(CachedDocument(doc, null, Instant.EPOCH)); progress.write(record)
            cache.remove("a"); controller().reconcile(emptyList(), false)
            assertTrue("Eviction must withdraw staged text even when bulk work cannot run", progress.pageIds().isEmpty())
            cache.put(CachedDocument(doc, null, Instant.EPOCH)); progress.write(record)
            val differentModel = object : EmbeddingProvider by provider {
                override val identity = provider.identity.copy(model = "replacement-model")
            }
            controller(differentModel).reconcile(emptyList(), false)
            assertTrue("Old model progress must never be reused", progress.pageIds().isEmpty())
            progress.write(record); index.write(record)
            controller().reconcile(emptyList(), false)
            assertTrue("A crash after full commit must not strand a duplicate checkpoint", progress.pageIds().isEmpty())
            progress.write(record); controller().clearIndex()
            assertTrue("Explicit clear must erase staged private evidence", progress.pageIds().isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun aNoteSpanningWindowsResumesItsDurablePassagesAfterControllerRestart() = runBlocking {
        val dir = Files.createTempDirectory("passage-window-recovery").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val durable = FileSemanticIndexStore(dir.resolve("progress"))
            val checkpoints = mutableListOf<Int>()
            val progress = object : SemanticIndexStore by durable {
                override suspend fun write(record: SemanticRecord): Boolean {
                    val saved = durable.write(record)
                    if (saved) checkpoints += record.passages.size
                    return saved
                }
            }
            val calls = mutableListOf<String>()
            var eligible = true
            val baseline = Provider()
            val provider = object : EmbeddingProvider {
                override val identity = baseline.identity
                override val tokenizer = baseline.tokenizer
                override suspend fun embed(text: String): List<Float> {
                    calls += text
                    if (calls.size == 1) eligible = false
                    return listOf(1f, 0f)
                }
            }
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("long", "Long forest study",
                listOf((1..600).joinToString(" ") { "forest$it" })), null, Instant.EPOCH))
            fun controller() = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")),
                provider, mayContinueIndexing = { eligible }, passageProgressStore = progress)
            val first = controller(); first.resume(); first.reconcile(emptyList(), true)
            assertEquals(1, calls.size)
            assertNull("An unfinished Note must never be exposed as fully indexed", index.read("long"))
            eligible = true
            val restarted = controller(); restarted.reconcile(emptyList(), true)
            assertEquals("The first completed passage must survive a process/window boundary", 1, calls.count { it == calls.first() })
            assertEquals(1, restarted.state.value.indexedCount)
            assertTrue(index.read("long")!!.passages.size > 1)
            assertTrue("Finished progress must release its private staging bytes", progress.pageIds().isEmpty())
            assertTrue("Recovery must never replace a checkpoint with a shorter or identical prefix",
                checkpoints.zipWithNext().all { (before, after) -> after > before })
        } finally { dir.deleteRecursively() }
    }
    @Test fun changedCachedWorkWithdrawsTheOldEstimateEvenWhileWaitingForPower() = runBlocking {
        val dir = Files.createTempDirectory("discovery-estimate-source").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache")); val index = FileSemanticIndexStore(dir.resolve("index"))
            val provider = Provider()
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), provider)
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("a", "Forest", listOf("Root growth.", "Leaf growth.")), null, Instant.EPOCH))
            controller.resume(); controller.updatePowerState(DiscoveryPowerState(80))
            provider.onEmbed = { controller.updatePowerState(DiscoveryPowerState(15)) }
            controller.reconcile(emptyList(), true)
            assertNotNull(controller.state.value.embeddingWork)
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("a", "Forest", listOf("A different body of work.")), null, Instant.EPOCH))
            controller.reconcile(emptyList(), true)
            assertNull(controller.state.value.embeddingWork)
        } finally { dir.deleteRecursively() }
    }
    private class Provider : EmbeddingProvider {
        override val identity = EmbeddingIdentity("power-test", 2)
        override val tokenizer = object : SemanticTokenizer {
            override fun tokens(text: String): List<SemanticToken> {
                onTokenize()
                return Regex("\\S+").findAll(text).mapIndexed { i, m ->
                    SemanticToken(i, m.range.first, m.range.last + 1)
                }.toList()
            }
        }
        var calls = 0
        val inputs = mutableListOf<String>()
        var onTokenize: () -> Unit = {}
        var onEmbed: suspend () -> Unit = {}
        override suspend fun embed(text: String): List<Float> { calls++; inputs += text; onEmbed(); return listOf(1f, 0f) }
    }

    @Test fun aPowerDropDuringAQueryWaitCannotStartAnotherBulkEmbedding() = runBlocking {
        val dir = Files.createTempDirectory("discovery-power-query").toFile()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            val cache = FileDocumentCache(dir.resolve("cache")); val index = FileSemanticIndexStore(dir.resolve("index"))
            val provider = Provider()
            lateinit var controller: DiscoveryController
            var armed = false; var launched = false
            var query: Deferred<Unit>? = null
            controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), provider,
                mayContinueIndexing = {
                    // Start the foreground query immediately after the final eligibility check,
                    // forcing bulk work to wait before entering the inference lock.
                    if (armed && !launched) {
                        launched = true
                        query = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { controller.search("manual query") }
                    }
                    true
                }, workDispatcher = Dispatchers.Unconfined)
            provider.onTokenize = { if (controller.state.value.embeddingWork != null) armed = true }
            provider.onEmbed = { if (provider.calls == 1) { entered.complete(Unit); release.await() } }
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("a", "Forest", listOf("Forest roots retain water.")), null, Instant.EPOCH))
            controller.resume(); controller.updatePowerState(DiscoveryPowerState(80))
            val work = async { controller.reconcile(emptyList(), true) }
            withTimeout(5_000) { entered.await() }
            assertEquals(listOf("manual query"), provider.inputs)
            controller.updatePowerState(DiscoveryPowerState(15)); release.complete(Unit)
            withTimeout(5_000) { work.await(); query!!.await() }
            assertEquals("Only the requested query may embed after the power change", 1, provider.calls)
            assertTrue(index.pageIds().isEmpty())
        } finally { release.complete(Unit); dir.deleteRecursively() }
    }

    @Test fun powerDeferralPreservesSearchButNeverDelaysSourceWithdrawal() = runBlocking {
        val dir = Files.createTempDirectory("discovery-power").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache")); val index = FileSemanticIndexStore(dir.resolve("index"))
            val provider = Provider()
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), provider)
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("a", "Forest", listOf("Forest roots retain water.")), null, Instant.EPOCH))
            controller.resume(); controller.updatePowerState(DiscoveryPowerState(29))
            controller.reconcile(emptyList(), true)
            assertEquals(0, provider.calls)
            assertFalse(controller.state.value.paused)
            controller.updatePowerState(DiscoveryPowerState(1, connected = true))
            controller.reconcile(emptyList(), true)
            assertEquals(1, provider.calls)
            controller.updatePowerState(DiscoveryPowerState(29))
            controller.search("roots")
            assertEquals(listOf("a"), controller.state.value.results.map { it.pageId })
            cache.clear(); controller.reconcile(emptyList(), false)
            assertTrue(index.pageIds().isEmpty())
            assertTrue(controller.state.value.results.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun aPowerDropStopsTheNextEmbeddingAndChargingResumesRequestedWork() = runBlocking {
        val dir = Files.createTempDirectory("discovery-power-drop").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache")); val index = FileSemanticIndexStore(dir.resolve("index"))
            val provider = Provider()
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), provider)
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("a", "Forest", listOf("Forest roots retain water.", "Forest leaves absorb sunlight.")), null, Instant.EPOCH))
            controller.resume(); controller.updatePowerState(DiscoveryPowerState(80))
            provider.onEmbed = { controller.updatePowerState(DiscoveryPowerState(29)) }
            controller.reconcile(emptyList(), true)
            assertEquals(1, provider.calls)
            assertTrue(index.pageIds().isEmpty())
            provider.onEmbed = {}; controller.updatePowerState(DiscoveryPowerState(connected = true))
            controller.reconcile(emptyList(), true)
            assertEquals("The completed first input is reused after power resumes", 2, provider.calls)
            assertEquals(setOf("a"), index.pageIds())
        } finally { dir.deleteRecursively() }
    }

    @Test fun timeEstimatesCountOnlyRemainingNonReusedPassagesOnThisDevice() = runBlocking {
        val dir = Files.createTempDirectory("discovery-estimate").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache")); val index = FileSemanticIndexStore(dir.resolve("index"))
            val provider = Provider(); var nanos = 0L
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), provider,
                monotonicNanos = { nanos })
            val observed = mutableListOf<DiscoveryEmbeddingWork>()
            provider.onEmbed = { controller.state.value.embeddingWork?.let { observed += it }; nanos += 1_000_000_000 }
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("a", "Forest", (1..7).map { "Forest passage $it describes growing roots and water." }), null, Instant.EPOCH))
            controller.resume(); controller.reconcile(emptyList(), true)
            assertEquals(7, provider.calls)
            assertTrue(observed.any { it.remainingPassages == 3 && it.estimate == ProcessingTimeRange(3, 5) })
            controller.reconcile(emptyList(), true)
            assertEquals(7, provider.calls)
            assertEquals(0, controller.state.value.embeddingWork!!.remainingPassages)
        } finally { dir.deleteRecursively() }
    }
}
