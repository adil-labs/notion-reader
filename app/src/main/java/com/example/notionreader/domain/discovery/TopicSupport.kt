package com.example.notionreader.domain.discovery

import kotlinx.serialization.Serializable

@Serializable enum class SupportDecision { SUPPORTED, UNSUPPORTED, UNCERTAIN }

data class TopicSupportRequest(val topicId: String, val conceptWorkKey: String, val name: String,
    val definition: String, val evidence: TopicLabelEvidence, val verifier: TopicLabelIdentity, val sourceEpoch: Long)

sealed interface TopicSupportRun {
    data class Completed(val decision: SupportDecision) : TopicSupportRun
    data object Cancelled : TopicSupportRun
    data object Unavailable : TopicSupportRun
    data class Failed(val reason: TopicLabelRuntimeFailure) : TopicSupportRun
}

interface TopicSupportRuntime {
    suspend fun verifySupport(request: TopicSupportRequest): TopicSupportRun
}

@Serializable data class TopicSupportAssessment(val topicId: String, val conceptWorkKey: String,
    val evidence: ProposalEvidenceRef, val verifier: TopicLabelIdentity, val workKey: String,
    val decision: SupportDecision)

sealed interface TopicSupportValidation {
    data class Accepted(val assessment: TopicSupportAssessment) : TopicSupportValidation
    data object Stale : TopicSupportValidation
    data object Invalid : TopicSupportValidation
}

data class TopicSupportCoverage(val supported: List<ProposalEvidenceRef>, val complete: Boolean)

/** The key captures the actual text offered to the verifier; the epoch remains a live guard. */
fun topicSupportWorkKey(request: TopicSupportRequest): String {
    val i = request.verifier
    val e = request.evidence
    val fields = listOf("topic-support-v1", request.topicId, request.conceptWorkKey, request.name, request.definition,
        canonicalSemanticId(e.pageId), e.revision, e.passageId, e.body, i.artifact, i.tokenizer, i.template,
        i.prompt, i.schema, i.policy, i.effectiveBackend)
    return semanticHash(fields.joinToString("") { "${it.length}:$it" })
}

/** This validates provenance and shape; it does not prove the verifier's semantic judgment. */
fun validateTopicSupport(request: TopicSupportRequest, decision: SupportDecision, currentEpoch: Long,
    currentConceptKey: String, currentSources: Map<String, String>): TopicSupportValidation {
    val e = request.evidence
    val page = canonicalSemanticId(e.pageId)
    if (request.sourceEpoch != currentEpoch || request.conceptWorkKey != currentConceptKey ||
        currentSources[page] != e.revision) return TopicSupportValidation.Stale
    if (request.topicId.isBlank() || request.conceptWorkKey.isBlank() || request.name.isBlank() ||
        request.name.length > 96 || request.definition.isBlank() || request.definition.length > 512 ||
        page.isBlank() || e.revision.isBlank() || e.passageId.isBlank() || e.body.isBlank()) return TopicSupportValidation.Invalid
    return TopicSupportValidation.Accepted(TopicSupportAssessment(request.topicId, request.conceptWorkKey,
        ProposalEvidenceRef(page, e.revision, e.passageId), request.verifier, topicSupportWorkKey(request), decision))
}

fun aggregateTopicSupport(requests: List<TopicSupportRequest>, assessments: List<TopicSupportAssessment>,
    currentEpoch: Long, currentConceptKey: String, currentSources: Map<String, String>,
    retrievalComplete: Boolean): TopicSupportCoverage {
    val grouped = assessments.groupBy { it.workKey }
    val supported = mutableListOf<ProposalEvidenceRef>()
    var complete = retrievalComplete
    val topicId = requests.firstOrNull()?.topicId
    val verifier = requests.firstOrNull()?.verifier
    val seen = mutableSetOf<Pair<String, String>>()
    for (request in requests) {
        val ref = canonicalSemanticId(request.evidence.pageId) to request.evidence.passageId
        if (request.topicId != topicId || request.verifier != verifier || !seen.add(ref)) { complete = false; continue }
        val entries = grouped[topicSupportWorkKey(request)]
        if (entries?.size != 1) { complete = false; continue }
        val assessment = entries.single()
        val checked = validateTopicSupport(request, assessment.decision, currentEpoch, currentConceptKey, currentSources)
        if (checked !is TopicSupportValidation.Accepted || checked.assessment != assessment) { complete = false; continue }
        when (assessment.decision) {
            SupportDecision.SUPPORTED -> supported += assessment.evidence
            SupportDecision.UNSUPPORTED -> Unit
            SupportDecision.UNCERTAIN -> complete = false
        }
    }
    return TopicSupportCoverage(supported, complete)
}
