<!--
  GENERATED FILE - do not edit by hand.
  Exported from Notion by tools/export-notion-doc.ps1.
  Source page: Notion Reader — Notion Integration Implementation Spec
  Page id:     3dec9345-e10a-81e8-8b28-e7d04672e53e
  Notion URL:  https://app.notion.com/p/Notion-Reader-Notion-Integration-Implementation-Spec-3dec9345e10a81e88b28e7d04672e53e
  Notion last edited: 10/17/2026 06:12:00
  Notion is authoritative. Re-run the exporter after editing there.
-->

# Notion Reader — Notion Integration Implementation Spec

> Governing document, mirrored from Notion. See the header above for provenance.

# Summary

Build the Notion integration layer for an Android-first, hands-free reader. The feature must fetch user-selected Notion content, normalize it into a predictable speech-friendly document model, support deterministic sequential navigation, and provide stable identifiers so playback can resume locally without modifying Notion pages.

The first implementation target is deliberately narrow: given a known Notion page ID, fetch that page and its supported child blocks, convert them into an ordered ReaderDocument, and expose enough structure for later TTS and hands-free navigation.

# Goals
- Connect the app to a Notion workspace securely.
- Fetch one known page and all supported readable child blocks.
- Preserve document order and heading hierarchy.
- Convert Notion blocks into a stable reader-domain model independent of the raw API response.
- Produce speech-friendly chunks suitable for Android TTS.
- Preserve block/page identifiers for resume and navigation.
- Handle pagination, nested blocks, unsupported blocks, and partial failures safely.
- Keep playback state local to the reader rather than writing progress into Notion.
- Make the feature usable without visual interaction once content has been selected.

# Non-goals for the first implementation
- Full workspace search UI.
- Editing Notion content from the reader.
- Creating notes from the Android app.
- AI summarization or automatic rewriting.
- Database-specific discovery and queue semantics in the first known-page-ID milestone. These are introduced after arbitrary-page reading is reliable.
- Reading every Notion block type perfectly.
- Syncing playback position back to Notion.
- Cross-device playback sync.
- Voice-command recognition; the first milestone exposes commands programmatically so voice control can be layered on later.

# Validated Notion API assumptions
- Notion currently supports internal connections with static tokens, personal access tokens for trusted user-scoped tools, and public OAuth connections for apps installed by other users.
- Public OAuth authorization uses an authorization-code flow and returns access credentials that must be stored securely.
- Access is scoped to content the user or connection has permission to access; connections cannot assume access to the entire workspace.
- Block children are retrieved separately and may be paginated, so recursive traversal must not assume one response contains the full document.
- Notion search can discover accessible pages and databases later, but search is not required for the first known-page-ID milestone.

References:
- Notion authorization
- Retrieve block children
- Search

# User experience

## Initial POC flow
1. User configures or authorizes Notion access.
1. App receives a known Notion page ID or URL.
1. User chooses Read.
1. App fetches the page and its readable descendants.
1. App announces the title.
1. Reader exposes the document as ordered sections and speakable chunks.
1. Later TTS work consumes those chunks using Play, Pause, Next section, Previous section, Next chunk, and Previous chunk.

## Hands-free behavior target

After content has been selected, routine reading must not require touch exploration. The reader state machine must therefore support commands independent of UI widgets:
- Play
- Pause
- Stop
- Next chunk
- Previous chunk
- Next section
- Previous section
- Restart section
- Where am I?
- Continue from saved position

Where am I? should be able to announce the document title, current section title, and approximate progress without changing playback position.

# Content semantics and schema profiles

The reader must not require one specific Notion organization in order to read a page.

Use two capability levels:
1. Generic Notion mode — any accessible readable Notion page can become a ReaderDocument. Unknown databases and standalone pages are treated as generic content. Page structure drives reading; database metadata is optional.
1. Organized workspace profile — known schemas such as Knowledge, Reflections, Practice & Learning, Projects, and Tasks can map their properties into normalized library metadata. This enables richer discovery, filtering, queue construction, and spoken navigation without changing the underlying page-reading pipeline.

