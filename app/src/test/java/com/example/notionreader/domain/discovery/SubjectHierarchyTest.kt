package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test

class SubjectHierarchyTest {
    private val model = EmbeddingIdentity("hierarchy", 2)
    private fun passage(id: String, label: String, text: String) = SemanticPassage(
        "$id:p", id, "$id:s", label, "$id:b", "$id:c", 0, text.length, text, text, 0)
    private fun topic(id: String, label: String): DiscoveredTopic {
        val p = passage(id, label, "$label provides a substantive subject discussed by this source Note.")
        val e = EmbeddedPassage(p, listOf(1f, 0f))
        return DiscoveredTopic(id, label, p, e,
            listOf(TopicMembership(id, SemanticMetadata(id), p, 1f, p, "r")), 0)
    }
    private val parent = topic("parent", "Messaging Systems")
    private val child = topic("child", "RabbitMQ Retry Handling")
    private fun source(text: String, heading: String = "Design evidence"): SemanticRecord {
        val p = passage("source", heading, text)
        return SemanticRecord("source", "r", model, SemanticMetadata("Source"), listOf(EmbeddedPassage(p, listOf(1f, 0f))))
    }

    @Test fun explicitBodyEvidenceCreatesAnExplainableParentChildRelationship() {
        val record = source("RabbitMQ Retry Handling is a subtopic of Messaging Systems. Retries preserve delivery after transient failures.")
        val edge = buildSubjectRelationships(listOf(parent, child), listOf(record)).single()
        assertEquals(parent.id, edge.parentId)
        assertEquals(child.id, edge.childId)
        assertEquals(record.passages.single().passage, edge.evidence)
        assertEquals("r", edge.revision)
        assertEquals(SubjectRelationshipKind.ParentChild, edge.kind)
    }

    @Test fun similarVectorsSharedMembersAndHeadingsCannotInventParents() {
        val record = source("Rainfall absorbs heat and supports seasonal forest growth across the valley.", parent.label)
        assertTrue(buildSubjectRelationships(listOf(parent, child), listOf(record)).isEmpty())
    }

    @Test fun negatedHypotheticalPartialAndAmbiguousLabelsCannotEstablishAnEdge() {
        for (text in listOf(
            "RabbitMQ Retry Handling is not a type of Messaging Systems.",
            "If RabbitMQ Retry Handling is a type of Messaging Systems, the claim needs review.",
            "RabbitMQ Retry is a type of Messaging Systems.",
            "RabbitMQ Retry Handling is a type of Messaging Systems architecture."
        )) assertTrue(text, buildSubjectRelationships(listOf(parent, child), listOf(source(text))).isEmpty())
        assertTrue(buildSubjectRelationships(listOf(parent, child, parent.copy(id = "ambiguous")),
            listOf(source("RabbitMQ Retry Handling is a type of Messaging Systems."))).isEmpty())
    }

    @Test fun multipleParentsAreAllowedAndCyclesAreRejectedDeterministically() {
        val other = topic("other", "Reliability Engineering")
        val records = listOf(source("RabbitMQ Retry Handling is a part of Messaging Systems. " +
            "RabbitMQ Retry Handling is a part of Reliability Engineering. " +
            "Messaging Systems is a part of RabbitMQ Retry Handling."))
        val edges = buildSubjectRelationships(listOf(parent, child, other), records)
        assertEquals(2, edges.size)
        assertEquals(setOf(parent.id, other.id), edges.filter { it.childId == child.id }.map { it.parentId }.toSet())
        assertEquals(edges, buildSubjectRelationships(listOf(other, child, parent), records.reversed()))
        assertTrue(edges.none { a -> edges.any { b -> a.parentId == b.childId && a.childId == b.parentId } })
    }

    @Test fun sourceLossAndRevisionChangesWithdrawRelationshipEvidence() {
        val records = listOf(source("RabbitMQ Retry Handling is a type of Messaging Systems."))
        val edges = buildSubjectRelationships(listOf(parent, child), records)
        assertEquals(1, edges.size)
        assertTrue(validatedSubjectRelationships(listOf(parent, child), edges, mapOf("source" to "new")).isEmpty())
        assertTrue(validatedSubjectRelationships(listOf(parent), edges, mapOf("source" to "r")).isEmpty())
    }

    @Test fun relatedSubjectsRemainSeparateFromHierarchy() {
        val edges = buildSubjectRelationships(listOf(parent, child), listOf(source(
            "RabbitMQ Retry Handling is related to Messaging Systems. Both concern reliable delivery.")))
        assertEquals(SubjectRelationshipKind.Related, edges.single().kind)
    }

    @Test fun completedDiscoveryPublishesRelationshipsAndSourceRemovalWithdrawsThem() {
        fun record(t: DiscoveredTopic, vector: List<Float>) = SemanticRecord(t.id, "r", model,
            SemanticMetadata(t.id), listOf(EmbeddedPassage(t.labelSource, vector)))
        val records = listOf(record(parent, listOf(1f, 0f)), record(child, listOf(0f, 1f)), source(
            "RabbitMQ Retry Handling is a type of Messaging Systems."))
        val snapshot = SectionHierarchicalEngine(model).discover(records, null, TopicPolicy())
        assertEquals(1, snapshot.subjectRelationships.size)
        val withoutSource = invalidateTopics(snapshot, setOf("source"), permanent = false)
        assertTrue(withoutSource.subjectRelationships.isEmpty())
    }
}
