package com.example.notionreader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.*
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class CatalogWindow(val offset: Int, val rows: List<NoteConnection>,
    val evidence: Map<String, ConnectionEvidence>, val available: Boolean)

/** Saved row positions are cheap; literal evidence is retained only near the viewport. */
@Composable
internal fun ConnectionCatalogContent(app: ReaderApp) {
    val controller = app.discovery ?: return
    val state by controller.state.collectAsState()
    val nav by app.navigator.state.collectAsState()
    val snapshot = state.topicSnapshot
    val total = snapshot.catalogReference?.count ?: snapshot.connections.size
    var revealed by rememberSaveable { mutableStateOf(20) }
    val count = minOf(revealed, total)
    val scroll = rememberLazyListState()
    val request by remember(count, total) { derivedStateOf {
        connectionWindowRequest(scroll.firstVisibleItemIndex, count, total)
    } }
    var window by remember(snapshot.catalogReference, snapshot.connections, state.cachedTitles, state.cachedRevision) {
        mutableStateOf<CatalogWindow?>(null)
    }
    LaunchedEffect(request, snapshot.catalogReference, snapshot.connections, state.cachedTitles, state.cachedRevision) {
        val page = if (request.limit == 0) ConnectionCatalogPageResult(emptyList(), total)
            else controller.connectionWindow(snapshot, request.offset, request.limit,
                expectedCacheRevision = state.cachedRevision)
        val prepared = withContext(Dispatchers.Default) {
            prepareConnectionWindow(page.connections, state.cachedTitles, limit = page.connections.size)
        }
        // A rejected entry cannot shift all later rank positions into the wrong slots.
        window = CatalogWindow(request.offset, prepared.connections, prepared.evidence,
            page.available && prepared.connections.size == page.connections.size &&
                page.connections.size == minOf(request.limit, (page.totalCount - request.offset).coerceAtLeast(0)))
    }
    val current = window
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connections between Notes", Modifier.restoreFocusOn(
            nav.focusTarget?.takeIf { target -> target.startsWith("connection:") && current != null &&
                current.rows.none { "connection:${it.id}" == target } },
            nav.focusTarget, app::focusHandled).semantics { heading(); paneTitle = "Connections between Notes" })
        Text("Showing $count of $total connections", style = MaterialTheme.typography.bodySmall)
        if (current?.available == false) Text("Connections currently unavailable. Refresh discoveries.")
        if (total == 0 && current?.available == true) Text("No connections in this snapshot.")
        LazyColumn(state = scroll, modifier = Modifier.weight(1f).fillMaxWidth()
            .semantics { collectionInfo = CollectionInfo(count, 1); contentDescription = "Connection catalog" },
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(count, key = { it }) { position ->
                val pair = current?.takeIf { it.available }?.rows?.getOrNull(position - current.offset)
                if (pair == null) Box(Modifier.fillMaxWidth().height(160.dp)) {
                    Text(if (current?.available == false) "Connection unavailable" else "Preparing connection…")
                } else {
                    val evidence = current.evidence.getValue(pair.id)
                    val previous = current.rows.getOrNull(position - current.offset - 1)
                    val named = snapshot.catalogReference?.namedReferences
                        ?: current.evidence.values.count { it.kind == ConnectionEvidenceKind.NamedReference }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (previous == null || current.evidence[previous.id]?.kind != evidence.kind) {
                            val label = if (evidence.kind == ConnectionEvidenceKind.NamedReference)
                                "Named references ($named)" else "Similar passages (${total - named})"
                            Text(label, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                        }
                        ConnectionNavigationRow(pair, {
                            app.navigator.open(ReaderDestination.DiscoveryConnection, "connection:${pair.id}", pair.id)
                        }, nav.focusTarget, app::focusHandled, evidence.kind, connectionPreviewExcerpt(pair, evidence))
                    }
                }
            }
        }
        if (count < total) Button(onClick = { revealed = minOf(total.toLong(), revealed.toLong() + 20).toInt() },
            enabled = current?.available == true,
            modifier = Modifier.restoreFocusOn(nav.focusTarget, "more_connections", app::focusHandled)) {
            Text("More connections")
        }
    }
}
