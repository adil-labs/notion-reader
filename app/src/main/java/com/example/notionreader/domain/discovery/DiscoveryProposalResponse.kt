package com.example.notionreader.domain.discovery

/** A proposal is a work item, not a canonical topic identity. */
data class DiscoveryProposalRequest(
    val proposalKey: String,
    val sourceEpoch: Long,
    val evidence: List<TopicLabelEvidence>,
    val identity: TopicLabelIdentity
)

@kotlinx.serialization.Serializable
enum class DiscoveryAbstentionReason { NO_USEFUL_TOPIC, INSUFFICIENT_EVIDENCE, MIXED_EVIDENCE }

sealed interface DiscoveryProposalResponse {
    data class Concept(val name: String, val definition: String, val listeningDescription: String,
        val evidenceIds: List<String>) : DiscoveryProposalResponse
    data class Abstained(val reason: DiscoveryAbstentionReason) : DiscoveryProposalResponse
}

sealed interface DiscoveryProposalRun {
    data class Completed(val response: DiscoveryProposalResponse) : DiscoveryProposalRun
    data object Cancelled : DiscoveryProposalRun
    data object Unavailable : DiscoveryProposalRun
    data class Failed(val reason: TopicLabelRuntimeFailure) : DiscoveryProposalRun
}

/** Shares native ownership and cleanup with the existing topic label runtime. */
interface DiscoveryProposalRuntime {
    suspend fun propose(request: DiscoveryProposalRequest): DiscoveryProposalRun
}

sealed interface DiscoveryProposalValidation {
    data class Accepted(val response: DiscoveryProposalResponse, val workKey: String) : DiscoveryProposalValidation
    data object Stale : DiscoveryProposalValidation
    data object Invalid : DiscoveryProposalValidation
}

/** Stable only for the exact evidence and generator identity; epoch remains a live commit guard. */
fun discoveryProposalWorkKey(request: DiscoveryProposalRequest): String {
    val identity = request.identity
    val fields = listOf("discovery-proposal-response-v1", request.proposalKey, identity.artifact,
        identity.tokenizer, identity.template, identity.prompt, identity.schema, identity.policy,
        identity.effectiveBackend) + request.evidence.sortedBy { it.passageId }.flatMap {
        listOf(it.passageId, canonicalSemanticId(it.pageId), it.revision, it.body)
    }
    return semanticHash(fields.joinToString("") { "${it.length}:$it" })
}

fun validateDiscoveryProposal(request: DiscoveryProposalRequest, response: DiscoveryProposalResponse,
    currentEpoch: Long, currentRevisions: Map<String, String>): DiscoveryProposalValidation {
    if (request.sourceEpoch != currentEpoch) return DiscoveryProposalValidation.Stale
    val offered = request.evidence
    if (request.proposalKey.isBlank() || offered.isEmpty() ||
        offered.any { it.passageId.isBlank() || canonicalSemanticId(it.pageId).isBlank() ||
            it.revision.isBlank() || it.body.isBlank() } ||
        offered.map { it.passageId }.toSet().size != offered.size) return DiscoveryProposalValidation.Invalid
    if (offered.any { currentRevisions[canonicalSemanticId(it.pageId)] != it.revision })
        return DiscoveryProposalValidation.Stale
    if (response is DiscoveryProposalResponse.Concept) {
        val ids = response.evidenceIds
        if (ids.isEmpty() || ids.size != ids.toSet().size || ids.any { id -> offered.none { it.passageId == id } } ||
            !validProposalText(response.name, 96) || !specificTopicWording(response.name.trim()) ||
            !validProposalText(response.definition, 512) || !validProposalText(response.listeningDescription, 512))
            return DiscoveryProposalValidation.Invalid
    }
    return DiscoveryProposalValidation.Accepted(response, discoveryProposalWorkKey(request))
}

/** Structural checks are not a semantic-support verdict. */
private fun validProposalText(value: String, maxLength: Int): Boolean {
    if (value.isBlank() || value.length > maxLength) return false
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character.isISOControl() || character.isLowSurrogate()) return false
        if (character.isHighSurrogate()) {
            if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
            index++
        }
        index++
    }
    return true
}
