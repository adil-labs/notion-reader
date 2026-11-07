package com.example.notionreader.application

import com.example.notionreader.data.local.FileProposalProgressStore
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Synthetic vectors exercise shipping retrieval and verification, not model quality.
 *
 * Two algorithms run over the same fixture:
 *  - exhaustive: the pre-change behavior, which verifies every threshold-eligible candidate;
 *  - support-first: the bounded per-Note behavior, which stops on the first validated support.
 *
 * The exhaustive arm deliberately reuses the shipping candidate pager and verifier rather
 * than a second production path.
 */
class DiscoverySupportScaleTest {
    @get:Rule val files = TemporaryFolder()

    private data class Counts(var fresh: Int = 0, var reused: Int = 0, var supported: Int = 0,
        var unsupported: Int = 0, var uncertain: Int = 0, var earlyScans: Int = 0,
        var exhaustedScans: Int = 0, var uncheckedNotes: Int = 0, var positiveNotes: Int = 0,
        var steps: Int = 0, var pages: Int = 0)

    private data class ModeResult(val cold: Counts, val warm: Counts, val oneNoteChanged: Counts,
        val verifierChanged: Counts, val tailFresh: Int = 0, val tailReused: Int = 0,
        val tailExhausted: Int = 0)

    @Test fun supportFirstStopsOnTheStrongestVerifiedPassageAndStillReusesExactDecisions() = runBlocking {
        val exhaustive = runMode(earlyStop = false)
        val supportFirst = runMode(earlyStop = true)

        // Cold: the strongest candidate is supported and the second candidate is unsupported.
        assertEquals(4000, exhaustive.cold.fresh)
        assertEquals(2000, supportFirst.cold.fresh)
        assertEquals(0, exhaustive.warm.fresh)
        assertEquals(0, supportFirst.warm.fresh)
        assertEquals(4000, exhaustive.warm.reused)
        assertEquals(2000, supportFirst.warm.reused)
        assertEquals(2, exhaustive.oneNoteChanged.fresh)
        assertEquals(1, supportFirst.oneNoteChanged.fresh)
        assertEquals(3998, exhaustive.oneNoteChanged.reused)
        assertEquals(1999, supportFirst.oneNoteChanged.reused)
        assertEquals(4000, exhaustive.verifierChanged.fresh)
        assertEquals(2000, supportFirst.verifierChanged.fresh)

        // Tail reconciliation is a later exhaustive pass, not a second membership scan. It reuses
        // every verified witness and checks only the previously unchecked candidate.
        assertEquals(2000, supportFirst.tailFresh)
        assertEquals(2000, supportFirst.tailReused)
        assertEquals(2000, supportFirst.tailExhausted)
        assertEquals(0, exhaustive.tailFresh)

        // Membership is unchanged even though the unchecked tail is not collected.
        assertEquals(2000, exhaustive.cold.positiveNotes)
        assertEquals(2000, supportFirst.cold.positiveNotes)
        assertEquals(2000, exhaustive.cold.supported)
        assertEquals(2000, supportFirst.cold.supported)
        assertEquals(2000, exhaustive.cold.unsupported)
        assertEquals("The unchecked weakest candidate must not be recorded as unsupported", 0,
            supportFirst.cold.unsupported)
        assertEquals(0, supportFirst.cold.uncertain)
        assertEquals(0, exhaustive.cold.earlyScans)
        assertEquals(2000, supportFirst.cold.earlyScans)
        assertEquals(2000, exhaustive.cold.exhaustedScans)
        assertEquals(0, supportFirst.cold.exhaustedScans)
        assertEquals(0, exhaustive.cold.uncheckedNotes)
        assertEquals(2000, supportFirst.cold.uncheckedNotes)
        assertEquals(2000, exhaustive.oneNoteChanged.positiveNotes)
        assertEquals(2000, supportFirst.oneNoteChanged.positiveNotes)
        assertEquals(2000, supportFirst.verifierChanged.positiveNotes)

        println(
            "Support scale A/B (passages=20000 notes=2000 concepts=35): " +
                "exhaustive cold=${exhaustive.cold.fresh} warm=${exhaustive.warm.fresh} " +
                "oneNoteChanged=${exhaustive.oneNoteChanged.fresh} verifierChanged=${exhaustive.verifierChanged.fresh} " +
                "| support-first cold=${supportFirst.cold.fresh} warm=${supportFirst.warm.fresh} " +
                "oneNoteChanged=${supportFirst.oneNoteChanged.fresh} verifierChanged=${supportFirst.verifierChanged.fresh} " +
                "| fresh per member ${exhaustive.cold.fresh / exhaustive.cold.positiveNotes} -> " +
                "${supportFirst.cold.fresh / supportFirst.cold.positiveNotes} " +
                "| early=${supportFirst.cold.earlyScans} exhausted=${supportFirst.cold.exhaustedScans} " +
                "uncheckedNotes=${supportFirst.cold.uncheckedNotes} " +
                "| steps=${supportFirst.cold.steps} pages=${supportFirst.cold.pages} " +
                "unsupported(exhaustive)=${exhaustive.cold.unsupported} unsupported(support-first)=${supportFirst.cold.unsupported}" +
                " | tail reconciliation fresh=${supportFirst.tailFresh} reused=${supportFirst.tailReused} " +
                "exhausted=${supportFirst.tailExhausted}"
        )
    }

