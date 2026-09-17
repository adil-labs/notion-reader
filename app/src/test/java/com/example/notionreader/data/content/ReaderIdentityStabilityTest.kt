package com.example.notionreader.data.content

import com.example.notionreader.data.notion.FixtureNotionApi
import com.example.notionreader.data.notion.NotionRepository
import com.example.notionreader.domain.PositionMatch
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderDocumentPosition
import com.example.notionreader.domain.positionAt
import com.example.notionreader.domain.resolve
import com.example.notionreader.domain.sectionOfChunk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5: proves that unchanged content keeps its identity across an edit.
 *
 * The integration spec asks for exactly this — "expose stable page, source-block, section, and chunk
 * identities, and prove that unchanged content produces unchanged identities across edits" — and its
 * test strategy lists four properties: unchanged blocks, unchanged sections and unchanged chunk
 * construction all produce stable identities, and "removed or changed structures are represented
 * accurately rather than silently remapped by numeric indexes".
 *
 * ## Why there are two snapshots rather than one hand-edited JSON
 *
 * Our mapper derives every id from the Notion block id, so it cannot renumber anything. A test that
 * edited a JSON string and fed it back would therefore prove only that our own code is
 * self-consistent — it would not test the assumption the whole scheme rests on, which is a fact about
 * **Notion**, not about us: *does a block keep its id when it, or its neighbours, change?*
 *
 * So `tools/verify-identity-stability.ps1` asks Notion directly. It makes three real edits to the
 * fixture page, captures what the API reports, and restores the page (throwing if the restore does not
 * verify byte-for-byte on ids and text). The edits are chosen to cover every identity the spec names:
 *
 * | Edit | Identity under test |
 * |---|---|
 * | one paragraph's text changed in place | block id and chunk id survive **their own** content changing |
 * | one paragraph appended, then really deleted | insertion and deletion move no other identity |
 * | one heading blanked | a vanished **section** shifts every later paragraph index, without moving any id |
 *
 * The third edit is the one that matters most for the spec's last clause. Blanking a heading produces
 * no section and no chunk (D4), so a whole section disappears and every later paragraph shifts — which
 * is precisely the edit that would silently redirect a position saved as a numeric index. The tests
 * below assert that the ids do not move while the indexes do.
 */
class ReaderIdentityStabilityTest {

    private val pageId = "3dec9345-e10a-8180-867d-fa96b49bd30b"

    /** Text of the paragraph the script appended, used to find it without knowing its block id. */
    private companion object {
        const val APPENDED_TEXT = "Paragraph added after the original capture"
    }

    private object Blocks {
        /** Edited in place: its text differs between the two snapshots. */
        const val MODIFIED_PARAGRAPH = "3dec9345-e10a-81ce-a1a0-f5eaf14193b1"

        /** Blanked: an empty heading, so it yields no section and no chunk (D4). */
        const val BLANKED_HEADING = "3dec9345-e10a-81ad-afda-e576ba917ae4"

        /** A heading that survives the edit, and whose section must keep its id. */
        const val SURVIVING_HEADING = "3dec9345-e10a-81b2-8de5-d4a070b01d1e"

        /** A long, split paragraph that survives the edit. */
        const val LONG_PARAGRAPH = "3dec9345-e10a-815d-a804-ce592e7b7181"

        /** A block that sits *after* the blanked heading, so its paragraph index must shift. */
        const val QUOTE_AFTER_BLANKED_HEADING = "3dec9345-e10a-814a-b203-c60426c9030f"
    }

    private suspend fun load(path: String): ReaderDocument =
        NotionRepository(FixtureNotionApi.fromResource(path)).getDocument(pageId)

    /** The document as it is today. */
    private suspend fun original(): ReaderDocument = load("/fixtures/reader-fixture.json")

    /** The same page with three edits applied, captured from the real API mid-edit. */
    private suspend fun edited(): ReaderDocument = load("/fixtures/reader-fixture-edited.json")

    // ---------------------------------------------------------------- the edits are real

