package com.example.notionreader.application

import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.domain.discovery.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Explicit interest persistence and matching; never owns playback or background loops. */
internal class DiscoveryInterests(
    private val cache: DocumentCache,
    private val interestStore: DiscoveryInterestStore,
    private val provider: EmbeddingProvider,
    private val mutable: MutableStateFlow<DiscoveryState>,
    private val runtime: DiscoveryRuntime,
    private val retrieval: DiscoveryRetrieval,
    private val workDispatcher: CoroutineDispatcher,
    private val modelAvailable: () -> Boolean,
    private val recommendationThreshold: Float,
    private val clock: () -> Instant
) {
    private val interestGeneration = AtomicLong()
    private val matching = DiscoveryRequests(runtime.validity)
    private val interestWrites = Mutex()
    private val state get() = mutable

    fun resetRequests() = matching.reset()

    suspend fun loadInterestMatches(id: String, force: Boolean = false) {
        val request = matching.begin()
        val interestEpoch = interestGeneration.get()
        fun publishMatch(transform: (DiscoveryState) -> DiscoveryState) = matching.publish(mutable, request) {
            if (interestEpoch == interestGeneration.get()) transform(it) else it
        }
        try {
            withContext(workDispatcher) {
                if (!matching.isCurrent(request)) return@withContext
                val interest = interestStore.read().firstOrNull { it.id == id }
                if (!matching.isCurrent(request)) return@withContext
                val key = interest?.let { InterestMatchKey(it, request.source.cacheRevision, request.source.reset,
                    state.value.indexedRevision, provider.identity) }
                val current = state.value.interestMatch
                val ready = current.outcome as? InterestMatchOutcome.Ready
                if (!force && ready != null && current.interestId == id && ready.key == key) return@withContext
                publishMatch {
                    it.copy(interestMatch = InterestMatchState(id, InterestMatchOutcome.Loading))
                }
                if (interest == null || !modelAvailable()) {
                    val message = if (interest == null) "Interest unavailable." else "Set up Discovery in Settings to find matching Notes."
                    publishMatch {
                        it.copy(interestMatch = InterestMatchState(id, InterestMatchOutcome.Unavailable(message)))
                    }
                    return@withContext
                }
                val matches = runtime.foregroundStep {
                    val vector = if (interest.seedPageId != null) retrieval.seedVector(interest.seedPageId)
                        else runtime.inference.withLock { runtime.embedExactInput(interest.text) }
                    vector?.let { retrieval.retrieve(it, BrowseQuery.Default, 20, interest.seedPageId, recommendationThreshold) }
                }
                publishMatch {
                    val outcome = if (matches == null) InterestMatchOutcome.Unavailable(
                        if (interest.seedPageId != null && canonicalSemanticId(interest.seedPageId) in it.cachedPageIds)
                            "Seed Note is cached, but no indexed content is available for matching. Check Discovery settings."
                        else "Seed Note unavailable. Recache it in Browse to find matching Notes.")
                    else InterestMatchOutcome.Ready(requireNotNull(key), matches,
                        if (matches.isEmpty()) "No matching indexed Notes for this interest. Try editing it or caching more Notes." else null)
                    it.copy(interestMatch = InterestMatchState(id, outcome))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            publishMatch {
                it.copy(interestMatch = InterestMatchState(id,
                    InterestMatchOutcome.Failed("Matching Notes are unavailable. Try again.")))
            }
        } finally {
            matching.finish(mutable, request) {
                if (it.interestMatch.interestId == id && it.interestMatch.loading)
                    it.copy(interestMatch = InterestMatchState(id)) else it
            }
        }
    }
    suspend fun followTopic(topicId: String, name: String, text: String) = interestWrites.withLock {
        if (name.isBlank() || text.isBlank()) return@withLock
        val current = interestStore.read()
        if (current.any { it.originTopicId == topicId }) return@withLock
        if (state.value.topicSnapshot.topics.none { it.id == topicId }) return@withLock
        val interest = DiscoveryInterest(UUID.randomUUID().toString(), text.trim(), displayName = name.trim(), originTopicId = topicId)
        val updated = current + interest
        interestStore.write(updated); interestGeneration.incrementAndGet()
        mutable.update { it.copy(interests = updated) }
    }
    suspend fun updateFollow(id: String, name: String, text: String) = interestWrites.withLock {
        if (name.isBlank() || text.isBlank()) return@withLock
        val current = interestStore.read()
        val updated = current.map {
            if (it.id == id && it.seedPageId == null) it.copy(displayName = name.trim(), text = text.trim()) else it
        }
        if (updated == current) return@withLock
        interestStore.write(updated); interestGeneration.incrementAndGet()
        if (mutable.value.interestMatch.interestId == id) matching.reset()
        mutable.update { it.copy(interests = updated, interestMatch = if (it.interestMatch.interestId == id) InterestMatchState(id) else it.interestMatch) }
    }
    suspend fun refreshRecommendations() {
        withContext(workDispatcher) { refreshRecommendationsInBackground() }
    }
    private suspend fun refreshRecommendationsInBackground() {
        if (!modelAvailable()) return
        val epoch = runtime.generation.get()
        val interestsEpoch = interestGeneration.get()
        val sourceRevision = cache.revision.value
        try {
            val recommendations = mutableListOf<SemanticMatch>()
            val olderMatches = mutableListOf<SemanticMatch>()
            val cutoff = clock().minusSeconds(90L * 86400)
            for (interest in interestStore.read()) {
                val vector = if (interest.seedPageId != null) retrieval.seedVector(interest.seedPageId) else {
                    runtime.foregroundStep { runtime.inference.withLock { runtime.embedExactInput(interest.text) } }
                }
                if (vector != null) {
                    recommendations += retrieval.retrieve(vector, BrowseQuery.Default, 5, interest.seedPageId, recommendationThreshold)
                    olderMatches += retrieval.retrieve(vector, BrowseQuery.Default, 5, interest.seedPageId, recommendationThreshold, olderThan = cutoff)
                }
            }
            val ordered = recommendations.sortedWith(compareByDescending<SemanticMatch> { it.score }.thenBy { it.pageId }).distinctBy { it.pageId }
            val older = olderMatches.sortedWith(compareByDescending<SemanticMatch> { it.score }.thenBy { it.pageId }).distinctBy { it.pageId }.take(5)
            mutable.update {
                if (epoch == runtime.generation.get() && interestsEpoch == interestGeneration.get() && sourceRevision == cache.revision.value)
                    it.copy(recommendations = ordered.take(5), older = older) else it
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update {
            if (epoch == runtime.generation.get() && interestsEpoch == interestGeneration.get() && sourceRevision == cache.revision.value)
                it.copy(message = "Interest recommendations are unavailable. Try again.") else it
        } }
    }

    suspend fun saveInterest(id: String?, text: String, seedPageId: String? = null) = interestWrites.withLock {
        val label = text.trim()
        if (seedPageId == null && label.isBlank()) return@withLock
        val current = interestStore.read()
        val old = current.firstOrNull { it.id == id }
        val interest = DiscoveryInterest(id ?: UUID.randomUUID().toString(), if (seedPageId == null) label else "", seedPageId?.let(::canonicalSemanticId),
            old?.displayName, old?.originTopicId)
        if (old == interest) return@withLock
        val updated = if (old == null) current + interest else current.map { if (it.id == interest.id) interest else it }
        interestStore.write(updated)
        interestGeneration.incrementAndGet()
        if (mutable.value.interestMatch.interestId == interest.id) matching.reset()
        mutable.update { it.copy(interests = updated, interestMatch = if (it.interestMatch.interestId == interest.id) InterestMatchState(interest.id) else it.interestMatch) }
    }
    suspend fun removeInterest(id: String) = interestWrites.withLock {
        runtime.embeddingMemo.clear()
        val updated = interestStore.read().filterNot { it.id == id }
        interestStore.write(updated)
        interestGeneration.incrementAndGet()
        if (mutable.value.interestMatch.interestId == id) matching.reset()
        mutable.update { it.copy(interests = updated, recommendations = emptyList(), older = emptyList(),
            interestMatch = if (it.interestMatch.interestId == id) InterestMatchState() else it.interestMatch) }
    }
    suspend fun clearInterests() = interestWrites.withLock { runtime.embeddingMemo.clear(); interestStore.write(emptyList()); interestGeneration.incrementAndGet(); matching.reset(); mutable.update { it.copy(interests = emptyList(), recommendations = emptyList(), older = emptyList(), interestMatch = InterestMatchState()) } }
}
