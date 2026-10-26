# Cluster-seeded topic retrieval implementation

Approved design: `docs/discovery-v2/cluster-seeded-topic-retrieval-design.md`, user instruction "implement the new design". Governing Product/UI/Integration Notion amendment: Sole Discovery pipeline: cluster seeds, generated concepts and retrieved Notes. Export and inspect mirrors before production edits. Continue in the current branch/workspace with all existing dirty work preserved; do not restart prior implemented native/runtime/model-management work.

Goal: existing grouping discovers seeds; a bounded local response supplies a name and definition; compatible embeddings retrieve current matching Notes across the library. Seed members are never mandatory matches. One catalog/coordinator/gate, original names evidence-only, unmatched Notes allowed. Production activation remains gated by fresh semantic and real resource evidence.

## Task 1: Source-supported concept schema and compatibility

Files: `domain/discovery/TopicLabeling.kt`, `data/discovery/TopicLabelResponse.kt`, `GemmaTopicLabeler.kt`, `GenerativeModelPack.kt`, `data/local/FileTopicLabelStore.kt`; their JUnit tests.

Preserve bounded closed parsing and compact request-local wire references. Extend generated metadata/candidates with a bounded definition and complete versioned identity. Old stored name-only metadata must not become a current retrieval concept. Full source fingerprint and commit epoch remain authoritative. Define one query constructor using name plus definition; aliases never affect it.

Write failing-first definition/query/schema/invalid-Unicode/bounds and old-record compatibility tests; confirm the intended failures, implement and run the full JVM suite. Update native output fixtures to the new actual schema without weakening citation/extra-field/size guards. Actual native model behavior must be measured rather than inferred from fake responses.

## Task 2: Current matching generations and durable storage

Add `domain/discovery/TopicRetrieval.kt` and `data/local/FileTopicRetrievalStore.kt`, with explicit identity, source revisions, completion/progress and storage-result contracts. Extend canonical topic overlays rather than changing seed `members`, fingerprints or grouping continuity. Query identity binds concept, embedding identity and versioned inclusion policy. Persist query vectors separately from generation evidence; keep actual supporting passages/source references and storage inventory bounded.

Property tests must demonstrate exclusion of seed Notes, inclusion of an outside Note, overlapping/empty results, genuine one/two-Note status, complete all-source coverage beyond a UI page, unchanged reuse, incremental source changes, reset/restart/stale publication, admission/I/O failure and corruption. No automatic result padding or hard global Note/topic cap. Calibrated policy is provisional until fresh acceptance; the seen0.85 result is not an accepted production threshold.

## Task 3: Extend the process-owned work loop

Files: new `application/TopicRetrievalCoordinator.kt`, `TopicCoordinator.kt`, `DiscoveryController.kt`, `DiscoveryRuntime.kt`, `DiscoveryState.kt`, `ReaderApplication.kt` and application integration tests.

Finish bounded concept generation, release its engine, then embed/query pending concepts through the shared inference/bulk gate. Scan current eligible indexed records with durable fair progress and atomic source/generation tickets. New source records can update retrieval without regenerating unaffected concepts. Restore compatible results on process restart; withdraw obsolete evidence before accepting later writes. Include sidecar/query storage in reset and inventory. No UI coroutine or second worker owns native work.

Failing-first tests cover missing integration, source/model/reset during suspended work, search/playback interruption, engine exclusivity, reuse without extra native calls, no stale cache commits and failure status. Run the full JVM suite plus build/androidTest compilation.

## Task 4: Replace presented memberships in place

Files: `ui/DiscoveryPresentation.kt`, topic cards/list/details/followed views and previews, relevant controller projection/preview paths, JVM presentation and targeted accessibility tests.

Display current aliases/name/definition and matching Notes from source-current retrieval generations; never use seed members as a fallback match list. Distinguish seed/naming evidence from matching evidence. Preserve neutral pending/unavailable/failed and explicit no-current-matches states, Refresh ordering/focus, IDs and follow/alias ownership. Old fixed-group data migrates as seeds; compatible source embeddings and all user listening/queue data survive.

Prove failing-first seed leakage and stale/empty/migration/follow/alias properties. Use the repository accessibility wrapper for affected real semantics/navigation classes and a real-root device probe. Do not claim human speech observation from semantics tests.

## Task 5: Native and semantic/resource acceptance

Extend the isolated shipping-source probe to use the actual new schema/coordinator/stores/UI bindings. Preserve exact source/APK/prompt/schema/input identities and failure outputs. Use seen cases for diagnosis only; freeze new source families and policy before final semantic evaluation. Count failed/unnamed/empty queries and missed subjects; agent authorship/review is explicit. Keep independent human release/TalkBack gates separate.

Run representative S25/slower-device10k-passage/>2k-uneven-section active-time, actual Java/native peak and whole-process pressure/Android17 exit, cancellation/resume, foreground search/playback and lifecycle/storage/model tests. Do not substitute small native smoke or sampled PSS for those gates. Use `verify-notion-reader`, record applicable verification and unresolved failures in decisions/ledger/README. Enable the sole production path only when applicable acceptance is actually proven.

## Interface review and progress

Task1 concept identity feeds Task2 query/retrieval identity: old name-only records must invalidate through new generator/schema identity, not source re-embedding. Task2 overlay feeds Task3 restore/work and Task4 presentation: keep seed evidence separate from matching Notes and require complete current generations. Task3 source/reset validity feeds every later storage/UI boundary. Task5 observes the actual outputs of all four tasks, not a retyped algorithm.

Prior native labeler/acquisition/shared scheduler and fixed-group integration remain implemented prerequisites, not tasks to repeat. Record new rulings and exact red/green checks in `docs/discovery-v2/execution-ledger.md`; no task is complete from intent or code alone.