    /**
     * Without this the rest of the file could be comparing two snapshots that happen to be identical,
     * and every "identities are stable" assertion would pass vacuously.
     */
    @Test
    fun theEditedSnapshotReallyDiffersFromTheOriginal() = runTest {
        val original = original()
        val edited = edited()

        assertEquals("same page in both snapshots", original.pageId, edited.pageId)
        assertNotEquals(
            "the edited paragraph's text must actually differ",
            original.chunks.single { it.sourceBlockId == Blocks.MODIFIED_PARAGRAPH }.text,
            edited.chunks.single { it.sourceBlockId == Blocks.MODIFIED_PARAGRAPH }.text
        )
        assertNotEquals(
            "blanking a heading removes its section, so the section count must change",
            original.sections.size,
            edited.sections.size
        )
        assertTrue(
            "the appended paragraph must be present in the edited snapshot",
            edited.chunkTexts.any { it.contains(APPENDED_TEXT) }
        )
        assertTrue(
            "and absent from the original",
            original.chunkTexts.none { it.contains(APPENDED_TEXT) }
        )
        assertTrue(
            "the blanked heading must have produced a chunk before the edit",
            original.chunks.any { it.sourceBlockId == Blocks.BLANKED_HEADING }
        )
        assertTrue(
            "and no chunk after it (D4)",
            edited.chunks.none { it.sourceBlockId == Blocks.BLANKED_HEADING }
        )
    }

    /**
     * The blanked heading produces no section and no chunk (D4), while its *following* blocks stay in
     * the enclosing section — so the edit grows the preceding section rather than deleting content.
     *
     * The consequence is that later paragraphs move up by exactly one position: the heading's own
     * chunk. One position is enough. Any positional scheme breaks on the very first edit, and the
     * section count changing from four to three breaks positional *section* references too.
     */
    @Test
    fun aVanishedSectionShiftsParagraphIndexes() = runTest {
        val original = original()
        val edited = edited()

        val quote = quoteText(original)
        val originalIndex = original.chunkTexts.indexOf(quote)
        val editedIndex = edited.chunkTexts.indexOf(quote)

        assertTrue("both snapshots must contain the quote", originalIndex >= 0 && editedIndex >= 0)
        assertTrue(
            "blanking the heading must move later paragraphs, otherwise this edit proves nothing " +
                "about index-based lookups (was $originalIndex, now $editedIndex)",
            editedIndex < originalIndex
        )
        assertEquals(
            "exactly the heading's own chunk disappears (D4 keeps its content)",
            1,
            originalIndex - editedIndex
        )
        assertEquals(
            "and the section it defined is gone, so positional section references break too",
            original.sections.size - 1,
            edited.sections.size
        )
    }

    // ---------------------------------------------------------------- block and chunk identity

    /**
     * The central stability claim: for every block present in both versions, the chunks it produces
     * have exactly the same ids. Not "similar" and not "resolvable via a lookup" — identical.
     */
    @Test
    fun everySurvivingBlockKeepsItsExactChunkIds() = runTest {
        val originalIds = original().chunks.groupBy({ it.sourceBlockId }, { it.id })
        val editedIds = edited().chunks.groupBy({ it.sourceBlockId }, { it.id })

        val sharedBlocks = originalIds.keys intersect editedIds.keys
        assertTrue("the snapshots must share most of their blocks", sharedBlocks.size >= 20)

        val moved = sharedBlocks.filter { originalIds[it] != editedIds[it] }
        assertTrue(
            "these blocks changed their chunk ids even though the blocks survived: $moved",
            moved.isEmpty()
        )
    }

    /** A block whose own text was edited keeps its id and its chunk id; only its text changes. */
    @Test
    fun editingABlockInPlaceKeepsItsChunkId() = runTest {
        val original = original().chunks.single { it.sourceBlockId == Blocks.MODIFIED_PARAGRAPH }
        val edited = edited().chunks.single { it.sourceBlockId == Blocks.MODIFIED_PARAGRAPH }

        assertEquals(original.id, edited.id)
        assertEquals("${Blocks.MODIFIED_PARAGRAPH}:0", edited.id)
        assertNotEquals(original.text, edited.text)
    }

