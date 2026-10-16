package com.example.notionreader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.ReaderApp

@Composable
internal fun HomeDashboard(app: ReaderApp, onOpenNote: (String) -> Unit,
    onContinue: (String) -> Unit, onReplay: (String) -> Unit) {
    val history by app.history.collectAsState()
    val playback by app.session.state.collectAsState()
    val nav by app.navigator.state.collectAsState()
    val notes = app.recentNotes?.cards?.collectAsState()?.value.orEmpty()
    val primary = history.let { playback; app.continueListeningCards(1).firstOrNull() }
    val recent = history.let { app.homeRecentListeningEntries() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
        .semantics { paneTitle = "Home" }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Home", Modifier.restoreFocusOn(nav.focusTarget?.takeIf { target ->
            target != primary?.sessionId && recent.none { it.sessionId == target } && notes.none { it.pageId == target } &&
                !target.startsWith("discovery_interest:") && target != "home_interests" && target != "home_history"
        }, nav.focusTarget, app::focusHandled).semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        if (primary != null) {
            HomeHeading("Continue listening")
            ContinueListeningEntry(primary, System.currentTimeMillis(), { onContinue(primary.sessionId) },
                nav.focusTarget, app::focusHandled, compact = true)
        } else {
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("Ready when you are", style = MaterialTheme.typography.titleMedium)
                Text("Open a Note or choose something to listen to.")
                TextButton(onClick = app::openBrowse) { Text("Browse Notes") }
                TextButton(onClick = app::openDiscover) { Text("Discover your Notes") }
            } }
        }
        HomeHeading("Recent listening")
        if (recent.isEmpty()) Text("Other recent listening sessions will appear here.")
        recent.forEach { entry -> key(entry.sessionId) {
            val title = recentListeningTitle(entry)
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .restoreFocusOn(nav.focusTarget, entry.sessionId, app::focusHandled)
                .clickable(onClickLabel = if (entry.isCompleted) "Play again" else "Continue listening") {
                    if (entry.isCompleted) onReplay(entry.sessionId) else onContinue(entry.sessionId)
                }.semantics(mergeDescendants = true) {}.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (title != entry.title) Text(entry.title, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(entry.membership, entry.stateDescription).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (entry.isCompleted) "Play again" else "Continue", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        } }
        TextButton(onClick = app::openQueues, modifier = Modifier.restoreFocusOn(nav.focusTarget, "home_history", app::focusHandled)) { Text("View listening history and queues") }
        HomeHeading("Recently opened Notes")
        if (notes.isEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("No cached recent Notes", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = app::openBrowse) { Text("Browse Notes") }
        }
        notes.forEach { note -> key(note.pageId) {
            OutlinedCard(onClick = { onOpenNote(note.pageId) }, modifier = Modifier.fillMaxWidth()
                .restoreFocusOn(nav.focusTarget, note.pageId, app::focusHandled)) {
                Text(note.title, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } }
        InterestsContent(app, preview = true)
    }
}

@Composable private fun HomeHeading(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
}
