package com.example.notionreader.application

import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.document.CachedDocument
import java.time.Instant
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiscoveryProposalLifecycleTest {
    @get:Rule val files = TemporaryFolder()
    private val provider = object : EmbeddingProvider {
        override val identity = EmbeddingIdentity("test", 2)
        override val tokenizer = object : SemanticTokenizer { override fun tokens(text: String) = emptyList<SemanticToken>() }
        override suspend fun embed(text: String): List<Float> = error("No inference is needed")
    }
    private fun document(id: String) = ReaderDocument.fromChunkTexts(id, id,
        listOf("Moisture allows dormant seeds to absorb water and start growing."))
    private fun progress(ownerRevision: String, otherRevision: String) = ProposalProgressRecord("one", ownerRevision, 0,
        provider.identity, ProposalScanCheckpoint(semanticHash("context"), mapOf("two" to otherRevision),
            mapOf("anchor" to listOf(ProposalScoredEvidence(ProposalEvidenceRef("two", otherRevision, "p"),
                .9f, ProposalReason.SEMANTIC_NEIGHBOR))), emptySet()))

    @Test fun reconciliationWithdrawsPendingProgressWhenASourceDisappears() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val one = document("one"); val two = document("two")
        cache.put(CachedDocument(one, null, Instant.EPOCH)); cache.put(CachedDocument(two, null, Instant.EPOCH))
        val sources = mapOf("one" to semanticRevision(one), "two" to semanticRevision(two))
        val store = FileProposalProgressStore(root.resolve("proposals"))
        assertEquals(ProposalProgressWriteResult.Written, store.write(progress(sources.getValue("one"), sources.getValue("two"))) { true })
        val controller = DiscoveryController(cache, FileSemanticIndexStore(root.resolve("index")),
            FileDiscoveryInterestStore(root.resolve("interests")), provider, modelAvailable = { false }, proposalProgressStore = store)
        cache.remove("two"); controller.reconcile(emptyList(), false)
        assertNull("The old source snapshot must not restore withdrawn disk evidence", store.read("one", 0, sources))
        assertEquals(0L, store.bytes())
    }

    @Test fun clearingDiscoveryAlsoClearsPendingProposalProgress() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val store = FileProposalProgressStore(root.resolve("proposals"))
        assertEquals(ProposalProgressWriteResult.Written, store.write(progress("r1", "r2")) { true })
        val controller = DiscoveryController(cache, FileSemanticIndexStore(root.resolve("index")),
            FileDiscoveryInterestStore(root.resolve("interests")), provider, proposalProgressStore = store)
        controller.clearIndex(preserveTopicContinuity = true)
        assertNull(store.read("one", 0, mapOf("one" to "r1", "two" to "r2")))
        assertEquals(0L, store.bytes())
    }

    @Test fun aWithdrawnAndRecachedSourceDoesNotResurrectPendingEvidence() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val one = document("one"); val two = document("two")
        cache.put(CachedDocument(one, null, Instant.EPOCH)); cache.put(CachedDocument(two, null, Instant.EPOCH))
        val sources = mapOf("one" to semanticRevision(one), "two" to semanticRevision(two))
        val store = FileProposalProgressStore(root.resolve("proposals"))
        assertEquals(ProposalProgressWriteResult.Written, store.write(progress(sources.getValue("one"), sources.getValue("two"))) { true })
        val controller = DiscoveryController(cache, FileSemanticIndexStore(root.resolve("index")),
            FileDiscoveryInterestStore(root.resolve("interests")), provider, modelAvailable = { false },
            topicStore = FileTopicStore(root.resolve("topics")), proposalProgressStore = store)
        cache.remove("two"); cache.put(CachedDocument(two, null, Instant.EPOCH))
        assertTrue(cache.invalidations.value.isNotEmpty())
        controller.reconcile(emptyList(), false)
        assertNull(store.read("one", 0, sources))
    }

    @Test fun failedProgressWithdrawalDoesNotAcknowledgeTheCacheInvalidation() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val one = document("one"); val two = document("two")
        cache.put(CachedDocument(one, null, Instant.EPOCH)); cache.put(CachedDocument(two, null, Instant.EPOCH))
        val sources = mapOf("one" to semanticRevision(one), "two" to semanticRevision(two))
        val real = FileProposalProgressStore(root.resolve("proposals"))
        assertEquals(ProposalProgressWriteResult.Written, real.write(progress(sources.getValue("one"), sources.getValue("two"))) { true })
        var failWithdrawal = true
        val failing = object : ProposalProgressStore by real {
            override suspend fun reconcile(sources: Map<String, String>) {
                if (failWithdrawal) throw IOException("withdrawal failed")
                real.reconcile(sources)
            }
        }
        val controller = DiscoveryController(cache, FileSemanticIndexStore(root.resolve("index")),
            FileDiscoveryInterestStore(root.resolve("interests")), provider, modelAvailable = { false },
            topicStore = FileTopicStore(root.resolve("topics")), proposalProgressStore = failing)
        cache.remove("two"); controller.reconcile(emptyList(), false)
        assertTrue("Failed withdrawal must remain pending for a retry", cache.invalidations.value.isNotEmpty())
        assertNotNull(controller.state.value.indexFailure)
        cache.put(CachedDocument(two, null, Instant.EPOCH)); failWithdrawal = false
        controller.reconcile(emptyList(), false)
        assertNull("A same-revision recache cannot resurrect a pending withdrawal", real.read("one", 0, sources))
    }

    @Test fun anInvalidationArrivingDuringTopicRestoreIsWithdrawnBeforeAcknowledgement() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val one = document("one"); val two = document("two")
        cache.put(CachedDocument(one, null, Instant.EPOCH)); cache.put(CachedDocument(two, null, Instant.EPOCH))
        val sources = mapOf("one" to semanticRevision(one), "two" to semanticRevision(two))
        val progressStore = FileProposalProgressStore(root.resolve("proposals"))
        assertEquals(ProposalProgressWriteResult.Written, progressStore.write(progress(sources.getValue("one"), sources.getValue("two"))) { true })
        val realTopics = FileTopicStore(root.resolve("topics"))
        assertTrue(realTopics.write(TopicSnapshot(identity = provider.identity, canonicalCatalogReady = true,
            acceptedRevisions = sources)))
        var inject = true
        val topics = object : TopicStore by realTopics {
            override suspend fun inspectMetadata(): TopicMetadataState {
                if (inject) { inject = false; cache.remove("two"); cache.put(CachedDocument(two, null, Instant.EPOCH)) }
                return realTopics.inspectMetadata()
            }
        }
        val controller = DiscoveryController(cache, FileSemanticIndexStore(root.resolve("index")),
            FileDiscoveryInterestStore(root.resolve("interests")), provider, modelAvailable = { false },
            topicStore = topics, proposalProgressStore = progressStore)
        controller.reconcile(emptyList(), false)
        assertFalse("The test must inject an invalidation inside restore", inject)
        assertNull("Acknowledgement cannot leave withdrawn references recoverable", progressStore.read("one", 0, sources))
    }

    @Test fun discoveryStorageAccountingIncludesPendingProposalProgress() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val one = document("one"); val two = document("two")
        cache.put(CachedDocument(one, null, Instant.EPOCH)); cache.put(CachedDocument(two, null, Instant.EPOCH))
        val store = FileProposalProgressStore(root.resolve("proposals"))
        assertEquals(ProposalProgressWriteResult.Written, store.write(progress(semanticRevision(one), semanticRevision(two))) { true })
        val index = FileSemanticIndexStore(root.resolve("index"))
        val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(root.resolve("interests")),
            provider, modelAvailable = { false }, proposalProgressStore = store)
        controller.reconcile(emptyList(), false)
        assertTrue(store.bytes() > 0)
        assertEquals(index.bytes() + store.bytes(), controller.state.value.bytes)
    }

    @Test fun failedWithdrawalRemainsRecoverableAfterProcessRecreation() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val one = document("one"); val two = document("two")
        cache.put(CachedDocument(one, null, Instant.EPOCH)); cache.put(CachedDocument(two, null, Instant.EPOCH))
        val sources = mapOf("one" to semanticRevision(one), "two" to semanticRevision(two))
        val store = FileProposalProgressStore(root.resolve("proposals"))
        assertEquals(ProposalProgressWriteResult.Written, store.write(progress(sources.getValue("one"), sources.getValue("two"))) { true })
        val failing = object : ProposalProgressStore by store {
            override suspend fun reconcile(sources: Map<String, String>) { throw IOException("withdrawal failed") }
        }
        val first = DiscoveryController(cache, FileSemanticIndexStore(root.resolve("index")),
            FileDiscoveryInterestStore(root.resolve("interests")), provider, modelAvailable = { false },
            topicStore = FileTopicStore(root.resolve("topics")), proposalProgressStore = failing)
        cache.remove("two"); first.reconcile(emptyList(), false)
        assertTrue(cache.invalidations.value.isNotEmpty())
        val restoredCache = FileDocumentCache(root.resolve("cache"))
        assertTrue(restoredCache.invalidations.value.isNotEmpty())
        val restoredStore = FileProposalProgressStore(root.resolve("proposals"))
        val restarted = DiscoveryController(restoredCache, FileSemanticIndexStore(root.resolve("index")),
            FileDiscoveryInterestStore(root.resolve("interests")), provider, modelAvailable = { false },
            topicStore = FileTopicStore(root.resolve("topics")), proposalProgressStore = restoredStore)
        restarted.reconcile(emptyList(), false)
        assertNull(restoredStore.read("one", 0, sources))
        assertEquals(0L, restoredStore.bytes())
    }

    @Test fun cacheInvalidationWithdrawsPersistedAbstentionBeforeSameRevisionRecache() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val one = document("one"); val two = document("two")
        cache.put(CachedDocument(one, null, Instant.EPOCH)); cache.put(CachedDocument(two, null, Instant.EPOCH))
        val sources = mapOf("one" to semanticRevision(one), "two" to semanticRevision(two))
        val store = FileProposalProgressStore(root.resolve("proposals"))
        val record = ProposalAbstentionRecord(semanticHash("work"), listOf(
            ProposalEvidenceRef("one", sources.getValue("one"), "p1"),
            ProposalEvidenceRef("two", sources.getValue("two"), "p2")), DiscoveryAbstentionReason.MIXED_EVIDENCE)
        assertEquals(ProposalProgressWriteResult.Written, store.writeAbstention(record) { true })
        val controller = DiscoveryController(cache, FileSemanticIndexStore(root.resolve("index")),
            FileDiscoveryInterestStore(root.resolve("interests")), provider, modelAvailable = { false },
            topicStore = FileTopicStore(root.resolve("topics")), proposalProgressStore = store)
        cache.remove("two"); cache.put(CachedDocument(two, null, Instant.EPOCH))
        controller.reconcile(emptyList(), false)
        assertNull(store.readAbstention(record.workKey, sources))
        assertEquals(0L, store.bytes())
    }

    @Test fun cacheInvalidationWithdrawsSupportBeforeSameRevisionRecache() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val one = document("one"); val two = document("two")
        cache.put(CachedDocument(one, null, Instant.EPOCH)); cache.put(CachedDocument(two, null, Instant.EPOCH))
        val sources = mapOf("one" to semanticRevision(one), "two" to semanticRevision(two))
        val store = FileProposalProgressStore(root.resolve("proposals"))
        val assessment = TopicSupportAssessment("topic", "concept", ProposalEvidenceRef("two", sources.getValue("two"), "p2"),
            TopicLabelIdentity("a", "t", "template", "prompt", "schema", "policy", "CPU"), semanticHash("support"), SupportDecision.SUPPORTED)
        assertEquals(ProposalProgressWriteResult.Written, store.writeSupport(assessment) { true })
        val controller = DiscoveryController(cache, FileSemanticIndexStore(root.resolve("index")),
            FileDiscoveryInterestStore(root.resolve("interests")), provider, modelAvailable = { false },
            topicStore = FileTopicStore(root.resolve("topics")), proposalProgressStore = store)
        cache.remove("two"); cache.put(CachedDocument(two, null, Instant.EPOCH))
        controller.reconcile(emptyList(), false)
        assertNull(store.readSupport(assessment.workKey, sources)); assertEquals(0L, store.bytes())
    }
}
