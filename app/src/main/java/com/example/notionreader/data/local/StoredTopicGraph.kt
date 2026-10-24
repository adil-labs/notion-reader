package com.example.notionreader.data.local

import com.example.notionreader.domain.discovery.*
import kotlinx.serialization.Serializable

/** Lossless on-disk references keep overlapping topics from duplicating private source text. */
@Serializable
internal data class StoredTopicMember(val pageId: String, val metadata: SemanticMetadata,
    val passage: Int, val score: Float, val anchor: Int, val revision: String)

@Serializable
internal data class StoredTopic(val id: String, val label: String, val labelSource: Int,
    val anchor: Int, val anchorVector: List<Float>, val members: List<StoredTopicMember>,
    val createdOrder: Long, val subjectVector: List<Float>)

@Serializable
internal data class StoredTopicGraph(val passages: List<SemanticPassage>, val topics: List<StoredTopic>,
    val provisional: List<StoredTopic>, val projectionMembers: List<StoredTopicMember>) {
    fun restore(base: TopicSnapshot): TopicSnapshot {
        require(base.topics.isEmpty() && base.provisionalTopics.isEmpty() &&
            base.formationProgress?.projectionMembers.orEmpty().isEmpty())
        require(base.formationProgress != null || projectionMembers.isEmpty())
        fun member(value: StoredTopicMember) = TopicMembership(value.pageId, value.metadata,
            passages[value.passage], value.score, passages[value.anchor], value.revision)
        fun topic(value: StoredTopic) = DiscoveredTopic(value.id, value.label, passages[value.labelSource],
            EmbeddedPassage(passages[value.anchor], value.anchorVector), value.members.map(::member),
            value.createdOrder, value.subjectVector)
        return base.copy(topics = topics.map(::topic), provisionalTopics = provisional.map(::topic),
            formationProgress = base.formationProgress?.copy(projectionMembers = projectionMembers.map(::member)))
    }

    companion object {
        fun separate(snapshot: TopicSnapshot, checkActive: () -> Unit = {}): Pair<TopicSnapshot, StoredTopicGraph> {
            val references = linkedMapOf<SemanticPassage, Int>()
            var visited = 0
            fun ref(passage: SemanticPassage): Int {
                if (visited++ % 64 == 0) checkActive()
                return references.getOrPut(passage) { references.size }
            }
            fun member(value: TopicMembership) = StoredTopicMember(value.pageId, value.metadata,
                ref(value.passage), value.score, ref(value.anchor), value.revision)
            fun topic(value: DiscoveredTopic) = StoredTopic(value.id, value.label, ref(value.labelSource),
                ref(value.anchor.passage), value.anchor.vector, value.members.map(::member), value.createdOrder,
                value.subjectVector)
            val topics = snapshot.topics.map(::topic)
            val provisional = snapshot.provisionalTopics.map(::topic)
            val projection = snapshot.formationProgress?.projectionMembers.orEmpty().map(::member)
            return snapshot.copy(topics = emptyList(), provisionalTopics = emptyList(),
                formationProgress = snapshot.formationProgress?.copy(projectionMembers = emptyList())) to
                StoredTopicGraph(references.keys.toList(), topics, provisional, projection)
        }
    }
}
