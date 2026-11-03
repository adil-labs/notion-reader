package com.example.notionreader.domain.discovery

import kotlinx.serialization.Serializable

@Serializable
data class ProposalEvidenceRef(val pageId: String, val revision: String, val passageId: String)
@Serializable
enum class ProposalReason { CHANGED_BODY, SAME_SECTION, SEMANTIC_NEIGHBOR, EXPLICIT_REFERENCE }
@Serializable
enum class ProposalIncompleteReason { STRUCTURE, SOURCE_SCAN, EVIDENCE_LIMIT, STALE_SOURCE, ANCHOR_WINDOW, EXISTING_CONCEPT_CATALOG, EXISTING_CONCEPT_UNCERTAIN }
/** These reasons leave reconciliation work but do not invalidate this concept's checked evidence. */
fun ProposalIncompleteReason.blocksConceptAdmission(): Boolean =
    this != ProposalIncompleteReason.STRUCTURE && this != ProposalIncompleteReason.ANCHOR_WINDOW
data class IncrementalProposal(val proposalKey: String, val evidence: List<ProposalEvidenceRef>, val reasons: Set<ProposalReason>,
    val incomplete: Set<ProposalIncompleteReason>, val policyVersion: Int = 1)
@Serializable
data class ProposalScoredEvidence(val ref: ProposalEvidenceRef, val score: Float, val reason: ProposalReason)
@Serializable
data class ProposalScanCheckpoint(val contextKey: String, val scannedRevisions: Map<String, String>,
    val candidates: Map<String, List<ProposalScoredEvidence>>, val truncatedAnchors: Set<String>,
    val scopeKey: String? = null)
data class ProposalWindow(val proposals: List<IncrementalProposal>, val nextAnchorOffset: Int?, val stale: Boolean = false,
    val checkpoint: ProposalScanCheckpoint? = null)

