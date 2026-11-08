package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.currentCoroutineContext

/** Membership work is bounded Note-concept scanning; tail work is the later exhaustive pass. */
internal enum class MembershipWorkKind {
    /** Find the first independently verified supporting passage for an unresolved Note. */
    MEMBERSHIP,
    /** Re-examine the deliberately unchecked candidate tail of an already verified Note. */
    TAIL
}

internal data class MembershipWork(val pageId: String, val kind: MembershipWorkKind)

internal enum class CanonicalMembershipScheduleResult { Idle, Progress, Blocked, Deferred }

/**
 * Derived membership work. Notes that already have a terminal scan are not membership work even
 * when they do not support the concept, and unresolved `UNCERTAIN` Notes are finished scans rather
 * than pending work. Only two facts create work: an unscanned eligible Note, or an unchecked tail.
 */
internal fun canonicalMembershipWork(snapshot: TopicSnapshot, sources: Map<String, String>,
    verifier: TopicLabelIdentity): List<MembershipWork> {
    val concepts = snapshot.topics.mapNotNull { it.canonicalConcept }
    if (concepts.isEmpty() || concepts.any { it.verifier != verifier }) return emptyList()
    val membership = sources.toSortedMap().keys.mapNotNull { id ->
        val revision = sources.getValue(id)
        id.takeIf { concepts.any { concept -> concept.scannedRevisions[id] != revision } }?.let {
            MembershipWork(it, MembershipWorkKind.MEMBERSHIP)
        }
    }
    val tails = sources.toSortedMap().keys.mapNotNull { id ->
        val revision = sources.getValue(id)
        id.takeIf { concepts.any { concept -> concept.uncheckedRevisions[id] == revision } }?.let {
            MembershipWork(it, MembershipWorkKind.TAIL)
        }
    }
    val membershipIds = membership.mapTo(hashSetOf()) { it.pageId }
    return membership + tails.filterNot { it.pageId in membershipIds }
}

internal fun canonicalMembershipNeedsWork(snapshot: TopicSnapshot, sources: Map<String, String>,
    verifier: TopicLabelIdentity?): Boolean {
    if (verifier == null) return false
    // A changed verifier invalidates every affected checkpoint. That is blocked work with a
    // revalidation dependency, not a silent idle state.
    if (snapshot.topics.any { it.canonicalConcept?.verifier != null && it.canonicalConcept.verifier != verifier }) return true
    return canonicalMembershipWork(snapshot, sources, verifier).isNotEmpty()
}

/**
 * Complete dependency identity of one membership unit. A block is reopened only when something
 * the scan actually depends on changes: its Note revision, the relevant concept identity, the
 * concept query/vector, the verifier, the retrieval policy, or the concept's coverage state.
 * An unrelated catalog generation change leaves the block in place.
 */
internal fun membershipDependency(work: MembershipWork, snapshot: TopicSnapshot, sources: Map<String, String>,
    verifier: TopicLabelIdentity, retrievalPolicyIdentity: String): String {
    val revision = sources[work.pageId] ?: return semanticHash("membership-dependency-v1|removed")
    val relevant = snapshot.topics.sortedWith(compareBy<DiscoveredTopic> { it.createdOrder }.thenBy { it.id })
        .mapNotNull { topic ->
            val concept = topic.canonicalConcept ?: return@mapNotNull null
            val applies = when (work.kind) {
                MembershipWorkKind.MEMBERSHIP -> concept.scannedRevisions[work.pageId] != revision
                MembershipWorkKind.TAIL -> concept.uncheckedRevisions[work.pageId] == revision
            }
            if (applies) topic to concept else null
        }
    val fields = mutableListOf("membership-dependency-v1", work.kind.name, work.pageId, revision,
        verifier.toString(), retrievalPolicyIdentity)
    for ((topic, concept) in relevant) {
        fields += topic.id
        fields += topic.createdOrder.toString()
        fields += concept.workKey
        fields += concept.name
        fields += concept.definition
        fields += concept.verifier.toString()
        fields += concept.queryEmbedding?.query?.key.orEmpty()
        fields += concept.queryEmbedding?.query?.policy?.toString().orEmpty()
        concept.queryEmbedding?.vector?.forEach { fields += it.toRawBits().toString() }
        fields += concept.scannedRevisions[work.pageId].orEmpty()
        fields += concept.uncheckedRevisions[work.pageId].orEmpty()
        fields += concept.uncertainRevisions[work.pageId].orEmpty()
    }
    return semanticHash(fields.joinToString("") { "${it.length}:$it" })
}