The organized profile is an enhancement, not a prerequisite. Failure to recognize a database schema must fall back to Generic mode rather than making the page unreadable.

## Normalized library metadata

When a source schema exposes meaningful properties, normalize them into a reader-owned model rather than leaking Notion property names into playback logic.

```kotlin
data class ReaderLibraryEntry(
    val pageId: String,
    val title: String,
    val kind: ReaderEntryKind,
    val area: String? = null,
    val topics: Set<String> = emptySet(),
    val date: LocalDate? = null,
    val summary: String? = null,
    val projectIds: Set<String> = emptySet(),
    val status: String? = null,
    val sourceSchema: ReaderSourceSchema = ReaderSourceSchema.GENERIC,
)

enum class ReaderEntryKind {
    KNOWLEDGE,   // no accessible source backs this yet; no adapter may claim it
    REFLECTION,
    PRACTICE,
    PROJECT,
    TASK,
    GENERIC,
}

enum class ReaderSourceSchema {
    KNOWLEDGE,   // as above
    REFLECTIONS,
    PRACTICE_AND_LEARNING,
    PROJECTS,
    TASKS,
    GENERIC,
}
```

ReaderLibraryEntry is for discovery and queue construction. ReaderDocument is for linear reading. Do not require every metadata field before a document can be read. projectIds may be empty: no accessible data source currently exposes a relation property, so an adapter must not treat an empty set as an error. status carries the source schema's own user-facing value rather than a universal enum, because the vocabularies differ per schema (six values in Projects, three in Todo List) and overlap only partially. A raw string is therefore not a cross-schema facet, so a Status filter is schema-scoped in the first implementation: it filters within a Type selection rather than across all content. A workspace-wide Status facet would be the point at which a normalized enum becomes justified.

## Metadata-to-speech boundary

Metadata such as Area, Topics, dates, project relations, status, URLs, and internal identifiers should normally influence selection and navigation but should not be spoken during ordinary note playback.

The default flow is:

```plain text
Notion database/page
    ↓
schema adapter when recognized
    ↓
ReaderLibraryEntry
    ↓
query / filter / sort
    ↓
reading queue
    ↓
ReaderDocument
    ↓
playback
```

A direct page-read request may bypass library discovery and go straight from page identity to ReaderDocument.

# Manual-open navigation history

Only a successfully published explicit manual Note-open request may record a recent-open reference. Failed, canceled, superseded, playback, indexing, prefetch and background loads do not. Persist at most twenty opaque page identities and timestamps in private backup-excluded storage. Resolve labels from currently eligible content; retain no evicted-content snapshot. Propagate confirmed deletion/permission-denial removals even after temporary eviction. History clearing/full reset removes these references. Keep this feature separate from document-cache LRU, semantic ranking and explicit interests.

# Discovery and reading queues

Hands-free multi-note navigation should operate on an explicit ordered queue, not on visual folder traversal or incidental Notion database order.

A queue query may use any available combination of:
- entry kind / source collection;
- Area;
- Topics;
- date range;
- project relation;
- status when meaningful;
- an explicit learning path or sequence when one exists.

Examples:

```plain text
"Read my reflections"
→ kind = REFLECTION
→ sort by date descending unless the user requests another order

"Read my mindfulness reflections"
→ kind = REFLECTION
→ topic contains mindfulness
→ sort by date descending

"Read my recent chess practice"
→ kind = PRACTICE
→ area = Chess
→ sort by date descending
```

Once constructed, a queue must expose deterministic commands independent of UI widgets:
- Next note
- Previous note
- Skip note
- Restart note
- Where am I?
- Continue queue

Chronological ordering is a sensible default for Reflections and Practice. Knowledge must not assume creation date equals learning order. A future explicit learning-path/sequence model may override chronology for Knowledge.

## Workspace schema adapters

Schema-specific interpretation belongs in adapters. For example, the current Reflections schema may map:

```plain text
Database identity → kind = REFLECTION
Area              → area
Topics            → topics
Date              → date
Summary           → summary
Page ID            → pageId
```

