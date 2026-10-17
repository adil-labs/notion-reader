package com.example.notionreader.domain.discovery

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

@Serializable
data class TopicMembership(val pageId: String, val metadata: SemanticMetadata, val passage: SemanticPassage,
    val score: Float, val anchor: SemanticPassage, val revision: String = "") {
    fun toMatch() = SemanticMatch(pageId, metadata, passage, score)
}
@Serializable
data class DiscoveredTopic(val id: String, val label: String, val labelSource: SemanticPassage,
    val anchor: EmbeddedPassage, val members: List<TopicMembership>, val createdOrder: Long,
    val experimentalEvidenceValidated: Boolean = false)
@Serializable
data class TopicContinuity(val id: String, val createdOrder: Long, val pageIds: Set<String> = emptySet(),
    val sourceIds: Set<String> = emptySet(), val replacementId: String? = null)
@Serializable
data class NoteConnection(val id: String, val left: TopicMembership, val right: TopicMembership, val score: Float,
    val independentNamedReference: Boolean = false)
@Serializable
data class TopicSnapshot(val topics: List<DiscoveredTopic> = emptyList(), val continuity: List<TopicContinuity> = emptyList(),
    val identity: EmbeddingIdentity? = null, val generation: Long = 0, val complete: Boolean = false,
    val message: String? = null, val connections: List<NoteConnection> = emptyList(),
    val acceptedRevisions: Map<String, String> = emptyMap(), val pendingRevisions: Map<String, String> = emptyMap(),
    val policyVersion: Int = 0, val connectionCursor: Int = 0, val connectionPassComplete: Boolean = false,
    val provisionalTopics: List<DiscoveredTopic> = emptyList(), val provisionalPasses: Map<String, Int> = emptyMap(),
    val candidateCursor: Int = 0, val groupingPass: Int = 0, val sourceSignature: String? = null,
    val candidatePassLimit: Int = 2, val candidateSeeds: Map<String, Int> = emptyMap(),
    val engineVersion: Int = 0, val formationProgress: TopicFormationProgress? = null)
/** Private, versioned work state. Indices refer to canonical eligible sections/passages, never a truncated input. */
@Serializable
data class TopicFormationProgress(
    val inputSignature: String,
    val totalSections: Int,
    val clusters: List<List<Int>>,
    val chain: List<Int> = emptyList(),
    val outerCursor: Int = 0,
    val neighborCursor: Int = 0,
    val bestNeighbor: Int = -1,
    val bestDistance: Float = 2f,
    val groupingComplete: Boolean = false,
    val projectionCluster: Int = 0,
    val projectionPassage: Int = 0,
    val projectionMembers: List<TopicMembership> = emptyList(),
    val comparisonsCompleted: Long = 0,
    val emptyProjectionClusters: List<Int> = emptyList(),
    val candidateGroups: List<List<Int>> = emptyList()
)
enum class TopicWriteFailure { BudgetExceeded, InvalidSnapshot, IoFailure, Unknown }
enum class TopicValidationReason {
    CandidateBound, DuplicateTopicId, InvalidGeneration, ContinuityBound, InvalidIdentity,
    InvalidTopic, InvalidMember, InvalidConnection, InvalidSnapshotProgress
}
sealed interface TopicWriteResult {
    data object Written : TopicWriteResult
    data class Rejected(val failure: TopicWriteFailure, val validationReason: TopicValidationReason? = null) : TopicWriteResult
}
interface TopicStore {
    suspend fun read(): TopicSnapshot?
    suspend fun write(snapshot: TopicSnapshot): Boolean
    suspend fun writeDetailed(snapshot: TopicSnapshot): TopicWriteResult =
        if (write(snapshot)) TopicWriteResult.Written else TopicWriteResult.Rejected(TopicWriteFailure.Unknown)
    suspend fun clear()
    suspend fun bytes(): Long
}
/** Production mapper uses the heading block as section identity. Keep it searchable,
 * but a repeated heading alone is not evidence that two Notes share body content. */
