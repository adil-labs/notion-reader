package com.example.notionreader.ui

import com.example.notionreader.domain.discovery.*

internal data class ConnectionWindowRequest(val offset: Int, val limit: Int)

/** Revealed row positions outlive their evidence; only nearby pages are materialized. */
internal fun connectionWindowRequest(firstVisible: Int, revealed: Int, total: Int): ConnectionWindowRequest {
    val count = minOf(revealed.coerceAtLeast(0), total.coerceAtLeast(0))
    if (count == 0) return ConnectionWindowRequest(0, 0)
    val page = firstVisible.coerceIn(0, count - 1) / 20
    val start = ((page - 1).coerceAtLeast(0) * 20).coerceAtMost(count)
    return ConnectionWindowRequest(start, minOf(60, count - start))
}

internal data class ConnectionPresentation(val connections: List<NoteConnection>,
    val evidence: Map<String, ConnectionEvidence>, val totalCount: Int)

internal fun prepareConnectionWindow(catalog: List<NoteConnection>, titles: Map<String, String>,
    offset: Int = 0, limit: Int = 20, selectedId: String? = null): ConnectionPresentation {
    require(offset >= 0 && limit >= 0)
    val window = if (selectedId != null) listOfNotNull(catalog.firstOrNull { it.id == selectedId })
        else catalog.subList(minOf(offset, catalog.size), minOf(catalog.size.toLong(), offset.toLong() + limit).toInt())
    val evidence = linkedMapOf<String, ConnectionEvidence>()
    val visible = window.filter { pair ->
        val classified = classifyConnectionEvidence(pair, titles)
        if (pair.independentNamedReference && classified.kind != ConnectionEvidenceKind.NamedReference) false
        else { evidence[pair.id] = classified; true }
    }
    return ConnectionPresentation(visible, evidence, catalog.size)
}

internal fun prepareConnectionPreview(catalog: List<NoteConnection>, titles: Map<String, String>): ConnectionPresentation {
    val order = compareByDescending<NoteConnection> { it.score }.thenBy { it.id }
    val selected = mutableListOf<Pair<NoteConnection, ConnectionEvidence>>()
    for (candidate in catalog) {
        if (canonicalSemanticId(candidate.left.pageId) == canonicalSemanticId(candidate.right.pageId)) continue
        if (selected.size == 2 && order.compare(candidate, selected.last().first) >= 0) continue
        val classified = classifyConnectionEvidence(candidate, titles)
        if (candidate.independentNamedReference && classified.kind != ConnectionEvidenceKind.NamedReference) continue
        val prior = selected.indexOfFirst { it.first.id == candidate.id }
        if (prior >= 0) {
            if (order.compare(candidate, selected[prior].first) >= 0) continue
            selected.removeAt(prior)
        }
        selected += candidate to classified
        selected.sortWith { a, b -> order.compare(a.first, b.first) }
        if (selected.size > 2) selected.removeAt(selected.lastIndex)
    }
    return ConnectionPresentation(selected.map { it.first }, selected.associate { it.first.id to it.second }, catalog.size)
}