Do not hard-code playback logic to a database name. The adapter recognizes a schema and produces normalized metadata; playback consumes only reader-domain models. Recognition itself must be schema-based rather than name-based: the TASKS source is the live database named "Todo List", so an adapter matching on the name would miss it. Discriminate on its schema instead - the title property is Task name, together with Status and Due date. No adapter may claim KNOWLEDGE, because no accessible source backs it; the member remains a defined future kind that nothing may assume exists. Skills and Inbox are readable but are not recognized schemas, so they fall back to GENERIC rather than acquiring a Kind or SourceSchema value merely because the databases exist.

# Functional requirements

## Authentication
- Hide all Notion credentials from logs and source control.
- Abstract authentication behind a token provider so the POC can use a trusted personal/internal credential while production can move to an installable OAuth flow without rewriting the repository layer.
- Never store client secrets directly in the Android application for a production public integration.
- Surface revoked/expired/unauthorized access as an actionable connection error rather than a generic network failure.

## Page retrieval

Given a page ID:
- Retrieve page metadata needed by the reader, especially title and canonical page identity.
- Retrieve all first-level child blocks.
- Follow pagination until complete.
- Recursively retrieve children for readable container blocks when has_children is true.
- Preserve original order.
- Prevent runaway recursion with explicit traversal safeguards.

## Supported block set for POC

Must support:
- paragraph
- heading 1
- heading 2
- heading 3
- bulleted list item
- numbered list item
- to-do item
- quote
- code block
- divider

Useful next set:
- toggle
- callout
- child page reference
- bookmark/link preview
- table
- synced block

Unsupported blocks must never crash the document. Represent them as skipped/unsupported nodes with enough metadata for diagnostics. Do not read raw opaque JSON aloud. To-do items preserve their checked state in chunk metadata and never bake it into spoken chunk text; code blocks emit one CODE chunk containing the verbatim source with the language preserved as metadata; divider and unsupported blocks follow the no-chunk rules under Speakable chunk construction.

## Rich text normalization

For readable rich text:
- concatenate fragments in source order;
- preserve meaningful text content;
- retain link targets as metadata;
- do not speak raw URLs by default;
- ignore purely visual styling for the first POC;
- preserve inline code as text with a content-type hint for later speech policy;
- avoid introducing duplicated punctuation or whitespace when joining fragments.

## Section construction
- The page title represents the document title, not an ordinary content block.
- Headings define sections.
- An introductory section is created only when content precedes the first heading. A page that opens with a heading has no leading section.
- Lower-level headings should remain navigable without flattening away hierarchy.
- Empty headings must not produce unusable navigation targets.

## Speakable chunk construction

A chunk is the smallest unit the playback engine can safely resume and navigate to.

Default behavior:
- one ordinary paragraph = one chunk;
- one heading announcement = one chunk of type HEADING;
- one list item = one chunk;
- quote text = one chunk unless unusually long;
- code = one chunk initially, with a later code-reading policy;
- divider and unsupported blocks are preserved as structural nodes in the normalized block tree but emit no spoken chunk; nothing in ReaderChunkKind represents a divider, and an unsupported block must not emit an empty chunk because empty blocks would generate useless navigation targets.

Very long text blocks must be split at sentence boundaries where possible. Chunking must be deterministic so the same unchanged Notion page produces the same logical chunk ordering. The character threshold used for splitting is an implementation constant rather than a specification value, so block-to-chunk counts are not contractual for long blocks; tests assert the threshold-independent properties instead.

# Reader domain model

The Notion API DTOs must not leak beyond the integration/data layer.

