package com.example.notionreader.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.notionreader.domain.settings.SettingsRepository
import com.example.notionreader.domain.settings.AnnouncementVerbosity
import com.example.notionreader.domain.settings.AppTheme
import com.example.notionreader.domain.settings.AutoCacheMode
import com.example.notionreader.domain.settings.AutoCacheNoteCount
import com.example.notionreader.domain.settings.AutoCacheTimeWindow
import com.example.notionreader.domain.settings.CacheFreshnessPolicy
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.userSettingsDataStore by preferencesDataStore(name = "user_settings")

/**
 * Persistent [SettingsRepository] backed by Android DataStore preferences.
 * Updates are serialized atomically through DataStore edit transactions,
 * eliminating startup overwrite races and concurrent update out-of-order writes.
 */
class DataStoreSettingsRepository(
    private val context: Context,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    @Suppress("UNUSED_PARAMETER") onClearHistoryCallback: (suspend () -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") onClearCacheCallback: (suspend () -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") onClearAllCallback: (suspend () -> Unit)? = null
) : SettingsRepository {

    override val settings: StateFlow<ReaderSettings> = context.userSettingsDataStore.data
        .map { decode(it) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = ReaderSettings()
        )

    override suspend fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        try {
            context.userSettingsDataStore.edit { prefs ->
                val current = decode(prefs)
                val next = transform(current)
                encode(next, prefs)
            }
        } catch (_: Exception) {
        }
    }

    override suspend fun resetToDefaults() {
        try {
            context.userSettingsDataStore.edit { it.clear() }
        } catch (_: Exception) {
        }
    }

    private fun decode(prefs: Preferences): ReaderSettings {
        return ReaderSettings(
            defaultNavigationUnit = prefs[KEY_NAV_UNIT]?.let { runCatching { NavigationUnit.valueOf(it) }.getOrNull() }
                ?: NavigationUnit.SECTION,
            continueToNextNote = prefs[KEY_CONTINUE_NEXT] ?: true,
            pauseBetweenNotes = prefs[KEY_PAUSE_NOTES]?.let { runCatching { NotePauseDuration.valueOf(it) }.getOrNull() }
                ?: NotePauseDuration.NONE,
            ttsEnginePackage = prefs[KEY_TTS_ENGINE_PKG],
            speechRate = prefs[KEY_SPEECH_RATE]?.let { SpeechRate.parse(it) }
                ?: SpeechRate.SYSTEM_DEFAULT,
            voicePitch = prefs[KEY_VOICE_PITCH]?.let { runCatching { VoicePitch.valueOf(it) }.getOrNull() }
                ?: VoicePitch.SYSTEM_DEFAULT,
            playbackAnnouncements = prefs[KEY_ANNOUNCEMENTS]?.let { runCatching { AnnouncementVerbosity.valueOf(it) }.getOrNull() }
                ?: AnnouncementVerbosity.CONCISE,
            cacheNotesForOffline = prefs[KEY_CACHE_OFFLINE] ?: true,
            prefetchUpcomingNotes = prefs[KEY_PREFETCH] ?: true,
            downloadNetworkPolicy = prefs[KEY_DOWNLOAD_POLICY]?.let { runCatching { DownloadNetworkPolicy.valueOf(it) }.getOrNull() }
                ?: DownloadNetworkPolicy.WIFI_AND_MOBILE_DATA,
            cacheFreshnessPolicy = prefs[KEY_CACHE_FRESHNESS]?.let { runCatching { CacheFreshnessPolicy.valueOf(it) }.getOrNull() }
                ?: CacheFreshnessPolicy.ONE_HOUR,
            autoCacheMode = prefs[KEY_AUTO_CACHE_MODE]?.let { runCatching { AutoCacheMode.valueOf(it) }.getOrNull() }
                ?: AutoCacheMode.TIME_WINDOW,
            autoCacheTimeWindow = prefs[KEY_AUTO_CACHE_WINDOW]?.let { runCatching { AutoCacheTimeWindow.valueOf(it) }.getOrNull() }
                ?: AutoCacheTimeWindow.DAYS_7,
            autoCacheNoteCount = prefs[KEY_AUTO_CACHE_COUNT]?.let { runCatching { AutoCacheNoteCount.valueOf(it) }.getOrNull() }
                ?: AutoCacheNoteCount.COUNT_10,
            keepScreenAwake = prefs[KEY_KEEP_AWAKE] ?: true,
            appTheme = prefs[KEY_THEME]?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() }
                ?: AppTheme.SYSTEM_DEFAULT,
            readerSpacing = prefs[KEY_SPACING]?.let { runCatching { ReaderSpacing.valueOf(it) }.getOrNull() }
                ?: ReaderSpacing.COMFORTABLE
        )
    }

    private fun encode(settings: ReaderSettings, prefs: androidx.datastore.preferences.core.MutablePreferences) {
        prefs[KEY_NAV_UNIT] = settings.defaultNavigationUnit.name
        prefs[KEY_CONTINUE_NEXT] = settings.continueToNextNote
        prefs[KEY_PAUSE_NOTES] = settings.pauseBetweenNotes.name
        if (settings.ttsEnginePackage != null) {
            prefs[KEY_TTS_ENGINE_PKG] = settings.ttsEnginePackage
        } else {
            prefs.remove(KEY_TTS_ENGINE_PKG)
        }
        prefs[KEY_SPEECH_RATE] = settings.speechRate.name
        prefs[KEY_VOICE_PITCH] = settings.voicePitch.name
        prefs[KEY_ANNOUNCEMENTS] = settings.playbackAnnouncements.name
        prefs[KEY_CACHE_OFFLINE] = settings.cacheNotesForOffline
        prefs[KEY_PREFETCH] = settings.prefetchUpcomingNotes
        prefs[KEY_DOWNLOAD_POLICY] = settings.downloadNetworkPolicy.name
        prefs[KEY_CACHE_FRESHNESS] = settings.cacheFreshnessPolicy.name
        prefs[KEY_AUTO_CACHE_MODE] = settings.autoCacheMode.name
        prefs[KEY_AUTO_CACHE_WINDOW] = settings.autoCacheTimeWindow.name
        prefs[KEY_AUTO_CACHE_COUNT] = settings.autoCacheNoteCount.name
        prefs[KEY_KEEP_AWAKE] = settings.keepScreenAwake
        prefs[KEY_THEME] = settings.appTheme.name
        prefs[KEY_SPACING] = settings.readerSpacing.name
    }

    private companion object {
        val KEY_NAV_UNIT = stringPreferencesKey("default_navigation_unit")
        val KEY_CONTINUE_NEXT = booleanPreferencesKey("continue_to_next_note")
        val KEY_PAUSE_NOTES = stringPreferencesKey("pause_between_notes")
        val KEY_TTS_ENGINE_PKG = stringPreferencesKey("tts_engine_package")
        val KEY_SPEECH_RATE = stringPreferencesKey("speech_rate")
        val KEY_VOICE_PITCH = stringPreferencesKey("voice_pitch")
        val KEY_ANNOUNCEMENTS = stringPreferencesKey("playback_announcements")
        val KEY_CACHE_OFFLINE = booleanPreferencesKey("cache_notes_for_offline")
        val KEY_PREFETCH = booleanPreferencesKey("prefetch_upcoming_notes")
        val KEY_DOWNLOAD_POLICY = stringPreferencesKey("download_network_policy")
        val KEY_CACHE_FRESHNESS = stringPreferencesKey("cache_freshness_policy")
        val KEY_AUTO_CACHE_MODE = stringPreferencesKey("auto_cache_mode")
        val KEY_AUTO_CACHE_WINDOW = stringPreferencesKey("auto_cache_time_window")
        val KEY_AUTO_CACHE_COUNT = stringPreferencesKey("auto_cache_note_count")
        val KEY_KEEP_AWAKE = booleanPreferencesKey("keep_screen_awake")
        val KEY_THEME = stringPreferencesKey("app_theme")
        val KEY_SPACING = stringPreferencesKey("reader_spacing")
    }
}
