package com.example.notionreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.DocumentLoadState
import com.example.notionreader.application.ReaderFocus
import com.example.notionreader.domain.ReaderChunk
import com.example.notionreader.domain.ReaderChunkKind
import com.example.notionreader.domain.ReaderDocument

/**
 * The Note: the manual reading and inspection surface.
 *
 * It is *not* a playback screen. Opening it never starts Reader TTS, and moving TalkBack focus
 * through it never touches playback progress — both are explicit requirements of the UI contract, and
 * both are satisfied structurally: this screen is display-only, takes no [ReaderDocument]-mutating
 * callback, and is rendered from a document snapshot the engine already owns.
 *
 * Primary action is Play, which the contract defines as "creates a new Active Queue containing only
 * this Note; starts from the beginning; opens Player". Its companion `Add to queue` is now here too: it
 * was omitted while there was no queue to add to, and the Queue Creation & Queue Management specification
 * is what supplied one. The contract's rule for it is narrow and this screen obeys it structurally — the
 * control opens Queue Destination and does nothing else, so it "does not: start playback; stop playback;
 * modify an Active Queue", and it cannot, because it is given no way to reach the session.
 *
 * Rendering follows the contract's "content may include" list: title, headings, paragraphs, lists,
 * links, code, supported structured content. Two deliberate omissions:
 * - **Link targets are not rendered.** The display text of a link is in the chunk text (D12), but the
 *   href is metadata, and the contract names URLs among the things that "should not be spoken during
 *   ordinary reading". Making links activatable needs a navigation surface this milestone has none of.
 * - **Ordered lists are shown as unordered.** The mapper keeps one `LIST_ITEM` kind for bulleted and
 *   numbered items, so numbering cannot be reproduced here. That is a rendering-fidelity gap, and the
 *   contract defers detailed rendering rules to the Note Reading Screen specification, which does not
 *   exist yet; inventing numbering before it does would mean guessing at the model. It is recorded as
 *   a known limitation rather than papered over.
 *
 * Accessibility:
 * - The list is ordered exactly as the document reads, so TalkBack's traversal order is reading order.
 * - Headings carry heading semantics at their real level, which is why the mapper keeps
 *   [com.example.notionreader.domain.ChunkMetadata.headingLevel].
 * - Bullets and check glyphs are decorative: they are cleared from the semantics tree so TalkBack
 *   reads the item's text, not "bullet, item text". A to-do's state is exposed as a state description
 *   instead, so it is available on demand without interrupting.
 * - Loading, empty and error are three distinct, textual states — loading is never conveyed by
 *   animation alone — and the error message is a polite live region because errors require concise
 *   spoken feedback.
 * - Nothing is force-focused, so TalkBack and the document TTS engine do not talk over each other.
 */
@Composable
fun NoteScreen(
    document: ReaderDocument,
    loadState: DocumentLoadState,
    onPlay: () -> Unit,
    /**
     * `Add to queue`: hand this Note to Queue Destination.
     *
     * A default so that every existing construction of this screen keeps compiling, and so a test that is
     * about something else does not have to state it.
     */
    onAddToQueue: () -> Unit = {},
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    statusMessage: String? = null,
    /**
     * Set when Back brought the listener here from the Player, so Play — the control they pressed — takes
     * focus again rather than the screen starting at its top.
     */
    focusTarget: String? = null,
    onFocusHandled: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { paneTitle = "Note" }
    ) {
        // A lazy list rather than a scrolling column: a real integration page produced 343 chunks, and
        // composing all of them at once to show the first screenful would be wasteful on a phone.
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "note-header") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = document.title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() }
                    )

                    if (statusMessage != null) {
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    LoadStateNotice(loadState = loadState, onRetry = onRetry)
                }
            }

            if (loadState is DocumentLoadState.Loaded && document.chunks.isEmpty()) {
                item(key = "note-empty") {
                    Text(
                        text = "This Note has no readable content.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            for (section in document.sections) {
                // Chunk ids are `sourceBlockId:part` (D7), so they are unique across the whole
                // document and safe to use as list keys.
                items(items = section.chunks, key = { it.id }) { chunk ->
                    ChunkItem(chunk)
                }
            }
        }

        // Kept outside the list so the primary action cannot scroll out of reach on a long note.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onPlay,
                enabled = loadState is DocumentLoadState.Loaded && document.chunks.isNotEmpty(),
                modifier = Modifier.restoreFocusOn(
                    target = focusTarget,
                    // A named id rather than a page id: the *button* is what was pressed, and the Play
                    // buttons of two different Notes are the same control. See `ReaderFocus`.
                    id = ReaderFocus.NotePlay,
                    onHandled = onFocusHandled
                )
            ) {
                Text("Play")
            }

            // Available on the same terms as Play: there is nothing to add to a queue until the Note has
            // loaded and has content, and a control that could only add an empty set would be one the
            // listener presses to no effect. `beginAddToQueue` refuses an empty set as well, so the screen and
            // the operation agree about when this is meaningful rather than the screen being the only guard.
            Button(
                onClick = onAddToQueue,
                enabled = loadState is DocumentLoadState.Loaded && document.chunks.isNotEmpty(),
                modifier = Modifier.restoreFocusOn(
                    target = focusTarget,
                    id = ReaderFocus.NoteAddToQueue,
                    onHandled = onFocusHandled
                )
            ) {
                Text("Add to queue")
            }
        }
    }
}