```kotlin
data class ReaderDocument(
    val pageId: String,
    val title: String,
    val sections: List<ReaderSection>,
    val lastEditedAt: Instant?,
)

data class ReaderSection(
    val id: String,
    val title: String?,
    val level: Int,
    val chunks: List<ReaderChunk>,
)

data class ReaderChunk(
    val id: String,
    val sourceBlockId: String,
    val kind: ReaderChunkKind,
    val text: String,
    val metadata: ChunkMetadata = ChunkMetadata(),
)

data class ChunkMetadata(
    val checked: Boolean? = null,
    val language: String? = null,
    val linkTargets: List<String> = emptyList(),
    val hasInlineCode: Boolean = false,
    val headingLevel: Int? = null,   // heading_1..3 mapped to 1..3, so the Note screen can render
                                     // heading hierarchy from the chunk itself rather than guessing
                                     // it back out of the enclosing section
)

enum class ReaderChunkKind {
    HEADING,
    PARAGRAPH,
    LIST_ITEM,
    TODO,
    QUOTE,
    CODE,
}
```

IDs used for persistence must derive from stable Notion identities where possible rather than list indexes alone. The page title is carried by ReaderDocument.title and announced by the Player on session start and note transitions; it is not represented as a chunk.

# Raw integration model

Keep a small API-facing representation separate from ReaderDocument.

Suggested layering:

```plain text
Notion HTTP JSON
    ↓
Notion DTOs
    ↓
Notion tree / normalized blocks
    ↓
ReaderDocument
    ↓
ReaderSession / playback queue
    ↓
Android TTS
```

This separation is an invariant. TTS code must not know Notion JSON structure.

# Architecture

## Components

### NotionAuthProvider

Provides an access token or reports that authorization is required.

### NotionApiClient

Responsible only for HTTP transport, headers, serialization, pagination primitives, and error mapping.

### NotionRepository

Provides semantic operations such as:

```kotlin
suspend fun getPage(pageId: String): NotionPage
suspend fun getAllBlockChildren(blockId: String): List<NotionBlock>
suspend fun getReadablePageTree(pageId: String): NotionPageTree
```

### NotionBlockNormalizer

Converts API block variants into a smaller internal normalized block model.

### ReaderDocumentMapper

Converts the normalized block tree into ReaderDocument, sections, and chunks.

### Out of scope: session persistence

Listening-session persistence is owned by the future Active Queue & Reader Session specification, not by the Notion integration layer.

The integration layer must expose stable page, section, block, and chunk identities so the session layer can persist and restore playback position without depending on Notion API DTOs.

# Interfaces

The first vertical slice should expose exactly one high-level use case:

```kotlin
interface LoadReaderDocument {
    suspend operator fun invoke(pageId: String): Result<ReaderDocument>
}
```

Everything below this interface is replaceable implementation detail.

# Session persistence boundary

This specification does not own listening-session persistence.

Playback progress belongs to the listening session and frozen Active Queue defined by Notion Reader — Product & Architecture Contract.

The future Active Queue & Reader Session specification owns:
- Active Queue snapshot persistence;
- current queue position;
- current Note identity;
- current structural playback position;
- chunk/block/section fallback rules for Continue;
- finer playback offsets where supported;
- recent and completed listening-session retention.

The Notion integration layer owns only the stable document identities required by that session layer. It must not persist a permanent resume position keyed only by pageId, and it must not define Play or Continue semantics.

A session-position model may reference integration-owned identities such as:

```kotlin
data class ReaderDocumentPosition(
    val sourceBlockId: String?,
    val chunkId: String?,
    val sectionId: String?,
    val characterOffset: Int = 0,
)
```

characterOffset may remain 0 in the first implementation. Numeric chunk indexes alone are not stable persistence identities because document edits can shift indexes.

# Change handling

When a Notion page is fetched after it has changed:
- fetch the fresh page;
- construct a fresh ReaderDocument;
- preserve stable page, source-block, section, and chunk identities wherever the corresponding Notion structure still exists;
- expose enough identity information for the session layer to determine whether a previously saved structural position still exists.

The Notion integration layer must not itself decide whether playback resumes, where Continue falls back, or what the user hears when a saved position no longer exists. Those behaviors belong to the future Active Queue & Reader Session specification.

Caching may be introduced after correctness is established. The first implementation should prefer fresh data over complicated invalidation logic.

# Error handling

Map failures into user-meaningful categories:

```plain text
AuthorizationRequired
PermissionDenied
PageNotFound
RateLimited
NetworkUnavailable
ServerError
MalformedResponse
UnsupportedDocumentStructure
Cancelled
```