    /** Sections are heading-block-derived (D8), so they too must survive an edit elsewhere. */
    @Test
    fun survivingSectionsKeepTheirIds() = runTest {
        val original = original().sections.map { it.id }
        val edited = edited().sections.map { it.id }

        assertEquals("${pageId}:intro", original.first())
        assertTrue(edited.contains("${pageId}:intro"))
        assertTrue(edited.contains(Blocks.SURVIVING_HEADING))
        assertTrue(
            "the blanked heading must no longer form a section",
            !edited.contains(Blocks.BLANKED_HEADING)
        )
    }

    /**
     * The structural reason the ids above are stable, asserted rather than assumed: every
     * `sourceBlockId` is a real Notion block id from the snapshot, and every chunk id is derived from
     * one — so no identity can have come from a list position.
     */
    @Test
    fun identitiesComeFromNotionBlockIdsNotFromPositions() = runTest {
        val document = original()
        val realBlockIds = collectBlockIds(FixtureNotionApi.fromResource("/fixtures/reader-fixture.json"))

        assertTrue("the snapshot should expose block ids", realBlockIds.size >= 25)

        document.chunks.forEach { chunk ->
            assertTrue(
                "chunk ${chunk.id} has a sourceBlockId that is not a real block id",
                realBlockIds.contains(chunk.sourceBlockId)
            )
            assertTrue(
                "chunk id '${chunk.id}' must be derived from its source block",
                chunk.id.startsWith("${chunk.sourceBlockId}:")
            )
        }

        document.sections.forEach { section ->
            val headingDerived = section.id.startsWith("${pageId}:intro") ||
                realBlockIds.contains(section.id)
            assertTrue("section id '${section.id}' is not block-derived", headingDerived)
        }
    }

    // ---------------------------------------------------------------- insertion and removal

    /**
     * Two edits touch the chunk list in opposite directions — one appended block adds a chunk, one
     * blanked heading removes one — so the total is unchanged. The assertion is therefore about
     * *which* identities exist, not how many: the appended block is new, and the only thing missing is
     * the heading the edit deliberately blanked.
     */
    @Test
    fun insertionAndDeletionTouchNothingElse() = runTest {
        val original = original()
        val edited = edited()

        val editedIds = edited.chunks.map { it.id }.toSet()
        val originalIds = original.chunks.map { it.id }.toSet()

        val disappeared = original.chunks.map { it.id }.filterNot { editedIds.contains(it) }
        val appeared = edited.chunks.map { it.id }.filterNot { originalIds.contains(it) }

        assertEquals(
            "the only chunk that should have gone is the blanked heading's",
            listOf("${Blocks.BLANKED_HEADING}:0"),
            disappeared
        )
        assertEquals("exactly one new chunk, from the appended block", 1, appeared.size)
        assertEquals(
            "the appended block's chunk must be block-derived like every other",
            "${appeared.single().substringBeforeLast(':')}:0",
            appeared.single()
        )
    }

    /**
     * The removal direction, using the same pair of snapshots.
     *
     * The appended paragraph was really deleted when the script restored the page, and the script
     * verifies that all 25 original ids came back unchanged. So reading the pair backwards — edited,
     * then original — is a genuine capture of "a block was removed", not a simulation.
     */
    @Test
    fun removalDoesNotRenumberRemainingIdentities() = runTest {
        val original = original()
        val edited = edited()

        val originalIds = original.chunks.map { it.id }.toSet()
        val editedIds = edited.chunks.map { it.id }.toSet()

        val removed = edited.chunks.map { it.id }.filterNot { originalIds.contains(it) }
        assertEquals("exactly the appended chunk should have gone", 1, removed.size)

        // The shared chunks must appear in the same order on both sides. A positional scheme would
        // still produce the same *set* here and fail this comparison, which is why order is asserted
        // rather than membership alone.
        assertEquals(
            "removing a block must not reorder or shift the identities that remain",
            original.chunks.map { it.id }.filter { editedIds.contains(it) },
            edited.chunks.map { it.id }.filter { originalIds.contains(it) }
        )
    }

    // ---------------------------------------------------------------- position handoff

