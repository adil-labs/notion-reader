package com.example.notionreader.domain.settings

import kotlinx.coroutines.flow.StateFlow

/**
 * Storage and observation port for user settings.
 */
interface SettingsRepository {

    /** Current user preferences. */
    val settings: StateFlow<ReaderSettings>

    /** Updates current settings via an atomic transform. */
    suspend fun updateSettings(transform: (ReaderSettings) -> ReaderSettings)

    /** Resets user preferences back to default values. */
    suspend fun resetToDefaults()

    /** Deprecated: local data clearing belongs on ReaderApp orchestration boundary. */
    @Deprecated("Use ReaderApp.clearHistory()")
    suspend fun clearHistory() {}

    /** Deprecated: local data clearing belongs on ReaderApp orchestration boundary. */
    @Deprecated("Use ReaderApp.clearCache()")
    suspend fun clearCache() {}

    /** Deprecated: local data clearing belongs on ReaderApp orchestration boundary. */
    @Deprecated("Use ReaderApp.clearAllLocalData()")
    suspend fun clearAllLocalData() {
        resetToDefaults()
    }
}