Requirements:
- cancellation must remain cancellation and must not be translated into a network error;
- retry only transient failures;
- do not automatically retry permission/authentication failures;
- preserve enough diagnostic context for logs without exposing tokens or private note content unnecessarily;
- a single unsupported block should not invalidate an otherwise readable page.

# Accessibility

Accessibility is a functional requirement, not post-processing.
- All commands must be reachable through ordinary Android accessibility APIs.
- TalkBack focus order must match logical reading order.
- Controls require stable accessible names and state announcements.
- Do not require gestures that conflict with TalkBack.
- Playback must continue without keeping visual focus on the currently spoken block.
- State changes such as paused, resumed, end of section, end of note, and errors must have concise spoken feedback.
- Metadata such as tags, timestamps, URLs, and internal IDs should not be spoken during ordinary reading unless requested.
- ReaderDocument must remain useful even if no visual UI is rendered at all.

# Security and privacy
- Treat Notion note content as private user data.
- Never send note content to third-party services merely to perform basic playback.
- Do not log full page bodies in release builds.
- Redact authorization headers and access tokens from all diagnostics.
- Keep stored credentials in platform-appropriate protected storage.
- For a distributed/public application, perform any operation requiring a confidential client secret outside the Android binary.

# Test strategy

## Unit tests

### Rich text
- joins multiple rich-text fragments in order;
- handles empty rich text;
- retains plain display text for links;
- normalizes whitespace predictably.

### Block mapping

One fixture for every supported block type.

### Hierarchy
- content before first heading creates intro section;
- headings split sections correctly;
- nested headings retain levels;
- nested block children preserve source order.

### Chunking
- deterministic IDs/order for unchanged input;
- long paragraphs split predictably;
- lists remain one item per chunk;
- empty blocks do not generate useless chunks.

### Stable position handoff
- unchanged source blocks produce stable block-derived identities;
- unchanged sections produce stable section identities;
- deterministic chunk construction produces stable chunk identities for unchanged content;
- removed or changed structures are represented accurately rather than silently remapped by numeric indexes;
- session-level resume and fallback behavior is tested in the future Active Queue & Reader Session specification.

## Repository tests

Using a fake HTTP layer:
- one-page children response;
- multiple pagination pages;
- nested children;
- rate limit response;
- 401/403;
- missing page;
- malformed block;
- cancellation during pagination.

## Integration test

Use one dedicated real Notion test page containing every POC-supported block type. The test passes when the app produces the expected ordered ReaderDocument without depending on visual UI.

## Accessibility validation

Before considering the POC complete:
- perform the complete playback/navigation flow with TalkBack enabled;
- verify that every action is exposed with an understandable label;
- verify that normal playback never starts reading implementation metadata accidentally.

# Implementation phases

## Phase 0 — Contract and fixture

Create one dedicated Notion test page with headings, paragraphs, lists, quote, code, to-do, divider, nested content, and at least one unsupported block.

Define expected ReaderDocument output manually before implementing the parser.

## Phase 1 — HTTP and authentication seam

Implement:
- NotionAuthProvider
- HTTP client
- required Notion headers
- page retrieval
- one page of block children

No recursive traversal yet.

## Phase 2 — Pagination and tree retrieval

Implement complete block pagination and recursive child retrieval with ordering and cancellation correctness.

## Phase 3 — Normalization

Convert raw Notion DTOs into a small normalized block tree. Add fixture-driven tests for each supported type.

## Phase 4 — Reader mapping

Implement sections and speakable chunks. At the end of this phase the app can print or inspect a complete ReaderDocument for a real page.

## Phase 5 — Local progress persistence

Expose stable page, source-block, section, and chunk identities, and prove that unchanged content produces unchanged identities across edits. This phase does not implement resume or persistence: listening-session persistence is owned by the future Active Queue & Reader Session specification, per Session persistence boundary above.

## Phase 6 — TTS integration

Feed ReaderChunks to Android TTS with play/pause/next/previous section and chunk commands.

## Phase 7 — Hands-free command layer