fun SemanticPassage.isBodyEvidence(): Boolean = sourceBlockId != sectionId && substantiveDiscoveryEvidence(this)
/** Separate clustering policy: retrieval thresholds are deliberately not reused. */
data class TopicPolicy(val admission: Float = .65f, val retention: Float = .59f,
    val separation: Float = .75f, val maxAnchors: Int = 64, val minNotes: Int = 3, val version: Int = 9,
    val connection: Float = .5f) {
    init {
        require(admission in 0f..1f && retention in 0f..admission && separation in admission..1f)
        require(maxAnchors in 1..64 && minNotes >= 3 && connection in 0f..1f)
    }
}

/** Streams one source document at a time; only the bounded anchors retain vectors. */
class TopicBuilder(private val previous: TopicSnapshot?, private val identity: EmbeddingIdentity,
    private val policy: TopicPolicy = TopicPolicy()) {
    private data class Candidate(val old: DiscoveredTopic?, var anchor: EmbeddedPassage,
        val members: MutableMap<String, TopicMembership> = linkedMapOf(),
        var currentAnchor: EmbeddedPassage? = null, var replacement: EmbeddedPassage? = null,
        var resolvedId: String? = null, var resolvedOrder: Long? = null)
    private val candidates = mutableListOf<Candidate>()
    private val accepted = linkedMapOf<String, String>()
    private val revisionsInProgress = mutableMapOf<String, String>()
    private var overflow = false
    private val seeded = mutableMapOf<String, Int>()
    private val resuming = previous?.identity == identity && previous.policyVersion == policy.version && previous.complete == false && (previous.acceptedRevisions.isNotEmpty() || previous.pendingRevisions.isNotEmpty())
    init {
        if (previous?.identity == identity) (previous.topics + previous.provisionalTopics).distinctBy { it.id }.take(policy.maxAnchors).forEach { topic ->
            if (valid(topic.anchor) && topic.anchor.passage.isBodyEvidence()) candidates += Candidate(topic, topic.anchor,
                if (resuming) topic.members.associateByTo(linkedMapOf()) { canonicalSemanticId(it.pageId) } else linkedMapOf(),
                if (resuming) topic.anchor else null)
        }
        if (resuming) {
            seeded.putAll(previous!!.candidateSeeds)
            accepted.putAll(previous.acceptedRevisions)
            revisionsInProgress.putAll(previous.pendingRevisions)
        }
    }
    private fun valid(embedded: EmbeddedPassage): Boolean = embedded.vector.size == identity.dimensions &&
        embedded.vector.all { it.isFinite() } && embedded.vector.any { it != 0f }
    private fun cosine(a: List<Float>, b: List<Float>): Float {
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { dot += a[i].toDouble() * b[i]; aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i] }
        return (dot / sqrt(aa * bb)).toFloat().coerceIn(-1f, 1f)
    }
    fun accept(record: SemanticRecord) = acceptPartial(record, true)
    /** Partial chunks of one revision merge; a replacement revision first withdraws all old evidence. */
    fun acceptPartial(record: SemanticRecord, completed: Boolean) {
        if (record.identity != identity) return
        val page = canonicalSemanticId(record.pageId)
        if (accepted[page] == record.revision) return
        if (revisionsInProgress[page] != record.revision) {
            candidates.forEach { candidate ->
                candidate.members.remove(page)
                if (canonicalSemanticId(candidate.currentAnchor?.passage?.pageId.orEmpty()) == page) candidate.currentAnchor = null
                if (canonicalSemanticId(candidate.replacement?.passage?.pageId.orEmpty()) == page) candidate.replacement = null
            }
            revisionsInProgress[page] = record.revision
            accepted.remove(page)
        }
        record.passages.filter { valid(it) && it.passage.isBodyEvidence() && canonicalSemanticId(it.passage.pageId) == page }.forEach { embedded ->
            var represented = false
            candidates.forEach { candidate ->
                val score = cosine(candidate.anchor.vector, embedded.vector)
                if (score >= policy.separation) represented = true
                val retained = candidate.old?.members?.any { canonicalSemanticId(it.pageId) == page } == true
                if (score >= if (retained) policy.retention else policy.admission) {
                    val member = TopicMembership(record.pageId, record.metadata, embedded.passage, score, candidate.anchor.passage, record.revision)
                    val prior = candidate.members[page]
                    if (prior == null || score > prior.score || score == prior.score && embedded.passage.id < prior.passage.id) candidate.members[page] = member
                    if (candidate.replacement == null) candidate.replacement = embedded
                    if (embedded.passage.id == candidate.anchor.passage.id) candidate.currentAnchor = embedded
                }
            }
            // Close anchors represent the same direct neighborhood; independent passages can create independent topics.
            if (!represented) {
                if ((seeded[page] ?: 0) >= 2) {
                    overflow = true
                } else if (candidates.size < policy.maxAnchors) {
                    val member = TopicMembership(record.pageId, record.metadata, embedded.passage, 1f, embedded.passage, record.revision)
                    candidates += Candidate(null, embedded, linkedMapOf(page to member), embedded, embedded)
                    seeded[page] = (seeded[page] ?: 0) + 1
                } else overflow = true
            }
        }
        if (completed) { accepted[page] = record.revision; revisionsInProgress.remove(page) }
    }
    /** A shared Note is not shared evidence: independent passages must stay independent groups. */
    private fun consolidated(input: List<Candidate>): List<Candidate> {
        val kept = mutableListOf<Candidate>()
        val ordered = input.sortedWith(compareByDescending<Candidate> {
            it.old != null && it.currentAnchor != null && it.members.size >= policy.minNotes
        }.thenBy { it.old?.createdOrder ?: Long.MAX_VALUE }
            .thenByDescending { it.members.size }.thenBy { it.anchor.passage.id })
        ordered.forEach { candidate ->
            val redundant = kept.any { prior ->
                val union = prior.members.keys + candidate.members.keys
                val shared = prior.members.keys.intersect(candidate.members.keys)
                val sharedEvidence = shared.count { page ->
                    val a = prior.members[page]!!.passage
                    val b = candidate.members[page]!!.passage
                    val overlap = minOf(a.end, b.end) - maxOf(a.start, b.start)
                    a.id == b.id || a.sourceBlockId == b.sourceBlockId && a.readerChunkId == b.readerChunkId &&
                        overlap > 0 && overlap * 2 >= minOf(a.end - a.start, b.end - b.start) ||
                        nearDuplicateDiscoveryPassages(a, b)
                }
                val heading = prior.anchor.passage.sectionTitle?.trim()?.lowercase()
                val sameLabel = heading != null && heading !in genericHeadings &&
                    heading == candidate.anchor.passage.sectionTitle?.trim()?.lowercase()
                val smaller = minOf(prior.members.size, candidate.members.size)
                val sameSupportedSubset = smaller >= policy.minNotes && shared.size == smaller && sharedEvidence == smaller
                val nearIdentical = shared.size.toDouble() / union.size >= .8 && sharedEvidence.toDouble() / union.size >= .8
                (sameSupportedSubset || nearIdentical) &&
                    (sameLabel || cosine(prior.anchor.vector, candidate.anchor.vector) >= policy.admission)
            }
            // Keep only evidence proven directly against the retained anchor; never union unvalidated members.
            if (!redundant) kept += candidate
        }
        return kept
    }
    /** Select current replacement anchors, then request one exact source-stream replay before publication. */
    fun reanchorMissing(): Boolean {
        val changed = candidates.any { it.replacement != null && (it.currentAnchor == null || it.currentAnchor!!.vector != it.anchor.vector) }
        if (!changed) return false
        candidates.forEach { candidate ->
            candidate.anchor = candidate.currentAnchor ?: candidate.replacement ?: candidate.anchor
            candidate.members.clear(); candidate.currentAnchor = null; candidate.replacement = null
        }
        accepted.clear(); revisionsInProgress.clear()
        return true
    }
    private fun snapshot(checkpoint: Boolean): TopicSnapshot {
        val registry = previous?.continuity.orEmpty().associateByTo(linkedMapOf()) { it.id }
        val active = consolidated(candidates.filter { it.members.isNotEmpty() && (it.currentAnchor != null || it.replacement != null) })
        candidates.forEach { candidate -> candidate.resolvedId?.let { id ->
            registry.putIfAbsent(id, TopicContinuity(id, candidate.resolvedOrder!!,
                candidate.members.keys.toSet(), candidate.members.values.map { it.passage.id }.toSet()))
        } }
        val assignments = mutableMapOf<Candidate, MutableList<TopicContinuity>>()
        // Each prior identity chooses one strongest surviving branch. Similarity alone is never continuity.
        registry.values.filter { it.replacementId == null }.forEach { continuity ->
            val old = (previous?.topics.orEmpty() + previous?.provisionalTopics.orEmpty()).find { it.id == continuity.id }
            fun strength(candidate: Candidate): Long {
                val overlap = continuity.pageIds.count { it in candidate.members }
                if (overlap == 0) return 0
                val anchorSurvives = old != null && candidate.members.values.any { it.passage.id == old.anchor.passage.id }
                return (if (anchorSurvives) 1_000_000_000L else 0) + overlap * 1000L +
                    continuity.sourceIds.count { source -> candidate.members.values.any { it.passage.id == source } }
            }
            val best = active.filter { strength(it) > 0 }.sortedWith(compareByDescending<Candidate> { strength(it) }
                .thenBy { it.anchor.passage.id }).firstOrNull()
            if (best != null) assignments.getOrPut(best) { mutableListOf() }.add(continuity)
        }
        // Reserve every continuity identity before publishing any candidate. A formerly
        // resolved identity may now belong to another branch (or be merged into it).
        val owners = assignments.entries.flatMap { (candidate, identities) ->
            identities.map { it.id to candidate }
        }.toMap()
        val publishedIds = mutableSetOf<String>()
        var nextOrder = (registry.values.maxOfOrNull { it.createdOrder } ?: -1L) + 1
        val groups = active.map { candidate ->
            val anchor = candidate.currentAnchor ?: candidate.replacement!!
            val anchorChanged = anchor.vector != candidate.anchor.vector
            val evidence = if (!anchorChanged) candidate.members.values.toList() else {
                val anchorSimilarity = cosine(candidate.anchor.vector, anchor.vector)
                candidate.members.values.map { member -> member.copy(score = if (member.passage.id == anchor.passage.id) 1f else cosineLowerBound(member.score, anchorSimilarity)) }
                    .filter { it.score >= policy.retention }
            }
            val continuing = assignments[candidate].orEmpty().sortedBy { it.createdOrder }
            val continuity = continuing.firstOrNull()
            val fallback = candidate.resolvedId?.takeIf { id ->
                (owners[id] == null || owners[id] === candidate) &&
                    registry[id]?.replacementId == null && id !in publishedIds
            }
            val id = continuity?.id ?: fallback ?: java.util.UUID.randomUUID().toString()
            val order = continuity?.createdOrder ?: fallback?.let { candidate.resolvedOrder } ?: nextOrder++
            publishedIds += id
            candidate.resolvedId = id; candidate.resolvedOrder = order
            val old = (previous?.topics.orEmpty() + previous?.provisionalTopics.orEmpty()).find { it.id == id }
            val labelSurvives = old != null && evidence.any { it.passage.id == old.labelSource.id }
            val labelSource = if (labelSurvives) old.labelSource else anchor.passage
            val title = evidence.firstOrNull { it.passage.id == labelSource.id }?.metadata?.title ?: "Note"
            val heading = supportedHeading(labelSource.sectionTitle, evidence)
            val preferred = heading ?: sharedSourcePhrase(labelSource, evidence)
            val stable = old?.label?.takeIf { labelSurvives &&
                (supportedHeading(it, evidence) != null || preferred == null && it.startsWith("Notes related to ")) }
            val label = stable ?: preferred ?: "Notes related to $title"
            registry[id] = TopicContinuity(id, order, continuity?.pageIds.orEmpty() + candidate.members.keys,
                continuity?.sourceIds.orEmpty() + candidate.members.values.map { it.passage.id })
            continuing.drop(1).forEach { merged -> registry[merged.id] = merged.copy(replacementId = id) }
            DiscoveredTopic(id, label, labelSource, anchor,
                evidence.map { it.copy(anchor = anchor.passage) }.sortedWith(compareByDescending<TopicMembership> { it.score }.thenBy { it.pageId }.thenBy { it.passage.id }), order)
        }.sortedBy { it.createdOrder }
        val counts = groups.filter { it.members.size < policy.minNotes }.associate { group ->
            group.id to ((previous?.provisionalPasses?.get(group.id) ?: 0) + if (checkpoint) 0 else 1)
        }
        val provisional = if (checkpoint) emptyList() else groups.filter { it.members.size < policy.minNotes && counts.getValue(it.id) < 2 }
        val visible = groups.filter { checkpoint || it.members.size >= policy.minNotes }
        return TopicSnapshot(visible, registry.values.sortedBy { it.createdOrder }, identity,
            (previous?.generation ?: 0) + if (checkpoint) 0 else 1, !checkpoint && !overflow,
            if (overflow) "Topic candidate coverage is incomplete" else if (checkpoint) "Topic processing is incomplete" else null,
            acceptedRevisions = accepted.toMap(), pendingRevisions = revisionsInProgress.toMap(),
            provisionalTopics = provisional, provisionalPasses = if (checkpoint) counts else counts.filterKeys { id -> provisional.any { it.id == id } },
            candidateSeeds = if (checkpoint) seeded.toMap() else emptyMap(), policyVersion = policy.version)
    }
    fun finish(): TopicSnapshot = snapshot(false)
    fun checkpoint(): TopicSnapshot = snapshot(true)
    private companion object { val genericHeadings = setOf("notes", "summary", "introduction", "overview", "untitled", "details", "content") }
}

