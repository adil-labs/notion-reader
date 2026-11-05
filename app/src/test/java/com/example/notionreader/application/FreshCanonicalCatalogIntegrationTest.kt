package com.example.notionreader.application

import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.document.CacheRemovalReason
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FreshCanonicalCatalogIntegrationTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun newConceptsPublishAutomaticallyUntilInspectionPinsTheView() = runBlocking { scenario("automatic") }
    @Test fun newConceptsWaitForRefreshWhileWithdrawalRemovesEvidenceImmediately() = runBlocking { scenario("inspection") }
    @Test fun aRejectedSecondConceptPreservesTheInspectedConceptAndReportsTheFailure() = runBlocking { for (failure in TopicWriteFailure.entries) scenario("rejected-existing", failure) }
    @Test fun anUnreadableSecondConceptCannotReplaceAnInspectedConcept() = runBlocking { scenario("existing-admission") }
    @Test fun anOutdatedConceptReadbackCannotPublishTheConcept() = runBlocking { scenario("stale-admission") }
    @Test fun anUnreadableConceptWriteCannotPublishTheConcept() = runBlocking { scenario("unreadable-admission") }
    @Test fun aProvenFreshInstallationBuildsATopicWithoutFormation() = runBlocking { scenario("clean") }
    @Test fun anOrphanFollowPreventsEmptyCatalogInitialization() = runBlocking { scenario("follow") }
    @Test fun corruptInterestsPreventEmptyCatalogInitialization() = runBlocking { scenario("corrupt-interest") }
    @Test fun orphanLabelEvidencePreventsEmptyCatalogInitialization() = runBlocking { scenario("label") }
    @Test fun orphanCheckpointPreventsEmptyCatalogInitialization() = runBlocking { scenario("checkpoint") }
    @Test fun orphanProposalPreventsEmptyCatalogInitialization() = runBlocking { scenario("proposal") }
    @Test fun aFreshInstallReconcilesPendingWithdrawalBeforeInitializing() = runBlocking { scenario("withdrawal") }
    @Test fun aFreshInstallWaitsForOtherConsumersBeforeAcknowledgingWithdrawal() = runBlocking { scenario("unacknowledged") }
    @Test fun withdrawalImmediatelyAfterFreshWriteCannotPublishTheEmptyCatalog() = runBlocking { scenario("stale-init") }
    @Test fun clearingAndReopeningRebuildsWithoutLosingAFollow() = runBlocking { scenario("reset") }
    @Test fun aRejectedResetCatalogReportsFailureWithoutLosingTheFollow() = runBlocking { scenario("reset-reject") }
    @Test fun anInterruptedResetBeforeIndexClearRecoversOnRecreation() = runBlocking { scenario("interrupt-index") }
    @Test fun anInterruptedResetBeforeCatalogCommitRecoversOnRecreation() = runBlocking { scenario("interrupt-finish") }
    @Test fun anUnchangedSupportingPassagePreservesTopicIdentityAcrossANoteEdit() = runBlocking { scenario("edited") }
    @Test fun aDifferentConceptCannotInheritTheOldTopicIdentity() = runBlocking { scenario("edited-concept") }
    @Test fun changedEvidenceCannotInheritTheOldTopicIdentity() = runBlocking { scenario("edited-passage") }
    @Test fun ambiguousContinuityCannotTransferAFollow() = runBlocking { scenario("edited-ambiguous") }
    @Test fun permissionLossAfterEvictionPurgesContinuityAndPreservesTheFollow() = runBlocking { scenario("evicted-permission") }
    @Test fun aPermanentlyDeletedSourceCannotTransferItsOldFollowWhenReadded() = runBlocking { scenario("deleted") }
    private suspend fun scenario(kind: String, rejectedFailure: TopicWriteFailure = TopicWriteFailure.IoFailure) {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index")); val model = EmbeddingIdentity("model", 2)
        val text = "Moisture and warmth support the germination of seeds."
        val doc = ReaderDocument.fromChunkTexts("note", "Seeds", listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
        if (kind == "withdrawal" || kind == "unacknowledged") {
            val old = ReaderDocument.fromChunkTexts("old", "Old", listOf("Previously cached content."))
            cache.put(CachedDocument(old, null, Instant.EPOCH)); cache.remove("old")
            assertTrue(cache.invalidations.value.isNotEmpty())
        }
        val revision = semanticRevision(doc); val p = SemanticPassage("p", "note", "s", "Seeds", "b", "c", 0, text.length, text, text, 0)
        assertTrue(index.write(SemanticRecord("note", revision, model, SemanticMetadata("Seeds"), listOf(EmbeddedPassage(p, listOf(1f, 0f))))))
        index.setPaused(false)
        val interests = FileDiscoveryInterestStore(root.resolve("interests"))
        if (kind == "follow") interests.write(listOf(DiscoveryInterest("follow", "seeds", originTopicId = "old-topic")))
        if (kind == "corrupt-interest") { root.resolve("interests").mkdirs(); root.resolve("interests/interests.json").writeText("{broken") }
        val labels = FileTopicLabelStore(root.resolve("labels"))
        if (kind == "label") root.resolve("labels/label-${semanticHash("old")}.json").writeText("{}")
        val graph = FileTopicStore(root.resolve("topics"), allowFreshInitialization = true)
        val checkpoint = FileTopicStore(root.resolve("checkpoint"))
        val queries = FileTopicRetrievalStore(root.resolve("queries")); val progress = FileProposalProgressStore(root.resolve("progress"))
        if (kind == "checkpoint") assertTrue(checkpoint.write(TopicSnapshot(identity = model)))
        if (kind == "proposal") {
            val prior = ProposalConceptRecord(semanticHash("prior"), listOf(ProposalEvidenceRef("note", revision, "p")),
                "Seed germination", "Conditions for seeds to germinate.", "Listen to seed germination.", listOf("p"))
            assertEquals(ProposalProgressWriteResult.Written, progress.writeConcept(prior) { true })
        }
        val identity = TopicLabelIdentity("a", "t", "template", "proposal", "schema", "v1", "CPU")
        var proposedName = "Seed germination conditions"
        val runtime = object : TopicLabelRuntime, DiscoveryProposalRuntime, TopicSupportRuntime {
            override suspend fun label(request: TopicLabelRequest): TopicLabelRun = error("No legacy naming")
            override suspend fun propose(request: DiscoveryProposalRequest): DiscoveryProposalRun {
                return DiscoveryProposalRun.Completed(DiscoveryProposalResponse.Concept(proposedName,
                    "Moisture and warmth support seed germination.", "Listen to conditions for seed germination.",
                    listOf(request.evidence.first().passageId)))
            }
            override suspend fun verifySupport(request: TopicSupportRequest): TopicSupportRun {
                val matches = when (request.name) {
                    "Garden tool maintenance" -> request.evidence.pageId == "other"
                    "Pruning orchard trees" -> request.evidence.pageId == "third"
                    else -> request.evidence.pageId == "note"
                }
                return TopicSupportRun.Completed(
                    if (kind in setOf("existing-admission", "rejected-existing", "inspection", "automatic") && !request.topicId.startsWith("pending:") && !matches)
                        SupportDecision.UNSUPPORTED else SupportDecision.SUPPORTED)
            }
            override suspend fun close() {}
        }
        val provider = object : EmbeddingProvider {
            override val identity = model
            override val tokenizer = object : SemanticTokenizer { override fun tokens(text: String) = emptyList<SemanticToken>() }
            override suspend fun embed(text: String): List<Float> {
                val lower = text.lowercase()
                return if (lower.contains("tool") || lower.contains("pruning")) listOf(0f, 1f)
                else listOf(1f, 0f)
            }
            override fun release() {}
        }
        var acknowledgementLimit = if (kind == "unacknowledged") 0L else Long.MAX_VALUE
        var rejectReset = false
        var unreadableAdmission = false
        var beforeAdmission: TopicSnapshot? = null
        var interruptIndex = false
        var interruptFinish = false
        val controllerIndex = object : SemanticIndexStore by index {
            override suspend fun clear() {
                if (interruptIndex) { interruptIndex = false; throw java.io.IOException("simulated interruption") }
                index.clear()
            }
        }
        val topicStore = if (kind == "unreadable-admission" || kind == "stale-admission" || kind == "existing-admission" || kind == "rejected-existing") object : TopicStore by graph {
            override suspend fun writeGuarded(snapshot: TopicSnapshot, current: () -> Boolean): TopicGuardedWriteResult {
                val target = snapshot.topics.count { it.canonicalConcept != null } >= if (kind == "existing-admission" || kind == "rejected-existing") 2 else 1
                if (!unreadableAdmission && target) beforeAdmission = graph.readMetadata()
                if (target && kind == "rejected-existing") {
                    unreadableAdmission = true
                    return TopicGuardedWriteResult.Rejected(TopicWriteResult.Rejected(rejectedFailure))
                }
                val result = graph.writeGuarded(snapshot, current)
                if (result == TopicGuardedWriteResult.Written && target) unreadableAdmission = true
                return result
            }
            override suspend fun readMetadata(): TopicSnapshot? = if (!unreadableAdmission || kind == "rejected-existing") graph.readMetadata() else if (kind == "stale-admission") beforeAdmission else null
        } else if (kind == "interrupt-finish") object : TopicStore by graph {
            override suspend fun finishReset(snapshot: TopicSnapshot): TopicWriteResult {
                if (interruptFinish) { interruptFinish = false; throw java.io.IOException("simulated interruption") }
                return graph.finishReset(snapshot)
            }
        } else if (kind == "reset-reject") object : TopicStore by graph {
            override suspend fun finishReset(snapshot: TopicSnapshot): TopicWriteResult =
                if (rejectReset) TopicWriteResult.Rejected(TopicWriteFailure.IoFailure) else graph.finishReset(snapshot)
        } else if (kind == "stale-init") object : TopicStore by graph {
            override suspend fun initializeFresh(snapshot: TopicSnapshot, current: () -> Boolean): TopicGuardedWriteResult {
                val result = graph.initializeFresh(snapshot, current)
                if (result == TopicGuardedWriteResult.Written) cache.remove("note")
                return result
            }
        } else graph
        fun controller() = DiscoveryController(cache, controllerIndex, interests, provider, topicStore = topicStore, topicCheckpointStore = if (kind == "checkpoint") checkpoint else null,
            invalidationAcknowledgementLimit = { acknowledgementLimit },
            topicLabelRuntime = runtime, topicLabelModelAvailable = { true }, topicLabelStore = if (kind == "label") labels else null, topicRetrievalStore = queries,
            proposalProgressStore = progress, topicSupportIdentity = { identity.copy(prompt = "support") },
            topicProposalIdentity = { identity })
        var controller = controller()
        controller.reconcile(emptyList(), false)
        if (kind == "unacknowledged") {
            assertNull(graph.readMetadata())
            assertTrue(cache.invalidations.value.isNotEmpty())
            acknowledgementLimit = Long.MAX_VALUE
        } else if (kind != "clean" && kind != "withdrawal" && kind != "reset" && kind != "reset-reject" && kind != "deleted" && kind != "evicted-permission" && kind != "unreadable-admission" && kind != "stale-admission" && kind != "existing-admission" && kind != "rejected-existing" && kind != "inspection" && kind != "automatic" && !kind.startsWith("edited") && !kind.startsWith("interrupt-")) {
            repeat(3) { controller.reconcile(emptyList(), true) }
            assertNull("Unknown pre-existing topic state must not be replaced", graph.readMetadata())
            assertNotNull(controller.state.value.topicFailure)
            if (kind == "follow") assertEquals("old-topic", interests.readForCatalogBootstrap()!!.single().originTopicId)
            if (kind == "stale-init") assertTrue(cache.invalidations.value.isNotEmpty())
            return
        }
        repeat(80) { controller.reconcile(emptyList(), true) }
        if (kind == "unreadable-admission" || kind == "stale-admission") {
            assertTrue("The test must reach an acknowledged concept write", unreadableAdmission)
            assertTrue("Unreadable persisted concepts must not become visible", controller.state.value.topicSnapshot.topics.isEmpty())
            assertTrue("Unreadable persisted concepts must not be published", controller.state.value.publishedTopics.isEmpty())
            assertTrue("The prior catalog must be restored", graph.readMetadata()!!.topics.isEmpty())
            assertNotNull("The storage failure must be visible", controller.state.value.topicFailure)
            return
        }
        val saved = graph.readMetadata()!!
        assertTrue(saved.canonicalCatalogReady)
        assertFalse(saved.complete)
        assertEquals(setOf("note"), saved.acceptedRevisions.keys)
        assertEquals(1, saved.topics.count { it.canonicalConcept != null })
        if (kind in setOf("existing-admission", "rejected-existing", "inspection", "automatic")) {
            multiConceptScenario(kind, controller, cache, index, model, graph, rejectedFailure,
                { proposedName = it }, { unreadableAdmission })
            return
        }
        if (kind == "withdrawal" || kind == "unacknowledged") assertTrue(cache.invalidations.value.isEmpty())
        if (kind == "evicted-permission") {
            val original = saved.topics.single { it.canonicalConcept != null }
            interests.write(listOf(DiscoveryInterest("follow", "seeds", displayName = "My seeds", originTopicId = original.id)))
            cache.remove("note")
            controller.reconcile(emptyList(), false)
            assertTrue(graph.readMetadata()!!.continuity.any { "note" in it.pageIds })
            cache.remove("note", CacheRemovalReason.PERMISSION_DENIED)
            controller.reconcile(emptyList(), false)
            assertTrue(graph.readMetadata()!!.continuity.none { "note" in it.pageIds })
            assertTrue(cache.invalidations.value.isEmpty())
            assertEquals("My seeds", interests.read().single().displayName)
            assertEquals(original.id, interests.read().single().originTopicId)
            return
        }
        if (kind == "deleted") {
            val original = saved.topics.single { it.canonicalConcept != null }
            interests.write(listOf(DiscoveryInterest("follow", "seeds", displayName = "My seeds", originTopicId = original.id)))
            cache.remove("note", CacheRemovalReason.DELETED)
            controller.reconcile(emptyList(), false)
            assertTrue(graph.readMetadata()!!.continuity.none { it.id == original.id })
            cache.put(CachedDocument(doc, null, Instant.EPOCH))
            assertTrue(index.write(SemanticRecord("note", revision, model, SemanticMetadata("Seeds"), listOf(EmbeddedPassage(p, listOf(1f, 0f))))))
            controller = controller()
            repeat(80) { controller.reconcile(emptyList(), true) }
            val regenerated = graph.readMetadata()!!.topics.single { it.canonicalConcept != null }
            assertNotEquals(original.id, regenerated.id)
            assertEquals(original.id, interests.read().single().originTopicId)
            return
        }
        if (kind.startsWith("edited")) {
            val original = saved.topics.single { it.canonicalConcept != null }
            interests.write(listOf(DiscoveryInterest("follow", "seeds", displayName = "My seeds", originTopicId = original.id)))
            if (kind == "edited-ambiguous") {
                val duplicate = saved.continuity.single().copy(id = "duplicate", createdOrder = 2)
                assertEquals(TopicWriteResult.Written, graph.writeDetailed(saved.copy(continuity = saved.continuity + duplicate)))
                controller = controller()
                controller.reconcile(emptyList(), false)
            }
            if (kind == "edited-concept") proposedName = "Conditions for seed sprouting"
            val body = if (kind == "edited-passage") "$text Additional changed evidence." else text
            val editedPassage = p.copy(excerpt = body, input = body, end = body.length)
            val edited = ReaderDocument.fromChunkTexts("note", "Seeds", listOf(body, "An unrelated addition about garden tools."))
            cache.put(CachedDocument(edited, null, Instant.EPOCH))
            val newRevision = semanticRevision(edited)
            controller.reconcile(emptyList(), false)
            assertTrue("Old citation must be withdrawn before regeneration", graph.readMetadata()!!.topics.isEmpty())
            assertTrue(index.write(SemanticRecord("note", newRevision, model, SemanticMetadata("Seeds"), listOf(EmbeddedPassage(editedPassage, listOf(1f, 0f))))))
            controller = controller()
            repeat(80) { controller.reconcile(emptyList(), true) }
            val regenerated = graph.readMetadata()!!.topics.single { it.canonicalConcept != null }
            if (kind == "edited") assertEquals(original.id, regenerated.id)
            else assertNotEquals("Unsafe or ambiguous continuity must not transfer the follow", original.id, regenerated.id)
            assertEquals(original.id, interests.read().single().originTopicId)
            assertEquals("My seeds", interests.read().single().displayName)
            return
        }
        if (kind == "reset" || kind == "reset-reject" || kind.startsWith("interrupt-")) {
            val topicId = saved.topics.single().id
            if (kind == "reset") {
                assertTrue(interests.read().isEmpty())
                controller.refreshTopics()
                controller.followTopic(topicId, "My seeds", "seed germination")
                controller.followTopic(topicId, "Duplicate", "different text")
                assertEquals(1, interests.read().size)
                assertEquals("My seeds", interests.read().single().displayName)
                assertEquals("seed germination", interests.read().single().text)
            } else {
                interests.write(listOf(DiscoveryInterest("follow", "seeds", displayName = "My seeds", originTopicId = topicId)))
            }
            rejectReset = kind == "reset-reject"
            interruptIndex = kind == "interrupt-index"
            interruptFinish = kind == "interrupt-finish"
            if (kind.startsWith("interrupt-")) {
                assertTrue(runCatching { controller.clearIndex() }.isFailure)
                assertEquals(TopicMetadataState.ResetPending, graph.inspectMetadata())
                assertNull("Pending reset must hide the previous catalog", graph.readMetadata())
                assertTrue("Interrupted reset must hide the in-memory catalog", controller.state.value.topicSnapshot.topics.isEmpty())
                assertTrue(controller.state.value.publishedTopics.isEmpty())
                controller = controller()
                controller.reconcile(emptyList(), false)
                assertTrue(index.isPaused())
                assertTrue(index.pageIds().isEmpty())
                assertTrue((graph.inspectMetadata() as TopicMetadataState.Available).snapshot.canonicalCatalogReady)
                assertTrue(graph.readMetadata()!!.topics.isEmpty())
                assertEquals(0L, labels.bytes())
                assertEquals(0L, queries.bytes())
                assertEquals("My seeds", interests.read().single().displayName)
                return
            }
            controller.clearIndex()
            assertTrue(index.isPaused())
            assertEquals(topicId, interests.read().single().originTopicId)
            if (kind == "reset-reject") {
                assertNull(graph.readMetadata())
                assertNotNull("A failed reset write must be visible", controller.state.value.topicFailure)
                controller = controller()
                controller.reconcile(emptyList(), false)
                assertNotNull(controller.state.value.topicFailure)
                assertEquals(topicId, interests.read().single().originTopicId)
                return
            }
            assertTrue("The cleared catalog must be durably ready for rebuilding", graph.readMetadata()!!.canonicalCatalogReady)
            assertTrue(graph.readMetadata()!!.topics.isEmpty())
            assertTrue(index.write(SemanticRecord("note", revision, model, SemanticMetadata("Seeds"), listOf(EmbeddedPassage(p, listOf(1f, 0f))))))
            index.setPaused(false)
            controller = controller()
            repeat(80) { controller.reconcile(emptyList(), true) }
            assertEquals(1, graph.readMetadata()!!.topics.count { it.canonicalConcept != null })
            assertEquals("My seeds", interests.read().single().displayName)
        }
    }
    private suspend fun multiConceptScenario(kind: String, controller: DiscoveryController, cache: FileDocumentCache,
        index: FileSemanticIndexStore, model: EmbeddingIdentity, graph: FileTopicStore, rejectedFailure: TopicWriteFailure,
        setName: (String) -> Unit, unreadableAdmission: () -> Boolean) {
        if (kind == "existing-admission" || kind == "rejected-existing" || kind == "inspection" || kind == "automatic") {
            if (kind != "automatic") controller.refreshTopics()
            val inspected = controller.state.value.topicSnapshot.topics.single()
            if (kind == "automatic") {
                assertEquals(inspected.id, controller.state.value.publishedTopics.single().topic.id)
                assertFalse(controller.state.value.topicsUpdated)
            }
            val otherText = "Garden tools and their maintenance make outdoor work easier."
            val other = ReaderDocument.fromChunkTexts("other", "Tools", listOf(otherText))
            cache.put(CachedDocument(other, null, Instant.EPOCH))
            val otherRevision = semanticRevision(other)
            val otherPassage = SemanticPassage("other-p", "other", "other-s", "Tools", "other-b", "other-c", 0,
                otherText.length, otherText, otherText, 0)
            assertTrue(index.write(SemanticRecord("other", otherRevision, model, SemanticMetadata("Tools"),
                listOf(EmbeddedPassage(otherPassage, listOf(0f, 1f))))))
            setName("Garden tool maintenance")
            repeat(120) { controller.reconcile(emptyList(), true) }
            if (kind == "automatic") {
                assertEquals(2, controller.state.value.topicSnapshot.topics.size)
                assertFalse(controller.state.value.topicsUpdated)
                controller.setTopicsInspected(true)
                val pinned = controller.state.value.topicSnapshot.topics.map { it.id }
                val thirdText = "Careful pruning supports strong growth in orchard trees. See Tools."
                val third = ReaderDocument.fromChunkTexts("third", "Pruning", listOf(thirdText))
                cache.put(CachedDocument(third, null, Instant.EPOCH))
                val thirdRevision = semanticRevision(third)
                val thirdPassage = SemanticPassage("third-p", "third", "third-s", "Pruning", "third-b", "third-c", 0,
                    thirdText.length, thirdText, thirdText, 0)
                assertTrue(index.write(SemanticRecord("third", thirdRevision, model, SemanticMetadata("Pruning"),
                    listOf(EmbeddedPassage(thirdPassage, listOf(0f, 1f))))))
                setName("Pruning orchard trees")
                repeat(160) { controller.reconcile(emptyList(), true) }
                assertEquals(3, graph.readMetadata()!!.topics.size)
                assertEquals(pinned, controller.state.value.topicSnapshot.topics.map { it.id })
                assertTrue(controller.state.value.topicsUpdated)
                controller.setTopicsInspected(false)
                assertEquals(3, controller.state.value.topicSnapshot.topics.size)
                assertFalse(controller.state.value.topicsUpdated)
                return
            }
            if (kind == "inspection") {
                assertEquals("The original inspection stays pinned", listOf(inspected.id), controller.state.value.topicSnapshot.topics.map { it.id })
                assertTrue(controller.state.value.topicsUpdated)
                assertEquals("failure=${controller.state.value.topicFailure}; updated=${controller.state.value.topicsUpdated}; pending=${controller.hasPendingWork}; topics=${graph.readMetadata()!!.topics.map { it.canonicalConcept?.name }}", 2, graph.readMetadata()!!.topics.size)
                controller.refreshTopics()
                assertEquals(2, controller.state.value.topicSnapshot.topics.size)
                assertFalse(controller.state.value.topicsUpdated)
                cache.remove("note")
                controller.reconcile(emptyList(), false)
                val visible = controller.state.value.topicSnapshot.topics
                assertTrue("Withdrawn source evidence cannot remain visible", visible.flatMap { it.members }.none { it.pageId == "note" })
                assertTrue("The independently supported concept remains", visible.any { it.canonicalConcept?.name == "Garden tool maintenance" })
                return
            }
            assertTrue("The second concept must reach its acknowledged write", unreadableAdmission())
            assertEquals(listOf(inspected.id), controller.state.value.topicSnapshot.topics.map { it.id })
            assertEquals(listOf(inspected.id), graph.readMetadata()!!.topics.map { it.id })
            val status = controller.state.value.topicFailure.orEmpty()
            if (kind == "rejected-existing") {
                assertEquals(rejectedFailure == TopicWriteFailure.BudgetExceeded, status.contains("storage is full"))
                assertTrue(status.contains(when (rejectedFailure) {
                    TopicWriteFailure.BudgetExceeded -> "storage is full"
                    TopicWriteFailure.RecordMemoryLimit -> "record memory limit"
                    TopicWriteFailure.InvalidSnapshot -> "validation"
                    TopicWriteFailure.IoFailure -> "I/O"
                    TopicWriteFailure.Unknown -> "unknown"
                }))
            } else assertTrue(status.contains("could not be reloaded"))
            return
        }
    }
}
