package com.example.notionreader.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.example.notionreader.application.BrowseIndexStatus
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.discovery.BrowseQuery
import com.example.notionreader.domain.discovery.BrowseResults
import com.example.notionreader.ui.A11y.assertIsReadable
import com.example.notionreader.ui.A11y.assertIsReachable
import com.example.notionreader.ui.A11y.hasHeading
import com.example.notionreader.ui.A11y.hasLiveRegion
import com.example.notionreader.ui.A11y.hasNoLiveRegion
import com.example.notionreader.ui.A11y.hasPaneTitle
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Browse's semantics, which is what TalkBack consumes.
 *
 * The application half — the default query, its ordering, which rows a search matches — is
 * `BrowseQueryTest` and `BrowseModelTest`, and runs with no device. What only a device can answer is
 * whether the tree a screen-reader walks is usable, and the UI contract is unusually specific about
 * three of these:
 *
 * - "One Browse result or one Queue row should normally be one primary focus item", with the
 *   counter-example spelled out — "avoid creating separate focus stops for: Title, Type, Date, Area,
 *   Summary, menu" — and a worked announcement: "Thoughts are less reactive but still frequent.
 *   Reflection. Mind and Learning. September 17."
 * - "`Loading Notes…` is not equivalent to `No matching Notes.`" and "loading must not be communicated
 *   only through animation".
 * - "Play all and Add all to queue must not appear when there are no results", and "these controls
 *   belong to the result set as a whole. They should not be repeated on every individual result row."
 *
 * Written this way because the contract's requirements are about *announcements*, so a test that read the
 * `LazyColumn`'s items instead of the semantics tree could pass while the announced experience was wrong.
 */
class BrowseScreenAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val edited = Instant.parse("2026-09-17T12:00:00Z")

    private fun results(vararg titles: String) = BrowseResults(
        query = BrowseQuery.Default,
        entries = titles.mapIndexed { index, title ->
            com.example.notionreader.domain.ReaderIndexEntry(
                pageId = "page-$index",
                title = title,
                lastEditedAt = edited
            )
        }
    )

    private fun show(
        state: BrowseResults = results("Thoughts are less reactive", "Second note"),
        isLoading: Boolean = false,
        error: BrowseIndexStatus.Error? = null,
        queryText: String = ""
    ) {
        compose.setContent {
            BrowseScreen(
                state = state,
                isLoading = isLoading,
                error = error,
                queryText = queryText,
                onQueryChange = { },
                onOpenNote = { },
                onPlayAll = { },
                onClearQuery = { },
                onRetry = { }
            )
        }
    }

    // ------------------------------------------------------------------ where the listener has arrived

    @Test
    fun browseNamesItselfSoEnteringItSaysWhereTheListenerIs() {
        show()

        compose.onNode(hasPaneTitle("Browse")).assertExists()
    }

    @Test
    fun theResultSetIsHeadedAndTheHeadingIsAHeading() {
        show()

        // A heading rather than a bold line, so the screen can be skipped through by structure. The
        // contract's own default state names it "All Notes".
        compose.onNodeWithText("All Notes").assert(hasHeading())
    }

    @Test
    fun everyControlCanDescribeItselfRatherThanBeingAnnouncedAsABareButton() {
        show()

        A11y.assertEveryClickableIsLabelled(compose.onAllNodes(A11y.ANY))
    }

    // ------------------------------------------------------------------ one focus stop per result

    @Test
    fun aResultIsOneFocusStopCarryingItsTitleAndDate() {
        show()

        // Asserted as one matcher rather than two lookups: two lookups would each find their own node and
        // pass even if the row had been split into two focus stops, which is the failure being looked for.
        compose.onNodeWithText("Thoughts are less reactive", substring = true)
            .assert(mergedTextContaining("Thoughts are less reactive", "September 17"))
    }

    @Test
    fun readingOrderMatchesTheOrderOfTheResults() {
        // "TalkBack focus order must match reading order", and for a result set that order *is* the query's
        // ordering — which is also what `Play all` snapshots, so a mismatch here would mean the queue
        // played one order and the screen showed another.
        show(state = results("Alpha note", "Beta note", "Gamma note"))

        A11y.assertReadingOrder("Alpha note", "Beta note", "Gamma note", collection = compose.onAllNodes(A11y.ANY))
    }

    @Test
    fun playAllBelongsToTheResultSetRatherThanToEveryRow() {
        show(state = results("Alpha note", "Beta note"))

        compose.onAllNodes(androidx.compose.ui.test.hasText("Play all")).assertCountEquals(1)
    }

    // ------------------------------------------------------------------ loading, empty and error are three states

    @Test
    fun loadingIsStatedInWordsAndIsNotTheSameSentenceAsNothingMatching() {
        show(state = BrowseResults(), isLoading = true)

        // A statement, not a control: `assertIsReadable` rather than `assertIsReachable`, which would
        // demand an OnClick action a loading line does not have.
        compose.onNodeWithText("Loading Notes…").assertIsReadable()
    }

    @Test
    fun noMatchingNotesIsADifferentSentenceFromLoading() {
        show(state = BrowseResults(query = BrowseQuery(text = "zzz")), isLoading = false)

        // The two are distinguished because they mean different things: nothing matched the search versus
        // the integration cannot see any pages. Collapsing them would tell someone their search was too
        // narrow when the real problem is that no page has been shared.
        compose.onNodeWithText("No matching Notes.").assertIsReadable()
        compose.onAllNodes(androidx.compose.ui.test.hasText("Loading Notes…")).assertCountEquals(0)
    }

    @Test
    fun playAllIsAbsentWhenThereAreNoResults() {
        show(state = BrowseResults(query = BrowseQuery(text = "zzz")))

        compose.onAllNodes(androidx.compose.ui.test.hasText("Play all")).assertCountEquals(0)
    }

    // ------------------------------------------------------------------ the error notice

    @Test
    fun theErrorMessageItselfIsTheLiveRegion() {
        show(error = BrowseIndexStatus.Error(ReaderErrorCategory.NETWORK_UNAVAILABLE, "Could not load Notes."))

        // The live region must be on the message, not on the Column that wraps it and the Retry button.
        // Semantics on a parent that does not merge its descendants creates its own node, so TalkBack
        // watches an empty container while the text sits in a child that never changes — and the
        // announcement silently never fires. That is a defect this project already fixed once on the Note
        // screen, which is why this names the node rather than counting live regions.
        val regions = compose.onAllNodes(hasLiveRegion()).fetchSemanticsNodes()

        assertEquals("expected exactly the message to be a live region", 1, regions.size)

        val text = regions.first().config[SemanticsProperties.Text].joinToString(" ") { it.text }
        assertTrue("the live region should be the message; was '$text'", text.contains("Could not load Notes."))
        assertFalse(
            "a control must not be read as part of the sentence describing the failure; was '$text'",
            text.contains("Retry")
        )
    }

    @Test
    fun aRetryableFailureOffersRetryAsASeparateNamedControl() {
        show(error = BrowseIndexStatus.Error(ReaderErrorCategory.NETWORK_UNAVAILABLE, "Could not load Notes."))

        // Separate from the announcement, and named: the contract requires errors to appear "where
        // recovery is meaningful", and a control announced inside the error sentence would be read as
        // part of it.
        compose.onNodeWithText("Retry").assertIsReachable()
    }

    @Test
    fun aFailureThatRetryingCannotFixOffersNoRetry() {
        // "Permission problems must not be collapsed into generic network errors." Retrying a request that
        // failed because the token was revoked or the page was never shared cannot change the answer, so
        // offering Retry would be a lie — and the decision comes from the taxonomy's `canRetry`, not from
        // reading the sentence.
        show(
            error = BrowseIndexStatus.Error(
                ReaderErrorCategory.PERMISSION_DENIED,
                "The integration cannot see any Notion pages."
            )
        )

        compose.onAllNodes(androidx.compose.ui.test.hasText("Retry")).assertCountEquals(0)
    }

    // ------------------------------------------------------------------ what must stay quiet

    @Test
    fun theResultCountIsNotALiveRegion() {
        // Search changes the count on every keystroke, and the contract warns against "repeatedly
        // re-announcing large parts of the interface for small state changes". The only live regions on
        // this screen are the loading line and a failure.
        //
        // The query has to be on the *state*, not only in the text field: the summary is rendered from
        // `state.query.hasText`, so passing a query string without a query asserts about a node that was
        // never drawn — which is how the first version of this test passed for the wrong reason.
        show(state = results("Alpha note").copy(query = BrowseQuery(text = "Alpha")), queryText = "Alpha")

        compose.onNodeWithText("1 result for \"Alpha\"", substring = true).assert(hasNoLiveRegion())
    }

    /** Matches a node whose merged text contains every fragment. */
    private fun mergedTextContaining(vararg fragments: String) =
        androidx.compose.ui.test.SemanticsMatcher("merged text containing ${fragments.toList()}") { node ->
            val text = if (node.config.contains(SemanticsProperties.Text)) {
                node.config[SemanticsProperties.Text].joinToString(" ") { it.text }
            } else {
                ""
            }
            fragments.all { text.contains(it) }
        }
}
