package com.example.notionreader.domain.discovery

import java.security.MessageDigest

object TopicLabelEvidencePolicy {
    const val version = 1
    const val maximumPassages = 4
}

/** Incremental hashing avoids materializing every member's body into another corpus-sized string. */
fun topicLabelFingerprint(topic: DiscoveredTopic): String = topic.labelEvidenceFingerprint

internal fun computeTopicLabelFingerprint(topic: DiscoveredTopic): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun field(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(':'.code.toByte()); digest.update(bytes)
    }
    field("group-label-evidence-v1"); field(topic.id)
    val members = topic.members.sortedWith(compareBy<TopicMembership> { canonicalSemanticId(it.pageId) }.thenBy { it.passage.id })
    field(members.size.toString())
    for (member in members) {
        field(canonicalSemanticId(member.pageId)); field(member.revision)
        field(member.passage.id); field(member.passage.sourceBlockId)
        field(member.passage.start.toString()); field(member.passage.end.toString()); field(member.passage.excerpt)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun labelMembers(topic: DiscoveredTopic): List<TopicMembership>? {
    if (topic.members.isEmpty() || topic.members.any { it.revision.isBlank() || !it.passage.isBodyEvidence() } ||
        topic.members.map { it.passage.id }.toSet().size != topic.members.size) return null
    return topic.members.sortedWith(compareBy<TopicMembership> { canonicalSemanticId(it.pageId) }.thenBy { it.passage.id })
}
private fun evidence(member: TopicMembership) = TopicLabelEvidence(member.passage.id,
    canonicalSemanticId(member.pageId), member.revision, member.passage.excerpt)

/** Bounded naming evidence; this never changes the group's canonical membership. */
fun topicLabelRequest(topic: DiscoveredTopic, identity: TopicLabelIdentity, sourceEpoch: Long,
    evidenceLimit: Int = TopicLabelEvidencePolicy.maximumPassages): TopicLabelRequest? {
    require(evidenceLimit in 1..TopicLabelEvidencePolicy.maximumPassages)
    val members = labelMembers(topic) ?: return null
    return TopicLabelRequest(topic.id, topicLabelFingerprint(topic), sourceEpoch,
        members.take(evidenceLimit).map(::evidence), identity)
}

/** Reconstruct the offered evidence from current bodies; never trust a saved fingerprint alone. */
fun reusableTopicLabel(topic: DiscoveredTopic, saved: GeneratedTopicLabel?, identity: TopicLabelIdentity): GeneratedTopicLabel? {
    saved ?: return null
    if (saved.topicId != topic.id || saved.identity != identity || saved.evidenceIds.size !in 1..TopicLabelEvidencePolicy.maximumPassages ||
        saved.evidenceIds.toSet().size != saved.evidenceIds.size) return null
    val fingerprint = topicLabelFingerprint(topic)
    if (saved.groupFingerprint != fingerprint) return null
    val members = labelMembers(topic)?.associateBy { it.passage.id } ?: return null
    val offered = saved.evidenceIds.map { members[it]?.let(::evidence) ?: return null }
    val request = TopicLabelRequest(topic.id, fingerprint, 0, offered, identity)
    if (saved.workKey != topicLabelWorkKey(request)) return null
    val valid = validateTopicLabel(request, saved.text, saved.evidenceIds, fingerprint, 0, saved.definition)
    return (valid as? TopicLabelValidation.Accepted)?.label
}
