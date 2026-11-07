# Discovery inference implementation plan

**Goal:** Publish independently supported topics early, reuse current durable evidence, and measure incremental discovery at 20,000 passages and on the S25.

**Architecture:** Refactor the existing process-owned coordinator, proposal/support stores and canonical membership scheduler. Keep retrieval separate from independent verification and keep incomplete coverage explicit.

**Tech stack:** Kotlin, Compose, JUnit 4, file-backed progress, LiteRT-LM.

**Spec:** User's pasted task and governing Product, Integration and UI contracts; decisions 239–240 record the starting state. Existing uncommitted changes are preserved.

## Implementation and acceptance

- [ ] Audit inference boundaries, durable identities, admission replay and scheduler transitions against current code. Record baseline failures before attributing them to this round.
- [ ] In `TopicSupportVerification.kt`, reuse compatible pending support after canonical admission, preserving canonical decision precedence and durable writes. Add failing-first file-backed tests in `TopicSupportVerificationTest.kt` for all decisions, restart, definition/model/source changes and refused persistence.
- [ ] In `EvidenceFirstScheduler.kt` and controller handoff, make admission progress deterministic across catalog changes and restart. Test eventual Idle, duplicate prevention and independent membership continuation.
- [ ] Verify early admission through the real controller path, including uncertain evidence and incomplete coverage. Preserve safe live replay reuse and durable revalidation after restart.
- [ ] Instrument actual embedding/proposal/support invocation boundaries and reuse paths with content-free, disabled-by-default metrics; include duration, failures, replay/admission, first publication and restarts.
- [ ] Add deterministic 2,000-Note/20,000-passage/35-concept tests for cold/warm work, partial invalidation, verifier changes, interruption, early admission and coverage. Report actual counts.
- [ ] Run full JVM suite, debug assembly and Android test compilation; run targeted device tests through repository tooling.
- [ ] Install and measure on the connected S25: fresh admission, citations, incremental membership, restart persistence, playback/resource constraints, latency distribution, cache hits and memory. Do not substitute emulator timing.
- [ ] Update architecture documentation, README and decisions with exact evidence and remaining acceptance gaps. Review changes and remove obsolete paths only after proving their replacements.

## Review focus

Source withdrawal during reads/writes must reject late results. Canonical rejection must supersede pending support. Failed persistence must not become completed work. Bounded retrieval must retain continuation and never imply complete coverage. Playback interruption and process restart must reuse only durable current evidence.

## Initial audit findings

`topicSupportWorkKey` already captures definition, exact body, source revision and full verifier identity, but includes topic ID. `verifyRetrievedPassage` currently reads only the requested ID. Hydration already checks canonical and pending IDs without inference. Admission has a process-local replay shortcut with citation revalidation; it is not a durable token. Existing early-publication tests include constructed canonical records, so controller-level early admission still needs independent proof.
