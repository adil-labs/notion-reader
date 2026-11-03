package com.example.notionreader.application

import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.discovery.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CanonicalMembershipSchedulerIntegrationTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun reconciliationAutomaticallyUpdatesAnExistingCanonicalTopic() = runBlocking { scenario(false) }
    @Test fun canonicalMembershipUpdatesDoNotRequireCompletedFormation() = runBlocking { scenario(true) }
    @Test fun migrationPreservesAnExistingCanonicalTopicIdentity() = runBlocking { scenario(false, migrateLegacy = true) }
    @Test fun newIndexedSourceUpdatesMembershipWithoutFormation() = runBlocking { scenario(true, newSource = true) }
    @Test fun aCompatibleLegacyCatalogMigratesWhenANewSourceWasAdded() = runBlocking { scenario(false, migrateLegacy = true, newSource = true) }
    @Test fun withdrawingASourceLeavesConnectionCoveragePending() = runBlocking { scenario(true, withdraw = true) }
    @Test fun connectionsAdvanceWhileMembershipVerifierIsUnavailable() = runBlocking { scenario(true, supportUnavailable = true) }
    @Test fun migrationWithdrawsADeletedSourceAndPreservesTheSurvivingTopicAndFollow() = runBlocking {
        scenario(false, migrateLegacy = true, earlyWithdrawal = true)
    }
    @Test fun migrationRollsBackAWithdrawalImmediatelyAfterTheCatalogWrite() = runBlocking {
        scenario(false, migrateLegacy = true, migrationFault = "withdraw")
    }
    @Test fun aRejectedMigrationPreservesThePreviousCatalogAndFollow() = runBlocking {
        scenario(false, migrateLegacy = true, migrationFault = "reject")
    }
    private suspend fun scenario(incrementalCatalog: Boolean, migrateLegacy: Boolean = false, newSource: Boolean = false, withdraw: Boolean = false,
        supportUnavailable: Boolean = false, earlyWithdrawal: Boolean = false, migrationFault: String? = null) {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index")); val progress = FileProposalProgressStore(root.resolve("progress"))
        val model = EmbeddingIdentity("model", 2); val verifier = TopicLabelIdentity("a", "t", "template", "support", "schema", "v1", "CPU")
        val generator = verifier.copy(prompt = "proposal")
        val text = "Moisture and warmth help seeds germinate and begin growing."
        suspend fun add(id: String): Pair<ReaderDocument, SemanticRecord> {
            val doc = ReaderDocument.fromChunkTexts(id, id, listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
            val passage = SemanticPassage("$id:p", id, "$id:s", "Seeds", "$id:b", "$id:c", 0, text.length, text, text, 0)
            val metadata = SemanticMetadata.from(ReaderIndexEntry(id, doc.title, doc.lastEditedAt))
            val record = SemanticRecord(id, semanticRevision(doc), model, metadata, listOf(EmbeddedPassage(passage, listOf(1f, 0f))))
            assertTrue(index.write(record)); return doc to record
        }
        val (seedDoc, seed) = add("seed"); val (laterDoc, later) = add("later")
        index.setPaused(false)
        val sources = mapOf("seed" to seed.revision, "later" to later.revision)
        val accepted = if (newSource) mapOf("seed" to seed.revision) else sources
        val p = seed.passages.single(); val ref = ProposalEvidenceRef("seed", seed.revision, p.passage.id)
        val offered = TopicLabelEvidence(ref.passageId, ref.pageId, ref.revision, text)
        val proposal = DiscoveryProposalRequest(semanticHash("proposal"), 0, listOf(offered), generator)
        val response = DiscoveryProposalResponse.Concept("Seed germination", "Moisture helps seeds germinate.", "Listen about seeds.", listOf(ref.passageId))
        val key = (validateDiscoveryProposal(proposal, response, 0, sources) as DiscoveryProposalValidation.Accepted).workKey
        val pending = TopicSupportRequest("pending:$key", key, response.name, response.definition, offered, verifier, 0)
        val assessment = (validateTopicSupport(pending, SupportDecision.SUPPORTED, 0, key, sources) as TopicSupportValidation.Accepted).assessment
        assertEquals(ProposalProgressWriteResult.Written, progress.writeSupport(assessment) { true })
        val policy = TopicRetrievalPolicy(.8f)
        val query = TopicQueryEmbedding(TopicRetrievalQuery("topic", key, "${response.name}\n${response.definition}", model, policy), listOf(1f, 0f))
        val concept = CanonicalConceptEvidence(key, response.name, response.definition, response.listeningDescription,
            generator, verifier, listOf(ref), listOf(assessment), listOf(ref), mapOf("seed" to seed.revision), newSource,
            proposal.proposalKey, queryEmbedding = query)
        val member = TopicMembership("seed", seed.metadata, p.passage, 1f, p.passage, seed.revision)
        val topic = DiscoveredTopic("topic", "Seeds", p.passage, p, listOf(member), 1, canonicalConcept = concept)
        val metadata = mapOf("seed" to SemanticMetadata.from(ReaderIndexEntry("seed", seedDoc.title, seedDoc.lastEditedAt)),
            "later" to SemanticMetadata.from(ReaderIndexEntry("later", laterDoc.title, laterDoc.lastEditedAt)))
        val topicPolicy = TopicPolicy()
        val graph = FileTopicStore(root.resolve("topics"))
        assertEquals(TopicWriteResult.Written, graph.writeDetailed(TopicSnapshot(topics = listOf(topic), identity = model,
            complete = !incrementalCatalog, canonicalCatalogReady = incrementalCatalog, connectionPassComplete = !supportUnavailable, acceptedRevisions = accepted, policyVersion = topicPolicy.version,
            sourceSignature = topicSourceSignature(model, topicPolicy, semanticMetadataHash(metadata), sources))))
        val interests = FileDiscoveryInterestStore(root.resolve("interests"))
        if (earlyWithdrawal || migrationFault != null) {
            interests.write(listOf(DiscoveryInterest("follow", "seed germination", displayName = "My seeds", originTopicId = "topic")))
            if (earlyWithdrawal) cache.remove("later")
        }
        var migrationAttempted = false
        val migratingStore = object : TopicStore by graph {
            override suspend fun writeGuarded(snapshot: TopicSnapshot, current: () -> Boolean): TopicGuardedWriteResult {
                if (migrationFault != null && snapshot.canonicalCatalogReady && !migrationAttempted) {
                    migrationAttempted = true
                    if (migrationFault == "reject") return TopicGuardedWriteResult.Rejected(TopicWriteResult.Rejected(TopicWriteFailure.IoFailure))
                    val result = graph.writeGuarded(snapshot, current)
                    if (result == TopicGuardedWriteResult.Written) cache.remove("later")
                    return result
                }
                return graph.writeGuarded(snapshot, current)
            }
        }
        var calls = 0
        val runtime = object : TopicLabelRuntime, TopicSupportRuntime {
            override suspend fun label(request: TopicLabelRequest): TopicLabelRun = error("No legacy naming")
            override suspend fun verifySupport(request: TopicSupportRequest): TopicSupportRun { calls++; return TopicSupportRun.Completed(SupportDecision.SUPPORTED) }
            override suspend fun close() {}
        }
        val provider = object : EmbeddingProvider {
            override val identity = model
            override val tokenizer = object : SemanticTokenizer { override fun tokens(text: String) = emptyList<SemanticToken>() }
            override suspend fun embed(text: String) = listOf(1f, 0f)
            override fun release() {}
        }
        fun controller() = DiscoveryController(cache, index, interests, provider,
            topicStore = migratingStore, topicLabelRuntime = runtime, topicLabelModelAvailable = { true },
            topicRetrievalStore = FileTopicRetrievalStore(root.resolve("queries")), topicRetrievalPolicy = policy,
            proposalProgressStore = FileProposalProgressStore(root.resolve("progress")), topicSupportIdentity = { if (supportUnavailable) null else verifier },
            enableEvidenceFirstScheduling = incrementalCatalog || migrateLegacy)
        var controller = controller()
        controller.reconcile(emptyList(), false)
        if (migrationFault != null) {
            assertTrue(migrationAttempted)
            val persisted = FileTopicStore(root.resolve("topics")).readMetadata()!!
            assertFalse(persisted.canonicalCatalogReady)
            assertEquals("topic", persisted.topics.single().id)
            assertEquals("topic", interests.read().single().originTopicId)
            assertEquals("My seeds", interests.read().single().displayName)
            if (migrationFault == "withdraw") {
                assertFalse(persisted.acceptedRevisions.containsKey("later"))
                assertTrue(cache.invalidations.value.isNotEmpty())
            } else {
                assertEquals(sources, persisted.acceptedRevisions)
                assertNotNull(controller.state.value.topicFailure)
            }
            return
        }
        if (migrateLegacy) assertTrue("state=${controller.state.value}", controller.state.value.topicSnapshot.canonicalCatalogReady)
        if (earlyWithdrawal) {
            val persisted = FileTopicStore(root.resolve("topics")).readMetadata()!!
            assertEquals("topic", persisted.topics.single().id)
            assertFalse(persisted.acceptedRevisions.containsKey("later"))
            assertEquals("topic", interests.read().single().originTopicId)
            assertEquals("My seeds", interests.read().single().displayName)
            assertTrue(cache.invalidations.value.isEmpty())
            return
        }
        assertEquals(0, calls)
        if (supportUnavailable) {
            repeat(30) { controller.reconcile(emptyList(), true) }
            assertTrue("Connections do not require a membership verifier", FileTopicStore(root.resolve("topics")).read()!!.connectionPassComplete)
            assertTrue(controller.hasPendingWork)
            assertNotNull(controller.state.value.topicFailure)
            assertEquals(0, calls)
            return
        }
        controller.reconcile(emptyList(), true)
        assertEquals(1, calls)
        if (newSource) assertEquals(sources, controller.state.value.topicSnapshot.acceptedRevisions)
        assertNotNull(progress.readConceptScan("later", sources))
        controller = controller() // Simulate process-owned controller recreation with the same durable stores.
        controller.reconcile(emptyList(), false)
        repeat(12) { if (controller.state.value.topicSnapshot.topics.single().matchingMembers.size < 2) controller.reconcile(emptyList(), true) }
        val result = controller.state.value.topicSnapshot.topics.single()
        assertEquals("topic", result.id)
        assertEquals(setOf("seed", "later"), result.matchingMembers.map { it.pageId }.toSet())
        assertEquals(TopicPublicationStatus.Established, discoveryTopicPublicationStatus(result))
        if (incrementalCatalog) assertFalse(controller.state.value.topicSnapshot.complete)
        assertEquals(1, calls)
        controller.reconcile(emptyList(), true)
        if (incrementalCatalog || migrateLegacy) {
            assertTrue("Proposal work remains pending without a generator", controller.hasPendingWork)
            assertNotNull("Unavailable proposal work must not spin as if it could advance", controller.state.value.topicFailure)
        } else assertFalse(controller.hasPendingWork)
        if (withdraw) {
            cache.remove("later")
            controller.reconcile(emptyList(), false)
            assertFalse("Withdrawal requires connection reconciliation", controller.state.value.topicSnapshot.connectionPassComplete)
            assertFalse(controller.state.value.topicSnapshot.acceptedRevisions.containsKey("later"))
        }
    }
}
