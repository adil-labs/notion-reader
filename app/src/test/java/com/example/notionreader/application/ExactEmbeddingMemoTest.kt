package com.example.notionreader.application

import com.example.notionreader.domain.discovery.EmbeddingIdentity
import org.junit.Assert.*
import org.junit.Test

class ExactEmbeddingMemoTest {
    private val model = EmbeddingIdentity("int4", 2)
    @Test fun onlyExactlyEqualInputAndFullModelIdentityCanReuseAnEmbedding() {
        val memo = ExactEmbeddingMemo(2, 100)
        memo.put(model, "Area: Ecology; actual evidence", listOf(1f, 0f))
        assertEquals(listOf(1f, 0f), memo.get(model, "Area: Ecology; actual evidence"))
        assertNull(memo.get(model.copy(model = "int8"), "Area: Ecology; actual evidence"))
        assertNull(memo.get(model.copy(preprocessing = 99), "Area: Ecology; actual evidence"))
        assertNull(memo.get(model, "Area: Ecology; different evidence"))
    }
    @Test fun boundedCacheEvictsOldInputsAndReleaseDropsPrivateInputs() {
        val memo = ExactEmbeddingMemo(2, 10)
        memo.put(model, "a", listOf(1f, 0f)); memo.put(model, "b", listOf(0f, 1f))
        memo.get(model, "a")
        memo.put(model, "c", listOf(1f, 0f))
        assertNull(memo.get(model, "b")); assertNotNull(memo.get(model, "a"))
        memo.put(model, "longer than the character budget", listOf(1f, 0f))
        assertNull(memo.get(model, "longer than the character budget"))
        memo.clear(); assertNull(memo.get(model, "a"))
    }
    @Test fun releasedMemoryCannotBeRepopulatedByAnOlderInference() {
        val memo = ExactEmbeddingMemo()
        val token = memo.token()
        memo.clear()
        memo.put(model, "private old input", listOf(1f, 0f), token)
        assertNull(memo.get(model, "private old input"))
    }
    @Test fun callersCannotMutateStoredVectorsThroughEitherSideOfTheCache() {
        val memo = ExactEmbeddingMemo()
        val values = mutableListOf(1f, 0f)
        memo.put(model, "same", values); values[0] = 99f
        assertEquals(listOf(1f, 0f), memo.get(model, "same"))
    }
}
