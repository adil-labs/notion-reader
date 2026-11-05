package com.example.notionreader.application

import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.document.CachedDocument
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CanonicalProposalWithdrawalIntegrationTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun withdrawingASourceDuringProposalInferencePromptlyClearsCatalogAndRejectsLateCandidate() = runBlocking {
        val root = files.newFolder()
        val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index"))
        val model = EmbeddingIdentity("model", 2)

        val noteText = "Moisture and warmth support the germination of seeds."
        val noteDoc = ReaderDocument.fromChunkTexts("note", "Seeds", listOf(noteText))
        cache.put(CachedDocument(noteDoc, null, Instant.EPOCH))
        val noteRevision = semanticRevision(noteDoc)
        val notePassage = SemanticPassage("note-p", "note", "note-s", "Seeds", "note-b", "note-c", 0, noteText.length, noteText, noteText, 0)
        assertTrue(index.write(SemanticRecord("note", noteRevision, model, SemanticMetadata("Seeds"), listOf(EmbeddedPassage(notePassage, listOf(1f, 0f))))))
        index.setPaused(false)

        val interests = FileDiscoveryInterestStore(root.resolve("interests"))
        val graph = FileTopicStore(root.resolve("topics"), allowFreshInitialization = true)
        val queries = FileTopicRetrievalStore(root.resolve("queries"))
        val progress = FileProposalProgressStore(root.resolve("progress"))
        val identity = TopicLabelIdentity("a", "t", "template", "proposal", "schema", "v1", "CPU")

        val proposalEntered = CompletableDeferred<Unit>()
        val proposalRelease = CompletableDeferred<Unit>()
        var suspendProposal = false
        var proposalCancelled = false
        var proposalPassedRelease = false
        var callbackReturned = false

        val runtime = object : TopicLabelRuntime, DiscoveryProposalRuntime, TopicSupportRuntime {
            override suspend fun label(request: TopicLabelRequest): TopicLabelRun = error("Legacy naming is not used")
            override suspend fun propose(request: DiscoveryProposalRequest): DiscoveryProposalRun {
                if (suspendProposal && request.evidence.any { it.pageId == "other" }) {
                    proposalEntered.complete(Unit)
                    try {
                        proposalRelease.await()
                    } catch (cancelled: CancellationException) {
                        proposalCancelled = true
                        withContext(NonCancellable) {
                            proposalRelease.await()
                        }
                    }
                    proposalPassedRelease = true
                    callbackReturned = true
                    return DiscoveryProposalRun.Completed(DiscoveryProposalResponse.Concept(
                        "Garden tool maintenance",
                        "Careful maintenance keeps garden tools working well.",
                        "Listen to methods for maintaining garden tools.",
                        listOf(request.evidence.first().passageId)
                    ))
                }
                return DiscoveryProposalRun.Completed(DiscoveryProposalResponse.Concept(
                    "Seed germination conditions",
                    "Moisture and warmth support seed germination.",
                    "Listen to conditions for seed germination.",
                    listOf(request.evidence.first().passageId)
                ))
            }
            override suspend fun verifySupport(request: TopicSupportRequest): TopicSupportRun {
                return TopicSupportRun.Completed(
                    if (!request.topicId.startsWith("pending:") && request.evidence.pageId == "other")
                        SupportDecision.UNSUPPORTED else SupportDecision.SUPPORTED
                )
            }
            override suspend fun close() {}
        }

        val provider = object : EmbeddingProvider {
            override val identity = model
            override val tokenizer = object : SemanticTokenizer { override fun tokens(text: String) = emptyList<SemanticToken>() }
            override suspend fun embed(text: String) = listOf(1f, 0f)
            override fun release() {}
        }

        val controller = DiscoveryController(
            cache,
            index,
            interests,
            provider,
            topicStore = graph,
            topicLabelRuntime = runtime,
            topicLabelModelAvailable = { true },
            topicRetrievalStore = queries,
            proposalProgressStore = progress,
            topicSupportIdentity = { identity.copy(prompt = "support") },
            topicProposalIdentity = { identity }
        )

        controller.reconcile(emptyList(), false)
        repeat(80) { controller.reconcile(emptyList(), true) }

        val initialSaved = graph.readMetadata()
        assertNotNull("Catalog must be initialized", initialSaved)
        assertTrue("Catalog must be canonical ready", initialSaved!!.canonicalCatalogReady)
        assertEquals("Initial catalog must have exactly one canonical concept", 1, initialSaved.topics.count { it.canonicalConcept != null })
        assertEquals("Initial concept must be visible in UI", 1, controller.state.value.topicSnapshot.topics.size)
        val initialTopic = controller.state.value.topicSnapshot.topics.single()
        assertEquals("Seed germination conditions", initialTopic.canonicalConcept?.name)
        assertEquals(setOf("note"), initialSaved.acceptedRevisions.keys)

        val otherText = "Garden tools and their maintenance make outdoor work easier."
        val otherDoc = ReaderDocument.fromChunkTexts("other", "Tools", listOf(otherText))
        cache.put(CachedDocument(otherDoc, null, Instant.EPOCH))
        val otherRevision = semanticRevision(otherDoc)
        val otherPassage = SemanticPassage("other-p", "other", "other-s", "Tools", "other-b", "other-c", 0,
            otherText.length, otherText, otherText, 0)
        assertTrue(index.write(SemanticRecord("other", otherRevision, model, SemanticMetadata("Tools"),
            listOf(EmbeddedPassage(otherPassage, listOf(0f, 1f))))))

        suspendProposal = true
        val backgroundJob = launch {
            while (!proposalEntered.isCompleted) {
                controller.reconcile(emptyList(), true)
            }
        }

        val watchdogFired = AtomicBoolean(false)
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        val watchdogTask = watchdog.schedule({
            watchdogFired.set(true)
            proposalRelease.complete(Unit)
        }, 15, TimeUnit.SECONDS)

        try {
            withTimeout(5_000) {
                proposalEntered.await()
            }
            assertTrue("Proposal inference must be entered before withdrawal", proposalEntered.isCompleted)

            cache.remove("other")
            withTimeout(5_000) {
                controller.reconcile(emptyList(), false)
            }

            val intermediateUi = controller.state.value.topicSnapshot.topics
            assertEquals("UI must retain only the initial concept during suspended inference", 1, intermediateUi.size)
            assertEquals("Seed germination conditions", intermediateUi.single().canonicalConcept?.name)
            assertTrue("UI must not contain evidence from withdrawn source", intermediateUi.flatMap { it.members }.none { it.pageId == "other" })

            val intermediateDurable = graph.readMetadata()
            assertNotNull(intermediateDurable)
            assertEquals("Persisted catalog must retain only the initial concept", 1, intermediateDurable!!.topics.size)
            assertEquals("Persisted catalog must not contain withdrawn source in acceptedRevisions", setOf("note"), intermediateDurable.acceptedRevisions.keys)
            assertTrue("Persisted catalog must not contain evidence from withdrawn source", intermediateDurable.topics.flatMap { it.members }.none { it.pageId == "other" })

            assertTrue("Proposal inference must have observed cancellation", proposalCancelled)
            assertTrue("Fake must remain in-flight before release", backgroundJob.isActive)
            assertFalse("Fake must not have passed release latch before release", proposalPassedRelease)
            assertFalse("Callback must not have returned before release", callbackReturned)

            proposalRelease.complete(Unit)
            withTimeout(5_000) {
                backgroundJob.join()
            }

            assertFalse("Watchdog must not have fired on the passing path", watchdogFired.get())
            assertTrue("Fake must have passed release latch", proposalPassedRelease)
            assertTrue("Callback must have returned late response", callbackReturned)

            val finalUi = controller.state.value.topicSnapshot.topics
            assertEquals("UI must still retain only the initial concept after fake completes", 1, finalUi.size)
            assertEquals("Seed germination conditions", finalUi.single().canonicalConcept?.name)
            assertTrue("Late proposal cannot publish withdrawn concept to UI", finalUi.none { it.canonicalConcept?.name == "Garden tool maintenance" })
            assertTrue("UI must not contain any members from withdrawn source", finalUi.flatMap { it.members }.none { it.pageId == "other" })

            val finalDurable = graph.readMetadata()
            assertNotNull(finalDurable)
            assertEquals("Persisted catalog must retain only the initial concept after late fake completion", 1, finalDurable!!.topics.size)
            assertEquals("Persisted catalog acceptedRevisions must only contain note", setOf("note"), finalDurable.acceptedRevisions.keys)
            assertTrue("Persisted catalog must not contain late concept", finalDurable.topics.none { it.canonicalConcept?.name == "Garden tool maintenance" })
            assertTrue("Persisted catalog must not contain evidence from withdrawn source", finalDurable.topics.flatMap { it.members }.none { it.pageId == "other" })

            val lateProgress = progress.read("other", 0, mapOf("note" to noteRevision, "other" to otherRevision))
            assertNull("Proposal progress store must not retain progress for withdrawn source", lateProgress)
            assertFalse("Watchdog must not have fired on the passing path", watchdogFired.get())
        } finally {
            watchdogTask.cancel(true)
            watchdog.shutdownNow()
            proposalRelease.complete(Unit)
            backgroundJob.cancelAndJoin()
        }
    }
}
