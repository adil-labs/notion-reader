# Phase 1 structural knowledge audit

Baseline: `main` at `374f70a8c2f10bcbea3827b637b132729652fd5e`, fetched and
fast-forwarded before implementation on 2026-10-26. The governing contracts permit
additive structural data capabilities without changing reader, UI or Discovery
semantics. The unified knowledge design suite remains subordinate to them.

## Before implementation

Statuses: **Implemented and verified**, **Partially implemented**, **Missing**,
**Unsupported or limited by the Notion API**. Existing verification below refers
to checked-in tests and historical decision evidence (§132–133), not a claim that
the baseline command freshly executed tests: that command was `UP-TO-DATE`.

Paths below are beneath `app/src/main/java/com/example/notionreader/` for code and
`app/src/test/java/com/example/notionreader/` for JVM tests.

| Capability | Before | Existing code | Applicable tests | Official API evidence |
| --- | --- | --- | --- | --- |
| Page discovery | Partially implemented | `data/notion/NotionApi.kt`, `NotionRepository.kt`: paginated page search; Browse drops blank titles | `data/notion/KtorNotionApiTest.kt`, `NotionRepositoryTest.kt` | [Search](https://developers.notion.com/reference/post-search), [limitations](https://developers.notion.com/reference/search-optimizations-and-limitations) |
| Database/data-source discovery and metadata | Missing | Page-only search; no container collection or persistence | Existing API tests do not establish container collection | [Data source](https://developers.notion.com/reference/data-source), [query](https://developers.notion.com/reference/query-a-data-source) |
| Page metadata, parent and revision | Partially implemented | `data/notion/NotionModels.kt`: title, properties, edit time, trash; parent and creation time absent | `data/notion/NotionRepositoryTest.kt` | [Page](https://developers.notion.com/reference/page), [parent](https://developers.notion.com/reference/parent-object) |
| Nested blocks, pagination and reading order | Partially implemented for complete structure; implemented and verified for supported reader order | `data/content/NotionBlockNormalizer.kt`, `ReaderDocumentMapper.kt`: bounded recursion; child pages/databases/synced blocks deliberately excluded from playback | `data/content/ReaderFixtureContractTest.kt`, `ReaderIdentityStabilityTest.kt`, `data/notion/KtorNotionApiTest.kt` | [Children](https://developers.notion.com/reference/get-block-children), [blocks](https://developers.notion.com/reference/block) |
| Persistent page/block hierarchy | Missing | Normalized tree is transient; reader projection does not retain full containment | Existing fixture tests cover reader exclusion, not structural queries | [Parent](https://developers.notion.com/reference/parent-object) |
| Headings, sections and navigation anchors | Partially implemented | Reader sections and block-based chunk IDs exist; no separate persistent structural heading paths or block ranges | `data/content/ReaderIdentityStabilityTest.kt`, `ReaderFixtureContractTest.kt` | [Blocks](https://developers.notion.com/reference/block) |
| Explicit relations, mentions, links and synced references | Partially implemented | Rich-text hrefs retained as chunk metadata; typed relations/mentions/synced provenance absent | `data/content/ReaderFixtureContractTest.kt` | [Properties](https://developers.notion.com/reference/retrieve-a-page-property), [blocks](https://developers.notion.com/reference/block) |
| Document caching, refresh and invalidation | Implemented and verified for existing document behavior; missing for structural records | `data/document/CachedDocumentRepository.kt`, `data/local/FileDocumentCache.kt`, `application/AutoCacheReconciler.kt`; durable cache event journal, atomic files and storage admission | `data/document/CachedDocumentRepositoryTest.kt`, `data/local/FileDocumentCacheTest.kt`, `application/AutoCacheReconcilerTest.kt` | [Status codes](https://developers.notion.com/reference/status-codes), [versioning](https://developers.notion.com/reference/versioning) |
| Offline reading, saved playback position and semantic identity | Implemented and verified for existing reader scope | `domain/ReaderDocumentPosition.kt`, `domain/session/StoredSession.kt`, `domain/discovery/SemanticIndex.kt` | `domain/session/SessionPersistenceTest.kt`, `ResumePolicyTest.kt`, `data/content/ReaderIdentityStabilityTest.kt` | [Stable record identity](https://developers.notion.com/reference/versioning) |
| Exhaustive workspace enumeration or proof of deletion from search absence/404 | Unsupported or limited by the Notion API | Existing page search cannot establish exhaustive coverage; existing reader cache maps 404 to access invalidation, not reliable deletion evidence | Structural tests must keep factual deletion distinct from reader error category | [Search limitations](https://developers.notion.com/reference/search-optimizations-and-limitations), [status codes](https://developers.notion.com/reference/status-codes) |

## Design and review findings

Reuse the existing document/auto-cache pipeline, atomic file-store pattern,
storage budget and process-scoped lifetimes. Keep structural facts separate from
semantic records and independent user state. Do not change playback traversal to
collect separate child pages or duplicate synced content into audio.

The preliminary delegated audit overstated fresh baseline verification and
identifier normalization, and made an unsupported synced-block endpoint claim.
The primary review rejected those claims. The initial implementation also exposed
risks in source identity, metadata/edge equality, stale indexes after moves,
ambiguous 404 handling, URL classification and crash consistency. Corrections
require concrete regression evidence before acceptance.

## After implementation

Phase 1 native structural knowledge is implemented and verified. All 1,353 JVM tests pass, debug assembly and Android test compilation succeed, targeted instrumented accessibility/pipeline tests pass on device, and failing-first regression proofs verify that the storage eligibility and reader isolation guards detect regressions.

| Capability | After | Code & persistence | Applicable tests | Verification evidence |
| --- | --- | --- | --- | --- |
| Page discovery | Implemented and verified | `data/notion/NotionApi.kt`, `NotionRepository.kt`, `data/structure/NotionStructuralDiscovery.kt` | `data/notion/NotionPropertyPaginationTest.kt`, `NotionStructuralApiTest.kt` | 100-page paginated traversal, cursor validation, retry on failure |
| Database/data-source discovery and metadata | Implemented and verified | `data/structure/NotionStructuralDiscovery.kt`, `data/structure/FileStructuralKnowledgeStore.kt` | `data/structure/NotionStructuralDiscoveryTest.kt`, `FileStructuralKnowledgeStoreTest.kt` | Data source and database containers recorded with parent hierarchy |
| Page metadata, parent and revision | Implemented and verified | `data/notion/NotionModels.kt`, `domain/structure/StructuralDocument.kt`, `StructuralParent.kt` | `data/notion/NotionModelsTest.kt`, `domain/structure/StructuralHierarchyTest.kt` | Typed page/block/database/data-source/workspace parents; edit & creation timestamps |
| Nested blocks, pagination and reading order | Implemented and verified | `data/structure/NotionStructuralExtractor.kt`, `NotionBlockNormalizer.kt` | `data/structure/NotionStructuralExtractorTest.kt`, `data/content/ReaderFixtureContractTest.kt` | Bounded recursion (depth 10, max 5,000 blocks); sibling order preserved; reader playback projection unchanged |
| Persistent page/block hierarchy | Implemented and verified | `data/structure/FileStructuralKnowledgeStore.kt`, `domain/structure/StructuralDocument.kt` | `data/structure/FileStructuralKnowledgeStoreTest.kt`, `domain/structure/StructuralQueryTest.kt` | Atomic JSON files in `noBackupFilesDir`; ancestor and subtree queries; index rebuilt from eligible records |
| Headings, sections and navigation anchors | Implemented and verified | `domain/structure/StructuralDocument.kt`, `StructuralBlock.kt`, `StructuralAnchor.kt`, `data/structure/NotionStructuralExtractor.kt` | `data/structure/NotionStructuralExtractorTest.kt`, `domain/structure/StructuralHierarchyTest.kt` | Explicit heading block IDs, section breadcrumb paths, speech chunk mapping and non-spoken structural anchors |
| Explicit relations, mentions, links and synced references | Implemented and verified | `domain/structure/StructuralEdge.kt`, `data/structure/NotionStructuralExtractor.kt`, `data/notion/NotionModels.kt` | `data/structure/NotionStructuralExtractorTest.kt`, `data/notion/NotionPropertyPaginationTest.kt` | Dual-direction relation edges, page/user/date mentions, URL link targets, duplicate synced block provenance |
| Document caching, refresh and invalidation | Implemented and verified | `data/structure/FileStructuralKnowledgeStore.kt`, `application/StructuralKnowledgeCoordinator.kt`, `application/StructuralWorkScheduler.kt`, `ReaderApplication.kt` | `application/StructuralKnowledgeCoordinatorTest.kt`, `application/StructuralWorkSchedulerTest.kt`, `data/structure/FileStructuralKnowledgeStoreTest.kt` | Epoch-guarded atomic persistence; credential switch & whole-cache invalidation advance epoch; partial work queues retry |
| Offline reading, saved playback position and semantic identity | Implemented and verified | Reader domain unchanged; `ReaderApplication.kt` isolation gate; `domain/structure/StructuralKnowledgeRepository.kt` | `ui/StructuralPipelineIntegrationTest.kt`, `tools/proof-structural-isolation.ps1`, `tools/proof-structural-store.ps1` | Failing-first proof: structural store failure cannot abort reader or Discovery; player commands and saved positions preserved |
| Exhaustive workspace enumeration or proof of deletion from search absence/404 | Unsupported by Notion API; handled safely | `data/structure/FileStructuralKnowledgeStore.kt`, `data/structure/NotionStructuralDiscovery.kt` | `data/structure/FileStructuralKnowledgeStoreTest.kt`, `data/notion/NotionStructuralApiTest.kt` | Ambiguous 404 maps to `MISSING_OR_INACCESSIBLE`, never confirmed deletion; search absence never deletes; incomplete traversal preserves existing snapshots |

### Verification summary

1. **Unit tests (`:app:testDebugUnitTest`)**: 1,353 tests passed, 0 failures, 0 errors.
2. **Build and assembly**: `:app:assembleDebug` and `:app:compileDebugAndroidTestKotlin` succeed cleanly with 0 warnings.
3. **Failing-first regression proof 1 (`tools/proof-structural-store.ps1`)**:
   - `eligibility-removed`: 3 tests failed with expected `AssertionError`.
   - `epoch-removed`: 1 test failed with expected `AssertionError`.
   - `restored`: 32 tests passed (0 failures).
4. **Failing-first regression proof 2 (`tools/proof-structural-isolation.ps1`)**:
   - `guard-removed`: 1 test failed with expected `AssertionError: Derived structure must not block the shipping reader`.
   - `restored`: 1 test passed wrapper exit code 0.
5. **Instrumented pipeline integration test (`tools/run-accessibility-tests.ps1 -Class com.example.notionreader.ui.StructuralPipelineIntegrationTest`)**:
   - 1 test passed on connected device (emulator-5554, API 35/16).

See [official API constraints](phase1-api-evidence.md),
[implementation plan](../superpowers/plans/2026-10-26-structural-knowledge.md) and
[Antigravity workflow lessons](../antigravity-cli-workflow.md).
