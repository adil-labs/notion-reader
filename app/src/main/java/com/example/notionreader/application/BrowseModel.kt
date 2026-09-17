package com.example.notionreader.application

import com.example.notionreader.domain.discovery.applyTo
import com.example.notionreader.domain.discovery.BrowseQuery
import com.example.notionreader.domain.discovery.BrowseResults
import com.example.notionreader.domain.discovery.BrowseSort
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderIndexEntry
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How the Browse index load went.
 *
 * Deliberately carries **no entries**. The first version had `Loaded(entries)` and `Error(category,
 * message)`, which meant a reload that failed had to overwrite the loaded entries with an error — losing
 * content that was already on screen and readable. `BrowseModelTest` caught it. Entries and status are
 * different facts with different lifetimes, so [BrowseState] holds them separately and neither can be
 * destroyed by the other changing.
 *
 * Three states rather than two, and the third is not a formality: the UI contract requires that "screens
 * dependent on remote content must distinguish loading from empty states" — `Loading Notes…` is not
 * `No matching Notes.` — and that a permission problem not be collapsed into a generic network error, so
 * the failure carries its [ReaderErrorCategory] and that is what tells the screen whether Retry could
 * achieve anything.
 */
sealed interface BrowseIndexStatus {

    data object Loading : BrowseIndexStatus

    /** The index was read. It may be empty, which is a real configuration rather than a failure. */
    data object Loaded : BrowseIndexStatus

    data class Error(
        val category: ReaderErrorCategory,
        val message: String
    ) : BrowseIndexStatus {
        val canRetry: Boolean get() = category.canRetry
    }
}

/**
 * What Browse is showing, and what the user asked for.
 *
 * [results] is derived rather than stored, which is what makes the contract's state restoration work
 * without any saving and loading: Browse has to keep "search text; filters; sort; result position" across
 * a trip to the Note screen and back, and since the query and the index are the state, the result set is
 * simply recomputed from them. There is nothing to restore and therefore nothing to forget to restore.
 *
 * It also means search and sort cannot disagree with the displayed results, because the results *are* the
 * query applied to the index — the arrangement `BrowseQuery → OrderedResultSet` the contract describes.
 */
data class BrowseState(
    val query: BrowseQuery = BrowseQuery.Default,
    val entries: List<ReaderIndexEntry> = emptyList(),
    val index: BrowseIndexStatus = BrowseIndexStatus.Loading
) {
    /** The ordered result set Browse is displaying. Empty before anything has loaded. */
    val results: BrowseResults
        get() = query.applyTo(entries)

    val isLoading: Boolean get() = index is BrowseIndexStatus.Loading

    val error: BrowseIndexStatus.Error? get() = index as? BrowseIndexStatus.Error

    /** True when the index has been read at least once, whether or not it turned out to be empty. */
    val hasIndex: Boolean get() = index is BrowseIndexStatus.Loaded || entries.isNotEmpty()
}

/**
 * Browse's state, and the only place its query is changed.
 *
 * Scope-free and synchronous like the rest of the app's state: loading is a `suspend` function the caller
 * drives, so the state machine itself has no coroutine of its own and is tested without a dispatcher.
 *
 * **The query and the entries survive a failed reload.** Reloading replaces the entries only on success,
 * and never touches the query, because a refresh is not a new search — and the user's "result position"
 * needs the same query afterwards to mean anything.
 */
class BrowseModel {

    private val _state = MutableStateFlow(BrowseState())

    val state: StateFlow<BrowseState> = _state.asStateFlow()

    /** Narrows the current result set. Search "narrows the same Browse result set" rather than replacing it. */
    fun setText(text: String) {
        _state.value = _state.value.copy(query = _state.value.query.copy(text = text))
    }

    fun setSort(sort: BrowseSort) {
        _state.value = _state.value.copy(query = _state.value.query.copy(sort = sort))
    }

    /**
     * Clears the search text, leaving the sort — the contract's "Clear filters" affordance, as far as the
     * default state has anything to clear.
     */
    fun clearQuery() {
        _state.value = _state.value.copy(query = BrowseQuery.Default)
    }

    /**
     * Loads the index.
     *
     * **The newest request is the only one allowed to publish.** Browse is loaded once at launch, on
     * Retry, and on any future pull-to-refresh, so two reloads can overlap — and a reload of a large
     * workspace is much slower than one of a small one, which means the last to *finish* is not reliably
     * the last to be *asked for*. Applying a superseded result would replace a newer index, or an error
     * from a request the user had already moved past would land on top of a freshly loaded one.
     *
     * Cancellation propagates rather than being reported as a failed load: leaving Browse is not a Note
     * that could not be loaded, and a cancellation folded into an error would leave "Could not load Notes"
     * on a screen the user had already left.
     *
     * `Loading` is only shown when there is nothing on screen. A reload over existing entries keeps them
     * visible, and a reload that *fails* keeps them too — the same rule as the Note screen, where a failed
     * refresh of readable content must not blank the screen.
     */
    suspend fun load(loader: suspend () -> List<ReaderIndexEntry>) {
        val token = ++requestToken

        if (!_state.value.hasIndex) {
            _state.value = _state.value.copy(index = BrowseIndexStatus.Loading)
        }

        // The outcome is carried out of the `try` so that one "am I still the newest request?" check
        // covers the success and the failure path alike. Two checks would be two places to forget one.
        val outcome: Result<List<ReaderIndexEntry>> = try {
            Result.success(loader())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A total mapping: an unmapped failure becomes MALFORMED_RESPONSE via readerErrorCategory,
            // the same fallback the loading use case uses.
            Result.failure(e)
        }

        // Requested first, answered second: a superseded reload is dropped rather than applied.
        if (token != requestToken) return

        _state.value = outcome.fold(
            onSuccess = { loaded ->
                _state.value.copy(entries = loaded, index = BrowseIndexStatus.Loaded)
            },
            onFailure = { failure ->
                // The category as well as the text, because the screen decides whether to offer Retry
                // from the category, not by reading the sentence. `entries` is deliberately left alone.
                val mapped = failure.asReaderLoadException()
                _state.value.copy(
                    index = BrowseIndexStatus.Error(
                        category = mapped.readerErrorCategory,
                        message = mapped.readerErrorMessage
                    )
                )
            }
        )
    }

    /**
     * Which load is allowed to publish. Incremented by every [load] call.
     *
     * The caller owns the coroutine and therefore owns cancellation; this class stays scope-free like the
     * rest of the app's state, and a counter is all that is needed to decide whether a result still has a
     * claimant.
     */
    private var requestToken: Int = 0
}