fun proposeDiscoveryWindow(changed: SemanticRecord, changedPassageIds: Set<String>,
    explainedPassageIds: Set<String>, olderRecords: Sequence<SemanticRecord>, currentRevisions: Map<String, String>,
    explicitPageIds: Set<String>, structureComplete: Boolean, scanComplete: Boolean,
    anchorOffset: Int = 0, maxAnchors: Int = 8, maxRecords: Int = 128, maxEvidence: Int = 4,
    similarityThreshold: Float = .85f, checkActive: () -> Unit = {},
    previous: ProposalScanCheckpoint? = null): ProposalWindow {
    require(anchorOffset >= 0 && maxAnchors in 1..128 && maxRecords in 1..4096 && maxEvidence in 1..32)
    require(similarityThreshold.isFinite() && similarityThreshold in -1f..1f)
    checkActive()
    val changedId = canonicalSemanticId(changed.pageId)
    if (currentRevisions[changedId] != changed.revision) return ProposalWindow(emptyList(), null, stale = true)
    fun eligible(p: EmbeddedPassage, pageId: String): Boolean = p.passage.isBodyEvidence() &&
        canonicalSemanticId(p.passage.pageId) == pageId && p.vector.size == changed.identity.dimensions &&
        p.vector.all { it.isFinite() } && p.vector.any { it != 0f }
    fun validBodies(record: SemanticRecord, pageId: String): Boolean {
        val seen = mutableSetOf<String>()
        for (passage in record.passages) {
            checkActive()
            if (!passage.passage.isBodyEvidence()) continue
            if (passage.passage.id.isBlank() || !seen.add(passage.passage.id) || !eligible(passage, pageId)) return false
        }
        return true
    }
    if (!validBodies(changed, changedId)) return ProposalWindow(emptyList(), null, stale = true)
    val changedEligible = changed.passages.filter { eligible(it, changedId) }
        .sortedWith(compareBy<EmbeddedPassage> { it.passage.order }.thenBy { it.passage.id })
    fun similar(a: EmbeddedPassage, b: EmbeddedPassage): Boolean =
        runCatching { cosineSimilarity(a.vector, b.vector) >= similarityThreshold }.getOrDefault(false)
    // Similarity cannot establish that two changed passages express the same concept.
    val anchors = changedEligible.filter { it.passage.id in changedPassageIds && it.passage.id !in explainedPassageIds }
    require(anchorOffset <= anchors.size)
    val selected = anchors.drop(anchorOffset).take(maxAnchors)
    val next = (anchorOffset + selected.size).takeIf { it < anchors.size }
    if (selected.isEmpty()) return ProposalWindow(emptyList(), next)
    val expected = currentRevisions.filterKeys { it != changedId }
    val scopeFields = listOf("proposal-scan-v1", changedId, changed.revision, changed.identity.toString(),
        similarityThreshold.toString(), anchorOffset.toString(), maxAnchors.toString(), maxEvidence.toString(),
        structureComplete.toString(), "anchors", selected.size.toString()) + selected.map { it.passage.id } +
        listOf("changed", changedPassageIds.size.toString()) + changedPassageIds.sorted() +
        listOf("explained", explainedPassageIds.size.toString()) + explainedPassageIds.sorted() +
        listOf("links", explicitPageIds.size.toString()) + explicitPageIds.map(::canonicalSemanticId).sorted()
    val scopeKey = semanticHash(scopeFields.joinToString("") { "${it.length}:$it" })
    val contextFields = scopeFields + listOf("sources", currentRevisions.size.toString()) +
        currentRevisions.toSortedMap().entries.flatMap { listOf(it.key, it.value) }
    val contextKey = semanticHash(contextFields.joinToString("") { "${it.length}:$it" })
    val anchorIds = selected.map { it.passage.id }.toSet()
    if (previous != null && ((previous.contextKey != contextKey && previous.scopeKey != scopeKey) || previous.candidates.keys != anchorIds ||
        previous.truncatedAnchors.any { it !in anchorIds } || previous.scannedRevisions.any { (id, rev) -> expected[id] != rev } ||
        previous.candidates.values.any { list -> list.size > maxEvidence - 1 || list.any { candidate ->
            val ref = candidate.ref
            currentRevisions[ref.pageId] != ref.revision ||
                (ref.pageId != changedId && previous.scannedRevisions[ref.pageId] != ref.revision) ||
                ref.passageId.isBlank() || !candidate.score.isFinite() ||
                candidate.score !in -1f..2f || candidate.reason == ProposalReason.CHANGED_BODY
        } || list.map { it.ref.pageId to it.ref.passageId }.toSet().size != list.size }))
        return ProposalWindow(emptyList(), null, stale = true)
    val order = compareByDescending<ProposalScoredEvidence> { it.score }.thenBy { it.ref.pageId }.thenBy { it.ref.passageId }
    val candidates = selected.map { previous?.candidates?.get(it.passage.id)?.toMutableList() ?: mutableListOf() }
    val truncated = BooleanArray(selected.size) { selected[it].passage.id in previous?.truncatedAnchors.orEmpty() }
    val scanned = previous?.scannedRevisions?.toMutableMap() ?: mutableMapOf()
    fun offer(index: Int, candidate: ProposalScoredEvidence) {
        val list = candidates[index]
        if (list.any { it.ref.pageId == candidate.ref.pageId && it.ref.passageId == candidate.ref.passageId }) return
        list.add(candidate); list.sortWith(order)
        if (list.size > maxEvidence - 1) { list.removeAt(list.lastIndex); truncated[index] = true }
    }
    selected.forEachIndexed { index, anchor ->
        changedEligible.forEach { candidate ->
            checkActive()
            if (candidate.passage.id != anchor.passage.id && candidate.passage.sectionId == anchor.passage.sectionId &&
                candidate.passage.id !in explainedPassageIds && similar(anchor, candidate))
                offer(index, ProposalScoredEvidence(ProposalEvidenceRef(changedId, changed.revision, candidate.passage.id),
                    2f, ProposalReason.SAME_SECTION))
        }
    }
    val links = explicitPageIds.mapTo(hashSetOf(), ::canonicalSemanticId)
    var staleSource = false
    val iterator = olderRecords.iterator()
    var read = 0
    while (read < maxRecords && iterator.hasNext()) {
        checkActive()
        val record = iterator.next(); read++
        val page = canonicalSemanticId(record.pageId)
        if (page == changedId) continue
        if (record.identity != changed.identity || currentRevisions[page] != record.revision || !validBodies(record, page)) {
            staleSource = true; continue
        }
        scanned[page] = record.revision
        for (candidate in record.passages) {
            checkActive()
            if (!eligible(candidate, page)) continue
            selected.forEachIndexed { index, anchor ->
                val score = runCatching { cosineSimilarity(anchor.vector, candidate.vector) }.getOrNull() ?: return@forEachIndexed
                val explicit = page in links
                if (explicit || score >= similarityThreshold)
                    offer(index, ProposalScoredEvidence(ProposalEvidenceRef(page, record.revision, candidate.passage.id),
                        score, if (explicit) ProposalReason.EXPLICIT_REFERENCE else ProposalReason.SEMANTIC_NEIGHBOR))
            }
        }
    }
    val incompleteScan = !scanComplete || iterator.hasNext() || scanned != expected
    val checkpoint = ProposalScanCheckpoint(contextKey, scanned.toMap(), selected.mapIndexed { index, anchor ->
        anchor.passage.id to candidates[index].toList()
    }.toMap(), selected.indices.filter { truncated[it] }.mapTo(hashSetOf()) { selected[it].passage.id }, scopeKey)
    return ProposalWindow(selected.mapIndexed { index, anchor ->
        val evidence = listOf(ProposalEvidenceRef(changedId, changed.revision, anchor.passage.id)) + candidates[index].map { it.ref }
        val reasons = setOf(ProposalReason.CHANGED_BODY) + candidates[index].map { it.reason }
        val incomplete = buildSet {
            if (!structureComplete) add(ProposalIncompleteReason.STRUCTURE)
            if (incompleteScan) add(ProposalIncompleteReason.SOURCE_SCAN)
            if (staleSource) add(ProposalIncompleteReason.STALE_SOURCE)
            if (truncated[index]) add(ProposalIncompleteReason.EVIDENCE_LIMIT)
            if (next != null) add(ProposalIncompleteReason.ANCHOR_WINDOW)
        }
        val fields = listOf("incremental-proposal-v1", changed.identity.toString(), similarityThreshold.toString()) +
            evidence.flatMap { listOf(it.pageId, it.revision, it.passageId) } +
            reasons.map { it.name }.sorted() + incomplete.map { it.name }.sorted()
        IncrementalProposal(semanticHash(fields.joinToString("") { "${it.length}:$it" }), evidence, reasons, incomplete)
    }, next, checkpoint = checkpoint)
}
