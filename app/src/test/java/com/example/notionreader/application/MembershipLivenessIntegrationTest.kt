package com.example.notionreader.application

import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.discovery.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Membership expansion is a bounded job over existing concepts. It must not stop the
 * independent evidence-first scheduler from proposing and publishing other subjects.
 */
class MembershipLivenessIntegrationTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun aNewConceptIsPublishedWhileAnExistingMembershipCheckIsUncertain() = runBlocking {
        val root = files.newFolder()
        val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index"))
        val progress = FileProposalProgressStore(root.resolve("progress"))
        val model = EmbeddingIdentity("model", 2)
        val verifier = TopicLabelIdentity("a", "t", "template", "support", "schema", "v1", "CPU")
        val generator = verifier.copy(prompt = "proposal")
        val seedText = "Moisture and warmth help seeds germinate and begin growing."
        val laterText = "Calculate chess targets before candidate moves."
        suspend fun add(id: String, text: String, section: String): Pair<ReaderDocument, SemanticRecord> {
            val doc = ReaderDocument.fromChunkTexts(id, id, listOf(text))
            cache.put(CachedDocument(doc, null, Instant.EPOCH))
            val passage = SemanticPassage("$id:p", id, "$id:s", section, "$id:b", "$id:c", 0, text.length, text, text, 0)
            val record = SemanticRecord(id, semanticRevision(doc), model, SemanticMetadata(id), listOf(EmbeddedPassage(passage, listOf(1f, 0f))))
            assertTrue(index.write(record))
            return doc to record
        }
        val (_, seed) = add("seed", seedText, "Seeds")
        val (_, later) = add("later", laterText, "Chess calculation")
        index.setPaused(false)
        val sources = mapOf("seed" to seed.revision, "later" to later.revision)

        val seedPassage = seed.passages.single()
        val ref = ProposalEvidenceRef("seed", seed.revision, seedPassage.passage.id)
        val offered = TopicLabelEvidence(ref.passageId, ref.pageId, ref.revision, seedText)
        val proposal = DiscoveryProposalRequest(semanticHash("seed-proposal"), 0, listOf(offered), generator)
        val response = DiscoveryProposalResponse.Concept("Seed germination", "Moisture helps seeds germinate.", "Listen about seeds.", listOf(ref.passageId))
        val key = (validateDiscoveryProposal(proposal, response, 0, sources) as DiscoveryProposalValidation.Accepted).workKey
        val pending = TopicSupportRequest("pending:$key", key, response.name, response.definition, offered, verifier, 0)
        val assessment = (validateTopicSupport(pending, SupportDecision.SUPPORTED, 0, key, sources) as TopicSupportValidation.Accepted).assessment
        assertEquals(ProposalProgressWriteResult.Written, progress.writeSupport(assessment) { true })
        val policy = TopicRetrievalPolicy(.8f)
        val query = TopicQueryEmbedding(TopicRetrievalQuery("topic", key, "${response.name}\n${response.definition}", model, policy), listOf(1f, 0f))
        val concept = CanonicalConceptEvidence(key, response.name, response.definition, response.listeningDescription,
            generator, verifier, listOf(ref), listOf(assessment), listOf(ref), mapOf("seed" to seed.revision),
            matchingComplete = false, proposalKey = proposal.proposalKey, queryEmbedding = query)
        val member = TopicMembership("seed", seed.metadata, seedPassage.passage, 1f, seedPassage.passage, seed.revision)
        val topic = DiscoveredTopic("topic", "Seeds", seedPassage.passage, seedPassage, listOf(member), 1, canonicalConcept = concept)
        val graph = FileTopicStore(root.resolve("topics"))
        assertEquals(TopicWriteResult.Written, graph.writeDetailed(TopicSnapshot(topics = listOf(topic), identity = model,
            canonicalCatalogReady = true, acceptedRevisions = sources, connectionPassComplete = true,
            policyVersion = TopicPolicy().version)))

        val runtime = object : TopicLabelRuntime, TopicSupportRuntime, DiscoveryProposalRuntime {
            override suspend fun label(request: TopicLabelRequest): TopicLabelRun = error("No legacy naming")
            override suspend fun verifySupport(request: TopicSupportRequest): TopicSupportRun =
                if (request.topicId.startsWith("pending:")) TopicSupportRun.Completed(SupportDecision.SUPPORTED)
                // The existing concept's later Note cannot be resolved. This is terminal for the
                // automatic window, but must not block a different subject's proposal.
                else TopicSupportRun.Completed(SupportDecision.UNCERTAIN)
            override suspend fun propose(request: DiscoveryProposalRequest): DiscoveryProposalRun {
                if (request.evidence.first().pageId != "later") return DiscoveryProposalRun.Completed(
                    DiscoveryProposalResponse.Abstained(DiscoveryAbstentionReason.MIXED_EVIDENCE))
                return DiscoveryProposalRun.Completed(DiscoveryProposalResponse.Concept("Chess calculation targets",
                    "Identify targets before candidate moves.", "Listen about chess calculation.",
                    listOf(request.evidence.first().passageId)))
            }
            override suspend fun close() {}
        }
        val provider = object : EmbeddingProvider {
            override val identity = model
            override val tokenizer = object : SemanticTokenizer { override fun tokens(text: String) = emptyList<SemanticToken>() }
            override suspend fun embed(text: String) =
            if (text.contains("chess", ignoreCase = true) || text.contains("target", ignoreCase = true))
                listOf(0f, 1f) else listOf(1f, 0f)
            override fun release() {}
        }
        val logs = mutableListOf<String>()
        val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(root.resolve("interests")), provider,
            topicStore = graph, topicLabelRuntime = runtime, topicLabelModelAvailable = { true },
            topicRetrievalStore = FileTopicRetrievalStore(root.resolve("queries")), topicRetrievalPolicy = policy,
            proposalProgressStore = progress, topicSupportIdentity = { verifier }, topicProposalIdentity = { generator },
            diagnosticLog = { tag, message -> logs += "$tag: $message" })
        controller.reconcile(emptyList(), false)
        val direct = controller.advanceEvidenceFirstNote("later")
        assertTrue("The later Note must be able to propose directly; logs=$logs result=$direct",
            direct is EvidenceFirstNoteStep.Proposals && direct.window.window.proposals.isNotEmpty())
        repeat(80) { controller.reconcile(emptyList(), true) }

        val names = graph.readMetadata()!!.topics.map { it.label }
        assertTrue("A blocked membership check must not starve new topics; names=$names state=${controller.state.value}",
            names.any { it == "Chess calculation targets" })
        assertEquals("Only the blocked existing concept plus the independent new subject are expected", 2, names.size)
        assertFalse("The blocked existing membership remains incomplete",
            graph.readMetadata()!!.topics.single { it.label == "Seeds" }
                .canonicalConcept!!.matchingComplete)
    }
}
