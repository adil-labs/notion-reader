# Phase 1 structural knowledge review follow-up

This report supersedes the initial landing's verification paragraph. The reviewed
main baseline is `9677cb7`; the original before-implementation baseline is
`374f70a`. See the [before/after audit](phase1-structural-audit.md),
[records, sync and queries](phase1-structural-implementation.md), and
[official API evidence](phase1-api-evidence.md). Governing Notion mirrors and the
captured reader fixture were not hand-edited.

## Review findings and resulting behavior

| Finding | Correction | Regression evidence |
| --- | --- | --- |
| Related withdrawal invalidated its source ticket | One mutex admits the original ticket, withdraws targets and renews only that observation | `StructuralKnowledgeCoordinatorTest.oneObservationWithdrawsTargetsAndCommitsItsSourceWithoutCollectingAgain`; one collection, source stored, no retry |
| External clear could be confused with self-renewal | Original ticket check plus atomic admission; cache clear wins even during write gate suspension | `StructuralObservationAdmissionTest` external-clear tests |
| Missing target could broadly invalidate cache | Distinct permission, ambiguous 404, trash and transient states; only previously known cached/readable targets publish cache withdrawal | `StructuralRecoveryIntegrationTest` never-accessible404, forbidden/missing/trash and transient tests; unrelated Notes retained |
| Partial work needed integrated recovery proof | Persist first partial, restore store/coordinator, retry complete traversal and clear pending status; retain earlier valid snapshots | `StructuralRecoveryIntegrationTest.firstPartialNestedContentRestoresThenACompleteRetryClearsThePendingStatus`; store partial-update tests |
| Oversize could retry forever | Durable `LIMIT_EXCEEDED` revision block; changed revision/manual request can retry | `anOversizedPageBlocksAutomaticRetriesUntilItsRevisionChanges`; extractor depth/block bounds |
| Related header could requeue blocked work or fail its source | Preserve same-revision target block; source writes first; optional target quota/intrinsic limits are isolated | Admission tests `anUnchangedReferencedTargetKeepsItsIntrinsicLimitBlock`, `anOversizedRelatedHeaderCannotBlockItsValidSource` |
| Pending-work-only authorization cleanup | Source-scoped 401 invalidation also works while idle; stale identity/epoch cannot clear replacement source | `StructuralSourceLifecycleTest`; root Note/Browse/resource failure wiring |
| Eligibility could change during an HTTP call | Monitor and cancel scheduled requests while preserving durable work; caller cancellation propagates | `StructuralWorkSchedulerTest` added in-flight playback/cache/cancellation regressions |
| Resource pagination restarted/starved | Stable native source rotation plus process-local cursors; retain admitted slots until completion, release absent-source cursors without deleting facts | `NotionStructuralDiscoveryTest`: pagination, rotation/recreation, epoch reset, repeated cursor, 201-source, inventory replacement and denied-query slot release |

## Files changed in corrective review

- `domain/structure/StructuralModels.kt`, `StructuralKnowledgeStore.kt`: terminal
  limit status, atomic observation admission, blocked revision and resource cursor ports.
- `data/structure/FileStructuralKnowledgeStore.kt`, `StructuralExtractor.kt`,
  `NotionStructuralCollector.kt`: atomic epoch admission, isolated related headers,
  precise access state precedence, partial/limit persistence.
- `application/StructuralKnowledgeCoordinator.kt`, `StructuralWorkScheduler.kt`,
  root `ReaderApplication.kt`, `data/notion/NotionRepository.kt`: lifecycle cleanup,
  source authorization, request cancellation and resource discovery integration.
- `data/structure/NotionStructuralDiscovery.kt`: bounded native source rotation,
  ephemeral pagination and fair continuation admission/release.
- Corresponding coordinator/scheduler/extractor tests, new recovery/admission/
  discovery/source-lifecycle suites, `tools/proof-structural-observation.ps1` and
  the Player position parser in `tools/probe-config-change.ps1`.
- README, documentation map, actual before/after evidence, implementation notes,
  decisions §134–135 and Antigravity workflow lessons.

## Coverage of the requested test categories

All paths below are under `app/src/test/java/com/example/notionreader/` unless
stated otherwise. These are synthetic contract tests, not an exhaustive workspace
capture or representative Android performance evaluation.

