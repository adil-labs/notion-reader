package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test

class TopicPublicationTest {
    private fun topic(id: String, label: String = "Orbital mechanics", order: Long = 0,
        pages: List<String> = listOf("a", "b", "c"), theme: String = label,
        vector: List<Float> = listOf(1f, 0f)): DiscoveredTopic {
        val members = pages.map { page ->
            val text = "$theme explains how bodies move and exchange energy over long periods."
            val passage = SemanticPassage("$page:$theme", page, "$page:section:$theme", theme,
                "$page:block:$theme", "$page:chunk:$theme", 0, text.length, text, text, 0)
            TopicMembership(page, SemanticMetadata("Research $page"), passage, 1f, passage)
        }
        return DiscoveredTopic(id, label, members.first().passage,
            EmbeddedPassage(members.first().passage, vector), members, order)
    }
    @Test fun fallbackCandidatesRemainIntactButAreWithheld() {
        val fallback = topic("fallback", "Notes related to Research", theme = "Overview")
        val input = listOf(fallback)
        assertTrue(publishableDiscoveryTopics(input).isEmpty())
        assertSame(fallback, input.single())
    }
    @Test fun supportedCompleteSubjectLabelsArePublished() {
        val heading = topic("heading")
        val phrase = topic("phrase", "energy exchange", pages = listOf("x", "y", "z"), theme = "Different section").let { candidate ->
            candidate.copy(members = candidate.members.map { member -> member.copy(passage = member.passage.copy(
                excerpt = "The energy exchange between bodies changes their motion.")) },
                labelSource = candidate.labelSource.copy(excerpt = "The energy exchange between bodies changes their motion."))
        }
        assertEquals(listOf(heading, phrase), publishableDiscoveryTopics(listOf(heading, phrase)))
    }
    @Test fun genericUnsupportedAndTruncatedLabelsAreWithheld() {
        val inputs = listOf(topic("generic", "Overview"), topic("invented", "Quantum botany", theme = "Orbital mechanics"),
            topic("fragment", "bodies move and", theme = "Orbital mechanics"))
        assertTrue(publishableDiscoveryTopics(inputs).isEmpty())
    }
    @Test fun genericDocumentContainersDoNotBecomePublishedSubjects() {
        for (heading in listOf("Body", "Abstract", "Main text", "Full text")) {
            val candidate = topic("container", heading)
            assertNull("Container heading is not a subject: $heading", discoveryTopicPublicationStatus(candidate))
            assertTrue(publishableDiscoveryTopics(listOf(candidate)).isEmpty())
            assertEquals(heading, candidate.label)
        }
    }
    @Test fun repeatedNegativeOrModalClausesDoNotBecomeSubjectLabels() {
        for (label in listOf("no longer operates", "never returns again", "could become unavailable", "would require approval")) {
            assertTrue("A repeated clause is not a subject: $label", publishableDiscoveryTopics(listOf(topic(label, label))).isEmpty())
        }
    }
    @Test fun titleFamilyPrefixesNeedIndependentSubjectHeadings() {
        fun family(heading: Boolean): DiscoveredTopic {
            val base = topic("family", "Civic Atlas")
            val members = base.members.mapIndexed { index, member -> member.copy(
                metadata = SemanticMetadata("Civic Atlas — Module $index"),
                passage = member.passage.copy(sectionTitle = if (heading) "Civic Atlas" else "Implementation")) }
            return base.copy(members = members, labelSource = members.first().passage,
                anchor = base.anchor.copy(passage = members.first().passage))
        }
        val ambiguous = family(false)
        assertTrue(publishableDiscoveryTopics(listOf(ambiguous)).isEmpty())
        assertEquals("Civic Atlas", ambiguous.label)
        val authoredSubject = family(true)
        assertEquals(listOf(authoredSubject), publishableDiscoveryTopics(listOf(authoredSubject)))
        val twoHeadings = authoredSubject.copy(members = authoredSubject.members.mapIndexed { index, member ->
            if (index == 2) member.copy(passage = member.passage.copy(sectionTitle = "Implementation")) else member
        })
        assertEquals(listOf(twoHeadings), publishableDiscoveryTopics(listOf(twoHeadings)))
        val oneHeading = twoHeadings.copy(members = twoHeadings.members.mapIndexed { index, member ->
            if (index == 1) member.copy(passage = member.passage.copy(sectionTitle = "Implementation")) else member
        })
        assertTrue(publishableDiscoveryTopics(listOf(oneHeading)).isEmpty())
    }
    @Test fun repeatedMemberFamilyViewsCollapseDeterministically() {
        val first = topic("first", order = 1)
        val repeated = topic("repeat", order = 2, pages = listOf("a", "b", "c", "d"), theme = "Energy transfer")
        assertEquals(listOf(first), publishableDiscoveryTopics(listOf(repeated, first)))
    }
    @Test fun equalAuthoredLabelsCannotCollapseIndependentPassageSubjects() {
        val first = topic("one", "Architecture", order = 0)
        val second = topic("two", "Architecture", order = 1).let { candidate ->
            val members = candidate.members.map { member -> member.copy(passage = member.passage.copy(
                id = member.passage.id + ":other", sourceBlockId = member.passage.sourceBlockId + ":other",
                excerpt = "Separate physical components connect through acoustic channels with independent transmission constraints.")) }
            candidate.copy(members = members, labelSource = members.first().passage)
        }
        assertEquals(listOf(first, second), publishableDiscoveryTopics(listOf(first, second)))
    }

