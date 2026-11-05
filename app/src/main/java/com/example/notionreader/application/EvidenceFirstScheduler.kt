package com.example.notionreader.application

import com.example.notionreader.domain.discovery.blocksConceptAdmission

internal enum class EvidenceFirstScheduleResult { Idle, Progress, Blocked, Deferred }

/** One bounded operation per call; durable evidence is revalidated when this cursor is rebuilt. */
internal class EvidenceFirstScheduler {
    private data class Page(var offset: Int = 0, var index: Int = 0, var done: Boolean = false,
        var blocked: Boolean = false, var replay: ConceptScanReplay? = null,
        var work: PendingCorpusWork? = null, var candidate: PendingCorpusWorkResult.Done? = null,
        var admissionReplay: PendingCorpusReplay = PendingCorpusReplay.start(), var restart: Boolean = false, var staleRetries: Int = 0)
    private var scope: String? = null
    private val pages = sortedMapOf<String, Page>()
    private var current: String? = null
    private var last: String? = null
    private val retry = java.util.concurrent.atomic.AtomicLong()
    private var seenRetry = 0L
    private val resetGeneration = java.util.concurrent.atomic.AtomicLong()
    private var seenReset = 0L

    fun retryBlocked() { retry.incrementAndGet() }
    fun reset() { resetGeneration.incrementAndGet(); retryBlocked() }

    suspend fun step(scopeKey: String, sourceIds: List<String>,
        inspect: suspend (String, Int, ConceptScanReplay?, Boolean) -> EvidenceFirstNoteStep,
        process: suspend (PendingCorpusWork) -> PendingCorpusWorkResult,
        admit: suspend (PendingCorpusWorkResult.Done, PendingCorpusReplay) -> CanonicalAdmissionResult
    ): EvidenceFirstScheduleResult {
        val resetToken = resetGeneration.get()
        if (seenReset != resetToken) { scope = null; pages.clear(); current = null; last = null; seenReset = resetToken }
        if (scope != scopeKey || pages.keys != sourceIds.toSet()) {
            scope = scopeKey; pages.clear(); sourceIds.distinct().sorted().forEach { pages[it] = Page() }
            current = null; last = null
        }
        val generation = retry.get()
        if (seenRetry != generation) { pages.values.forEach { if (it.blocked) { it.blocked = false; it.staleRetries = 0; it.restart = false } }; seenRetry = generation }
        val available = pages.filterValues { !it.done && !it.blocked }.keys
        if (available.isEmpty()) return if (pages.values.any { it.blocked && !it.done })
            EvidenceFirstScheduleResult.Blocked else EvidenceFirstScheduleResult.Idle
        val id = current?.takeIf { it in available } ?: (available.firstOrNull { it > (last ?: "") } ?: available.first())
        current = id
        val page = pages.getValue(id)
        fun block(): EvidenceFirstScheduleResult {
            page.blocked = true; page.work = null; page.candidate = null; page.replay = null
            page.admissionReplay = PendingCorpusReplay.start(); current = null; last = id
            return if (pages.values.any { !it.done && !it.blocked }) EvidenceFirstScheduleResult.Progress else EvidenceFirstScheduleResult.Blocked
        }
        fun stale(): EvidenceFirstScheduleResult {
            if (page.staleRetries++ > 0) return block()
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
                    page.index++
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
                if (result.window.scanPending) EvidenceFirstScheduleResult.Progress
                else if (page.index < result.window.window.proposals.size) {
                    page.work = PendingCorpusWork.start(id, page.index, anchorOffset = page.offset)
                    EvidenceFirstScheduleResult.Progress
                } else {
                    val next = result.window.window.nextAnchorOffset
                    if (next != null && next <= page.offset) block()
                    else { if (next == null) { page.done = true; current = null; last = id }
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
