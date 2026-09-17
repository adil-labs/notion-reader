package com.example.notionreader.data.content

import com.example.notionreader.data.notion.FixtureNotionApi
import com.example.notionreader.data.notion.NotionRepository
import com.example.notionreader.domain.ReaderChunkKind
import com.example.notionreader.domain.ReaderDocument
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the mapper against the Phase 0 fixture page.
 *
 * The fixture is the acceptance oracle described in `docs/phase0-fixture-contract.md`: a real Notion
 * page containing every supported block type plus the edge cases the spec's test strategy calls out.
 * The snapshot in `src/test/resources/fixtures/` is a capture of that page, so these tests need no
 * token and no network.
 *
 * Assertions are deliberately split into two kinds:
 * - **Exact** where the contract is deterministic: section structure, ids, kinds, metadata.
 * - **Rule-based** where it is not: block-to-chunk counts depend on the split threshold, which
 *   docs/decisions.md D11 deliberately leaves unfrozen, so long blocks are asserted on the
 *   threshold-independent properties instead.
 */
class ReaderFixtureContractTest {

    private val pageId = "3dec9345-e10a-8180-867d-fa96b49bd30b"

    /** Block ids from the fixture, named so the assertions read as prose. */
    private object Blocks {
        const val INTRO_PARA = "3dec9345-e10a-81ce-a1a0-f5eaf14193b1"
        const val FRAGMENTS = "3dec9345-e10a-81c5-9d27-d7ae2a10c453"
        const val LINK_PARA = "3dec9345-e10a-8189-becc-c72f3acff82a"
        const val INLINE_CODE_PARA = "3dec9345-e10a-81f2-b62f-f88c9184f316"
        const val HEADING_1 = "3dec9345-e10a-81b2-8de5-d4a070b01d1e"
        const val BODY_1 = "3dec9345-e10a-81bb-ac31-c8546d8725af"
        const val HEADING_2 = "3dec9345-e10a-81ad-afda-e576ba917ae4"
        const val BULLET_1 = "3dec9345-e10a-81aa-864c-d1bdb6e5b361"
        const val BULLET_2 = "3dec9345-e10a-817d-a39c-ffe3baa1cd5e"
        const val NESTED_BULLET = "3dec9345-e10a-8123-b215-ea1c79c92568"
        const val NUMBERED_1 = "3dec9345-e10a-8102-922b-eb7d15365341"
        const val NESTED_UNDER_NUMBERED = "3dec9345-e10a-814e-8814-ca751f274aa3"
        const val NUMBERED_2 = "3dec9345-e10a-8105-95a5-e623b6b577c0"
        const val TODO_UNCHECKED = "3dec9345-e10a-810e-a8d7-fcbfb3dc92bd"
        const val TODO_CHECKED = "3dec9345-e10a-8108-a329-d796f372ae08"
        const val QUOTE = "3dec9345-e10a-814a-b203-c60426c9030f"
        const val CODE = "3dec9345-e10a-81bc-a791-c6788c5038ed"
        const val DIVIDER = "3dec9345-e10a-814a-841f-debd8be3d6ed"
        const val HEADING_3 = "3dec9345-e10a-818d-9595-c7dac1c3c555"
        const val BODY_3 = "3dec9345-e10a-814e-bd10-de300f0710f6"
        const val EMPTY_HEADING = "3dec9345-e10a-81db-862b-fbbd7a2670e1"
        const val AFTER_EMPTY = "3dec9345-e10a-816f-999e-f7b91a594c5a"
        const val IMAGE = "3dec9345-e10a-81b4-880e-dfe9bf40f95e"
        const val LONG_PARAGRAPH = "3dec9345-e10a-815d-a804-ce592e7b7181"
        const val TOGGLE = "3dec9345-e10a-8123-a2bb-f87323ee38e8"
        const val INSIDE_TOGGLE = "3dec9345-e10a-812a-860d-e1f49d4f72c0"
        const val ONE_LONG_SENTENCE = "3dec9345-e10a-817c-999b-d31318d4cbee"
        const val NO_FULL_STOP = "3dec9345-e10a-8136-9c8d-edb3b8a6ee8a"
    }

    /** Blocks whose text is longer than any plausible threshold, so their chunk count is not fixed. */
    private val oversizedBlocks = listOf(
        Blocks.LONG_PARAGRAPH,
        Blocks.ONE_LONG_SENTENCE,
        Blocks.NO_FULL_STOP
    )