    /**
     * The end-to-end claim: a position saved while reading the original document still resolves to
     * the same content in the edited one, **even though its paragraph index has changed**.
     *
     * The index assertion is the point. If resolution were index-based, it would return the same
     * number and land on different content; here the number moves and the content does not.
     */
    @Test
    fun aSavedPositionResolvesToTheSameContentAcrossAnEdit() = runTest {
        val original = original()
        val edited = edited()

        val savedIndex = original.chunks.indexOfFirst {
            it.sourceBlockId == Blocks.QUOTE_AFTER_BLANKED_HEADING
        }
        assertTrue(savedIndex >= 0)

        val saved = original.positionAt(savedIndex)
        assertEquals(Blocks.QUOTE_AFTER_BLANKED_HEADING, saved.sourceBlockId)

        val resolution = edited.resolve(saved)

        assertEquals(PositionMatch.CHUNK, resolution.match)
        assertTrue(resolution.isExact)
        assertEquals(
            "the position must land on the same chunk text",
            original.chunks[savedIndex].text,
            edited.chunks[resolution.paragraphIndex!!].text
        )
        assertNotEquals(
            "the paragraph index must have moved, otherwise this test would pass for an index-based scheme",
            savedIndex,
            resolution.paragraphIndex
        )
    }

    @Test
    fun aPositionAtTheStartStillResolvesAfterAnEdit() = runTest {
        val original = original()
        val edited = edited()

        val saved = original.positionAt(0)
        val resolution = edited.resolve(saved)

        assertEquals(PositionMatch.CHUNK, resolution.match)
        assertEquals(0, resolution.paragraphIndex)
        assertTrue(resolution.blockSurvived)
        assertTrue(resolution.sectionSurvived)
    }

    /**
     * A chunk part that no longer exists because its block was re-split. This is the case the spec
     * means by "changed structures are represented accurately": the exact chunk is gone, the block is
     * not, and the resolution says so instead of guessing a nearby index.
     */
    @Test
    fun aChunkThatNoLongerExistsButWhoseBlockDoesIsReportedAsABlockMatch() = runTest {
        val document = original()

        val vanished = ReaderDocumentPosition(
            sourceBlockId = Blocks.LONG_PARAGRAPH,
            chunkId = "${Blocks.LONG_PARAGRAPH}:99",
            sectionId = Blocks.SURVIVING_HEADING
        )

        val resolution = document.resolve(vanished)

        assertEquals(PositionMatch.BLOCK, resolution.match)
        assertTrue(resolution.blockSurvived)
        assertEquals(
            "a block match anchors to the block's first part, not to a re-split middle",
            "${Blocks.LONG_PARAGRAPH}:0",
            document.chunks[resolution.paragraphIndex!!].id
        )
    }

    @Test
    fun aPositionWhoseBlockAndChunkAreGoneButWhoseSectionSurvivesFallsBackToTheSection() = runTest {
        val document = original()

        val vanished = ReaderDocumentPosition(
            sourceBlockId = "00000000-0000-0000-0000-000000000000",
            chunkId = "00000000-0000-0000-0000-000000000000:0",
            sectionId = Blocks.SURVIVING_HEADING
        )

        val resolution = document.resolve(vanished)

        assertEquals(PositionMatch.SECTION, resolution.match)
        assertTrue(resolution.sectionSurvived)
        assertTrue(!resolution.blockSurvived)
        assertEquals(
            "a section match lands on the section's first paragraph, which is its heading",
            Blocks.SURVIVING_HEADING,
            document.chunks[resolution.paragraphIndex!!].sourceBlockId
        )
    }

    /** Nothing survived, so the session layer — not this layer — must choose what happens. */
    @Test
    fun aPositionWithNothingLeftIsReportedAsLostRatherThanGuessed() = runTest {
        val document = original()

        val gone = ReaderDocumentPosition(
            sourceBlockId = "00000000-0000-0000-0000-000000000000",
            chunkId = "00000000-0000-0000-0000-000000000000:0",
            sectionId = "00000000-0000-0000-0000-000000000000"
        )

        val resolution = document.resolve(gone)

        assertEquals(PositionMatch.NONE, resolution.match)
        assertTrue(resolution.isLost)
        assertNull("a lost position must not name an index to resume from", resolution.paragraphIndex)
    }

