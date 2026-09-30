package com.example.notionreader.application

/**
 * A deterministic discovery shortcut into Browse, surfaced on Home.
 *
 * Per UI Screens & Navigation Contract lines 302–365:
 * "Explore contains deterministic discovery shortcuts into Browse.
 * It does not automatically create Saved or Smart Queues.
 * Example: Mindfulness (12 notes), Chess (31 notes)...
 * Selecting opens Browse with Topic / query text set."
 */
data class ExploreCard(
    val title: String,
    val noteCount: Int,
    val queryText: String
)
