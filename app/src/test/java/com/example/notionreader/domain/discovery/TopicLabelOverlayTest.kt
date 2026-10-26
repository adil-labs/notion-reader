package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test

class TopicLabelOverlayTest {
    private val identity = TopicLabelIdentity("a", "t", "c", "p", "s", "v", "GPU")
    private fun topic(): DiscoveredTopic {
        val body = "Increasing waits after a failed request reduces repeated load on an unavailable server and spreads retries over time."
        val passages = (0..1).map { i -> SemanticPassage("p$i", "n$i", "s$i", "Overview", "b$i", "c$i", 0, body.length, body, body, 0) }
        val anchor = passages.first()
        return DiscoveredTopic("canonical", "Overview", anchor, EmbeddedPassage(anchor, listOf(1f, 0f)),
            passages.map { TopicMembership(it.pageId, SemanticMetadata("Note"), it, 1f, anchor, "r1") }, 2)
    }
    private fun named(base: DiscoveredTopic, name: String = "Retry backoff"): DiscoveredTopic {
        val request = topicLabelRequest(base, identity, 0)!!
        val record = (validateTopicLabel(request, name, request.evidence.map { it.passageId }, request.groupFingerprint, 0,
            "Increasing retry waits reduces repeated server load.") as TopicLabelValidation.Accepted).label
        return base.copy(generatedLabel = record)
    }
    @Test fun aGeneratedNameChangesOnlyPresentationAndKeepsCanonicalGroupingFields() {
        val original = topic(); val labeled = named(original)
        assertEquals("Retry backoff", labeled.effectiveLabel)
        assertEquals(original.label, labeled.label)
        assertEquals(original.id, labeled.id); assertSame(original.members, labeled.members)
        assertEquals(original.anchor, labeled.anchor)
    }
    @Test fun supportedParaphrasedNamesAreNotRequiredToOccurLiterallyInTheSources() {
        val labeled = named(topic())
        assertFalse(labeled.labelSource.excerpt.contains("Retry backoff"))
        assertEquals(TopicPublicationStatus.Pending, discoveryTopicPublicationStatus(labeled))
    }
    @Test fun aRejectedGeneratedNameCannotNameOrHideTheCanonicalGroup() {
        val base = named(topic())
        val rejected = base.copy(generatedLabel = base.generatedLabel!!.copy(text = "Overview"))
        assertNull(rejected.currentGeneratedLabel)
        assertEquals("Topic name pending", rejected.effectiveLabel)
        assertEquals(TopicPublicationStatus.Pending, discoveryTopicPublicationStatus(rejected))
    }
    @Test fun changedSourcesOrForgedWorkIdentityLeaveTheGroupWithoutAName() {
        val labeled = named(topic())
        val changed = labeled.copy(members = labeled.members.map { it.copy(revision = "changed") })
        assertEquals("Topic name pending", changed.effectiveLabel)
        assertEquals("Topic name pending", labeled.copy(generatedLabel = labeled.generatedLabel!!.copy(workKey = semanticHash("forged"))).effectiveLabel)
    }
    @Test fun aClusterNameIsEvidenceAndNeverAnUnnamedGroupsDisplayName() {
        val original = topic().copy(label = "Internal cluster wording")
        assertEquals("Topic name pending", original.effectiveLabel)
        assertEquals("Internal cluster wording", original.label)
    }
    @Test fun eligibleUnnamedGroupsStayInspectableRegardlessOfTheClusterWording() {
        for (wording in listOf("Overview", "Notes related to a source", "bodies move and")) {
            val group = topic().copy(label = wording)
            assertEquals(listOf(group), publishableDiscoveryTopics(listOf(group)))
            assertEquals(TopicPublicationStatus.Pending, discoveryTopicPublicationStatus(group))
            assertEquals("Topic name pending", group.effectiveLabel)
        }
    }
    @Test fun sourceWithdrawalClearsDerivedNameMetadataAsWellAsItsDisplay() {
        val labeled = named(topic())
        val result = invalidateTopics(TopicSnapshot(topics = listOf(labeled)), setOf("n1"), true)
        assertEquals(1, result.topics.size)
        assertNull(result.topics.single().generatedLabel)
    }
}
