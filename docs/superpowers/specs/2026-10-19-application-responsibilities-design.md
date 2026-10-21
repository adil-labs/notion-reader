# Application responsibility refactor

## Intent and authority

Implement the four refactors requested in this chat: separate Discovery responsibilities,
slim ReaderApp, clarify Discovery state, and separate topic destination screens.
Improve Kotlin dispatcher consistency and make invalidation ownership explicit.
Success means cohesive owners and testable boundaries with existing product behavior,
accessible reading order, focus restoration and process-owned playback preserved.

The seven governing specifications and AGENTS.md remain authoritative. This document
describes implementation structure, not new product semantics. Generated Notion mirrors
are not edited. Read decision rounds 98–100 and the current documentation entrypoint.

## Current evidence

DiscoveryController is 618 lines and combines cache/index reconciliation, source
eligibility, retrieval, inference serialization, interests, recommendations, previews,
publication and reset policy. An injected workDispatcher is bypassed by explicit
Dispatchers.Default in retrieval, seed-vector work and topic building. DiscoveryState
mixes independently changing concerns and represents published topics as Pair values.

ReaderApp is 1,402 lines. Its facade combines queue edits, history presentation,
navigation, settings, cache management and Discovery-to-listening actions. Its opening
documentation still describes an earlier implementation without queues or persistence.
TopicScreens combines previews, topic details, connection details, interest details,
relationship presentation and interest editing. Existing integration and accessibility
tests already describe the behavior these extractions must retain.

Round 100 demonstrated why cache revision alone cannot validate reads across derived
index reset. The new architecture must retain that independently tested reset boundary.

## Approach and alternatives

Use cohesive internal collaborators behind the existing public facades, migrating
callers only where named state improves clarity. Preserve orchestration explicitly.
This allows focused changes and existing facade integration tests to remain useful.

Moving functions into extension files alone would shorten files without clarifying
ownership. Replacing every facade with fine-grained use-case objects or adding Gradle
modules would introduce wider wiring changes without demonstrated benefit here.

## Discovery ownership

DiscoveryController remains the externally consumed facade and owner of published
application state. The existing process coordinator remains the only bulk execution
owner. No additional worker, coroutine scope, service or screen-owned processing loop
is introduced.

Extract a shared Discovery validity component that owns the reset epoch and memoized
source eligibility. A read token captures both reset epoch and cache revision; source
validation and final publication must reject changed tokens. Clearing derived data
advances the epoch before destructive work. Source cache changes invalidate eligibility.
This component is shared by all readers, not independently recreated by each helper.

Extract Discovery retrieval for streaming exact ranking, seed-vector aggregation and
catalog page/selected-entry access. It uses the common validity component, existing
domain ports and injected dispatcher. It does not mutate screen state or own inference.
Retain catalog availability, pagination totals, ordering, timing diagnostics and source
eligibility behavior, including reset rejection on suspended reads.

Extract indexing reconciliation, explicit-interest persistence/matching, and guided
preview editing as separate cohesive collaborators. Keep inference serialization,
foreground priority, commit serialization and reset sequencing owned by one shared
runtime, with explicit narrowly scoped dependencies rather than copies of locks or
generation counters. Extract incrementally; do not create an unrestricted context
object exposing every field to every collaborator.

No expensive work moves into Flow update lambdas, composition or the main dispatcher.
Respect cancellation propagation. Use the injected work dispatcher consistently;
do not silently introduce independent dispatchers inside helpers.

## Discovery state

Introduce PublishedDiscoveryTopic(topic, status) instead of Pair. Consumers use named
properties. Preserve prepared-publication reuse for unchanged inspected generations.

Group state by independently owned concern: search, interest matches, guided preview
and processing. Use sealed request states for mutually exclusive idle/loading/ready/
failed outcomes in search and interest matching. Preserve the distinction between a
successful empty result, unavailable model/source, policy deferral and failed work.
Background processing and foreground search may occur independently; never force
their states into one global loading enum. Keep request identity checks before all
success and failure publication, and preserve current visible messages and transitions.

Migrate production consumers and test fixtures together. Any temporary compatibility
accessors must be derived from the new state and removed before completion; do not
maintain two writable sources of truth.

## ReaderApp facade

