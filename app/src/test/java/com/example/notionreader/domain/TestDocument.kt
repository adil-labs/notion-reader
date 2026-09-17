package com.example.notionreader.domain

import com.example.notionreader.data.notion.title
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.domain.session.SESSION_SCHEMA_VERSION
import com.example.notionreader.domain.session.StoredSession

/**
 * Documents for the session-persistence and resume tests.
 *
 * Chunk ids follow the integration spec's scheme — `"<sourceBlockId>:<part>"`, 0-based, applied
 * uniformly — because the resume tests turn on the difference between a chunk that survived and a block
 * that survived but was re-split, and that difference is expressed entirely in these two fields.
 */
internal fun testChunk(blockId: String, part: Int = 0, text: String = "Text of $blockId") = ReaderChunk(
    id = "$blockId:$part",
    sourceBlockId = blockId,
    kind = ReaderChunkKind.PARAGRAPH,
    text = text
)

internal fun testSection(id: String, title: String?, vararg chunks: ReaderChunk) = ReaderSection(
    id = id,
    title = title,
    level = if (title == null) 0 else 1,
    chunks = chunks.toList()
)

internal fun testDocument(vararg sections: ReaderSection) = ReaderDocument(
    pageId = "note-1",
    title = "A Note",
    sections = sections.toList()
)

/**
 * Three sections — One / Two / Three — with chunks `a b c | d e | f`, so flat indices are 0 through 5 and
 * a position can be saved at index 4 (`e`, in the titled section "Two").
 *
 * The middle section is the one every resolution test edits, because it is the only one that is neither
 * first nor last: a deleted block there has a surviving section on both sides, which is what makes the
 * difference between a correct SECTION resolution and an accidental index-based one observable.
 */
internal fun originalDocument() = testDocument(
    testSection("s1", "One", testChunk("a"), testChunk("b"), testChunk("c")),
    testSection("s2", "Two", testChunk("d"), testChunk("e")),
    testSection("s3", "Three", testChunk("f"))
)

internal fun queuedNote(pageId: String, title: String = "Note $pageId") = QueuedNote(pageId, title)

internal fun storedSession(
    id: String = "session-1",
    notes: List<QueuedNote> = listOf(queuedNote("p1")),
    origin: QueueOrigin = QueueOrigin.SingleNote,
    index: Int = 0,
    position: ReaderDocumentPosition? = null,
    completed: Boolean = false,
    wasPlaying: Boolean = false,
    lastActivityAt: Long = 1_000L,
    schemaVersion: Int = SESSION_SCHEMA_VERSION
) = StoredSession(
    id = id,
    notes = notes,
    origin = origin,
    index = index,
    position = position,
    completed = completed,
    wasPlaying = wasPlaying,
    lastActivityAt = lastActivityAt,
    schemaVersion = schemaVersion
)
