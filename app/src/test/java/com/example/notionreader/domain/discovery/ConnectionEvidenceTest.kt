package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test

class ConnectionEvidenceTest {
    @Test fun boundedReferenceCandidatesPreserveIndependentRetrievalEvidence() {
        val input = records("See Coastal forest recovery.")
        val index = ExplicitNoteReferenceIndex(input.associate { it.pageId to it.metadata.title })
        val candidates = input.flatMap { source -> source.passages.flatMap { p -> input.mapNotNull { target ->
            index.candidate(source, p.passage, target)
        } } }
        assertEquals(discoverExplicitNoteReferences(input), candidates)
        val ambiguous = ExplicitNoteReferenceIndex(input.associate { it.pageId to it.metadata.title } + ("other" to input.last().metadata.title))
        assertNull(ambiguous.candidate(input.first(), input.first().passages.first().passage, input.last()))
    }

    @Test fun sourceWithdrawalRemovesPrivateReferenceIndexTitlesWithoutRequiringPublishedTopics() {
        val original = TopicSnapshot(referenceTitles = mapOf("a" to "Private source title", "b" to "Target"))
        val removed = invalidateTopics(original, setOf("a"), false)
        assertEquals(mapOf("b" to "Target"), removed.referenceTitles)
    }
    private val sourceTitle = "Coastal field journal"
    private val targetTitle = "Coastal forest recovery"
    private val titles = mapOf("a" to sourceTitle, "b" to targetTitle)

    private val identity = EmbeddingIdentity("reference-test", 2)
    private fun records(sourceText: String): List<SemanticRecord> {
        val connection = pair(sourceText, "Sedimentary measurements from the target Note remain literal source context.")
        return listOf(connection.left, connection.right).mapIndexed { index, member ->
            SemanticRecord(member.pageId, "current", identity, member.metadata,
                listOf(EmbeddedPassage(member.passage, if (index == 0) listOf(1f, 0f) else listOf(0f, 1f))))
        }
    }

    @Test fun anExplicitReferenceDoesNotRequireSemanticSimilarityOrThematicProse() {
        val results = discoverExplicitNoteReferences(records("See Coastal forest recovery."))
        assertEquals(1, results.size)
        assertTrue(results.single().independentNamedReference)
        assertEquals(ConnectionEvidenceKind.NamedReference, classifyConnectionEvidence(results.single(), titles).kind)
        assertEquals("See Coastal forest recovery.", results.single().left.passage.excerpt)
        assertEquals("Sedimentary measurements from the target Note remain literal source context.", results.single().right.passage.excerpt)
    }

    @Test fun ambiguousPartialAndNegatedReferencesAreNotRetrieved() {
        for (source in listOf("See Coastal forest.", "Do not see Coastal forest recovery.", "Coastal forest recovery."))
            assertTrue(discoverExplicitNoteReferences(records(source)).isEmpty())
        val original = records("See Coastal forest recovery.")
        val duplicate = original.last().copy(pageId = "c", passages = original.last().passages.map {
            it.copy(passage = it.passage.copy(pageId = "c", id = "c:passage")) })
        assertTrue(discoverExplicitNoteReferences(original + duplicate).isEmpty())
    }

    @Test fun referenceRetrievalIsDeterministicAndRetainsCanonicalPairIdentity() {
        val original = records("Read Coastal forest recovery.")
        val first = discoverExplicitNoteReferences(original).single()
        assertEquals(first, discoverExplicitNoteReferences(original.reversed()).single())
        assertEquals(semanticHash("a|b|reference"), first.id)
    }

    private fun pair(source: String, target: String = "Mangrove forests stabilize sediment and protect coastlines during severe storm surges."): NoteConnection {
        fun member(id: String, title: String, text: String): TopicMembership {
            val passage = SemanticPassage("$id:passage", id, "$id:section", "Field observations", "$id:block", "$id:chunk",
                0, text.length, text, text, 0)
            return TopicMembership(id, SemanticMetadata(title), passage, .8f, passage)
        }
        return NoteConnection("pair", member("a", sourceTitle, source), member("b", targetTitle, target), .8f)
    }

    @Test fun anExplicitInstructionNamesTheOtherCurrentNoteWithoutClaimingAHyperlink() {
        val connection = pair("Open Coastal forest recovery. This Note explains habitat changes after severe coastal storms.")
        val evidence = classifyConnectionEvidence(connection, titles)
        assertEquals(ConnectionEvidenceKind.NamedReference, evidence.kind)
        assertEquals(listOf(NamedNoteReference("a", "b")), evidence.references)
        assertEquals("pair", connection.id)
    }

    @Test fun aCitationAndQuotedInstructionCanIdentifyEitherDirection() {
        val left = "As discussed in Coastal forest recovery, mangrove roots support recovery after severe storms."
        val right = "Please read “Coastal field journal”. The author records shoreline changes after seasonal storms."
        assertEquals(listOf(NamedNoteReference("a", "b"), NamedNoteReference("b", "a")),
            classifyConnectionEvidence(pair(left, right), titles).references)
    }

    @Test fun coincidenceNegationPartialTitlesAndReferencesToOtherNotesRemainSimilarityEvidence() {
        for (text in listOf(
            "Coastal forest recovery protects nesting birds and stabilizes sediment during severe coastal storms.",
            "Do not open Coastal forest recovery. The guide is currently incomplete and needs revision.",
            "Open Coastal forest. The guide describes recovery after severe storms along the eastern coastline.",
            "Open Coastal forest recovery guide. The document records detailed habitat measurements after storms.",
            "Open Volcanic rock weathering. This document records chemical changes in rocks after heavy rainfall."
        )) assertEquals(text, ConnectionEvidenceKind.SimilarPassages, classifyConnectionEvidence(pair(text), titles).kind)
    }

    @Test fun ambiguousStaleAndUnavailableTitlesCannotProduceNamedReferences() {
        val connection = pair("Open Coastal forest recovery. This Note describes habitat changes after severe coastal storms.")
        for (available in listOf(titles + ("c" to targetTitle), titles + ("b" to "Old title"), titles - "b", titles - "a")) {
            assertEquals(ConnectionEvidenceKind.SimilarPassages, classifyConnectionEvidence(connection, available).kind)
        }
    }

    @Test fun canonicalAliasesAndWhitespaceDoNotCreateAmbiguity() {
        val connection = pair("Please read ‘COASTAL  FOREST RECOVERY’. It describes habitat changes after severe coastal storms.")
        assertEquals(ConnectionEvidenceKind.NamedReference,
            classifyConnectionEvidence(connection, titles + ("B" to " coastal forest recovery ")).kind)
    }

    @Test fun anIsolatedHeadingCannotEstablishAReference() {
        val connection = pair("Open Coastal forest recovery. This Note describes habitat changes after severe coastal storms.")
        val heading = connection.left.copy(passage = connection.left.passage.copy(sourceBlockId = connection.left.passage.sectionId))
        assertEquals(ConnectionEvidenceKind.SimilarPassages, classifyConnectionEvidence(connection.copy(left = heading), titles).kind)
    }
}
