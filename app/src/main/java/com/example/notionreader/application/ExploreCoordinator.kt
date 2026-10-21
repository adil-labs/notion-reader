package com.example.notionreader.application

/**
 * Coordinates deterministic Area and Topic discovery taxonomy exploration.
 */
class ExploreCoordinator(
    private val browse: BrowseModel,
    private val navigator: ReaderNavigator
) {
    /** Complete deterministic Area and Topic discovery taxonomy. */
    fun exploreCatalog(): ExploreCatalog {
        val entries = browse.state.value.entries
        if (entries.isEmpty()) return ExploreCatalog(emptyList(), emptyList())

        val topicCards = entries
            .flatMap { entry -> entry.topics.filter { it.isNotBlank() }.map { topic -> topic to entry } }
            .groupBy({ it.first.lowercase() }, { it.second })
            .map { (topicKey, notes) ->
                val title = entries
                    .asSequence()
                    .flatMap { it.topics.asSequence() }
                    .first { it.equals(topicKey, ignoreCase = true) }
                ExploreCard(
                    title = title,
                    noteCount = notes.distinctBy { it.pageId }.size,
                    queryText = title,
                    topic = title
                )
            }

        val areaCards = entries
            .filter { !it.area.isNullOrBlank() }
            .groupBy { it.area!!.lowercase() }
            .map { (areaKey, notes) ->
                val title = entries.first { it.area?.equals(areaKey, ignoreCase = true) == true }.area!!
                ExploreCard(
                    title = title,
                    noteCount = notes.distinctBy { it.pageId }.size,
                    queryText = title,
                    area = title
                )
            }

        val alphabetical = compareBy<ExploreCard> { it.title.lowercase() }.thenBy { it.title }
        return ExploreCatalog(
            areas = areaCards.sortedWith(alphabetical),
            topics = topicCards.sortedWith(alphabetical)
        )
    }

    /** Bounded Home preview; the catalog remains the complete discovery surface. */
    fun exploreCards(): List<ExploreCard> =
        exploreCatalog().let { (it.areas + it.topics).take(6) }

    fun openExploreCard(card: ExploreCard) {
        browse.clearFilters()
        when {
            card.topic != null -> browse.setTopicFilter(card.topic)
            card.area != null -> browse.setAreaFilter(card.area)
            else -> browse.setText(card.queryText)
        }
        navigator.open(ReaderDestination.Browse)
    }

    /** Opens the complete deterministic discovery taxonomy without changing playback or Browse filters. */
    fun openExploreCatalog() {
        navigator.open(ReaderDestination.ExploreCatalog, fromFocusId = ReaderFocus.BrowseExplore)
    }

    /**
     * Activates an Explore shortcut from Home, navigating to Browse pre-filtered with [queryText].
     */
    fun openExplore(queryText: String) {
        val card = exploreCards().firstOrNull { it.queryText.equals(queryText, ignoreCase = true) || it.title.equals(queryText, ignoreCase = true) }
        if (card != null) {
            openExploreCard(card)
        } else {
            browse.clearFilters()
            browse.setText(queryText)
            navigator.open(ReaderDestination.Browse)
        }
    }
}
