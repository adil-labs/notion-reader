package com.example.notionreader.domain.discovery

@kotlinx.serialization.Serializable
data class TopicLabelCursor(val createdOrder: Long, val topicId: String)

enum class TopicLabelWriteResult { Written, Stale, Invalid, StorageRefused, IoFailure }
/** Label records decorate canonical groups; this store is not a source of topic identities or membership. */
interface TopicLabelStore {
    suspend fun read(topicId: String): GeneratedTopicLabel?
    suspend fun write(label: GeneratedTopicLabel, stillCurrent: () -> Boolean): TopicLabelWriteResult
    suspend fun remove(topicId: String)
    suspend fun prune(eligibleFingerprints: Map<String, String>)
    suspend fun clear()
    suspend fun readCursor(): TopicLabelCursor?
    suspend fun writeCursor(cursor: TopicLabelCursor, stillCurrent: () -> Boolean): TopicLabelWriteResult
    suspend fun bytes(): Long
}
