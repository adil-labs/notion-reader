package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*

internal enum class CanonicalMembershipScheduleResult { Idle, Progress, Blocked, Deferred }

internal fun canonicalMembershipNeedsWork(snapshot: TopicSnapshot, sources: Map<String, String>,
    verifier: TopicLabelIdentity?, fullCoverage: Boolean = true): Boolean = snapshot.topics.mapNotNull { it.canonicalConcept }.any { concept ->
    verifier != null && concept.verifier != verifier ||
        fullCoverage && sources.isNotEmpty() && !concept.matchingComplete ||
        sources.any { (id, revision) -> concept.scannedRevisions[id] != revision }
}

/** One bounded operation per call, owned by the existing Discovery reconciliation loop. */
internal class CanonicalMembershipScheduler {
    private data class Active(val pageId: String, val scope: String, val saved: ExistingConceptScanRecord? = null,
        val replay: ConceptScanReplay = ConceptScanReplay.start(), val restart: Boolean = false, val staleRetries: Int = 0)
    private var active: Active? = null
    private var lastPage: String? = null
    private var blockedScope: String? = null
    private val blocked = mutableSetOf<String>()
    private val retryGeneration = java.util.concurrent.atomic.AtomicLong()
    fun retryBlocked() { retryGeneration.incrementAndGet() }
    fun reset() { active = null; lastPage = null; blockedScope = null; blocked.clear(); retryBlocked() }

    suspend fun step(snapshot: TopicSnapshot, sources: Map<String, String>, verifier: TopicLabelIdentity,
        scan: suspend (String, Boolean) -> DurableConceptScanResult,
        commit: suspend (String, ExistingConceptScanRecord, ConceptScanReplay, Int) -> CanonicalMembershipUpdateResult,
        fullCoverage: Boolean = true
    ): CanonicalMembershipScheduleResult {
        val concepts = snapshot.topics.mapNotNull { it.canonicalConcept }
        if (concepts.isEmpty()) { active = null; return CanonicalMembershipScheduleResult.Idle }
        if ((!snapshot.complete && !snapshot.canonicalCatalogReady) || concepts.any { it.verifier != verifier }) return CanonicalMembershipScheduleResult.Blocked
        val firstSource = sources.keys.minOrNull()
        val pending = sources.toSortedMap().filter { (id, revision) -> concepts.any {
            it.scannedRevisions[id] != revision || fullCoverage && !it.matchingComplete && it.scannedRevisions == sources && id == firstSource
        } }.keys.toList()
        if (pending.isEmpty()) { active = null; return CanonicalMembershipScheduleResult.Idle }
        val fields = listOf("canonical-membership-schedule-v1", snapshot.generation.toString(), snapshot.identity.toString(),
            verifier.toString(), retryGeneration.get().toString()) +
            sources.toSortedMap().flatMap { listOf(it.key, it.value) } + snapshot.topics.flatMap { topic ->
                listOf(topic.id, topic.canonicalConcept?.workKey.orEmpty(), topic.canonicalConcept?.queryEmbedding?.query?.key.orEmpty())
            }
        val scope = semanticHash(fields.joinToString("") { "${it.length}:$it" })
        if (blockedScope != scope) { blockedScope = scope; blocked.clear() }
        val available = pending.filterNot { it in blocked }
        if (available.isEmpty()) return CanonicalMembershipScheduleResult.Blocked
        fun block(pageId: String): CanonicalMembershipScheduleResult {
            blocked += pageId; lastPage = pageId; active = null
            return if (pending.any { it !in blocked }) CanonicalMembershipScheduleResult.Progress else CanonicalMembershipScheduleResult.Blocked
        }
        var work = active?.takeIf { it.pageId in available && it.scope == scope }
        if (work == null) {
            val page = available.firstOrNull { it > (lastPage ?: "") } ?: available.first()
            work = Active(page, scope)
            active = work
        }
        if (work.saved == null) {
            return when (val result = scan(work.pageId, work.restart)) {
                DurableConceptScanResult.Pending -> {
                    active = work.copy(restart = false); CanonicalMembershipScheduleResult.Progress
                }
                is DurableConceptScanResult.AwaitingValidation -> {
                    active = work.copy(saved = result.record, restart = false); CanonicalMembershipScheduleResult.Progress
                }
                DurableConceptScanResult.Stale -> {
                    active = work.copy(saved = null, replay = ConceptScanReplay.start(), restart = true, staleRetries = work.staleRetries + 1)
                    if (work.staleRetries == 0) CanonicalMembershipScheduleResult.Progress else block(work.pageId)
                }
                DurableConceptScanResult.Deferred -> CanonicalMembershipScheduleResult.Deferred
                else -> block(work.pageId)
            }
        }
        return when (val result = commit(work.pageId, work.saved, work.replay, 8)) {
            is CanonicalMembershipUpdateResult.Pending -> {
                active = work.copy(replay = result.replay); CanonicalMembershipScheduleResult.Progress
            }
            CanonicalMembershipUpdateResult.Updated -> {
                lastPage = work.pageId; active = null; CanonicalMembershipScheduleResult.Progress
            }
            CanonicalMembershipUpdateResult.Stale -> {
                active = work.copy(saved = null, replay = ConceptScanReplay.start(), restart = true, staleRetries = work.staleRetries + 1)
                if (work.staleRetries == 0) CanonicalMembershipScheduleResult.Progress else block(work.pageId)
            }
            CanonicalMembershipUpdateResult.Deferred -> CanonicalMembershipScheduleResult.Deferred
            else -> block(work.pageId)
        }
    }
}
