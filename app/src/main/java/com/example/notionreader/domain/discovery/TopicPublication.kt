package com.example.notionreader.domain.discovery


/** Display-only policy: candidate persistence and opaque continuity retain every input topic. */
enum class TopicPublicationStatus { Established, Emerging }

fun discoveryTopicPublicationStatus(topic: DiscoveredTopic): TopicPublicationStatus? {
    if (!hasPublishedSubject(topic)) return null
    return if (topic.members.map { canonicalSemanticId(it.pageId) }.distinct().size == 1)
        TopicPublicationStatus.Emerging else TopicPublicationStatus.Established
}

fun publishableDiscoveryTopics(topics: List<DiscoveredTopic>): List<DiscoveredTopic> =
    publishableDiscoveryTopics(topics, ::prepareDiscoveryPassageOverlap)

internal fun publishableDiscoveryTopics(topics: List<DiscoveredTopic>,
    prepareOverlap: (SemanticPassage) -> DiscoveryPassageOverlap): List<DiscoveredTopic> {
    val kept = mutableListOf<DiscoveredTopic>()
    val evidence = PublicationEvidence(prepareOverlap)
    topics.sortedWith(compareBy<DiscoveredTopic> { it.createdOrder }.thenBy { it.id })
        .filter(::hasPublishedSubject).forEach { candidate ->
            if (kept.none { prior ->
                sameSubjectEvidence(prior, candidate, evidence)
            }) kept += candidate
        }
    return kept
}

internal val genericPublicationLabels = setOf("notes", "summary", "introduction", "overview", "untitled", "details", "content",
    "body", "abstract", "main text", "full text")
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

/** Labels are source-backed; semantic membership is validated independently of label wording. */
internal fun topicEvidenceIsValid(topic: DiscoveredTopic): Boolean =
    hasSubjectLabel(topic) && topic.members.isNotEmpty() && topic.labelSource.isBodyEvidence() &&
        topic.members.any { it.passage == topic.labelSource } &&
        topic.members.all { member ->
            canonicalSemanticId(member.pageId) == canonicalSemanticId(member.passage.pageId) &&
                member.passage.isBodyEvidence() && member.score.isFinite() && member.score >= TopicPolicy().retention
        }

private fun hasPublishedSubject(topic: DiscoveredTopic): Boolean = topicEvidenceIsValid(topic)

private class PublicationEvidence(private val prepareOverlap: (SemanticPassage) -> DiscoveryPassageOverlap) {
    private val pages = java.util.IdentityHashMap<DiscoveredTopic, Map<String, TopicMembership>>()
    private val overlaps = mutableMapOf<String, DiscoveryPassageOverlap>()
    fun members(topic: DiscoveredTopic): Map<String, TopicMembership> = pages.getOrPut(topic) {
        topic.members.associateBy { canonicalSemanticId(it.pageId) }
    }
    fun overlap(passage: SemanticPassage): DiscoveryPassageOverlap = overlaps.getOrPut(passage.excerpt) {
        prepareOverlap(passage)
    }
}

private fun sameSubjectEvidence(a: DiscoveredTopic, b: DiscoveredTopic, evidence: PublicationEvidence): Boolean {
    if (!sameMemberFamily(a, b, evidence)) return false
    return !independentThemes(a, b, evidence)
}

private fun sameMemberFamily(a: DiscoveredTopic, b: DiscoveredTopic, evidence: PublicationEvidence): Boolean {
    val left = evidence.members(a).keys
    val right = evidence.members(b).keys
    val union = left + right
    // Three shared Notes out of four is the smallest near-identical supported family.
    return union.isNotEmpty() && left.intersect(right).size.toDouble() / union.size >= .75
}
private fun independentThemes(a: DiscoveredTopic, b: DiscoveredTopic, evidence: PublicationEvidence): Boolean {
    val right = evidence.members(b)
    return evidence.members(a).count { (page, member) ->
        val other = right[page]?.passage ?: return@count false
        val passage = member.passage
        passage.id != other.id && (passage.sourceBlockId != other.sourceBlockId ||
            passage.end <= other.start || other.end <= passage.start) &&
            // Both topics already passed full member/body validation before pair comparison.
            !evidence.overlap(passage).nearDuplicate(evidence.overlap(other))
    } >= minOf(2, evidence.members(a).size, evidence.members(b).size)
}
