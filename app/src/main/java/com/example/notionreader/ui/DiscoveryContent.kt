package com.example.notionreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.ReaderApp
import com.example.notionreader.application.ReaderDestination
import com.example.notionreader.domain.discovery.BrowseQuery
import com.example.notionreader.domain.discovery.SemanticMatch
import com.example.notionreader.domain.discovery.canonicalSemanticId
import kotlinx.coroutines.launch

internal fun SemanticMatch.noteUi() = DiscoveryNoteUi(pageId, metadata.title, passage.excerpt, passage.sectionTitle.orEmpty())

@Composable
internal fun DiscoveryContent(app: ReaderApp, onOpenNote: (String) -> Unit) {
    val controller = app.discovery
    if (controller == null) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text("Discover", Modifier.semantics { heading() })
            Text("Semantic discovery is unavailable in this configuration.")
            TextButton(onClick = { app.openInterests("discover_interests") }) { Text("View your interests") }
        }
        return
    }
    val state by controller.state.collectAsState()
    val browse by app.browse.state.collectAsState()
    val navigation by app.navigator.state.collectAsState()
    val model = app.discoveryModel?.state?.collectAsState()?.value
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf(state.query) }
    var selectedFacet by rememberSaveable { mutableStateOf<Int?>(null) }
    val filterSaver = remember { listSaver<BrowseQuery, String>(
        save = { listOf(it.selectedType.orEmpty(), it.selectedArea.orEmpty(), it.selectedTopic.orEmpty(), it.selectedProject.orEmpty(), it.selectedStatus.orEmpty()) },
        restore = { BrowseQuery(selectedType = it[0].ifEmpty { null }, selectedArea = it[1].ifEmpty { null },
            selectedTopic = it[2].ifEmpty { null }, selectedProject = it[3].ifEmpty { null }, selectedStatus = it[4].ifEmpty { null }) }) }
    var filters by rememberSaveable(stateSaver = filterSaver) { mutableStateOf(state.filters) }

    val titles = state.cachedTitles + browse.entries.associate { canonicalSemanticId(it.pageId) to it.title }
    DiscoveryScreen(query, { query = it }, state.indexedCount, state.cachedPageIds.size,
        state.results.map { it.noteUi() },
        state.interests.map { interest -> DiscoveryInterestUi(interest.id,
            interest.displayName ?: interest.seedPageId?.let { titles[canonicalSemanticId(it)] ?: "Seed Note" } ?: interest.text,
            interest.seedPageId == null || canonicalSemanticId(interest.seedPageId) in state.cachedPageIds,
            seed = interest.seedPageId != null) },
        onSearch = { scope.launch { controller.search(query, filters) } },
        onOpenNote = onOpenNote,
        onAddInterest = { scope.launch { controller.saveInterest(null, it) } },
        onEditInterest = { id, text -> scope.launch { controller.saveInterest(id, text) } },
        onRemoveInterest = { scope.launch { controller.removeInterest(it) } },
        onSelectSeed = { app.navigator.open(ReaderDestination.DiscoverySeeds, fromFocusId = "discovery_seed") },
        onEditSeed = { app.navigator.open(ReaderDestination.DiscoverySeeds, fromFocusId = "discovery_interest:$it", subjectId = it) },
        onRelated = { scope.launch { app.openRelatedNotes(it, "discovery_related:$it") } },

        onManageDiscovery = if (!discoveryStatus(state).manageAvailable) null else ({
            app.navigator.open(ReaderDestination.SettingsDiscovery, "discovery_settings") }),
        topicsContent = { TopicPreviews(app) },
        onViewInterests = { app.openInterests("discover_interests") },
        coverageDescription = discoveryStatus(state).coverage,
        cacheReady = state.cacheStatus == com.example.notionreader.application.DiscoveryCacheStatus.Ready,
        cacheError = (state.cacheStatus as? com.example.notionreader.application.DiscoveryCacheStatus.Failed)?.message,
        semanticAvailable = state.modelAvailable,
        onBrowseNotes = app::openBrowse,
        submittedSummary = state.query.takeIf { it.isNotBlank() }?.let { submitted ->
            val values = listOfNotNull(state.filters.selectedType, state.filters.selectedArea, state.filters.selectedTopic,
                state.filters.selectedProject, state.filters.selectedStatus)
            "Results for “$submitted”" + if (values.isEmpty()) " · All metadata" else " · " + values.joinToString(", ")
        },
        onViewInterest = { id ->
            app.navigator.open(ReaderDestination.DiscoveryInterest, "discovery_interest:$id", id)
        },
        filterControls = {
            Text("Metadata filters", Modifier.semantics { heading() })
            val facets = listOf(
                Triple("Type", browse.entries.mapNotNull { it.kind }, filters.selectedType),
                Triple("Area", browse.entries.mapNotNull { it.area }, filters.selectedArea),
                Triple("Topic", browse.entries.flatMap { it.topics }, filters.selectedTopic),
                Triple("Project", browse.entries.mapNotNull { it.project }, filters.selectedProject),
                Triple("Status", browse.entries.mapNotNull { it.status }, filters.selectedStatus))
            facets.forEachIndexed { index, (label, values, selected) ->
                FilterChip(selected != null, onClick = { selectedFacet = index },
                    label = { Text("$label: ${selected ?: "All"}") })
            }
            selectedFacet?.let { index ->
                val (label, values, selected) = facets[index]
                fun choose(next: String?) {
                    filters = when (index) {
                        0 -> filters.copy(selectedType = next)
                        1 -> filters.copy(selectedArea = next)
                        2 -> filters.copy(selectedTopic = next)
                        3 -> filters.copy(selectedProject = next)
                        else -> filters.copy(selectedStatus = next)
                    }
                    selectedFacet = null
                }
                AlertDialog(onDismissRequest = { selectedFacet = null }, title = { Text("Filter by $label") },
                    text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                        TextButton(onClick = { choose(null) }) { Text("All $label values") }
                        values.distinct().sorted().forEach { value ->
                            FilterChip(selected == value, onClick = { choose(value) }, label = { Text(value) })
                        }
                    } }, confirmButton = { TextButton(onClick = { selectedFacet = null }) { Text("Close") } })
            }
        },
        focusTarget = navigation.focusTarget, onFocusHandled = app::focusHandled,
        status = state.message?.takeUnless { it == state.processingFailure } ?: when {
            model?.downloading == true -> discoveryModelDownloadProgress(model)
            !state.modelAvailable -> "Download the optional on-device model in Discovery settings to search cached Notes."
            state.searching -> "Searching cached Notes…"
            state.canSearch && state.query.isNotBlank() && state.results.isEmpty() -> "No matching cached Notes."
            else -> null
        })
}

@Composable
internal fun DiscoverySeedsContent(app: ReaderApp) {
    val controller = app.discovery ?: return
    val state by controller.state.collectAsState()
    val browse by app.browse.state.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).semantics { paneTitle = "Choose cached seed Note" },
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Choose cached seed Note", Modifier.semantics { heading() })
        val titles = state.cachedTitles + browse.entries.associate { canonicalSemanticId(it.pageId) to it.title }
        val entries = state.cachedPageIds.map { id -> com.example.notionreader.domain.ReaderIndexEntry(id, titles[id] ?: "Untitled Note") }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        if (entries.isEmpty()) Text("No cached Notes available. Open a Note in Browse to cache it.")
        entries.forEach { entry -> key(entry.pageId) {
            Button(onClick = { scope.launch { app.selectDiscoverySeed(entry.pageId) } }) { Text("Select ${entry.title}") }
        } }
        Button(onClick = { app.back() }) { Text("Back") }
    }
}
