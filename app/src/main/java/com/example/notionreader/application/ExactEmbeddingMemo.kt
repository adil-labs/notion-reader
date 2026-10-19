package com.example.notionreader.application

import com.example.notionreader.domain.discovery.EmbeddingIdentity

/** Process-only exact reuse. Current passage evidence is always supplied by the caller. */
internal class ExactEmbeddingMemo(private val maxEntries: Int = 128, private val maxInputCharacters: Int = 262144) {
    private data class Key(val identity: EmbeddingIdentity, val input: String)
    private val entries = LinkedHashMap<Key, FloatArray>(16, .75f, true)
    private var characters = 0
    private var generation = 0L
    @Synchronized fun token(): Long = generation
    init { require(maxEntries > 0 && maxInputCharacters > 0) }
    @Synchronized fun get(identity: EmbeddingIdentity, input: String): List<Float>? = entries[Key(identity, input)]?.toList()
    @Synchronized fun put(identity: EmbeddingIdentity, input: String, vector: List<Float>, expectedToken: Long? = null) {
        if (expectedToken != null && expectedToken != generation) return
        if (input.length > maxInputCharacters || vector.size != identity.dimensions || vector.any { !it.isFinite() }) return
        val key = Key(identity, input)
        if (entries.remove(key) != null) characters -= input.length
        entries[key] = vector.toFloatArray(); characters += input.length
        while (entries.size > maxEntries || characters > maxInputCharacters) {
            val iterator = entries.entries.iterator()
            characters -= iterator.next().key.input.length; iterator.remove()
        }
    }
    @Synchronized fun clear() { generation++; entries.clear(); characters = 0 }
}
