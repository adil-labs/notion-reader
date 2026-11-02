package com.example.notionreader.data.local

import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.document.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileProposalProgressStoreTest {
    @get:Rule val files = TemporaryFolder()
    private val sources = mapOf("new" to "r1", "older" to "r2")
    private val record = ProposalProgressRecord("new", "r1", 0, EmbeddingIdentity("model", 2),
        ProposalScanCheckpoint(semanticHash("context"), mapOf("older" to "r2"), mapOf("anchor" to listOf(
            ProposalScoredEvidence(ProposalEvidenceRef("older", "r2", "passage"), .9f, ProposalReason.SEMANTIC_NEIGHBOR))), emptySet()))
    private val abstention = ProposalAbstentionRecord(semanticHash("exact-evidence-and-policy"),
        listOf(ProposalEvidenceRef("new", "r1", "anchor"), ProposalEvidenceRef("older", "r2", "passage")),
        DiscoveryAbstentionReason.MIXED_EVIDENCE)
    private val support = TopicSupportAssessment("topic", "concept", ProposalEvidenceRef("older", "r2", "passage"),
        TopicLabelIdentity("model", "tokenizer", "template", "prompt", "schema", "policy", "CPU"),
        semanticHash("support-work"), SupportDecision.SUPPORTED)

    @Test fun pendingConceptSurvivesRestartAndWithdrawalRemovesAllDerivedText() = runBlocking {
        val dir = files.newFolder(); val store = FileProposalProgressStore(dir)
        val concept = ProposalConceptRecord(abstention.workKey, abstention.evidence, "Seed germination",
            "Conditions for seeds to germinate.", "Listen to germination conditions.", listOf("anchor"))
        assertEquals(ProposalProgressWriteResult.Written, store.writeConcept(concept) { true })
        assertEquals(concept, FileProposalProgressStore(dir).readConcept(concept.workKey, sources))
        assertNull(store.readConcept(concept.workKey, sources - "older"))
        assertEquals(ProposalProgressWriteResult.Invalid, store.writeAbstention(abstention) { true })
        store.reconcile(sources - "older")
        assertNull(store.readConcept(concept.workKey, sources))
        assertEquals(0L, store.bytes())
    }

    @Test fun passageDeltaSurvivesRestartOnlyForTheCurrentSourceAndModel() = runBlocking {
        val dir = files.newFolder(); val store = FileProposalProgressStore(dir)
        val delta = PassageDeltaRecord("new", "r1", EmbeddingIdentity("model", 2), semanticHash("fingerprint"),
            PassageDelta(setOf("new-body"), setOf("old-body"), mapOf("old" to "current")))
        assertEquals(ProposalProgressWriteResult.Written, store.writeDelta(delta) { true })
        val reopened = FileProposalProgressStore(dir)
        assertEquals(delta, reopened.readDelta("new", "r1", delta.embedding))
        assertNull(reopened.readDelta("new", "r2", delta.embedding))
        assertNull(reopened.readDelta("new", "r1", delta.embedding.copy(model = "other")))
        store.reconcile(sources - "new")
        assertNull(reopened.readDelta("new", "r1", delta.embedding))
        assertEquals(0L, store.bytes())
    }

    @Test fun staleAndInvalidDeltaCannotBePersisted() = runBlocking {
        val dir = files.newFolder(); val store = FileProposalProgressStore(dir)
        val delta = PassageDeltaRecord("new", "r1", EmbeddingIdentity("model", 2), semanticHash("fingerprint"),
            PassageDelta(setOf("new-body"), emptySet(), emptyMap()))
        assertEquals(ProposalProgressWriteResult.Invalid, store.writeDelta(delta.copy(delta = delta.delta.copy(changed = setOf("")))) { true })
        var checks = 0
        assertEquals(ProposalProgressWriteResult.Stale, store.writeDelta(delta) { ++checks == 1 })
        assertNull(store.readDelta("new", "r1", delta.embedding))
        assertFalse(dir.listFiles()!!.any { it.extension == "tmp" })
    }

    @Test fun conceptScanCheckpointSurvivesRestartAndEverySourceWithdrawal() = runBlocking {
        val dir = files.newFolder(); val store = FileProposalProgressStore(dir)
        val scope = semanticHash("catalog")
        val checkpoint = ExistingConceptScanRecord("older", "r2", sources, scope,
            ExistingConceptScanCursor(scope, 1, null), listOf(support))
        assertEquals(ProposalProgressWriteResult.Written, store.replaceConceptScan(null, checkpoint) { true })
        val reopened = FileProposalProgressStore(dir)
        assertEquals(checkpoint, reopened.readConceptScan("older", sources))
        assertNull(reopened.readConceptScan("older", sources + ("new" to "r3")))
        store.reconcile(sources - "new")
        assertNull(reopened.readConceptScan("older", sources)); assertEquals(0L, store.bytes())
    }

    @Test fun staleOrMalformedConceptScanCheckpointCannotBeSaved() = runBlocking {
        val dir = files.newFolder(); val store = FileProposalProgressStore(dir); val scope = semanticHash("catalog")
        val checkpoint = ExistingConceptScanRecord("older", "r2", sources, scope,
            ExistingConceptScanCursor(scope, 1, null), listOf(support))
        assertEquals(ProposalProgressWriteResult.Invalid, store.replaceConceptScan(null, checkpoint.copy(next = checkpoint.next!!.copy(scopeKey = semanticHash("other")))) { true })
        var checks = 0
        assertEquals(ProposalProgressWriteResult.Stale, store.replaceConceptScan(null, checkpoint) { ++checks == 1 })
        assertNull(store.readConceptScan("older", sources))
        assertFalse(dir.listFiles()!!.any { it.extension == "tmp" })
    }

    @Test fun compareAndReplaceCannotOverwriteANewerConceptScan() = runBlocking {
        val dir = files.newFolder(); val store = FileProposalProgressStore(dir); val scope = semanticHash("catalog")
        val first = ExistingConceptScanRecord("older", "r2", sources, scope, ExistingConceptScanCursor(scope, 1, null), listOf(support))
        val second = first.copy(next = ExistingConceptScanCursor(scope, 2, null))
        val third = first.copy(next = null)
        assertEquals(ProposalProgressWriteResult.Written, store.replaceConceptScan(null, first) { true })
        assertEquals(ProposalProgressWriteResult.Written, store.replaceConceptScan(first, second) { true })
        assertEquals(ProposalProgressWriteResult.Stale, store.replaceConceptScan(first, third) { true })
        assertEquals(second, store.readConceptScan("older", sources))
        var checks = 0
        assertEquals(ProposalProgressWriteResult.Stale, store.replaceConceptScan(second, third) { ++checks == 1 })
        assertEquals(second, FileProposalProgressStore(dir).readConceptScan("older", sources))
    }

    @Test fun supportDecisionSurvivesRestartOnlyForCurrentSourceAndExactKey() = runBlocking {
        val dir = files.newFolder(); val store = FileProposalProgressStore(dir)
        assertEquals(ProposalProgressWriteResult.Written, store.writeSupport(support) { true })
        val restored = FileProposalProgressStore(dir)
        assertEquals(support, restored.readSupport(support.workKey, sources))
        assertNull(restored.readSupport(semanticHash("different"), sources))
        assertNull(restored.readSupport(support.workKey, sources + ("older" to "r3")))
        store.reconcile(sources - "older")
        assertNull(restored.readSupport(support.workKey, sources)); assertEquals(0L, store.bytes())
    }

    @Test fun invalidOrStaleSupportWritesCannotCreateAReusableDecision() = runBlocking {
        val dir = files.newFolder(); val store = FileProposalProgressStore(dir)
        assertEquals(ProposalProgressWriteResult.Invalid, store.writeSupport(support.copy(evidence = support.evidence.copy(passageId = ""))) { true })
        var checks = 0
        assertEquals(ProposalProgressWriteResult.Stale, store.writeSupport(support) { ++checks == 1 })
        assertNull(store.readSupport(support.workKey, sources))
        assertFalse(dir.listFiles()!!.any { it.extension == "tmp" })
    }

    @Test fun abstentionSurvivesRestartOnlyForCurrentSourcesAndTheExactKey() = runBlocking {
        val dir = files.newFolder(); val store = FileProposalProgressStore(dir)
        assertEquals(ProposalProgressWriteResult.Written, store.writeAbstention(abstention) { true })
        val restored = FileProposalProgressStore(dir)
        assertEquals(abstention, restored.readAbstention(abstention.workKey, sources))
        assertNull(restored.readAbstention(semanticHash("changed-evidence"), sources))
        assertNull(restored.readAbstention(abstention.workKey, sources + ("older" to "r3")))
        store.reconcile(sources - "older")
        assertNull(restored.readAbstention(abstention.workKey, sources))
        assertEquals(0L, store.bytes())
    }

    @Test fun abstentionWritesRejectStaleTicketsAndInvalidReferences() = runBlocking {
        val dir = files.newFolder(); val store = FileProposalProgressStore(dir)
        assertEquals(ProposalProgressWriteResult.Invalid, store.writeAbstention(abstention.copy(evidence = emptyList())) { true })
        assertEquals(ProposalProgressWriteResult.Invalid, store.writeAbstention(abstention.copy(evidence = listOf(abstention.evidence.first(), abstention.evidence.first()))) { true })
        var checks = 0
        assertEquals(ProposalProgressWriteResult.Stale, store.writeAbstention(abstention) { ++checks == 1 })
        assertNull(store.readAbstention(abstention.workKey, sources))
        assertFalse(dir.listFiles()!!.any { it.extension == "tmp" })
    }

    @Test fun sourceReferencesAndProgressSurviveReopeningWithoutSourceBodies() = runBlocking {
        val dir = files.newFolder(); val store = FileProposalProgressStore(dir)
        assertEquals(ProposalProgressWriteResult.Written, store.write(record) { true })
        assertEquals(record, FileProposalProgressStore(dir).read("new", 0, sources))
        assertEquals(dir.listFiles()!!.sumOf { it.length() }, store.bytes())
        assertFalse(dir.listFiles()!!.single().readText().contains("passage body"))
    }

    @Test fun withdrawnSourcesCannotBeReadOrRetainedAfterReconciliation() = runBlocking {
        val dir = files.newFolder(); val store = FileProposalProgressStore(dir)
        assertEquals(ProposalProgressWriteResult.Written, store.write(record) { true })
        assertNull(store.read("new", 0, sources - "older"))
        store.reconcile(sources - "older")
        assertNull(FileProposalProgressStore(dir).read("new", 0, sources))
        assertEquals(0L, store.bytes())
    }

    @Test fun staleCommitAndStorageRefusalCannotResurrectWithdrawnEvidence() = runBlocking {
        val dir = files.newFolder(); val store = FileProposalProgressStore(dir)
        assertEquals(ProposalProgressWriteResult.Written, store.write(record) { true })
        var checks = 0
        assertEquals(ProposalProgressWriteResult.Stale, store.write(record) { ++checks == 1 })
        val refused = FileProposalProgressStore(dir, object : StorageWriteGate {
            override suspend fun <T> write(category: StorageCategory, additionalPeakBytes: Long, block: suspend () -> T): T =
                throw StorageRefused(StorageFailure.DEVICE_SPACE)
        })
        assertEquals(ProposalProgressWriteResult.StorageRefused, refused.write(record) { true })
        assertNull(refused.read("new", 0, sources - "older"))
        store.reconcile(sources - "older")
        assertNull(refused.read("new", 0, sources))
        assertFalse(dir.listFiles()!!.any { it.extension == "tmp" })
    }

    @Test fun invalidReferencesAndCorruptFilesCannotRestoreProgress() = runBlocking {
        val dir = files.newFolder(); val store = FileProposalProgressStore(dir)
        val invalid = record.copy(checkpoint = record.checkpoint.copy(scannedRevisions = emptyMap()))
        assertEquals(ProposalProgressWriteResult.Invalid, store.write(invalid) { true })
        assertEquals(ProposalProgressWriteResult.Written, store.write(record) { true })
        dir.listFiles()!!.single().writeText("broken")
        assertNull(FileProposalProgressStore(dir).read("new", 0, sources))
        store.reconcile(sources); assertEquals(0L, store.bytes())
    }

    @Test fun resetDeletesOnlyOwnedProgressFiles() = runBlocking {
        val dir = files.newFolder(); val store = FileProposalProgressStore(dir)
        val unrelated = File(dir, "unrelated.txt").apply { writeText("keep") }
        assertEquals(ProposalProgressWriteResult.Written, store.write(record) { true }); store.clear()
        assertNull(store.read("new", 0, sources)); assertEquals("keep", unrelated.readText())
    }
}
