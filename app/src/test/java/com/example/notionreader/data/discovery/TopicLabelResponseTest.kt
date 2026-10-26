package com.example.notionreader.data.discovery

import com.example.notionreader.domain.discovery.*
import org.junit.Assert.*
import org.junit.Test

class TopicLabelResponseTest {
    private val request = TopicLabelRequest("topic", "fingerprint", 1,
        listOf(TopicLabelEvidence("p", "page", "r", "Moisture supports germination.")),
        TopicLabelIdentity("a", "t", "c", "p", "s", "v", "GPU"))
    private val valid = """{"topics":[{"label":"Seed germination","definition":"How moisture supports seed germination.","passage_ids":["p"]}]}"""
    @Test fun aConceptIncludesABoundedDefinitionSupportedByItsOfferedEvidence() {
        assertEquals("How moisture supports seed germination.", parseTopicLabelResponse(valid, request)?.definition)
    }
    @Test fun anOldNameOnlyResponseCannotBecomeARetrievalConcept() {
        assertNull(parseTopicLabelResponse(valid.replace("\"definition\":\"How moisture supports seed germination.\",", ""), request))
    }
    @Test fun invalidDefinitionValuesAreRejectedWithoutWeakeningTheClosedSchema() {
        for (replacement in listOf("3", "null", "\" \"", "\"${"x".repeat(513)}\"", "\"\\ud800\"")) {
            assertNull(parseTopicLabelResponse(valid.replace("\"How moisture supports seed germination.\"", replacement), request))
        }
        assertNull(parseTopicLabelResponse(valid.replace("\"definition\":", "\"definition\":\"Hidden meaning\",\"definition\":"), request))
    }
    @Test fun aSingleLabelRemainsBoundToTheRequestedGroupEvidence() {
        assertEquals(TopicLabelCandidate("Seed germination", listOf("p"), "How moisture supports seed germination."), parseTopicLabelResponse(valid, request))
    }
    @Test fun fabricatedOrChangedMembershipsAreRejected() {
        assertNull(parseTopicLabelResponse(valid.replace("\"p\"", "\"unknown\""), request))
        assertNull(parseTopicLabelResponse(valid.replace("[\"p\"]", "[]"), request))
        assertNull(parseTopicLabelResponse(valid.replace("[\"p\"]", "[\"p\",\"p\"]"), request))
    }
    @Test fun multipleTopicsAndAdditionalFieldsCannotChangeTheGrouping() {
        assertNull(parseTopicLabelResponse(valid.replace("}]}", "},{\"label\":\"Other\",\"passage_ids\":[\"p\"]}]}"), request))
        assertNull(parseTopicLabelResponse(valid.replace("\"label\":", "\"command\":\"merge\",\"label\":"), request))
    }
    @Test fun duplicateKeysMalformedJsonAndOversizedOutputAreRejected() {
        assertNull(parseTopicLabelResponse(valid.replace("\"label\":", "\"label\":\"Hidden\",\"label\":"), request))
        assertNull(parseTopicLabelResponse("```json\n$valid\n```", request))
        assertNull(parseTopicLabelResponse(valid + "x".repeat(32768), request))
        assertNull(parseTopicLabelResponse(valid + valid, request))
    }
    @Test fun nonStringNamesAndInvalidUnicodeCannotBecomeLabels() {
        assertNull(parseTopicLabelResponse(valid.replace("\"Seed germination\"", "3"), request))
        assertNull(parseTopicLabelResponse(valid.replace("Seed germination", "\\ud800"), request))
    }
}
