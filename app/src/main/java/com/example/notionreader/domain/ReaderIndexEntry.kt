package com.example.notionreader.domain

import com.example.notionreader.domain.queue.QueuedNote
import java.time.Instant

/**
 * One discoverable page: a stable identity, a name to show, and when it last changed.
 *
 * This lives in the **reader domain** rather than in `browse` because of where the product contract puts
 * it. Its architecture is:
 *
 * ```plain text
 * Notion integration layer → Normalized Notion content → Reader domain
 *     ├── ReaderDocument
 *     └── normalized discovery metadata
 * → Browse / Saved Queue / Smart Queue / Active Queue / Session domain
 * ```
 *
 * "Normalized discovery metadata" is a reader-domain concept that Browse *consumes*, so the integration
 * layer must be able to produce it without knowing that Browse exists. Putting this type in `browse` and
 * returning it from `NotionRepository` would have made the integration layer depend on a downstream
 * domain — the dependency pointing the wrong way down that diagram, which is the one direction the
 * boundary rules exist to prevent.
 *
 * ## What it is, and what it is not
 *
 * It is deliberately the same identity as [QueuedNote] plus a timestamp. That is what makes `Play all` a
 * direct projection of a result set rather than a translation: the rows *are* queue entries, so no
 * mapping step can drop or reorder one.
 *
 * It is **not** the integration spec's `ReaderLibraryEntry`. That type carries kind, area, topics,
 * project and status for filtering, and `docs/decisions.md` §5 records why it cannot be built yet: no
 * data source in this workspace has a `relation` property, `Status` vocabularies differ per schema, Area
 * is a per-schema union rather than a controlled vocabulary, and `ReaderEntryKind`/`ReaderSourceSchema`
 * have no value for two of the accessible databases. This type is what the *default* result set needs —
 * which the contract requires to work before any filter does ("Browse must not open as an empty
 * screen") — and it will be widened by that type rather than replaced by it, because "which page" and
 * "what else is known about it" are different questions.
 */
data class ReaderIndexEntry(
    val pageId: String,
    val title: String,
    val lastEditedAt: Instant? = null,
    val kind: String? = null,
    val area: String? = null,
    val topics: Set<String> = emptySet(),
    val project: String? = null,
    val status: String? = null
) {
    /** The queue identity for this page, so a result set can be snapshotted into a queue unchanged. */
    fun toQueuedNote(): QueuedNote = QueuedNote(pageId = pageId, title = title)
}
