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
import com.example.notionreader.application.*
import com.example.notionreader.domain.discovery.canonicalSemanticId
import kotlinx.coroutines.launch

@Composable internal fun InterestsScreen(app: ReaderApp) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
        .semantics { paneTitle = "Your interests" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InterestsContent(app, preview = false)
        TextButton(onClick = { app.back() }) { Text("Back") }
    }
}

@Composable internal fun InterestsContent(app: ReaderApp, preview: Boolean) {
    val controller = app.discovery
    val state = controller?.state?.collectAsState()?.value
    val nav by app.navigator.state.collectAsState()
    val scope = rememberCoroutineScope()
    var adding by rememberSaveable { mutableStateOf(false) }
    var describing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    Text("Your interests", Modifier.restoreFocusOn(nav.focusTarget?.takeIf { target ->
        target.startsWith("discovery_interest:") && state?.interests.orEmpty().none { "discovery_interest:${it.id}" == target }
    }, nav.focusTarget, app::focusHandled).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
    if (state == null) Text("Interest management is unavailable in this configuration.")
    else {
        if (!preview) DiscoveryStatusNotice(state)
        val interests = if (preview) state.interests.take(3) else state.interests
        if (interests.isEmpty()) Text("Follow a discovered topic or add an interest of your own.")
        Column(Modifier.semantics { collectionInfo = CollectionInfo(interests.size, 1) },
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            interests.forEach { interest -> key(interest.id) {
                OutlinedCard(onClick = { app.navigator.open(ReaderDestination.DiscoveryInterest,
                    "discovery_interest:${interest.id}", interest.id) }, modifier = Modifier.fillMaxWidth()
                    .restoreFocusOn(nav.focusTarget, "discovery_interest:${interest.id}", app::focusHandled)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(interest.displayName ?: interest.seedPageId?.let {
                            state.cachedTitles[canonicalSemanticId(it)] ?: "Seed Note unavailable"
                        } ?: interest.text, style = MaterialTheme.typography.titleMedium)
                        Text(if (interest.seedPageId != null && canonicalSemanticId(interest.seedPageId) !in state.cachedPageIds)
                            "Seed Note unavailable until recached" else "Matching Notes and listening preview")
                    }
                }
            } }
        }
        if (preview) TextButton(onClick = { app.openInterests("home_interests") },
            modifier = Modifier.restoreFocusOn(nav.focusTarget, "home_interests", app::focusHandled)) { Text("View all interests") }
        else Button(onClick = { adding = true }, modifier = Modifier.restoreFocusOn(nav.focusTarget, "discovery_seed", app::focusHandled)) { Text("Add interest") }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("Add interest") }, text = { Column {
        TextButton(onClick = { adding = false; describing = true }) { Text("Describe a topic") }
        Text("Choose a Note uses cached Notes only.")
        TextButton(onClick = { adding = false; app.navigator.open(ReaderDestination.DiscoverySeeds, "discovery_seed") }) { Text("Choose a Note") }
    } }, confirmButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
    if (describing) AlertDialog(onDismissRequest = { describing = false }, title = { Text("Describe a topic") },
        text = { OutlinedTextField(draft, { draft = it }, label = { Text("New interest") }) },
        confirmButton = { TextButton(onClick = { scope.launch { controller?.saveInterest(null, draft.trim()); describing = false; draft = "" } },
            enabled = draft.isNotBlank()) { Text("Save interest") } },
        dismissButton = { TextButton(onClick = { describing = false }) { Text("Cancel") } })
}