/** One bounded operation per call, owned by the existing Discovery reconciliation loop. */
internal class CanonicalMembershipScheduler {
    private data class Active(val work: MembershipWork, val scope: String,
        val saved: ExistingConceptScanRecord? = null, val replay: ConceptScanReplay = ConceptScanReplay.start(),
        val restart: Boolean = false, val staleRetries: Int = 0, val unchangedRetries: Int = 0)
    private var active: Active? = null
    private var lastPage: String? = null
    /** Work → its complete dependency identity at the time it was blocked. */
    private val blocked = mutableMapOf<MembershipWork, String>()
    private var membershipTurnsSinceTail = 0

    /**
     * Automatic wake-ups change nothing about a work dependency. Active continuation state,
     * blocked decisions and durable checkpoints are preserved; the next `step` continues the
     * selected work from its in-memory or durable cursor.
     */
    fun continueAutomatically() { /* no dependency changed */ }

    /** Explicit user retry reopens recoverable blocked work; active work is untouched. */
    fun retryBlocked() { blocked.clear() }

    fun reset() {
        active = null; lastPage = null; blocked.clear()
        membershipTurnsSinceTail = 0
    }

    suspend fun step(snapshot: TopicSnapshot, sources: Map<String, String>, verifier: TopicLabelIdentity,
        retrievalPolicyIdentity: String = "",
        scan: suspend (String, Boolean) -> DurableConceptScanResult,
        commit: suspend (String, ExistingConceptScanRecord, ConceptScanReplay, Int) -> CanonicalMembershipUpdateResult
    ): CanonicalMembershipScheduleResult {
        val diagnostics = currentCoroutineContext()[DiscoveryWorkDiagnostics]
        diagnostics?.record("membershipSchedulerTurns")
        val concepts = snapshot.topics.mapNotNull { it.canonicalConcept }
        if (concepts.isEmpty()) { active = null; diagnostics?.record("membershipIdle"); return CanonicalMembershipScheduleResult.Idle }
        if ((!snapshot.complete && !snapshot.canonicalCatalogReady) || concepts.any { it.verifier != verifier }) {
            diagnostics?.record("membershipBlocked")
            return CanonicalMembershipScheduleResult.Blocked
        }
        val pending = canonicalMembershipWork(snapshot, sources, verifier)
        diagnostics?.record("membershipPendingWork", pending.size.toLong())
        diagnostics?.record("membershipPendingMembership", pending.count { it.kind == MembershipWorkKind.MEMBERSHIP }.toLong())
        diagnostics?.record("membershipPendingTails", pending.count { it.kind == MembershipWorkKind.TAIL }.toLong())
        if (pending.isEmpty()) { active = null; diagnostics?.record("membershipIdle"); return CanonicalMembershipScheduleResult.Idle }
        // The active-work scope is the actual membership dependency: model identity, verifier,
        // retrieval policy, sources and the topic/concept/query identities. The global catalog
        // generation and retry counters are deliberately excluded: they change for unrelated
        // commits/wake-ups, while the durable checkpoint/replay validates any relevant change.
        val fields = listOf("canonical-membership-schedule-v3", snapshot.identity.toString(),
            verifier.toString(), retrievalPolicyIdentity) +
            sources.toSortedMap().flatMap { listOf(it.key, it.value) } + snapshot.topics.flatMap { topic ->
                listOf(topic.id, topic.canonicalConcept?.workKey.orEmpty(), topic.canonicalConcept?.queryEmbedding?.query?.key.orEmpty())
            }
        val scope = semanticHash(fields.joinToString("") { "${it.length}:$it" })
        // Reopen only the work whose complete dependency identity changed. This preserves blocks
        // for an unchanged Note while allowing a concept, query, verifier, policy or source change
        // (or removal) to release them.
        blocked.keys.removeAll { work ->
            work !in pending || blocked[work] != membershipDependency(work, snapshot, sources, verifier, retrievalPolicyIdentity)
        }
        val available = pending.filterNot { it in blocked }
        if (available.isEmpty()) { diagnostics?.record("membershipBlocked"); return CanonicalMembershipScheduleResult.Blocked }

        fun block(work: MembershipWork): CanonicalMembershipScheduleResult {
            blocked[work] = membershipDependency(work, snapshot, sources, verifier, retrievalPolicyIdentity)
            lastPage = work.pageId; active = null
            return if (pending.any { it !in blocked }) CanonicalMembershipScheduleResult.Progress
            else CanonicalMembershipScheduleResult.Blocked
        }
        val membership = available.filter { it.kind == MembershipWorkKind.MEMBERSHIP }
        val tails = available.filter { it.kind == MembershipWorkKind.TAIL }
        // Bounded fairness: after two completed membership turns, even an in-flight membership
        // scan yields so an eligible tail gets one bounded opportunity.
        if (tails.isNotEmpty() && membershipTurnsSinceTail >= 2 && active?.work?.kind == MembershipWorkKind.MEMBERSHIP)
            active = null
        val selected = active?.takeIf { it.work in available && it.scope == scope }?.work ?: run {
            val rotational = if (tails.isNotEmpty() && membershipTurnsSinceTail >= 2) tails
            else if (membership.isNotEmpty()) membership else tails
            val page = rotational.firstOrNull { it.pageId > (lastPage ?: "") } ?: rotational.first()
            active = null
            page
        }
        if (selected.kind == MembershipWorkKind.MEMBERSHIP) membershipTurnsSinceTail++
        else membershipTurnsSinceTail = 0
        diagnostics?.record(if (selected.kind == MembershipWorkKind.MEMBERSHIP) "membershipWorkTurns" else "tailWorkTurns")
        val work = active?.takeIf { it.work == selected && it.scope == scope }
            ?: Active(selected, scope).also { active = it }
        // The scan function owns durable tail semantics: a partial tail checkpoint resumes, a
        // completed early-support checkpoint with a pending tail restarts exhaustively, and a
        // completed exhaustive checkpoint is committed without rescanning. Only a stale result
        // sets this flag, forcing a deliberate restart.
        val restart = work.restart
        fun finishTail() {
            if (work.work.kind != MembershipWorkKind.TAIL) return
            membershipTurnsSinceTail = 0
            active = null
        }
        var recorded = work.saved
        if (recorded == null) {
            when (val result = scan(work.work.pageId, restart)) {
                DurableConceptScanResult.Pending -> {
                    if (work.work.kind == MembershipWorkKind.TAIL) finishTail()
                    else active = work.copy(restart = false)
                    return CanonicalMembershipScheduleResult.Progress
                }
                is DurableConceptScanResult.AwaitingValidation -> {
                    recorded = result.record
                    if (work.work.kind == MembershipWorkKind.TAIL) {
                        // A completed tail can commit in the same bounded step; no extra rescan.
                    } else {
                        active = work.copy(saved = recorded, restart = false)
                        return CanonicalMembershipScheduleResult.Progress
                    }
                }
                DurableConceptScanResult.Stale -> {
                    if (work.work.kind == MembershipWorkKind.TAIL) {
                        finishTail()
                    } else {
                        active = work.copy(saved = null, replay = ConceptScanReplay.start(), restart = true,
                            staleRetries = work.staleRetries + 1)
                    }
                    return if (work.staleRetries == 0) CanonicalMembershipScheduleResult.Progress else block(work.work)
                }
                DurableConceptScanResult.Deferred -> {
                    finishTail()
                    return CanonicalMembershipScheduleResult.Deferred
                }
                else -> return block(work.work)
            }
        }
        return when (val result = commit(work.work.pageId, checkNotNull(recorded), work.replay, 8)) {
            is CanonicalMembershipUpdateResult.Pending -> {
                // A bounded tail replay must continue from its cursor. Clearing a tail's active
                // state here restarts a large replay from its first page every step, so the tail
                // never commits. Keep the completed record and replay until the commit finishes.
                if (work.work.kind == MembershipWorkKind.TAIL) {
                    active = work.copy(saved = recorded, replay = result.replay)
                } else {
                    active = work.copy(replay = result.replay)
                }
                CanonicalMembershipScheduleResult.Progress
            }
            CanonicalMembershipUpdateResult.Updated, CanonicalMembershipUpdateResult.Unchanged -> {
                if (result == CanonicalMembershipUpdateResult.Unchanged && work.unchangedRetries >= 1) return block(work.work)
                lastPage = work.work.pageId
                if (work.work.kind == MembershipWorkKind.TAIL) {
                    finishTail()
                } else {
                    active = if (result == CanonicalMembershipUpdateResult.Unchanged)
                        work.copy(saved = null, replay = ConceptScanReplay.start(), unchangedRetries = work.unchangedRetries + 1)
                    else null
                }
                CanonicalMembershipScheduleResult.Progress
            }
            CanonicalMembershipUpdateResult.Stale -> {
                // Preserve the forced restart for both kinds. Clearing a tail's active state here
                // loses the restart flag, so a completed-but-stale tail loops AwaitingValidation ->
                // Stale forever without ever rescanning under the current scope.
                active = work.copy(saved = null, replay = ConceptScanReplay.start(), restart = true,
                    staleRetries = work.staleRetries + 1)
                if (work.work.kind == MembershipWorkKind.TAIL) membershipTurnsSinceTail = 0
                if (work.staleRetries == 0) CanonicalMembershipScheduleResult.Progress else block(work.work)
            }
            CanonicalMembershipUpdateResult.Deferred -> {
                finishTail()
                CanonicalMembershipScheduleResult.Deferred
            }
            else -> block(work.work)
        }
    }
}
