package com.example.notionreader.domain.discovery

/** Implementations preserve descending score / ascending canonical pair identity. */
interface RankedConnectionCatalog : List<NoteConnection>

private val catalogOrder = compareByDescending<NoteConnection> { it.score }.thenBy { it.id }
private val evidenceOrder = compareByDescending<NoteConnection> { it.score }
    .thenBy { it.left.passage.id }.thenBy { it.right.passage.id }

private open class IndexedCatalog(private val base: List<NoteConnection>, private val updates: List<NoteConnection>,
    private val routes: IntArray) : AbstractList<NoteConnection>() {
    val depth: Int = (base as? IndexedCatalog)?.depth?.plus(1) ?: 1
    override val size get() = routes.size
    override fun get(index: Int): NoteConnection {
        val route = routes[index]
        return if (route >= 0) base[route] else updates[-route - 1]
    }
}
private class RankedIndexedCatalog(base: List<NoteConnection>, updates: List<NoteConnection>, routes: IntArray) :
    IndexedCatalog(base, updates, routes), RankedConnectionCatalog

/** Keeps only the update batch's evidence plus integer routes into the immutable base. */
fun mergeConnectionUpdates(base: List<NoteConnection>, updates: List<NoteConnection>,
    checkActive: () -> Unit = {}): List<NoteConnection> {
    checkActive()
    // In-memory adapters may have no storage round-trip to flatten their immutable generations.
    if (base is IndexedCatalog && base.depth >= 4) return mergeConnectionUpdates(base.toList(), updates, checkActive)
    if (updates.isEmpty() && base is RankedConnectionCatalog) return base
    val best = linkedMapOf<String, NoteConnection>()
    updates.forEachIndexed { index, pair ->
        if (index % 64 == 0) checkActive()
        val prior = best[pair.id]
        if (prior == null || evidenceOrder.compare(pair, prior) < 0) best[pair.id] = pair
    }
    base.forEachIndexed { index, prior ->
        if (index % 64 == 0) checkActive()
        val candidate = best[prior.id]
        if (candidate != null && evidenceOrder.compare(candidate, prior) >= 0) best.remove(prior.id)
    }
    if (best.isEmpty() && base is RankedConnectionCatalog) return base
    var comparisons = 0
    val order = Comparator<NoteConnection> { a, b ->
        if (comparisons++ % 64 == 0) checkActive()
        catalogOrder.compare(a, b)
    }
    val added = best.values.sortedWith(order)
    val survivors = base.indices.filter { index ->
        if (index % 64 == 0) checkActive()
        base[index].id !in best
    }
    val old = if (base is RankedConnectionCatalog) survivors else survivors.sortedWith { a, b -> order.compare(base[a], base[b]) }
    val routes = IntArray(old.size + added.size)
    var left = 0; var right = 0
    for (position in routes.indices) {
        if (position % 64 == 0) checkActive()
        routes[position] = if (right >= added.size || left < old.size && order.compare(base[old[left]], added[right]) <= 0)
            old[left++] else -++right
    }
    return RankedIndexedCatalog(base, added, routes)
}

/** Non-owning projection. Source removal must purge/rewrite the owning catalog, not only this view. */
fun filterConnectionCatalog(base: List<NoteConnection>, checkActive: () -> Unit = {},
    eligible: (NoteConnection) -> Boolean): List<NoteConnection> {
    val positions = base.indices.filter { index ->
        if (index % 64 == 0) checkActive()
        eligible(base[index])
    }
    if (positions.size == base.size) return base
    val routes = positions.toIntArray()
    return if (base is RankedConnectionCatalog) RankedIndexedCatalog(base, emptyList(), routes)
        else IndexedCatalog(base, emptyList(), routes)
}
