package com.example.notionreader.domain.discovery

import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
enum class SubjectRelationshipKind { ParentChild, Related }

/** Literal author evidence, independent of member overlap and embedding similarity. */
@Serializable
data class SubjectRelationship(
    val id: String,
    val parentId: String,
    val childId: String,
    val evidence: SemanticPassage,
    val revision: String,
    val kind: SubjectRelationshipKind = SubjectRelationshipKind.ParentChild
)

private fun subjectName(text: String) = text.trim().lowercase(Locale.ROOT)
private val relationshipSentence = Regex(
    "^(.+?) is (?:(?:a |an )?(subtopic|type|kind|part) of|related to) (.+?)[.!?]?$",
    RegexOption.IGNORE_CASE
)

private fun subjectIds(topics: List<DiscoveredTopic>): Map<String, String> =
    topics.filter(::topicEvidenceIsValid).groupBy { subjectName(it.label) }
        .mapNotNull { (name, matches) -> matches.singleOrNull()?.let { name to it.id } }.toMap()

private fun statedRelationships(byName: Map<String, String>, passage: SemanticPassage): List<Triple<String, String, SubjectRelationshipKind>> {
    if (byName.size < 2 || !passage.excerpt.contains(" is ", ignoreCase = true) || !passage.isBodyEvidence()) return emptyList()
    return passage.excerpt.split(Regex("(?<=[.!?])\\s+")).mapNotNull { sentence ->
        val match = relationshipSentence.matchEntire(sentence.trim()) ?: return@mapNotNull null
        val child = byName[subjectName(match.groupValues[1])] ?: return@mapNotNull null
        val parent = byName[subjectName(match.groupValues[3])] ?: return@mapNotNull null
        val kind = if (match.groupValues[2].isEmpty()) SubjectRelationshipKind.Related else SubjectRelationshipKind.ParentChild
        if (kind == SubjectRelationshipKind.Related && parent > child) Triple(child, parent, kind)
        else Triple(parent, child, kind)
    }
}

fun buildSubjectRelationships(topics: List<DiscoveredTopic>, records: List<SemanticRecord>,
    checkActive: () -> Unit = {}): List<SubjectRelationship> {
    val edges = mutableListOf<SubjectRelationship>()
    val names = subjectIds(topics)
    val sources = records.groupBy { canonicalSemanticId(it.pageId) }.values.mapNotNull { it.singleOrNull() }
        .sortedBy { canonicalSemanticId(it.pageId) }
    for (record in sources) for (embedded in record.passages.sortedWith(compareBy<EmbeddedPassage> { it.passage.order }.thenBy { it.passage.id })) {
        checkActive()
        val passage = embedded.passage
        if (canonicalSemanticId(passage.pageId) != canonicalSemanticId(record.pageId)) continue
        for ((parent, child, kind) in statedRelationships(names, passage)) {
            val id = semanticHash("$parent|$child|$kind")
            edges += SubjectRelationship(id, parent, child, passage, record.revision, kind)
        }
    }
    return validatedSubjectRelationships(topics, edges, sources.associate { canonicalSemanticId(it.pageId) to it.revision }, checkActive)
}

/** Revalidates source eligibility and graph structure before publication or after removal. */
fun validatedSubjectRelationships(topics: List<DiscoveredTopic>, relationships: List<SubjectRelationship>,
    revisions: Map<String, String>, checkActive: () -> Unit = {}): List<SubjectRelationship> {
    val accepted = linkedMapOf<String, SubjectRelationship>()
    if (relationships.isEmpty()) return emptyList()
    val names = subjectIds(topics)
    val children = mutableMapOf<String, MutableSet<String>>()
    fun reaches(from: String, target: String): Boolean {
        val seen = mutableSetOf<String>()
        val pending = ArrayDeque<String>(); pending.add(from)
        while (pending.isNotEmpty()) {
            checkActive()
            val next = pending.removeLast()
            if (next == target) return true
            if (seen.add(next)) children[next].orEmpty().forEach(pending::add)
        }
        return false
    }
    for (edge in relationships) {
        checkActive()
        if (edge.parentId == edge.childId || edge.id != semanticHash("${edge.parentId}|${edge.childId}|${edge.kind}") ||
            revisions[canonicalSemanticId(edge.evidence.pageId)] != edge.revision ||
            Triple(edge.parentId, edge.childId, edge.kind) !in statedRelationships(names, edge.evidence)) continue
        if (edge.id in accepted) continue
        if (edge.kind == SubjectRelationshipKind.ParentChild) {
            if (reaches(edge.childId, edge.parentId)) continue
            children.getOrPut(edge.parentId) { mutableSetOf() }.add(edge.childId)
        }
        accepted[edge.id] = edge
    }
    return accepted.values.toList()
}
