package com.example.notionreader.platform

import org.junit.Assert.*
import org.junit.Test

class SpeechRequestCallbacksTest {
    @Test fun rejectedNativeSubmissionNeverBecomesSuccessfulCompletion() = assertFailedSubmission { false }
    @Test fun throwingNativeSubmissionNeverBecomesSuccessfulCompletion() = assertFailedSubmission {
        throw IllegalStateException("Synthetic native failure")
    }
    private fun assertFailedSubmission(action: () -> Boolean) {
        var failures = 0
        var completions = 0
        val callbacks = SpeechRequestCallbacks { failures++ }
        callbacks.enqueue("request", { completions++ }, action)
        assertEquals("Submission must report failure", 1, failures)
        assertEquals(0, completions)
        assertFalse(callbacks.complete("request"))
    }
    @Test fun aFailedRequestReportsOnceWithoutCompletingOrSkipping() {
        var failures = 0
        var completions = 0
        val callbacks = SpeechRequestCallbacks { failures++ }
        callbacks.submit("first") { completions++ }
        callbacks.fail("first")
        callbacks.fail("first")
        callbacks.complete("first")
        assertEquals(1, failures)
        assertEquals(0, completions)
    }

    @Test fun cancelledAndSupersededRequestsCannotReportFailureOrCompletion() {
        var failures = 0
        var completions = 0
        val callbacks = SpeechRequestCallbacks { failures++ }
        callbacks.submit("old") { completions++ }
        callbacks.submit("current") { completions++ }
        callbacks.fail("old")
        callbacks.complete("old")
        assertTrue(callbacks.complete("current"))
        callbacks.submit("cancelled") { completions++ }
        callbacks.cancel()
        callbacks.fail("cancelled")
        callbacks.complete("cancelled")
        assertEquals(0, failures)
        assertEquals(1, completions)
    }

    @Test fun completionCanStartTheNextRequestWithoutBeingClearedAfterwards() {
        var completions = 0
        val callbacks = SpeechRequestCallbacks { fail("No request failed") }
        callbacks.submit("first") { callbacks.submit("second") { completions++ } }
        callbacks.complete("first")
        assertTrue(callbacks.complete("second"))
        assertEquals(1, completions)
    }

    @Test fun aFailedAnnouncementAlsoReportsWithoutPretendingToFinish() {
        var failures = 0
        val callbacks = SpeechRequestCallbacks { failures++ }
        callbacks.submit("announcement", null)
        callbacks.fail("announcement")
        assertEquals(1, failures)
        assertFalse(callbacks.complete("announcement"))
    }
}
