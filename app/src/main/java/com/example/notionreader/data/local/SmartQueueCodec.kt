package com.example.notionreader.data.local

import com.example.notionreader.domain.queue.SMART_QUEUE_SCHEMA_VERSION
import com.example.notionreader.domain.queue.SmartQueue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The wire form of Smart Queues.
 */
internal object SmartQueueCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val queue: KSerializer<SmartQueue> = SmartQueue.serializer()
    private val elementList: KSerializer<List<JsonElement>> = ListSerializer(JsonElement.serializer())

    fun encode(queues: List<SmartQueue>): String {
        val valid = queues.filter { it.schemaVersion == SMART_QUEUE_SCHEMA_VERSION && it.id.isNotBlank() && it.name.isNotBlank() }
        return json.encodeToString(ListSerializer(queue), valid)
    }

    fun decode(encoded: String): List<SmartQueue> {
        val elements = try {
            json.decodeFromString(elementList, encoded)
        } catch (_: SerializationException) {
            return emptyList()
        } catch (_: IllegalArgumentException) {
            return emptyList()
        }

        val decoded = mutableListOf<SmartQueue>()
        for (element in elements) {
            try {
                val item = json.decodeFromJsonElement(queue, element)
                if (item.schemaVersion == SMART_QUEUE_SCHEMA_VERSION && item.id.isNotBlank() && item.name.isNotBlank()) {
                    decoded += item
                }
            } catch (_: SerializationException) {
                // Discard invalid/future records individually
            }
        }
        return decoded
    }
}
