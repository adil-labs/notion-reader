package com.example.notionreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The resume policy: where a restored session starts, and what it says first.
 *
 * These are the behaviors the Notion Integration Implementation Spec explicitly refused to define —
 * "must not itself decide whether playback resumes, where Continue falls back, or what the user hears when
 * a saved position no longer exists" — so they are the tests that matter most in this round. Each one
 * asserts both halves of a decision: the index, and whether anything is announced. A test that checked
 * only the index would pass for a resume that silently moved the listener.
 */
class ResumePolicyTest {

    /** Index 4 is `e`, inside the titled section "Two". */
    private val savedPosition = originalDocument().positionAt(4)

    @Test
    fun aNoteWithNoSavedPositionStartsAtTheBeginningAndSaysNothing() {
        val decision = ResumePolicy.decide(position = null, document = originalDocument())

        assertEquals(0, decision.startIndex)
        assertNull("a Note that was never played has not lost anything", decision.announcement)
        assertNull("there was no position to resolve", decision.match)
    }

    @Test
    fun anEmptyPositionIsTreatedAsNoPositionRatherThanALostOne() {
        val decision = ResumePolicy.decide(ReaderDocumentPosition(), originalDocument())

        assertEquals(0, decision.startIndex)
        assertNull(decision.announcement)
        assertNull(decision.match)
    }

    @Test
    fun anUnchangedDocumentResumesAtTheExactChunkAndSaysNothing() {
        val decision = ResumePolicy.decide(savedPosition, originalDocument())

        assertEquals("resumes inside the sentence it left off in", 4, decision.startIndex)
        assertNull("an exact resume must not interrupt the content", decision.announcement)
        assertEquals(PositionMatch.CHUNK, decision.match)
    }

    @Test
    fun aResplitBlockResumesAtTheBlocksFirstPartAndNamesTheSection() {
        // `e` survives but as `e:1`: the block was re-split, so the saved part is gone. `b` is gone too,
        // which is what makes this test able to tell a block resolution from an index that never moved: a
        // caller resuming at the saved index 4 would land on `f`, a different paragraph entirely.
        val edited = testDocument(
            testSection("s1", "One", testChunk("a"), testChunk("c")),
            testSection("s2", "Two", testChunk("d"), testChunk("e", part = 1)),
            testSection("s3", "Three", testChunk("f"))
        )

        val decision = ResumePolicy.decide(savedPosition, edited)

        assertEquals("the start of the same content, not the middle of a re-split", 3, decision.startIndex)
        assertEquals("Two", decision.announcement)
        assertEquals(PositionMatch.BLOCK, decision.match)
    }

    @Test
    fun aDeletedBlockResumesAtItsSurvivingSectionsStartAndNamesTheSection() {
        val edited = testDocument(
            testSection("s1", "One", testChunk("a"), testChunk("b"), testChunk("c")),
            testSection("s2", "Two", testChunk("d")),
            testSection("s3", "Three", testChunk("f"))
        )

        val decision = ResumePolicy.decide(savedPosition, edited)

        assertEquals(3, decision.startIndex)
        assertEquals("Two", decision.announcement)
        assertEquals(PositionMatch.SECTION, decision.match)
    }

    @Test
    fun aMovedPositionInAnUntitledSectionResumesSilently() {
        // The intro section has no title, so there is nothing to name: a section title is announced
        // "when the section has one", and the alternative is announcing nothing as if it were something.
        val edited = testDocument(
            testSection("s1", null, testChunk("a"), testChunk("b"), testChunk("c")),
            testSection("s2", null, testChunk("d")),
            testSection("s3", "Three", testChunk("f"))
        )

        val decision = ResumePolicy.decide(savedPosition, edited)

        assertEquals(3, decision.startIndex)
        assertNull(decision.announcement)
        assertEquals(PositionMatch.SECTION, decision.match)
    }

    @Test
    fun aLostPositionStartsFromTheBeginningAndSaysSoFirst() {
        // Both the block and the whole section it lived in are gone.
        val edited = testDocument(
            testSection("s1", "One", testChunk("a"), testChunk("b"), testChunk("c")),
            testSection("s3", "Three", testChunk("f"))
        )

        val decision = ResumePolicy.decide(savedPosition, edited)

        assertEquals("the Note is still readable, so it is read", 0, decision.startIndex)
        assertEquals(ResumePolicy.POSITION_LOST_ANNOUNCEMENT, decision.announcement)
        assertEquals(PositionMatch.NONE, decision.match)
    }

    @Test
    fun aPositionIsNeverAppliedToWhateverNowOccupiesTheSavedIndex() {
        // `b` is deleted, so index 1 now holds `c`. Resuming at index 1 would drop the listener into a
        // different paragraph and say nothing, which is the silent remapping the integration spec forbids.
        val edited = testDocument(
            testSection("s1", "One", testChunk("a"), testChunk("c")),
            testSection("s2", "Two", testChunk("d"), testChunk("e")),
            testSection("s3", "Three", testChunk("f"))
        )

        val decision = ResumePolicy.decide(originalDocument().positionAt(1), edited)

        assertEquals("the surviving section, not the chunk that inherited the index", 0, decision.startIndex)
        assertEquals(PositionMatch.SECTION, decision.match)
    }
}
