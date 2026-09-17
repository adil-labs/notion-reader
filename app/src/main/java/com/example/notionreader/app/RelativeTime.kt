package com.example.notionreader.app

/**
 * How long ago a session was last listened to, in words.
 *
 * Pure, and in `app` rather than in a composable, for two reasons. The UI contract is specific that
 * history entries are described with relative time — "Paused 2 hours ago", "Yesterday" — and that "a
 * history entry must not announce a raw timestamp", so this is a requirement rather than a formatting
 * preference and deserves to be testable on the JVM. And `android.text.format.DateUtils` would have made
 * the phrasing a property of the platform's locale data, which is not where a contract's vocabulary
 * belongs.
 *
 * The thresholds are chosen so that the description is always *useful* rather than precise: a listener
 * deciding whether to continue something cares about "a few minutes" versus "yesterday", not about
 * minutes. Beyond a week, the day of the week is no longer enough to place it, so it becomes a date.
 */
fun describeLastActivity(elapsedMillis: Long): String = when {
    elapsedMillis < 0L -> "Just now"
    elapsedMillis < MINUTE -> "Just now"
    elapsedMillis < HOUR -> plural(elapsedMillis / MINUTE, "minute")
    elapsedMillis < DAY -> plural(elapsedMillis / HOUR, "hour")
    elapsedMillis < 2 * DAY -> "Yesterday"
    elapsedMillis < WEEK -> plural(elapsedMillis / DAY, "day")
    else -> plural(elapsedMillis / WEEK, "week")
}

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR
private const val WEEK = 7 * DAY

private fun plural(count: Long, unit: String): String =
    if (count == 1L) "1 $unit ago" else "$count ${unit}s ago"
