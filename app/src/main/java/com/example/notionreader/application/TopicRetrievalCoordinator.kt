package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class TopicRetrievalBatch(val snapshot: TopicSnapshot, val pending: Boolean = false,
    val stale: Boolean = false, val failure: String? = null)

/** Collaborator of the existing topic worker; never owns a worker or another catalog. */
internal class TopicRetrievalCoordinator(private val index: SemanticIndexStore, private val store: TopicRetrievalStore,
    private val identity: () -> EmbeddingIdentity, private val policy: TopicRetrievalPolicy,
    private val embed: suspend (String) -> List<Float>, private val mayWork: () -> Boolean,
    private val waitForQueries: suspend () -> Unit, private val bulkGate: Mutex,
    private val recordsPerWindow: Int = 128) {
    init { require(recordsPerWindow in 1..4096) }
    private fun query(topic: DiscoveredTopic, model: EmbeddingIdentity): TopicRetrievalQuery? {
        val concept = topic.currentGeneratedLabel ?: return null
        return topicConceptQuery(concept)?.let { TopicRetrievalQuery(topic.id, concept.workKey, it, model, policy) }
    }
    private suspend fun readRecord(id: String): SemanticRecord? {
        waitForQueries(); currentCoroutineContext().ensureActive()
        return bulkGate.withLock { index.read(id) }
    }
    private fun retained(saved: TopicRetrievalProgress, sources: Map<String, String>): TopicRetrievalProgress {
        val scanned = saved.scannedRevisions.filter { (id, revision) -> sources[id] == revision }
        return saved.copy(scannedRevisions = scanned,
            matches = saved.matches.filter { (id, match) -> scanned[id] == match.revision },
            complete = saved.complete && scanned == sources)
    }
    private suspend fun hydrate(progress: TopicRetrievalProgress, sources: Map<String, String>,
        current: () -> Boolean): TopicMatches? {
        if (!topicRetrievalIsCurrent(progress, progress.queryEmbedding.query, sources)) return null
        val members = ArrayList<TopicMembership>(progress.matches.size)
        for ((id, match) in progress.matches.toSortedMap()) {
            currentCoroutineContext().ensureActive()
            if (!current()) return null
            val record = readRecord(id) ?: return null
            if (record.identity != progress.queryEmbedding.query.embedding || record.revision != match.revision) return null
            val passage = record.passages.firstOrNull { it.passage.id == match.passageId && it.passage.isBodyEvidence() }?.passage ?: return null
            members += TopicMembership(id, record.metadata, passage, match.score, passage, record.revision)
        }
        return if (current()) TopicMatches(progress.queryEmbedding.query,
            members.sortedWith(compareByDescending<TopicMembership> { it.score }.thenBy { it.pageId })) else null
    }
    suspend fun restore(snapshot: TopicSnapshot, sources: Map<String, String>, readSaved: Boolean = true,
        current: () -> Boolean): TopicSnapshot? {
        val model = identity()
        fun valid() = current() && identity() == model
        val topics = ArrayList<DiscoveredTopic>(snapshot.topics.size)
        for (topic in snapshot.topics) {
            if (!valid()) return null
            val request = query(topic, model)
            if (!readSaved) {
                val pinned = topic.currentTopicMatches?.takeIf { it.query.key == request?.key }
                val matches = pinned?.copy(members = pinned.members.filter { sources[it.pageId] == it.revision })
                topics += if (topic.topicMatches == matches) topic else topic.copy(topicMatches = matches)
                continue
            }
            val saved = request?.let { store.read(topic.id)?.takeIf { it.queryEmbedding.query.key == request.key } }
            val matches = saved?.let { hydrate(retained(it, sources), sources, ::valid) }
            topics += if (topic.topicMatches == matches) topic else topic.copy(topicMatches = matches)
        }
        return if (valid()) snapshot.copy(topics = topics) else null
    }
    suspend fun process(snapshot: TopicSnapshot, sources: Map<String, String>, current: () -> Boolean): TopicRetrievalBatch {
        val model = identity()
        fun valid() = current() && identity() == model
        var shown = restore(snapshot, sources, current = ::valid) ?: return TopicRetrievalBatch(snapshot, pending = true, stale = true)
        var pending = false
        for (topic in shown.topics.sortedWith(compareBy<DiscoveredTopic> { it.createdOrder }.thenBy { it.id })) {
            currentCoroutineContext().ensureActive(); waitForQueries()
            if (!valid()) return TopicRetrievalBatch(shown, pending = true, stale = true)
            val request = query(topic, model)
            if (request == null) { store.remove(topic.id); continue }
            val original = store.read(topic.id)?.takeIf { it.queryEmbedding.query.key == request.key }
            var progress = original?.let { retained(it, sources) }
            if (progress?.complete != true) {
                if (!mayWork()) return TopicRetrievalBatch(shown, pending = true)
                if (progress == null) {
                    val vector = embed(request.text)
                    if (!valid()) return TopicRetrievalBatch(shown, pending = true, stale = true)
                    progress = TopicRetrievalProgress(TopicQueryEmbedding(request, vector))
                }
                val scanned = progress.scannedRevisions.toMutableMap()
                val matches = progress.matches.toMutableMap()
                var processed = 0
                var lastAttempted = progress.lastAttemptedId
                val ordered = sources.toSortedMap()
                // Failed reads stay incomplete, but the durable cursor gives later sources a turn.
                val remaining = ordered.entries.filter { it.key > (lastAttempted ?: "") } +
                    ordered.entries.filter { it.key <= (lastAttempted ?: "") }
                for ((id, revision) in remaining) {
                    if (scanned[id] == revision) continue
                    if (!valid()) return TopicRetrievalBatch(shown, pending = true, stale = true)
                    if (!mayWork() || processed >= recordsPerWindow) break
                    val record = readRecord(id)
                    processed++
                    lastAttempted = id
                    if (record == null || record.identity != model || record.revision != revision) continue
                    val match = matchTopicRecord(progress.queryEmbedding, record, revision)
                    scanned[id] = revision
                    if (match == null) matches.remove(id) else matches[id] = match
                }
                progress = progress.copy(scannedRevisions = scanned.toMap(), matches = matches.toMap(),
                    complete = scanned == sources, lastAttemptedId = lastAttempted)
            }
            if (!valid()) return TopicRetrievalBatch(shown, pending = true, stale = true)
            if (progress != original) {
                when (store.write(progress) { valid() }) {
                    TopicRetrievalWriteResult.Written -> Unit
                    TopicRetrievalWriteResult.Stale -> return TopicRetrievalBatch(shown, pending = true, stale = true)
                    TopicRetrievalWriteResult.RecordMemoryLimit -> return TopicRetrievalBatch(shown, pending = true,
                        failure = "Topic matches exceed the record memory limit. Coverage is incomplete.")
                    else -> return TopicRetrievalBatch(shown, pending = true,
                        failure = "Topic matches could not be saved. Check storage and retry processing.")
                }
            }
            val matches = hydrate(progress, sources, ::valid)
            if (!valid()) return TopicRetrievalBatch(shown, pending = true, stale = true)
            if (progress.complete && matches == null) return TopicRetrievalBatch(shown, pending = true,
                failure = "Matching Note evidence is unavailable. Retry processing.")
            pending = pending || !progress.complete
            shown = shown.copy(topics = shown.topics.map { if (it.id == topic.id) it.copy(topicMatches = matches) else it })
        }
        return TopicRetrievalBatch(shown, pending = pending)
    }
    suspend fun withdraw(snapshot: TopicSnapshot) {
        store.prune(snapshot.topics.mapTo(hashSetOf()) { it.id })
        for (topic in snapshot.topics) {
            val saved = store.read(topic.id) ?: continue
            if (query(topic, identity())?.key != saved.queryEmbedding.query.key) {
                store.remove(topic.id)
                continue
            }
            val eligible = retained(saved, snapshot.acceptedRevisions)
            if (eligible != saved && store.write(eligible) { true } != TopicRetrievalWriteResult.Written)
                store.remove(topic.id) // Withdraw obsolete references even when replacement storage is refused.
        }
    }
    suspend fun clear() = store.clear()
    suspend fun bytes() = store.bytes()
}
