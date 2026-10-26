package com.example.notionreader.application

import com.example.notionreader.data.local.*
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TopicRetrievalCoordinatorTest {
    @get:Rule val files = TemporaryFolder()
    private val identity = EmbeddingIdentity("embedding", 2)
    private var embeddingCalls = 0
    private fun record(id: String, relevant: Boolean = true, revision: String = "r1"): SemanticRecord {
        val body = "Moisture and suitable temperature allow seeds to germinate and begin sprouting."
        val p = SemanticPassage("$id:p", id, "$id:s", "Summary", "$id:b", "$id:c", 0, body.length, body, body, 0)
        return SemanticRecord(id, revision, identity, SemanticMetadata("Note"), listOf(EmbeddedPassage(p,
            if (relevant) listOf(1f, 0f) else listOf(0f, 1f))))
    }
    private fun snapshot(): TopicSnapshot {
        val source = record("seed", false)
        val p = source.passages.single()
        val topic = DiscoveredTopic("canonical", "Internal seed wording", p.passage, p,
            listOf(TopicMembership("seed", source.metadata, p.passage, 1f, p.passage, "r1")), 1)
        val request = topicLabelRequest(topic, TopicLabelIdentity("a", "t", "c", "p", "s", "v", "GPU"), 0)!!
        val label = (validateTopicLabel(request, "Seed germination", request.evidence.map { it.passageId }, request.groupFingerprint, 0,
            "Conditions that allow seeds to germinate and sprout.") as TopicLabelValidation.Accepted).label
        return TopicSnapshot(topics = listOf(topic.copy(generatedLabel = label)), identity = identity, complete = true)
    }
    private fun coordinator(index: SemanticIndexStore, store: TopicRetrievalStore, window: Int = 128,
        embed: suspend (String) -> List<Float> = { embeddingCalls++; listOf(1f, 0f) }) =
        TopicRetrievalCoordinator(index, store, { identity }, TopicRetrievalPolicy(.8f), embed,
            { true }, {}, Mutex(), recordsPerWindow = window)

    @Test fun aPartialScanNeverPublishesSeedMembersAndDurableResumeFindsAnOutsideNote() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record("seed", false)); index.write(record("outside"))
        val directory = files.newFolder(); val store = FileTopicRetrievalStore(directory)
        val original = snapshot(); val sources = mapOf("seed" to "r1", "outside" to "r1")
        val first = coordinator(index, store, 1).process(original, sources) { true }
        assertTrue(first.pending); assertTrue(first.snapshot.topics.single().matchingMembers.isEmpty())
        val resumed = coordinator(index, FileTopicRetrievalStore(directory), 1).process(first.snapshot, sources) { true }
        assertFalse(resumed.pending)
        assertEquals(listOf("outside"), resumed.snapshot.topics.single().matchingMembers.map { it.pageId })
        assertEquals(listOf("seed"), resumed.snapshot.topics.single().members.map { it.pageId })
        assertEquals("canonical", resumed.snapshot.topics.single().id); assertEquals(1, embeddingCalls)
    }
    @Test fun unchangedResultsAndQueryVectorsSurviveCoordinatorRecreationWithoutNativeWork() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record("seed", false)); index.write(record("outside"))
        val directory = files.newFolder(); val sources = mapOf("seed" to "r1", "outside" to "r1")
        coordinator(index, FileTopicRetrievalStore(directory)).process(snapshot(), sources) { true }
        val restored = coordinator(index, FileTopicRetrievalStore(directory)).restore(snapshot(), sources) { true }!!
        assertEquals(listOf("outside"), restored.topics.single().matchingMembers.map { it.pageId })
        coordinator(index, FileTopicRetrievalStore(directory)).process(restored, sources) { true }
        assertEquals(1, embeddingCalls)
    }
    @Test fun aChangedOutsideSourceUpdatesMatchesWithoutRegeneratingTheConceptOrQuery() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record("seed", false)); index.write(record("outside"))
        val store = FileTopicRetrievalStore(files.newFolder()); val worker = coordinator(index, store)
        val original = snapshot()
        worker.process(original, mapOf("seed" to "r1", "outside" to "r1")) { true }
        index.write(record("outside", false, "r2"))
        val changed = worker.process(original, mapOf("seed" to "r1", "outside" to "r2")) { true }
        assertFalse(changed.pending); assertNotNull(changed.snapshot.topics.single().currentTopicMatches)
        assertTrue(changed.snapshot.topics.single().matchingMembers.isEmpty())
        assertEquals(original.topics.single().currentGeneratedLabel, changed.snapshot.topics.single().currentGeneratedLabel)
        assertEquals(1, embeddingCalls)
    }
    @Test fun sourceWithdrawalCannotRestoreAnOldMatchedNote() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record("seed", false)); index.write(record("outside"))
        val store = FileTopicRetrievalStore(files.newFolder()); val worker = coordinator(index, store)
        val original = snapshot()
        worker.process(original, mapOf("seed" to "r1", "outside" to "r1")) { true }
        val restored = worker.restore(original, mapOf("seed" to "r1")) { true }!!
        assertTrue(restored.topics.single().matchingMembers.isEmpty())
        assertEquals(1, embeddingCalls)
    }
    @Test fun aSupersededQueryCannotSaveOrPublishItsLateEmbedding() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record("seed", false))
        val store = FileTopicRetrievalStore(files.newFolder()); var current = true
        val worker = coordinator(index, store, embed = { current = false; listOf(1f, 0f) })
        val result = worker.process(snapshot(), mapOf("seed" to "r1")) { current }
        assertTrue(result.stale); assertEquals(0L, store.bytes())
        assertTrue(result.snapshot.topics.single().matchingMembers.isEmpty())
    }
    @Test fun completeMatchingCoverageExtendsBeyondASearchPageThroughDurableWindows() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record("seed", false))
        val sources = linkedMapOf("seed" to "r1")
        repeat(31) { i -> val id = "outside$i"; index.write(record(id)); sources[id] = "r1" }
        val directory = files.newFolder()
        var result = TopicRetrievalBatch(snapshot(), pending = true)
        repeat(8) {
            if (result.pending) result = coordinator(index, FileTopicRetrievalStore(directory), 7)
                .process(result.snapshot, sources) { true }
        }
        assertFalse(result.pending); assertEquals(31, result.snapshot.topics.single().matchingMembers.size)
        assertEquals(1, embeddingCalls)
    }
    @Test fun inspectedMatchesStayPinnedUntilRefreshWhileRemovedSourcesWithdrawImmediately() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record("seed", false)); index.write(record("outside"))
        val store = FileTopicRetrievalStore(files.newFolder()); val worker = coordinator(index, store)
        val sources = mapOf("seed" to "r1", "outside" to "r1")
        val pinned = worker.process(snapshot(), sources) { true }.snapshot
        index.write(record("added"))
        val expanded = sources + ("added" to "r1")
        worker.process(pinned, expanded) { true }
        val inspected = worker.restore(pinned, expanded, readSaved = false) { true }!!
        assertEquals(listOf("outside"), inspected.topics.single().matchingMembers.map { it.pageId })
        val refreshed = worker.restore(pinned, expanded) { true }!!
        assertEquals(setOf("outside", "added"), refreshed.topics.single().matchingMembers.map { it.pageId }.toSet())
        val withdrawn = worker.restore(inspected, mapOf("seed" to "r1"), readSaved = false) { true }!!
        assertTrue(withdrawn.topics.single().matchingMembers.isEmpty())
        worker.withdraw(withdrawn.copy(acceptedRevisions = mapOf("seed" to "r1")))
        assertFalse(store.read("canonical")!!.scannedRevisions.containsKey("outside"))
        assertFalse(store.read("canonical")!!.matches.containsKey("outside"))
    }
    @Test fun withdrawingAConceptAlsoRemovesItsSavedQueryMeaningAndVector() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record("seed", false)); index.write(record("outside"))
        val store = FileTopicRetrievalStore(files.newFolder()); val worker = coordinator(index, store)
        val original = snapshot(); val sources = mapOf("seed" to "r1", "outside" to "r1")
        worker.process(original, sources) { true }
        worker.withdraw(original.copy(topics = original.topics.map { it.copy(generatedLabel = null) }, acceptedRevisions = sources))
        assertNull(store.read("canonical"))
        assertEquals(0L, store.bytes())
    }
    @Test fun matchingNotesAreRankedByRelevanceBeforePreviewSelection() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder())
        val weaker = record("aweaker")
        index.write(weaker.copy(passages = weaker.passages.map { it.copy(vector = listOf(.9f, .4358899f)) }))
        index.write(record("zstronger"))
        val result = coordinator(index, FileTopicRetrievalStore(files.newFolder())).process(snapshot(),
            mapOf("aweaker" to "r1", "zstronger" to "r1")) { true }
        assertEquals(listOf("zstronger", "aweaker"), result.snapshot.topics.single().matchingMembers.map { it.pageId })
    }
    @Test fun unreadableSourcesCannotStarveLaterNotesAcrossWorkerRecreation() = runBlocking {
        val index = FileSemanticIndexStore(files.newFolder()); index.write(record("zvalid"))
        val directory = files.newFolder()
        val sources = mapOf("amissing" to "r1", "bmissing" to "r1", "cmissing" to "r1", "zvalid" to "r1")
        var result = TopicRetrievalBatch(snapshot())
        repeat(3) {
            result = coordinator(index, FileTopicRetrievalStore(directory), 2).process(result.snapshot, sources) { true }
        }
        val saved = FileTopicRetrievalStore(directory).read("canonical")!!
        assertEquals("r1", saved.scannedRevisions["zvalid"])
        assertTrue(result.pending)
        assertFalse(saved.complete)
        assertNull(saved.scannedRevisions["amissing"])
        assertTrue(result.snapshot.topics.single().matchingMembers.isEmpty())
    }
}
