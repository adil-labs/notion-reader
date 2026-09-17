package com.example.notionreader.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import com.example.notionreader.data.notion.title
import com.example.notionreader.domain.ChunkMetadata
import com.example.notionreader.domain.ReaderChunk
import com.example.notionreader.domain.ReaderChunkKind
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderSection
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

    // ------------------------------------------------------------------ reachability

    /**
     * Asserts a control is laid out somewhere the listener can reach, **without asking the device**.
     *
     * ## Why this is not `assertIsDisplayed`, which is the obvious thing to write
     *
     * `assertIsDisplayed` was used here and was intermittently wrong. Full-suite runs on this device
     * reported 32/0, 32/1, 32/2 and 32/0 with a *different* test failing each time, always on a control
     * that was plainly present, and always with the uninformative message "is not displayed". Raising
     * `screen_off_timeout` and `stay_on_while_plugged_in` did not stop it, and neither assertion in the
     * suite that only checks for existence ever failed.
     *
     * The mechanism, read out of `AndroidAssertions_androidKt.checkIsDisplayed` in Compose 1.12.0 rather
     * than guessed: it requires the node's layout info to be placed, then it matches
     * **`androidx.test.espresso.matcher.ViewMatchers.isDisplayed()` against the root view**, then it
     * intersects the node's bounds in the window with `View.getGlobalVisibleRect()`. That last pair are
     * claims about **the device's window state** — whether the Compose view is currently being drawn and
     * has a non-empty visible rectangle on the display — and not about the app. They are exactly what the
     * two enabled accessibility services on this device can perturb, since both draw UI of their own
     * (Bitwarden's autofill overlay is a real floating window), and they are why the failure moves around
     * between runs: whichever assertion happens to run while the Compose view has no visible rectangle
     * fails, and it is not the same one twice.
     *
     * A test cannot fix that by asserting it harder, and keeping it means the suite fails for a reason
     * the app cannot influence. So this checks the app-side half, which is deterministic:
     *
     * 1. the node is **placed** — it has been through layout, so its position is a fact rather than a
     *    default;
     * 2. it **occupies area** — a zero-area control cannot be tapped by anyone;
     * 3. it **overlaps the surface root** — it is on the app's own layout rather than laid out past its
     *    edge, which is the "you would have to scroll to reach it" case;
     * 4. it **offers an OnClick action with a non-blank name** — so an accessibility service has
     *    something to focus and something to say, which is what "reachable" means for TalkBack
     *    specifically. A screen-reader user does not look at the screen; they move focus and activate.
     *
     * Point 3 is a genuine intersection rather than a containment test, and deliberately: this project
     * has already had one fix rejected for reading the layout more strictly than the layout promises
     * (`performScrollTo`, which threw because these controls are siblings of the scrolling content rather
     * than inside it — see `docs/decisions.md` §15), and whether a composable's bounds are clipped or
     * unclipped depends on where in the tree it sits. Intersection is the part that is true either way.
     *
     * What this gives up, stated so the gap is visible: it does not prove the device was displaying the
     * window at that instant. `tools/run-accessibility-tests.ps1` keeps the screen on so that the
     * question does not arise, and the requirement the original assertion was standing in for — "a
     * primary action a listener cannot reach is not a primary action" — is met by 1-4.
     */
    fun SemanticsNodeInteraction.assertIsReachable(): SemanticsNodeInteraction {
        val node = fetchSemanticsNode()
        val label = node.text() ?: node.contentDescription() ?: "node ${node.id}"
        val bounds = node.boundsInRoot

        // The topmost node's bounds in root are the surface itself, so this needs no knowledge of the
        // window, the screen, or the device.
        val surface = generateSequence(node) { it.parent }.last().boundsInRoot

        assertTrue(
            "'$label' exists in the tree but has never been laid out (isPlaced=false); bounds=$bounds",
            node.layoutInfo.isPlaced
        )

        assertTrue(
            "'$label' is laid out but occupies no area, so nothing can be tapped: bounds=$bounds",
            bounds.width > 0f && bounds.height > 0f
        )

        val overlapsSurface = bounds.left < surface.right && bounds.right > surface.left &&
            bounds.top < surface.bottom && bounds.bottom > surface.top

        assertTrue(
            "'$label' lies outside the app surface at $bounds (surface is $surface), so a listener " +
                "would have to scroll to reach it",
            overlapsSurface
        )

        val clickable = node.config.contains(SemanticsActions.OnClick)
        val name = "${node.text().orEmpty()} ${node.contentDescription().orEmpty()}".trim()

        assertTrue(
            "'$label' has no OnClick action, so an accessibility service cannot activate it",
            clickable
        )

        assertTrue(
            "'$label' offers OnClick but has no accessible name, so it would be announced as a " +
                "bare button",
            name.isNotBlank()
        )

        return this
    }
}
