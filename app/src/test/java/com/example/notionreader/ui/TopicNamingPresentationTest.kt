package com.example.notionreader.ui

import com.example.notionreader.domain.discovery.*
import org.junit.Assert.*
import org.junit.Test

class TopicNamingPresentationTest {
    private fun topic(): DiscoveredTopic {
        val body = "Increasing waits after a failed request reduces repeated load on an unavailable server and spreads retries over time."
        val p = SemanticPassage("p", "n", "s", "Overview", "b", "c", 0, body.length, body, body, 0)
        return DiscoveredTopic("canonical", "Internal cluster wording", p, EmbeddedPassage(p, listOf(1f)),
            listOf(TopicMembership("n", SemanticMetadata("Note"), p, 1f, p, "r1")), 0)
    }
    private fun named(topic: DiscoveredTopic): DiscoveredTopic {
        val request = topicLabelRequest(topic, TopicLabelIdentity("a", "t", "c", "p", "s", "v", "GPU"), 0)!!
        val label = (validateTopicLabel(request, "Retry backoff", request.evidence.map { it.passageId },
            request.groupFingerprint, 0, "Increasing retry waits reduces repeated server load.") as TopicLabelValidation.Accepted).label
        return topic.copy(generatedLabel = label)
    }
    @Test fun anUnnamedGroupHasNeutralPresentationAndNeverExposesClusterWording() {
        assertEquals("Topic name pending", topicDisplayName(topic()))
    }
    @Test fun aCurrentGeneratedNameIsSharedByAllTopicPresentations() {
        assertEquals("Retry backoff", topicDisplayName(named(topic())))
    }
    @Test fun anExplicitAliasWinsWithoutChangingTheGeneratedNameOrCanonicalIdentity() {
        val original = named(topic())
        val aliases = listOf(DiscoveryInterest("alias", "server retries", displayName = "My reliability notes", originTopicId = original.id))
        assertEquals("My reliability notes", topicDisplayName(original, aliases))
        assertEquals("My reliability notes", topicDisplayName(topic(), aliases))
        assertEquals("Retry backoff", original.effectiveLabel)
        assertEquals("Topic name pending", topicDisplayName(topic().copy(id = "other"), aliases))
    }
    @Test fun namingEvidenceContainsOnlyTheCurrentOfferedPassagesAndWithdrawsWithItsName() {
        val original = topic()
        val many = original.copy(members = (0..5).map { i -> original.members.single().let { member ->
            member.copy(pageId = "n$i", passage = member.passage.copy(id = "p$i", pageId = "n$i"))
        } })
        val labeled = named(many)
        assertEquals(listOf("p0", "p1", "p2", "p3"), topicNamingEvidence(labeled).map { it.passage.id })
        assertEquals(6, labeled.members.size)
        val changed = labeled.copy(members = labeled.members.map { it.copy(revision = "changed") })
        assertTrue(topicNamingEvidence(changed).isEmpty())
        assertTrue(topicNamingEvidence(original).isEmpty())
    }
    @Test fun canonicalPresentationUsesVerifiedDescriptionCitationsAndMatches() {
        val raw = topic(); val identity = TopicLabelIdentity("a", "t", "c", "p", "s", "v", "GPU")
        val key = semanticHash("concept"); val member = raw.members.single()
        val concept = CanonicalConceptEvidence(key, "Seed germination", "Seeds need moisture.",
            "Listen to seed germination.", identity, identity, emptyList(), emptyList())
        val projection = CanonicalTopicProjection(key, concept.name, concept.definition, concept.listeningDescription,
            listOf(member), listOf(member))
        val ready = raw.copy(canonicalConcept = concept, canonicalProjection = projection)
        assertEquals("Seed germination", topicDisplayName(ready))
        assertEquals("1 matching Note", topicNoteCount(ready))
        assertEquals("Listen to seed germination.", topicDescription(ready))
        assertEquals("Seed germination\nSeeds need moisture.", topicFollowText(ready))
        assertEquals(listOf(member), topicNamingEvidence(ready))
        assertNull(topicSourceContext(ready))
        assertEquals("Matching Notes pending", topicNoteCount(ready.copy(canonicalProjection = projection.copy(members = null))))
        assertEquals("No matching Notes", topicNoteCount(ready.copy(canonicalProjection = projection.copy(members = emptyList()))))
    }
    @Test fun aNamedSeedWithoutRetrievalDoesNotClaimSeedNotesAsMatchingNotes() {
        assertEquals("Matching Notes pending", topicNoteCount(named(topic())))
    }
    @Test fun countsAndPublicationStatusUseRetrievedNotesInsteadOfSeedMembership() {
        val original = named(topic())
        val concept = original.currentGeneratedLabel!!
        val query = TopicRetrievalQuery(original.id, concept.workKey, topicConceptQuery(concept)!!,
            EmbeddingIdentity("matches", 1), TopicRetrievalPolicy(.8f))
        val outside = original.members.single().copy(pageId = "outside")
        val matched = original.copy(topicMatches = TopicMatches(query, listOf(outside, outside.copy(pageId = "second"))))
        assertEquals("2 matching Notes", topicNoteCount(matched))
        assertEquals(TopicPublicationStatus.Established, discoveryTopicPublicationStatus(matched))
        val single = matched.copy(topicMatches = TopicMatches(query, listOf(outside)))
        assertEquals(TopicPublicationStatus.Emerging, discoveryTopicPublicationStatus(single))
        val empty = matched.copy(topicMatches = TopicMatches(query, emptyList()))
        assertEquals("No matching Notes", topicNoteCount(empty))
        assertEquals(TopicPublicationStatus.NoMatches, discoveryTopicPublicationStatus(empty))
        assertEquals(TopicPublicationStatus.Pending, discoveryTopicPublicationStatus(original))
    }
}
