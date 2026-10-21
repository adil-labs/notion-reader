package com.example.notionreader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.ReaderApp
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.launch

/**
 * Screen displaying comparison and evidence details for a single connection between two Notes.
 */
@Composable
internal fun ConnectionDetailsScreen(
    app: ReaderApp,
    onOpenNote: (String) -> Unit
) {
    val controller = app.discovery ?: return
    val state by controller.state.collectAsState()
    val nav by app.navigator.state.collectAsState()
    val scope = rememberCoroutineScope()
    val subject = nav.currentSubjectId

    val connections = state.topicSnapshot.connections
    val titles = state.cachedTitles
    val reference = state.topicSnapshot.catalogReference
    val storedSelection = reference != null && subject != null

    var page by remember(connections, reference, titles, state.cachedRevision, subject) {
        mutableStateOf(if (storedSelection) null else ConnectionCatalogPageResult(connections, connections.size))
    }
    LaunchedEffect(connections, reference, titles, state.cachedRevision, subject) {
        if (storedSelection) {
            page = controller.connectionWindow(
                state.topicSnapshot,
                selectedId = subject,
                expectedCacheRevision = state.cachedRevision
            )
        }
    }
    val presentation = remember(page, titles, subject) {
        val rows = page?.connections.orEmpty()
        prepareConnectionWindow(rows, titles, limit = 0, selectedId = subject)
    }

    var preparingConnection by remember(subject) { mutableStateOf(false) }
    var connectionPreviewMessage by remember(subject) { mutableStateOf<String?>(null) }
    val loading = storedSelection && page == null

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val connection = presentation.connections.firstOrNull { it.id == subject }

        Text(
            text = "Compare passages",
            modifier = Modifier.restoreFocusOn(
                nav.focusTarget?.takeIf { connection == null },
                nav.focusTarget,
                app::focusHandled
            ).semantics {
                heading()
                paneTitle = "Connection details"
            },
            style = MaterialTheme.typography.headlineMedium
        )

        if (loading) {
            Text("Loading saved connection…")
        } else if (connection == null) {
            Text("Connection currently unavailable")
        } else {
            val evidence = presentation.evidence.getValue(connection.id)
            val kind = evidence.kind
            Text(kind.label(), style = MaterialTheme.typography.titleSmall)
            Text(
                if (connection.independentNamedReference && kind == ConnectionEvidenceKind.NamedReference)
                    "A source passage explicitly names the other cached Note by title. Target context does not establish agreement or a verified Notion hyperlink."
                else if (kind == ConnectionEvidenceKind.NamedReference)
                    "A source passage explicitly names the other cached Note by title."
                else "These indexed passages matched by similarity.",
                style = MaterialTheme.typography.bodyMedium
            )
            listOf(connection.left, connection.right).forEach { member ->
                if (connection.independentNamedReference && evidence.references.any {
                    it.targetPageId == canonicalSemanticId(member.pageId)
                }) {
                    Text("Target context; not evidence of thematic similarity", style = MaterialTheme.typography.labelLarge)
                }
                DiscoveryNoteRow(
                    note = member.toMatch().noteUi(),
                    onOpenNote = onOpenNote,
                    focusTarget = nav.focusTarget,
                    onFocusHandled = app::focusHandled,
                    excerptMaxLines = Int.MAX_VALUE
                )
            }
            Button(
                onClick = {
                    preparingConnection = true
                    connectionPreviewMessage = null
                    val inspected = state.topicSnapshot
                    val revision = state.cachedRevision
                    scope.launch {
                        try {
                            val matches = controller.connectionPreview(connection.id, inspected, revision)
                            if (matches.size == 2) app.prepareDiscoveryPreview(matches, kind.label())
                            else connectionPreviewMessage = "Connection currently unavailable. Refresh discoveries."
                        } finally {
                            preparingConnection = false
                        }
                    }
                },
                enabled = !preparingConnection,
                modifier = Modifier.restoreFocusOn(nav.focusTarget, "preview_guided", app::focusHandled)
            ) {
                Text(if (preparingConnection) "Preparing listening preview…" else "Preview listening")
            }
            connectionPreviewMessage?.let {
                Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }

        Button(onClick = { app.back() }) {
            Text("Back")
        }
    }
}
