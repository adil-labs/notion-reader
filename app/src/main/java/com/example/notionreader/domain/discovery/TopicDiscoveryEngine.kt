package com.example.notionreader.domain.discovery

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.sqrt

/**
 * Pluggable discovery clustering engine contract.
 * Allows running the shipping streaming anchor engine or the experimental hierarchical engine
 * behind an isolated feature flag.
 */
interface TopicDiscoveryEngine {
    fun discover(
        records: List<SemanticRecord>,
        previous: TopicSnapshot?,
        policy: TopicPolicy
    ): TopicSnapshot
}

/**
 * Default shipping engine wrapping the streaming [TopicBuilder].
 * Guaranteed 100% parity with production behavior when feature flag is disabled.
 */
class StreamingAnchorEngine(private val identity: EmbeddingIdentity) : TopicDiscoveryEngine {
    override fun discover(
        records: List<SemanticRecord>,
        previous: TopicSnapshot?,
        policy: TopicPolicy
    ): TopicSnapshot {
        val builder = TopicBuilder(previous, identity, policy)
        records.forEach { builder.accept(it) }
        return builder.finish()
    }
}

/** Exact average-linkage over all eligible sections, with linear vector storage.
 * Average cosine distance equals one minus the dot product of the two mean vectors;
 * this avoids a quadratic distance matrix without approximating retrieval or linkage.
 */