    private suspend fun runMode(earlyStop: Boolean): ModeResult {
        val model = EmbeddingIdentity("scale", 36)
        val verifier = TopicLabelIdentity("fake", "tokens", "template", "support", "schema", "v1", "CPU")
        fun vector(n: Int) = List(36) { if (it == n) 1f else 0f }
        val records = (0 until 2000).associate { n ->
            val id = "note$n"
            id to SemanticRecord(id, "r1", model, SemanticMetadata(id), (0 until 10).map { p ->
                val body = "Purpose-built evidence $n passage $p about the assigned subject."
                EmbeddedPassage(SemanticPassage("$id:p$p", id, "$id:s", "Subject", "$id:b$p", "$id:c$p",
                    0, body.length, body, body, p), vector(if (p < 2) n % 35 else 35))
            })
        }.toMutableMap()
        val sources = records.mapValues { it.value.revision }.toMutableMap()
        val index = object : SemanticIndexStore by FileSemanticIndexStore(files.newFolder()) {
            override suspend fun read(pageId: String) = records[pageId]
        }
        // Keep scale I/O out of this architecture test. Real file reopening is tested by
        // TopicSupportVerificationTest; this double preserves destructive invalidation.
        val decisions = mutableMapOf<String, TopicSupportAssessment>()
        var reuseHits = 0
        val store = object : ProposalProgressStore by FileProposalProgressStore(files.newFolder()) {
            override suspend fun readSupport(workKey: String, sources: Map<String, String>): TopicSupportAssessment? {
                val saved = decisions[workKey]?.takeIf { sources[it.evidence.pageId] == it.evidence.revision }
                if (saved != null) reuseHits++
                return saved
            }
            override suspend fun writeSupport(record: TopicSupportAssessment, stillCurrent: () -> Boolean): ProposalProgressWriteResult {
                if (!stillCurrent()) return ProposalProgressWriteResult.Stale
                decisions[record.workKey] = record
                return ProposalProgressWriteResult.Written
            }
            override suspend fun reconcile(sources: Map<String, String>) {
                decisions.entries.removeAll { sources[it.value.evidence.pageId] != it.value.evidence.revision }
            }
        }

        suspend fun scan(identity: TopicLabelIdentity): Counts {
            reuseHits = 0
            val counts = Counts()
            for (t in 0 until 35) {
                val concept = SupportConcept("topic$t", semanticHash("concept$t"), "Subject $t", "Definition for subject $t")
                val query = TopicQueryEmbedding(TopicRetrievalQuery(concept.topicId, concept.workKey,
                    "${concept.name}\n${concept.definition}", model, TopicRetrievalPolicy(.8f)), vector(t))
                for (record in records.values) {
                    val eligible = record.passages.count {
                        cosineSimilarity(query.vector, it.vector) >= query.query.policy.minimumScore
                    }
                    if (earlyStop) {
                        val page = verifyExistingConceptPage(concept, query, record, index, identity, 0,
                            sources, null, 1, store, { true }, { true }) { request ->
                            counts.fresh++
                            TopicSupportRun.Completed(if (request.evidence.passageId.endsWith("p0"))
                                SupportDecision.SUPPORTED else SupportDecision.UNSUPPORTED)
                        } as ExistingConceptEvidenceResult.Ready
                        counts.supported += page.assessments.count { it.decision == SupportDecision.SUPPORTED }
                        counts.uncertain += page.assessments.count { it.decision == SupportDecision.UNCERTAIN }
                        if (page.termination == ConceptScanTermination.SUPPORTED_EARLY) {
                            counts.earlyScans++
                            counts.uncheckedNotes++
                        } else if (page.termination == ConceptScanTermination.EXHAUSTED && eligible > 0) counts.exhaustedScans++
                        if (page.supported.isNotEmpty()) counts.positiveNotes++
                        if (eligible > 0) { counts.steps++; counts.pages++ }
                    } else {
                        // Pre-change behavior: every threshold-eligible candidate in every page.
                        var cursor: TopicCandidateCursor? = null
                        var conceptSupported = false
                        do {
                            val page = topicCandidatePage(query, record, sources[record.pageId], 1, cursor)
                                as TopicCandidatePage.Ready
                            if (page.candidates.isNotEmpty()) counts.pages++
                            for (candidate in page.candidates) {
                                val result = verifyRetrievedPassage(concept, candidate, index, model, identity, 0,
                                    sources, { true }, store) { request ->
                                    counts.fresh++
                                    TopicSupportRun.Completed(if (request.evidence.passageId.endsWith("p0"))
                                        SupportDecision.SUPPORTED else SupportDecision.UNSUPPORTED)
                                } as SupportVerificationResult.Completed
                                when (result.assessment.decision) {
                                    SupportDecision.SUPPORTED -> { counts.supported++; conceptSupported = true }
                                    SupportDecision.UNSUPPORTED -> counts.unsupported++
                                    SupportDecision.UNCERTAIN -> counts.uncertain++
                                }
                            }
                            cursor = page.next
                        } while (cursor != null)
                        if (conceptSupported) counts.positiveNotes++
                        if (eligible > 0) { counts.steps++; counts.exhaustedScans++ }
                    }
                }
            }
            return counts.copy(reused = reuseHits)
        }

        val cold = scan(verifier)
        assertEquals(20000, records.values.sumOf { it.passages.size })
        val warm = scan(verifier)
        records["note0"] = records.getValue("note0").copy(revision = "r2")
        sources["note0"] = "r2"
        store.reconcile(sources)
        val changed = scan(verifier)
        val verifierChanged = scan(verifier.copy(policy = "v2"))
        // Later exhaustive tail reconciliation: the strongest witness is reused and only the
        // previously unchecked candidate needs a new decision. This is the cost tail work adds
        // after support-first membership, measured on the same fixture and cache.
        var tailFresh = 0; var tailExhausted = 0
        if (earlyStop) {
            reuseHits = 0
            for (t in 0 until 35) {
                val concept = SupportConcept("topic$t", semanticHash("concept$t"), "Subject $t", "Definition for subject $t")
                val query = TopicQueryEmbedding(TopicRetrievalQuery(concept.topicId, concept.workKey,
                    "${concept.name}\n${concept.definition}", model, TopicRetrievalPolicy(.8f)), vector(t))
                for (record in records.values) {
                    val eligible = record.passages.count {
                        cosineSimilarity(query.vector, it.vector) >= query.query.policy.minimumScore
                    }
                    val page = verifyExistingConceptPage(concept, query, record, index, verifier, 0,
                        sources, null, 128, store, { true }, { true }, stopAfterSupport = false) { request ->
                        tailFresh++
                        TopicSupportRun.Completed(if (request.evidence.passageId.endsWith("p0"))
                            SupportDecision.SUPPORTED else SupportDecision.UNSUPPORTED)
                    } as ExistingConceptEvidenceResult.Ready
                    if (eligible > 0 && page.termination == ConceptScanTermination.EXHAUSTED) tailExhausted++
                }
            }
        }
        return ModeResult(cold, warm, changed, verifierChanged, tailFresh, reuseHits, tailExhausted)
    }
}
