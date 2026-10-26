package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test

class TopicLabelingTest {
    private val identity = TopicLabelIdentity("artifact", "tokenizer", "template", "prompt", "schema", "policy", "GPU")
    private val request = TopicLabelRequest("topic", "group-source-v1", 4,
        listOf(TopicLabelEvidence("p", "note", "revision", "Seed germination requires moisture.")), identity)
    private fun validate(label: String = "Seed germination conditions", ids: List<String> = listOf("p"),
        fingerprint: String = request.groupFingerprint, epoch: Long = request.sourceEpoch) =
        validateTopicLabel(request, label, ids, fingerprint, epoch, "How moisture supports seed germination.")

    @Test fun acceptedLabelKeepsTheCanonicalTopicAndEvidenceIdentity() {
        val accepted = validate() as TopicLabelValidation.Accepted
        assertEquals("topic", accepted.label.topicId)
        assertEquals(request.groupFingerprint, accepted.label.groupFingerprint)
        assertEquals(topicLabelWorkKey(request), accepted.label.workKey)
        assertEquals("Seed germination conditions", accepted.label.text)
    }
    @Test fun missingBlankOversizedOrMalformedDefinitionsCannotBecomeRetrievalConcepts() {
        for (definition in listOf(null, " ", "x".repeat(513), "\uD800", "source\ncommand")) {
            assertEquals(TopicLabelValidation.Rejected(TopicLabelRejection.InvalidDefinition),
                validateTopicLabel(request, "Seed germination", listOf("p"), request.groupFingerprint, request.sourceEpoch, definition))
        }
    }
    @Test fun retrievalQueriesUseBothGeneratedMeaningFieldsAndRejectIncompleteMeaning() {
        val label = (validate() as TopicLabelValidation.Accepted).label
        assertEquals("Seed germination conditions\nHow moisture supports seed germination.", topicConceptQuery(label))
        assertNull(topicConceptQuery(label.copy(definition = "")))
        assertNotEquals(topicConceptQuery(label), topicConceptQuery(label.copy(definition = "How temperature affects seed germination.")))
    }
    @Test fun changedSourcesCannotReceiveAnOldLabel() {
        assertEquals(TopicLabelValidation.Rejected(TopicLabelRejection.StaleSources), validate(fingerprint = "changed"))
        assertEquals(TopicLabelValidation.Rejected(TopicLabelRejection.StaleSources), validate(epoch = 5))
    }
    @Test fun fabricatedOmittedOrRepeatedEvidenceCannotBecomeALabel() {
        for (ids in listOf(emptyList(), listOf("unknown"), listOf("p", "p")))
            assertEquals(TopicLabelValidation.Rejected(TopicLabelRejection.EvidenceMismatch), validate(ids = ids))
    }
    @Test fun blankOversizedAndMalformedUnicodeLabelsAreRejected() {
        for (label in listOf(" ", "x".repeat(97), "\uD800", "name\ncommand"))
            assertEquals(TopicLabelValidation.Rejected(TopicLabelRejection.InvalidLabel), validate(label = label))
    }
    @Test fun genericNamesAndIncompletePhrasesAreRejectedBeforeTheyCanBeCached() {
        for (label in listOf("Overview", "Full text", "Notes related to Research", "bodies move and", "no longer operates"))
            assertEquals(label, TopicLabelValidation.Rejected(TopicLabelRejection.InvalidLabel), validate(label = label))
    }
    @Test fun unchangedEvidenceReusesTheKeyButChangedSourcesOrPromptDoNot() {
        assertEquals(topicLabelWorkKey(request), topicLabelWorkKey(request.copy()))
        assertNotEquals(topicLabelWorkKey(request), topicLabelWorkKey(request.copy(groupFingerprint = "changed")))
        assertNotEquals(topicLabelWorkKey(request), topicLabelWorkKey(request.copy(identity = identity.copy(prompt = "new"))))
        assertNotEquals(topicLabelWorkKey(request), topicLabelWorkKey(request.copy(evidence = request.evidence.map { it.copy(body = "Changed body") })))
    }
    @Test fun liveEpochChangesDoNotInvalidateAnUnchangedDurableLabelKey() {
        assertEquals(topicLabelWorkKey(request), topicLabelWorkKey(request.copy(sourceEpoch = 99)))
        assertEquals(TopicLabelValidation.Rejected(TopicLabelRejection.StaleSources), validate(epoch = 99))
    }

}
