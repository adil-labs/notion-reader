package com.example.notionreader.domain.discovery

/** Conservative English-first eligibility for automatic discovery, never a search-content filter. */
internal val referenceHeadings = setOf("related knowledge", "related notes", "related", "see also", "references",
    "further reading", "dependencies", "read next", "links", "navigation")
private val words = Regex("[\\p{L}\\p{N}]+")
/** Android matchers own native allocations. Reuse them, clearing source text after every operation. */
private class EvidencePattern(regex: Regex) {
    private val matchers = ThreadLocal.withInitial { regex.toPattern().matcher("") }
    private fun <T> use(text: String, action: (java.util.regex.Matcher) -> T): T {
        val matcher = requireNotNull(matchers.get()).reset(text)
        return try { action(matcher) } finally { matcher.reset("") }
    }
    fun tokens(text: String): List<String> = use(text) { matcher -> buildList {
        while (matcher.find()) add(matcher.group())
    } }
    fun count(text: String): Int = use(text) { matcher -> var count = 0; while (matcher.find()) count++; count }
    fun containsMatchIn(text: String): Boolean = use(text) { it.find() }
    fun matches(text: String): Boolean = use(text) { it.matches() }
    fun replace(text: String, replacement: String): String = use(text) { it.replaceAll(replacement) }
}
private val bodyWords = EvidencePattern(words)
private val sentenceBoundary = EvidencePattern(Regex("[.!?](?:\\s|$)"))
private val previousSentence = EvidencePattern(Regex("[.!]\\s"))
private val whitespace = EvidencePattern(Regex("\\s+"))
private val markdownLink = EvidencePattern(Regex("\\[[^]\\n]+]\\(https?://[^)]+\\)"))
private val plainLink = EvidencePattern(Regex("https?://\\S+"))
private val linkSeparators = EvidencePattern(Regex("[\\s*•,;:—–-]+"))
private val referenceStart = EvidencePattern(Regex("^(related(?: notes| knowledge)?|see also|references|depends on|read next|open next)\\s*:", RegexOption.IGNORE_CASE))
private val route = EvidencePattern(Regex("^(?:the future .{0,100}specification owns\\s*:|(?:exact|detailed) .{0,240}(?:defined|owned|specified|governed) by (?:the )?(?:separate|future) .{0,120}(?:specification|contract)\\.?)$", RegexOption.IGNORE_CASE))

internal fun substantiveDiscoveryEvidence(passage: SemanticPassage): Boolean {
    val text = passage.excerpt.trim()
    if (text.isBlank()) return false
    val tokens = bodyWords.tokens(text.lowercase())
    val wordCount = tokens.size
    if (text.endsWith("?") && wordCount < 12 && !previousSentence.containsMatchIn(text)) return false
    val sentenceCount = sentenceBoundary.count(text)
    val explanatory = wordCount >= 10 && sentenceCount > 0 || wordCount >= 8 && sentenceCount >= 2
    if (route.matches(whitespace.replace(text, " "))) return false
    if (referenceStart.containsMatchIn(text) && !(explanatory && sentenceCount >= 2)) return false
    val withoutLinks = linkSeparators.replace(plainLink.replace(markdownLink.replace(text, ""), ""), "")
    if (withoutLinks.isEmpty()) return false
    if (passage.sectionTitle?.trim()?.lowercase() in referenceHeadings) {
        val proseVerbs = setOf("is", "are", "was", "were", "has", "have", "can", "will", "should", "must",
            "helps", "help", "reduces", "reduce", "increases", "increase", "protects", "protect", "slows", "slow",
            "absorbs", "absorb", "releases", "release", "depends", "depend", "allows", "allow", "grow", "grows", "fear")
        if (!explanatory || tokens.none { it in proseVerbs }) return false
    }
    return true
}

/** Lexical near-duplicates are conservative: overlap of Notes alone never merges independent themes. */
internal fun nearDuplicateDiscoveryPassages(a: SemanticPassage, b: SemanticPassage): Boolean {
    val left = words.findAll(a.excerpt.lowercase()).map { it.value }.take(256).toList()
    val right = words.findAll(b.excerpt.lowercase()).map { it.value }.take(256).toList()
    if (left.size < 5 || right.size < 5) return false
    val polarity = setOf("not", "never", "without", "cannot", "always")
    if (left.filter { it in polarity }.toSet() != right.filter { it in polarity }.toSet()) return false
    val leftWindows = left.windowed(4).map { it.joinToString(" ") }.toSet()
    val rightWindows = right.windowed(4).map { it.joinToString(" ") }.toSet()
    return leftWindows.intersect(rightWindows).size.toDouble() / (leftWindows + rightWindows).size >= .85

}

/** Greedy relevance order with a deterministic exposure cap; never fills with below-threshold pairs. */
fun selectDiscoveryConnections(candidates: Collection<NoteConnection>, limit: Int = 20, perNote: Int = 2): List<NoteConnection> {
    require(limit >= 0 && perNote > 0)
    val count = mutableMapOf<String, Int>()
    return candidates.sortedWith(compareByDescending<NoteConnection> { it.score }.thenBy { it.id }).distinctBy { it.id }
        .filter { pair ->
            val a = canonicalSemanticId(pair.left.pageId); val b = canonicalSemanticId(pair.right.pageId)
            if (a == b || (count[a] ?: 0) >= perNote || (count[b] ?: 0) >= perNote) false
            else { count[a] = (count[a] ?: 0) + 1; count[b] = (count[b] ?: 0) + 1; true }
        }.take(limit)
}
