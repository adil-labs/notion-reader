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

## After implementation and follow-up review

The initial landing at `9677cb7` required corrective review. This matrix describes
actual capabilities; final command results are recorded in the
[follow-up report](phase1-review-followup.md). API/resource limits below are part
of the scope of each claim. Existing Discovery acceptance gates remain separate.

| Capability | After | Actual implementation | Tests / evidence |
| --- | --- | --- | --- |
| Page discovery | Implemented and verified within API limits | `data/notion/NotionApi.kt`, `NotionRepository.kt`: cursor-paginated search; metadata callbacks include blank titles without changing Browse presentation | `KtorNotionApiTest`, `NotionRepositorySnapshotTest`, `NotionStructuralApiTest` |
| Database/data-source metadata and member discovery | Implemented and verified within bounded windows | `NotionStructuralDiscovery`, `StructuralDiscoveryContinuation`, `StructuralContainer`; native source-ID rotation persists, opaque cursors stay in process memory | `NotionStructuralDiscoveryTest`, `StructuralContainerStoreTest`; search cannot establish exhaustive workspace coverage |
| Page metadata, parent and revision | Implemented and verified | `NotionModels.kt`, `StructuralMetadata.kt`, `domain/structure/StructuralModels.kt`: typed parents, source identity, creation/edit times, source revision and access state | `NotionStructuralApiTest`, `StructuralModelsTest`, `StructuralQueryIntegrationTest` |
| Nested blocks, pagination and order | Implemented and verified within traversal limits | `StructuralExtractor`: native tree and explicit block containment; default depth10/5,000 blocks; reader projection retains its own policy | `StructuralCollectionBoundariesTest`, `StructuralExtractorTest`, `KtorNotionApiTest`, existing `ReaderFixtureContractTest` |
| Persistent hierarchy and offline queries | Implemented and verified | `FileStructuralKnowledgeStore`: atomic page/container files, eligibility authority and disposable indexes; direct children and ancestors through containers | `FileStructuralKnowledgeStoreTest`, `StructuralContainerStoreTest`, `StructuralQueryIntegrationTest` |
| Sections and stable anchors | Implemented and verified | `StructuralSection`, `StructuralNavigationAnchor`: heading IDs, hierarchy paths, ordered block IDs, optional actual reader chunk IDs | `StructuralExtractorTest`, `StructuralQueryIntegrationTest`, existing `ReaderIdentityStabilityTest` |
| Explicit relationships | Implemented and verified for decoded native fields | Typed forward relations/mentions/links, child-page/database and synced references; locally known incoming indexes; provenance and unresolved states | `StructuralExtractorTest`, `StructuralCollectionBoundariesTest`, `NotionUrlClassifierTest`, `NotionPropertyPaginationTest`; no inferred reciprocal semantic edges |
| Incremental update and withdrawal | Implemented and verified | `StructuralKnowledgeCoordinator`, atomic `applyObservation`: one collection can withdraw targets and commit its source; external lifecycle events still win; metadata revision/fact comparisons avoid unchanged fetches | `StructuralKnowledgeCoordinatorTest`, `StructuralObservationAdmissionTest`, `StructuralRecoveryIntegrationTest`, store/observation guard proofs |
| Partial and oversized work | Implemented and verified | Durable partial retries preserve prior content; supported-limit failures report `LIMIT_EXCEEDED` and stop automatic retries until revision change/manual refresh | `StructuralRecoveryIntegrationTest`, `StructuralExtractorTest`, `FileStructuralKnowledgeStoreTest` |
| Existing reader, playback and Discovery | Verified for executed regression scope; broader performance/human acceptance remains open | Process-owned existing background pipeline; no model invocation, new UI destination, player command or semantic ID migration | Full JVM suite, targeted Android wrapper tests and probes recorded in follow-up report; `StructuralPipelineIntegrationTest` checks reader/Browse isolation, not all Discovery or TalkBack speech behavior |
| Exhaustive inventory / deletion from search absence or404 | Unsupported or limited by Notion API | Search absence never deletes; explicit trash differs from missing-or-unshared; limits remain incomplete; unsupported blocks retain their native type | [Search limits](https://developers.notion.com/reference/search-optimizations-and-limitations), [status codes](https://developers.notion.com/reference/status-codes); collector and recovery tests |

Paths in this table are relative to `app/src/main/java/com/example/notionreader`
for production and `app/src/test/java/com/example/notionreader` for JVM tests.
Instrumented tests are under `app/src/androidTest/java/com/example/notionreader`.
The [API evidence](phase1-api-evidence.md) supplies official sources for each native
capability; resource limits do not imply an exhaustive workspace inventory.