    private suspend fun loadFixture(): ReaderDocument =
        NotionRepository(FixtureNotionApi.fromResource("/fixtures/reader-fixture.json"))
            .getDocument(pageId)

    // ---------------------------------------------------------------- page identity

    @Test
    fun mapsPageIdentityAndTitle() = runTest {
        val document = loadFixture()

        assertEquals(pageId, document.pageId)
        assertEquals("Reader Fixture — Block Type Coverage", document.title)
        assertNotNull("lastEditedAt should be parsed from the page", document.lastEditedAt)
    }

    // ---------------------------------------------------------------- section structure

    @Test
    fun buildsFourSectionsWithHeadingLevelsAndTitles() = runTest {
        val document = loadFixture()

        assertEquals(4, document.sections.size)
        assertEquals(
            listOf(
                "$pageId:intro" to null,
                Blocks.HEADING_1 to "Primary Heading",
                Blocks.HEADING_2 to "Secondary Heading",
                Blocks.HEADING_3 to "Tertiary Heading"
            ),
            document.sections.map { it.id to it.title }
        )
        assertEquals(listOf(0, 1, 2, 3), document.sections.map { it.level })
    }

    @Test
    fun introSectionHoldsTheContentBeforeTheFirstHeading() = runTest {
        val document = loadFixture()

        assertEquals(
            listOf(
                "Intro paragraph before any heading.",
                "Fragment one, fragment two, and fragment three.",
                "See the Notion docs for details.",
                "Inline code_span inside a paragraph."
            ),
            document.sections[0].chunks.map { it.text }
        )
    }

    @Test
    fun richTextFragmentsJoinInOrderWithoutInsertingSeparators() = runTest {
        val document = loadFixture()

        val fragments = document.chunks.single { it.sourceBlockId == Blocks.FRAGMENTS }

        // The first fragment ends with a space that its author typed. Re-adding a separator, or
        // trimming the fragment first, would corrupt this.
        assertEquals("Fragment one, fragment two, and fragment three.", fragments.text)
    }

    @Test
    fun headingSectionsContainTheirOwnHeadingChunkFirstThenTheirBody() = runTest {
        val document = loadFixture()

        val primary = document.sections[1]
        assertEquals(listOf(ReaderChunkKind.HEADING, ReaderChunkKind.PARAGRAPH), primary.chunks.map { it.kind })
        assertEquals(listOf("Primary Heading", "Body under the primary heading."), primary.chunks.map { it.text })

        val tertiary = document.sections[3]
        assertEquals(ReaderChunkKind.HEADING, tertiary.chunks.first().kind)
        assertEquals("Tertiary Heading", tertiary.chunks.first().text)
    }

    // ---------------------------------------------------------------- kind mapping

    @Test
    fun mapsEverySupportedBlockTypeToItsChunkKind() = runTest {
        val document = loadFixture()
        val kinds = document.chunks.associate { it.sourceBlockId to it.kind }

        assertEquals(ReaderChunkKind.LIST_ITEM, kinds[Blocks.BULLET_1])
        assertEquals(ReaderChunkKind.LIST_ITEM, kinds[Blocks.NUMBERED_2])
        assertEquals(ReaderChunkKind.TODO, kinds[Blocks.TODO_UNCHECKED])
        assertEquals(ReaderChunkKind.QUOTE, kinds[Blocks.QUOTE])
        assertEquals(ReaderChunkKind.CODE, kinds[Blocks.CODE])
        assertEquals(ReaderChunkKind.PARAGRAPH, kinds[Blocks.TOGGLE])
    }

    @Test
    fun nestedChildrenAppearImmediatelyAfterTheirParent() = runTest {
        val document = loadFixture()
        val texts = document.sections[2].chunks.map { it.text }

        // The nested bullet is read where it sits, not appended at the end of the section.
        assertEquals(
            listOf(
                "Secondary Heading",
                "First bullet",
                "Second bullet",
                "Nested bullet under the second bullet.",
                "First numbered item",
                "Nested bullet under the first numbered item.",
                "Second numbered item",
                "Unchecked task",
                "Checked task",
                "Quoted text for the fixture.",
                "val answer = 42"
            ),
            texts
        )
    }

    @Test
    fun toggleChildrenAreReadInPlace() = runTest {
        val document = loadFixture()
        val section = document.sections[3]

        val toggleIndex = section.chunks.indexOfFirst { it.sourceBlockId == Blocks.TOGGLE }
        assertTrue("the toggle should produce a chunk", toggleIndex >= 0)
        assertEquals("Toggle summary", section.chunks[toggleIndex].text)
        assertEquals(Blocks.INSIDE_TOGGLE, section.chunks[toggleIndex + 1].sourceBlockId)
        assertEquals("Inside the toggle.", section.chunks[toggleIndex + 1].text)
    }

