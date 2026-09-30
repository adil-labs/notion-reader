package com.example.notionreader.domain.queue

import com.example.notionreader.domain.ReaderIndexEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartQueueFilterTest {

    private val entry1 = ReaderIndexEntry(
        pageId = "1",
        title = "Android Architecture Guide",
        kind = "Guide",
        area = "Engineering",
        topics = setOf("Kotlin", "Architecture", "Mobile"),
        project = "Notion Reader",
        status = "Active"
    )

    private val entry2 = ReaderIndexEntry(
        pageId = "2",
        title = "Grocery Shopping List",
        kind = "List",
        area = "Personal",
        topics = setOf("Food", "Home"),
        project = null,
        status = "Completed"
    )

    @Test
    fun emptyQueryMatchesAll() {
        assertTrue(SmartQueueFilter.matches(entry1, ""))
        assertTrue(SmartQueueFilter.matches(entry1, "   "))
    }

    @Test
    fun freeTextMatchesAcrossMultipleFields() {
        // Matches title
        assertTrue(SmartQueueFilter.matches(entry1, "Architecture"))
        // Matches area
        assertTrue(SmartQueueFilter.matches(entry1, "Engineering"))
        // Matches topic
        assertTrue(SmartQueueFilter.matches(entry1, "Kotlin"))
        // Matches project
        assertTrue(SmartQueueFilter.matches(entry1, "Notion"))
        // Matches status
        assertTrue(SmartQueueFilter.matches(entry1, "Active"))

        assertFalse(SmartQueueFilter.matches(entry2, "Kotlin"))
    }

    @Test
    fun structuredTopicTokenMatches() {
        assertTrue(SmartQueueFilter.matches(entry1, "topic:kotlin"))
        assertTrue(SmartQueueFilter.matches(entry1, "tag:mobile"))
        assertFalse(SmartQueueFilter.matches(entry2, "topic:kotlin"))
    }

    @Test
    fun structuredAreaTokenMatches() {
        assertTrue(SmartQueueFilter.matches(entry1, "area:engineering"))
        assertTrue(SmartQueueFilter.matches(entry2, "area:personal"))
        assertFalse(SmartQueueFilter.matches(entry1, "area:personal"))
    }

    @Test
    fun structuredProjectAndStatusTokensMatch() {
        assertTrue(SmartQueueFilter.matches(entry1, "project:reader"))
        assertTrue(SmartQueueFilter.matches(entry1, "status:active"))
        assertTrue(SmartQueueFilter.matches(entry2, "status:completed"))
        assertFalse(SmartQueueFilter.matches(entry2, "project:reader"))
    }

    @Test
    fun compoundTokensRequireAllToMatch() {
        assertTrue(SmartQueueFilter.matches(entry1, "area:engineering status:active topic:kotlin"))
        // status is completed on entry2, not active
        assertFalse(SmartQueueFilter.matches(entry1, "area:engineering status:completed"))
    }

    @Test
    fun filterAppliesQueryToCollection() {
        val entries = listOf(entry1, entry2)
        val filtered = SmartQueueFilter.filter(entries, "topic:home")
        assertEquals(listOf(entry2), filtered)
    }
}
