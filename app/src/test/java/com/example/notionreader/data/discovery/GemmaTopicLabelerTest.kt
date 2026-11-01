package com.example.notionreader.data.discovery

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GemmaTopicLabelerTest {
    @Test fun supportVerificationUsesItsOwnSchemaAndKeepsUncertaintyDistinct() = runTest {
        val engine = Engine().apply { output = """{"decision":"UNCERTAIN"}""" }
        val input = TopicSupportRequest("topic", "concept", "Seed germination", "Conditions for seed germination.",
            request.evidence.single(), topicSupportIdentity(identity), 1)
        val runtime = GemmaTopicLabeler(identity, { true }, { engine }, { true }, StandardTestDispatcher(testScheduler))
        assertEquals(TopicSupportRun.Completed(SupportDecision.UNCERTAIN), runtime.verifySupport(input))
        assertEquals(TopicSupportSchema, engine.lastSchema)
        assertTrue(engine.lastPrompt.contains("Seed germination"))
        runtime.close()
    }

    @Test fun malformedSupportOutputCannotBecomeAValidDecision() = runTest {
        val engine = Engine().apply { output = """{"decision":"SUPPORTED","extra":true}""" }
        val input = TopicSupportRequest("topic", "concept", "Seed germination", "Conditions for seed germination.",
            request.evidence.single(), topicSupportIdentity(identity), 1)
        val runtime = GemmaTopicLabeler(identity, { true }, { engine }, { true }, StandardTestDispatcher(testScheduler))
        assertEquals(TopicSupportRun.Failed(TopicLabelRuntimeFailure.InvalidOutput), runtime.verifySupport(input))
    }
    @Test fun proposalUsesCompactReferencesAndPreservesSelectedCanonicalEvidence() = runTest {
        val engine = Engine().apply { output = """{"result":"concept","name":"Seed germination","definition":"How moisture helps seeds germinate.","listening_description":"Listen to seed germination.","passage_ids":["p1"]}""" }
        val first = semanticHash("first passage"); val second = semanticHash("second passage")
        val input = DiscoveryProposalRequest("proposal", 1, listOf(request.evidence.single().copy(passageId = first),
            request.evidence.single().copy(passageId = second, pageId = "other")), discoveryProposalIdentity(identity))
        val runtime = GemmaTopicLabeler(identity, { true }, { engine }, { true }, StandardTestDispatcher(testScheduler))
        val result = runtime.propose(input) as DiscoveryProposalRun.Completed
        assertEquals(listOf(second), (result.response as DiscoveryProposalResponse.Concept).evidenceIds)
        assertFalse(engine.lastPrompt.contains(first)); assertFalse(engine.lastPrompt.contains(second))
        assertEquals(DiscoveryProposalSchema, engine.lastSchema)
        runtime.close()
    }

    @Test fun cancellingAProposalWaitsForNativeTerminationBeforeClosing() = runTest {
        val engine = Engine().apply { automatic = false }
        val input = DiscoveryProposalRequest("proposal", 1, request.evidence, discoveryProposalIdentity(identity))
        val runtime = GemmaTopicLabeler(identity, { true }, { engine }, { true }, StandardTestDispatcher(testScheduler))
        val operation = launch { runtime.propose(input) }; runCurrent()
        operation.cancel(); runCurrent()
        assertTrue(engine.cancels > 0)
        assertFalse(operation.isCompleted); assertEquals(0, engine.sessionCloses)
        engine.terminal!!(CancellationException("Native terminal")); advanceUntilIdle()
        assertTrue(operation.isCompleted); assertEquals(1, engine.sessionCloses); assertEquals(1, engine.closes)
    }

    @Test fun nativeAbstentionIsSuccessAndDoesNotReleaseTheEngine() = runTest {
        val engine = Engine().apply { output = """{"result":"abstain","reason":"MIXED_EVIDENCE"}""" }
        val input = DiscoveryProposalRequest("proposal", 1, request.evidence, discoveryProposalIdentity(identity))
        val runtime = GemmaTopicLabeler(identity, { true }, { engine }, { true }, StandardTestDispatcher(testScheduler))
        assertEquals(DiscoveryProposalRun.Completed(DiscoveryProposalResponse.Abstained(DiscoveryAbstentionReason.MIXED_EVIDENCE)), runtime.propose(input))
        assertEquals(0, engine.closes)
        runtime.close()
    }
    @Test fun wireReferencesAreCompactAndResultsRetainTheCanonicalSourceIds() = runTest {
        val canonical = semanticHash("a long canonical passage identity")
        val input = request.copy(evidence = request.evidence.map { it.copy(passageId = canonical) })
        val engine = Engine().apply { output = """{"topics":[{"label":"Seed germination","definition":"How moisture supports seed germination.","passage_ids":["p0"]}]}""" }
        val runtime = GemmaTopicLabeler(identity, { true }, { engine }, { true }, StandardTestDispatcher(testScheduler))
        val result = runtime.label(input)
        assertFalse(engine.lastPrompt.contains(canonical))
        assertTrue(engine.lastPrompt.contains("\"id\":\"p0\""))
        assertTrue(result is TopicLabelRun.Completed)
        assertEquals(listOf(canonical), (result as TopicLabelRun.Completed).candidate.evidenceIds)
        runtime.close()
    }
    @Test fun wireNamesCannotBeConfusedWithEqualCanonicalIdsInOtherPositions() = runTest {
        val input = request.copy(evidence = listOf(request.evidence.single().copy(passageId = "p1"),
            request.evidence.single().copy(passageId = "p0", pageId = "other")))
        val engine = Engine().apply { output = """{"topics":[{"label":"Seed germination","definition":"How moisture supports seed germination.","passage_ids":["p0","p1"]}]}""" }
        val runtime = GemmaTopicLabeler(identity, { true }, { engine }, { true }, StandardTestDispatcher(testScheduler))
        val result = runtime.label(input) as TopicLabelRun.Completed
        assertEquals(listOf("p1", "p0"), result.candidate.evidenceIds)
        assertTrue(validateTopicLabel(input, result.candidate.text, result.candidate.evidenceIds, input.groupFingerprint,
            input.sourceEpoch, result.candidate.definition) is TopicLabelValidation.Accepted)
        runtime.close()
    }
    private val identity = TopicLabelIdentity("a", "t", "c", "p", "s", "v", "GPU")
    private val request = TopicLabelRequest("topic", "f", 1,
        listOf(TopicLabelEvidence("p", "page", "r", "Seed germination requires moisture.")), identity)
    private class Engine : NativeTopicLabelEngine {
        var lastPrompt = ""; var lastSchema = ""
        var opened = 0; var closes = 0; var sessionCloses = 0; var cancels = 0; var started = false
        var tokens = 20; var automatic = true; var failStart = false; var failCancel = false
        var output = """{"topics":[{"label":"Seed germination","definition":"How moisture supports seed germination.","passage_ids":["p0"]}]}"""
        var terminal: ((Throwable?) -> Unit)? = null
        var running = false
        override fun conversation(): NativeTopicLabelSession {
            check(closes == 0 && !running); opened++
            return object : NativeTopicLabelSession {
                override fun countPromptTokens(prompt: String): Int { lastPrompt = prompt; return tokens }
                override fun start(prompt: String, chunk: (String) -> Unit, done: (Throwable?) -> Unit) {
                    if (failStart) error("Native start failed")
                    started = true; running = true
                    terminal = { failure -> running = false; done(failure) }
                    chunk(output)
                    if (automatic) terminal!!(null)
                }
                override fun start(prompt: String, schema: String, chunk: (String) -> Unit, done: (Throwable?) -> Unit) {
                    lastSchema = schema
                    start(prompt, chunk, done)
                }
                override fun cancel() { cancels++; if (failCancel) error("Cancel driver failed") }
                override fun close() { check(!running) { "Destructive close while native work is running" }; sessionCloses++ }
            }
        }
        override fun close() { check(!running); closes++ }
    }
    @Test fun missingModelDoesNotOpenNativeResources() = runTest {
        val runtime = GemmaTopicLabeler(identity, { false }, { error("Must not open") }, { true }, StandardTestDispatcher(testScheduler))
        assertEquals(TopicLabelRun.Unavailable, runtime.label(request))
    }
    @Test fun validNativeResponseBecomesACandidateAndTheEngineIsReusedUntilClose() = runTest {
        val engine = Engine(); var loads = 0
        val runtime = GemmaTopicLabeler(identity, { true }, { loads++; engine }, { true }, StandardTestDispatcher(testScheduler))
        repeat(2) { assertTrue(runtime.label(request) is TopicLabelRun.Completed) }
        assertEquals(1, loads); assertEquals(2, engine.sessionCloses); assertEquals(0, engine.closes)
        runtime.close(); assertEquals(1, engine.closes)
    }
    @Test fun malformedNativeOutputIsAnExplicitFailure() = runTest {
        val engine = Engine().apply { output = "not JSON" }
        val runtime = GemmaTopicLabeler(identity, { true }, { engine }, { true }, StandardTestDispatcher(testScheduler))
        assertEquals(TopicLabelRun.Failed(TopicLabelRuntimeFailure.InvalidOutput), runtime.label(request))
        assertEquals(1, engine.sessionCloses)
    }
    @Test fun backendFailureClosesTheEngineWithoutRepeatedRetries() = runTest {
        val engine = Engine().apply { failStart = true }; var loads = 0
        val runtime = GemmaTopicLabeler(identity, { true }, { loads++; engine }, { true }, StandardTestDispatcher(testScheduler))
        assertEquals(TopicLabelRun.Failed(TopicLabelRuntimeFailure.BackendFailure), runtime.label(request))
        assertEquals(1, loads); assertEquals(1, engine.closes)
    }
    @Test fun devicePressureDefersGenerationBeforeOpeningTheModel() = runTest {
        val runtime = GemmaTopicLabeler(identity, { true }, { error("Must not open") }, { false }, StandardTestDispatcher(testScheduler))
        assertEquals(TopicLabelRun.Failed(TopicLabelRuntimeFailure.ResourcePressure), runtime.label(request))
    }
    @Test fun cancellationAndCloseWaitForNativeTerminalBeforeDestructiveRelease() = runTest {
        val engine = Engine().apply { automatic = false }
        val runtime = GemmaTopicLabeler(identity, { true }, { engine }, { true }, StandardTestDispatcher(testScheduler))
        val operation = launch { runtime.label(request) }; runCurrent()
        assertTrue(engine.started)
        operation.cancel(); runCurrent()
        assertTrue(engine.cancels > 0)
        assertFalse(operation.isCompleted); assertEquals(0, engine.sessionCloses)
        val release = launch { runtime.close() }; runCurrent()
        assertFalse(release.isCompleted); assertEquals(0, engine.closes)
        engine.terminal!!(CancellationException("Native terminal")); advanceUntilIdle()
        assertTrue(operation.isCompleted && release.isCompleted)
        assertEquals(1, engine.sessionCloses); assertEquals(1, engine.closes)
    }
    @Test fun anOversizedPromptNeverStartsNativeGeneration() = runTest {
        val engine = Engine().apply { tokens = 2048 }
        val runtime = GemmaTopicLabeler(identity, { true }, { engine }, { true }, StandardTestDispatcher(testScheduler))
        assertEquals(TopicLabelRun.Failed(TopicLabelRuntimeFailure.InputLimit), runtime.label(request))
        assertFalse(engine.started); assertEquals(1, engine.sessionCloses)
    }
    @Test fun aFailedCancelRequestStillWaitsForNativeTermination() = runTest {
        val engine = Engine().apply { automatic = false; failCancel = true }
        val runtime = GemmaTopicLabeler(identity, { true }, { engine }, { true }, StandardTestDispatcher(testScheduler))
        val operation = launch { runCatching { runtime.label(request) } }; runCurrent()
        operation.cancel(); runCurrent()
        val finishedBeforeTerminal = operation.isCompleted
        engine.terminal!!(CancellationException("Native finished")); advanceUntilIdle()
        assertFalse("Cancellation failure must not permit destructive early release", finishedBeforeTerminal)
        assertEquals(1, engine.sessionCloses)
    }

    @Test fun oversizedNativeOutputRequestsCancellationAndWaitsForTerminal() = runTest {
        val engine = Engine().apply { output = "x".repeat(32769); automatic = false }
        val runtime = GemmaTopicLabeler(identity, { true }, { engine }, { true }, StandardTestDispatcher(testScheduler))
        val operation = async { runtime.label(request) }; runCurrent()
        val cancellationRequests = engine.cancels
        val closedBeforeTerminal = engine.sessionCloses
        engine.terminal!!(CancellationException("Native stopped")); advanceUntilIdle()
        assertEquals(1, cancellationRequests)
        assertEquals(0, closedBeforeTerminal)
        assertEquals(TopicLabelRun.Failed(TopicLabelRuntimeFailure.InvalidOutput), operation.await())
    }
    @Test fun closingTheRuntimeCannotPublishALateNormallyCompletedLabel() = runTest {
        val engine = Engine().apply { automatic = false }
        val runtime = GemmaTopicLabeler(identity, { true }, { engine }, { true }, StandardTestDispatcher(testScheduler))
        val operation = async { runtime.label(request) }; runCurrent()
        val release = launch { runtime.close() }; runCurrent()
        engine.terminal!!(null); advanceUntilIdle()
        assertEquals(TopicLabelRun.Cancelled, operation.await())
        assertTrue(release.isCompleted); assertEquals(1, engine.closes)
    }

}
