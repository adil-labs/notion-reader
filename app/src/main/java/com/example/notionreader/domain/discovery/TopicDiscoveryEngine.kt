package com.example.notionreader.domain.discovery

import kotlinx.serialization.Serializable
import java.util.UUID

internal data class ExactSeedFormation(val groups: List<List<Int>>, val opportunities: Int, val comparisons: Long)

/** Exact average-linkage over all eligible sections, with linear vector storage.
 * Average cosine distance equals one minus the dot product of the two mean vectors;
 * this avoids a quadratic distance matrix without approximating retrieval or linkage.
 */
class SectionHierarchicalEngine(
    private val identity: EmbeddingIdentity,
    private val distanceThreshold: Float = .35f,
    private val membershipThreshold: Float = .60f,
    // A work quantum, never a section admission ceiling.
    private val workQuantum: Int = 250_000,
    // Exact exhaustive projection remains available to host/regression oracles, never a runtime engine selector.
    private val metadataBoundsEnabled: Boolean = true,
    private val formationBoundsEnabled: Boolean = true
) {
    companion object { const val VERSION = 4 }
    /** Only the process-owned coordinator uses this cache; imported/public steps remain independently checked. */
    private class PreparedWork(val records: List<SemanticRecord>, val policy: TopicPolicy,
        val input: PreparedFormationInput, val vectors: Map<String, FloatArray>, val signature: String) {
        var snapshot: TopicSnapshot? = null
        var sums: List<FloatArray>? = null
        var bounds: List<FormationVectorBounds>? = null
        var projection: Pair<Int, SubjectProjection>? = null
        var buckets: MetadataEvidenceBuckets? = null
        var eligible: Pair<Int, BooleanArray>? = null
        var formationInputs: List<String>? = null
    }
    private var preparedWork: PreparedWork? = null
    internal var prunedProjectionComparisons: Long = 0
        private set
    internal var avoidedFormationComponents: Long = 0
        private set
    init { require(workQuantum > 0); require(distanceThreshold in 0f..2f); require(membershipThreshold in 0f..1f) }
    /** Exact zero-distance contraction: retain every original section and its linkage weight. */
    private fun initialClusters(sections: List<FormationOpportunity>, checkActive: () -> Unit): List<List<Int>> {
        val clusters = MutableList(sections.size) { mutableListOf<Int>() }
        val directions = HashMap<FormationDirectionKey, Int>()
        sections.forEachIndexed { index, section ->
            if (index % 64 == 0) checkActive()
            // A zero centroid has no direction; shared cancellation is not shared subject evidence.
            val slot = if (section.vector.any { it != 0f })
                directions.getOrPut(FormationDirectionKey(section.vector)) { index } else index
            clusters[slot] += index
        }
        return clusters
    }

    fun discover(records: List<SemanticRecord>, previous: TopicSnapshot?, policy: TopicPolicy): TopicSnapshot {
        var result = step(records, previous, policy, Int.MAX_VALUE)
        while (result.formationProgress != null) result = step(records, result, policy, Int.MAX_VALUE)
        return result
    }

    /** Explicit host evaluation observes the existing phase; it never selects another algorithm. */
    internal fun evaluate(records: List<SemanticRecord>, policy: TopicPolicy, checkActive: () -> Unit = {},
        onFormation: (ExactSeedFormation) -> Unit): TopicSnapshot {
        var observed = false
        val observer: (ExactSeedFormation) -> Unit = { seeds ->
            if (!observed) { onFormation(seeds); observed = true }
        }
        var result = stepInternal(records, null, policy, Int.MAX_VALUE, checkActive, observer)
        while (result.formationProgress != null)
            result = stepInternal(records, result, policy, Int.MAX_VALUE, checkActive, observer)
        check(observed)
        return result
    }

    /** One bounded comparison quantum. Orderly yields retain progress; hard cancellation still propagates.
     * A durable checkpoint can be resumed by a fresh engine. */
    fun step(records: List<SemanticRecord>, previous: TopicSnapshot?, policy: TopicPolicy,
        workBudget: Int = workQuantum, shouldYield: () -> Boolean = { false }, checkActive: () -> Unit = {}): TopicSnapshot =
        stepInternal(records, previous, policy, workBudget, checkActive, shouldYield = shouldYield)

    internal fun stepPrepared(records: List<SemanticRecord>, previous: TopicSnapshot?, policy: TopicPolicy,
        workBudget: Int = workQuantum, shouldYield: () -> Boolean = { false }, checkActive: () -> Unit = {}): TopicSnapshot =
        stepInternal(records, previous, policy, workBudget, checkActive, shouldYield = shouldYield, retainPrepared = true)

    private fun stepInternal(records: List<SemanticRecord>, previous: TopicSnapshot?, policy: TopicPolicy,
        workBudget: Int, checkActive: () -> Unit, onFormation: ((ExactSeedFormation) -> Unit)? = null,
        shouldYield: () -> Boolean = { false }, retainPrepared: Boolean = false): TopicSnapshot {
        require(workBudget > 0)
        checkActive()
        val retained = if (retainPrepared) preparedWork?.takeIf { it.records === records && it.policy == policy } else null
        val input = retained?.input ?: prepareFormationInput(records, identity, checkActive)
        val sorted = input.records
        val evidence = input.evidence
        val sections = input.opportunities
        val authoredSections = input.authoredSections
        val preparedVectors = retained?.vectors ?: evidence.associate { it.embedded.passage.id to it.vector }
        val signature = retained?.signature ?: semanticHash(identity.toString() + policy.toString() + distanceThreshold + membershipThreshold +
            sorted.joinToString { canonicalSemanticId(it.pageId) + ":" + it.revision } +
            evidence.joinToString { it.embedded.passage.id })
        val workspace = if (retainPrepared) retained ?: PreparedWork(records, policy, input, preparedVectors, signature)
            .also { preparedWork = it } else null
        val last = workspace?.snapshot
        val trusted = previous != null && last != null && previous.formationProgress === last.formationProgress &&
            previous.topics === last.topics && previous.provisionalTopics === last.provisionalTopics &&
            previous.continuity === last.continuity && previous.identity == last.identity &&
            previous.policyVersion == last.policyVersion && previous.engineVersion == last.engineVersion
        val saved = previous?.formationProgress?.takeIf { previous.engineVersion == VERSION &&
            previous.policyVersion == policy.version && previous.identity == identity && it.inputSignature == signature &&
            it.totalOpportunities == sections.size && (trusted || validProgress(it, previous, evidence, sections, checkActive)) }
        val reuse = IncrementalFormation(sections, semanticHash(identity.toString() + policy.toString() +
            distanceThreshold + membershipThreshold + VERSION), distanceThreshold, checkActive, shouldYield, workspace?.formationInputs)
        workspace?.formationInputs = reuse.inputs
        val clusters = (saved?.clusters ?: reuse.seeds(previous?.formationReuse, initialClusters(sections, checkActive))).map { it.toMutableList() }.toMutableList()
        val sums = if (saved != null && trusted) workspace?.sums else null
        val workingSums = sums ?: clusters.map { members -> FloatArray(identity.dimensions).also { sum -> members.forEachIndexed { position, index ->
            if (position % 64 == 0) checkActive()
            add(sum, sections[index].vector)
        } } }
        val workingBounds = if (formationBoundsEnabled) {
            (if (saved != null && trusted && sums != null) workspace?.bounds else null)
                ?: workingSums.mapIndexed { index, vector ->
                    if (index % 64 == 0) checkActive()
                    FormationVectorBounds(vector)
                }
        } else null
        var retainedProjection = if (saved != null && trusted) workspace?.projection else null
        var retainedEligible = if (saved != null && trusted) workspace?.eligible else null
        // An interrupted invocation must never leave a trusted checkpoint paired with mutated working vectors.
        workspace?.snapshot = null
        workspace?.sums = null
        workspace?.bounds = null
        workspace?.projection = null
        workspace?.eligible = null
        val changedSums = mutableSetOf<Int>()
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
        var yieldRequested = false
        var comparisons = saved?.comparisonsCompleted ?: 0
        fun worked() {
            work++
            if (work % 64 == 0) {
                checkActive()
                yieldRequested = shouldYield()
            }
        }
        fun compared() { comparisons++; worked() }
        fun resetNeighbor() { neighbor = 0; best = -1; bestDistance = 2f }
        while (!grouped && work < workBudget && !yieldRequested) {
            if (chain.isEmpty()) {
                while (outer < clusters.size && clusters[outer].isEmpty()) outer++
                if (outer >= clusters.size) { grouped = true; break }
                chain += outer++
                resetNeighbor()
            }
            val u = chain.last()
            while (neighbor < clusters.size && work < workBudget && !yieldRequested) {
                val k = neighbor++
                if (k == u || clusters[k].isEmpty()) continue
                val denominator = clusters[u].size.toFloat() * clusters[k].size
                val leftBound = workingBounds?.get(u)
                val score = if (leftBound != null) leftBound.dotOrReject(workingBounds!![k], denominator, distanceThreshold)
                    else dot(workingSums[u], workingSums[k])
                val rejected = leftBound?.lastRejected == true
                if (rejected) avoidedFormationComponents += leftBound!!.lastAvoidedComponents
                compared()
                if (rejected) continue
                val distance = (1f - score / denominator).coerceIn(0f, 2f)
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
                clusters[u].addAll(clusters[v]); clusters[v].clear(); add(workingSums[u], workingSums[v])
                workingBounds?.get(u)?.refresh()
                changedSums += u; changedSums += v
                outer = minOf(outer, u)
            } else chain += best
            resetNeighbor()
        }
        val candidateGroups = if (grouped) {
            if (saved?.groupingComplete == true && trusted) saved.candidateGroups else candidateSubjectGroups(sections, clusters)
        } else emptyList()
        val buckets = if (grouped && metadataBoundsEnabled) workspace?.buckets
            ?: MetadataEvidenceBuckets(evidence, checkActive).also { workspace?.buckets = it } else null
        if (grouped && onFormation != null)
            onFormation(ExactSeedFormation(candidateGroups.map { it.toList() }, sections.size, comparisons))
        // Reserve every old identity for its strongest surviving source branch before any candidate claims it.
        val reservations = if (grouped) reserveTopicBranches(
            registry.values.filter { it.replacementId == null && it.id !in claimed },
            candidateGroups.map { indices -> indices.flatMap { sections[it].passages }.map { it.passage } },
            checkActive = checkActive
        ) else emptyMap()
        while (grouped && projectionCluster < candidateGroups.size && work < workBudget && !yieldRequested) {
            val indices = candidateGroups[projectionCluster]
            if (indices.isEmpty()) { projectionCluster++; continue }
            val projection = retainedProjection?.takeIf { it.first == projectionCluster }?.second
                ?: prepareSubjectProjection(sections, indices, preparedVectors, identity.dimensions)
            retainedProjection = projectionCluster to projection
            val center = projection.center
            val anchor = projection.anchor
            val eligible = retainedEligible?.takeIf { it.first == projectionCluster }?.second
                ?: buckets?.eligible(center, membershipThreshold, checkActive)
            retainedEligible = eligible?.let { projectionCluster to it }
            while (projectionPassage < evidence.size && work < workBudget && !yieldRequested) {
                val position = projectionPassage++
                if (eligible != null && !eligible[position]) {
                    prunedProjectionComparisons++
                    worked()
                    continue
                }
                val e = evidence[position]
                val score = dot(center, e.vector).coerceIn(-1f, 1f)
                compared()
                considerSubjectMembership(e, score, anchor.passage, members, membershipThreshold)
            }
            if (projectionPassage < evidence.size) break
            if (members.isNotEmpty()) {
                val pages = members.keys.toSet(); val sources = members.values.map { topicEvidenceIdentity(it.passage) }.toSet()
                // Source overlap ranks before page overlap; one prior identity can serve only one branch.
                val matches = registry.values.filter { it.id !in claimed && it.replacementId == null && reservations[it.id] == projectionCluster }
                    .filter { old -> old.sourceIds.any { it in sources } }
                    .map { old -> old to (old.sourceIds.count { it in sources } * 1_000_000L + old.pageIds.count { it in pages }) }
                    .filter { it.second > 0 }.sortedWith(compareByDescending<Pair<TopicContinuity, Long>> { it.second }
                        .thenBy { it.first.createdOrder }.thenBy { it.first.id })
                val match = matches.firstOrNull()?.first
                val id = match?.id ?: UUID.randomUUID().toString(); claimed += id
                val order = match?.createdOrder ?: nextOrder++
                matches.drop(1).forEach { (old, _) -> registry[old.id] = old.copy(replacementId = id) }
                registry[id] = TopicContinuity(id, order, pages, sources)
                val candidate = nameSubjectCandidate(id, order, projection, members.values.toList())
                if (topicEvidenceIsValid(candidate)) topics += candidate
                else provisional += candidate
            }
            if (members.isEmpty()) emptyProjections += projectionCluster
            projectionCluster++; projectionPassage = 0; members = linkedMapOf()
        }
        val complete = grouped && projectionCluster >= candidateGroups.size
        val progress = if (complete) null else TopicFormationProgress(signature, authoredSections, clusters,
            chain.toList(), outer, neighbor, best, bestDistance, grouped, projectionCluster, projectionPassage,
            members.values.toList(), comparisons, emptyProjections, candidateGroups, sections.size)
        val result = TopicSnapshot(topics = topics.sortedBy { it.createdOrder }, provisionalTopics = provisional.sortedBy { it.createdOrder },
            continuity = registry.values.sortedBy { it.createdOrder }, identity = identity,
            generation = (previous?.generation ?: 0L) + 1, complete = complete,
            message = if (complete) null else if (!grouped)
                "Topic coverage is incomplete. Finding topics in ${sorted.size} Notes (${sections.size} passage groups across $authoredSections sections). $comparisons similarity checks completed. Progress is saved."
            else "Topic coverage is incomplete. Checking topic evidence in ${sorted.size} Notes (${evidence.size} passages across $authoredSections sections): $projectionCluster of ${candidateGroups.size} candidates checked. $comparisons similarity checks completed. Progress is saved.",
            acceptedRevisions = sorted.associate { canonicalSemanticId(it.pageId) to it.revision },
            policyVersion = policy.version, engineVersion = VERSION, formationProgress = progress,
            formationReuse = if (complete) reuse.certify(clusters) else previous?.formationReuse,
            subjectRelationships = if (complete) buildSubjectRelationships(publishableDiscoveryTopics(topics), sorted, checkActive) else emptyList())
        if (workspace != null) {
            // Preserve the original resumed-step summation order exactly, recomputing only clusters changed this slice.
            for (index in changedSums) {
                workingSums[index].fill(0f)
                clusters[index].forEachIndexed { position, member ->
                    if (position % 64 == 0) checkActive()
                    add(workingSums[index], sections[member].vector)
                }
                workingBounds?.get(index)?.refresh()
            }
            workspace.sums = workingSums
            workspace.bounds = workingBounds
            workspace.projection = retainedProjection
            workspace.eligible = retainedEligible
            workspace.snapshot = result
        }
        return result
    }
    private fun validProgress(work: TopicFormationProgress, snapshot: TopicSnapshot, evidence: List<FormationEvidence>, sections: List<FormationOpportunity>, checkActive: () -> Unit): Boolean {
        if (work.clusters.size != work.totalOpportunities || work.clusters.flatten().sorted() != (0 until work.totalOpportunities).toList() ||
            work.chain.distinct().size != work.chain.size || work.chain.any { it !in work.clusters.indices || work.clusters[it].isEmpty() } ||
            work.outerCursor !in 0..work.totalOpportunities || work.neighborCursor !in 0..work.totalOpportunities ||
            work.bestNeighbor !in -1 until work.totalOpportunities ||
            work.bestNeighbor >= 0 && (work.clusters[work.bestNeighbor].isEmpty() || work.chain.lastOrNull() == work.bestNeighbor) || !work.bestDistance.isFinite() || work.bestDistance !in 0f..2f ||
            work.projectionCluster !in 0..work.candidateGroups.size || work.projectionPassage !in 0..evidence.size ||
            work.comparisonsCompleted < 0) return false
        if (work.groupingComplete && (work.outerCursor != work.totalOpportunities || work.chain.isNotEmpty() ||
                work.neighborCursor != 0 || work.bestNeighbor != -1)) return false
        if (!work.groupingComplete && (work.projectionCluster != 0 || work.projectionPassage != 0 ||
                work.projectionMembers.isNotEmpty() || snapshot.topics.isNotEmpty() || snapshot.provisionalTopics.isNotEmpty())) return false
        if (work.groupingComplete && (snapshot.topics.size + snapshot.provisionalTopics.size + work.emptyProjectionClusters.size !=
                work.projectionCluster)) return false
        if (work.emptyProjectionClusters.distinct().size != work.emptyProjectionClusters.size ||
            work.emptyProjectionClusters.any { it !in 0 until work.projectionCluster || work.candidateGroups[it].isEmpty() }) return false
        if (work.projectionCluster == work.candidateGroups.size && (work.projectionPassage != 0 || work.projectionMembers.isNotEmpty())) return false
        if (work.groupingComplete && work.candidateGroups != candidateSubjectGroups(sections, work.clusters)) return false
        val byPassage = evidence.associateBy { it.embedded.passage.id }
        return (snapshot.topics + snapshot.provisionalTopics).all { topic -> topic.members.all { member ->
            byPassage[member.passage.id]?.let { e -> e.embedded.passage == member.passage &&
                e.record.revision == member.revision && e.record.metadata == member.metadata &&
                canonicalSemanticId(e.record.pageId) == canonicalSemanticId(member.pageId) } == true
        } } && projectionEvidenceMatches(work, evidence, sections, checkActive)
    }
    private fun projectionEvidenceMatches(work: TopicFormationProgress, evidence: List<FormationEvidence>, sections: List<FormationOpportunity>, checkActive: () -> Unit): Boolean {
        if (!work.groupingComplete || work.projectionCluster >= work.candidateGroups.size)
            return work.projectionPassage == 0 && work.projectionMembers.isEmpty()
        val indices = work.candidateGroups[work.projectionCluster]
        val candidates = indices.flatMap { sections[it].passages }
        val vectors = evidence.associate { it.embedded.passage.id to it.vector }
        val center = FloatArray(identity.dimensions)
        candidates.forEach { add(center, vectors.getValue(it.passage.id)) }; normalize(center)
        val anchor = candidates.maxWithOrNull(compareBy<EmbeddedPassage> { dot(center, vectors.getValue(it.passage.id)) }
            .thenByDescending { it.passage.id }) ?: return false
        val expected = linkedMapOf<String, TopicMembership>()
        for ((position, e) in evidence.take(work.projectionPassage).withIndex()) {
            if (position % 64 == 0) checkActive()
            val score = dot(center, e.vector).coerceIn(-1f, 1f)
            val page = canonicalSemanticId(e.record.pageId); val old = expected[page]
            if (score >= membershipThreshold && (old == null || score > old.score || score == old.score && e.embedded.passage.id < old.passage.id))
                expected[page] = TopicMembership(e.record.pageId, e.record.metadata, e.embedded.passage, score, anchor.passage, e.record.revision)
        }
        return expected.values.toList() == work.projectionMembers
    }
    private fun add(target: FloatArray, source: FloatArray) = addProjectionVector(target, source)
    private fun dot(a: FloatArray, b: FloatArray): Float = projectionDot(a, b)
    private fun normalize(vector: FloatArray) = normalizeProjectionVector(vector)
}
