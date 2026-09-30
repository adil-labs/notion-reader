package com.example.notionreader.domain.queue

/**
 * Storage interface for Smart Queue definitions.
 */
interface SmartQueueStore {

    /**
     * Reads all stored Smart Queues, in no particular order.
     */
    suspend fun read(): List<SmartQueue>

    /**
     * Reads stored queues, applies [decide], and persists the result atomically.
     */
    suspend fun transact(decide: (List<SmartQueue>) -> Pair<List<SmartQueue>, SmartQueueEffect>): SmartQueueEffect

    suspend fun clear() {}
}
