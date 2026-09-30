package com.example.notionreader.application

import com.example.notionreader.application.DocumentLoadState
import com.example.notionreader.application.LoadReaderDocument
import com.example.notionreader.domain.ReaderDocument
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Note screen's content: the document being inspected, and how its load went.
 *
 * **This class holds no reference to a [com.example.notionreader.application.ReaderSession]**, and that
 * absence is the feature. The product contract makes manual reading and spoken playback separate
 * modes: "Moving accessibility focus through the Note must not alter Player progress", and, more
 * sharply, "If playback is occurring while another Note is manually inspected, the existing playback
 * session remains unchanged".
 *
 * Both were false *structurally* before this type existed, not merely unhandled: the engine held one
 * document, `setDocument` stopped speech and reset the position, and the same document fed the Note
 * screen and the Player — so loading a Note to read it was indistinguishable from ending the session.
 * The consequence was reachable in the shipped build. `MainActivity` starts a load after `setContent`,
 * so the UI is live while a load is in flight: tap Play on the demo document, let the Notion load
 * finish, and `setDocument` killed the playback with no message. Retry made it worse, because Retry is
 * `loadNotionDocument` and is pressed most often when a load is slow — the widest window.
 *
 * Separating the state makes that impossible rather than fixed: `load` writes only to the two flows
 * below, so there is no call it *could* make that interrupts playback. `ReaderAppTest` pins it as a
 * regression test, asserted against a recording engine rather than by inspecting this class.
 */
class NoteViewer(initial: ReaderDocument = ReaderDocument.Empty) {

    private val _document = MutableStateFlow(initial)

    /** What the Note screen renders. Kept separate from the load state so a failed load can leave a readable document on screen. */
    val document: StateFlow<ReaderDocument> = _document.asStateFlow()

    private val _loadState = MutableStateFlow<DocumentLoadState>(DocumentLoadState.Loaded(initial))

    /** Loading, loaded or failed. The contract requires these three to be distinguishable, never conveyed by animation alone. */
    val loadState: StateFlow<DocumentLoadState> = _loadState.asStateFlow()

    private val _pageId = MutableStateFlow<String?>(null)

    /**
     * The page the viewer last asked for — not necessarily the page it is showing.
     *
     * It exists so Retry re-loads the same page. Those were the same thing while the only Note was the
     * configured one; a Browse result can open any page, so a Retry that reverted to the configured id
     * would look like the app ignoring the tap.
     */
    val pageId: StateFlow<String?> = _pageId.asStateFlow()

    /**
     * Loads the Note identified by [pageId], replacing the inspected document on success.
     *
     * ## The newest request is the only one allowed to publish
     *
     * This is also the Retry action, and a Browse result can be activated twice — tap a row, come back,
     * tap another — so two loads can genuinely overlap. The first version of this method let the **last
     * to finish** win, which is a different thing from the last to be *asked for*, and the two diverge
     * exactly when one page is slower than the next:
     *
     * ```
     * load(A) starts, slow — several requests for a large page
     * load(B) starts, fast
     * load(B) completes, B is shown
     * load(A) completes -> overwrote B, so the screen now shows the Note the user navigated away from
     * ```
     *
     * The damage is worse than a stale screen, because [pageId] and [document] are written at different
     * moments: the late result left them describing **different pages**, so Retry re-loaded B while A was
     * on screen. Two fields that can disagree about which Note is being inspected is the ambiguity this
     * class exists to avoid.
     *
     * [requestToken] closes it by asking the only question that has a stable answer — "am I still the
     * newest request?" — instead of relying on which coroutine happened to finish last. A superseded load
     * publishes nothing at all: not its document, not its load state, and not an error.
     *
     * Cancellation is not folded into failure here; [LoadReaderDocument] throws it, and it propagates so
     * that superseding a load cannot leave "could not load" on screen.
     *
     * On failure the *previous* document stays loaded and readable. That is deliberate: a failed refresh
     * of content the user can already read should not blank the screen, and it is what the screen's
     * three-state notice is for.
     */
    suspend fun load(
        pageId: String,
        loader: LoadReaderDocument,
        knownRemoteLastEditedAt: java.time.Instant? = null,
        forceRefresh: Boolean = false
    ) {
        val token = ++requestToken
        _pageId.value = pageId
        _loadState.value = DocumentLoadState.Loading

        val result = loader(pageId, knownRemoteLastEditedAt, forceRefresh, com.example.notionreader.domain.document.DocumentLoadPurpose.INTERACTIVE)

        // Requested first, answered second: a superseded load is dropped rather than applied.
        if (token != requestToken) return

        result.fold(
            onSuccess = { loaded ->
                _document.value = loaded
                _loadState.value = DocumentLoadState.Loaded(loaded)
            },
            onFailure = { failure ->
                // Category as well as text, because the UI contract requires that a permission problem
                // not be collapsed into a generic network error and that Retry is only offered when it
                // could achieve something.
                _loadState.value = DocumentLoadState.Error(
                    category = failure.readerErrorCategory,
                    message = failure.readerErrorMessage
                )
            }
        )
    }

    /**
     * Which load is allowed to publish. Incremented by every [load] call.
     *
     * Deliberately not a `Job`: the caller owns the coroutine (and therefore owns cancellation), and this
     * class must not acquire a scope of its own — the same reason [ReaderSession] has none. A counter is
     * all that is needed to decide whether a result still has a claimant, and it cannot be forgotten to be
     * reset because nothing else compares against it.
     */
    private var requestToken: Int = 0
}
