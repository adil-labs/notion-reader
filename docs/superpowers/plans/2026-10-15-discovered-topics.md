# Discovered Topics Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development task by task.

**Goal:** Deliver automatic overlapping topics, durable follows and individual
connections through an accessible Discover-to-listening flow.

**Architecture:** Pure bounded passage membership and identity reconciliation in
domain; atomic private persistence in data; process-scoped orchestration reuses
DiscoveryController's worker. UI consumes reader/application state only.

**Tech Stack:** Kotlin, coroutines, kotlinx serialization, Compose, existing ONNX
embeddings, JUnit4 handwritten fakes, repository device-test wrapper.

**Spec:** ../specs/2026-10-15-discovered-topics-design.md

## Global constraints

- API 26 remains supported; no new model or Python runtime.
- Automatic topics and explicit interests remain separate; membership overlaps.
- Cache-bound evidence; typed permanent invalidations also purge continuity links.
- 64 resident anchors, 64-passage checkpoints, three distinct Notes per visible topic.
- 64 MiB combined derived budget; 1 MiB continuity sub-budget.
- Source-backed labels and paired excerpts; no generated conceptual claims.
- Explicit Play alone changes playback; existing session history stays compatible.

## Review focus

- Two-topic Notes must retain distinct evidence in both topics.
- Temporary eviction must not become permanent identity loss.
- Follows survive topic disappearance, model/index clear and rename.
- Background updates cannot move inspected content/focus.
- Storage/cancellation/revocation races must not republish private source evidence.

## Task 1 — Domain and durable topic records

Files: domain/discovery/DiscoveredTopics.kt, data/local/FileTopicStore.kt and JVM tests.
Interface: TopicBuilder(previous, identity, policy).accept(record), finish();
TopicSnapshot/DiscoveredTopic/TopicMembership/TopicContinuity/NoteConnection;
TopicStore.read/write/clear/bytes. Records retain source provenance.

- [x] Write and observe failing overlapping membership, distinct counts, identity,
  literal labels, deletion/eviction, bounded anchors and atomic storage tests.
- [x] Implement bounded streaming anchored memberships and stable reconciliation.
- [x] Run targeted JVM tests, then request scoped review.

## Task 2 — Typed source invalidation

Files: domain/document/DocumentCache.kt, data/local/FileDocumentCache.kt,
data/document/CachedDocumentRepository.kt and their tests.
Interface: CacheRemovalReason plus retained StateFlow lifecycle invalidations;
remove(pageId, reason) and acknowledgement-safe generation observation.

- [x] Write failing eviction-versus-confirmed-access-loss tests, including an
  already-evicted Note; verify the failure, then propagate reasons from production.
- [x] Ensure corruption, clear, age/budget pruning and reset have correct reasons.
- [x] Run targeted JVM tests and inspect destructive fake fidelity.

## Task 3 — Application and real composition root

Files: application/DiscoveryController.kt, ReaderApplication.kt,
domain/discovery/SemanticIndex.kt and application tests.
Consume Task 1 records/ports and Task 2 invalidations.
Produce topics, newer-snapshot state, connections, durable follow operations and
evidence-safe snapshot refresh. Existing preview API remains reusable.

- [x] Fail first on durable follows, clear/reset, invalidation, stale commit,
  source eligibility and preview/playback isolation.
- [x] Reconcile topics via the same worker, reuse vectors, checkpoint/yield,
  prioritize queries, atomically publish revision-checked generations.
- [x] Build bounded round-robin individual passage pairs via exact retrieval.
- [x] Wire atomic stores and the total storage budget at the composition root.

## Task 4 — Accessible Discover exploration

Files: ui/DiscoveryContent.kt, DiscoveryScreen.kt, TopicScreens.kt, ReaderShell.kt,
HomeScreen.kt, ReaderDestination.kt and UI tests.
Consume application topic/connection state and explicit follow operations.

- [x] Add semantics tests first for grouped previews, member evidence, durable
  follow forms, named navigation/actions and listening-preview isolation.
- [x] Deliver compact search/filters, early Continue listening, automatic topic
  and pair previews, details, explicit Refresh, Add/Edit interest dialog.
- [x] Preserve stable ID focus and saveable scroll/snapshot navigation state.
- [x] Reuse guided preview and Saved Queue destination actions.

## Task 5 — Evaluation and end-to-end verification

- [ ] Add separate multi-label synthetic calibration/held-out corpus; report
  membership/pair precision, recall, source-label validity and identity churn.
- [x] Run full JVM suite, debug assembly and androidTest compilation.
- [ ] Run targeted repository-wrapper accessibility/navigation tests and a real
  application device probe; repeat racy checks rather than accepting one green run.
- [ ] Benchmark actual ARM64 topic/update cost, storage/memory and search contention;
  report unmeasured hardware/human gates explicitly.
- [ ] Review whole feature, fix findings, align README and append decisions evidence.

## Execution ledger

- Approved written design: user requested implement end to end.
- Existing codex/discovery-first checkout reused; prior semantic work preserved.
- No commits or external notifications requested; changes stay reviewable locally.
- Task ownership: domain/storage implementer; cache-lifecycle implementer;
  UI implementer; root application integration, evaluation and final verification.

- Tasks 1–4 implemented and reviewed. Verification evidence: decisions §§45–46.
- Latest full JVM: 738 tests, zero failures/errors. Debug assembly and androidTest compilation passed.
- Native synthetic topic quality passed; concurrent 10,000-passage warm-query performance remains failed (p95 3,244 ms). No hardware/human gate is implied by implementation checkmarks.
