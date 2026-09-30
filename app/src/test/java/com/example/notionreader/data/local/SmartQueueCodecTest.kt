package com.example.notionreader.data.local

import com.example.notionreader.domain.queue.SMART_QUEUE_SCHEMA_VERSION
import com.example.notionreader.domain.queue.SmartQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartQueueCodecTest {

    private fun smartQueue(
        id: String = "sq-1",
        name: String = "Articles",
        queryText: String = "tag:articles",
        lastEditedAt: Long = 1000L,
        schemaVersion: Int = SMART_QUEUE_SCHEMA_VERSION
    ) = SmartQueue(
        id = id,
        name = name,
        queryText = queryText,
        lastEditedAt = lastEditedAt,
        schemaVersion = schemaVersion
    )

    @Test
    fun smartQueueRoundTripsWithAllFields() {
        val original = listOf(
            smartQueue("sq-1", "Articles", "tag:articles", 1000L),
            smartQueue("sq-2", "Books", "books", 2000L)
        )

        val encoded = SmartQueueCodec.encode(original)
        val decoded = SmartQueueCodec.decode(encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun decodingEmptyStringYieldsEmptyList() {
        assertEquals(emptyList<SmartQueue>(), SmartQueueCodec.decode(""))
        assertEquals(emptyList<SmartQueue>(), SmartQueueCodec.decode("   "))
    }

    @Test
    fun decodingMalformedJsonYieldsEmptyList() {
        assertEquals(emptyList<SmartQueue>(), SmartQueueCodec.decode("not json at all"))
    }

    @Test
    fun corruptedElementInArrayDoesNotBreakOtherElements() {
        val json = """
            [
              {"id": "valid-1", "name": "Valid", "queryText": "query", "lastEditedAt": 1000, "schemaVersion": 1},
              {"id": "", "corrupted": true},
              {"id": "valid-2", "name": "Valid 2", "queryText": "query 2", "lastEditedAt": 2000, "schemaVersion": 1}
            ]
        """.trimIndent()

        val decoded = SmartQueueCodec.decode(json)

        assertEquals(2, decoded.size)
        assertEquals("valid-1", decoded[0].id)
        assertEquals("valid-2", decoded[1].id)
    }

    @Test
    fun futureSchemaVersionIsIgnored() {
        val json = """
            [
              {"id": "future", "name": "Future", "queryText": "query", "lastEditedAt": 1000, "schemaVersion": 999}
            ]
        """.trimIndent()

        val decoded = SmartQueueCodec.decode(json)

        assertTrue(decoded.isEmpty())
    }
}
