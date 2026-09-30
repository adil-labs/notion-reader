package com.example.notionreader.domain.queue

import com.example.notionreader.domain.ReaderIndexEntry

/**
 * Filter evaluation engine for Smart Queues.
 *
 * Supports multi-field matching and structured query tokens:
 * - `topic:android` or `tag:android` -> matches entries where any topic contains "android"
 * - `area:engineering` -> matches entries where area contains "engineering"
 * - `project:reader` -> matches entries where project contains "reader"
 * - `status:reading` -> matches entries where status contains "reading"
 * - `kind:note` -> matches entries where kind contains "note"
 * - `title:summary` -> matches entries where title contains "summary"
 * - Free text term (e.g. `architecture`) -> matches across title, area, project, status, and topics
 *
 * Multiple whitespace-separated tokens are combined with AND logic (all tokens must match).
 */
object SmartQueueFilter {

    fun matches(entry: ReaderIndexEntry, queryText: String): Boolean {
        val trimmed = queryText.trim()
        if (trimmed.isEmpty()) return true

        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (token in tokens) {
            if (!matchToken(entry, token)) {
                return false
            }
        }
        return true
    }

    private fun matchToken(entry: ReaderIndexEntry, token: String): Boolean {
        val colonIndex = token.indexOf(':')
        if (colonIndex > 0 && colonIndex < token.length - 1) {
            val key = token.substring(0, colonIndex).lowercase()
            val value = token.substring(colonIndex + 1)
            return when (key) {
                "topic", "tag", "topics", "tags" -> entry.topics.any { it.contains(value, ignoreCase = true) }
                "area" -> entry.area?.contains(value, ignoreCase = true) == true
                "project" -> entry.project?.contains(value, ignoreCase = true) == true
                "status" -> entry.status?.contains(value, ignoreCase = true) == true
                "kind" -> entry.kind?.contains(value, ignoreCase = true) == true
                "title" -> entry.title.contains(value, ignoreCase = true)
                else -> matchAnyField(entry, token)
            }
        }
        return matchAnyField(entry, token)
    }

    private fun matchAnyField(entry: ReaderIndexEntry, term: String): Boolean {
        if (entry.title.contains(term, ignoreCase = true)) return true
        if (entry.area?.contains(term, ignoreCase = true) == true) return true
        if (entry.project?.contains(term, ignoreCase = true) == true) return true
        if (entry.status?.contains(term, ignoreCase = true) == true) return true
        if (entry.topics.any { it.contains(term, ignoreCase = true) }) return true
        return false
    }

    fun filter(entries: List<ReaderIndexEntry>, queryText: String): List<ReaderIndexEntry> {
        return entries.filter { matches(it, queryText) }
    }
}
