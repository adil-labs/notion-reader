package com.example.notionreader.data.structure

import com.example.notionreader.data.notion.*
import java.util.concurrent.atomic.AtomicReference

/** One bounded, short-lived handoff from the existing reader fetch, never a second cache. */
class StructuralSnapshotHandoff(private val nanoTime: () -> Long = System::nanoTime) {
    data class Snapshot(val page: NotionPage, val roots: List<NotionBlock>, val children: Map<String, List<NotionBlock>>,
        val identity: String, val epoch: Long, val observedNanos: Long)
    private val pending = AtomicReference<Snapshot?>()
    fun clear() { pending.set(null) }
    fun offer(page: NotionPage, roots: List<NotionBlock>, children: Map<String, List<NotionBlock>>, identity: String, epoch: Long): Boolean {
        if (roots.size + children.values.sumOf { it.size } > 500) return false
        var bytes = notionJson.encodeToString(NotionPage.serializer(), page).toByteArray().size
        if (bytes > 512 * 1024) return false
        for (block in roots.asSequence() + children.values.asSequence().flatten()) {
            bytes += notionJson.encodeToString(NotionBlock.serializer(), block).toByteArray().size
            if (bytes > 512 * 1024) return false
        }
        pending.set(Snapshot(page, roots.toList(), children.mapValues { it.value.toList() }, identity, epoch, nanoTime()))
        return true
    }
    fun take(pageId: String, identity: String, epoch: Long): Snapshot? = pending.getAndSet(null)?.takeIf {
        com.example.notionreader.domain.structure.normalizeNotionId(it.page.id) == pageId && it.identity == identity &&
            it.epoch == epoch && nanoTime() - it.observedNanos in 0..30_000_000_000L
    }
}