Connect the reader command interface to the chosen hands-free interaction mechanism without changing Notion parsing or playback-domain logic.

## Phase 8 — Discovery and schema-aware queues

Only after the known-page path is reliable:
- discover accessible pages/databases;
- recognize supported workspace schemas through adapters;
- map recognized entries into ReaderLibraryEntry;
- fall back to GENERIC for readable pages whose schema is unknown;
- filter by Area, Topics, date, project relation, and kind when available;
- construct deterministic multi-note queues;
- add recents and explicit reading/learning sequences where useful.

Schema recognition must enhance discovery without becoming a requirement for basic reading.

# Acceptance criteria for the first POC milestone

The first milestone is complete when all of the following are true:
- A known Notion page ID can be loaded from the Android app.
- All paginated supported children are retrieved in correct order.
- Nested supported content is included.
- The raw Notion response is converted into ReaderDocument.
- Title, sections, and chunks are deterministic and inspectable.
- Unsupported blocks do not crash loading.
- Authentication, permission, network, and not-found errors are distinguishable.
- Unit tests cover mapping, hierarchy, pagination behavior, and error mapping.
- The implementation contains no TTS-specific dependency in the Notion repository or mapper.

# Architectural invariants
1. Notion DTOs never reach the playback layer.
1. Playback state never modifies Notion content.
1. Document ordering follows Notion source ordering.
1. Pagination is invisible to callers.
1. Unsupported blocks degrade locally rather than failing the whole note.
1. Stable source identities are preferred over positional indexes.
1. Every reader action can eventually be triggered without visual UI.
1. The first implementation optimizes for correctness and inspectability before caching or advanced discovery.

# Open questions
- For the private POC, should authentication start with a personal access token, an internal connection, or the production OAuth architecture immediately?
- Should headings themselves be spoken automatically or treated primarily as navigation boundaries with a configurable announcement policy?
- How should code blocks be spoken: verbatim, summarized structurally, or skipped by default?
- Should child-page references automatically enter the child page during sequential playback or remain explicit navigation actions?
- What maximum nesting depth should the first recursive traversal permit before treating the structure as suspicious?

# Next implementation step

Do not begin with TTS or a full workspace browser.

Create the dedicated Notion fixture page and define the expected Kotlin ReaderDocument for it. Then implement the smallest vertical slice:

```plain text
known page ID
    → fetch page metadata
    → fetch first-level child blocks
    → normalize supported blocks
    → map to ReaderDocument
    → print/assert the result
```

Once that contract is stable, add pagination and recursive children before introducing playback.

# Local semantic indexing boundary

Semantic indexing consumes cached ReaderDocuments and normalized ReaderIndexEntries only. Notion DTOs remain confined to data integration. It must not force a workspace-content download or alter existing refresh, cache ownership, playback, or retry semantics.

The cache exposes bounded enumeration and change observation without touching access timestamps. All writes, removals, budget evictions, corruption removals and clears participate. A failed or partial remote index response never proves deletion; confirmed page-not-found or permission-denied removal invalidates derived semantic data.

A single process-scoped application worker extracts title, section and paragraph text, chunks within the embedding model token budget without whole-document truncation, and retains page, section, block and passage-offset provenance. Filtering metadata is separate from semantic input. Content hashes avoid unchanged embeddings; metadata-only edits update filters independently.

Per-document semantic records are atomic, versioned by model artifact, tokenizer, preprocessing, chunking, dimensions and storage format, and stored outside backup. Index completion checks source revision and reset generation before commit. Topic/checkpoint persistence distinguishes successful writes, invalid snapshot rejection with a content-free reason, derived-budget exhaustion, I/O failure and an unknown legacy-adapter failure. Never label validation, I/O or unknown failure as storage full. Preserve the previous valid generation on a rejected write and retain cancellation semantics. Developer diagnostics may record aggregate timings and structural failure codes, never Note content, IDs or queries in general logs. An explicitly requested private local evaluation may retain a developer-controlled snapshot outside the app in ignored local files; it is not behavioral profiling, a cloud embedding service or a product cache-retention exemption. Interrupted work resumes from cached source; corrupt derived data is rebuilt. Retrieval revalidates cache eligibility before publishing.