    // ---------------------------------------------------------------- D4, D5, D6

    @Test
    fun emptyHeadingProducesNoSectionAndLosesNoContent() = runTest {
        val document = loadFixture()

        // D4: an empty heading is not a section boundary, so the paragraph after it stays in the
        // tertiary section rather than opening a titleless section of its own.
        assertTrue(
            "no section should be identified by the empty heading",
            document.sections.none { it.id == Blocks.EMPTY_HEADING }
        )
        val tertiary = document.sections[3]
        assertTrue(
            "the paragraph after the empty heading stays in the enclosing section",
            tertiary.chunks.any { it.sourceBlockId == Blocks.AFTER_EMPTY }
        )
        assertTrue(
            "the empty heading itself produces no chunk",
            document.chunks.none { it.sourceBlockId == Blocks.EMPTY_HEADING }
        )
    }

    @Test
    fun dividerProducesNoChunkAndNoSection() = runTest {
        val document = loadFixture()

        // D5: preserved in the normalized tree only.
        assertTrue(
            "the divider must not produce a chunk",
            document.chunks.none { it.sourceBlockId == Blocks.DIVIDER }
        )
    }

    @Test
    fun unsupportedBlockProducesNoChunkButDoesNotBreakTheDocument() = runTest {
        val document = loadFixture()

        // D6: no chunk at all, not even an empty one, because an empty chunk would be a useless
        // navigation target. The rest of the page still reads.
        assertTrue(
            "the image block must not produce a chunk",
            document.chunks.none { it.sourceBlockId == Blocks.IMAGE }
        )
        assertTrue(
            "no chunk may have empty text, since empty chunks are useless navigation targets",
            document.chunks.none { it.text.isEmpty() }
        )
        assertTrue("content around the unsupported block survives", document.chunks.isNotEmpty())
    }

    // ---------------------------------------------------------------- chunk identity

    @Test
    fun chunkIdsDeriveFromBlockIdsWithAPartIndex() = runTest {
        val document = loadFixture()

        for (chunk in document.chunks) {
            assertTrue(
                "chunk id '${chunk.id}' should be '<sourceBlockId>:<part>'",
                chunk.id.startsWith("${chunk.sourceBlockId}:")
            )
            val part = chunk.id.substringAfterLast(':').toIntOrNull()
            assertNotNull("chunk id '${chunk.id}' should end in a numeric part index", part)
        }
    }

    @Test
    fun unsplitBlocksUsePartIndexZero() = runTest {
        val document = loadFixture()

        // D7: the part index is always present so a saved position keeps resolving if the block
        // later grows into several parts.
        val intro = document.chunks.single { it.sourceBlockId == Blocks.INTRO_PARA }
        assertEquals("${Blocks.INTRO_PARA}:0", intro.id)
    }

    @Test
    fun partIndexesAreContiguousFromZeroForSplitBlocks() = runTest {
        val document = loadFixture()

        for (blockId in oversizedBlocks) {
            val parts = document.chunks.filter { it.sourceBlockId == blockId }
            assertEquals(
                "block $blockId should be split into contiguous parts from :0",
                parts.indices.map { "$blockId:$it" },
                parts.map { it.id }
            )
        }
    }

    // ---------------------------------------------------------------- metadata (D10, D12)

    @Test
    fun toDoCheckedStateIsMetadataAndNotSpokenText() = runTest {
        val document = loadFixture()

        val unchecked = document.chunks.single { it.sourceBlockId == Blocks.TODO_UNCHECKED }
        val checked = document.chunks.single { it.sourceBlockId == Blocks.TODO_CHECKED }

        assertEquals(false, unchecked.metadata.checked)
        assertEquals(true, checked.metadata.checked)
        assertEquals("Unchecked task", unchecked.text)
        assertEquals("Checked task", checked.text)
    }

    @Test
    fun codeLanguageIsMetadataAndTheCodeStaysVerbatimInOneChunk() = runTest {
        val document = loadFixture()

        val code = document.chunks.filter { it.sourceBlockId == Blocks.CODE }

        assertEquals(1, code.size)
        assertEquals("val answer = 42", code.single().text)
        assertEquals("kotlin", code.single().metadata.language)
    }