/** Mandatory source removal also applies to pinned snapshots. No removed text survives in continuity. */
fun invalidateTopics(snapshot: TopicSnapshot, pageIds: Set<String>, permanent: Boolean,
    replacements: Map<String, EmbeddedPassage> = emptyMap()): TopicSnapshot {
    val removed = pageIds.map(::canonicalSemanticId).toSet()
    if (removed.isEmpty()) return snapshot
    val affected = (snapshot.topics + snapshot.provisionalTopics).any { t -> t.members.any { canonicalSemanticId(it.pageId) in removed } || canonicalSemanticId(t.anchor.passage.pageId) in removed || canonicalSemanticId(t.labelSource.pageId) in removed } ||
        snapshot.connections.any { canonicalSemanticId(it.left.pageId) in removed || canonicalSemanticId(it.right.pageId) in removed } ||
        permanent && snapshot.continuity.any { c -> c.pageIds.any { canonicalSemanticId(it) in removed } } ||
        snapshot.acceptedRevisions.keys.any { it in removed } || snapshot.pendingRevisions.keys.any { it in removed }
    if (!affected) return snapshot
    fun available(id: String) = canonicalSemanticId(id) !in removed
    val registry = snapshot.continuity.mapNotNull { continuity ->
        val pages = if (permanent) continuity.pageIds.filter(::available).toSet() else continuity.pageIds
        if (pages.isEmpty()) null else continuity.copy(pageIds = pages,
            sourceIds = if (permanent && continuity.pageIds.any { !available(it) }) emptySet() else continuity.sourceIds)
    }
    fun removeEvidence(input: List<DiscoveredTopic>, minimum: Int): List<DiscoveredTopic> = input.mapNotNull { topic ->
        val members = topic.members.filter { available(it.pageId) }
        val required = if (topic.experimentalEvidenceValidated) 1 else minimum
        if (members.size < required) null
        else if (available(topic.anchor.passage.pageId) && available(topic.labelSource.pageId)) topic.copy(members = members)
        else {
            val replacement = replacements[topic.id]
            val member = members.find { it.passage == replacement?.passage }
            if (replacement == null || member == null || replacement.vector.size != snapshot.identity?.dimensions ||
                replacement.vector.any { !it.isFinite() } || replacement.vector.none { it != 0f }) null
            else {
                val similarity = directCosine(topic.anchor.vector, replacement.vector)
                val rescored = members.map { it.copy(anchor = replacement.passage, score = if (it.passage.id == replacement.passage.id) 1f else cosineLowerBound(it.score, similarity)) }
                    .filter { it.score >= TopicPolicy().retention }
                if (rescored.size < required) null else topic.copy(anchor = replacement, labelSource = replacement.passage,
                    label = (if (topic.experimentalEvidenceValidated) topic.label.takeIf { label -> rescored.all { it.passage.sectionTitle?.trim()?.equals(label, true) == true || it.passage.excerpt.contains(label, true) } } ?: replacement.passage.sectionTitle else null)
                        ?: supportedHeading(replacement.passage.sectionTitle, rescored) ?: sharedSourcePhrase(replacement.passage, rescored)
                        ?: "Notes related to ${member.metadata.title}", members = rescored)
            }
        }
    }
    val provisional = removeEvidence(snapshot.provisionalTopics, 1)
    return snapshot.copy(topics = removeEvidence(snapshot.topics, 3), continuity = registry,
        provisionalTopics = provisional, provisionalPasses = snapshot.provisionalPasses.filterKeys { id -> provisional.any { it.id == id } },
        connections = snapshot.connections.filter { available(it.left.pageId) && available(it.right.pageId) },
        generation = snapshot.generation + 1, acceptedRevisions = snapshot.acceptedRevisions.filterKeys(::available),
        pendingRevisions = snapshot.pendingRevisions.filterKeys(::available), candidateSeeds = snapshot.candidateSeeds.filterKeys(::available), formationProgress = null)
}

