package com.example.notionreader.application

import com.example.notionreader.data.local.FileProposalProgressStore
import com.example.notionreader.domain.discovery.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Early support deliberately leaves a candidate tail unchecked. Those candidates are pending
 * verification evidence, not explained evidence and not a new subject. Only that exact tail is
 * deferred; other anchors in the same Note remain eligible, and a bounded expiry releases the
 * tail if its verification never runs.
 */
class DeferredProposalAnchorTest {
    private val identity = EmbeddingIdentity("deferred-anchors", 2)
    private val policy = TopicRetrievalPolicy(.8f)
    private val verifier = TopicLabelIdentity("a", "t", "c", "p", "s", "v", "CPU")

    private fun record(): SemanticRecord {
        val passages = (0..3).map { n ->
            val text = "Chess target candidate $n"
            val passage = SemanticPassage("note:p$n", "note", "s1", "Practical sequence", "b$n", "c$n",
                0, text.length, text, text, n)
            EmbeddedPassage(passage, if (n == 3) listOf(0f, 1f) else listOf(1f, 0f))
        }
        return SemanticRecord("note", "r1", identity, SemanticMetadata("Note"), passages)
    }

    @Test
    fun uncheckedTailAnchorsAreDeferredWhileAnUnrelatedAnchorStillProducesAProposal() {
        val source = record()
        val revision = mapOf("note" to "r1")
        val deferred = setOf("note:p1", "note:p2")
        val window = proposeDiscoveryWindow(source, source.passages.map { it.passage.id }.toSet(),
            emptySet(), emptySequence(), revision, emptySet(), true, true, maxAnchors = 8,
            deferredPassageIds = deferred)
        val anchors = window.proposals.map { it.evidence.first().passageId }.toSet()
        assertTrue("An unrelated anchor must remain eligible", "note:p3" in anchors)
        assertFalse("Unchecked tail anchors are pending verification, not new subjects", "note:p1" in anchors)
        assertFalse("Unchecked tail anchors are pending verification, not new subjects", "note:p2" in anchors)
    }

    @Test
    fun deferralIsPersistedAcrossARestartAndReleasedByTheBoundedExpiry() = runBlocking {
        val dir = Files.createTempDirectory("deferred-anchors").toFile()
        try {
            val source = record()
            val sources = mapOf("note" to "r1")
            val store = FileProposalProgressStore(dir.resolve("progress"))
            assertEquals(ProposalProgressWriteResult.Written, store.writeDelta(
                PassageDeltaRecord("note", "r1", identity, passageDeltaFingerprint(source),
                    PassageDelta(source.passages.map { it.passage.id }.toSet(), emptySet(), emptyMap())), { true }))
            val deferred = setOf("note:p1", "note:p2")
            suspend fun pending(offset: Int, now: Long, store: ProposalProgressStore) =
                advancePendingProposalWindow(source, emptySet(), sources, emptySet(), true, offset, store, 1,
                    { true }, { true }, deferredPassageIds = deferred, nowEpochMillis = now,
                    deferralTtlMillis = 1_000L) { null }
            val first = pending(0, 1_000L, store) as PendingProposalStepResult.Ready
            val firstAnchors = first.step.window.proposals.map { it.evidence.first().passageId }.toSet()
            assertFalse("note:p1" in firstAnchors)
            assertFalse("note:p2" in firstAnchors)

            // A new store instance is the process-restart boundary; the deferral key/since are durable.
            val reopened = FileProposalProgressStore(dir.resolve("progress"))
            val resumed = pending(first.step.window.nextAnchorOffset ?: 0, 1_500L, reopened) as PendingProposalStepResult.Ready
            val resumedAnchors = resumed.step.window.proposals.map { it.evidence.first().passageId }.toSet()
            assertFalse("note:p1" in resumedAnchors)
            assertFalse("note:p2" in resumedAnchors)

            // Expiry is bounded: a blocked/deferred tail cannot suppress the anchor forever.
            val expired = pending(resumed.step.window.nextAnchorOffset ?: 0, 2_500L, reopened) as PendingProposalStepResult.Ready
            val expiredAnchors = expired.step.window.proposals.map { it.evidence.first().passageId }.toSet()
            assertTrue("A bounded expiry must release the deferred anchors", "note:p1" in expiredAnchors || "note:p2" in expiredAnchors)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun candidateTailReconstructionExcludesOnlyAssessedPassagesAndNeedsNoStoredTailState() = runBlocking {
        val source = record()
        val query = TopicQueryEmbedding(TopicRetrievalQuery("topic", semanticHash("work"), "Chess targets", identity, policy),
            listOf(1f, 0f))
        val tail = pendingConceptCandidatePassages(query, source, mapOf("note" to "r1"), setOf("note:p0"))
        assertEquals(setOf("note:p1", "note:p2"), tail)
        // Once the pending verification resolves the candidates, nothing remains deferred.
        assertEquals(emptySet<String>(),
            pendingConceptCandidatePassages(query, source, mapOf("note" to "r1"),
                setOf("note:p0", "note:p1", "note:p2")))
    }
}