    @Test fun independentPassageThemesSurviveIdenticalMembers() {
        val orbit = topic("orbit", order = 1)
        val music = topic("music", "Musical harmony", 2, theme = "Musical harmony", vector = listOf(0f, 1f))
        assertEquals(listOf(orbit, music), publishableDiscoveryTopics(listOf(music, orbit)))
    }
    @Test fun publicationPreservesObjectsIdentitiesAndStableOrder() {
        val z = topic("z", order = 2)
        val a = topic("a", "Musical harmony", 2, pages = listOf("x", "y", "z"))
        val input = listOf(z, a)
        val result = publishableDiscoveryTopics(input)
        assertEquals(listOf("a", "z"), result.map { it.id })
        assertSame(a, result.first())
        assertSame(z, result.last())
        assertEquals(listOf(z, a), input)
    }
    @Test fun representativeLabelsAndSemanticMembershipAreValidatedIndependently() {
        val candidate = topic("weak")
        val weak = candidate.copy(members = candidate.members.mapIndexed { index, member ->
            if (index == 0) member else member.copy(passage = member.passage.copy(
                sectionTitle = "Other research", excerpt = "A separate subject explains processes in a different field."))
        })
        val substring = topic("substring", "Orbital mechanic", theme = "Orbital mechanics")
        assertEquals(listOf(weak), publishableDiscoveryTopics(listOf(weak, substring)))
    }
    @Test fun differentLabelsAndDistantVectorsAloneCannotProveIndependentThemes() {
        val first = topic("first", order = 1)
        val second = topic("second", "Energy transfer", 2, vector = listOf(0f, 1f)).let { candidate ->
            candidate.copy(members = candidate.members.mapIndexed { index, member -> member.copy(passage =
                member.passage.copy(excerpt = first.members[index].passage.excerpt)) })
        }
        assertEquals(listOf(first), publishableDiscoveryTopics(listOf(first, second)))
    }    @Test fun independentThemesInDisjointSpansOfTheSameBlockRemainPublished() {
        val orbit = topic("orbit", order = 1)
        val music = topic("music", "Musical harmony", 2, vector = listOf(0f, 1f)).let { candidate ->
            candidate.copy(members = candidate.members.mapIndexed { index, member -> member.copy(passage =
                member.passage.copy(sourceBlockId = orbit.members[index].passage.sourceBlockId,
                    start = 500, end = 500 + member.passage.excerpt.length)) }).let { it.copy(labelSource = it.members.first().passage) }
        }
        assertEquals(listOf(orbit, music), publishableDiscoveryTopics(listOf(orbit, music)))
        val overlapping = music.copy(members = music.members.map { member -> member.copy(passage =
            member.passage.copy(start = 0, end = member.passage.excerpt.length)) }).let { it.copy(labelSource = it.members.first().passage) }
        assertEquals(listOf(orbit), publishableDiscoveryTopics(listOf(orbit, overlapping)))
    }
    @Test fun aValidatedSingleNoteSubjectIsPublishedAsEmerging() {
        val emerging = topic("emerging", pages = listOf("one"))
        assertEquals(listOf(emerging), publishableDiscoveryTopics(listOf(emerging)))
    }
    @Test fun aSingleNoteWithoutBodyEvidenceStaysInternal() {
        val candidate = topic("candidate", pages = listOf("one"))
        val heading = candidate.members.single().passage.let { it.copy(sourceBlockId = it.sectionId) }
        assertTrue(publishableDiscoveryTopics(listOf(candidate.copy(members = listOf(candidate.members.single().copy(passage = heading)), labelSource = heading))).isEmpty())
    }
    @Test fun publicationRequiresBodyEvidenceFromEveryMember() {
        val candidate = topic("weak")
        val weak = candidate.copy(members = candidate.members.mapIndexed { index, member ->
            if (index < 2) member else member.copy(passage = member.passage.copy(sourceBlockId = member.passage.sectionId))
        })
        assertTrue(publishableDiscoveryTopics(listOf(weak)).isEmpty())
    }
    @Test fun validatedDistinctSubjectsSurviveSharedMembersAndNearbyVectors() {
        val orbit = topic("orbit")
        val music = topic("music", "Musical harmony", order = 1)
        assertEquals(listOf(orbit, music), publishableDiscoveryTopics(listOf(orbit, music)))
    }
    @Test fun alternativeLabelsDoNotDuplicateTheSameExperimentalEvidence() {
        val first = topic("first").let { candidate ->
            val members = candidate.members.map { member -> member.copy(passage = member.passage.copy(
                excerpt = "Orbital mechanics and energy transfer explain how bodies move through space over long periods.")) }
            candidate.copy(members = members, labelSource = members.first().passage)
        }
        val alternative = first.copy(id = "alternative", label = "energy transfer", createdOrder = 1)
        assertEquals(listOf(first), publishableDiscoveryTopics(listOf(first, alternative)))
    }
}
