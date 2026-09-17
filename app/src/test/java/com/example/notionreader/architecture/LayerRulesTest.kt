package com.example.notionreader.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enforces the Product & Architecture Contract's "Architectural boundaries" section against the source
 * tree, rather than trusting that the next change will respect it.
 *
 * The contract's diagram names the layers and its "Boundary rules" name three prohibitions, but a
 * document cannot fail a build. Before this test existed the code stated the layers in prose and
 * contradicted them in the import graph: the application package imported a type from the UI package
 * while the UI package imported two types back, which is not a layering with a direction but a knot; and
 * the reading domain imported the Notion integration layer while the integration layer imported the
 * domain back. Neither was noticed by any of the 276 tests, because a dependency cycle is not a
 * behaviour — it is a property nobody was asserting.
 *
 * ## Why the rules are stated as families
 *
 * Because the diagram is about families and not about sub-packages. `domain.queue` and `domain.session`
 * are both the domain, `data.notion` and `data.local` are both adapters, and requiring an order *within*
 * a family would be inventing a constraint the contract does not make. What the contract does say is
 * which direction dependencies run between the families, and that is what is checked.
 *
 * ## What a violation looks like
 *
 * The message names the file, the layer it is in, and the layer it reaches into, because the useful
 * response to a failure is to decide whether the dependency is legitimate — in which case this table is
 * what changes, deliberately — or whether it was an accident.
 */
class LayerRulesTest {

    /**
     * Which families each family is allowed to depend on.
     *
     * Read downward: a family may depend on the families to its right and never on the ones that depend
     * on it. `ui` may name `player` because a screen renders the Player's published state; it may not
     * name `data` or `platform`, because a screen that can construct a Notion client or a TTS engine is a
     * screen that cannot be tested without one.
     */
    private val allowed: Map<String, Set<String>> = mapOf(
        // The innermost layer. It names nothing of ours, which is what makes it testable with no device,
        // no dispatcher and no network.
        "domain" to emptySet(),

        // The Player depends on the domain and on nothing above it. It talks to TTS through the
        // SpeechEngine port, which it owns.
        "player" to setOf("domain"),

        // Use cases and application state. It orchestrates the Player over the domain, and knows nothing
        // about where content comes from or where history is stored - both are supplied by the
        // composition root or through a port.
        "application" to setOf("domain", "player"),

        // Adapters. They depend on the domain and on each other, and nothing depends on them except the
        // composition root.
        "data" to setOf("domain"),

        // Android-specific implementations. AndroidSpeechEngine implements the player's port.
        "platform" to setOf("player"),

        // Presentation.
        "ui" to setOf("domain", "player", "application")
    )

    /** The composition root, which is allowed to name everything because wiring is exactly its job. */
    private val rootFamily = ""

    private val packagePrefix = "com.example.notionreader"

    @Test
    fun `no layer depends on a layer above it`() {
        val violations = mutableListOf<String>()

        for (file in sourceFiles()) {
            val text = file.readText()
            val ownPackage = packageOf(text) ?: continue
            val ownFamily = familyOf(ownPackage)
            val permitted = allowed[ownFamily] ?: continue

            // Comments are removed before scanning. A KDoc link to a type in another layer is prose, not a
            // dependency, and flagging it would push the explanation out of the file that needs it.
            val code = stripComments(text)

            for (reference in internalReferences(code)) {
                val referencedFamily = familyOf(reference.substringBeforeLast('.'))
                if (referencedFamily == ownFamily) continue
                if (referencedFamily == rootFamily) continue
                if (referencedFamily in permitted) continue

                violations += "${relative(file)}: $ownPackage -> $reference"
            }
        }

        val report = violations.distinct()

        assertTrue(
            buildString {
                appendLine("Layer violations (the Product & Architecture Contract names these boundaries):")
                report.forEach { appendLine("  $it") }
                appendLine()
                appendLine("Allowed dependencies:")
                allowed.forEach { (family, targets) ->
                    val from = family.ifEmpty { "<composition root>" }
                    val to = if (targets.isEmpty()) "<nothing>" else targets.joinToString()
                    appendLine("  $from -> $to")
                }
            },
            report.isEmpty()
        )
    }

