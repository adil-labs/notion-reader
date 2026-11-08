package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*

internal enum class EvidenceFirstScheduleResult { Idle, Progress, Blocked, Deferred }

/**
 * Catalog identity relevant to proposal eligibility and duplicate/continuity admission.
 *
 * It covers the canonical readiness gate, each topic's existing-concept query/verifier/members
 * and the duplicate-continuity bookkeeping. It deliberately excludes connection progress and the
 * global generation: those do not change whether a proposal can be checked or admitted, and
 * including them would release blocked proposal work on unrelated activity.
 */
internal fun evidenceCatalogIdentity(snapshot: TopicSnapshot): String {
    val fields = mutableListOf("evidence-catalog-v2", snapshot.canonicalCatalogReady.toString())
    (snapshot.topics + snapshot.provisionalTopics)
        .sortedWith(compareBy<DiscoveredTopic> { it.id })
        .forEach { topic ->
            val concept = topic.canonicalConcept
            fields += topic.id
            fields += concept?.workKey.orEmpty()
            fields += concept?.name.orEmpty()
            fields += concept?.definition.orEmpty()
            fields += concept?.verifier?.toString().orEmpty()
            fields += concept?.queryEmbedding?.query?.key.orEmpty()
            fields += concept?.queryEmbedding?.query?.policy?.toString().orEmpty()
            concept?.queryEmbedding?.vector?.forEach { fields += it.toRawBits().toString() }
            fields += topic.currentGeneratedLabel?.workKey.orEmpty()
            fields += topic.currentCanonicalProjection?.workKey.orEmpty()
            topic.members.sortedBy { it.pageId }.forEach { member ->
                fields += member.pageId
                fields += member.revision
                fields += member.passage.id
            }
        }
    // Continuity is consulted by duplicate/continuity admission.
    snapshot.continuity.sortedBy { it.id }.forEach { continuity ->
        fields += continuity.id
        fields += continuity.createdOrder.toString()
        fields += continuity.canonicalKey.orEmpty()
        fields += continuity.replacementId.orEmpty()
        fields += continuity.anchorSourceId.orEmpty()
        continuity.pageIds.sorted().forEach { fields += it }
        continuity.sourceIds.sorted().forEach { fields += it }
    }
    return semanticHash(fields.joinToString("") { "${it.length}:$it" })
}

/** One bounded operation per call; durable evidence is revalidated when this cursor is rebuilt. */
internal class EvidenceFirstScheduler {
    private data class Page(var offset: Int = 0, var index: Int = 0, var done: Boolean = false,
        var blocked: Boolean = false, var replay: ConceptScanReplay? = null,
        var work: PendingCorpusWork? = null, var candidate: PendingCorpusWorkResult.Done? = null,
        var admissionReplay: PendingCorpusReplay = PendingCorpusReplay.start(), var restart: Boolean = false, var staleRetries: Int = 0,
        val admittedProposals: MutableSet<String> = mutableSetOf())
    private var scope: String? = null
    private val pages = sortedMapOf<String, Page>()
    private var current: String? = null
    private var last: String? = null
    private var catalogEpoch: Long? = null
    private var catalogIdentity: String? = null
    private val retry = java.util.concurrent.atomic.AtomicLong()
    private var seenRetry = 0L
    private val resetGeneration = java.util.concurrent.atomic.AtomicLong()
    private var seenReset = 0L

    /**
     * Automatic continuation resumes available/deferred work and preserves every page's durable
     * cursor. It must not reopen blocked proposal work; that is reserved for an explicit retry or
     * a relevant catalog-identity change.
     */
    fun continueAutomatically() { /* available pages already resume from their stored work */ }

    fun retryBlocked() { retry.incrementAndGet() }
    fun reset() { resetGeneration.incrementAndGet(); retryBlocked() }

