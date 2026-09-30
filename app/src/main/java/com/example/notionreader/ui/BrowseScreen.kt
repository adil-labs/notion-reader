package com.example.notionreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.BrowseIndexStatus
import com.example.notionreader.application.ReaderFocus
import com.example.notionreader.domain.discovery.BrowseQuery
import com.example.notionreader.domain.discovery.BrowseResults
import com.example.notionreader.domain.discovery.BrowseSort
import com.example.notionreader.domain.ReaderIndexEntry
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class FacetDialogType {
    TYPE, AREA, TOPIC, PROJECT, STATUS
}

/**
 * Browse: the reader-oriented discovery surface and "the primary semantic discovery interface".
 *
 * ## What is here
 *
 * - Search notes: case-insensitive query matching title, area, kind, topics, project, status.
 * - Facets: Type, Area, Topics, Project, Status. Facets dynamically reflect workspace content.
 * - Active filter summary: exposes active filter count, labels, result count and Clear filters.
 * - Sort: Recently updated, Title.
 * - Results list: one focus stop per result (merging title, type, area, date).
 * - Result actions: Play all and Add all to queue.
 */
@Composable
fun BrowseScreen(
    state: BrowseResults,
    isLoading: Boolean,
    error: BrowseIndexStatus.Error?,
    queryText: String,
    onQueryChange: (String) -> Unit,
    onOpenNote: (ReaderIndexEntry) -> Unit,
    onPlayAll: () -> Unit,
    onAddAllToQueue: () -> Unit = {},
    onClearQuery: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit = onRetry,
    onSortChange: (BrowseSort) -> Unit = {},
    modifier: Modifier = Modifier,
    focusTarget: String? = null,
    onFocusHandled: () -> Unit = {},
    allEntries: List<ReaderIndexEntry> = state.entries,
    query: BrowseQuery = state.query,
    onTypeFilterChange: (String?) -> Unit = {},
    onAreaFilterChange: (String?) -> Unit = {},
    onTopicFilterChange: (String?) -> Unit = {},
    onProjectFilterChange: (String?) -> Unit = {},
    onStatusFilterChange: (String?) -> Unit = {},
    onClearFilters: () -> Unit = {}
) {
    LaunchedEffect(focusTarget, state.entries) {
        val owned = focusTarget == ReaderFocus.BrowsePlayAll ||
            focusTarget == ReaderFocus.BrowseAddAllToQueue ||
            state.entries.any { it.pageId == focusTarget }
        if (focusTarget != null && !owned) onFocusHandled()
    }

    var activeFacetDialog by remember { mutableStateOf<FacetDialogType?>(null) }

    // Facet option discovery from workspace entries
    val availableTypes = remember(allEntries) {
        allEntries.mapNotNull { it.kind }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val availableAreas = remember(allEntries, query.selectedType) {
        allEntries
            .filter { query.selectedType == null || it.kind.equals(query.selectedType, ignoreCase = true) }
            .mapNotNull { it.area }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }
    val availableTopics = remember(allEntries) {
        allEntries.flatMap { it.topics }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val availableProjects = remember(allEntries) {
        allEntries.mapNotNull { it.project }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val availableStatuses = remember(allEntries) {
        allEntries.mapNotNull { it.status }.filter { it.isNotBlank() }.distinct().sorted()
    }

    val hasSearch = queryText.isNotBlank() || state.query.hasText
    val searchText = if (queryText.isNotBlank()) queryText else state.query.text

    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { paneTitle = "Browse" }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = queryText,
                    onValueChange = onQueryChange,
                    label = { Text("Search notes") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onRefresh,
                    enabled = !isLoading,
                    modifier = Modifier.semantics {
                        contentDescription = "Refresh Notes from Notion"
                    }
                ) {
                    Text("↻", style = MaterialTheme.typography.titleLarge)
                }
            }

            // Facet Filter Chips (scrollable row)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (availableTypes.isNotEmpty() || query.selectedType != null) {
                    FilterChip(
                        selected = query.selectedType != null,
                        onClick = { activeFacetDialog = FacetDialogType.TYPE },
                        label = {
                            Text(
                                if (query.selectedType != null) "Type: ${query.selectedType}" else "Type"
                            )
                        }
                    )
                }
                if (availableAreas.isNotEmpty() || query.selectedArea != null) {
                    FilterChip(
                        selected = query.selectedArea != null,
                        onClick = { activeFacetDialog = FacetDialogType.AREA },
                        label = {
                            Text(
                                if (query.selectedArea != null) "Area: ${query.selectedArea}" else "Area"
                            )
                        }
                    )
                }
                if (availableTopics.isNotEmpty() || query.selectedTopic != null) {
                    FilterChip(
                        selected = query.selectedTopic != null,
                        onClick = { activeFacetDialog = FacetDialogType.TOPIC },
                        label = {
                            Text(
                                if (query.selectedTopic != null) "Topic: ${query.selectedTopic}" else "Topic"
                            )
                        }
                    )
                }
                if (availableProjects.isNotEmpty() || query.selectedProject != null) {
                    FilterChip(
                        selected = query.selectedProject != null,
                        onClick = { activeFacetDialog = FacetDialogType.PROJECT },
                        label = {
                            Text(
                                if (query.selectedProject != null) "Project: ${query.selectedProject}" else "Project"
                            )
                        }
                    )
                }
                if (availableStatuses.isNotEmpty() || query.selectedStatus != null) {
                    FilterChip(
                        selected = query.selectedStatus != null,
                        onClick = { activeFacetDialog = FacetDialogType.STATUS },
                        label = {
                            Text(
                                if (query.selectedStatus != null) "Status: ${query.selectedStatus}" else "Status"
                            )
                        }
                    )
                }
            }

            // "When filters are active, Browse should expose a concise summary"
            if (query.hasFilters) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${query.activeFilterCount} filter${if (query.activeFilterCount == 1) "" else "s"}: " +
                                query.activeFilterLabels.joinToString(", "),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${state.size} result${if (state.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    TextButton(onClick = onClearFilters) {
                        Text("Clear filters")
                    }
                }
            }

            // Search summary
            if (hasSearch) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${state.size} result${if (state.size == 1) "" else "s"} for \"$searchText\"",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClearQuery) {
                        Text("Clear search")
                    }
                }
            }

            // Sort controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Sort:",
                    style = MaterialTheme.typography.labelMedium
                )
                FilterChip(
                    selected = state.query.sort == BrowseSort.RECENTLY_UPDATED,
                    onClick = { onSortChange(BrowseSort.RECENTLY_UPDATED) },
                    label = { Text("Recently updated") }
                )
                FilterChip(
                    selected = state.query.sort == BrowseSort.TITLE,
                    onClick = { onSortChange(BrowseSort.TITLE) },
                    label = { Text("Title") }
                )
            }

            Text(
                text = "All Notes",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
        }

        if (error != null) {
            BrowseErrorNotice(error = error, onRetry = onRetry)
        }

        when {
            isLoading && state.isEmpty -> Text(
                text = "Loading Notes…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            state.isEmpty && (hasSearch || query.hasFilters) -> Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "No matching Notes.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (query.hasFilters) {
                    Button(onClick = onClearFilters) {
                        Text("Clear filters")
                    }
                } else {
                    Button(onClick = onClearQuery) {
                        Text("Clear search")
                    }
                }
            }

            state.isEmpty && error != null -> Unit

            state.isEmpty -> Text(
                text = "No Notes are available. Share a page with the Notion integration to read it here.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(items = state.entries, key = { it.pageId }) { entry ->
                        BrowseResultRow(
                            entry = entry,
                            onClick = { onOpenNote(entry) },
                            focusTarget = focusTarget,
                            onFocusHandled = onFocusHandled
                        )
                    }
                }

                Button(
                    onClick = onPlayAll,
                    modifier = Modifier
                        .fillMaxWidth()
                        .restoreFocusOn(
                            target = focusTarget,
                            id = ReaderFocus.BrowsePlayAll,
                            onHandled = onFocusHandled
                        )
                        .padding(start = 24.dp, end = 24.dp, top = 12.dp)
                ) {
                    Text("Play all")
                }

                Button(
                    onClick = onAddAllToQueue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .restoreFocusOn(
                            target = focusTarget,
                            id = ReaderFocus.BrowseAddAllToQueue,
                            onHandled = onFocusHandled
                        )
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text("Add all to queue")
                }
            }
        }
    }

    // Facet Selection Dialog
    activeFacetDialog?.let { dialogType ->
        val title: String
        val options: List<String>
        val currentSelection: String?
        val onSelect: (String?) -> Unit

        when (dialogType) {
            FacetDialogType.TYPE -> {
                title = "Type"
                options = availableTypes
                currentSelection = query.selectedType
                onSelect = onTypeFilterChange
            }
            FacetDialogType.AREA -> {
                title = "Area"
                options = availableAreas
                currentSelection = query.selectedArea
                onSelect = onAreaFilterChange
            }
            FacetDialogType.TOPIC -> {
                title = "Topic"
                options = availableTopics
                currentSelection = query.selectedTopic
                onSelect = onTopicFilterChange
            }
            FacetDialogType.PROJECT -> {
                title = "Project"
                options = availableProjects
                currentSelection = query.selectedProject
                onSelect = onProjectFilterChange
            }
            FacetDialogType.STATUS -> {
                title = "Status"
                options = availableStatuses
                currentSelection = query.selectedStatus
                onSelect = onStatusFilterChange
            }
        }

        FacetSelectionDialog(
            facetTitle = title,
            options = options,
            currentSelection = currentSelection,
            onSelect = { selected ->
                onSelect(selected)
                activeFacetDialog = null
            },
            onDismiss = { activeFacetDialog = null }
        )
    }
}

