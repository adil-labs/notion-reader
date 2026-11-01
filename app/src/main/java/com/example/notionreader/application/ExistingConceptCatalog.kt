package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface ExistingConceptCatalogResult {
    data class Ready(val concept: SupportConcept, val query: TopicQueryEmbedding) : ExistingConceptCatalogResult
    data object Unavailable : ExistingConceptCatalogResult
    data object Stale : ExistingConceptCatalogResult
}

internal suspend fun currentExistingConcept(topic: DiscoveredTopic, identity: EmbeddingIdentity, policy: TopicRetrievalPolicy,
    sources: Map<String, String>, retrievals: TopicRetrievalStore, current: () -> Boolean): ExistingConceptCatalogResult {
    currentCoroutineContext().ensureActive()
    if (!current()) return ExistingConceptCatalogResult.Stale
    if (topic.members.isEmpty() || topic.members.any { member ->
            val id = canonicalSemanticId(member.pageId)
            id.isBlank() || member.revision.isBlank() || sources[id] != member.revision
        }) return ExistingConceptCatalogResult.Stale
    val label = topic.currentGeneratedLabel ?: return ExistingConceptCatalogResult.Unavailable
    val text = topicConceptQuery(label) ?: return ExistingConceptCatalogResult.Unavailable
    val expected = TopicRetrievalQuery(topic.id, label.workKey, text, identity, policy)
    val saved = retrievals.read(topic.id)
    currentCoroutineContext().ensureActive()
    if (!current()) return ExistingConceptCatalogResult.Stale
    if (saved?.queryEmbedding?.query?.key != expected.key) return ExistingConceptCatalogResult.Unavailable
    return ExistingConceptCatalogResult.Ready(SupportConcept(topic.id, label.workKey, label.text, label.definition), saved.queryEmbedding)
}