Keep ReaderApp as the application entry point while extracting cohesive queue
management, listening-history presentation and Discovery listening actions. Queue
storage/edit state belongs to queue management; navigation and session handoff remain
explicit facade orchestration. History projection receives the live session snapshot
and persisted history without owning playback. Discovery listening actions prepare
frozen queue input without starting a new session except through explicit Play.

Preserve pending Add semantics, duplicate reports, Saved/Smart distinctions, history
ordering, session identities and reset sequencing. Do not move queue-advancement or
next-Note loading loops away from ReaderHost or change their process lifetime. Rewrite
stale ownership documentation to describe the actual implementation.

## Presentation boundaries

Separate topic details, connection details, interest details, common topic rows and
interest editing into focused files/components. The destination router selects a
screen; it does not implement all screens in one when block. Screen-specific state
stays with its screen, keyed by subject identity as today. Share prepared evidence
presentation through a focused boundary rather than repeating catalog retrieval.

Preserve all control labels, semantics, source-loss withdrawal, saved-catalog loading,
Back behavior, expanded evidence, dialog input retention and actual focus targets.
Keep topic publication off the main thread and inspection free of model inference.

## Verification and delivery

Use focused slices in order: shared validity/retrieval; remaining Discovery ownership
and state; ReaderApp collaborators; destination screens. Each slice must be independently
reviewable and verified before proceeding. Do not mark the full objective complete
after only the first extraction.

Retain existing contract tests. Add targeted properties only where boundaries introduce
new risk: shared reset/source token rejection, dispatcher use, superseded requests,
pending queue operations, and preserved navigation/dialog state. New regression guards
must fail first for the intended reason. Test fakes preserve destructive clearing.

Run :app:testDebugUnitTest for each implementation slice. Run :app:assembleDebug and
:app:compileDebugAndroidTestKotlin before completion. For screen changes, run affected
accessibility classes through tools/run-accessibility-tests.ps1 on the owned emulator
and use a relevant device navigation probe. Inspect wrapper completeness and verdict;
do not substitute a partial run. Human TalkBack speech acceptance remains unobserved
unless a person performs it.

Update README and append numbered decisions with extraction rationale, regression
evidence and applicable verification. New production, test and tooling files must
be tracked intentionally. Use focused prose commit subjects if committing the slices;
no push or merge is part of this refactor.

## Completion audit

- Retrieval, indexing, interests and preview handling have cohesive owners with one
  shared runtime/invalidation boundary and no duplicated worker or mutable state.
- ReaderApp delegates queue management, history projection and Discovery listening
  responsibilities while retaining explicit navigation/session orchestration.
- Published topics use a named type; related Discovery state is grouped and request
  outcomes cannot represent contradictory loading/success/failure combinations.
- Destination-specific composables replace the combined detail implementation.
- Injected dispatchers are respected throughout extracted application work.
- Governing behavior, architecture tests, reset regressions, builds and affected
  accessibility/navigation evidence pass; documentation reflects current ownership.

## Existing verification anchors

DiscoveryControllerTest already covers cancellation before dispatch, newest-request
ownership when dispatch order reverses, reconciliation concurrent with search and
preview edits, replaced-source withdrawal and reset during inference. Preserve these
tests through the extractions; they exercise public behavior across collaborators.
DiscoveryTopicsIntegrationTest covers reset during page/detail/preview retrieval,
pinned catalog access, automatic continuation, publication reuse and reset during
checkpoint writes. DiscoveryWorkCoordinatorTest and DiscoveryPlaybackIsolationTest
remain the guards for single-owner execution and playback separation.

ReaderAppAddToQueueTest, ReaderAppSavedQueuesTest, ReaderAppQueueEditsTest and
ReaderAppHistoryTest anchor the facade extraction. Retain their contract assertions
rather than rewriting them solely to call the new helpers. LayerRulesTest and
SingleDiscoveryEngineTest remain architecture checks. The destination split requires
the existing DiscoveryTopicsNavigationAccessibilityTest and other affected classes,
selected from current source rather than an assumed suite list.

These anchors do not yet prove the refactor: no implementation or post-refactor
verification has occurred. Dispatcher consistency needs specific new coverage because
existing pre-dispatch tests alone cannot detect nested hard-coded dispatchers.

The user instructed proceeding with implementation after this design was presented.
Execution follows the adjacent application-responsibilities implementation plan.