The initial model is optional English MiniLM-L6-v2 with 384-dimensional normalized embeddings and ONNX Runtime CPU inference, subject to API 26, ABI/native packaging, licensing, tokenizer parity, memory and retrieval-quality feasibility gates. Downloads require explicit user action and immutable revisions/checksums; model initialization and inference never run on the UI thread.

The derived index budget is 64 MiB. Indexing pauses rather than truncating documents or evicting source documents on budget exhaustion. One worker gives foreground queries priority and pauses bulk indexing during playback, app backgrounding and resource pressure. Checkpoints resume on an eligible launch; no persistent indexing service is introduced.

Vectors and source excerpts are removed with their source cache records, including temporary eviction and corruption cleanup. Automatic topic labels, evidence, memberships and Note-pair connection evidence have the same cache-bound lifecycle. Temporary eviction and explicit cache clear are distinct from confirmed deletion or permission loss: the former may retain a minimal opaque continuity registry, while the latter also removes affected source identities from that registry and interest provenance. Neither partial index refresh nor failed remote fetch is evidence of deletion. Cache mutations expose a typed reason and generation; confirmed access loss invalidates discovery even when the page was already evicted. No registry record retains an evicted source's title, derived label, text, vector or excerpt. Explicit user-confirmed interest name/matching text is independent user data, not a hidden seed-content snapshot.

Topic discovery reuses the existing embedding identity, passage records and exact cosine primitives. Domain owns pure overlapping membership, source-backed labels, identity reconciliation and connection ranking; data owns private atomic topic/continuity persistence and typed cache lifecycle events; application owns process-scoped incremental coordination. No Python, extra generative model, cloud service or second permanent worker is introduced. A Note is independently eligible for multiple topics through matching passages; do not replace this with a non-overlapping document partition. Do not materialize an unbounded passage-pair similarity matrix. Version evidence/label/diversity policies separately from embeddings. Policy updates reprocess cached vector records without recomputing unchanged embeddings, preserve opaque continuity and saved interests, invalidate incompatible processing checkpoints, and offer the resulting generation through explicit Refresh. Reference-only/link-list/navigation boilerplate is excluded from thematic group/Similar passages evidence without deleting or hiding it from semantic search. Explicit citations may independently establish Named references in the replacement engine. Apply deterministic near-duplicate passage checks alongside member overlap for consolidation and a bounded per-Note connection exposure cap. Publication eligibility is separate from candidate retention: retain eligible generic fallback-labelled candidates and their opaque continuity internally, but withhold them from user-facing topic collections. Literal overlap is not proof of a complete, specific subject: reject incomplete negative/modal predicate clauses. Conservatively withhold a label equal to the shared title-family prefix of at least three and at least sixty percent of current members unless two distinct current member body sections independently use it as a complete heading. This source-family check is not a semantic classifier; retain eligible candidates and independent passage themes, and keep search and individual connections available. Near-identical member families need separately supported passage themes and subject labels to justify separate published topics; never infer distinct themes from document-family membership alone or erase proven multi-topic passages merely because members overlap. Hidden candidates do not prevent individual Note search or connections. In the release baseline, classify existing eligible pairs with a pure, versioned presentation policy: Named reference requires an explicit instruction or citation in a current body passage naming the other eligible cached Note by its full unique current title, matched conservatively with word/title boundaries. Resolve title uniqueness across canonical eligible cached identities; ambiguous, stale or partial titles remain Similar passages. Do not infer verified Notion-link targets from title text. Classification retains pair identities, source excerpts, score admission and exposure limits, and needs no new embeddings or vector storage format. Keep reference usefulness separate from inferred thematic usefulness in evaluation; reclassification of a previously reviewed pair is exposed follow-up evidence, not a fresh held-out case. Version this publication policy independently from embedding artifacts and preserve explicit followed-interest definitions. A replacement embedding provider may ship only after immutable artifact, native ABI/API 26, tokenizer/prompt/pooling, memory, retrieval quality and playback-contention gates pass; unsupported ABIs or unmeasured gates block default migration, without changing minimum Android support.

