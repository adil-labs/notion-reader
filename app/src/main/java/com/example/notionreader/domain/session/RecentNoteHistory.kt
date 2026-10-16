package com.example.notionreader.domain.session

import kotlinx.serialization.Serializable

/** Navigation references only: never persisted titles, content or semantic signals. */
@Serializable
data class RecentNoteReference(val pageId: String, val openedAt: Long)

interface RecentNoteHistoryStore {
    suspend fun read(): List<RecentNoteReference>
    suspend fun write(references: List<RecentNoteReference>)
    suspend fun clear() = write(emptyList())
}