    /**
     * A source file's package must match its directory.
     *
     * **Kotlin does not require this**, which is the whole reason it is asserted. The language allows a
     * file declaring `package a.b` to sit in any directory, so a package can be moved for the compiler and
     * left behind for a reader - and the reader is who the layer structure is for. During the migration
     * this actually happened: the package declarations were rewritten before the files were moved, and a
     * build that was green in between would have described a tree that did not exist.
     */
    @Test
    fun `every source file declares the package its directory implies`() {
        val root = mainSourceRoot()
        val mismatches = mutableListOf<String>()

        for (file in sourceFiles()) {
            val declared = packageOf(file.readText()) ?: continue
            val directory = file.parentFile.relativeTo(root).path.replace(File.separatorChar, '.').trim('.')
            val expected = if (directory.isEmpty()) packagePrefix else "$packagePrefix.$directory"

            if (declared != expected) {
                mismatches += "${relative(file)}: declares '$declared', directory implies '$expected'"
            }
        }

        assertTrue(
            "Package declarations that disagree with the directory they are in:\n" +
                mismatches.joinToString("\n") { "  $it" },
            mismatches.isEmpty()
        )
    }

    /**
     * Every family in the rule table must correspond to a package that exists.
     *
     * Without this, a family renamed in the tree and not in the table is a rule that silently stops
     * applying: `allowed[ownFamily]` returns null, the loop `continue`s, and a whole layer becomes
     * unchecked while the suite stays green. A typo would be invisible; this makes it a failure.
     */
    @Test
    fun `every declared layer exists`() {
        val root = mainSourceRoot()

        val missing = allowed.keys.filter { family ->
            family.isNotEmpty() && !File(root, family).isDirectory
        }

        assertTrue(
            "Layer families in the rule table with no package in the tree: ${missing.joinToString()}. " +
                "A rule for a package that does not exist checks nothing.",
            missing.isEmpty()
        )
    }

    // -------------------------------------------------------------------------------------------------
    // Source scanning
    // -------------------------------------------------------------------------------------------------

    /**
     * The main source root, found by walking up from the test's working directory.
     *
     * Walked rather than assumed because the working directory of a unit test is a Gradle detail: it is
     * the module directory here, and a test that hard-codes `app/src/main/...` from the repository root
     * would pass locally and fail everywhere the layout differs.
     */
    private fun mainSourceRoot(): File {
        val relative = "src/main/java/com/example/notionreader"
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile

        while (directory != null) {
            for (candidate in listOf(File(directory, relative), File(directory, "app/$relative"))) {
                if (candidate.isDirectory) return candidate.canonicalFile
            }
            directory = directory.parentFile
        }

        error("Could not find $relative above ${System.getProperty("user.dir")}")
    }

    private fun sourceFiles(): List<File> =
        mainSourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun relative(file: File): String = file.relativeTo(mainSourceRoot()).path

    private fun packageOf(text: String): String? =
        Regex("^package\\s+(\\S+)\\s*$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)

    /**
     * The layer family a package belongs to: the first segment beneath our own prefix, and the empty
     * string for the root itself.
     *
     * `com.example.notionreader.domain.queue` and `com.example.notionreader.domain` are both `domain`.
     */
    private fun familyOf(packageName: String): String {
        if (packageName == packagePrefix) return rootFamily
        if (!packageName.startsWith("$packagePrefix.")) return ""
        return packageName.removePrefix("$packagePrefix.").substringBefore('.')
    }

    /**
     * Every fully-qualified reference to our own code.
     *
     * Fully-qualified rather than import-only, because an import is not the only way to reach across a
     * layer: a fully-qualified reference in the middle of a function couples two packages just as firmly,
     * and would otherwise be the way to break the rule without failing this test.
     */
    private fun internalReferences(code: String): List<String> =
        Regex("\\b${Regex.escape(packagePrefix)}(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
            .findAll(code)
            .map { it.value }
            // A bare package reference has no member, so it is not a dependency on a type. It cannot
            // arise from an import and is dropped so that `domain.queue` alone is not treated as a type.
            .filter { it.count { character -> character == '.' } > 2 }
            .toList()

    /**
     * Removes comments but leaves code on the same line intact.
     *
     * Block comments go entirely. Line comments are removed only when they occupy the whole line, because
     * `//` also occurs inside string literals - a URL is the common case - and truncating from the first
     * `//` would delete real code that shares the line with one.
     */
    private fun stripComments(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .joinToString("\n") { line -> if (line.trimStart().startsWith("//")) "" else line }
}