/** Safe minimum direct cosine when only two similarities to a shared anchor are known. */
private fun cosineLowerBound(left: Float, right: Float): Float {
    val a = left.toDouble().coerceIn(-1.0, 1.0); val b = right.toDouble().coerceIn(-1.0, 1.0)
    return (a * b - sqrt(maxOf(0.0, (1 - a * a) * (1 - b * b)))).toFloat().coerceIn(-1f, 1f)
}
private fun directCosine(a: List<Float>, b: List<Float>): Float {
    var dot = 0.0; var aa = 0.0; var bb = 0.0
    for (i in a.indices) { dot += a[i].toDouble() * b[i]; aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i] }
    return (dot / sqrt(aa * bb)).toFloat().coerceIn(-1f, 1f)
}
private fun supportedHeading(heading: String?, members: List<TopicMembership>): String? {
    val literal = heading?.trim()?.takeIf { it.length in 3..100 && it.lowercase() !in setOf("notes", "summary", "introduction", "overview", "untitled", "details", "content") && it.lowercase() !in referenceHeadings } ?: return null
    if (Regex("^(i|you|we|they|he|she|my|your|our|their)\\b", RegexOption.IGNORE_CASE).containsMatchIn(literal)) return null
    val support = members.filter { member -> sequenceOf(member.metadata.title, member.passage.sectionTitle.orEmpty(), member.passage.excerpt)
        .any { it.contains(literal, ignoreCase = true) } }.map { canonicalSemanticId(it.pageId) }.distinct().size
    return literal.takeIf { support >= 2 }
}

