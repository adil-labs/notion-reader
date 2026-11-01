package com.example.notionreader.application

import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.data.local.FileProposalProgressStore
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiscoveryProposalEvidenceTest {
    @get:Rule val files = TemporaryFolder()
    private val embedding = EmbeddingIdentity("model", 2)
    private val generator = TopicLabelIdentity("a", "t", "template", "p", "s", "policy", "CPU")
    private fun record(page: String, rev: String = "r1"): SemanticRecord {
        val body = "Moisture allows dormant seeds to absorb water and begin the germination process."
        val p = SemanticPassage("$page:p", page, "$page:s", "Overview", "$page:b", "$page:c", 0, body.length, body, body, 0)
        return SemanticRecord(page, rev, embedding, SemanticMetadata(page), listOf(EmbeddedPassage(p, listOf(1f, 0f))))
    }
    private val proposal = IncrementalProposal("proposal-key", listOf(ProposalEvidenceRef("one", "r1", "one:p"),
        ProposalEvidenceRef("two", "r1", "two:p")), setOf(ProposalReason.CHANGED_BODY, ProposalReason.SEMANTIC_NEIGHBOR),
        setOf(ProposalIncompleteReason.STRUCTURE))
    private val sources = mapOf("one" to "r1", "two" to "r1")

    @Test fun currentReferencesRehydrateActualBodiesAndPreserveIncompleteness() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record("one")); index.write(record("two"))
        val result = prepareProposalEvidence(proposal, index, embedding, generator, 5, sources) { true } as ProposalEvidenceResult.Ready
        assertEquals(listOf("one:p", "two:p"), result.request.evidence.map { it.passageId })
        assertEquals(record("two").passages.single().passage.excerpt, result.request.evidence.last().body)
        assertEquals(setOf(ProposalIncompleteReason.STRUCTURE), result.incomplete)
        assertEquals(5L, result.request.sourceEpoch)
    }

    @Test fun withdrawalAndChangedRevisionCannotBeRehydrated() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record("one")); index.write(record("two", "r2"))
        assertEquals(ProposalEvidenceResult.Stale, prepareProposalEvidence(proposal, index, embedding, generator, 5, sources - "two") { true })
        assertEquals(ProposalEvidenceResult.Stale, prepareProposalEvidence(proposal, index, embedding, generator, 5, sources) { true })
    }

    @Test fun fabricatedHeadingAndDuplicateReferencesCannotBecomeModelEvidence() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record("one")); index.write(record("two"))
        val fake = proposal.copy(evidence = proposal.evidence.map { if (it.pageId == "two") it.copy(passageId = "invented") else it })
        assertEquals(ProposalEvidenceResult.Invalid, prepareProposalEvidence(fake, index, embedding, generator, 5, sources) { true })
        assertEquals(ProposalEvidenceResult.Invalid, prepareProposalEvidence(proposal.copy(evidence = listOf(proposal.evidence.first(), proposal.evidence.first())), index, embedding, generator, 5, sources) { true })
        val second = record("two"); index.write(second.copy(passages = second.passages.map { it.copy(passage = it.passage.copy(sourceBlockId = it.passage.sectionId)) }))
        assertEquals(ProposalEvidenceResult.Invalid, prepareProposalEvidence(proposal, index, embedding, generator, 5, sources) { true })
    }

    @Test fun epochChangeDuringAReadPreventsReturningAnyBodies() = runBlocking {
        val real = FileSemanticIndexStore(files.newFolder()); real.write(record("one")); real.write(record("two"))
        var valid = true
        val index = object : SemanticIndexStore by real {
            override suspend fun read(pageId: String): SemanticRecord? = real.read(pageId).also { valid = false }
        }
        assertEquals(ProposalEvidenceResult.Stale, prepareProposalEvidence(proposal, index, embedding, generator, 5, sources) { valid })
    }

    @Test fun unavailableRecordsRemainIncompleteRatherThanInventingEvidence() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record("one"))
        assertEquals(ProposalEvidenceResult.Unavailable, prepareProposalEvidence(proposal, index, embedding, generator, 5, sources) { true })
    }

    @Test fun generationUsesHydratedEvidenceAndKeepsAbstentionAsASuccess() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record("one")); index.write(record("two"))
        val response = DiscoveryProposalResponse.Abstained(DiscoveryAbstentionReason.MIXED_EVIDENCE)
        val result = generateProposal(proposal, index, embedding, generator, 5, sources, { true }) { request ->
            assertEquals(record("one").passages.single().passage.excerpt, request.evidence.first().body)
            DiscoveryProposalRun.Completed(response)
        } as ProposalGenerationResult.Completed
        assertEquals(response, result.response)
        assertEquals(proposal.incomplete, result.incomplete)
        assertTrue(result.workKey.isNotBlank())
    }

    @Test fun withdrawalDuringGenerationCannotPublishTheLateResponse() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record("one")); index.write(record("two"))
        var current = true
        val result = generateProposal(proposal, index, embedding, generator, 5, sources, { current }) {
            index.remove("two"); current = false
            DiscoveryProposalRun.Completed(DiscoveryProposalResponse.Abstained(DiscoveryAbstentionReason.MIXED_EVIDENCE))
        }
        assertEquals(ProposalGenerationResult.Stale, result)
    }

    @Test fun aFabricatedGenerationCitationCannotBecomeAResult() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record("one")); index.write(record("two"))
        val result = generateProposal(proposal, index, embedding, generator, 5, sources, { true }) {
            DiscoveryProposalRun.Completed(DiscoveryProposalResponse.Concept("Seed germination", "Conditions for seeds to germinate.",
                "Listen to seed germination conditions.", listOf("fabricated")))
        }
        assertEquals(ProposalGenerationResult.Invalid, result)
    }

    @Test fun unchangedAbstentionSurvivesRestartButChangedEvidenceOrPolicyRunsAgain() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index"))
        index.write(record("one")); index.write(record("two"))
        var calls = 0
        suspend fun run(identity: TopicLabelIdentity = generator): ProposalGenerationResult =
            generateProposal(proposal, index, embedding, identity, 5, sources, { true },
                FileProposalProgressStore(root.resolve("proposals"))) {
                calls++; DiscoveryProposalRun.Completed(DiscoveryProposalResponse.Abstained(DiscoveryAbstentionReason.MIXED_EVIDENCE))
            }
        assertTrue(run() is ProposalGenerationResult.Completed)
        assertTrue(run() is ProposalGenerationResult.Completed)
        assertEquals(1, calls)
        assertTrue(run(generator.copy(policy = "new")) is ProposalGenerationResult.Completed)
        assertEquals(2, calls)
        val changed = record("two")
        index.write(changed.copy(passages = changed.passages.map { embedded ->
            embedded.copy(passage = embedded.passage.copy(excerpt = "A different passage with sufficient content to explain seed germination clearly."))
        }))
        assertTrue(run() is ProposalGenerationResult.Completed)
        assertEquals(3, calls)
    }

    @Test fun refusedAbstentionPersistenceIsNotReportedAsCompleted() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index"))
        index.write(record("one")); index.write(record("two"))
        val real = FileProposalProgressStore(root.resolve("proposals"))
        val refused = object : ProposalProgressStore by real {
            override suspend fun writeAbstention(record: ProposalAbstentionRecord, stillCurrent: () -> Boolean) = ProposalProgressWriteResult.StorageRefused
        }
        val result = generateProposal(proposal, index, embedding, generator, 5, sources, { true }, refused) {
            DiscoveryProposalRun.Completed(DiscoveryProposalResponse.Abstained(DiscoveryAbstentionReason.MIXED_EVIDENCE))
        }
        assertEquals(ProposalGenerationResult.StorageFailure(ProposalProgressWriteResult.StorageRefused), result)
    }
}
