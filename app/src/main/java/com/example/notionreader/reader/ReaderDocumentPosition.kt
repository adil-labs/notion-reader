package com.example.notionreader.reader

/**
 * A structural position in a document, expressed only in stable identities.
 *
 * This is the handoff type the integration spec requires: the Notion layer's job ends at *exposing*
 * stable page, block, section and chunk identities, and the future Active Queue & Reader Session
 * specification owns everything about what to do with them. Sharing this type in the reader package
 * rather than in `notion` is the point — nothing here mentions Notion, so the session layer can
 * persist and restore a position without depending on the integration layer's DTOs.
 *
 * Every field is nullable because a position can be partially known: a session that only ever saw a
 * section boundary still has a usable [sectionId]. At least one field must be set for the position to
 * be meaningful, which is what [isUsable] asserts.
 *
 * [characterOffset] is carried but never populated in the first implementation — v1 resumes at chunk
 * boundaries. It exists now so that adding finer offsets later is not a schema migration, which the
 * spec settled explicitly.
 *
 * Deliberately **not** included: a numeric chunk index. The spec is unambiguous that "numeric chunk
 * indexes alone are not stable persistence identities because document edits can shift indexes", so
 * carrying one would invite exactly the bug this type exists to prevent. A caller that has an index
 * must convert it with [ReaderDocument.positionAt] while it still holds the document.
 */
data class ReaderDocumentPosition(
    val sourceBlockId: String? = null,
    val chunkId: String? = null,
    val sectionId: String? = null,
    val characterOffset: Int = 0
) {
    /** True when the position carries something that can be looked up. */
    val isUsable: Boolean
        get() = !sourceBlockId.isNullOrBlank() ||
            !chunkId.isNullOrBlank() ||
            !sectionId.isNullOrBlank()

    companion object {
        /** A position identified by a section alone, for a session that has only ever seen one. */
        fun atSection(sectionId: String): ReaderDocumentPosition =
            ReaderDocumentPosition(sectionId = sectionId)
    }
}

/**
 * How precisely a saved position could be located in a freshly loaded document.
 *
 * Ordered from most to least precise; the declaration order is the order in which resolution tries
 * them, so a more precise identity always wins over a weaker one.
 */
enum class PositionMatch {
    /** The exact chunk still exists. */
    CHUNK,

    /** The source block survived but that particular part did not — the block was shortened, lengthened, or re-split. */
    BLOCK,

    /** The block is gone, but the section it lived in survives, so the reading position is still meaningful. */
    SECTION,

    /** Nothing survived: neither the block nor the section. */
    NONE
}

/**
 * Where a saved position now lands, and how much of it survived.
 *
 * This reports **facts, not policy**. The spec is explicit that the integration layer "must not itself
 * decide whether playback resumes, where Continue falls back, or what the user hears when a saved
 * position no longer exists" — those belong to the session layer. So there is no `shouldResume` and no
 * chosen fallback here; there is only what is still true about the document, which is what a session
 * layer needs in order to decide.
 *
 * [paragraphIndex] is the position a caller *could* use, not one it must: for [PositionMatch.SECTION]
 * it points at the first paragraph of the surviving section, which is the narrowest truthful answer.
 */
data class ReaderPositionResolution(
    val match: PositionMatch,
    /** Index into [ReaderDocument.chunks], or null when nothing survived. */
    val paragraphIndex: Int?,
    /** The section the resolution lands in now, which may differ from the saved one. */
    val resolvedSectionId: String?,
    /** Whether the saved [ReaderDocumentPosition.sourceBlockId] still exists anywhere. */
    val blockSurvived: Boolean,
    /** Whether the saved [ReaderDocumentPosition.sectionId] still exists. */
    val sectionSurvived: Boolean
) {
    val isExact: Boolean get() = match == PositionMatch.CHUNK

    /** True when the position could not be located at all, so the session layer must choose a fallback. */
    val isLost: Boolean get() = match == PositionMatch.NONE

    companion object {
        val Lost = ReaderPositionResolution(
            match = PositionMatch.NONE,
            paragraphIndex = null,
            resolvedSectionId = null,
            blockSurvived = false,
            sectionSurvived = false
        )
    }
}

