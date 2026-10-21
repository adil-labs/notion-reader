package com.example.notionreader.domain.discovery

/** Reserves each prior identity for its strongest surviving source branch. */
internal fun reserveTopicBranches(prior: List<TopicContinuity>, groups: List<List<SemanticPassage>>,
    evidenceIdentity: (SemanticPassage) -> String = ::topicEvidenceIdentity,
    checkActive: () -> Unit = {}): Map<String, Int?> {
    if (prior.isEmpty()) return emptyMap()
    val identities = java.util.IdentityHashMap<SemanticPassage, String>()
    val candidates = groups.map { passages ->
        checkActive()
        val ids = passages.mapIndexed { index, passage ->
            if (index % 64 == 0) checkActive()
            identities.getOrPut(passage) { evidenceIdentity(passage) }
        }.toSet()
        ids to passages.map { canonicalSemanticId(it.pageId) }.toSet()
    }
    return prior.associate { old ->
        checkActive()
        val ranked = candidates.indices.map { index ->
            if (index % 64 == 0) checkActive()
            val (ids, pages) = candidates[index]
            val overlap = old.sourceIds.count { it in ids }
            val union = old.sourceIds.size + ids.size - overlap
            val specificity = if (union == 0) 0L else overlap * 1_000_000L / union
            index to (if (overlap == 0) 0L else overlap * 1_000_000_000L + specificity * 100L + old.pageIds.count { it in pages })
        }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<Int, Long>> { it.second }.thenBy { it.first })
        old.id to ranked.firstOrNull()?.first
    }
}