| Requested property | Applicable tests |
| --- | --- |
| Typed page metadata and parents | `domain/structure/StructuralModelsTest`, `data/notion/NotionStructuralApiTest` |
| Database/data-source discovery and ownership | `data/structure/NotionStructuralDiscoveryTest`, `StructuralContainerStoreTest` |
| Nested pagination and order | `data/notion/KtorNotionApiTest`, `data/structure/StructuralExtractorTest` |
| Bounded recursion and partial traversal | `StructuralCollectionBoundariesTest`, `StructuralRecoveryIntegrationTest` |
| Page/block containment | `StructuralQueryIntegrationTest`, `StructuralCollectionBoundariesTest` |
| Headings/section paths and anchors | `StructuralExtractorTest`, `StructuralQueryIntegrationTest` |
| Relations with paginated property values | `data/notion/NotionPropertyPaginationTest`, `data/structure/NotionStructuralCollectorTest` |
| Mentions and explicit internal/external links | `StructuralExtractorTest`, `NotionUrlClassifierTest` |
| Synced references versus original children | `StructuralCollectionBoundariesTest`, existing reader fixture tests |
| Provenance and persisted records | `StructuralModelsTest`, `FileStructuralKnowledgeStoreTest`, query integration |
| Incremental equality/revision reuse | Store and coordinator tests; unchanged source facts preserve payload files |
| Rename/move/reorder | Store/query/extractor tests preserve canonical IDs and suppress obsolete containment |
| Permissions, 404, trash and transient failures | Collector and `StructuralRecoveryIntegrationTest` |
| Cache/source changes and stale writers | Store eligibility/epoch tests, admission races, source lifecycle tests |
| Atomic persistence/recreation and schema rejection | `FileStructuralKnowledgeStoreTest`; instrumented isolation test |
| Query coverage and unresolved targets | `StructuralQueryIntegrationTest`, store/container tests |
| Cancellation/resumable bounded work | Scheduler, coordinator, discovery and recovery tests |
| Existing reader/Discovery/user-state isolation | Full JVM suite; `androidTest/.../ui/StructuralPipelineIntegrationTest`; targeted player/device checks below |

## Failing-first evidence

| Guard disabled / old behavior | Observed intended failure |
| --- | --- |
| Store source/minimum-epoch eligibility | Exactly three store assertion failures; restored 32/32 |
| Store exact write epoch | Exactly one stale-write assertion failure; restored |
| Observation renews old epoch | One-collection source admission assertion fails; restored |
| Original observation epoch check removed | Stale observation after clear assertion fails; restored |
| Reader isolation guard removed | Instrumented assertion detects structure blocking reader; restored result below |
| Same-revision header, oversized related header, evicted continuation | Three new regression tests fail under old behavior; corrected fixtures model UNKNOWN headers and four fair windows |
| Disappeared sources retain all slots | Exactly the new inventory-replacement assertion fails before cursor pruning |
| Denied resource queries retain all slots | Exactly the denied-query slot regression fails before releasing its ephemeral cursor |

Reproducible shipping-guard mutation harnesses are tracked in
`tools/proof-structural-store.ps1`, `tools/proof-structural-observation.ps1` and
`tools/proof-structural-isolation.ps1`. Each restores source in `finally` and
checks fresh reports, counts and assertion failure type. Raw local logs are
under ignored `build/agy-tasks/structural-phase1/` and proof-specific build folders.

## Final verification

| Command | Observed result |
| --- | --- |
| `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin` | Exit 0; 1,378 JVM tests, zero failures/errors/skips; APK and instrumented Kotlin compile pass. Existing Compose test API deprecation warning remains. |
| `pwsh -NoProfile -File tools/proof-structural-store.ps1` | Three intended eligibility assertion failures, one intended epoch assertion failure, restored 32/32 pass. |
| `pwsh -NoProfile -File tools/proof-structural-observation.ps1` | Two single-test assertion failures with guards removed; both single-test restored checks pass. |
| `pwsh -NoProfile -File tools/proof-structural-isolation.ps1` | One expected device assertion failure; restored wrapper 1/1 pass, exit 0. |
| `pwsh -NoProfile -File tools/run-accessibility-tests.ps1 -Class com.example.notionreader.ui.PlayerScreenAccessibilityTest` | Wrapper PASSED, 18/18, zero failures/errors/skips. |

Device: `emulator-5554`, API 36 / Android 16. These targeted runs establish
synthetic pipeline isolation and player semantics, not spoken TalkBack quality.
The first configuration probe was obstructed by the notification permission
dialog. After granting the test emulator's notification permission, the probe
observed preserved queue/playback/section controls but exposed its obsolete
Paragraph-only position parser. It now accepts the actual Part label as well as
the legacy label, preserving the ordinal comparison rather than weakening it.
The corrected `pwsh -NoProfile -File tools/probe-config-change.ps1` exits 0:
all five checks pass (Player retained, same Active Queue, non-reset part ordinal,
Playing state, responsive live section navigation). Font scale is restored.
This is one emulator lifecycle observation, not repeated reliability or human
speech acceptance.

## Boundaries and remaining risks

The API cannot prove exhaustive workspace visibility or deletion from search
absence/404. Depth10, 5,000 examined blocks, 25 target metadata requests, 500 API
pagination requests, 200 resource sources/continuations per window, 20,000 member
observations per window, per-record/store byte limits and durable pending limits
are explicit stopping points. A bounded window is not a complete inventory.
Opaque cursors disappear on process death; stable source rotation and durable
page work survive, so a partially traversed resource may restart its API pages.
Retained page-ID/cursor histories are bounded by source/request caps but can
still consume substantial process memory; no representative allocation/scale
acceptance is claimed. Query indexes are disposable and bounded by store limits;
large-workspace query latency has not been measured.

Related positive metadata is best effort when cache quota is exhausted; source
traversal completeness does not mean every target payload is locally present.
No semantic catalog replacement, AI processing, structural UI or Notion write
operation was added. Existing reader traversal, playback commands and user-owned
state retain their owning contracts. Human TalkBack speech/hands-free acceptance,
S25 resource measurements, battery behavior and production-scale acceptance
remain open. Existing Discovery acceptance work in decisions §132 remains open.
