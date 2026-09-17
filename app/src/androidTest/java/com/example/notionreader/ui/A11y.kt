package com.example.notionreader.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import com.example.notionreader.reader.ChunkMetadata
import com.example.notionreader.reader.ReaderChunk
import com.example.notionreader.reader.ReaderChunkKind
import com.example.notionreader.reader.ReaderDocument
import com.example.notionreader.reader.ReaderSection
import org.junit.Assert.assertTrue

/**
 * Shared helpers for the instrumented accessibility tests.
 *
 * These tests exist because the unit tests cannot see semantics at all: `ReaderState` and the mapper
 * are plain Kotlin, so "is this control announced usefully" is not a question they can answer. The
 * integration spec makes accessibility validation a POC gate — "verify that every action is exposed
 * with an understandable label" — and the UI contract adds that focus order must match reading order
 * and that state changes need concise spoken feedback.
 *
 * The assertions deliberately target the semantics tree, because that is precisely what TalkBack
 * consumes. A test that inspected `Text` composables directly could pass while the rendered tree was
 * unusable.
 */
internal object A11y {

    /** Matches every node, so a whole tree can be enumerated and inspected in aggregate. */
    val ANY: SemanticsMatcher = SemanticsMatcher("any node") { true }

    private fun SemanticsNode.text(): String? =
        if (config.contains(SemanticsProperties.Text)) {
            config[SemanticsProperties.Text].joinToString(" ") { it.text }
        } else {
            null
        }

    private fun SemanticsNode.contentDescription(): String? =
        if (config.contains(SemanticsProperties.ContentDescription)) {
            config[SemanticsProperties.ContentDescription].joinToString(" ")
        } else {
            null
        }

    /** A document exercising every chunk kind the Note screen renders differently. */
    fun document(): ReaderDocument = ReaderDocument(
        pageId = "a11y",
        title = "Accessibility Fixture",
        sections = listOf(
            ReaderSection(
                id = "a11y:intro",
                title = null,
                level = 0,
                chunks = listOf(
                    chunk("intro", ReaderChunkKind.PARAGRAPH, "Intro paragraph."),
                    chunk("bullet", ReaderChunkKind.LIST_ITEM, "Bullet item."),
                    chunk(
                        "todo-off",
                        ReaderChunkKind.TODO,
                        "Unchecked task.",
                        ChunkMetadata(checked = false)
                    ),
                    chunk(
                        "todo-on",
                        ReaderChunkKind.TODO,
                        "Checked task.",
                        ChunkMetadata(checked = true)
                    )
                )
            ),
            ReaderSection(
                id = "a11y:h1",
                title = "Primary Heading",
                level = 1,
                chunks = listOf(
                    chunk("h1", ReaderChunkKind.HEADING, "Primary Heading", ChunkMetadata(headingLevel = 1)),
                    chunk("quote", ReaderChunkKind.QUOTE, "Quoted text."),
                    chunk(
                        "code",
                        ReaderChunkKind.CODE,
                        "val answer = 42",
                        ChunkMetadata(language = "kotlin")
                    )
                )
            ),
            ReaderSection(
                id = "a11y:h2",
                title = "Secondary Heading",
                level = 2,
                chunks = listOf(
                    chunk("h2", ReaderChunkKind.HEADING, "Secondary Heading", ChunkMetadata(headingLevel = 2))
                )
            )
        )
    )

    private fun chunk(
        id: String,
        kind: ReaderChunkKind,
        text: String,
        metadata: ChunkMetadata = ChunkMetadata()
    ) = ReaderChunk(
        id = "$id:0",
        sourceBlockId = id,
        kind = kind,
        text = text,
        metadata = metadata
    )

    // ------------------------------------------------------------------ semantic assertions

    /** Every node that offers a click action must also be able to describe itself to TalkBack. */
    fun assertEveryClickableIsLabelled(collection: SemanticsNodeInteractionCollection) {
        val unlabelled = collection.fetchSemanticsNodes().mapNotNull { node ->
            if (!node.config.contains(SemanticsActions.OnClick)) return@mapNotNull null

            val text = node.text().orEmpty()
            val description = node.contentDescription().orEmpty()

            if (text.isBlank() && description.isBlank()) node.id.toString() else null
        }

        assertTrue(
            "these clickable nodes would be announced as an unlabelled 'button': $unlabelled",
            unlabelled.isEmpty()
        )
    }

    /**
     * Asserts the named content appears in the given order.
     *
     * This is the objective form of "TalkBack focus order must match logical reading order", and it reads
     * the order out of the **semantics tree** rather than off the screen. TalkBack walks that tree, so
     * its order *is* the reading order; the tree's order for a `LazyColumn` follows item index, which is
     * document order.
     *
     * **This deliberately does not sort by geometry, and that is a bug fix rather than a simplification.**
     * The first version sorted nodes by `boundsInRoot.top`, which reads as more objective but is not:
     * a `LazyColumn` composes its items progressively, so at capture time some nodes have settled bounds
     * and others report a stale or zero rect. Sorting by a mixture of real and stale tops reorders the
     * list, and the result was a test that failed intermittently — once in a full-suite run and never in
     * isolation — with a "rendered order" that was an artefact of the sort rather than of the rendering.
     * The geometry is still included in the failure message, so a genuine layout problem is diagnosable.
     */
    fun assertReadingOrder(vararg expected: String, collection: SemanticsNodeInteractionCollection) {
        val nodes = collection.fetchSemanticsNodes()
        val ordered = nodes.mapNotNull { it.text() }
        val tops = nodes.mapNotNull { node -> node.text()?.let { it to node.boundsInRoot.top } }

        val positions = expected.map { label -> label to ordered.indexOfFirst { it.contains(label) } }

        val missing = positions.filter { it.second < 0 }.map { it.first }
        assertTrue("expected these to be rendered at all: $missing", missing.isEmpty())

        positions.zipWithNext().forEach { (earlier, later) ->
            assertTrue(
                "'${earlier.first}' (index ${earlier.second}) must come before " +
                    "'${later.first}' (index ${later.second}); reading order was $ordered; " +
                    "top edges were $tops",
                earlier.second < later.second
            )
        }
    }

    fun hasLiveRegion(): SemanticsMatcher =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)

    fun hasNoLiveRegion(): SemanticsMatcher =
        SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion)

    fun hasHeading(): SemanticsMatcher =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
}
