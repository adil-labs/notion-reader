package com.example.notionreader.domain.discovery

import kotlinx.serialization.Serializable

/** Opaque, linear-size proof of isolated complete-link groups; contains no source text or vectors. */
@Serializable
data class FormationReuse(val policyKey: String, val inputs: List<String>, val groups: List<List<Int>>, val checksum: String)

internal class IncrementalFormation(private val sections: List<FormationOpportunity>, private val policyKey: String,
    private val threshold: Float, private val checkActive: () -> Unit, private val shouldYield: () -> Boolean,
    preparedInputs: List<String>? = null) {
    val inputs = preparedInputs ?: sections.map { section -> semanticHash(section.passages.joinToString { passage ->
        canonicalSemanticId(passage.passage.pageId) + ":" + passage.passage.sectionId + ":" + semanticHash(passage.passage.input)
    } + section.vector.joinToString()) }
    // Strict certificates leave room for Float normalization, sums and dot-product rounding.
    private val margin = maxOf(.001f, (sections.size + 2 * (sections.firstOrNull()?.vector?.size ?: 0)) * 0.000001f)
    private var checks = 0
    private fun dot(a: Int, b: Int): Float {
        if (++checks % 32 == 0) checkActive()
        return projectionDot(sections[a].vector, sections[b].vector)
    }
    private fun digest(keys: List<String>, groups: List<List<Int>>) = semanticHash(policyKey + keys.toString() + groups.toString())
    private fun valid(saved: FormationReuse): Boolean = saved.policyKey == policyKey &&
        saved.inputs.distinct().size == saved.inputs.size && saved.groups.all { group ->
            group.size >= 2 && group.distinct().size == group.size && group.all { it in saved.inputs.indices }
        } && saved.groups.flatten().distinct().size == saved.groups.sumOf { it.size } &&
        saved.checksum == digest(saved.inputs, saved.groups)

    /** Only untouched, certified-isolated groups survive; changed/new opportunities are checked against them. */
    fun seeds(saved: FormationReuse?, initial: List<List<Int>>): List<List<Int>> {
        if (saved == null || !valid(saved) || inputs.distinct().size != inputs.size) return initial
        val positions = inputs.withIndex().associate { it.value to it.index }
        val oldInputs = saved.inputs.toHashSet()
        val added = inputs.indices.filter { inputs[it] !in oldInputs }
        val parent = IntArray(sections.size) { it }
        fun root(i: Int): Int { var at = i; while (parent[at] != at) at = parent[at]; return at }
        fun join(group: List<Int>) { val first = root(group.first()); group.drop(1).forEach { parent[root(it)] = first } }
        initial.filter { it.isNotEmpty() }.forEach(::join)
        for (group in saved.groups) {
            checkActive()
            if (shouldYield()) break
            val mapped = group.mapNotNull { positions[saved.inputs[it]] }
            if (mapped.size != group.size) continue
            var safe = true
            loop@ for (a in mapped) for (b in added) {
                if (checks >= 65_536 || shouldYield() || 1f - dot(a, b) <= threshold + margin) { safe = false; break@loop }
            }
            if (safe) join(mapped)
        }
        val result = MutableList(sections.size) { mutableListOf<Int>() }
        sections.indices.forEach { result[root(it)] += it }
        return result
    }

    /** Optional optimization proof, capped independently of evidence coverage. Uncertified groups are recomputed. */
    fun certify(clusters: List<List<Int>>): FormationReuse {
        checks = 0
        val certified = mutableListOf<List<Int>>()
        for (group in clusters.filter { it.size >= 2 }) {
            checkActive()
            if (checks >= 65_536 || shouldYield()) break
            val inside = group.toHashSet()
            var safe = true
            loop@ for (a in group) for (b in sections.indices) {
                if (a == b) continue
                if (checks >= 65_536 || shouldYield()) { safe = false; break@loop }
                val distance = 1f - dot(a, b)
                if (b in inside && distance >= threshold - margin || b !in inside && distance <= threshold + margin) {
                    safe = false; break@loop
                }
            }
            if (safe) certified += group.toList()
        }
        return FormationReuse(policyKey, inputs, certified, digest(inputs, certified))
    }
}
