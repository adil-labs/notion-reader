package com.example.notionreader.domain.discovery

import kotlin.math.sqrt

/** Mandatory source removal also applies to pinned snapshots. No removed text survives in continuity. */
fun invalidateTopics(snapshot: TopicSnapshot, pageIds: Set<String>, permanent: Boolean,
    replacements: Map<String, EmbeddedPassage> = emptyMap(), retentionThreshold: Float = TopicPolicy().retention): TopicSnapshot {
    val removed = pageIds.map(::canonicalSemanticId).toSet()
    if (removed.isEmpty()) return snapshot
    val affected = (snapshot.topics + snapshot.provisionalTopics).any { t -> t.members.any { canonicalSemanticId(it.pageId) in removed } || canonicalSemanticId(t.anchor.passage.pageId) in removed || canonicalSemanticId(t.labelSource.pageId) in removed } ||
        snapshot.connections.any { canonicalSemanticId(it.left.pageId) in removed || canonicalSemanticId(it.right.pageId) in removed } ||
        snapshot.subjectRelationships.any { canonicalSemanticId(it.evidence.pageId) in removed } ||
        permanent && snapshot.continuity.any { c -> c.pageIds.any { canonicalSemanticId(it) in removed } } ||
        snapshot.acceptedRevisions.keys.any { it in removed } || snapshot.referenceTitles.keys.any { canonicalSemanticId(it) in removed }
    if (!affected) return snapshot
    fun available(id: String) = canonicalSemanticId(id) !in removed
    val survivingSources = (snapshot.topics + snapshot.provisionalTopics).associate { topic ->
        topic.id to topic.members.filter { available(it.pageId) }.map { topicEvidenceIdentity(it.passage) }.toSet()
    }
    val registry = snapshot.continuity.mapNotNull { continuity ->
        val pages = if (permanent) continuity.pageIds.filter(::available).toSet() else continuity.pageIds
        val sources = if (permanent && continuity.pageIds.any { !available(it) })
            continuity.sourceIds.intersect(survivingSources[continuity.id].orEmpty()) else continuity.sourceIds
        if (pages.isEmpty()) null else continuity.copy(pageIds = pages, sourceIds = sources,
            anchorSourceId = continuity.anchorSourceId?.takeIf { it in sources })
    }
    fun removeEvidence(input: List<DiscoveredTopic>): List<DiscoveredTopic> = input.mapNotNull { topic ->
        val members = topic.members.filter { available(it.pageId) }
        val required = 1
        if (members.size < required) null
        else if (available(topic.anchor.passage.pageId) && available(topic.labelSource.pageId)) topic.copy(members = members)
        else {
            val replacement = replacements[topic.id]
            val member = members.find { it.passage == replacement?.passage }
            if (replacement == null || member == null || replacement.vector.size != snapshot.identity?.dimensions ||
                replacement.vector.any { !it.isFinite() } || replacement.vector.none { it != 0f }) null
            else {
                val similarity = directCosine(topic.subjectVector, replacement.vector)
                val rescored = members.map { it.copy(anchor = replacement.passage, score = if (it.passage.id == replacement.passage.id) 1f else cosineLowerBound(it.score, similarity)) }
                    .filter { it.score >= retentionThreshold }
                if (rescored.size < required) null else topic.copy(anchor = replacement, subjectVector = replacement.vector, labelSource = replacement.passage,
                    label = replacement.passage.sectionTitle ?: supportedHeading(replacement.passage.sectionTitle, rescored) ?: sharedSourcePhrase(replacement.passage, rescored)
                        ?: "Notes related to ${member.metadata.title}", members = rescored)
            }
        }
    }
    val provisional = removeEvidence(snapshot.provisionalTopics)
    val topics = removeEvidence(snapshot.topics)
    val revisions = snapshot.acceptedRevisions.filterKeys(::available)
    return snapshot.copy(topics = topics, continuity = registry,
        provisionalTopics = provisional,
        connections = snapshot.connections.filter { available(it.left.pageId) && available(it.right.pageId) },
        generation = snapshot.generation + 1, acceptedRevisions = revisions,
        referenceTitles = snapshot.referenceTitles.filterKeys(::available),
        subjectRelationships = validatedSubjectRelationships(publishableDiscoveryTopics(topics),
            snapshot.subjectRelationships.filter { available(it.evidence.pageId) }, revisions),
        formationProgress = null)
}

