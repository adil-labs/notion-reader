package com.example.notionreader.domain.discovery

import java.text.Normalizer
import java.util.Locale

const val CONNECTION_EVIDENCE_POLICY_VERSION = 2

enum class ConnectionEvidenceKind { NamedReference, SimilarPassages }
data class NamedNoteReference(val sourcePageId: String, val targetPageId: String)
data class ConnectionEvidence(val references: List<NamedNoteReference> = emptyList()) {
    val kind: ConnectionEvidenceKind get() = if (references.isEmpty()) ConnectionEvidenceKind.SimilarPassages else ConnectionEvidenceKind.NamedReference
}

private fun referenceText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
    .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

private fun explicitTitlePattern(title: String): Regex {
    val instruction = "(?:^|[.!?;,:])\\s*(?:please\\s+|you (?:can|may)\\s+)?(?:open|read|see|consult|refer to|go to)\\s+(?:(?:the\\s+)?note\\s+)?"
    val citation = "(?:\\bas (?:described|discussed|explained) in|\\b(?:the\\s+)?note (?:titled|named|called))\\s+"
    val quotedTitle = "[\\\"“‘'`]?${Regex.escape(title)}[\\\"”’'`]?\\s*(?=$|[.!?;:,—–])"
    return Regex("(?:$instruction|$citation)$quotedTitle")
}

private fun explicitlyNamesTitle(text: String, title: String): Boolean =
    explicitTitlePattern(title).containsMatchIn(referenceText(text))

/** Classifies existing eligible evidence; never infers a verified hyperlink or creates a pair. */
fun classifyConnectionEvidence(connection: NoteConnection, cachedTitles: Map<String, String>): ConnectionEvidence {
    fun normalized(text: String): String = referenceText(text)
    val titles = cachedTitles.entries.groupBy { canonicalSemanticId(it.key) }.mapNotNull { (id, aliases) ->
        val names = aliases.map { normalized(it.value) }.distinct()
        names.singleOrNull()?.takeIf { it.isNotEmpty() }?.let { id to it }
    }.toMap()
    fun reference(source: TopicMembership, target: TopicMembership): NamedNoteReference? {
        val sourceId = canonicalSemanticId(source.pageId)
        val targetId = canonicalSemanticId(target.pageId)
        val title = normalized(target.metadata.title)
        val sourceEligible = if (connection.independentNamedReference) source.passage.sourceBlockId != source.passage.sectionId
            else source.passage.isBodyEvidence()
        val targetEligible = if (connection.independentNamedReference) target.passage.excerpt.isNotBlank()
            else target.passage.isBodyEvidence()
        if (sourceId == targetId || !sourceEligible || !targetEligible ||
            titles[sourceId] != normalized(source.metadata.title) || titles[targetId] != title ||
            titles.values.count { it == title } != 1) return null
        return if (explicitlyNamesTitle(source.passage.excerpt, title))
            NamedNoteReference(sourceId, targetId) else null
    }
    return ConnectionEvidence(listOfNotNull(reference(connection.left, connection.right), reference(connection.right, connection.left))
        .sortedWith(compareBy<NamedNoteReference> { it.sourcePageId }.thenBy { it.targetPageId }))
}

/** Explicit retrieval is separate from thematic admission. Call only for the experimental engine. */
fun discoverExplicitNoteReferences(records: List<SemanticRecord>, checkActive: () -> Unit = {}): List<NoteConnection> {
    checkActive()
    val current = records.groupBy { canonicalSemanticId(it.pageId) }.values.mapNotNull { it.singleOrNull() }
        .sortedBy { canonicalSemanticId(it.pageId) }
    val titleCounts = current.groupingBy { referenceText(it.metadata.title) }.eachCount()
    val targets = current.filter { referenceText(it.metadata.title).isNotBlank() && titleCounts[referenceText(it.metadata.title)] == 1 }
        .mapNotNull { record ->
            val passages = record.passages.map { it.passage }.filter { it.excerpt.isNotBlank() }
                .sortedWith(compareBy<SemanticPassage> { it.sourceBlockId == it.sectionId }.thenBy { it.order }.thenBy { it.id })
            passages.firstOrNull()?.let { Triple(record, explicitTitlePattern(referenceText(record.metadata.title)), it) }
        }
    val pairs = linkedMapOf<String, NoteConnection>()
    for (source in current) {
        checkActive()
        val sourceId = canonicalSemanticId(source.pageId)
        for (passage in source.passages.map { it.passage }
            .filter { it.sourceBlockId != it.sectionId && it.excerpt.isNotBlank() }
            .sortedWith(compareBy<SemanticPassage> { it.order }.thenBy { it.id })) {
            val text = referenceText(passage.excerpt)
            for ((target, pattern, context) in targets) {
                checkActive()
                val targetId = canonicalSemanticId(target.pageId)
                if (sourceId == targetId || !pattern.containsMatchIn(text)) continue
                val members = listOf(
                    TopicMembership(sourceId, source.metadata, passage, 0f, context, source.revision),
                    TopicMembership(targetId, target.metadata, context, 0f, passage, target.revision)
                ).sortedBy { it.pageId }
                val id = semanticHash(members.joinToString("|") { it.pageId })
                pairs.putIfAbsent(id, NoteConnection(id, members[0], members[1], 0f, independentNamedReference = true))
            }
        }
    }
    return pairs.values.toList()
}
