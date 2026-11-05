package com.example.notionreader.application

import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.document.CachedDocument
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CanonicalInspectionWithoutInferenceTest {
    @get:Rule val files = TemporaryFolder()

    private class CountingEmbeddingProvider(
        override val identity: EmbeddingIdentity
    ) : EmbeddingProvider {
        var embedCalls = 0
        override val tokenizer = object : SemanticTokenizer {
            override fun tokens(text: String): List<SemanticToken> = emptyList()
        }

        override suspend fun embed(text: String): List<Float> {
            embedCalls++
            return listOf(1f, 0f)
        }

        override fun release() {}
    }

    private class CountingProposalSupportRuntime(
        private val conceptName: String,
        private val definition: String,
        private val listeningDescription: String
    ) : TopicLabelRuntime, DiscoveryProposalRuntime, TopicSupportRuntime {
        var proposalCalls = 0
        var supportCalls = 0
        var labelCalls = 0

        override suspend fun label(request: TopicLabelRequest): TopicLabelRun {
            labelCalls++
            error("Legacy cluster label inference must not run for canonical concept catalog")
        }

        override suspend fun propose(request: DiscoveryProposalRequest): DiscoveryProposalRun {
            proposalCalls++
            return DiscoveryProposalRun.Completed(
                DiscoveryProposalResponse.Concept(
                    name = conceptName,
                    definition = definition,
                    listeningDescription = listeningDescription,
                    evidenceIds = listOf(request.evidence.first().passageId)
                )
            )
        }

        override suspend fun verifySupport(request: TopicSupportRequest): TopicSupportRun {
            supportCalls++
            return TopicSupportRun.Completed(SupportDecision.SUPPORTED)
        }

        override suspend fun close() {}
    }

    @Test
    fun canonicalConceptInspectionAcrossReopeningRequiresNoFurtherInference() = runBlocking {
        val root = files.newFolder()
        val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index"))
        val model = EmbeddingIdentity("inspection-model", 2)

        val passageText = "Dark skies and minimal light pollution are essential for observing faint celestial objects with a telescope."
        val doc = ReaderDocument.fromChunkTexts("note-astronomy", "Urban Astronomy", listOf(passageText))
        cache.put(CachedDocument(doc, null, Instant.EPOCH))
        val revision = semanticRevision(doc)

        val passage = SemanticPassage(
            id = "passage-astronomy-1",
            pageId = "note-astronomy",
            sectionId = "section-astronomy-1",
            sectionTitle = "Urban Astronomy",
            sourceBlockId = "block-1",
            readerChunkId = "chunk-1",
            start = 0,
            end = passageText.length,
            excerpt = passageText,
            input = passageText,
            order = 0
        )
        assertTrue(
            index.write(
                SemanticRecord(
                    pageId = "note-astronomy",
                    revision = revision,
                    identity = model,
                    metadata = SemanticMetadata("Urban Astronomy"),
                    passages = listOf(EmbeddedPassage(passage, listOf(1f, 0f)))
                )
            )
        )
        index.setPaused(false)

        val interests = FileDiscoveryInterestStore(root.resolve("interests"))
        val labels = FileTopicLabelStore(root.resolve("labels"))
        val graph = FileTopicStore(root.resolve("topics"), allowFreshInitialization = true)
        val queries = FileTopicRetrievalStore(root.resolve("queries"))
        val progress = FileProposalProgressStore(root.resolve("progress"))

        val provider = CountingEmbeddingProvider(model)
        val runtime = CountingProposalSupportRuntime(
            conceptName = "Stargazing conditions",
            definition = "Atmospheric and ambient lighting conditions required to observe celestial objects.",
            listeningDescription = "Listen to notes discussing dark skies and telescope observations."
        )

        val identity = TopicLabelIdentity("model-v1", "tokenizer-v1", "template-v1", "proposal", "schema-v1", "v1", "CPU")

        fun createController() = DiscoveryController(
            cache = cache,
            index = index,
            interestStore = interests,
            provider = provider,
            topicStore = graph,
            topicCheckpointStore = null,
            invalidationAcknowledgementLimit = { Long.MAX_VALUE },
            topicLabelRuntime = runtime,
            topicLabelModelAvailable = { true },
            topicLabelStore = labels,
            topicRetrievalStore = queries,
            proposalProgressStore = progress,
            topicSupportIdentity = { identity.copy(prompt = "support") },
            topicProposalIdentity = { identity }
        )

        var controller = createController()
        controller.reconcile(emptyList(), mayIndex = false)
        repeat(80) { controller.reconcile(emptyList(), mayIndex = true) }

        val savedSnapshot = graph.readMetadata()
        assertNotNull("Topic metadata must be persisted", savedSnapshot)
        assertTrue("Canonical catalog must be ready", savedSnapshot!!.canonicalCatalogReady)

        val persistedTopic = savedSnapshot.topics.singleOrNull { it.canonicalConcept != null }
        assertNotNull("A supported canonical concept must be persisted", persistedTopic)
        val persistedConcept = persistedTopic!!.canonicalConcept
        assertNotNull("Canonical concept evidence must be present", persistedConcept)
        assertEquals("Stargazing conditions", persistedConcept!!.name)
        assertEquals(listOf("passage-astronomy-1"), persistedConcept.supported.map { it.passageId })

        val initialProposalCalls = runtime.proposalCalls
        val initialSupportCalls = runtime.supportCalls
        val initialEmbedCalls = provider.embedCalls

        assertTrue("Proposal inference must have executed during concept generation", initialProposalCalls > 0)
        assertTrue("Support inference must have executed during concept verification", initialSupportCalls > 0)
        assertTrue("Embedding inference must have executed during retrieval embedding", initialEmbedCalls > 0)
        assertEquals("Legacy labeling must not be invoked", 0, runtime.labelCalls)

        // Recreate controller with the same file-backed stores
        val reopened = createController()

        // Inspect, refresh, preview, and reconcile without indexing authorization
        reopened.reconcile(emptyList(), mayIndex = false)
        reopened.setTopicsInspected(true)
        reopened.refreshTopics()
        val previewMatches = reopened.topicPreview(persistedTopic.id)
        reopened.reconcile(emptyList(), mayIndex = false)

        // Concept identity and evidence must be visible and match across recreation
        val reopenedTopics = reopened.state.value.topicSnapshot.topics
        val reopenedTopic = reopenedTopics.singleOrNull { it.canonicalConcept != null }
        assertNotNull("Persisted concept must be visible in reopened snapshot", reopenedTopic)
        assertEquals("Concept identity must be preserved across controller recreation", persistedTopic.id, reopenedTopic!!.id)

        val reopenedConcept = reopenedTopic.canonicalConcept
        assertNotNull("Persisted concept evidence must be visible across controller recreation", reopenedConcept)
        assertEquals("Concept name must match across recreation", persistedConcept.name, reopenedConcept!!.name)
        assertEquals("Concept definition must match across recreation", persistedConcept.definition, reopenedConcept.definition)
        assertEquals("Concept supported evidence must match across recreation", persistedConcept.supported, reopenedConcept.supported)
        assertEquals("Concept evidence passage IDs must match", listOf("passage-astronomy-1"), reopenedConcept.supported.map { it.passageId })

        // Preview matches must expose the supported passage without executing model inference
        assertTrue("Topic preview must return evidence matches", previewMatches.isNotEmpty())
        assertEquals("Topic preview must show the supported passage", "passage-astronomy-1", previewMatches.first().passage.id)
        assertEquals("Topic preview must match the source page", canonicalSemanticId("note-astronomy"), previewMatches.first().pageId)

        // Published topics must reflect the admitted topic
        val published = reopened.state.value.publishedTopics
        assertEquals("Published topics must contain the persisted concept", 1, published.size)
        assertEquals(persistedTopic.id, published.single().topic.id)

        // Neither proposal, support, nor embedding call counts may increase
        assertEquals(
            "Proposal inference call count must not increase during inspection or reopening",
            initialProposalCalls,
            runtime.proposalCalls
        )
        assertEquals(
            "Support inference call count must not increase during inspection or reopening",
            initialSupportCalls,
            runtime.supportCalls
        )
        assertEquals(
            "Embedding inference call count must not increase during inspection or reopening",
            initialEmbedCalls,
            provider.embedCalls
        )
        assertEquals("Legacy labeling must never be invoked", 0, runtime.labelCalls)
    }
}
