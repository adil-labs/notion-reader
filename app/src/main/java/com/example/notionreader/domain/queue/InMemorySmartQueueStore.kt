package com.example.notionreader.domain.queue

/**
 * In-memory test double for [SmartQueueStore].
 */
class InMemorySmartQueueStore(initial: List<SmartQueue> = emptyList()) : SmartQueueStore {

    private var data: List<SmartQueue> = initial

    override suspend fun read(): List<SmartQueue> = data

    override suspend fun transact(
        decide: (List<SmartQueue>) -> Pair<List<SmartQueue>, SmartQueueEffect>
    ): SmartQueueEffect {
        val (next, effect) = decide(data)
        data = next
        return effect
    }
}