/**
 * Builds the stable position for a paragraph index.
 *
 * The index is a *transient* playback coordinate, and this converts it to identities while the
 * document that defines it is still in hand — which is the only moment the conversion can be made.
 * An out-of-range index yields an unusable position rather than throwing: playback can legitimately
 * ask for the position of a document it is about to find empty.
 */
fun ReaderDocument.positionAt(paragraphIndex: Int): ReaderDocumentPosition {
    val chunk = chunks.getOrNull(paragraphIndex) ?: return ReaderDocumentPosition()
    val section = sectionOfChunk(chunk.id)

    return ReaderDocumentPosition(
        sourceBlockId = chunk.sourceBlockId.ifBlank { null },
        chunkId = chunk.id,
        sectionId = section?.id
    )
}

/**
 * Reports what a saved position still refers to in this document.
 *
 * Resolution tries the strongest identity first — exact chunk, then source block, then section — and
 * reports which one answered. Deleting a block therefore does not silently move the caller to whatever
 * now occupies the old index, which is the failure the spec forbids: "removed or changed structures
 * are represented accurately rather than silently remapped by numeric indexes."
 */
fun ReaderDocument.resolve(position: ReaderDocumentPosition): ReaderPositionResolution {
    val sectionSurvived = position.sectionId
        ?.let { saved -> sections.any { it.id == saved } }
        ?: false

    val chunkIndexById = chunks.withIndex().associate { (index, chunk) -> chunk.id to index }

    position.chunkId?.let { saved ->
        val index = chunkIndexById[saved]
        if (index != null) {
            return resolution(
                match = PositionMatch.CHUNK,
                paragraphIndex = index,
                chunkId = saved,
                blockSurvived = true,
                sectionSurvived = sectionSurvived
            )
        }
    }

    position.sourceBlockId?.let { block ->
        // The block survives but this part did not. Anchoring to the block's first remaining part is
        // the least surprising landing place: it is the start of the same content, not the middle of
        // a re-split that the listener never asked to be dropped into.
        val index = chunks.indexOfFirst { it.sourceBlockId == block }
        if (index >= 0) {
            return resolution(
                match = PositionMatch.BLOCK,
                paragraphIndex = index,
                chunkId = chunks[index].id,
                blockSurvived = true,
                sectionSurvived = sectionSurvived
            )
        }
    }

    position.sectionId?.let { saved ->
        val index = sectionStartIndex(saved)
        if (index != null) {
            return resolution(
                match = PositionMatch.SECTION,
                paragraphIndex = index,
                chunkId = chunks[index].id,
                blockSurvived = false,
                sectionSurvived = true
            )
        }
    }

    return ReaderPositionResolution.Lost.copy(sectionSurvived = sectionSurvived)
}

/** The section a chunk id belongs to, or null when the id is unknown. */
fun ReaderDocument.sectionOfChunk(chunkId: String): ReaderSection? =
    sections.firstOrNull { section -> section.chunks.any { it.id == chunkId } }

/** Index of a section's first chunk in the flat chunk list, or null when the section is unknown or empty. */
private fun ReaderDocument.sectionStartIndex(sectionId: String): Int? {
    var start = 0
    for (section in sections) {
        if (section.chunks.isEmpty()) continue
        if (section.id == sectionId) return start
        start += section.chunks.size
    }
    return null
}

private fun ReaderDocument.resolution(
    match: PositionMatch,
    paragraphIndex: Int,
    chunkId: String,
    blockSurvived: Boolean,
    sectionSurvived: Boolean
) = ReaderPositionResolution(
    match = match,
    paragraphIndex = paragraphIndex,
    resolvedSectionId = sectionOfChunk(chunkId)?.id,
    blockSurvived = blockSurvived,
    sectionSurvived = sectionSurvived
)