Persist versioned topic generations, stable topic IDs, source references and processing checkpoints atomically. Only publish a completed consistent generation after model/index/cache/reset revisions still match. Reconcile changed and invalidated documents incrementally with bounded candidate/anchor memory, foreground-query priority and the existing playback/background/resource-pressure suspension policy. Any topic/work/storage cap is visible as incomplete coverage, not a claim that remaining Notes have no topic. Retain the existing 64 MiB total derived-data budget across embeddings, topics, connections and continuity state.

Index clear disables rebuilding until explicit resume and clears automatic topics, connections and continuity state; model deletion does the same. Explicit interests, including followed-topic name/matching text, survive both operations; full local reset clears them. A model change rebuilds incompatible content embeddings and topic evidence without silently rewriting followed interests. No note content or queries are transmitted to external inference services or logged.

# Automatic topics: formation, publication and acceptance

The automatic topic formation and publication requirements in the Product and UI contracts govern the replacement engine. Complete eligible-section opportunity through bounded cancellable resumable work; do not use a section cap as terminal completion. Persist progress tied to source/model/policy/reset identity with accurate incomplete coverage. Resource pressure pauses work rather than silently discarding remaining sections. Preserve prior valid generations on rejected writes and cancellation, current source eligibility checks and the sixty-four MiB derived budget.

Publication status distinguishes established subjects with at least two distinct supporting Notes from validated single-Note Emerging topics without changing independent saved interests. Literal body evidence and specific subject labels remain required. Substantive discussion under non-goal/deferred headings is eligible without claiming that a capability exists. Existing reference classification, source-removal/privacy rules, exact search, process-scoped worker and playback/background scheduling remain binding. No whole passage-pair matrix, Python runtime, cloud inference or new model is introduced. General release enablement requires the governing human-quality and physical Android gates. The authorized debug trial below is permitted while those gates remain open.

# Independent explicit Note references

In the replacement engine, retrieve explicit Note references independently of semantic similarity thresholds, cross-topic membership and thematic body-evidence admission. An explicit instruction or citation naming the unique complete current title of another eligible cached Note establishes a reference, including instructions in reference lists; a bare coincidental or partial title mention, ambiguous title, standalone heading, or negated instruction does not. Reference-list evidence never independently establishes topic membership or a Similar passages relationship. Both endpoints must remain currently cached and indexed, with source revision and title uniqueness checked. Display the literal referencing passage and a literal target passage as target context; explain that the target excerpt is context, not proof of thematic similarity, agreement or a verified Notion hyperlink. Keep Named references separate from Similar passages in lists, evidence descriptions and evaluation. Preserve unordered canonical pair identities, explicit Refresh, source-removal/privacy rules and session isolation. Independent retrieval uses the experimental engine flag. General release enablement requires the governing gates; the authorized debug trial is permitted as described below. The release baseline retains classification of already eligible pairs.

# Developer evaluation and authorized debug trial

Discovery is automatic. End users are not required to label, rate, validate or approve discovered topics or supporting passages before using it. Follow and Unfollow express a personal choice, not a topic-quality judgment. Human quality evaluation is developer acceptance work on public, consented or purpose-built representative evaluation Notes; it must not require any user to submit or review their private library. Private-library feedback is optional and stays local unless the user explicitly chooses otherwise. Preserve the existing evaluation thresholds and distinguish verified human judgments, provisional annotations and unrated cases.

The user has authorized a debug-build trial of the implemented replacement Discovery engine on their Android device. Debug builds may enable the replacement for this trial while general release builds retain the baseline. This is not a declaration that quality, memory, playback or human TalkBack acceptance has passed. Keep failed and unmeasured gates recorded, including the latest physical-device additional-memory result, and retain the existing gates for general release enablement. The trial uses the existing local MiniLM model and automatic indexing lifecycle, without a new model download, required private-data review, source-cache reset, saved-interest reset or playback-command change.

