package com.example.notionreader.app

import com.example.notionreader.browse.BrowseSort
import com.example.notionreader.reader.ReaderErrorCategory
import com.example.notionreader.reader.ReaderIndexEntry
import com.example.notionreader.reader.ReaderLoadException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException
import java.time.Instant

/**
 * Browse's state: the three load states, and the query surviving them.
 *
 * The point of these is the contract's state-restoration rule read the other way round. Browse must keep
 * "search text; filters; sort" across a trip to the Note screen and back — and because the result set is
 * *derived* from the query and the index rather than stored, that is satisfied by construction. These
 * tests pin that: the query is never touched by a load, and the results are always the query applied to
 * whatever the index currently holds.
 */
class BrowseModelTest {

    private fun entry(id: String, title: String) = ReaderIndexEntry(
        pageId = id,
        title = title,
        lastEditedAt = Instant.parse("2026-09-01T10:00:00Z")
    )

    private val entries = listOf(entry("a", "Alpha notes"), entry("b", "Beta practice"))

    @Test
    fun browseStartsLoadingWithTheDefaultQuery() = runTest {
        val model = BrowseModel()

        assertTrue(model.state.value.isLoading)
        assertEquals("", model.state.value.query.text)
        assertEquals(BrowseSort.RECENTLY_UPDATED, model.state.value.query.sort)
        // Derived, so a loading index has no results rather than stale ones.
        assertTrue(model.state.value.results.isEmpty)
    }

    @Test
    fun aSuccessfulLoadProducesTheDefaultResultSet() = runTest {
        val model = BrowseModel()

        model.load { entries }

        assertFalse(model.state.value.isLoading)
        assertEquals(listOf("a", "b"), model.state.value.results.entries.map { it.pageId })
    }

    @Test
    fun searchNarrowsTheDisplayedResults() = runTest {
        val model = BrowseModel()
        model.load { entries }

        model.setText("practice")

        assertEquals(listOf("b"), model.state.value.results.entries.map { it.pageId })
        // The index is untouched: searching re-orders and filters the set already loaded rather than
        // re-fetching, which is what "narrows the same Browse result set" means.
        assertEquals(2, model.state.value.entries.size)
    }

    @Test
    fun theQuerySurvivesAReload() = runTest {
        val model = BrowseModel()
        model.load { entries }
        model.setText("practice")

        model.load { entries }

        // A refresh is not a new search. This is also what makes "result position" mean anything after a
        // reload, since it is a position in the result set the query defines.
        assertEquals("practice", model.state.value.query.text)
        assertEquals(listOf("b"), model.state.value.results.entries.map { it.pageId })
    }

    @Test
    fun clearingTheSearchRestoresTheWholeSet() = runTest {
        val model = BrowseModel()
        model.load { entries }
        model.setText("practice")

        model.clearQuery()

        assertEquals("", model.state.value.query.text)
        assertEquals(2, model.state.value.results.size)
    }

    @Test
    fun aFailedLoadReportsItsCategoryAndWhetherRetryIsWorthOffering() = runTest {
        val model = BrowseModel()

        model.load { throw ReaderLoadException(ReaderErrorCategory.AUTHORIZATION_REQUIRED) }

        val error = model.state.value.error
        assertEquals(ReaderErrorCategory.AUTHORIZATION_REQUIRED, error?.category)
        // A revoked token cannot start working, so the screen must not offer Retry. This is the contract's
        // "permission problems must not be collapsed into generic network errors".
        assertFalse(error!!.canRetry)
        assertTrue(model.state.value.results.isEmpty)
    }

    @Test
    fun anUnmappedFailureStillBecomesAReportableError() = runTest {
        val model = BrowseModel()

        model.load { throw IllegalStateException("something unexpected") }

        // A total mapping, as with the document loader: an unexpected exception must not be able to crash
        // the caller, and it lands on the taxonomy's fallback category.
        assertEquals(ReaderErrorCategory.MALFORMED_RESPONSE, model.state.value.error?.category)
    }

    @Test
    fun aLoadCannotBlankAnAlreadyLoadedIndex() = runTest {
        val model = BrowseModel()
        model.load { entries }

        model.load { throw ReaderLoadException(ReaderErrorCategory.NETWORK_UNAVAILABLE) }

        // A failed refresh of readable content must not empty the screen — the same rule the Note screen
        // follows. This is why entries and load status are separate fields: with entries inside the status,
        // moving to Error destroyed them, which is what a first version of this model did.
        assertEquals(2, model.state.value.results.size)
        assertEquals(ReaderErrorCategory.NETWORK_UNAVAILABLE, model.state.value.error?.category)
    }

    @Test
    fun cancellingALoadIsNotReportedAsAFailure() = runTest {
        val model = BrowseModel()

        val thrown = runCatching {
            model.load { throw kotlinx.coroutines.CancellationException("left the screen") }
        }.exceptionOrNull()

        // Cancellation is not a Note that could not be loaded, and folding it into an error would leave
        // "Could not load Notes" on a screen the user had already left.
        assertTrue(thrown is CancellationException)
        assertNull(model.state.value.error)
    }

    @Test
    fun anIndexWithNoPagesIsLoadedAndEmptyRatherThanStillLoading() = runTest {
        val model = BrowseModel()

        model.load { emptyList() }

        // Distinct from Loading: an integration with access to nothing is a real configuration, and it is
        // the case where "no Notes" is the truthful answer.
        assertFalse(model.state.value.isLoading)
        assertTrue(model.state.value.results.isEmpty)
        assertNull(model.state.value.error)
    }
}
