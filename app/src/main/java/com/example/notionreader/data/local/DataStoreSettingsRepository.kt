package com.example.notionreader.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.notionreader.domain.settings.SettingsRepository
import com.example.notionreader.domain.settings.AnnouncementVerbosity
import com.example.notionreader.domain.settings.AppTheme
import com.example.notionreader.domain.settings.DownloadNetworkPolicy
import com.example.notionreader.domain.settings.NavigationUnit
import com.example.notionreader.domain.settings.NotePauseDuration
import com.example.notionreader.domain.settings.ReaderSettings
import com.example.notionreader.domain.settings.ReaderSpacing
import com.example.notionreader.domain.settings.SpeechRate
import com.example.notionreader.domain.settings.VoicePitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.userSettingsDataStore by preferencesDataStore(name = "user_settings")

/**
 * Persistent [SettingsRepository] backed by Android DataStore preferences.
 */
class DataStoreSettingsRepository(
    private val context: Context,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onClearHistoryCallback: (suspend () -> Unit)? = null,
    private val onClearCacheCallback: (suspend () -> Unit)? = null,
    private val onClearAllCallback: (suspend () -> Unit)? = null
) : SettingsRepository {

    private val _settings = MutableStateFlow(ReaderSettings())
    override val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    init {
        scope.launch {
            val loaded = readFromDisk()
            _settings.value = loaded
        }
    }

    override suspend fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        writeToDisk(next)
    }

    override suspend fun clearHistory() {
        onClearHistoryCallback?.invoke()
    }

    override suspend fun clearCache() {
        onClearCacheCallback?.invoke()
    }

    override suspend fun clearAllLocalData() {
        try {
            context.userSettingsDataStore.edit { it.clear() }
        } catch (_: Exception) {
        }
        _settings.value = ReaderSettings()
        onClearAllCallback?.invoke()
    }

    private suspend fun readFromDisk(): ReaderSettings {
        return try {
            val prefs = context.userSettingsDataStore.data.first()
            ReaderSettings(
                defaultNavigationUnit = prefs[KEY_NAV_UNIT]?.let { runCatching { NavigationUnit.valueOf(it) }.getOrNull() }
                    ?: NavigationUnit.SECTION,
                continueToNextNote = prefs[KEY_CONTINUE_NEXT] ?: true,
                pauseBetweenNotes = prefs[KEY_PAUSE_NOTES]?.let { runCatching { NotePauseDuration.valueOf(it) }.getOrNull() }
                    ?: NotePauseDuration.NONE,
                useSystemSpeechSettings = prefs[KEY_USE_SYSTEM_SPEECH] ?: true,
                speechRate = prefs[KEY_SPEECH_RATE]?.let { runCatching { SpeechRate.valueOf(it) }.getOrNull() }
                    ?: SpeechRate.SYSTEM_DEFAULT,
                voicePitch = prefs[KEY_VOICE_PITCH]?.let { runCatching { VoicePitch.valueOf(it) }.getOrNull() }
                    ?: VoicePitch.NORMAL,
                playbackAnnouncements = prefs[KEY_ANNOUNCEMENTS]?.let { runCatching { AnnouncementVerbosity.valueOf(it) }.getOrNull() }
                    ?: AnnouncementVerbosity.CONCISE,
                cacheNotesForOffline = prefs[KEY_CACHE_OFFLINE] ?: true,
                prefetchUpcomingNotes = prefs[KEY_PREFETCH] ?: true,
                downloadNetworkPolicy = prefs[KEY_DOWNLOAD_POLICY]?.let { runCatching { DownloadNetworkPolicy.valueOf(it) }.getOrNull() }
                    ?: DownloadNetworkPolicy.WIFI_AND_MOBILE_DATA,
                keepScreenAwake = prefs[KEY_KEEP_AWAKE] ?: true,
                appTheme = prefs[KEY_THEME]?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() }
                    ?: AppTheme.SYSTEM_DEFAULT,
                readerSpacing = prefs[KEY_SPACING]?.let { runCatching { ReaderSpacing.valueOf(it) }.getOrNull() }
                    ?: ReaderSpacing.COMFORTABLE
            )
        } catch (_: Exception) {
            ReaderSettings()
        }
    }

    private suspend fun writeToDisk(settings: ReaderSettings) {
        try {
            context.userSettingsDataStore.edit { prefs ->
                prefs[KEY_NAV_UNIT] = settings.defaultNavigationUnit.name
                prefs[KEY_CONTINUE_NEXT] = settings.continueToNextNote
                prefs[KEY_PAUSE_NOTES] = settings.pauseBetweenNotes.name
                prefs[KEY_USE_SYSTEM_SPEECH] = settings.useSystemSpeechSettings
                prefs[KEY_SPEECH_RATE] = settings.speechRate.name
                prefs[KEY_VOICE_PITCH] = settings.voicePitch.name
                prefs[KEY_ANNOUNCEMENTS] = settings.playbackAnnouncements.name
                prefs[KEY_CACHE_OFFLINE] = settings.cacheNotesForOffline
                prefs[KEY_PREFETCH] = settings.prefetchUpcomingNotes
                prefs[KEY_DOWNLOAD_POLICY] = settings.downloadNetworkPolicy.name
                prefs[KEY_KEEP_AWAKE] = settings.keepScreenAwake
                prefs[KEY_THEME] = settings.appTheme.name
                prefs[KEY_SPACING] = settings.readerSpacing.name
            }
        } catch (_: Exception) {
        }
    }

    private companion object {
        val KEY_NAV_UNIT = stringPreferencesKey("default_navigation_unit")
        val KEY_CONTINUE_NEXT = booleanPreferencesKey("continue_to_next_note")
        val KEY_PAUSE_NOTES = stringPreferencesKey("pause_between_notes")
        val KEY_USE_SYSTEM_SPEECH = booleanPreferencesKey("use_system_speech_settings")
        val KEY_SPEECH_RATE = stringPreferencesKey("speech_rate")
        val KEY_VOICE_PITCH = stringPreferencesKey("voice_pitch")
        val KEY_ANNOUNCEMENTS = stringPreferencesKey("playback_announcements")
        val KEY_CACHE_OFFLINE = booleanPreferencesKey("cache_notes_for_offline")
        val KEY_PREFETCH = booleanPreferencesKey("prefetch_upcoming_notes")
        val KEY_DOWNLOAD_POLICY = stringPreferencesKey("download_network_policy")
        val KEY_KEEP_AWAKE = booleanPreferencesKey("keep_screen_awake")
        val KEY_THEME = stringPreferencesKey("app_theme")
        val KEY_SPACING = stringPreferencesKey("reader_spacing")
    }
}