class SectionHierarchicalEngine(
    private val identity: EmbeddingIdentity,
    private val distanceThreshold: Float = .35f,
    private val membershipThreshold: Float = .60f,
    // Compatibility name: now a work quantum, never a section admission ceiling.
    private val maxSectionsBudget: Int = 25_000
) : TopicDiscoveryEngine {
    companion object { const val VERSION = 2 }
    init { require(maxSectionsBudget > 0); require(distanceThreshold in 0f..2f); require(membershipThreshold in 0f..1f) }
    private data class Section(val passages: List<EmbeddedPassage>, val vector: FloatArray)
    private data class Evidence(val record: SemanticRecord, val embedded: EmbeddedPassage, val vector: FloatArray)
    private class DirectionKey(private val vector: FloatArray) {
        override fun equals(other: Any?): Boolean = other is DirectionKey && vector.contentEquals(other.vector)
        override fun hashCode(): Int = vector.contentHashCode()
    }

    /** Exact zero-distance contraction: retain every original section and its linkage weight. */
    private fun initialClusters(sections: List<Section>, checkActive: () -> Unit): List<List<Int>> {
        val clusters = MutableList(sections.size) { mutableListOf<Int>() }
        val directions = HashMap<DirectionKey, Int>()
        sections.forEachIndexed { index, section ->
            if (index % 64 == 0) checkActive()
            // A zero centroid has no direction; shared cancellation is not shared subject evidence.
            val slot = if (section.vector.any { it != 0f })
                directions.getOrPut(DirectionKey(section.vector)) { index } else index
            clusters[slot] += index
        }
        return clusters
    }

    override fun discover(records: List<SemanticRecord>, previous: TopicSnapshot?, policy: TopicPolicy): TopicSnapshot {
        var result = step(records, previous, policy, Int.MAX_VALUE)
        while (result.formationProgress != null) result = step(records, result, policy, Int.MAX_VALUE)
        return result
    }

    /** One bounded comparison quantum. A durable checkpoint can be resumed by a fresh engine. */
    fun step(records: List<SemanticRecord>, previous: TopicSnapshot?, policy: TopicPolicy,
        workBudget: Int = maxSectionsBudget, checkActive: () -> Unit = {}): TopicSnapshot {
        require(workBudget > 0)
        checkActive()
        val sorted = records.filter { it.identity == identity }.sortedBy { canonicalSemanticId(it.pageId) }
        var prepared = 0
        val evidence = buildList {
            for (record in sorted) for (p in record.passages.sortedBy { it.passage.id }) {
                if (++prepared % 64 == 0) checkActive()
                if (!p.passage.isBodyEvidence() || canonicalSemanticId(p.passage.pageId) != canonicalSemanticId(record.pageId)) continue
                val vector = validatedNormalized(p.vector) ?: continue
                add(Evidence(record, p, vector))
            }
        }
        val preparedVectors = evidence.associate { it.embedded.passage.id to it.vector }
        val sections = evidence.groupBy { canonicalSemanticId(it.record.pageId) to it.embedded.passage.sectionId }
            .values.map { entries ->
                checkActive()
                val vector = FloatArray(identity.dimensions)
                entries.forEach { add(vector, it.vector) }
                normalize(vector)
                Section(entries.map { it.embedded }, vector)
            }
        val signature = semanticHash(identity.toString() + policy.toString() + distanceThreshold + membershipThreshold +
            sorted.joinToString { canonicalSemanticId(it.pageId) + ":" + it.revision } +
            evidence.joinToString { it.embedded.passage.id })
        val saved = previous?.formationProgress?.takeIf { previous.engineVersion == VERSION &&
            previous.policyVersion == policy.version && previous.identity == identity && it.inputSignature == signature &&
            it.totalSections == sections.size && validProgress(it, previous, evidence, sections, checkActive) }
        val clusters = (saved?.clusters ?: initialClusters(sections, checkActive)).map { it.toMutableList() }.toMutableList()
        val sums = clusters.map { members -> FloatArray(identity.dimensions).also { sum -> members.forEach { add(sum, sections[it].vector) } } }
        val chain = saved?.chain?.toMutableList() ?: mutableListOf()
        var outer = saved?.outerCursor ?: 0
        var neighbor = saved?.neighborCursor ?: 0
        var best = saved?.bestNeighbor ?: -1
        var bestDistance = saved?.bestDistance ?: 2f
        var grouped = saved?.groupingComplete ?: false
        var projectionCluster = saved?.projectionCluster ?: 0
        var projectionPassage = saved?.projectionPassage ?: 0
        var members = saved?.projectionMembers?.associateByTo(linkedMapOf()) { canonicalSemanticId(it.pageId) } ?: linkedMapOf()
        val topics = if (saved != null) previous!!.topics.toMutableList() else mutableListOf()
        val provisional = if (saved != null) previous!!.provisionalTopics.toMutableList() else mutableListOf()
        val emptyProjections = saved?.emptyProjectionClusters?.toMutableList() ?: mutableListOf()
        val registry = previous?.continuity.orEmpty().associateByTo(linkedMapOf()) { it.id }
        val claimed = (topics + provisional).map { it.id }.toMutableSet()
        var nextOrder = (registry.values.maxOfOrNull { it.createdOrder } ?: 0L) + 1
        var work = 0
        var comparisons = saved?.comparisonsCompleted ?: 0
        fun compared() { work++; comparisons++; if (work % 64 == 0) checkActive() }
        fun resetNeighbor() { neighbor = 0; best = -1; bestDistance = 2f }
        while (!grouped && work < workBudget) {
            if (chain.isEmpty()) {
                while (outer < clusters.size && clusters[outer].isEmpty()) outer++
                if (outer >= clusters.size) { grouped = true; break }
                chain += outer++
                resetNeighbor()
            }
            val u = chain.last()
            while (neighbor < clusters.size && work < workBudget) {
                val k = neighbor++
                if (k == u || clusters[k].isEmpty()) continue
                val distance = (1f - dot(sums[u], sums[k]) / (clusters[u].size.toFloat() * clusters[k].size)).coerceIn(0f, 2f)
                compared()
                // Prefer the previous link on ties: this guarantees mutual-neighbor termination.
                if (best < 0 || distance < bestDistance || distance == bestDistance && chain.size >= 2 && k == chain[chain.lastIndex - 1]) {
                    bestDistance = distance; best = k
                }
            }
            if (neighbor < clusters.size) break
            if (best < 0 || bestDistance > distanceThreshold) {
                chain.removeAt(chain.lastIndex); resetNeighbor(); continue
            }
            if (chain.size >= 2 && chain[chain.lastIndex - 1] == best) {
                val v = best
                chain.removeAt(chain.lastIndex); chain.removeAt(chain.lastIndex)
                clusters[u].addAll(clusters[v]); clusters[v].clear(); add(sums[u], sums[v])
                outer = minOf(outer, u)
            } else chain += best
            resetNeighbor()
        }
        val candidateGroups = if (grouped) subjectGroups(sections, clusters) else emptyList()
        // Reserve every old identity for its strongest surviving source branch before any candidate claims it.
        val reservations = if (grouped) registry.values.filter { it.replacementId == null && it.id !in claimed }.associate { old ->
            checkActive()
            val ranked = candidateGroups.indices.map { index ->
                if (index % 64 == 0) checkActive()
                val passages = candidateGroups[index].flatMap { sections[it].passages }
                val ids = passages.map { it.passage.id }.toSet()
                val pages = passages.map { canonicalSemanticId(it.passage.pageId) }.toSet()
                val sourceOverlap = old.sourceIds.count { it in ids }
                val sourceUnion = (old.sourceIds + ids).size
                val specificity = if (sourceUnion == 0) 0L else sourceOverlap * 1_000_000L / sourceUnion
                index to (sourceOverlap * 1_000_000_000L + specificity * 100L + old.pageIds.count { it in pages })
            }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<Int, Long>> { it.second }.thenBy { it.first })
            old.id to ranked.firstOrNull()?.first
        } else emptyMap()
        while (grouped && projectionCluster < candidateGroups.size && work < workBudget) {
            val indices = candidateGroups[projectionCluster]
            if (indices.isEmpty()) { projectionCluster++; continue }
            val focusedHeading = if (clusters.any { it.toSet() == indices.toSet() }) null else sections[indices.first()].passages.first().passage.sectionTitle?.trim()
            val center = FloatArray(identity.dimensions)
            val candidates = indices.flatMap { sections[it].passages }
            candidates.forEach { add(center, preparedVectors.getValue(it.passage.id)) }; normalize(center)
            val anchor = candidates.maxWithOrNull(compareBy<EmbeddedPassage> { dot(center, preparedVectors.getValue(it.passage.id)) }
                .thenByDescending { it.passage.id })!!
            while (projectionPassage < evidence.size && work < workBudget) {
                val e = evidence[projectionPassage++]
                val score = dot(center, e.vector).coerceIn(-1f, 1f)
                compared()
                val page = canonicalSemanticId(e.record.pageId)
                val old = members[page]
                val supportsFocused = focusedHeading == null || e.embedded.passage.sectionTitle?.trim()?.equals(focusedHeading, true) == true || e.embedded.passage.excerpt.contains(focusedHeading, true)
                if (supportsFocused && score >= membershipThreshold && (old == null || score > old.score || score == old.score && e.embedded.passage.id < old.passage.id)) {
                    members[page] = TopicMembership(e.record.pageId, e.record.metadata, e.embedded.passage, score, anchor.passage, e.record.revision)
                }
            }
            if (projectionPassage < evidence.size) break
            if (members.isNotEmpty()) {
                // The actual label source must be retained membership evidence, not an unrelated heading.
                val source = members.values.firstOrNull { it.passage.id == anchor.passage.id }?.passage ?: members.values.first().passage
                val label = source.sectionTitle?.trim()?.takeIf { it.isNotEmpty() } ?: "Discovered Topic"
                val pages = members.keys.toSet(); val sources = members.values.map { it.passage.id }.toSet()
                // Source overlap ranks before page overlap; one prior identity can serve only one branch.
                val matches = registry.values.filter { it.id !in claimed && it.replacementId == null && reservations[it.id] == projectionCluster }
                    .map { old -> old to (old.sourceIds.count { it in sources } * 1_000_000L + old.pageIds.count { it in pages }) }
                    .filter { it.second > 0 }.sortedWith(compareByDescending<Pair<TopicContinuity, Long>> { it.second }
                        .thenBy { it.first.createdOrder }.thenBy { it.first.id })
                val match = matches.firstOrNull()?.first
                val id = match?.id ?: UUID.randomUUID().toString(); claimed += id
                val order = match?.createdOrder ?: nextOrder++
                matches.drop(1).forEach { (old, _) -> registry[old.id] = old.copy(replacementId = id) }
                registry[id] = TopicContinuity(id, order, pages, sources)
                val base = DiscoveredTopic(id, label, source, anchor, members.values.toList(), order,
                    experimentalEvidenceValidated = true)
                val candidate = sequenceOf(label, sharedSourcePhrase(source, members.values.toList(), minimumSupport = 1, requireAll = true))
                    .filterNotNull().map { base.copy(label = it) }.firstOrNull(::experimentalTopicEvidenceIsValid) ?: base
                if (experimentalTopicEvidenceIsValid(candidate)) topics += candidate
                else provisional += candidate.copy(experimentalEvidenceValidated = false)
            }
            if (members.isEmpty()) emptyProjections += projectionCluster
            projectionCluster++; projectionPassage = 0; members = linkedMapOf()
        }
        val complete = grouped && projectionCluster >= candidateGroups.size
        val progress = if (complete) null else TopicFormationProgress(signature, sections.size, clusters,
            chain.toList(), outer, neighbor, best, bestDistance, grouped, projectionCluster, projectionPassage,
            members.values.toList(), comparisons, emptyProjections, candidateGroups)
        return TopicSnapshot(topics = topics.sortedBy { it.createdOrder }, provisionalTopics = provisional.sortedBy { it.createdOrder },
            continuity = registry.values.sortedBy { it.createdOrder }, identity = identity,
            generation = (previous?.generation ?: 0L) + 1, complete = complete,
            message = if (complete) null else if (!grouped)
                "Topic coverage is incomplete. 0 of ${sections.size} sections finalized; $comparisons comparisons completed. Progress is saved."
            else "Topic coverage is incomplete. ${sections.size} of ${sections.size} sections compared; $projectionCluster of ${candidateGroups.size} topic candidates checked. Progress is saved.",
            acceptedRevisions = sorted.associate { canonicalSemanticId(it.pageId) to it.revision },
            policyVersion = policy.version, engineVersion = VERSION, formationProgress = progress)
    }
    private fun validProgress(work: TopicFormationProgress, snapshot: TopicSnapshot, evidence: List<Evidence>, sections: List<Section>, checkActive: () -> Unit): Boolean {
        if (work.clusters.size != work.totalSections || work.clusters.flatten().sorted() != (0 until work.totalSections).toList() ||
            work.chain.distinct().size != work.chain.size || work.chain.any { it !in work.clusters.indices || work.clusters[it].isEmpty() } ||
            work.outerCursor !in 0..work.totalSections || work.neighborCursor !in 0..work.totalSections ||
            work.bestNeighbor !in -1 until work.totalSections ||
            work.bestNeighbor >= 0 && (work.clusters[work.bestNeighbor].isEmpty() || work.chain.lastOrNull() == work.bestNeighbor) || !work.bestDistance.isFinite() || work.bestDistance !in 0f..2f ||
            work.projectionCluster !in 0..work.candidateGroups.size || work.projectionPassage !in 0..evidence.size ||
            work.comparisonsCompleted < 0) return false
        if (work.groupingComplete && (work.outerCursor != work.totalSections || work.chain.isNotEmpty() ||
                work.neighborCursor != 0 || work.bestNeighbor != -1)) return false
        if (!work.groupingComplete && (work.projectionCluster != 0 || work.projectionPassage != 0 ||
                work.projectionMembers.isNotEmpty() || snapshot.topics.isNotEmpty() || snapshot.provisionalTopics.isNotEmpty())) return false
        if (work.groupingComplete && (snapshot.topics.size + snapshot.provisionalTopics.size + work.emptyProjectionClusters.size !=
                work.projectionCluster)) return false
        if (work.emptyProjectionClusters.distinct().size != work.emptyProjectionClusters.size ||
            work.emptyProjectionClusters.any { it !in 0 until work.projectionCluster || work.candidateGroups[it].isEmpty() }) return false
        if (work.projectionCluster == work.candidateGroups.size && (work.projectionPassage != 0 || work.projectionMembers.isNotEmpty())) return false
        if (work.groupingComplete && work.candidateGroups != subjectGroups(sections, work.clusters)) return false
        val byPassage = evidence.associateBy { it.embedded.passage.id }
        return (snapshot.topics + snapshot.provisionalTopics).all { topic -> topic.members.all { member ->
            byPassage[member.passage.id]?.let { e -> e.embedded.passage == member.passage &&
                e.record.revision == member.revision && e.record.metadata == member.metadata &&
                canonicalSemanticId(e.record.pageId) == canonicalSemanticId(member.pageId) } == true
        } } && projectionEvidenceMatches(work, evidence, sections, checkActive)
    }
    private fun projectionEvidenceMatches(work: TopicFormationProgress, evidence: List<Evidence>, sections: List<Section>, checkActive: () -> Unit): Boolean {
        if (!work.groupingComplete || work.projectionCluster >= work.candidateGroups.size)
            return work.projectionPassage == 0 && work.projectionMembers.isEmpty()
        val indices = work.candidateGroups[work.projectionCluster]
        val candidates = indices.flatMap { sections[it].passages }
        val vectors = evidence.associate { it.embedded.passage.id to it.vector }
        val center = FloatArray(identity.dimensions)
        candidates.forEach { add(center, vectors.getValue(it.passage.id)) }; normalize(center)
        val anchor = candidates.maxWithOrNull(compareBy<EmbeddedPassage> { dot(center, vectors.getValue(it.passage.id)) }
            .thenByDescending { it.passage.id }) ?: return false
        val heading = if (work.clusters.any { it.toSet() == indices.toSet() }) null
            else sections[indices.first()].passages.first().passage.sectionTitle?.trim()
        val expected = linkedMapOf<String, TopicMembership>()
        for ((position, e) in evidence.take(work.projectionPassage).withIndex()) {
            if (position % 64 == 0) checkActive()
            val score = dot(center, e.vector).coerceIn(-1f, 1f)
            val page = canonicalSemanticId(e.record.pageId); val old = expected[page]
            val supported = heading == null || e.embedded.passage.sectionTitle?.trim()?.equals(heading, true) == true || e.embedded.passage.excerpt.contains(heading, true)
            if (supported && score >= membershipThreshold && (old == null || score > old.score || score == old.score && e.embedded.passage.id < old.passage.id))
                expected[page] = TopicMembership(e.record.pageId, e.record.metadata, e.embedded.passage, score, anchor.passage, e.record.revision)
        }
        return expected.values.toList() == work.projectionMembers
    }
    /** Keep authored focused subjects as additional opportunities inside a broader linkage family. */
    private fun subjectGroups(sections: List<Section>, clusters: List<List<Int>>): List<List<Int>> {
        val generic = setOf("notes", "summary", "introduction", "overview", "untitled", "details", "content", "non-goals", "non goals", "explicitly deferred work", "out of scope")
        return clusters.filter { it.isNotEmpty() }.flatMap { cluster ->
            val focused = cluster.groupBy { index -> sections[index].passages.first().passage.sectionTitle?.trim()?.lowercase() }
                .filter { (heading, members) -> heading != null && heading !in generic && heading !in referenceHeadings && members.size < cluster.size }
                .toSortedMap(compareBy<String?> { it.orEmpty() }).values.toList()
            listOf(cluster) + focused
        }.distinctBy { it.sorted() }
    }
    private fun validatedNormalized(vector: List<Float>): FloatArray? {
        if (vector.size != identity.dimensions) return null
        val components = if (vector is PackedNormalizedVector) vector.copyComponents() else vector.toFloatArray()
        var nonzero = false
        for (value in components) {
            if (!value.isFinite()) return null
            if (value != 0f) nonzero = true
        }
        if (!nonzero) return null
        return components.also(::normalize)
    }
    private fun add(target: FloatArray, source: FloatArray) { for (i in target.indices) target[i] += source[i] }
    private fun dot(a: FloatArray, b: FloatArray): Float { var sum = 0f; for (i in a.indices) sum += a[i] * b[i]; return sum }
    private fun normalize(vector: FloatArray) {
        var square = 0.0; for (v in vector) square += v.toDouble() * v
        val norm = sqrt(square).toFloat(); if (norm > 0f) for (i in vector.indices) vector[i] /= norm
    }
}
