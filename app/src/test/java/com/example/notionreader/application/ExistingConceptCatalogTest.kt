package com.example.notionreader.application

import com.example.notionreader.data.local.FileTopicRetrievalStore
import com.example.notionreader.data.local.FileProposalProgressStore
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExistingConceptCatalogTest {
    @get:Rule val files = TemporaryFolder()
    private val identity = EmbeddingIdentity("embedding", 2)
    private val policy = TopicRetrievalPolicy(.8f)
    private val labelIdentity = TopicLabelIdentity("a", "t", "c", "p", "s", "v", "CPU")
    private fun topic(): DiscoveredTopic {
        val text = "Moisture and suitable temperature allow seeds to germinate and begin sprouting."
        val p = SemanticPassage("seed:p", "seed", "seed:s", "Summary", "seed:b", "seed:c", 0, text.length, text, text, 0)
        val embedded = EmbeddedPassage(p, listOf(1f, 0f)); val metadata = SemanticMetadata("Seed")
        val topic = DiscoveredTopic("canonical", "seed", p, embedded, listOf(TopicMembership("seed", metadata, p, 1f, p, "r1")), 1)
        val request = topicLabelRequest(topic, labelIdentity, 0)!!
        val label = (validateTopicLabel(request, "Seed germination", request.evidence.map { it.passageId }, request.groupFingerprint, 0,
            "Conditions that allow seeds to germinate and sprout.") as TopicLabelValidation.Accepted).label
        return topic.copy(generatedLabel = label)
    }
    private fun query(topic: DiscoveredTopic): TopicQueryEmbedding {
        val label = topic.currentGeneratedLabel!!
        return TopicQueryEmbedding(TopicRetrievalQuery(topic.id, label.workKey, topicConceptQuery(label)!!, identity, policy), listOf(1f, 0f))
    }
    @Test fun currentCatalogConceptReusesItsExactStoredQueryVector() = runBlocking {
        val topic = topic(); val store = FileTopicRetrievalStore(files.newFolder()); val query = query(topic)
        assertEquals(TopicRetrievalWriteResult.Written, store.write(TopicRetrievalProgress(query)) { true })
        val result = currentExistingConcept(topic, identity, policy, mapOf("seed" to "r1"), store) { true } as ExistingConceptCatalogResult.Ready
        assertEquals(topic.id, result.concept.topicId); assertEquals(topic.currentGeneratedLabel!!.definition, result.concept.definition)
        assertEquals(query, result.query)
    }
    @Test fun catalogQueryAndVerifiedSupportCanExplainAnIndexedChangedPassage() = runBlocking {
        val root = files.newFolder(); val topic = topic(); val retrievals = FileTopicRetrievalStore(root.resolve("retrieval"))
        assertEquals(TopicRetrievalWriteResult.Written, retrievals.write(TopicRetrievalProgress(query(topic))) { true })
        val sources = mapOf("seed" to "r1")
        val existing = currentExistingConcept(topic, identity, policy, sources, retrievals) { true } as ExistingConceptCatalogResult.Ready
        val index = FileSemanticIndexStore(root.resolve("index")); val member = topic.members.single()
        val record = SemanticRecord("seed", "r1", identity, member.metadata, listOf(topic.anchor)); index.write(record)
        val store = FileProposalProgressStore(root.resolve("proposals"))
        assertEquals(ProposalProgressWriteResult.Written, store.writeDelta(PassageDeltaRecord("seed", "r1", identity,
            passageDeltaFingerprint(record), PassageDelta(setOf(member.passage.id), emptySet(), emptyMap()))) { true })
        val support = verifyExistingConceptPage(existing.concept, existing.query, record, index, labelIdentity, 5,
            sources, null, 1, store, { true }, { true }) { TopicSupportRun.Completed(SupportDecision.SUPPORTED) }
            as ExistingConceptEvidenceResult.Ready
        assertEquals(setOf(member.passage.id), support.supported)
        val result = advancePendingProposalWindow(record, support.supported, sources, emptySet(), true, 0, store, 1,
            { true }, { true }) { null } as PendingProposalStepResult.Ready
        assertTrue(result.step.window.proposals.isEmpty())
    }
    @Test fun missingOrIncompatibleQueryAndLabelStayUnavailable() = runBlocking {
        val topic = topic(); val store = FileTopicRetrievalStore(files.newFolder())
        assertEquals(ExistingConceptCatalogResult.Unavailable, currentExistingConcept(topic, identity, policy, mapOf("seed" to "r1"), store) { true })
        assertEquals(TopicRetrievalWriteResult.Written, store.write(TopicRetrievalProgress(query(topic))) { true })
        assertEquals(ExistingConceptCatalogResult.Unavailable, currentExistingConcept(topic, identity, policy.copy(version = 2), mapOf("seed" to "r1"), store) { true })
        assertEquals(ExistingConceptCatalogResult.Unavailable, currentExistingConcept(topic.copy(generatedLabel = null), identity, policy, mapOf("seed" to "r1"), store) { true })
    }
    @Test fun staleSourceAndInterruptedLookupCannotReuseConceptEvidence() = runBlocking {
        val topic = topic(); val store = FileTopicRetrievalStore(files.newFolder())
        assertEquals(TopicRetrievalWriteResult.Written, store.write(TopicRetrievalProgress(query(topic))) { true })
        assertEquals(ExistingConceptCatalogResult.Stale, currentExistingConcept(topic, identity, policy, emptyMap(), store) { true })
        var current = true
        val interrupted = object : TopicRetrievalStore by store {
            override suspend fun read(topicId: String): TopicRetrievalProgress? = store.read(topicId).also { current = false }
        }
        assertEquals(ExistingConceptCatalogResult.Stale, currentExistingConcept(topic, identity, policy, mapOf("seed" to "r1"), interrupted) { current })
    }
}