    /**
     * A position saved against the *blanked* heading is the hardest real case: the block still exists
     * in Notion, but it now produces no chunk and no section, so nothing about the saved position can
     * be honoured and the resolution says so.
     */
    @Test
    fun aPositionInsideASectionThatWasBlankedAwayIsReportedAsLost() = runTest {
        val headingId = Blocks.BLANKED_HEADING

        val saved = ReaderDocumentPosition(
            sourceBlockId = headingId,
            chunkId = "$headingId:0",
            sectionId = headingId
        )

        val original = original()
        assertEquals("the position must be valid before the edit", PositionMatch.CHUNK, original.resolve(saved).match)

        val resolution = edited().resolve(saved)

        assertEquals(PositionMatch.NONE, resolution.match)
        assertTrue(resolution.isLost)
        assertNull(resolution.paragraphIndex)
    }

    // ---------------------------------------------------------------- determinism

    @Test
    fun mappingTheSameSnapshotTwiceProducesIdenticalIdentities() = runTest {
        val first = original()
        val second = original()

        assertEquals(first.chunks.map { it.id }, second.chunks.map { it.id })
        assertEquals(first.sections.map { it.id }, second.sections.map { it.id })
        assertEquals(first.chunkTexts, second.chunkTexts)
    }

    @Test
    fun positionAtRoundTripsThroughResolution() = runTest {
        val document = original()

        document.chunks.indices.forEach { index ->
            val resolution = document.resolve(document.positionAt(index))

            assertEquals("index $index did not round-trip", PositionMatch.CHUNK, resolution.match)
            assertEquals(index, resolution.paragraphIndex)
        }
    }

    @Test
    fun anOutOfRangeIndexProducesAnUnusablePositionRatherThanThrowing() = runTest {
        val document = original()

        val past = document.positionAt(document.chunks.size)
        val negative = document.positionAt(-1)

        assertTrue(!past.isUsable)
        assertTrue(!negative.isUsable)
        assertEquals(PositionMatch.NONE, document.resolve(past).match)
    }

    @Test
    fun aPositionCarriesNoNumericIndexOfItsOwn() = runTest {
        val document = original()
        val position = document.positionAt(5)

        // The spec is explicit that "numeric chunk indexes alone are not stable persistence
        // identities". The type has three identity fields and no index field, and this asserts the
        // shape rather than trusting the prose: everything it carries is block-derived.
        assertEquals(document.chunks[5].id, position.chunkId)
        assertEquals(document.chunks[5].sourceBlockId, position.sourceBlockId)
        assertEquals(document.sectionOfChunk(document.chunks[5].id)?.id, position.sectionId)
        assertEquals(0, position.characterOffset)
        assertTrue(position.isUsable)
    }

    @Test
    fun aSectionOnlyPositionResolvesToThatSectionsStart() = runTest {
        val document = original()

        val resolution = document.resolve(ReaderDocumentPosition.atSection(Blocks.SURVIVING_HEADING))

        assertEquals(PositionMatch.SECTION, resolution.match)
        assertEquals(Blocks.SURVIVING_HEADING, document.chunks[resolution.paragraphIndex!!].sourceBlockId)
    }

    // ---------------------------------------------------------------- helpers

    private fun quoteText(document: ReaderDocument): String =
        document.chunks.first { it.sourceBlockId == Blocks.QUOTE_AFTER_BLANKED_HEADING }.text

    /** Every block id in the snapshot, at every nesting level. */
    private fun collectBlockIds(api: FixtureNotionApi): Set<String> {
        val root = FixtureNotionApi::class.java
            .getResourceAsStream("/fixtures/reader-fixture.json")!!
            .bufferedReader().use { it.readText() }

        // Parsed rather than reflected: the snapshot's own keys are the block ids the API was
        // addressed by, which is exactly what the assertions need.
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(root)
            .let { it as kotlinx.serialization.json.JsonObject }
        val children = parsed["children"]!!.let { it as kotlinx.serialization.json.JsonObject }

        val ids = mutableSetOf<String>()
        children.forEach { (parentId, blocks) ->
            if (parentId != pageId) ids += parentId
            blocks.let { it as kotlinx.serialization.json.JsonArray }.forEach { block ->
                val element = block as kotlinx.serialization.json.JsonObject
                element["id"]?.let { ids += it.toString().trim('"') }
            }
        }
        return ids
    }
}
