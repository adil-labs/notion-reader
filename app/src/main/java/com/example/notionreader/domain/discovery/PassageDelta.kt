package com.example.notionreader.domain.discovery

/** Source references only; rebound maps old IDs to current IDs for identical evidence. */
data class PassageDelta(val changed: Set<String>, val removed: Set<String>, val rebound: Map<String, String>)

/** Compare content and provenance, not offsets or vector similarity. Never join duplicate occurrences. */
fun passageDelta(previous: SemanticRecord?, current: SemanticRecord): PassageDelta {
    val now = current.passages.map { it.passage }
    require(now.all { it.id.isNotBlank() && canonicalSemanticId(it.pageId) == canonicalSemanticId(current.pageId) })
    require(now.map { it.id }.toSet().size == now.size)
    val before = previous?.takeIf { canonicalSemanticId(it.pageId) == canonicalSemanticId(current.pageId) && it.identity == current.identity }
        ?.passages?.map { it.passage }.orEmpty()
    require(before.all { it.id.isNotBlank() && canonicalSemanticId(it.pageId) == canonicalSemanticId(current.pageId) })
    require(before.map { it.id }.toSet().size == before.size)
    fun key(p: SemanticPassage) = listOf(p.sectionId, p.sectionTitle.orEmpty(), p.sourceBlockId, p.readerChunkId, p.excerpt, p.input)
    val remaining = before.groupBy(::key).mapValues { (_, values) -> ArrayDeque(values.sortedBy { it.id }) }
    val changed = linkedSetOf<String>()
    val rebound = linkedMapOf<String, String>()
    val exact = before.associateBy { it.id }
    val unmatched = mutableListOf<SemanticPassage>()
    for (passage in now.sortedBy { it.id }) {
        val same = exact[passage.id]
        if (same != null && key(same) == key(passage)) {
            remaining[key(passage)]?.remove(same)
            rebound[same.id] = passage.id
        } else unmatched += passage
    }
    for (passage in unmatched) {
        val match = remaining[key(passage)]?.removeFirstOrNull()
        if (match == null) changed += passage.id else rebound[match.id] = passage.id
    }
    val removed = remaining.values.flatMap { queue -> queue.map { it.id } }.toSet()
    return PassageDelta(changed, removed, rebound)
}
