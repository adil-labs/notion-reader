package com.example.notionreader.domain.discovery

import kotlin.math.sqrt

/** Display-only policy: candidate persistence and opaque continuity retain every input topic. */
const val TOPIC_PUBLICATION_POLICY_VERSION = 3

enum class TopicPublicationStatus { Established, Emerging }

fun discoveryTopicPublicationStatus(topic: DiscoveredTopic): TopicPublicationStatus? {
    if (!hasPublishedSubject(topic)) return null
    return if (topic.members.map { canonicalSemanticId(it.pageId) }.distinct().size == 1)
        TopicPublicationStatus.Emerging else TopicPublicationStatus.Established
}

fun publishableDiscoveryTopics(topics: List<DiscoveredTopic>): List<DiscoveredTopic> {
    val kept = mutableListOf<DiscoveredTopic>()
    topics.sortedWith(compareBy<DiscoveredTopic> { it.createdOrder }.thenBy { it.id })
        .filter(::hasPublishedSubject).forEach { candidate ->
            if (kept.none { prior ->
                if (prior.experimentalEvidenceValidated || candidate.experimentalEvidenceValidated)
                    sameExperimentalSubject(prior, candidate)
                else sameMemberFamily(prior, candidate) && !independentThemes(prior, candidate)
            }) kept += candidate
        }
    return kept
}

private val genericPublicationLabels = setOf("notes", "summary", "introduction", "overview", "untitled", "details", "content")
private val phraseEdges = setOf("the", "a", "an", "and", "or", "to", "of", "in", "is", "are", "be", "it", "this", "that",
    "with", "as", "for", "from", "on", "by", "at", "not", "must", "should", "will", "may", "can", "have", "has",
    "when", "then", "which", "its", "i", "you", "we", "they", "he", "she", "my", "your", "our", "their",
    "no", "never", "could", "would", "shall", "does", "did", "was", "were", "been", "being")
private val publicationWords = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*")
private fun literalIn(text: String, label: String): Boolean =
    Regex("(?<![\\p{L}\\p{N}])${Regex.escape(label)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(text)

private fun hasSubjectLabel(topic: DiscoveredTopic): Boolean {
    val label = topic.label.trim()
    if (label.length !in 3..100 || label.lowercase() in genericPublicationLabels ||
        label.lowercase() in referenceHeadings || label.startsWith("Notes related to ", true)) return false
    val tokens = publicationWords.findAll(label).map { it.value.lowercase() }.toList()
    if (tokens.isEmpty() || tokens.first() in phraseEdges || tokens.last() in phraseEdges) return false
    // A heading is complete as authored; a phrase must have meaningful, bounded word edges.
    if (!supportsLabel(topic.labelSource, label)) return false
    val members = topic.members.distinctBy { canonicalSemanticId(it.pageId) }
    val titlePrefix = Regex("^${Regex.escape(label)}\\s*(?:[—–:|]|\\s-\\s)\\s*\\S", RegexOption.IGNORE_CASE)
    val familyCount = members.count { titlePrefix.containsMatchIn(it.metadata.title.trim()) }
    val headingCount = members.count { it.passage.isBodyEvidence() && it.passage.sectionTitle?.trim()?.equals(label, true) == true }
    if (familyCount >= 3 && familyCount.toDouble() / members.size >= .6 && headingCount < 2) return false
    return true
}

private fun supportsLabel(passage: SemanticPassage, label: String): Boolean =
    passage.sectionTitle?.trim()?.equals(label, true) == true || literalIn(passage.excerpt, label)

/** Recheck persisted evidence; the experimental marker alone is never a publication decision. */
internal fun experimentalTopicEvidenceIsValid(topic: DiscoveredTopic): Boolean =
    hasSubjectLabel(topic) && topic.members.isNotEmpty() && topic.labelSource.isBodyEvidence() &&
        topic.members.any { it.passage == topic.labelSource } &&
        topic.members.all { member ->
            canonicalSemanticId(member.pageId) == canonicalSemanticId(member.passage.pageId) &&
                member.passage.isBodyEvidence() && supportsLabel(member.passage, topic.label.trim())
        }

private fun hasPublishedSubject(topic: DiscoveredTopic): Boolean {
    if (topic.experimentalEvidenceValidated) return experimentalTopicEvidenceIsValid(topic)
    if (!hasSubjectLabel(topic)) return false
    return topic.members.filter { it.passage.isBodyEvidence() && supportsLabel(it.passage, topic.label.trim()) }
        .map { canonicalSemanticId(it.pageId) }.distinct().size >= 2
}

private fun sameExperimentalSubject(a: DiscoveredTopic, b: DiscoveredTopic): Boolean {
    if (!sameMemberFamily(a, b)) return false
    val right = membersByPage(b)
    return membersByPage(a).all { (page, member) ->
        right[page]?.let { other -> member.passage.id == other.passage.id ||
            nearDuplicateDiscoveryPassages(member.passage, other.passage) } == true
    }
}

private fun membersByPage(topic: DiscoveredTopic) = topic.members.associateBy { canonicalSemanticId(it.pageId) }
private fun sameMemberFamily(a: DiscoveredTopic, b: DiscoveredTopic): Boolean {
    val left = membersByPage(a).keys
    val right = membersByPage(b).keys
    val union = left + right
    // Three shared Notes out of four is the smallest near-identical supported family.
    return union.isNotEmpty() && left.intersect(right).size.toDouble() / union.size >= .75
}
private fun independentThemes(a: DiscoveredTopic, b: DiscoveredTopic): Boolean {
    if (a.label.trim().equals(b.label.trim(), true)) return false
    val x = a.anchor.vector
    val y = b.anchor.vector
    if (x.isEmpty() || x.size != y.size || x.any { !it.isFinite() } || y.any { !it.isFinite() }) return false
    val xx = x.sumOf { it.toDouble() * it }
    val yy = y.sumOf { it.toDouble() * it }
    if (xx == 0.0 || yy == 0.0) return false
    val cosine = x.indices.sumOf { x[it].toDouble() * y[it] } / sqrt(xx * yy)
    if (cosine >= TopicPolicy().separation) return false
    val right = membersByPage(b)
    return membersByPage(a).count { (page, member) ->
        val other = right[page]?.passage ?: return@count false
        val passage = member.passage
        passage.id != other.id && (passage.sourceBlockId != other.sourceBlockId ||
            passage.end <= other.start || other.end <= passage.start) &&
            passage.isBodyEvidence() && other.isBodyEvidence() && !nearDuplicateDiscoveryPassages(passage, other)
    } >= 2
}