@Composable
private fun FacetSelectionDialog(
    facetTitle: String,
    options: List<String>,
    currentSelection: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter by $facetTitle") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    val isAllSelected = currentSelection == null
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = isAllSelected,
                                role = Role.RadioButton,
                                onClick = { onSelect(null) }
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isAllSelected,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "All ($facetTitle)",
                            style = if (isAllSelected) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                items(options) { option ->
                    val isSelected = option.equals(currentSelection, ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = { onSelect(option) }
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = option,
                            style = if (isSelected) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/**
 * One result, as one focus stop.
 *
 * `mergeDescendants = true` ensures TalkBack reads title, kind, area, and date as one item.
 */
@Composable
private fun BrowseResultRow(
    entry: ReaderIndexEntry,
    onClick: () -> Unit,
    focusTarget: String?,
    onFocusHandled: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .restoreFocusOn(
                target = focusTarget,
                id = entry.pageId,
                onHandled = onFocusHandled
            )
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodyLarge
        )

        val date = entry.lastEditedAt?.let {
            DateTimeFormatter.ofPattern("MMMM d").withZone(ZoneId.systemDefault()).format(it)
        }
        val metadataParts = listOfNotNull(
            entry.kind,
            entry.area,
            date
        )
        if (metadataParts.isNotEmpty()) {
            Text(
                text = metadataParts.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun BrowseErrorNotice(
    error: BrowseIndexStatus.Error,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = error.message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )

        if (error.canRetry) {
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}
