package com.example.notionreader.domain.discovery

import kotlin.math.sqrt

internal data class SubjectProjection(val center: FloatArray, val anchor: EmbeddedPassage)

internal fun addProjectionVector(target: FloatArray, source: FloatArray) { for (i in target.indices) target[i] += source[i] }
internal fun projectionDot(a: FloatArray, b: FloatArray): Float { var sum = 0f; for (i in a.indices) sum += a[i] * b[i]; return sum }
internal fun normalizeProjectionVector(vector: FloatArray) {
    var square = 0.0; for (v in vector) square += v.toDouble() * v
    val norm = sqrt(square).toFloat(); if (norm > 0f) for (i in vector.indices) vector[i] /= norm
}

/** Additional focused candidates are shared by formation evaluations and shipping publication. */
internal fun candidateSubjectGroups(sections: List<FormationOpportunity>, clusters: List<List<Int>>): List<List<Int>> {
    val generic = genericPublicationLabels + setOf("non-goals", "non goals", "explicitly deferred work", "out of scope")
    return clusters.filter { it.isNotEmpty() }.flatMap { cluster ->
        val focused = cluster.groupBy { index -> sections[index].passages.first().passage.sectionTitle?.trim()?.lowercase() }
            .filter { (heading, members) -> heading != null && heading !in generic && heading !in referenceHeadings && members.size < cluster.size }
            .toSortedMap(compareBy<String?> { it.orEmpty() }).values.toList()
        listOf(cluster) + focused
    }.distinctBy { it.sorted() }
}

internal fun prepareSubjectProjection(sections: List<FormationOpportunity>, indices: List<Int>,
    vectors: Map<String, FloatArray>, dimensions: Int): SubjectProjection {
    val center = FloatArray(dimensions)
    val candidates = indices.flatMap { sections[it].passages }
    candidates.forEach { addProjectionVector(center, vectors.getValue(it.passage.id)) }; normalizeProjectionVector(center)
    val anchor = candidates.maxWithOrNull(compareBy<EmbeddedPassage> { projectionDot(center, vectors.getValue(it.passage.id)) }
        .thenByDescending { it.passage.id })!!
    return SubjectProjection(center, anchor)
}

internal fun considerSubjectMembership(e: FormationEvidence, rawScore: Float, anchor: SemanticPassage,
    members: MutableMap<String, TopicMembership>, threshold: Float) {
    val score = rawScore.coerceIn(-1f, 1f)
    val page = canonicalSemanticId(e.record.pageId)
    val old = members[page]
    if (score >= threshold && (old == null || score > old.score || score == old.score && e.embedded.passage.id < old.passage.id))
        members[page] = TopicMembership(e.record.pageId, e.record.metadata, e.embedded.passage, score, anchor, e.record.revision)
}

internal fun nameSubjectCandidate(id: String, order: Long, projection: SubjectProjection, members: List<TopicMembership>): DiscoveredTopic {
    val source = members.firstOrNull { it.passage.id == projection.anchor.passage.id }?.passage ?: members.first().passage
    val label = source.sectionTitle?.trim()?.takeIf { it.isNotEmpty() } ?: "Discovered Topic"
    val base = DiscoveredTopic(id, label, source, projection.anchor, members, order, projection.center.toList())
    return sequenceOf(label, sharedSourcePhrase(source, members, minimumSupport = 1)).filterNotNull()
        .map { base.copy(label = it) }.firstOrNull(::topicEvidenceIsValid) ?: base
}
