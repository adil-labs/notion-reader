package com.example.notionreader.domain.settings

import kotlinx.coroutines.flow.StateFlow

/**
 * Storage and observation port for user settings and local state management.
 */
interface SettingsRepository {

    /** Current user preferences. */
    val settings: StateFlow<ReaderSettings>

    /** Updates current settings via an atomic transform. */
    suspend fun updateSettings(transform: (ReaderSettings) -> ReaderSettings)

    /** Clears session history while preserving saved queues and cached notes. */
    suspend fun clearHistory()

    /** Clears cached offline notes. */
    suspend fun clearCache()

    /**
     * Clears all local application state (history, queues, offline cache, preferences).
     * Does not affect remote Notion content.
     */
    suspend fun clearAllLocalData()
}
