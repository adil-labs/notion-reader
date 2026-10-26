package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test

class TopicLabelRequestsTest {
    private val identity = TopicLabelIdentity("a", "t", "c", "p", "s", "v", "GPU")
    private fun topic(): DiscoveredTopic {
        val members = (0..5).map { index ->
            val page = "n$index"
            val body = "Increasing waits after failures reduces repeated requests to unavailable servers and prevents synchronized retry storms."
            val passage = SemanticPassage("p$index", page, "$page:s", "Overview", "$page:b", "$page:c", 0, body.length, body, body, 0)
            TopicMembership(page, SemanticMetadata("Title"), passage, 1f, passage, "r1")
        }
        return DiscoveredTopic("topic", "Overview", members.first().passage,
            EmbeddedPassage(members.first().passage, listOf(1f, 0f)), members, 1)
    }
    private fun saved(topic: DiscoveredTopic): GeneratedTopicLabel {
        val request = requireNotNull(topicLabelRequest(topic, identity, 9))
        return (validateTopicLabel(request, "Retry backoff", request.evidence.map { it.passageId },
            request.groupFingerprint, 9, "Increasing retry waits reduces repeated requests to unavailable servers.") as TopicLabelValidation.Accepted).label
    }
    @Test fun boundedSelectionIsStableAndContainsBodyEvidenceOnly() {
        val topic = topic()
        val request = requireNotNull(topicLabelRequest(topic, identity, 1))
        assertEquals(4, request.evidence.size)
        assertEquals(request.evidence, topicLabelRequest(topic.copy(members = topic.members.reversed()), identity, 1)!!.evidence)
        assertTrue(request.evidence.all { it.body.startsWith("Increasing waits") })
    }
    @Test fun aChangedUnselectedMemberInvalidatesTheLabel() {
        val topic = topic(); val saved = saved(topic)
        val changed = topic.copy(members = topic.members.mapIndexed { index, member ->
            if (index == 5) member.copy(revision = "r2", passage = member.passage.copy(excerpt = "An unrelated changed subject.")) else member
        })
        assertNotEquals(topicLabelFingerprint(topic), topicLabelFingerprint(changed))
        assertNull(reusableTopicLabel(changed, saved, identity))
    }
    @Test fun unchangedSourcesReuseLabelsAcrossEpochsAndExtractiveNameChanges() {
        val topic = topic(); val saved = saved(topic)
        assertEquals(saved, reusableTopicLabel(topic.copy(label = "Different old name", members = topic.members.reversed()), saved, identity))
        assertNotEquals(topicLabelWorkKey(topicLabelRequest(topic, identity, 0)!!),
            topicLabelWorkKey(topicLabelRequest(topic, identity.copy(prompt = "changed"), 0)!!))
        assertNull(reusableTopicLabel(topic, saved, identity.copy(prompt = "changed")))
    }
    @Test fun changedCitationsOrForgedWorkIdentityAreNotSuccessfulCacheEntries() {
        val topic = topic(); val saved = saved(topic)
        assertNull(reusableTopicLabel(topic, saved.copy(evidenceIds = listOf("unknown")), identity))
        assertNull(reusableTopicLabel(topic, saved.copy(workKey = semanticHash("forged")), identity))
    }
}
