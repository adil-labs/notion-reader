package com.example.notionreader.application

import com.example.notionreader.domain.discovery.SemanticMatch
import com.example.notionreader.domain.discovery.TopicSnapshot
import com.example.notionreader.domain.discovery.TopicMembership
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Manages the Discovery preview list and topic/connection preview extracts.
 */
internal class DiscoveryPreviews(
    private val mutable: MutableStateFlow<DiscoveryState>,
    private val retrieval: DiscoveryRetrieval
) {
    fun topicPreview(topicId: String): List<SemanticMatch> =
        mutable.value.topicSnapshot.topics
            .firstOrNull { it.id == topicId }?.members.orEmpty().take(5).map { it.toMatch() }

    suspend fun connectionPreview(
        connectionId: String,
        snapshot: TopicSnapshot = mutable.value.topicSnapshot,
        expectedCacheRevision: Long = mutable.value.cachedRevision
    ): List<SemanticMatch> =
        retrieval.connectionWindow(snapshot, selectedId = connectionId, expectedCacheRevision = expectedCacheRevision)
            .connections.firstOrNull()?.let { listOf(it.left.toMatch(), it.right.toMatch()) }.orEmpty()

    fun preparePreview(matches: List<SemanticMatch>, label: String = "Discovery recommendations") {
        mutable.update {
            it.copy(
                preview = matches.distinctBy { match -> match.pageId }.take(5),
                previewLabel = label,
                unavailablePreviewIds = emptySet(),
                previewStatus = null
            )
        }
    }

    fun removePreview(pageId: String) {
        mutable.update {
            val unavailable = it.unavailablePreviewIds - pageId
            it.copy(
                preview = it.preview.filterNot { match -> match.pageId == pageId },
                unavailablePreviewIds = unavailable,
                previewStatus = it.previewStatus.takeUnless { unavailable.isEmpty() }
            )
        }
    }

    fun movePreview(pageId: String, delta: Int) {
        mutable.update { s ->
            val list = s.preview.toMutableList()
            val from = list.indexOfFirst { it.pageId == pageId }
            val to = from + delta
            if (from >= 0 && to in list.indices) {
                val item = list.removeAt(from)
                list.add(to, item)
            }
            s.copy(preview = list)
        }
    }
}
