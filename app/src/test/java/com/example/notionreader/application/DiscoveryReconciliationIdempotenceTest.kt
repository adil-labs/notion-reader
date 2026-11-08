package com.example.notionreader.application

import com.example.notionreader.data.local.FileDiscoveryInterestStore
import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.data.local.FileProposalProgressStore
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.data.local.FileTopicRetrievalStore
import com.example.notionreader.data.local.FileTopicStore
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.domain.discovery.*
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reconciliation step is a scheduler turn, not an inventory rebuild. Once the cached sources,
 * model identity and browse metadata are unchanged, the next turn must not re-read the whole
 * index or revalidate every proposal-progress file; otherwise each bounded work step pays the
 * full corpus cost and proposal/membership work barely advances.
 */
class DiscoveryReconciliationIdempotenceTest {
    private class Provider : EmbeddingProvider {
        override val identity = EmbeddingIdentity("reconciliation-idempotence", 2)
        override val tokenizer = object : SemanticTokenizer {
            override fun tokens(text: String) = Regex("\\S+").findAll(text)
                .map { SemanticToken(1, it.range.first, it.range.last + 1) }.toList()
        }
        override suspend fun embed(text: String): List<Float> = listOf(1f, 0f)
    }

    private class CountingCache(private val delegate: DocumentCache) : DocumentCache by delegate {
        var snapshots = 0
        override suspend fun snapshot(): List<CachedDocument> {
            snapshots++
            return delegate.snapshot()
        }
    }

    private class CountingIndex(private val delegate: SemanticIndexStore) : SemanticIndexStore by delegate {
        var reads = 0
        override suspend fun read(pageId: String): SemanticRecord? {
            reads++
            return delegate.read(pageId)
        }
    }

    private class CountingProgress(private val delegate: ProposalProgressStore) : ProposalProgressStore by delegate {
        var reconciles = 0
        override suspend fun reconcile(sources: Map<String, String>) {
            reconciles++
            delegate.reconcile(sources)
        }
    }

