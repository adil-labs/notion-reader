package com.example.notionreader.domain.settings

/**
 * Immutable user preferences governing playback, speech, offline storage, accessibility, and appearance.
 *
 * This value object lives in the domain layer, ensuring player, application, and UI layers all agree
 * on the shape of user preferences without violating layer boundaries.
 */
data class ReaderSettings(
    // Playback
    val defaultNavigationUnit: NavigationUnit = NavigationUnit.SECTION,
    val continueToNextNote: Boolean = true,
    val pauseBetweenNotes: NotePauseDuration = NotePauseDuration.NONE,
    val keepScreenAwake: Boolean = true,

    // Speech
    val ttsEnginePackage: String? = null,
    val speechRate: SpeechRate = SpeechRate.SYSTEM_DEFAULT,
    val voicePitch: VoicePitch = VoicePitch.SYSTEM_DEFAULT,
    val playbackAnnouncements: AnnouncementVerbosity = AnnouncementVerbosity.CONCISE,

    // Offline & Storage
    val cacheNotesForOffline: Boolean = true,
    val prefetchUpcomingNotes: Boolean = true,
    val downloadNetworkPolicy: DownloadNetworkPolicy = DownloadNetworkPolicy.WIFI_AND_MOBILE_DATA,
    val cacheFreshnessPolicy: CacheFreshnessPolicy = CacheFreshnessPolicy.ONE_HOUR,
    val autoCacheMode: AutoCacheMode = AutoCacheMode.TIME_WINDOW,
    val autoCacheTimeWindow: AutoCacheTimeWindow = AutoCacheTimeWindow.DAYS_7,
    val autoCacheNoteCount: AutoCacheNoteCount = AutoCacheNoteCount.COUNT_10,

    // Appearance
    val appTheme: AppTheme = AppTheme.SYSTEM_DEFAULT,
    val readerSpacing: ReaderSpacing = ReaderSpacing.COMFORTABLE
)

enum class NavigationUnit(val label: String) {
    SECTION("Section"),
    PART("Part"),
    NOTE("Note")
}

enum class NotePauseDuration(val label: String, val delayMs: Long) {
    NONE("None", 0L),
    SHORT("Short", 350L),
    ONE_SECOND("1 second", 1000L),
    TWO_SECONDS("2 seconds", 2000L),
    FIVE_SECONDS("5 seconds", 5000L)
}

enum class SpeechRate(val label: String, val factor: Float) {
    SYSTEM_DEFAULT("System default", 1.0f),
    RATE_0_75("0.75×", 0.75f),
    RATE_1_0("1.0×", 1.0f),
    RATE_1_25("1.25×", 1.25f),
    RATE_1_5("1.5×", 1.5f),
    RATE_2_0("2.0×", 2.0f)
}

enum class VoicePitch(val label: String, val factor: Float) {
    SYSTEM_DEFAULT("System default", 1.0f),
    LOW("Low", 0.8f),
    NORMAL("Natural", 1.0f),
    HIGH("High", 1.2f)
}

enum class AnnouncementVerbosity(val label: String) {
    MINIMAL("Minimal"),
    CONCISE("Concise"),
    DETAILED("Detailed")
}

enum class DownloadNetworkPolicy(val label: String) {
    WIFI_AND_MOBILE_DATA("Wi-Fi and mobile data"),
    WIFI_ONLY("Wi-Fi only")
}

enum class CacheFreshnessPolicy(val label: String, val maxAgeMillis: Long) {
    ALWAYS_CHECK("Always check Notion", 0L),
    ONE_HOUR("1 hour", 1 * 3600 * 1000L),
    SIX_HOURS("6 hours", 6 * 3600 * 1000L),
    ONE_DAY("24 hours", 24 * 3600 * 1000L),
    MANUAL_ONLY("Manual refresh only", Long.MAX_VALUE)
}

enum class AutoCacheMode(val label: String) {
    OFF("Off"),
    TIME_WINDOW("By time window"),
    NOTE_COUNT("By recent note count")
}

enum class AutoCacheTimeWindow(val label: String, val days: Int) {
    DAYS_7("Last 7 days", 7),
    DAYS_15("Last 15 days", 15),
    DAYS_30("Last 30 days", 30);

    val durationMillis: Long get() = days * 24L * 3600L * 1000L
}

enum class AutoCacheNoteCount(val label: String, val count: Int) {
    COUNT_5("5 notes", 5),
    COUNT_10("10 notes", 10),
    COUNT_20("20 notes", 20),
    COUNT_50("50 notes", 50)
}

enum class AppTheme(val label: String) {
    SYSTEM_DEFAULT("System default"),
    LIGHT("Light"),
    DARK("Dark")
}

enum class ReaderSpacing(val label: String) {
    COMPACT("Compact"),
    COMFORTABLE("Comfortable"),
    SPACIOUS("Spacious")
}