/** Bounded literal n-grams from one source, supported by at least two distinct Notes. */
internal fun sharedSourcePhrase(source: SemanticPassage, members: List<TopicMembership>, minimumSupport: Int = 2, requireAll: Boolean = false): String? {
    val stops = setOf("the", "a", "an", "and", "or", "to", "of", "in", "is", "are", "be", "it", "this", "that",
        "with", "as", "for", "from", "on", "by", "at", "not", "never", "must", "should", "will", "may", "can", "have", "has", "when", "then", "all", "only", "which", "its", "i", "you", "we", "they", "he", "she", "my", "your", "our", "their", "one", "someone", "something", "need", "needs", "feel", "feels", "seem", "seems")
    val words = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*").findAll(source.excerpt).take(48).toList()
    val distinct = members.distinctBy { canonicalSemanticId(it.pageId) }
    val samples = if (requireAll) distinct else distinct.take(12)
    data class Phrase(val text: String, val support: Int, val content: Int, val position: Int)
    val candidates = mutableListOf<Phrase>()
    for (start in words.indices) for (length in 2..5) {
        if (start + length > words.size) continue
        val selected = words.subList(start, start + length)
        if (selected.first().value.lowercase() in stops || selected.last().value.lowercase() in stops) continue
        val content = selected.count { it.value.lowercase() !in stops }
        if (content < 2) continue
        val literal = source.excerpt.substring(selected.first().range.first, selected.last().range.last + 1)
        if (literal.length > 64 || literal.any { it == '\n' || it == '.' || it == ';' }) continue
        if (selected.any { it.value.lowercase() in setOf("i", "you", "we", "they", "he", "she", "one", "someone", "something") }) continue
        val support = samples.count { it.passage.excerpt.contains(literal, ignoreCase = true) ||
            it.passage.sectionTitle.orEmpty().contains(literal, ignoreCase = true) }
        if (support >= if (requireAll) samples.size else minimumSupport) candidates += Phrase(literal, support, content, start)
    }
    return candidates.sortedWith(compareByDescending<Phrase> { it.support * minOf(3, it.content) }
        .thenByDescending { minOf(3, it.content) }.thenBy { it.text.length }.thenBy { it.position }).firstOrNull()?.text
}
