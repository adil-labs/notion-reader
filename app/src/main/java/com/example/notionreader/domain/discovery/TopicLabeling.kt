package com.example.notionreader.domain.discovery

import kotlinx.serialization.Serializable

/** Independent of embedding/grouping compatibility; native provenance is explicit. */
@Serializable
data class TopicLabelIdentity(val artifact: String, val tokenizer: String, val template: String,
    val prompt: String, val schema: String, val policy: String, val effectiveBackend: String)
data class TopicLabelEvidence(val passageId: String, val pageId: String, val revision: String, val body: String)
/** Fingerprint covers the whole group, including members outside this bounded evidence selection. */
data class TopicLabelRequest(val topicId: String, val groupFingerprint: String, val sourceEpoch: Long,
    val evidence: List<TopicLabelEvidence>, val identity: TopicLabelIdentity)
@Serializable
data class GeneratedTopicLabel(val topicId: String, val groupFingerprint: String, val workKey: String,
    val text: String, val identity: TopicLabelIdentity, val evidenceIds: List<String> = emptyList(),
    val definition: String)
enum class TopicLabelRejection { StaleSources, EvidenceMismatch, InvalidLabel, InvalidDefinition }
sealed interface TopicLabelValidation {
    data class Accepted(val label: GeneratedTopicLabel) : TopicLabelValidation
    data class Rejected(val reason: TopicLabelRejection) : TopicLabelValidation
}

/** A candidate is untrusted until current sources are checked by the coordinator. */
data class TopicLabelCandidate(val text: String, val evidenceIds: List<String>, val definition: String)
enum class TopicLabelRuntimeFailure { InvalidModel, InputLimit, InvalidOutput, BackendFailure, ResourcePressure }
sealed interface TopicLabelRun {
    data class Completed(val candidate: TopicLabelCandidate) : TopicLabelRun
    data object Cancelled : TopicLabelRun
    data object Unavailable : TopicLabelRun
    data class Failed(val reason: TopicLabelRuntimeFailure) : TopicLabelRun
}
/** Implementations must await native termination before close returns or another engine starts. */
interface TopicLabelRuntime {
    suspend fun label(request: TopicLabelRequest): TopicLabelRun
    suspend fun close()
}

/** Durable input identity excludes the live epoch: reset/source guards belong to the commit ticket.
 * Length framing prevents field-boundary collisions; ordering does not invent new work. */
fun topicLabelWorkKey(request: TopicLabelRequest): String {
    val identity = request.identity
    val fields = listOf("topic-label-v2", request.topicId, request.groupFingerprint,
        identity.artifact, identity.tokenizer, identity.template, identity.prompt, identity.schema,
        identity.policy, identity.effectiveBackend) + request.evidence.sortedBy { it.passageId }.flatMap {
            listOf(it.passageId, it.pageId, it.revision, it.body)
        }
    return semanticHash(fields.joinToString("") { "${it.length}:$it" })
}

/** Structural/source acceptance only. This cannot establish semantic entailment or usefulness. */
fun validateTopicLabel(request: TopicLabelRequest, label: String, evidenceIds: List<String>,
    currentFingerprint: String, currentEpoch: Long, definition: String?): TopicLabelValidation {
    if (request.groupFingerprint != currentFingerprint || request.sourceEpoch != currentEpoch)
        return TopicLabelValidation.Rejected(TopicLabelRejection.StaleSources)
    val expected = request.evidence.map { it.passageId }
    if (expected.isEmpty() || expected.any { it.isBlank() } || expected.toSet().size != expected.size ||
        evidenceIds.size != evidenceIds.toSet().size || evidenceIds.toSet() != expected.toSet())
        return TopicLabelValidation.Rejected(TopicLabelRejection.EvidenceMismatch)
    if (label.isBlank() || label.length > 96 || !validLabelCharacters(label) || !specificTopicWording(label.trim()))
        return TopicLabelValidation.Rejected(TopicLabelRejection.InvalidLabel)
    if (!validTopicDefinition(definition)) return TopicLabelValidation.Rejected(TopicLabelRejection.InvalidDefinition)
    return TopicLabelValidation.Accepted(GeneratedTopicLabel(request.topicId, request.groupFingerprint,
        topicLabelWorkKey(request), label.trim(), request.identity, evidenceIds.toList(), definition!!.trim()))
}

private fun validTopicDefinition(definition: String?): Boolean = definition != null &&
    definition.isNotBlank() && definition.length <= 512 && validLabelCharacters(definition)

/** Only generated source-bound meaning constructs a query; display aliases are not inputs. */
fun topicConceptQuery(label: GeneratedTopicLabel): String? =
    if (validTopicDefinition(label.definition) && label.text.length <= 96 &&
        validLabelCharacters(label.text) && specificTopicWording(label.text.trim()))
        "${label.text.trim()}\n${label.definition.trim()}" else null

private fun validLabelCharacters(label: String): Boolean {
    var index = 0
    while (index < label.length) {
        val character = label[index]
        if (character.isISOControl() || character.isLowSurrogate()) return false
        if (character.isHighSurrogate()) {
            if (index + 1 >= label.length || !label[index + 1].isLowSurrogate()) return false
            index++
        }
        index++
    }
    return true
}
