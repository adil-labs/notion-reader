package com.example.notionreader.application

import org.junit.Assert.*
import org.junit.Test

class DiscoverySchedulingFailureTest {
    @Test fun coordinatorFailureWithTheSameTextIsNotTreatedAsASchedulingFailure() {
        val message = DiscoverySchedulingFailure.ProposalsIncomplete.userMessage()
        val original = DiscoveryState(topicFailure = message, topicFailureSource = TopicFailureSource.Coordinator)
        assertFalse(original.canRetryTopicScheduling())
        val changed = original.withSchedulingFailure(DiscoverySchedulingFailure.MembershipIncomplete)
        assertEquals(message, changed.processingFailure)
        assertEquals(message, changed.withoutSchedulingFailure().topicFailure)
        assertFalse(changed.canRetryTopicScheduling())
    }

    @Test fun schedulingOwnershipDoesNotDependOnPresentationText() {
        val state = DiscoveryState().withSchedulingFailure(DiscoverySchedulingFailure.ProposalsIncomplete)
            .copy(topicFailure = "A different presentation string")
        assertTrue(state.canRetryTopicScheduling())
        val cleared = state.withoutSchedulingFailure()
        assertNull(cleared.schedulingFailure)
        assertNull(cleared.topicFailure)
        assertNull(cleared.topicFailureSource)
    }
}
