package com.example.notionreader.application

import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.data.local.FileProposalProgressStore
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.data.local.FileTopicStore
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class EarlyEmergingTopicPublicationTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun verifiedCitationPublishesEmergingBeforeCorpusCompletionWhileUncheckedSourcesAreNotMarkedScanned() = runBlocking {
        val root = files.newFolder()
        val index = FileSemanticIndexStore(root.resolve("index"))
        val store = FileProposalProgressStore(root.resolve("progress"))
        val model = EmbeddingIdentity("model", 2)
        val generator = TopicLabelIdentity("g", "t", "template", "proposal", "schema", "v1", "CPU")
        val verifier = generator.copy(prompt = "support")

        val text1 = "Moisture and warmth allow seeds to sprout and germinate."
        val passage1 = SemanticPassage("p1", "note1", "section1", "Seeds", "block1", "chunk1", 0, text1.length, text1, text1, 0)
        val embedded1 = EmbeddedPassage(passage1, listOf(1f, 0f))
        val record1 = SemanticRecord("note1", "r1", model, SemanticMetadata("Seeds"), listOf(embedded1))
        assertTrue(index.write(record1))

        val text2 = "Seedling emergence occurs when the shoot breaks through soil."
        val passage2 = SemanticPassage("p2", "note2", "section2", "Sprouts", "block2", "chunk2", 0, text2.length, text2, text2, 0)
        val embedded2 = EmbeddedPassage(passage2, listOf(0f, 1f))
        val record2 = SemanticRecord("note2", "r2", model, SemanticMetadata("Sprouts"), listOf(embedded2))
        assertTrue(index.write(record2))

        val sources = mapOf("note1" to "r1", "note2" to "r2")
        val evidence1 = TopicLabelEvidence("p1", "note1", "r1", text1)
        val proposalKey = semanticHash("proposal-early-emerging")
        val request = DiscoveryProposalRequest(proposalKey, 0, listOf(evidence1), generator)
        val response = DiscoveryProposalResponse.Concept("Seed germination", "Moisture and warmth allow seeds to sprout.",
            "Listen to seed germination.", listOf("p1"))
        val key = (validateDiscoveryProposal(request, response, 0, sources) as DiscoveryProposalValidation.Accepted).workKey

        val support = TopicSupportRequest("pending:$key", key, response.name, response.definition, evidence1, verifier, 0)
        val assessment = (validateTopicSupport(support, SupportDecision.SUPPORTED, 0, key, sources) as TopicSupportValidation.Accepted).assessment
        assertEquals(ProposalProgressWriteResult.Written, store.writeSupport(assessment) { true })

        val ref1 = ProposalEvidenceRef("note1", "r1", "p1")
        val query = TopicRetrievalQuery("canonical", key, "${response.name}\n${response.definition}", model, TopicRetrievalPolicy(.85f))
        val embeddedQuery = TopicQueryEmbedding(query, listOf(1f, 0f))

        // Candidate admitted early with incomplete matching: scannedRevisions is empty, matchingComplete is false
        val earlyConcept = CanonicalConceptEvidence(
            workKey = key,
            name = response.name,
            definition = response.definition,
            listeningDescription = response.listeningDescription,
            generator = generator,
            verifier = verifier,
            offered = listOf(ref1),
            citations = listOf(assessment),
            supported = emptyList(),
            scannedRevisions = emptyMap(),
            matchingComplete = false,
            proposalKey = proposalKey,
            queryEmbedding = embeddedQuery
        )
        val member = TopicMembership("note1", record1.metadata, passage1, 1f, passage1, "r1")
        val topic = DiscoveredTopic("canonical", "Seeds", passage1, embedded1, listOf(member), 1, canonicalConcept = earlyConcept)

        val hydrated = hydrateCanonicalTopic(topic, model, sources, index, store) { true }!!

        // Property 1: Emerging publication before corpus completion
        assertEquals(TopicPublicationStatus.Emerging, discoveryTopicPublicationStatus(hydrated))
        assertEquals(listOf("note1"), hydrated.matchingMembers.map { it.pageId })
        assertEquals("Seed germination", hydrated.effectiveLabel)

        // Property 2: matchingComplete remains false until required support checks truly complete
        assertFalse(hydrated.canonicalConcept!!.matchingComplete)

        // Property 3: Unchecked sources are NOT marked scanned
        assertTrue(hydrated.canonicalConcept!!.scannedRevisions.isEmpty())
        assertTrue(hydrated.canonicalConcept!!.supported.isEmpty())
    }

    @Test fun unverifiedOrUnsupportedCitationCannotPublish() = runBlocking {
        val root = files.newFolder()
        val index = FileSemanticIndexStore(root.resolve("index"))
        val store = FileProposalProgressStore(root.resolve("progress"))
        val model = EmbeddingIdentity("model", 2)
        val generator = TopicLabelIdentity("g", "t", "template", "proposal", "schema", "v1", "CPU")
        val verifier = generator.copy(prompt = "support")

        val text = "Moisture and warmth allow seeds to sprout."
        val passage = SemanticPassage("p1", "note1", "section1", "Seeds", "block1", "chunk1", 0, text.length, text, text, 0)
        val embedded = EmbeddedPassage(passage, listOf(1f, 0f))
        val record = SemanticRecord("note1", "r1", model, SemanticMetadata("Seeds"), listOf(embedded))
        assertTrue(index.write(record))

        val sources = mapOf("note1" to "r1")
        val evidence = TopicLabelEvidence("p1", "note1", "r1", text)
        val proposalKey = semanticHash("proposal-unverified")
        val request = DiscoveryProposalRequest(proposalKey, 0, listOf(evidence), generator)
        val response = DiscoveryProposalResponse.Concept("Seed germination", "Moisture allows sprouting.",
            "Listen to seed germination.", listOf("p1"))
        val key = (validateDiscoveryProposal(request, response, 0, sources) as DiscoveryProposalValidation.Accepted).workKey

        val support = TopicSupportRequest("pending:$key", key, response.name, response.definition, evidence, verifier, 0)
        val assessment = (validateTopicSupport(support, SupportDecision.SUPPORTED, 0, key, sources) as TopicSupportValidation.Accepted).assessment
        val ref = ProposalEvidenceRef("note1", "r1", "p1")
        val concept = CanonicalConceptEvidence(
            workKey = key, name = response.name, definition = response.definition,
            listeningDescription = response.listeningDescription, generator = generator, verifier = verifier,
            offered = listOf(ref), citations = listOf(assessment), supported = emptyList(),
            scannedRevisions = emptyMap(), matchingComplete = false, proposalKey = proposalKey
        )
        val member = TopicMembership("note1", record.metadata, passage, 1f, passage, "r1")
        val topic = DiscoveredTopic("canonical", "Seeds", passage, embedded, listOf(member), 1, canonicalConcept = concept)

        // Missing from store -> cannot publish
        assertNull("Missing support in store must prevent hydration",
            hydrateCanonicalTopic(topic, model, sources, index, store) { true })

        // Unsupported decision written -> cannot publish
        val unsupported = (validateTopicSupport(support, SupportDecision.UNSUPPORTED, 0, key, sources) as TopicSupportValidation.Accepted).assessment
        assertEquals(ProposalProgressWriteResult.Written, store.writeSupport(unsupported) { true })
        assertNull("UNSUPPORTED decision must prevent hydration",
            hydrateCanonicalTopic(topic, model, sources, index, store) { true })

        // Superseded on canonical topic ID with UNSUPPORTED -> cannot publish
        val canonicalRejection = (validateTopicSupport(support.copy(topicId = "canonical"), SupportDecision.UNSUPPORTED, 0, key, sources) as TopicSupportValidation.Accepted).assessment
        assertEquals(ProposalProgressWriteResult.Written, store.writeSupport(canonicalRejection) { true })
        assertNull("Canonical-ID rejection supersedes and prevents hydration",
            hydrateCanonicalTopic(topic, model, sources, index, store) { true })
    }

    @Test fun staleOrWithdrawnEvidenceCannotPublish() = runBlocking {
        val root = files.newFolder()
        val index = FileSemanticIndexStore(root.resolve("index"))
        val store = FileProposalProgressStore(root.resolve("progress"))
        val model = EmbeddingIdentity("model", 2)
        val generator = TopicLabelIdentity("g", "t", "template", "proposal", "schema", "v1", "CPU")
        val verifier = generator.copy(prompt = "support")

        val text = "Moisture and warmth allow seeds to sprout."
        val passage = SemanticPassage("p1", "note1", "section1", "Seeds", "block1", "chunk1", 0, text.length, text, text, 0)
        val embedded = EmbeddedPassage(passage, listOf(1f, 0f))
        val record = SemanticRecord("note1", "r1", model, SemanticMetadata("Seeds"), listOf(embedded))
        assertTrue(index.write(record))

        val sources = mapOf("note1" to "r1")
        val evidence = TopicLabelEvidence("p1", "note1", "r1", text)
        val proposalKey = semanticHash("proposal-stale")
        val request = DiscoveryProposalRequest(proposalKey, 0, listOf(evidence), generator)
        val response = DiscoveryProposalResponse.Concept("Seed germination", "Moisture allows sprouting.",
            "Listen to seed germination.", listOf("p1"))
        val key = (validateDiscoveryProposal(request, response, 0, sources) as DiscoveryProposalValidation.Accepted).workKey

        val support = TopicSupportRequest("pending:$key", key, response.name, response.definition, evidence, verifier, 0)
        val assessment = (validateTopicSupport(support, SupportDecision.SUPPORTED, 0, key, sources) as TopicSupportValidation.Accepted).assessment
        assertEquals(ProposalProgressWriteResult.Written, store.writeSupport(assessment) { true })

        val ref = ProposalEvidenceRef("note1", "r1", "p1")
        val concept = CanonicalConceptEvidence(
            workKey = key, name = response.name, definition = response.definition,
            listeningDescription = response.listeningDescription, generator = generator, verifier = verifier,
            offered = listOf(ref), citations = listOf(assessment), supported = emptyList(),
            scannedRevisions = emptyMap(), matchingComplete = false, proposalKey = proposalKey
        )
        val member = TopicMembership("note1", record.metadata, passage, 1f, passage, "r1")
        val topic = DiscoveredTopic("canonical", "Seeds", passage, embedded, listOf(member), 1, canonicalConcept = concept)

        // Revision changed in sources -> hydration fails safely
        assertNull("Stale source revision must prevent hydration",
            hydrateCanonicalTopic(topic, model, mapOf("note1" to "r2"), index, store) { true })

        // Source withdrawn from sources -> hydration fails safely
        assertNull("Withdrawn source must prevent hydration",
            hydrateCanonicalTopic(topic, model, emptyMap(), index, store) { true })
    }

    @Test fun remainingMatchingContinuesToCompletionViaMembershipUpdate() = runBlocking {
        val root = files.newFolder()
        val index = FileSemanticIndexStore(root.resolve("index"))
        val store = FileProposalProgressStore(root.resolve("progress"))
        val model = EmbeddingIdentity("model", 2)
        val generator = TopicLabelIdentity("g", "t", "template", "proposal", "schema", "v1", "CPU")
        val verifier = generator.copy(prompt = "support")

        val text1 = "Moisture and warmth allow seeds to sprout."
        val passage1 = SemanticPassage("p1", "note1", "section1", "Seeds", "block1", "chunk1", 0, text1.length, text1, text1, 0)
        val record1 = SemanticRecord("note1", "r1", model, SemanticMetadata("Seeds"), listOf(EmbeddedPassage(passage1, listOf(1f, 0f))))
        assertTrue(index.write(record1))

        val text2 = "Later growth produces flowers and fruit."
        val passage2 = SemanticPassage("p2", "note2", "section2", "Flowers", "block2", "chunk2", 0, text2.length, text2, text2, 0)
        val record2 = SemanticRecord("note2", "r2", model, SemanticMetadata("Flowers"), listOf(EmbeddedPassage(passage2, listOf(0f, 1f))))
        assertTrue(index.write(record2))

        val sources = mapOf("note1" to "r1", "note2" to "r2")
        val evidence1 = TopicLabelEvidence("p1", "note1", "r1", text1)
        val proposalKey = semanticHash("proposal-progression")
        val request = DiscoveryProposalRequest(proposalKey, 0, listOf(evidence1), generator)
        val response = DiscoveryProposalResponse.Concept("Plant biology", "Growth processes in plants.",
            "Listen to plant biology.", listOf("p1"))
        val key = (validateDiscoveryProposal(request, response, 0, sources) as DiscoveryProposalValidation.Accepted).workKey

        val support1 = TopicSupportRequest("pending:$key", key, response.name, response.definition, evidence1, verifier, 0)
        val assessment1 = (validateTopicSupport(support1, SupportDecision.SUPPORTED, 0, key, sources) as TopicSupportValidation.Accepted).assessment
        assertEquals(ProposalProgressWriteResult.Written, store.writeSupport(assessment1) { true })

        val ref1 = ProposalEvidenceRef("note1", "r1", "p1")
        val earlyConcept = CanonicalConceptEvidence(
            workKey = key, name = response.name, definition = response.definition,
            listeningDescription = response.listeningDescription, generator = generator, verifier = verifier,
            offered = listOf(ref1), citations = listOf(assessment1), supported = emptyList(),
            scannedRevisions = emptyMap(), matchingComplete = false, proposalKey = proposalKey
        )
        val member1 = TopicMembership("note1", record1.metadata, passage1, 1f, passage1, "r1")
        val topic = DiscoveredTopic("canonical", "Seeds", passage1, EmbeddedPassage(passage1, listOf(1f, 0f)),
            listOf(member1), 1, canonicalConcept = earlyConcept)

        // Phase 1: Admitted early with 1 note -> Emerging
        val earlyHydrated = hydrateCanonicalTopic(topic, model, sources, index, store) { true }!!
        assertEquals(TopicPublicationStatus.Emerging, discoveryTopicPublicationStatus(earlyHydrated))
        assertFalse(earlyHydrated.canonicalConcept!!.matchingComplete)
        assertEquals(listOf("note1"), earlyHydrated.matchingMembers.map { it.pageId })

        // Phase 2: note2 is checked by membership scan and supported
        val evidence2 = TopicLabelEvidence("p2", "note2", "r2", text2)
        val support2 = TopicSupportRequest("canonical", key, response.name, response.definition, evidence2, verifier, 0)
        val assessment2 = (validateTopicSupport(support2, SupportDecision.SUPPORTED, 0, key, sources) as TopicSupportValidation.Accepted).assessment
        assertEquals(ProposalProgressWriteResult.Written, store.writeSupport(assessment2) { true })

        // Canonical ID support for note1 is also written
        val canonicalSupport1 = TopicSupportRequest("canonical", key, response.name, response.definition, evidence1, verifier, 0)
        val canonicalAssessment1 = (validateTopicSupport(canonicalSupport1, SupportDecision.SUPPORTED, 0, key, sources) as TopicSupportValidation.Accepted).assessment
        assertEquals(ProposalProgressWriteResult.Written, store.writeSupport(canonicalAssessment1) { true })

        // Both notes scanned and completed
        val ref2 = ProposalEvidenceRef("note2", "r2", "p2")
        val completedConcept = earlyConcept.copy(
            supported = listOf(ref1, ref2),
            scannedRevisions = sources,
            matchingComplete = true
        )
        val completedTopic = topic.copy(canonicalConcept = completedConcept)
        val completeHydrated = hydrateCanonicalTopic(completedTopic, model, sources, index, store) { true }!!

        // Promoted to Established once matching completed with >= 2 notes
        assertTrue(completeHydrated.canonicalConcept!!.matchingComplete)
        assertEquals(setOf("note1", "note2"), completeHydrated.matchingMembers.map { it.pageId }.toSet())
        assertEquals(TopicPublicationStatus.Established, discoveryTopicPublicationStatus(completeHydrated))
    }

    @Test fun restartHydrationWorksForEarlyEmergingTopic() = runBlocking {
        val root = files.newFolder()
        val index = FileSemanticIndexStore(root.resolve("index"))
        val store = FileProposalProgressStore(root.resolve("progress"))
        val model = EmbeddingIdentity("model", 2)
        val generator = TopicLabelIdentity("g", "t", "template", "proposal", "schema", "v1", "CPU")
        val verifier = generator.copy(prompt = "support")

        val text = "Moisture and warmth allow seeds to sprout."
        val passage = SemanticPassage("p1", "note1", "section1", "Seeds", "block1", "chunk1", 0, text.length, text, text, 0)
        val record = SemanticRecord("note1", "r1", model, SemanticMetadata("Seeds"), listOf(EmbeddedPassage(passage, listOf(1f, 0f))))
        assertTrue(index.write(record))

        val sources = mapOf("note1" to "r1", "note2" to "r2")
        val evidence = TopicLabelEvidence("p1", "note1", "r1", text)
        val proposalKey = semanticHash("proposal-restart")
        val request = DiscoveryProposalRequest(proposalKey, 0, listOf(evidence), generator)
        val response = DiscoveryProposalResponse.Concept("Seed germination", "Moisture allows sprouting.",
            "Listen to seed germination.", listOf("p1"))
        val key = (validateDiscoveryProposal(request, response, 0, sources) as DiscoveryProposalValidation.Accepted).workKey

        val support = TopicSupportRequest("pending:$key", key, response.name, response.definition, evidence, verifier, 0)
        val assessment = (validateTopicSupport(support, SupportDecision.SUPPORTED, 0, key, sources) as TopicSupportValidation.Accepted).assessment
        assertEquals(ProposalProgressWriteResult.Written, store.writeSupport(assessment) { true })

        val ref = ProposalEvidenceRef("note1", "r1", "p1")
        // Topic with canonicalProjection = null simulating fresh deserialization on app restart
        val deserializedTopic = DiscoveredTopic(
            id = "canonical-restart",
            label = "Seed germination",
            labelSource = passage,
            anchor = EmbeddedPassage(passage, listOf(1f, 0f)),
            members = listOf(TopicMembership("note1", record.metadata, passage, 1f, passage, "r1")),
            createdOrder = 1,
            canonicalConcept = CanonicalConceptEvidence(
                workKey = key, name = response.name, definition = response.definition,
                listeningDescription = response.listeningDescription, generator = generator, verifier = verifier,
                offered = listOf(ref), citations = listOf(assessment), supported = emptyList(),
                scannedRevisions = emptyMap(), matchingComplete = false, proposalKey = proposalKey
            ),
            canonicalProjection = null
        )

        assertNull("Before hydration, projection is null", deserializedTopic.currentCanonicalProjection)

        val restored = hydrateCanonicalTopic(deserializedTopic, model, sources, index, store) { true }!!
        assertNotNull("After hydration, projection is restored", restored.currentCanonicalProjection)
        assertEquals(listOf("note1"), restored.matchingMembers.map { it.pageId })
        assertEquals(TopicPublicationStatus.Emerging, discoveryTopicPublicationStatus(restored))
        assertFalse(restored.canonicalConcept!!.matchingComplete)
        assertTrue(restored.canonicalConcept!!.scannedRevisions.isEmpty())
    }

    @Test fun topicCoordinatorAdmitsCandidateEarlyWithIncompleteCoverage() = runBlocking {
        val root = files.newFolder()
        val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index"))
        val topicStore = FileTopicStore(root.resolve("topics"))
        val progress = FileProposalProgressStore(root.resolve("progress"))
        val model = EmbeddingIdentity("model", 2)
        val generator = TopicLabelIdentity("g", "t", "template", "proposal", "schema", "v1", "CPU")
        val verifier = generator.copy(prompt = "support")

        val doc1 = ReaderDocument.fromChunkTexts("note1", "Seeds", listOf("Moisture and warmth allow seeds to sprout."))
        cache.put(CachedDocument(doc1, null, Instant.EPOCH))
        val rev1 = semanticRevision(doc1)
        val passage1 = SemanticPassage("p1", "note1", "section1", "Seeds", "block1", "chunk1", 0, doc1.sections.first().chunks.first().text.length, doc1.sections.first().chunks.first().text, doc1.sections.first().chunks.first().text, 0)
        val record1 = SemanticRecord("note1", rev1, model, SemanticMetadata("Seeds"), listOf(EmbeddedPassage(passage1, listOf(1f, 0f))))
        assertTrue(index.write(record1))

        val doc2 = ReaderDocument.fromChunkTexts("note2", "Sprouts", listOf("Seedling emergence occurs when shoots break through soil."))
        cache.put(CachedDocument(doc2, null, Instant.EPOCH))
        val rev2 = semanticRevision(doc2)
        val passage2 = SemanticPassage("p2", "note2", "section2", "Sprouts", "block2", "chunk2", 0, doc2.sections.first().chunks.first().text.length, doc2.sections.first().chunks.first().text, doc2.sections.first().chunks.first().text, 0)
        val record2 = SemanticRecord("note2", rev2, model, SemanticMetadata("Sprouts"), listOf(EmbeddedPassage(passage2, listOf(0f, 1f))))
        assertTrue(index.write(record2))

        val sources = mapOf("note1" to rev1, "note2" to rev2)
        val evidence1 = TopicLabelEvidence("p1", "note1", rev1, passage1.excerpt)
        val proposalKey = semanticHash("coord-early-proposal")
        val request = DiscoveryProposalRequest(proposalKey, 0, listOf(evidence1), generator)
        val response = DiscoveryProposalResponse.Concept("Seed germination", "Moisture and warmth allow seeds to sprout.",
            "Listen to seed germination.", listOf("p1"))
        val key = (validateDiscoveryProposal(request, response, 0, sources) as DiscoveryProposalValidation.Accepted).workKey

        val support = TopicSupportRequest("pending:$key", key, response.name, response.definition, evidence1, verifier, 0)
        val assessment = (validateTopicSupport(support, SupportDecision.SUPPORTED, 0, key, sources) as TopicSupportValidation.Accepted).assessment
        assertEquals(ProposalProgressWriteResult.Written, progress.writeSupport(assessment) { true })

        val ref1 = ProposalEvidenceRef("note1", rev1, "p1")
        val incrementalProposal = IncrementalProposal(proposalKey, listOf(ref1), emptySet(), emptySet())
        val completedGen = ProposalGenerationResult.Completed(response, key, emptySet())
        val query = TopicRetrievalQuery("pending", key, "${response.name}\n${response.definition}", model, TopicRetrievalPolicy(.85f))
        val embeddedQuery = TopicQueryEmbedding(query, listOf(1f, 0f))

        var viewEmitted: TopicView? = null
        val coordinator = TopicCoordinator(
            cache = cache,
            index = index,
            store = topicStore,
            checkpoint = null,
            identity = { model },
            policy = TopicPolicy(),
            bulkGate = Mutex(),
            mayWork = { true },
            waitForQueries = {},
            changed = { viewEmitted = it },
            retrievals = TopicRetrievalCoordinator(
                index, com.example.notionreader.data.local.FileTopicRetrievalStore(root.resolve("queries")),
                { model }, query.policy, { listOf(1f, 0f) }, { true }, {}, Mutex()
            ),
            proposalProgress = progress
        )
        assertTrue(topicStore.write(TopicSnapshot(
            identity = model, canonicalCatalogReady = true, acceptedRevisions = sources
        )))
        coordinator.invalidate(sources)
        val ticket = coordinator.captureCatalogTicket(allowIncomplete = true)!!

        val candidate = PendingConceptCandidate(
            proposal = incrementalProposal,
            generation = completedGen,
            citations = listOf(assessment),
            sources = sources,
            embedding = model,
            verifier = verifier,
            generator = generator,
            query = embeddedQuery,
            catalogTicket = ticket
        )

        // Coverage is Incomplete because note2 has not been scanned yet
        val incompleteCoverage = PendingCorpusReplayResult.Incomplete("note2", PendingCorpusReplay.start())
        val admissionResult = coordinator.admitCandidate(ticket, candidate, incompleteCoverage) { true }
        assertTrue("Early candidate must be admitted", admissionResult is CanonicalAdmissionResult.Admitted)

        val admittedTopic = viewEmitted!!.snapshot.topics.single { it.canonicalConcept?.workKey == key }
        assertNotNull(admittedTopic.canonicalProjection)
        assertEquals(listOf("note1"), admittedTopic.matchingMembers.map { it.pageId })
        assertEquals(TopicPublicationStatus.Emerging, discoveryTopicPublicationStatus(admittedTopic))
        assertFalse("matchingComplete must be false on early admission", admittedTopic.canonicalConcept!!.matchingComplete)
        assertTrue("scannedRevisions must be empty on early admission", admittedTopic.canonicalConcept!!.scannedRevisions.isEmpty())
        assertTrue("supported must be empty on early admission", admittedTopic.canonicalConcept!!.supported.isEmpty())
    }
}
