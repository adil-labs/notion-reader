package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class TopicLabelBatch(val snapshot: TopicSnapshot, val pending: Boolean = false,
    val unavailable: Boolean = false, val stale: Boolean = false, val failure: String? = null)

/** Serial collaborator of TopicCoordinator; never starts another worker or defines memberships. */
internal class TopicLabelCoordinator(private val store: TopicLabelStore,
    private val identity: () -> TopicLabelIdentity?, private val available: () -> Boolean,
    private val generate: suspend (TopicLabelRequest) -> TopicLabelRun, private val mayWork: () -> Boolean,
    private val waitForQueries: suspend () -> Unit = {},
    val unavailableMessage: () -> String = { "Topic naming is unavailable. Install the topic naming model in Discovery settings." }) {
    fun status(snapshot: TopicSnapshot): TopicNamingStatus = when {
        snapshot.topics.all { it.currentGeneratedLabel?.identity == identity() && it.currentGeneratedLabel != null } -> TopicNamingStatus.Ready
        identity() == null || !available() -> TopicNamingStatus.Unavailable
        else -> TopicNamingStatus.Pending
    }
    suspend fun restore(snapshot: TopicSnapshot, current: () -> Boolean, readSaved: Boolean = true): TopicSnapshot? {
        val expected = identity()
        val topics = ArrayList<DiscoveredTopic>(snapshot.topics.size)
        for (topic in snapshot.topics) {
            currentCoroutineContext().ensureActive()
            if (!current()) return null
            val saved = topic.currentGeneratedLabel?.takeIf { it.identity == expected }
                ?: if (readSaved) store.read(topic.id) else null
            val label = expected?.let { reusableTopicLabel(topic, saved, it) }
            topics += if (topic.generatedLabel == label) topic else topic.copy(generatedLabel = label)
        }
        if (!current() || identity() != expected) return null
        return snapshot.copy(topics = topics)
    }

    suspend fun process(snapshot: TopicSnapshot, epoch: Long, current: () -> Boolean,
        progress: suspend (TopicSnapshot) -> Unit = {}): TopicLabelBatch {
        val expected = identity()
        fun valid() = current() && identity() == expected
        var shown = restore(snapshot, ::valid) ?: return TopicLabelBatch(snapshot, pending = true, stale = true)
        if (expected == null || !available()) return TopicLabelBatch(shown, unavailable = true)
        val ordered = shown.topics.filter { it.currentGeneratedLabel == null && topicLabelRequest(it, expected, epoch) != null }
            .sortedWith(compareBy<DiscoveredTopic> { it.createdOrder }.thenBy { it.id })
        val cursor = store.readCursor()
        val start = cursor?.let { point -> ordered.indexOfFirst {
            it.createdOrder > point.createdOrder || it.createdOrder == point.createdOrder && it.id > point.topicId
        }.takeIf { it >= 0 } } ?: 0
        val pending = ordered.drop(start) + ordered.take(start)
        var failure: String? = null
        for (topic in pending) {
            currentCoroutineContext().ensureActive()
            waitForQueries()
            if (!valid()) return TopicLabelBatch(shown, pending = true, stale = true)
            if (!available()) return TopicLabelBatch(shown, unavailable = true, failure = failure)
            if (!mayWork()) return TopicLabelBatch(shown, pending = true, failure = failure)
            var limit = TopicLabelEvidencePolicy.maximumPassages
            var request = requireNotNull(topicLabelRequest(topic, expected, epoch, limit))
            var result = generate(request)
            // Token budgeting may require fewer complete passages; never truncate their source text.
            while (result == TopicLabelRun.Failed(TopicLabelRuntimeFailure.InputLimit) && limit > 1 && valid() && mayWork() && available()) {
                limit = maxOf(1, limit / 2)
                request = requireNotNull(topicLabelRequest(topic, expected, epoch, limit))
                result = generate(request)
            }
            if (!valid()) return TopicLabelBatch(shown, pending = true, stale = true)
            if (!available()) return TopicLabelBatch(shown, unavailable = true, failure = failure)
            when (result) {
                is TopicLabelRun.Completed -> {
                    val checked = validateTopicLabel(request, result.candidate.text, result.candidate.evidenceIds,
                        topicLabelFingerprint(topic), epoch, result.candidate.definition)
                    val label = (checked as? TopicLabelValidation.Accepted)?.label
                    if (label == null) failure = "Some topic names could not be verified. Retry processing."
                    else {
                        val saved = store.write(label) { valid() && available() }
                        if (saved == TopicLabelWriteResult.Stale) return TopicLabelBatch(shown, pending = true, stale = true)
                        if (saved != TopicLabelWriteResult.Written) return TopicLabelBatch(shown, pending = true,
                            failure = "Topic names could not be saved. Check storage and retry processing.")
                        if (!valid()) return TopicLabelBatch(shown, pending = true, stale = true)
                        shown = shown.copy(topics = shown.topics.map { if (it.id == topic.id) it.copy(generatedLabel = label) else it })
                        progress(shown)
                    }
                }
                TopicLabelRun.Unavailable -> return TopicLabelBatch(shown, unavailable = true, failure = failure)
                TopicLabelRun.Cancelled -> {
                    val saved = store.writeCursor(TopicLabelCursor(topic.createdOrder, topic.id), ::valid)
                    if (saved != TopicLabelWriteResult.Written) return TopicLabelBatch(shown, pending = true,
                        stale = saved == TopicLabelWriteResult.Stale || !valid(),
                        failure = "Topic naming progress could not be saved. Check storage and retry processing.")
                    return TopicLabelBatch(shown, pending = true, failure = failure)
                }
                is TopicLabelRun.Failed -> failure = "Some topic names are unavailable. Retry processing."
            }
            if (store.writeCursor(TopicLabelCursor(topic.createdOrder, topic.id), ::valid) != TopicLabelWriteResult.Written)
                return TopicLabelBatch(shown, pending = true, stale = !valid(),
                    failure = "Topic naming progress could not be saved. Check storage and retry processing.")
        }
        return TopicLabelBatch(shown, pending = failure != null, failure = failure)
    }

    suspend fun withdraw(snapshot: TopicSnapshot) {
        store.prune((snapshot.topics + snapshot.provisionalTopics).associate { it.id to topicLabelFingerprint(it) })
    }
    suspend fun clear() = store.clear()
    suspend fun bytes() = store.bytes()
}
