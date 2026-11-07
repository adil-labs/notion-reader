package com.example.notionreader.application

import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.document.*
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DiscoveryIndexingScaleTest {
    private class Cache : DocumentCache {
        val documents = linkedMapOf<String, CachedDocument>()
        override val revision = MutableStateFlow(0L)
        override val invalidations = MutableStateFlow<List<CacheInvalidation>>(emptyList())
        override suspend fun snapshot() = documents.values.toList()
        override suspend fun get(pageId: String) = documents[pageId]
        override suspend fun put(document: CachedDocument) { documents[document.document.pageId] = document; revision.value++ }
        override suspend fun remove(pageId: String) {
            documents.remove(pageId); revision.value++
            invalidations.value += CacheInvalidation(pageId, CacheRemovalReason.DELETED, revision.value)
        }
        override suspend fun clear() {
            documents.clear(); revision.value++
            invalidations.value += CacheInvalidation(null, CacheRemovalReason.CLEARED, revision.value)
        }
        override suspend fun getMostRecentlyUsed() = documents.values.lastOrNull()
        override suspend fun getNewestCached() = documents.values.lastOrNull()
        override suspend fun count() = documents.size
    }
    private class Index : SemanticIndexStore {
        val records = linkedMapOf<String, SemanticRecord>()
        var afterWrite: (() -> Unit)? = null
        private var paused = false
        override suspend fun pageIds() = records.keys.toSet()
        override suspend fun read(pageId: String) = records[pageId]
        override suspend fun write(record: SemanticRecord): Boolean {
            records[record.pageId] = record // Replacement deliberately destroys the previous prefix.
            afterWrite?.invoke()
            return true
        }
        override suspend fun remove(pageId: String) { records.remove(pageId) }
        override suspend fun clear() { records.clear() }
        override suspend fun bytes() = 0L
        override suspend fun isPaused() = paused
        override suspend fun setPaused(paused: Boolean) { this.paused = paused }
    }
    @Test fun twentyThousandPassagesResumeInBoundedWindowsAndWarmIndexingDoesNoInference() = runBlocking {
        val cache = Cache()
        fun note(n: Int, changed: Boolean = false) = CachedDocument(ReaderDocument.fromChunkTexts(
            "note$n", "Note $n", (0 until 10).map { p ->
                "Purpose built evidence for Note $n passage $p." + if (changed && p == 5) " Revised." else ""
            }), null, Instant.EPOCH)
        repeat(2000) { cache.put(note(it)) }
        val index = Index(); val progress = Index()
        val interests = object : DiscoveryInterestStore {
            override suspend fun read() = emptyList<DiscoveryInterest>()
            override suspend fun write(interests: List<DiscoveryInterest>) = Unit
        }
        val inputs = mutableMapOf<String, Int>()
        var calls = 0
        val provider = object : EmbeddingProvider {
            override val identity = EmbeddingIdentity("scale", 2)
            override val tokenizer = object : SemanticTokenizer {
                override fun tokens(text: String) = Regex("\\S+").findAll(text).map {
                    SemanticToken(1, it.range.first, it.range.last + 1)
                }.toList()
            }
            override suspend fun embed(text: String): List<Float> {
                calls++; inputs[text] = (inputs[text] ?: 0) + 1
                return listOf(1f, 0f)
            }
        }
        fun controller(limit: Int) = DiscoveryController(cache, index, interests, provider,
            passageProgressStore = progress, mayContinueIndexing = { calls < limit })
        progress.afterWrite = {
            if (calls == 777) { progress.afterWrite = null; throw CancellationException("Process interrupted after durable checkpoint") }
        }
        try {
            controller(20000).reconcile(emptyList(), true)
            fail("The interruption must execute")
        } catch (_: CancellationException) { }
        assertEquals(777, calls)
        var windows = 0
        while (index.records.size < 2000) {
            val before = calls
            controller(before + 503).reconcile(emptyList(), true)
            assertTrue("An execution window must bound fresh calls", calls - before <= 503)
            assertTrue("Eligible work must advance", calls > before)
            assertTrue("Continuation must terminate", ++windows < 50)
        }
        assertEquals(20000, index.records.values.sumOf { it.passages.size })
        assertEquals(20000, calls)
        assertTrue("No completed inference may repeat after restart", inputs.values.all { it == 1 })
        controller(Int.MAX_VALUE).reconcile(emptyList(), true)
        assertEquals(20000, calls)
        cache.put(note(0, changed = true))
        controller(Int.MAX_VALUE).reconcile(emptyList(), true)
        assertEquals("Only the changed passage needs a new embedding", 20001, calls)
        println("Index scale: passages=20000 notes=2000 cold=20000 warm=0 changedPassage=1 interruptedAt=777 continuationWindows=$windows maxFreshPerWindow=503")
    }
}
