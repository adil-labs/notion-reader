package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Reconstruct source-backed presentation; missing or changed evidence never becomes a match. */
internal suspend fun hydrateCanonicalTopic(topic: DiscoveredTopic, model: EmbeddingIdentity,
    sources: Map<String, String>, index: SemanticIndexStore, store: ProposalProgressStore,
    current: () -> Boolean): DiscoveredTopic? {
    val concept = topic.canonicalConcept ?: return null
    if (!current() || concept.proposalKey.isBlank() || concept.offered.isEmpty() ||
        concept.offered.any { sources[it.pageId] != it.revision }) return null
    val records = linkedMapOf<String, SemanticRecord>()
    suspend fun resolve(ref: ProposalEvidenceRef): Pair<SemanticRecord, SemanticPassage>? {
        currentCoroutineContext().ensureActive()
        if (!current() || sources[ref.pageId] != ref.revision) return null
        val record = records[ref.pageId] ?: index.read(ref.pageId)?.also { records[ref.pageId] = it } ?: return null
        if (!current() || record.identity != model || record.revision != ref.revision ||
            canonicalSemanticId(record.pageId) != ref.pageId) return null
        val passage = record.passages.map { it.passage }.singleOrNull { it.id == ref.passageId && it.isBodyEvidence() &&
            canonicalSemanticId(it.pageId) == ref.pageId } ?: return null
        return record to passage
    }
    val offered = ArrayList<TopicLabelEvidence>()
    for (ref in concept.offered) {
        val (_, passage) = resolve(ref) ?: return null
        offered += TopicLabelEvidence(ref.passageId, ref.pageId, ref.revision, passage.excerpt)
    }
    val request = DiscoveryProposalRequest(concept.proposalKey, 0, offered, concept.generator)
    val response = DiscoveryProposalResponse.Concept(concept.name, concept.definition, concept.listeningDescription,
        concept.citations.map { it.evidence.passageId })
    val checked = validateDiscoveryProposal(request, response, 0, sources)
    if (checked !is DiscoveryProposalValidation.Accepted || checked.workKey != concept.workKey) return null
    val citations = mutableListOf<TopicMembership>()
    for (saved in concept.citations) {
        val (record, passage) = resolve(saved.evidence) ?: return null
        val evidence = TopicLabelEvidence(passage.id, saved.evidence.pageId, saved.evidence.revision, passage.excerpt)
        val support = TopicSupportRequest("pending:${concept.workKey}", concept.workKey, concept.name,
            concept.definition, evidence, concept.verifier, 0)
        val validation = validateTopicSupport(support, SupportDecision.SUPPORTED, 0, concept.workKey, sources)
        if (validation !is TopicSupportValidation.Accepted || validation.assessment != saved) return null
        citations += TopicMembership(saved.evidence.pageId, record.metadata, passage, 0f, passage, record.revision)
    }
    val members = run {
        val result = mutableListOf<TopicMembership>()
        for (ref in concept.supported) {
            if (concept.scannedRevisions[ref.pageId] != ref.revision) return null
            val (record, passage) = resolve(ref) ?: return null
            val evidence = TopicLabelEvidence(passage.id, ref.pageId, ref.revision, passage.excerpt)
            var verified = false
            // A canonical-ID assessment supersedes a pre-admission assessment for the same passage.
            for (id in listOf(topic.id, "pending:${concept.workKey}")) {
                val support = TopicSupportRequest(id, concept.workKey, concept.name,
                    concept.definition, evidence, concept.verifier, 0)
                val expected = validateTopicSupport(support, SupportDecision.SUPPORTED, 0, concept.workKey, sources)
                if (expected !is TopicSupportValidation.Accepted) return null
                val saved = store.readSupport(expected.assessment.workKey, sources)
                if (!current()) return null
                if (saved != null) {
                    if (saved != expected.assessment) return null
                    verified = true
                    break
                }
            }
            if (!verified) return null
            result += TopicMembership(ref.pageId, record.metadata, passage, 0f, passage, record.revision)
        }
        result.distinctBy { it.pageId }
    }
    for ((id, record) in records) if (!current() || index.read(id) != record) return null
    if (!current()) return null
    return topic.copy(canonicalProjection = CanonicalTopicProjection(concept.workKey, concept.name,
        concept.definition, concept.listeningDescription, citations, members))
}