/**
 * Loading, loaded and failed are distinct states.
 *
 * The contract is explicit that "Loading Notes… is not equivalent to No matching Notes" and that
 * "loading must not be communicated only through animation", so each state is its own sentence.
 */
@Composable
private fun LoadStateNotice(loadState: DocumentLoadState, onRetry: () -> Unit) {
    when (loadState) {
        DocumentLoadState.Loading -> Text(
            text = "Loading Notes…",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )

        is DocumentLoadState.Error -> Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // The live region goes on the message itself, not on this Column. Semantics applied to a
            // parent that does not merge its descendants creates its own node, so TalkBack would
            // watch an empty container while the text sat in a child that never changed - the
            // announcement would silently never fire. Verified by an instrumented test that failed
            // when this modifier was on the Column.
            //
            // The Retry button is deliberately outside the announcement: a control should not be read
            // out as part of the sentence describing what went wrong.
            Text(
                text = "Could not load this Note. ${loadState.message}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )

            // Retry only where recovery is meaningful: an expired token or a page the integration
            // cannot see will fail identically on the next press, and offering Retry for it would be a
            // lie. The category decides, which is why it is carried in the state.
            if (loadState.canRetry) {
                Button(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }

        is DocumentLoadState.Loaded -> Unit
    }
}

/** One chunk, rendered according to its kind. */
@Composable
private fun ChunkItem(chunk: ReaderChunk) {
    when (chunk.kind) {
        ReaderChunkKind.HEADING -> Text(
            text = chunk.text,
            style = headingStyle(chunk.metadata.headingLevel),
            modifier = Modifier.semantics { heading() }
        )

        ReaderChunkKind.PARAGRAPH -> Text(
            text = chunk.text,
            style = MaterialTheme.typography.bodyLarge
        )

        ReaderChunkKind.LIST_ITEM -> MarkedLine(marker = "•") {
            Text(text = chunk.text, style = MaterialTheme.typography.bodyLarge)
        }

        ReaderChunkKind.TODO -> Row(
            modifier = Modifier.semantics(mergeDescendants = true) {
                stateDescription = if (chunk.metadata.checked == true) "Checked" else "Not checked"
            },
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Marker(if (chunk.metadata.checked == true) "☑" else "☐")
            Text(text = chunk.text, style = MaterialTheme.typography.bodyLarge)
        }

        ReaderChunkKind.QUOTE -> Text(
            text = chunk.text,
            style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
            modifier = Modifier.padding(start = 12.dp)
        )

        ReaderChunkKind.CODE -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val language = chunk.metadata.language
            if (!language.isNullOrBlank()) {
                // Deliberately readable, unlike the decorative markers above. The reader's speech layer
                // only ever speaks chunk text, so this can never be narrated during playback; showing it
                // here is the one place it is useful, and hiding it from TalkBack would withhold
                // information rather than protect anyone from noise.
                Text(
                    text = language,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Text(
                text = chunk.text,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            )
        }
    }
}

/** A line with a decorative marker that TalkBack skips. */
@Composable
private fun MarkedLine(marker: String, content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Marker(marker)
        content()
    }
}

/**
 * A glyph that is visible but carries no semantics.
 *
 * A bullet read aloud as "bullet" or a to-do read as "ballot box" is noise between the user and the
 * content; the meaning is conveyed by the item's text and, for a to-do, its state description.
 */
@Composable
private fun Marker(glyph: String) {
    Text(
        text = glyph,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .width(16.dp)
            .clearAndSetSemantics { }
    )
}

/**
 * Heading style for a heading level.
 *
 * The level comes from chunk metadata rather than from the enclosing section: a heading chunk is also
 * its section's title (D3), so reading the level off the section would work for section headings and
 * silently fall back to a default for a nested one.
 */
@Composable
private fun headingStyle(level: Int?): TextStyle = when (level) {
    1 -> MaterialTheme.typography.headlineMedium
    2 -> MaterialTheme.typography.titleLarge
    else -> MaterialTheme.typography.titleMedium
}
