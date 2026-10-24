package com.example.notionreader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.ReaderApp
import com.example.notionreader.application.ReaderDestination
import com.example.notionreader.domain.discovery.canonicalSemanticId
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Screen displaying details, matches, and management controls for a single Discovery interest.
 */
@Composable
internal fun InterestDetailsScreen(
    app: ReaderApp,
    onOpenNote: (String) -> Unit
) {
    val controller = app.discovery ?: return
    val state by controller.state.collectAsState()
    val nav by app.navigator.state.collectAsState()
    val scope = rememberCoroutineScope()
    val subject = nav.currentSubjectId

    val interest = state.interests.firstOrNull { it.id == subject }
    val modelInstalled = state.modelAvailable

    LaunchedEffect(subject, interest, modelInstalled, state.cacheStatus, state.cachedRevision, state.indexedRevision) {
        if (state.cacheStatus == com.example.notionreader.application.DiscoveryCacheStatus.Ready)
            interest?.let { controller.loadInterestMatches(it.id) }
    }

    val name = interest?.displayName
        ?: interest?.seedPageId?.let { state.cachedTitles[canonicalSemanticId(it)] ?: "Seed Note" }
        ?: interest?.text
        ?: "Your interest"

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = name,
            modifier = Modifier.semantics {
                heading()
                paneTitle = "Interest details"
            },
            style = MaterialTheme.typography.headlineMedium
        )

        DiscoveryStatusNotice(state)
        if (interest == null) {
            Text("Interest unavailable")
        } else {
            var editing by rememberSaveable(subject) { mutableStateOf(false) }
            var olderOnly by rememberSaveable(subject) { mutableStateOf(false) }

            val matches = state.interestMatch.matches.takeIf { state.interestMatch.interestId == subject }.orEmpty()
            val cutoff = Instant.now().minusSeconds(90L * 86400)
            val older = matches.filter { match ->
                match.metadata.lastEditedAt?.let { dateStr ->
                    runCatching { Instant.parse(dateStr) }.getOrNull()
                }?.let { !it.isAfter(cutoff) } == true
            }
            val visible = if (olderOnly) older else matches

            Text(
                if (interest.seedPageId == null) interest.text
                else "Matches the cached seed Note. The seed is excluded.",
                style = MaterialTheme.typography.bodyMedium
            )
            if (interest.seedPageId == null) {
                Text("Matched against your saved text.", style = MaterialTheme.typography.bodySmall)
            }
            if (state.interestMatch.interestId == subject && state.interestMatch.loading) {
                Text("Finding matching cached Notes…")
            }
            if (state.interestMatch.interestId == subject) {
                state.interestMatch.message?.let { Text(it) }
            }
            if (!modelInstalled) {
                TextButton(
                    onClick = { app.navigator.open(ReaderDestination.SettingsDiscovery, "interest_model") },
                    modifier = Modifier.restoreFocusOn(nav.focusTarget, "interest_model", app::focusHandled)
                ) {
                    Text("Set up Discovery")
                }
            }
            if (visible.isNotEmpty()) {
                Button(
                    onClick = {
                        app.prepareDiscoveryPreview(visible, "Interest: $name" + if (olderOnly) " · Older matching Notes" else "")
                    },
                    modifier = Modifier.restoreFocusOn(nav.focusTarget, "preview_guided", app::focusHandled)
                ) {
                    Text("Preview listening")
                }
            }
            if (older.isNotEmpty() || olderOnly) {
                TextButton(onClick = { olderOnly = !olderOnly }) {
                    Text(if (olderOnly) "All matching Notes" else "Older matching Notes")
                }
            }
            Text("Matching Notes", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            if (olderOnly) {
                Text("Last edited at least 90 days ago; unknown dates excluded.")
            }
            if (state.interestMatch.interestId == subject &&
                state.interestMatch.outcome is com.example.notionreader.application.InterestMatchOutcome.Ready)
                Text("${visible.size} matching cached Notes", style = MaterialTheme.typography.bodySmall)
            Column(
                modifier = Modifier.semantics { collectionInfo = CollectionInfo(visible.size, 1) },
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                visible.forEach { match ->
                    key(match.pageId) {
                        DiscoveryNoteRow(match.noteUi(), onOpenNote, nav.focusTarget, app::focusHandled)
                    }
                }
            }
            TextButton(onClick = { scope.launch { controller.loadInterestMatches(interest.id, force = true) } },
                enabled = state.canSearch && !state.interestMatch.loading) {
                Text("Refresh matches")
            }
            if (interest.seedPageId != null) {
                TextButton(
                    onClick = {
                        app.navigator.open(ReaderDestination.DiscoverySeeds, "interest_seed:${interest.id}", interest.id)
                    },
                    modifier = Modifier.restoreFocusOn(nav.focusTarget, "interest_seed:${interest.id}", app::focusHandled)
                ) {
                    Text("Change seed Note")
                }
            } else {
                TextButton(onClick = { editing = true }) {
                    Text("Edit interest")
                }
            }
            TextButton(onClick = { scope.launch { controller.removeInterest(interest.id); app.back() } }) {
                Text("Remove interest")
            }
            if (editing) {
                InterestDefinitionDialog(
                    initialName = interest.displayName ?: interest.text,
                    initialText = interest.text,
                    title = "Edit interest",
                    onCancel = { editing = false }
                ) { newName, newText ->
                    scope.launch {
                        controller.updateFollow(interest.id, newName, newText)
                        editing = false
                    }
                }
            }
        }

        Button(onClick = { app.back() }) {
            Text("Back")
        }
    }
}
