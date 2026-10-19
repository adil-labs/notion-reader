package com.example.notionreader.domain.discovery

import kotlin.math.sqrt

internal data class FormationOpportunity(val passages: List<EmbeddedPassage>, val vector: FloatArray)
internal data class FormationEvidence(val record: SemanticRecord, val embedded: EmbeddedPassage, val vector: FloatArray)
internal data class PreparedFormationInput(val records: List<SemanticRecord>, val evidence: List<FormationEvidence>,
    val opportunities: List<FormationOpportunity>, val authoredSections: Int)
internal class FormationDirectionKey(private val vector: FloatArray) {
    override fun equals(other: Any?): Boolean = other is FormationDirectionKey && vector.contentEquals(other.vector)
    override fun hashCode(): Int = vector.contentHashCode()
}

/** Shared shipping preparation, also used by explicit host evaluation exports. */
internal fun prepareFormationInput(records: List<SemanticRecord>, identity: EmbeddingIdentity,
    checkActive: () -> Unit = {}): PreparedFormationInput {
    checkActive()
    val sorted = records.filter { it.identity == identity }.sortedBy { canonicalSemanticId(it.pageId) }
    var prepared = 0
    fun normalized(vector: List<Float>): FloatArray? {
        if (vector.size != identity.dimensions) return null
        val components = if (vector is PackedNormalizedVector) vector.copyComponents() else vector.toFloatArray()
        var nonzero = false
        for (value in components) {
            if (!value.isFinite()) return null
            if (value != 0f) nonzero = true
        }
        if (!nonzero) return null
        var square = 0.0
        for (value in components) square += value.toDouble() * value
        val norm = sqrt(square).toFloat()
        if (norm > 0f) for (i in components.indices) components[i] /= norm
        return components
    }
    val evidence = buildList {
        for (record in sorted) for (p in record.passages.sortedBy { it.passage.id }) {
            if (++prepared % 64 == 0) checkActive()
            if (!p.passage.isBodyEvidence() || canonicalSemanticId(p.passage.pageId) != canonicalSemanticId(record.pageId)) continue
            val vector = normalized(p.vector) ?: continue
            add(FormationEvidence(record, p, vector))
        }
    }
    val sections = evidence.groupBy { canonicalSemanticId(it.record.pageId) to it.embedded.passage.sectionId }
    val opportunities = sections.values.flatMap { entries ->
        checkActive()
        val directions = entries.groupBy { FormationDirectionKey(it.vector) }.values
        if (directions.size == 1) listOf(FormationOpportunity(entries.map { it.embedded }, entries.first().vector))
        else directions.map { same -> FormationOpportunity(same.map { it.embedded }, same.first().vector) }
    }
    return PreparedFormationInput(sorted, evidence, opportunities, sections.size)
}