/** Safe minimum direct cosine when only two similarities to a shared anchor are known. */
private fun cosineLowerBound(left: Float, right: Float): Float {
    val a = left.toDouble().coerceIn(-1.0, 1.0); val b = right.toDouble().coerceIn(-1.0, 1.0)
    return (a * b - sqrt(maxOf(0.0, (1 - a * a) * (1 - b * b)))).toFloat().coerceIn(-1f, 1f)
}
private fun directCosine(a: List<Float>, b: List<Float>): Float {
    var dot = 0.0; var aa = 0.0; var bb = 0.0
    for (i in a.indices) { dot += a[i].toDouble() * b[i]; aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i] }
    return (dot / sqrt(aa * bb)).toFloat().coerceIn(-1f, 1f)
}
private fun supportedHeading(heading: String?, members: List<TopicMembership>): String? {
    val literal = heading?.trim()?.takeIf { it.length in 3..100 && it.lowercase() !in setOf("notes", "summary", "introduction", "overview", "untitled", "details", "content") && it.lowercase() !in referenceHeadings } ?: return null
    if (Regex("^(i|you|we|they|he|she|my|your|our|their)\\b", RegexOption.IGNORE_CASE).containsMatchIn(literal)) return null
    val support = members.filter { member -> sequenceOf(member.metadata.title, member.passage.sectionTitle.orEmpty(), member.passage.excerpt)
        .any { it.contains(literal, ignoreCase = true) } }.map { canonicalSemanticId(it.pageId) }.distinct().size
    return literal.takeIf { support >= 2 }
}

/** Bounded literal n-grams from one source, supported by at least two distinct Notes. */
internal fun sharedSourcePhrase(source: SemanticPassage, members: List<TopicMembership>, minimumSupport: Int = 2, requireAll: Boolean = false): String? {
    val stops = setOf("the", "a", "an", "and", "or", "to", "of", "in", "is", "are", "be", "it", "this", "that",
        "with", "as", "for", "from", "on", "by", "at", "not", "never", "must", "should", "will", "may", "can", "have", "has", "when", "then", "all", "only", "which", "its", "i", "you", "we", "they", "he", "she", "my", "your", "our", "their", "one", "someone", "something", "need", "needs", "feel", "feels", "seem", "seems")
    val words = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*").findAll(source.excerpt).take(48).toList()
    val distinct = members.distinctBy { canonicalSemanticId(it.pageId) }
    val samples = if (requireAll) distinct else distinct.take(12)
    data class Phrase(val text: String, val support: Int, val content: Int, val position: Int)
    val candidates = mutableListOf<Phrase>()
    for (start in words.indices) for (length in 2..5) {
        if (start + length > words.size) continue
        val selected = words.subList(start, start + length)
        if (selected.first().value.lowercase() in stops || selected.last().value.lowercase() in stops) continue
        val content = selected.count { it.value.lowercase() !in stops }
        if (content < 2) continue
        val literal = source.excerpt.substring(selected.first().range.first, selected.last().range.last + 1)
        if (literal.length > 64 || literal.any { it == '\n' || it == '.' || it == ';' }) continue
        if (selected.any { it.value.lowercase() in setOf("i", "you", "we", "they", "he", "she", "one", "someone", "something") }) continue
        val support = samples.count { it.passage.excerpt.contains(literal, ignoreCase = true) ||
            it.passage.sectionTitle.orEmpty().contains(literal, ignoreCase = true) }
        if (support >= if (requireAll) samples.size else minimumSupport) candidates += Phrase(literal, support, content, start)
    }
    return candidates.sortedWith(compareByDescending<Phrase> { it.support * minOf(3, it.content) }
        .thenByDescending { minOf(3, it.content) }.thenBy { it.text.length }.thenBy { it.position }).firstOrNull()?.text
}

/** Opaque identity ties continuity to actual source evidence, not just stable span IDs. */
internal fun topicEvidenceIdentity(passage: SemanticPassage): String =
    semanticHash(passage.id + "\u0000" + passage.input + "\u0000" + passage.excerpt)
