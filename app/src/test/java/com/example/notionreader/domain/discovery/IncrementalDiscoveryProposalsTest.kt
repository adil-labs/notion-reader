package com.example.notionreader.domain.discovery

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class IncrementalDiscoveryProposalsTest {
    private val identity = EmbeddingIdentity("fixture", 2)
    private fun passage(page: String, id: String, section: String, vector: List<Float>, body: String =
        "Moisture allows dormant seeds to absorb water and begin the germination process."): EmbeddedPassage =
        EmbeddedPassage(SemanticPassage(id, page, section, "Overview", "$id-block", "$id-chunk", 0,
            body.length, body, body, 0), vector)
    private fun record(id: String, vararg passages: EmbeddedPassage) =
        SemanticRecord(id, "r1", identity, SemanticMetadata(id), passages.toList())
    private fun run(changed: SemanticRecord, older: List<SemanticRecord> = emptyList(),
        changedIds: Set<String> = changed.passages.map { it.passage.id }.toSet(), explained: Set<String> = emptySet(),
        explicit: Set<String> = emptySet(), structure: Boolean = true, scan: Boolean = true,
        revisions: Map<String, String> = (older + changed).associate { it.pageId to it.revision },
        maxEvidence: Int = 4, maxRecords: Int = 128, offset: Int = 0, maxAnchors: Int = 8,
        check: () -> Unit = {}) = proposeDiscoveryWindow(changed, changedIds, explained, older.asSequence(),
        revisions, explicit, structure, scan, offset, maxAnchors, maxRecords, maxEvidence, checkActive = check)

    @Test fun aSingleUnorganizedNoteCanProduceAProposal() {
        val note = record("one", passage("one", "a", "s", listOf(1f, 0f)))
        val proposal = run(note).proposals.single()
        assertEquals(listOf(ProposalEvidenceRef("one", "r1", "a")), proposal.evidence)
        assertTrue(ProposalReason.CHANGED_BODY in proposal.reasons)
    }

    @Test fun unrelatedSectionsRemainSeparateAndExplainedEvidenceDoesNotSeed() {
        val note = record("one", passage("one", "a", "s1", listOf(1f, 0f)),
            passage("one", "b", "s2", listOf(0f, 1f)))
        assertEquals(setOf("a", "b"), run(note).proposals.map { it.evidence.single().passageId }.toSet())
        assertEquals("b", run(note, explained = setOf("a")).proposals.single().evidence.single().passageId)
    }

    @Test fun oldUnmatchedSemanticAndExplicitlyRelatedEvidenceRemainEligible() {
        val note = record("new", passage("new", "a", "s", listOf(1f, 0f)))
        val semantic = record("old", passage("old", "b", "s2", listOf(1f, 0f)))
        val explicit = record("linked", passage("linked", "c", "s3", listOf(0f, 1f)))
        val proposal = run(note, listOf(semantic, explicit), explicit = setOf("linked")).proposals.single()
        assertEquals(setOf("a", "b", "c"), proposal.evidence.map { it.passageId }.toSet())
        assertTrue(proposal.reasons.containsAll(setOf(ProposalReason.SEMANTIC_NEIGHBOR, ProposalReason.EXPLICIT_REFERENCE)))
    }

    @Test fun incompleteOrTruncatedInputsCannotAppearComplete() {
        val note = record("new", passage("new", "a", "s", listOf(1f, 0f)))
        val others = (1..3).map { record("n$it", passage("n$it", "p$it", "s$it", listOf(1f, 0f))) }
        val proposal = run(note, others, structure = false, scan = false, maxEvidence = 2, maxRecords = 2).proposals.single()
        assertTrue(proposal.incomplete.containsAll(setOf(ProposalIncompleteReason.STRUCTURE,
            ProposalIncompleteReason.SOURCE_SCAN, ProposalIncompleteReason.EVIDENCE_LIMIT)))
        assertEquals(2, proposal.evidence.size)
    }

    @Test fun staleSourcesAndCancellationNeverProduceACompletedWindow() {
        val note = record("one", passage("one", "a", "s", listOf(1f, 0f)))
        assertTrue(run(note, revisions = mapOf("one" to "r2")).stale)
        try { run(note, check = { throw CancellationException("stop") }); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
    }

    @Test fun anchorWindowsResumeAndHeadingOnlyEvidenceDoesNotSeed() {
        val a = passage("one", "a", "s1", listOf(1f, 0f))
        val b = passage("one", "b", "s2", listOf(0f, 1f))
        val heading = passage("one", "h", "s3", listOf(1f, 0f)).let { it.copy(passage = it.passage.copy(sourceBlockId = "s3")) }
        val note = record("one", a, b, heading)
        val first = run(note, maxAnchors = 1)
        assertEquals(1, first.nextAnchorOffset)
        assertTrue(ProposalIncompleteReason.ANCHOR_WINDOW in first.proposals.single().incomplete)
        val second = run(note, offset = first.nextAnchorOffset!!, maxAnchors = 1)
        assertNull(second.nextAnchorOffset)
        assertEquals(setOf("a", "b"), (first.proposals + second.proposals).map { it.evidence.first().passageId }.toSet())
    }

    @Test fun unchangedOrExplainedPassagesDoNotScanTheOlderCorpus() {
        val note = record("one", passage("one", "a", "s", listOf(1f, 0f)))
        val older = sequence<SemanticRecord> { error("Unneeded scan") }
        val result = proposeDiscoveryWindow(note, emptySet(), emptySet(), older, mapOf("one" to "r1"),
            emptySet(), structureComplete = false, scanComplete = false)
        assertTrue(result.proposals.isEmpty())
        assertNull(result.nextAnchorOffset)
    }
}
