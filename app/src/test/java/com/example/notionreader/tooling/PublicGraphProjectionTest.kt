package com.example.notionreader.tooling

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PublicGraphProjectionTest {
    @Test fun exploratoryMembershipThresholdMustRespectShippingEvidenceFloor() {
        assertEquals(.8f, PublicGraphProjection.membershipThreshold("0.8"), 0f)
        for (value in listOf("NaN", "Infinity", "0.5", "1.1")) {
            try { PublicGraphProjection.membershipThreshold(value); fail("Invalid host threshold") }
            catch (_: IllegalArgumentException) { }
        }
    }
    private fun report(groups: List<List<String>>, hash: String = "native", algorithm: String = "faiss-hnsw-leiden-rbconfiguration") = buildJsonObject {
        put("algorithm", algorithm); put("seedOpportunities", 2)
        put("input", buildJsonObject { put("identity", buildJsonObject { put("nativeOpportunityManifestSha256", hash) }) })
        put("seedGroups", JsonArray(groups.map { group -> JsonArray(group.map(::JsonPrimitive)) }))
    }

    @Test fun graphPartitionMapsEveryVerifiedOpportunityBeforeCommonProjection() {
        assertEquals(listOf(listOf(1), listOf(0)), PublicGraphProjection.partition(report(listOf(listOf("b"), listOf("a"))),
            listOf("a", "b"), "native"))
    }

    @Test fun missingDuplicateUnknownOrWrongGenerationCommunitiesCannotBecomeReviewEvidence() {
        val invalid = listOf(report(listOf(listOf("a"))), report(listOf(listOf("a", "a"))),
            report(listOf(listOf("a", "other"))), report(listOf(emptyList(), listOf("a", "b"))),
            report(listOf(listOf("a", "b")), hash = "other"), report(listOf(listOf("a", "b")), algorithm = "different-algorithm"))
        invalid.forEach { input ->
            try { PublicGraphProjection.partition(input, listOf("a", "b"), "native"); fail("Invalid graph input must fail closed") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
