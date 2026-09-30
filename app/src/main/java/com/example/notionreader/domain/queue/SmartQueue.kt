package com.example.notionreader.domain.queue

import kotlinx.serialization.Serializable

/**
 * The schema version of a stored Smart Queue record.
 */
const val SMART_QUEUE_SCHEMA_VERSION: Int = 1

/**
 * One Smart Queue definition, as persisted.
 *
 * A Smart Queue is a persistent, query-backed queue definition whose membership is evaluated
 * dynamically from available content rather than being manually curated.
 */
@Serializable
data class SmartQueue(
    val id: String,
    val name: String,
    val queryText: String,
    val lastEditedAt: Long,
    val schemaVersion: Int = SMART_QUEUE_SCHEMA_VERSION
) {
    val isValid: Boolean
        get() = schemaVersion == SMART_QUEUE_SCHEMA_VERSION && id.isNotBlank() && name.isNotBlank()
}
