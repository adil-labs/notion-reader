package com.example.notionreader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The relative-time vocabulary history entries are described with.
 *
 * The UI contract requires relative time and forbids a raw timestamp, so these are requirement tests
 * rather than formatting preferences. The cases that matter are the boundaries: "just now" must not read as
 * "0 minutes ago", "Yesterday" must not read as "1 day ago", and a clock that has gone backwards — which a
 * device can do — must not produce a negative duration.
 */
class RelativeTimeTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun aSessionJustListenedToReadsAsJustNow() {
        assertEquals("Just now", describeLastActivity(0))
        assertEquals("Just now", describeLastActivity(30_000))
        assertEquals("a minute is not yet \"1 minute ago\"", "Just now", describeLastActivity(minute - 1))
    }

    @Test
    fun minutesAndHoursArePluralised() {
        assertEquals("1 minute ago", describeLastActivity(minute))
        assertEquals("2 minutes ago", describeLastActivity(2 * minute))
        assertEquals("1 hour ago", describeLastActivity(hour))
        assertEquals("5 hours ago", describeLastActivity(5 * hour))
    }

    @Test
    fun theDayBeforeIsYesterdayRatherThanOneDayAgo() {
        assertEquals("Yesterday", describeLastActivity(day))
        assertEquals("Yesterday", describeLastActivity(2 * day - 1))
    }

    @Test
    fun olderSessionsAreDescribedInDaysThenWeeks() {
        assertEquals("2 days ago", describeLastActivity(2 * day))
        assertEquals("6 days ago", describeLastActivity(6 * day))
        assertEquals("1 week ago", describeLastActivity(7 * day))
        assertEquals("3 weeks ago", describeLastActivity(21 * day))
    }

    @Test
    fun aClockThatHasGoneBackwardsDoesNotProduceANegativeDuration() {
        assertEquals("Just now", describeLastActivity(-hour))
    }
}