    suspend fun step(scopeKey: String, sourceIds: List<String>,
        inspect: suspend (String, Int, ConceptScanReplay?, Boolean) -> EvidenceFirstNoteStep,
        process: suspend (PendingCorpusWork) -> PendingCorpusWorkResult,
        admit: suspend (PendingCorpusWorkResult.Done, PendingCorpusReplay) -> CanonicalAdmissionResult,
        catalogEpoch: Long? = null,
        catalogIdentity: String? = null
    ): EvidenceFirstScheduleResult {
        val diagnostics = kotlinx.coroutines.currentCoroutineContext()[DiscoveryWorkDiagnostics]
        diagnostics?.record("evidenceSchedulerTurns")
        val resetToken = resetGeneration.get()
        if (seenReset != resetToken) { scope = null; pages.clear(); current = null; last = null; seenReset = resetToken }
        if (scope != scopeKey || pages.keys != sourceIds.toSet()) {
            if (scope != null) diagnostics?.record("schedulerRestarts")
            scope = scopeKey; pages.clear(); sourceIds.distinct().sorted().forEach { pages[it] = Page() }
            current = null; last = null
        }
        if (this.catalogEpoch != catalogEpoch) {
            this.catalogEpoch = catalogEpoch
            pages.values.forEach { page ->
                page.replay = null
                page.staleRetries = 0
            }
        }
        if (this.catalogIdentity != catalogIdentity) {
            this.catalogIdentity = catalogIdentity
            // Proposal work re-checks the current canonical topic catalog for existing concepts
            // and duplicate safety. A connection-only generation bump changes the epoch but not
            // this identity, so it does not reopen a proposal block.
            pages.values.forEach { page -> page.blocked = false }
        }
        val retryGeneration = retry.get()
        if (seenRetry != retryGeneration) { pages.values.forEach { if (it.blocked) { it.blocked = false; it.staleRetries = 0; it.restart = false } }; seenRetry = retryGeneration }
        val available = pages.filterValues { !it.done && !it.blocked }.keys
        if (available.isEmpty()) return if (pages.values.any { it.blocked && !it.done })
            EvidenceFirstScheduleResult.Blocked else EvidenceFirstScheduleResult.Idle
        val id = current?.takeIf { it in available } ?: (available.firstOrNull { it > (last ?: "") } ?: available.first())
        current = id
        val page = pages.getValue(id)
        fun block(): EvidenceFirstScheduleResult {
            page.blocked = true; page.work = null; page.candidate = null; page.replay = null
            page.admissionReplay = PendingCorpusReplay.start(); current = null; last = id
            diagnostics?.record("evidencePageBlocks")
            return if (pages.values.any { !it.done && !it.blocked }) EvidenceFirstScheduleResult.Progress else EvidenceFirstScheduleResult.Blocked
        }
        fun stale(): EvidenceFirstScheduleResult {
            diagnostics?.record("staleResultDiscards")
            if (page.staleRetries++ > 0) return block()
            diagnostics?.record("schedulerRestarts")
            page.work = null; page.candidate = null; page.replay = null; page.restart = true
            page.admissionReplay = PendingCorpusReplay.start()
            return EvidenceFirstScheduleResult.Progress
        }
        page.candidate?.let { candidate ->
            val result = admit(candidate, page.admissionReplay)
            if (resetGeneration.get() != resetToken) return EvidenceFirstScheduleResult.Deferred
            return when (result) {
                is CanonicalAdmissionResult.Pending -> { page.admissionReplay = result.replay; EvidenceFirstScheduleResult.Progress }
                is CanonicalAdmissionResult.Admitted -> {
                    page.candidate = null
                    page.admissionReplay = PendingCorpusReplay.start()
                    page.admittedProposals += candidate.candidate.proposal.proposalKey
                    // Admission can remove explained anchors from the next proposal window.
                    // Numeric offsets belong to the old window, so rebuild once for this
                    // admission and skip its exact proposal if it remains eligible.
                    page.offset = 0
                    page.index = 0
                    page.replay = null
                    page.restart = true
                    EvidenceFirstScheduleResult.Progress
                }
                CanonicalAdmissionResult.Deferred -> EvidenceFirstScheduleResult.Deferred
                CanonicalAdmissionResult.Stale -> stale()
                else -> block()
            }
        }
        page.work?.let { work ->
            val result = process(work)
            if (resetGeneration.get() != resetToken) return EvidenceFirstScheduleResult.Deferred
            return when (result) {
                is PendingCorpusWorkResult.Pending -> { page.work = result.work; EvidenceFirstScheduleResult.Progress }
                is PendingCorpusWorkResult.Done -> { page.candidate = result; page.work = null; EvidenceFirstScheduleResult.Progress }
                is PendingCorpusWorkResult.Abstained -> if (result.scanPending || result.reasons.any { it.blocksConceptAdmission() }) block()
                    else { page.work = null; page.index++; EvidenceFirstScheduleResult.Progress }
                is PendingCorpusWorkResult.Stopped -> when (result.reason) {
                    EvidenceFirstNoteStep.Deferred -> EvidenceFirstScheduleResult.Deferred
                    EvidenceFirstNoteStep.Stale -> stale()
                    else -> block()
                }
                is PendingCorpusWorkResult.Incomplete -> block()
            }
        }
        val restart = page.restart
        page.restart = false
        val result = inspect(id, page.offset, page.replay, restart)
        if (resetGeneration.get() != resetToken) return EvidenceFirstScheduleResult.Deferred
        return when (result) {
            EvidenceFirstNoteStep.PendingScan -> EvidenceFirstScheduleResult.Progress
            is EvidenceFirstNoteStep.PendingReplay -> { page.replay = result.replay; EvidenceFirstScheduleResult.Progress }
            is EvidenceFirstNoteStep.Proposals -> {
                while (page.index < result.window.window.proposals.size &&
                    result.window.window.proposals[page.index].proposalKey in page.admittedProposals) page.index++
                if (result.window.scanPending) EvidenceFirstScheduleResult.Progress
                else if (page.index < result.window.window.proposals.size) {
                    page.work = PendingCorpusWork.start(id, page.index, anchorOffset = page.offset)
                    diagnostics?.record("evidenceProposalWindows")
                    EvidenceFirstScheduleResult.Progress
                } else {
                    val next = result.window.window.nextAnchorOffset
                    if (next != null && next <= page.offset) block()
                    else { if (next == null) { page.done = true; current = null; last = id; diagnostics?.record("evidencePagesCompleted") }
                        else { page.offset = next; page.index = 0; page.replay = null }
                        EvidenceFirstScheduleResult.Progress }
                }
            }
            EvidenceFirstNoteStep.Deferred -> { page.restart = restart; EvidenceFirstScheduleResult.Deferred }
            EvidenceFirstNoteStep.Stale -> stale()
            else -> block()
        }
    }
}
