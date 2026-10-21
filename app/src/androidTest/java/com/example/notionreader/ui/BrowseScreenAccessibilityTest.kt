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
    fun anEmptySearchIsStatedInWords() {
        // Searching for something that does not exist is not the same fact as having access to no pages at
        // all, so the text must not say "Share a page with the integration".
        show(state = BrowseResults(query = BrowseQuery(text = "missing")), queryText = "missing")

        compose.onNodeWithText("No matching Notes.").assertIsReadable()
    }

    @Test
    fun anEmptyIntegrationExplainsHowToFixIt() {
        // The empty state when the integration can see nothing. "Share a page" is the only thing that can
        // change the answer, so it is the only thing worth saying.
        show(state = BrowseResults())

        compose.onNodeWithText("No Notes are available. Share a page with the Notion integration to read it here.")
            .assertIsReadable()
    }

    @Test
    fun playAllIsAbsentWhenThereAreNoResults() {
        // "Play all and Add all to queue must not appear when there are no results."
        show(state = BrowseResults())

        compose.onAllNodes(androidx.compose.ui.test.hasText("Play all")).assertCountEquals(0)
    }

    @Test
    fun anErrorIsPoliteSoItDoesNotInterruptTheUser() {
        show(
            error = BrowseIndexStatus.Error(
                ReaderErrorCategory.NETWORK_UNAVAILABLE,
                "Could not reach Notion."
            )
        )

        compose.onNodeWithText("Could not reach Notion.").assert(hasLiveRegion())
    }

    @Test
    fun aRetriableFailureOffersRetry() {
        show(
            error = BrowseIndexStatus.Error(
                ReaderErrorCategory.NETWORK_UNAVAILABLE,
                "Could not reach Notion."
            )
        )

        // Reachable: Retry is a control, so a screen reader user must be able to activate it.
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

    // ------------------------------------------------------------------ facet filtering

    @Test
    fun facetFilterChipsAreExposedAndLabelled() {
        val entryWithMetadata = com.example.notionreader.domain.ReaderIndexEntry(
            pageId = "1",
            title = "Chess Tactics",
            kind = "Practice",
            area = "Chess",
            topics = setOf("Tactics")
        )
        val state = BrowseResults(
            query = BrowseQuery.Default,
            entries = listOf(entryWithMetadata)
        )
        compose.setContent {
            BrowseScreen(
                state = state,
                allEntries = listOf(entryWithMetadata),
                isLoading = false,
                error = null,
                queryText = "",
                onQueryChange = {},
                onOpenNote = {},
                onPlayAll = {},
                onClearQuery = {},
                onRetry = {}
            )
        }

        compose.onNodeWithText("Type").assertIsReachable()
        compose.onNodeWithText("Area").assertIsReachable()
        compose.onNodeWithText("Topic").assertIsReachable()
    }

    @Test
    fun activeFilterSummaryExposesActiveCountAndClearButton() {
        val activeQuery = BrowseQuery(
            selectedType = "Practice",
            selectedArea = "Chess"
        )
        val entry = com.example.notionreader.domain.ReaderIndexEntry(
            pageId = "1",
            title = "Chess Tactics",
            kind = "Practice",
            area = "Chess"
        )
        var cleared = false
        compose.setContent {
            BrowseScreen(
                state = BrowseResults(query = activeQuery, entries = listOf(entry)),
                allEntries = listOf(entry),
                isLoading = false,
                error = null,
                queryText = "",
                query = activeQuery,
                onQueryChange = {},
                onOpenNote = {},
                onPlayAll = {},
                onClearQuery = {},
                onRetry = {},
                onClearFilters = { cleared = true }
            )
        }

        compose.onNodeWithText("2 filters: Practice, Chess", substring = true).assertIsReadable()
        compose.onNodeWithText("Clear filters").assertIsReachable()
        compose.onNodeWithText("Clear filters").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(cleared)
    }

    @Test
    fun aResultWithMetadataMergesTypeAndAreaIntoItsFocusStop() {
        val entry = com.example.notionreader.domain.ReaderIndexEntry(
            pageId = "1",
            title = "Thoughts are less reactive",
            kind = "Reflection",
            area = "Mind & Learning",
            lastEditedAt = edited
        )
        compose.setContent {
            BrowseScreen(
                state = BrowseResults(entries = listOf(entry)),
                isLoading = false,
                error = null,
                queryText = "",
                onQueryChange = {},
                onOpenNote = {},
                onPlayAll = {},
                onClearQuery = {},
                onRetry = {}
            )
        }

        // The contract's worked announcement: "Thoughts are less reactive but still frequent. Reflection. Mind and Learning. September 17."
        compose.onNodeWithText("Thoughts are less reactive", substring = true)
            .assert(mergedTextContaining("Thoughts are less reactive", "Reflection", "Mind & Learning", "September 17"))
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

    @Test
    fun browseResultRowDeclaresButtonRole() {
        show(state = results("Thoughts are less reactive"))
        compose.onNodeWithText("Thoughts are less reactive", substring = true)
            .assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
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
