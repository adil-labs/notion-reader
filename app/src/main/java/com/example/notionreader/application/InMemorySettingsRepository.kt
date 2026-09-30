package com.example.notionreader.application

import com.example.notionreader.domain.settings.ReaderSettings
import com.example.notionreader.domain.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory test double and default implementation of [SettingsRepository].
 */
class InMemorySettingsRepository(
    initial: ReaderSettings = ReaderSettings(),
    private val onClearHistory: (suspend () -> Unit)? = null,
    private val onClearCache: (suspend () -> Unit)? = null,
    private val onClearAll: (suspend () -> Unit)? = null
) : SettingsRepository {

    private val _settings = MutableStateFlow(initial)
    override val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    var clearHistoryCalls = 0
        private set

    var clearCacheCalls = 0
        private set

    var clearAllLocalDataCalls = 0
        private set

    override suspend fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        _settings.value = transform(_settings.value)
    }

    override suspend fun clearHistory() {
        clearHistoryCalls++
        onClearHistory?.invoke()
    }

    override suspend fun clearCache() {
        clearCacheCalls++
        onClearCache?.invoke()
    }

    override suspend fun resetToDefaults() {
        _settings.value = ReaderSettings()
    }

    override suspend fun clearAllLocalData() {
        clearAllLocalDataCalls++
        resetToDefaults()
        onClearAll?.invoke()
    }
}
