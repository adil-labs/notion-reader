package com.example.notionreader.application

/**
 * A deterministic discovery shortcut into Browse.
 */
data class ExploreCard(
    val title: String,
    val noteCount: Int,
    val queryText: String,
    val topic: String? = null,
    val area: String? = null
)

/** The complete metadata taxonomy exposed by the secondary Explore Catalog screen. */
data class ExploreCatalog(
    val areas: List<ExploreCard>,
    val topics: List<ExploreCard>
)