    @Test
    fun linkHrefIsMetadataWhileTheDisplayTextStaysInTheChunk() = runTest {
        val document = loadFixture()

        val link = document.chunks.single { it.sourceBlockId == Blocks.LINK_PARA }

        // D12: the href must not be spoken, so it is not concatenated into the text.
        assertEquals("See the Notion docs for details.", link.text)
        assertEquals(
            listOf("https://developers.notion.com/reference/intro"),
            link.metadata.linkTargets
        )
    }

    @Test
    fun inlineCodeIsFlaggedWithoutAlteringTheText() = runTest {
        val document = loadFixture()

        val inline = document.chunks.single { it.sourceBlockId == Blocks.INLINE_CODE_PARA }

        assertEquals("Inline code_span inside a paragraph.", inline.text)
        assertTrue(inline.metadata.hasInlineCode)
    }

    @Test
    fun blocksWithoutMetadataCarryNone() = runTest {
        val document = loadFixture()

        val plain = document.chunks.single { it.sourceBlockId == Blocks.INTRO_PARA }

        assertNull(plain.metadata.checked)
        assertNull(plain.metadata.language)
        assertTrue(plain.metadata.linkTargets.isEmpty())
        assertTrue(!plain.metadata.hasInlineCode)
    }

    // ---------------------------------------------------------------- chunking rules (D11)

    @Test
    fun oversizedBlocksSplitIntoMultipleParts() = runTest {
        val document = loadFixture()

        for (blockId in oversizedBlocks) {
            val parts = document.chunks.count { it.sourceBlockId == blockId }
            assertTrue("block $blockId should split into at least 2 parts, got $parts", parts >= 2)
        }
    }

    @Test
    fun splittingLosesNoText() = runTest {
        val document = loadFixture()

        // Concatenating the parts must reproduce the source text. Whitespace at a part boundary is
        // trimmed, so compare on normalised whitespace rather than raw equality.
        assertTrue(
            "the long paragraph should survive splitting intact",
            normaliseTexts(document.chunks.filter { it.sourceBlockId == Blocks.LONG_PARAGRAPH }.map { it.text }) ==
                normaliseTexts(listOf(LONG_PARAGRAPH_TEXT))
        )
        assertTrue(
            "the single-sentence block should survive splitting intact",
            normaliseTexts(document.chunks.filter { it.sourceBlockId == Blocks.ONE_LONG_SENTENCE }.map { it.text }) ==
                normaliseTexts(listOf(ONE_LONG_SENTENCE_TEXT))
        )
    }

    @Test
    fun aBlockWithNoSentenceBoundaryStillSplitsAtWordBoundaries() = runTest {
        val document = loadFixture()

        val parts = document.chunks.filter { it.sourceBlockId == Blocks.NO_FULL_STOP }

        assertTrue("expected the no-punctuation block to split", parts.size >= 2)
        // The sentence rule has nothing to match here, so the word-boundary rule must have run.
        // A break must therefore fall between words, never inside one.
        for (part in parts) {
            assertTrue(
                "part '${part.text.take(40)}...' should not start mid-word",
                part.text.isNotEmpty() && !part.text.first().isWhitespace()
            )
        }
    }

    // ---------------------------------------------------------------- aggregate

    @Test
    fun documentIsInspectableEndToEnd() = runTest {
        val document = loadFixture()

        // 22 chunks are threshold-independent; the three oversized blocks add at least 2 each.
        assertTrue(
            "expected at least 28 chunks, got ${document.chunks.size}",
            document.chunks.size >= 28
        )
        assertEquals(
            "chunkTexts must mirror chunks",
            document.chunks.map { it.text },
            document.chunkTexts
        )
    }

    private fun normaliseTexts(texts: List<String>): String =
        texts.joinToString(" ").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    private companion object {
        const val LONG_PARAGRAPH_TEXT =
            "Long paragraph sentence one, deliberately written to be short. " +
                "Long paragraph sentence two continues the thought without stopping. " +
                "Long paragraph sentence three adds still more words to the block. " +
                "Long paragraph sentence four carries the total well past three hundred characters. " +
                "Long paragraph sentence five pushes it further still. " +
                "Long paragraph sentence six ends the block."

        const val ONE_LONG_SENTENCE_TEXT =
            "This single sentence deliberately runs well past any plausible chunk threshold so that a " +
                "splitter which only understands sentence boundaries has no choice but to fall through " +
                "to its word boundary rule and break the text somewhere in the middle of a clause " +
                "rather than at a punctuation mark, which is precisely the fallback path that must be " +
                "exercised before the threshold constant can be chosen with any confidence at all."
    }
}

