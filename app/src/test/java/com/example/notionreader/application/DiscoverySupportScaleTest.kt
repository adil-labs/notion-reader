package com.example.notionreader.application

import com.example.notionreader.data.local.FileProposalProgressStore
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic vectors exercise shipping retrieval and verification, not model quality. */
class DiscoverySupportScaleTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun twentyThousandPassagesUseOnlyRetrievedEvidenceAndReuseUnchangedDecisions() = runBlocking {
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
        val store = object : ProposalProgressStore by FileProposalProgressStore(files.newFolder()) {
            override suspend fun readSupport(workKey: String, sources: Map<String, String>) =
                decisions[workKey]?.takeIf { sources[it.evidence.pageId] == it.evidence.revision }
            override suspend fun writeSupport(record: TopicSupportAssessment, stillCurrent: () -> Boolean): ProposalProgressWriteResult {
                if (!stillCurrent()) return ProposalProgressWriteResult.Stale
                decisions[record.workKey] = record
                return ProposalProgressWriteResult.Written
            }
            override suspend fun reconcile(sources: Map<String, String>) {
                decisions.entries.removeAll { sources[it.value.evidence.pageId] != it.value.evidence.revision }
            }
        }
        var calls = 0
        suspend fun scan(identity: TopicLabelIdentity): Int {
            val before = calls
            for (t in 0 until 35) {
                val concept = SupportConcept("topic$t", semanticHash("concept$t"), "Subject $t", "Definition for subject $t")
                val query = TopicQueryEmbedding(TopicRetrievalQuery(concept.topicId, concept.workKey,
                    "${concept.name}\n${concept.definition}", model, TopicRetrievalPolicy(.8f)), vector(t))
                for (record in records.values) {
                    var cursor: TopicCandidateCursor? = null
                    var checked = 0
                    do {
                        val page = verifyExistingConceptPage(concept, query, record, index, identity, 0,
                            sources, cursor, 1, store, { true }, { true }) {
                            calls++
                            TopicSupportRun.Completed(if (it.evidence.passageId.endsWith("p0"))
                                SupportDecision.SUPPORTED else SupportDecision.UNSUPPORTED)
                        } as ExistingConceptEvidenceResult.Ready
                        assertTrue(page.supported.size <= 1)
                        checked += page.supported.size
                        cursor = page.next
                    } while (cursor != null)
                    assertEquals(if (record.pageId.removePrefix("note").toInt() % 35 == t) 1 else 0, checked)
                }
            }
            return calls - before
        }
        assertEquals(20000, records.values.sumOf { it.passages.size })
        val cold = scan(verifier)
        val warm = scan(verifier)
        records["note0"] = records.getValue("note0").copy(revision = "r2")
        sources["note0"] = "r2"
        store.reconcile(sources)
        val changed = scan(verifier)
        val modelChanged = scan(verifier.copy(policy = "v2"))
        assertEquals(4000, cold)
        assertEquals(0, warm)
        assertEquals(2, changed)
        assertEquals(4000, modelChanged)
        println("Support scale: passages=20000 notes=2000 concepts=35 cold=$cold warm=$warm oneNoteChanged=$changed verifierChanged=$modelChanged")
    }
}