    @Test
    fun anUnchangedCorpusIsNotFullyRereadOnEveryReconciliationStep() = runBlocking {
        val dir = Files.createTempDirectory("reconciliation-idempotence").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val progress = FileProposalProgressStore(dir.resolve("proposals"))
            repeat(12) { n ->
                val id = "p%02d".format(n)
                val doc = ReaderDocument.fromChunkTexts(id, "Note $n",
                    listOf("Forest ecology sustains diverse woodland habitats and wildlife $n."))
                cache.put(CachedDocument(doc, null, Instant.EPOCH))
            }
            index.setPaused(false)
            val countingCache = CountingCache(cache)
            val countingIndex = CountingIndex(index)
            val countingProgress = CountingProgress(progress)
            val controller = DiscoveryController(countingCache, countingIndex,
                FileDiscoveryInterestStore(dir.resolve("interests")), Provider(),
                proposalProgressStore = countingProgress)

            controller.reconcile(emptyList(), true)
            assertEquals(12, controller.state.value.indexedCount)

            countingCache.snapshots = 0
            countingIndex.reads = 0
            countingProgress.reconciles = 0
            repeat(3) { controller.reconcile(emptyList(), true) }

            assertTrue("An unchanged corpus must not re-decode every cached Note on every step: ${countingCache.snapshots} snapshots",
                countingCache.snapshots <= 1)
            assertTrue("An unchanged corpus must not re-read every index record on every step: ${countingIndex.reads} reads",
                countingIndex.reads <= 1)
            assertTrue("An unchanged corpus must not revalidate every proposal-progress file on every step: ${countingProgress.reconciles} reconciles",
                countingProgress.reconciles <= 1)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun aChangedSourceStillReindexesAfterAnUnchangedStep() = runBlocking {
        val dir = Files.createTempDirectory("reconciliation-change-after-idempotence").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            index.setPaused(false)
            val controller = DiscoveryController(cache, index,
                FileDiscoveryInterestStore(dir.resolve("interests")), Provider())
            val first = ReaderDocument.fromChunkTexts("p", "Note", listOf("Forest ecology evidence."))
            cache.put(CachedDocument(first, null, Instant.EPOCH))
            controller.reconcile(emptyList(), true)
            assertEquals(1, controller.state.value.indexedCount)

            val changed = ReaderDocument.fromChunkTexts("p", "Note", listOf("Wetland ecology evidence."))
            cache.put(CachedDocument(changed, null, Instant.EPOCH))
            controller.reconcile(emptyList(), true)
            assertEquals("A changed source must still be reindexed after an idempotent step",
                1, controller.state.value.indexedCount)
            assertEquals(1, index.pageIds().size)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun repeatedMembershipTurnsDoNotReDecodeTheUnchangedCorpus() = runBlocking {
        val dir = Files.createTempDirectory("membership-source-revisions").toFile()
        try {
            val provider = Provider()
            val model = provider.identity
            val verifier = TopicLabelIdentity("a", "t", "template", "support", "schema", "v1", "CPU")
            val generator = verifier.copy(prompt = "proposal")
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val progress = FileProposalProgressStore(dir.resolve("progress"))
            val text = "Moisture and warmth help seeds germinate and begin growing."
            val sources = linkedMapOf<String, String>()
            val records = linkedMapOf<String, SemanticRecord>()
            repeat(12) { n ->
                val id = "note%02d".format(n)
                val doc = ReaderDocument.fromChunkTexts(id, id, listOf(text))
                cache.put(CachedDocument(doc, null, Instant.EPOCH))
                val passage = SemanticPassage("$id:p", id, "$id:s", "Seeds", "$id:b", "$id:c",
                    0, text.length, text, text, 0)
                val record = SemanticRecord(id, semanticRevision(doc), model, SemanticMetadata(id),
                    listOf(EmbeddedPassage(passage, listOf(1f, 0f))))
                assertTrue(index.write(record))
                sources[id] = record.revision
                records[id] = record
            }
            index.setPaused(false)
            val seed = records.getValue("note00")
            val embedded = seed.passages.single()
            val passage = embedded.passage
            val ref = ProposalEvidenceRef("note00", seed.revision, passage.id)
            val offered = TopicLabelEvidence(ref.passageId, ref.pageId, ref.revision, text)
            val proposal = DiscoveryProposalRequest(semanticHash("membership-idempotence"), 0, listOf(offered), generator)
            val response = DiscoveryProposalResponse.Concept("Seed germination",
                "Moisture helps seeds germinate.", "Listen about seeds.", listOf(ref.passageId))
            val key = (validateDiscoveryProposal(proposal, response, 0, sources) as DiscoveryProposalValidation.Accepted).workKey
            val pending = TopicSupportRequest("pending:$key", key, response.name, response.definition, offered, verifier, 0)
            val assessment = (validateTopicSupport(pending, SupportDecision.SUPPORTED, 0, key, sources)
                as TopicSupportValidation.Accepted).assessment
            assertEquals(ProposalProgressWriteResult.Written, progress.writeSupport(assessment) { true })
            val policy = TopicRetrievalPolicy(.8f)
            val query = TopicQueryEmbedding(TopicRetrievalQuery("topic", key,
                "${response.name}\n${response.definition}", model, policy), listOf(1f, 0f))
            val concept = CanonicalConceptEvidence(key, response.name, response.definition,
                response.listeningDescription, generator, verifier, listOf(ref), listOf(assessment),
                listOf(ref), mapOf("note00" to seed.revision), false, proposal.proposalKey, queryEmbedding = query)
            val member = TopicMembership("note00", seed.metadata, passage, 1f, passage, seed.revision)
            val topic = DiscoveredTopic("topic", "Seeds", passage, embedded, listOf(member), 1, canonicalConcept = concept)
            val metadata = records.mapValues { (_, record) -> record.metadata }
            val topicPolicy = TopicPolicy()
            val graph = FileTopicStore(dir.resolve("topics"))
            assertEquals(TopicWriteResult.Written, graph.writeDetailed(TopicSnapshot(
                topics = listOf(topic), identity = model, canonicalCatalogReady = true, acceptedRevisions = sources,
                policyVersion = topicPolicy.version,
                sourceSignature = topicSourceSignature(model, topicPolicy, semanticMetadataHash(metadata), sources))))
            val runtime = object : TopicLabelRuntime, TopicSupportRuntime {
                override suspend fun label(request: TopicLabelRequest): TopicLabelRun = error("No naming")
                override suspend fun verifySupport(request: TopicSupportRequest): TopicSupportRun =
                    TopicSupportRun.Completed(SupportDecision.SUPPORTED)
                override suspend fun close() {}
            }
            val countingCache = CountingCache(cache)
            val controller = DiscoveryController(countingCache, index,
                FileDiscoveryInterestStore(dir.resolve("interests")), provider,
                topicStore = graph, topicLabelRuntime = runtime, topicLabelModelAvailable = { true },
                topicRetrievalStore = FileTopicRetrievalStore(dir.resolve("queries")),
                topicRetrievalPolicy = policy,
                proposalProgressStore = progress,
                topicSupportIdentity = { verifier }, topicProposalIdentity = { generator })
            controller.reconcile(emptyList(), false)
            countingCache.snapshots = 0
            repeat(4) { controller.reconcile(emptyList(), true) }
            assertTrue("Membership scans must run for the assertion to be meaningful",
                graph.readMetadata()!!.topics.single().canonicalConcept!!.scannedRevisions.isNotEmpty())
            assertTrue("Repeated membership turns must not re-decode the unchanged corpus: ${countingCache.snapshots} snapshots",
                countingCache.snapshots <= 2)
        } finally { dir.deleteRecursively() }
    }
}
