package com.example.notionreader.domain.session

import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.ReaderDocumentPosition
import kotlinx.serialization.Serializable

/**
 * The schema version of a stored session record.
 *
 * Bumped only by a change that makes an older record mean something different. A record whose version
 * is not this value is **discarded rather than migrated**, which is the policy the Active Queue & Reader
 * Session spec settles in "Schema versioning": a migration that guesses wrong restores a listener to a
 * place they were not, which is worse than an empty history the listener can rebuild by playing
 * something. That is a deliberate v1 choice and not an accident, which is why it is stated where the
 * version is declared rather than where the read happens.
 *
 * ## Why this is `2`
 *
 * `QueueOrigin.Saved` and `QueueOrigin.Smart` gained a **queue identity** and kept the name as a label, so the
 * stored origin now names a queue exactly rather than approximately. That is a change of *meaning*, not an
 * addition, and it is the case the policy above exists for.
 *
 * The bump is load-bearing rather than an artefact of the shape change, and that distinction matters. A v1
 * record whose origin is `{"type":"saved","name":"Reading"}` will not decode any more, so it is dropped whether
 * or not the version changed. But a v1 record whose origin is `{"type":"browse_results"}` or
 * `{"type":"single_note"}` decodes **perfectly** — those variants did not change — and would be restored under a
 * version this build no longer means anything different about. So the version check, not the wire shape, is what
 * refuses those, and `SessionWireFormatTest` asserts exactly that split.
 */
const val SESSION_SCHEMA_VERSION: Int = 2

/**
 * One listening session, as persisted.
 *
 * This is a **description, never a source of truth for content.** It stores identities and a position;
 * it stores no Note content, no derived playback coordinate, and no numeric chunk index. The Active
 * Queue & Reader Session spec lists each of those exclusions and why:
 *
 * - content is fetched, never cached, because a cache can disagree with the workspace and silently
 *   disagreeing with the workspace is what a reading app must not do;
 * - a numeric chunk index is excluded because the integration spec is unambiguous that "numeric chunk
 *   indexes alone are not stable persistence identities because document edits can shift indexes", and a
 *   stored index invites exactly the silent remapping it forbids;
 * - sections, spans and progress fractions are recomputed from the document after loading.
 *
 * ## Why [notes] rather than a page id
 *
 * The queue's *membership and order* are what make Continue able to resume a listening session rather
 * than just reopen a Note, and they are frozen at creation. Restoring them from storage is the same
 * operation as handing [ReaderSession.start] a snapshot, which is why [ActiveQueue.snapshotOf] is reused
 * rather than a second stored-list type being introduced.
 *
 * ## Why [position] is nullable and belongs to the session
 *
 * The Product contract lists maintaining permanent resume positions for individual Notes independently
 * of listening sessions as a non-goal. So exactly one position is stored, it belongs to the session, and
 * it belongs to the session's *current* Note — never to any other Note in [notes]. Null means the
 * session never got as far as knowing a position for its current Note, which is a different fact from a
 * position that could not be resolved, and the two are distinguishable: the second is
 * `PositionMatch.NONE` after resolution, at restore time.
 */
@Serializable
data class StoredSession(
    /** The session's own identity. History entries are identified by this, never by Note or title. */
    val id: String,
    /** The frozen Active Queue snapshot, in order. */
    val notes: List<QueuedNote>,
    /** Descriptive only — persisted for explanation, never used to re-derive [notes]. */
    val origin: QueueOrigin,
    /** Index of the current Note within [notes]. */
    val index: Int,
    /** The structural position within the current Note, expressed only in stable identities. */
    val position: ReaderDocumentPosition? = null,
    /** Whether the session reached the end of its queue. Completed sessions are replayable, not resumable. */
    val completed: Boolean = false,
    /** Whether playback was running when this record was written. Descriptive; nothing resumes on it. */
    val wasPlaying: Boolean = false,
    /** Epoch millis of the last listening activity. Ordering and retention both use this. */
    val lastActivityAt: Long,
    val schemaVersion: Int = SESSION_SCHEMA_VERSION
) {
    /**
     * Whether this record can be used at all.
     *
     * A record from an unknown schema version, or one with no Notes, describes nothing restorable. The
     * version check lives here rather than at the read site so that no future reader can forget it, and
     * so it is one predicate to test.
     */
    val isValid: Boolean
        get() = schemaVersion == SESSION_SCHEMA_VERSION && notes.isNotEmpty()

    /**
     * The Note this session was reading, or null when [index] does not address [notes].
     *
     * Clamped rather than thrown: a record is data from disk, and a record whose index is out of range is
     * a record to decline, not a reason for the reader to fail to start.
     */
    val currentNote: QueuedNote?
        get() = notes.getOrNull(index)
}
