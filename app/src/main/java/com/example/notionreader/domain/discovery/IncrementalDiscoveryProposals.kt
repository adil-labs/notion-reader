package com.example.notionreader.domain.discovery

data class ProposalEvidenceRef(val pageId: String, val revision: String, val passageId: String)
enum class ProposalReason { CHANGED_BODY, SAME_SECTION, SEMANTIC_NEIGHBOR, EXPLICIT_REFERENCE }
enum class ProposalIncompleteReason { STRUCTURE, SOURCE_SCAN, EVIDENCE_LIMIT, STALE_SOURCE, ANCHOR_WINDOW }
data class IncrementalProposal(val proposalKey: String, val evidence: List<ProposalEvidenceRef>, val reasons: Set<ProposalReason>,
    val incomplete: Set<ProposalIncompleteReason>, val policyVersion: Int = 1)
data class ProposalWindow(val proposals: List<IncrementalProposal>, val nextAnchorOffset: Int?, val stale: Boolean = false)

fun proposeDiscoveryWindow(changed: SemanticRecord, changedPassageIds: Set<String>,
    explainedPassageIds: Set<String>, olderRecords: Sequence<SemanticRecord>, currentRevisions: Map<String, String>,
    explicitPageIds: Set<String>, structureComplete: Boolean, scanComplete: Boolean,
    anchorOffset: Int = 0, maxAnchors: Int = 8, maxRecords: Int = 128, maxEvidence: Int = 4,
    similarityThreshold: Float = .85f, checkActive: () -> Unit = {}): ProposalWindow {
    require(anchorOffset >= 0 && maxAnchors in 1..128 && maxRecords in 1..4096 && maxEvidence in 1..32)
    require(similarityThreshold.isFinite() && similarityThreshold in -1f..1f)
    checkActive()
    val changedId = canonicalSemanticId(changed.pageId)
    if (currentRevisions[changedId] != changed.revision) return ProposalWindow(emptyList(), null, stale = true)
    fun eligible(p: EmbeddedPassage, pageId: String): Boolean = p.passage.isBodyEvidence() &&
        canonicalSemanticId(p.passage.pageId) == pageId && p.vector.size == changed.identity.dimensions &&
        p.vector.all { it.isFinite() } && p.vector.any { it != 0f }
    val changedEligible = changed.passages.filter { eligible(it, changedId) }
        .sortedWith(compareBy<EmbeddedPassage> { it.passage.order }.thenBy { it.passage.id })
    val anchors = changedEligible.filter { it.passage.id in changedPassageIds && it.passage.id !in explainedPassageIds }
        .distinctBy { it.passage.sectionId }
    require(anchorOffset <= anchors.size)
    val selected = anchors.drop(anchorOffset).take(maxAnchors)
    val next = (anchorOffset + selected.size).takeIf { it < anchors.size }
    if (selected.isEmpty()) return ProposalWindow(emptyList(), next)
    data class Candidate(val ref: ProposalEvidenceRef, val score: Float, val reason: ProposalReason)
    val order = compareByDescending<Candidate> { it.score }.thenBy { it.ref.pageId }.thenBy { it.ref.passageId }
    val candidates = selected.map { mutableListOf<Candidate>() }
    val truncated = BooleanArray(selected.size)
    fun offer(index: Int, candidate: Candidate) {
        val list = candidates[index]
        if (list.any { it.ref.pageId == candidate.ref.pageId && it.ref.passageId == candidate.ref.passageId }) return
        list.add(candidate); list.sortWith(order)
        if (list.size > maxEvidence - 1) { list.removeAt(list.lastIndex); truncated[index] = true }
    }
    selected.forEachIndexed { index, anchor ->
        changedEligible.forEach { candidate ->
            checkActive()
            if (candidate.passage.id != anchor.passage.id && candidate.passage.sectionId == anchor.passage.sectionId &&
                candidate.passage.id !in explainedPassageIds)
                offer(index, Candidate(ProposalEvidenceRef(changedId, changed.revision, candidate.passage.id),
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
        if (record.identity != changed.identity || currentRevisions[page] != record.revision) { staleSource = true; continue }
        for (candidate in record.passages) {
            checkActive()
            if (!eligible(candidate, page)) continue
            selected.forEachIndexed { index, anchor ->
                val score = runCatching { cosineSimilarity(anchor.vector, candidate.vector) }.getOrNull() ?: return@forEachIndexed
                val explicit = page in links
                if (explicit || score >= similarityThreshold)
                    offer(index, Candidate(ProposalEvidenceRef(page, record.revision, candidate.passage.id),
                        score, if (explicit) ProposalReason.EXPLICIT_REFERENCE else ProposalReason.SEMANTIC_NEIGHBOR))
            }
        }
    }
    val incompleteScan = !scanComplete || iterator.hasNext()
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
    }, next)
}
