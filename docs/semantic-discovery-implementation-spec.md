# Semantic Discovery Implementation Specification

Hand-authored implementation companion to the seven governing specifications listed
in [the documentation map](README.md). This file records concrete implementation
choices and acceptance procedures; it is not an additional governing specification.
The live Product, UI and Integration contracts, including their 2026-10-17 and 2026-10-18 amendments,
control Discovery semantics. [decisions.md](decisions.md) preserves investigation,
verification and superseded-policy history.

## Current discovery engine

All debug and release builds use the sole section-aware hierarchical engine; legacy
streaming execution, wrappers, switching flags and rollback properties are retired.
The process-owned host coordinates bounded foreground and persistent WorkManager execution windows. See
[single-engine design](single-engine-discovery-design.md) for stages and migration.
This adoption does not certify failed or unmeasured acceptance gates.

### Current hierarchy/catalog refinement

The 2026-10-17 governing amendments introduce explicit subject relationships and
complete catalog retention; see [the current round](decisions.md#77-subject-hierarchy-and-complete-connection-catalog-in-progress-2026-10-17).
The initial hierarchy recognizes affirmative literal body statements of
subtopic/type/kind/part relationships, independently of vector similarity or heading
ancestry. Related-subject statements form a separate edge kind. Relationships retain
source revision and literal passage provenance, reject ambiguity/cycles and withdraw
on source/endpoint loss. This initial path does not establish general hierarchy recall.

TopicPolicy 12 removes equal/subset-topic admission exclusions, per-passage top-five
truncation and persisted exposure selection. Homepage diversity selection consumes
the full retained catalog. Canonical semantic pairs and explicit references have
separate identities and classifications. `FileConnectionCatalog` stores immutable
64-entry digest-checked pages, committed by `FileTopicStore` through an atomic
generation manifest; pages and staging count against the shared storage budget.
UI pagination and direct store page reads exist, but coordinator/inspected snapshots
still materialize complete catalogs; bounded-memory controller/candidate handling
and its performance verification remain required.

Connection work quanta count 256 target-Note comparisons, with durable Note,
passage and target cursors and the declared target-source count. Restart resumes
inside a passage rather than rescanning every target. Exact ranking checks
cooperative cancellation every 64 passages and applies body admission in the
streaming loop; a one-Note library has no pair opportunities. JVM checks establish
those work units and guard behavior; real Android latency gates remain separate.

Presentation classifies only the requested visible connection window or selected
comparison and retains only two candidates for the homepage preview. Growing the
visible window keeps prior content mounted during preparation; source changes
discard stale presentation immediately. This bounds extra evidence preparation
by the explicitly displayed window, while full store/coordinator snapshot residency
still requires a separate persistence/ownership change.

## Product and surfaces

Home is the default destination alongside Discover, Browse, Queues and Settings.
Home owns Continue listening, listening history, recent Notes and compact interest
cards; Discover owns cached semantic search, automatic subjects and individual
connections. Browse owns deterministic Explore and its complete catalog.
Explicit interests are compact navigation cards. Their individual details load up
to 20 matching cached Notes on demand, retain source evidence, and offer a five-Note
listening preview with a descriptive Interest label. Only explicit Play starts its
frozen queue. Cached-seed interests share that route and exclude their seed.
Keep Saved Queue shortcuts in their existing Home/Queues surfaces. Semantic search,
explicit text/seed-Note interests, related Notes, older matching Notes and guided
listening supplement Browse. Browse keyword search, metadata facets,
sorts, Smart Queue semantics and playback ownership stay unchanged.

Search is labelled Search your cached Notes and reports indexed/cached coverage.
Return at most 20 distinct Notes by relevance, or five contextual recommendations.
Filters use existing case-insensitive metadata matching. Exclude seed identities
from recommendations. Cite literal source excerpts and section labels; do not
generate answers, relationship claims or confidence percentages. Hybrid ranking,
activity profiling and cloud embeddings are excluded.

Topics in your Notes automatically discovers overlapping passage-supported groups;
it is independent of explicitly saved Your interests. A Note can qualify for several
topics or none. Individual cross-topic Note connections use excerpts from both
Notes and remain distinct from topic membership. Search, Browse and individual
Related Notes never require membership or followed interests.

Interests are local editable/removable text, canonical cached Note identities or
explicitly confirmed followed topics. A topic follow persists its own ID, display
name and editable matching text, with optional origin topic ID as provenance only.
Its recommendations match that saved text; cluster changes never rewrite or delete
it. Do not persist an anchor vector, excerpt or seed-content snapshot as a follow.
An evicted seed is unavailable until recached. Confirmed source loss removes source
links but preserves explicitly confirmed interest text. Index/model clear preserves
interests; full local reset removes them.
Older Notes have known last-edited timestamps at least 90 days before the injected
clock; do not claim they are unread/forgotten. Missing metadata does not exclude
otherwise readable Notes.

Guided previews contain up to five recommended Notes, initially by relevance,
excluding the seed. Provide remove/move-up/move-down, Play and Saved Queue actions.
Only Play freezes the visible order into a new Active Queue from the beginning.
Discovery origin stores a descriptive label, never a live query/interest reference.
Preserve version-2 session records. Preview edits, saving, navigation and index
changes never affect the active or resumable session.

### Presentation and accessible evidence

The governing navigation contract defines compact recent-session rows on Home, integrated field/IME search on Discover, one representative per topic preview, and ready-update precedence in the single discovery progress state. Generic fallback captions are presentation only: persisted labels, identities, membership, saved interests and frozen listening queues remain unchanged. Full passage evidence remains in details.

Topic detail provenance and distinct anchor contexts are collapsed behind Why these Notes?, with saved expansion and source-control return focus. Representative members are marked inline rather than listed twice. Connections use single-action compact previews with two titles and one excerpt; comparison details show both full excerpts. Generic labels have source-Note attribution resolved from currently eligible members. No ranking, identity or followed-interest policy changes are implied.

Guided listening keeps its single Play and Save to queue actions outside the weighted scrolling preview. Long evidence and large text do not push those actions beyond the Note list. The existing shell owns system insets; no overlay obscures the last item. Editing controls, stable focus targets, empty/unavailable disablement and explicit-Play session creation retain their existing contracts.

## Architecture and content

Domain owns model/embedding identities, tokenizer and index ports, chunk provenance,
interests, filters and pure ranking. Data owns inference, model acquisition and
private storage. Application owns process-scoped worker/query orchestration;
composition root wires it. Preserve existing layer rules and playback lifetimes.
Consume ReaderDocument and ReaderIndexEntry, never raw Notion DTOs.

Each passage retains canonical page ID, section ID/title, source block and reader
chunk IDs, character offsets, source order and literal excerpt. Semantic text is
title, section context and passage; filtering metadata is separate. Chunk at
section/paragraph boundaries; split oversized paragraphs with 32-token overlap.
Complete inputs, including context and special tokens, fit 256 WordPiece tokens;
bound context to 32 tokens. Skip empty text; supported code remains literal text.
Never reduce a whole long document to one truncated vector.

Stable source identities and offsets identify passages. Hash semantic input
separately from filter metadata. Reuse unchanged embeddings, including across
metadata-only edits. Index identity includes immutable model and tokenizer
artifacts, preprocessing/chunker versions, dimensions and storage version.

## Model and privacy

Current optional model: text-only EmbeddingGemma 2 INT4, 768 dimensions, using
community ONNX export revision `daa72c51243991dfcaf9f9137d2c573d8f7790c0` and Gemma
terms. ONNX Runtime Android 1.31.0 validates Vulkan execution against CPU on the first real input and falls back to two-thread CPU on unavailable, failed or invalid GPU output. The 206,696,682-byte
pack includes a pinned graph, external weights, tokenizer and configuration assets.
Use the exported `sentence_embedding`, with L2 normalization, never mean pooling
its hidden states. Native Hugging Face Tokenizers 0.22.1 supplies exact BPE token
IDs and converted UTF-16 evidence offsets. The clustering instruction and BOS/EOS
are included; the provider rejects inputs over 8,192 tokens without truncation.
Bounded source windows remain complete and overlapping rather than using the
whole context for every passage.

The user-requested governing amendment authorizes this integration; host proxy
quality does not establish general release acceptance. Model/tokenizer/preprocessing
identity changes invalidate incompatible vectors and derived checkpoints, preserving
explicit interests and listening state. Policy 16 retains exact-formation distance
0.10, membership 0.90, retention 0.89, semantic connection 0.90 and recommendation
0.90. These decisions are separate from search's unthresholded top-20 ranking and
from named-reference admission. The actual shipping formation path was tested on
500 public WOS Notes, and real inference plus a six-Note end-to-end journey on a
Pixel 8 Pro/API37. Other runtime ABI/API combinations, full-scale resource limits,
independent quality and human speech/listening gates remain unqualified.

Model acquisition is explicit and optional, showing exact size/source/license,
storage impact and network choice. Pin immutable revisions, SHA-256 and byte
lengths. Download to staging, verify, then atomically install; cancellation/error
cleans staging and supports retry. No silent updates or launch downloads. Disable
runtime telemetry initialization and telemetry. Private content/queries are never
transmitted to inference services or logged.

Lazy inference runs off-main-thread with one operation at a time, yielding to
foreground queries between indexing passages. Release native resources on memory
pressure. Store model, vectors, excerpts and topic state outside backups. Every
cache removal removes derived vectors/excerpts, topic membership/evidence,
connection evidence and exclusively dependent automatic labels. Temporary eviction,
cache clear and corruption may retain only opaque topic/page/source IDs, creation
order and unavailable state for continuity. Confirmed deletion or permission loss
also removes affected source IDs and provenance links, including when a Note was
already evicted. Failed/partial refresh never proves deletion. Index clear advances
reset generation, removes automatic topics/connections/continuity and persists
paused state until explicit resume/rebuild. Model deletion does the same;
full local reset also clears interests.

Historical LiteRT-LM static evaluation rejected a different generic converted artifact; it does not apply to the now-integrated text-only ONNX INT8 export. See `tools/semantic-discovery/gemma2-gate-report.json` for that historical probe and decisions §94 for current Android evidence. No minimum SDK or ABI support was removed.

## Index lifecycle and retrieval

Extend cache enumeration without touching LRU timestamps. Observe all puts,
removals, clears, pruning, corruption deletion and internal budget eviction. One
process-scoped worker reconciles on launch/cache/index changes. Bulk indexing
pauses during playback and resource pressure. A process coordinator serializes
foreground and persistent WorkManager execution windows through one gate. Ordinary
bounded background windows may run with the screen off. Balanced requires thirty
percent battery or external power; Charging only requires external power. Explicit
Pause remains persisted. Activity recreation does not own or replace this work;
no foreground notification, sticky restart or second indexing loop is introduced. Query inference,
tokenization and publication filtering are main-safe too. Do not force a workspace download.

Persist atomic per-document records and a versioned manifest. Publish a completed
document only after source revision, cache membership, model identity and reset
generation still match. Persist completed-document checkpoints; interrupted work
retries from cached source. Corruption becomes rebuildable missing data. Remove
obsolete records before publishing. Failed/partial remote refresh never proves
deletion; confirmed denied/not-found cache removal does. Revalidate eligibility
before publishing search results to prevent stale deletion/reset races.

Stream exact cosine retrieval using bounded per-document/top-K memory. Validate
dimensions, finite values and normalized nonzero vectors. Filter before scoring,
group by the strongest matching passage per Note and deduplicate canonical IDs.
Order by descending score, canonical page ID and passage source order. No vector
database initially. Derived storage budget is 64 MiB; pause with incomplete
coverage on exhaustion, never silently truncate a document or evict its source.

## Shared topic identity, persistence and listening behavior

The detailed design is
[Discovered topics and individual connections](superpowers/specs/2026-10-15-discovered-topics-design.md).
That document records the initial baseline design; decisions.md section 45 records
its implementation and measured evidence. Subsequent governing Notion amendments
and the current replacement requirements below take precedence.

Use overlapping passage-supported membership, never an exclusive document
partition. The legacy engine retains its bounded anchor implementation while the
sole engine uses complete, bounded, resumable section opportunity.
The replacement publishes established topics with at least two distinct eligible
Notes and validated single-Note subjects separately as Emerging topics. Version/calibrate
topic thresholds separately from recommendation thresholds. Process source changes
incrementally; no unbounded pair matrix or Python runtime. Grouping and connection
work shares the existing single process-scoped worker and its
query/playback/memory scheduling policy and Android-granted background windows.

Persist stable opaque topic identities independent of label/order. Reconcile
surviving source/member evidence across anchor changes, splits and merges. Never
redirect an inspected destination silently. Pin completed snapshots for navigation;
offer explicit Refresh for newer topic generations, while withdrawing ineligible
content immediately. Literal labels keep source provenance and do not change merely
because a new candidate has a slightly higher score.

Topic, connection, staging and continuity bytes share the existing 64 MiB budget.
Continuity has a 1 MiB sub-budget; exhaustion pauses new identity creation with
incomplete coverage rather than silently deleting identity records. Index clear
explicitly discards continuity. Cache removal reasons must be typed and propagated;
cache snapshots alone cannot distinguish eviction from confirmed access loss.

Topic and connection details reuse the existing editable five-Note listening
preview and Saved Queue actions. Explicit Play alone snapshots a new Discovery
session. A topic preview uses representative order; a connection preview contains
the two displayed Notes. Follows, topics and pair connections are never evaluated
while restoring frozen sessions.

Checkpoint persistence reports validation rejection (with content-free structural reason), budget exhaustion, I/O failure and unknown legacy-adapter failure separately. Only budget exhaustion is described as storage full. Rejected writes preserve the prior valid generation and cancellation propagates. During split/merge reconciliation, reserve continuity identities before assigning fallback candidate IDs; a moved or merged identity cannot also identify its former candidate.

An explicitly requested developer review may export a private snapshot to ignored local build storage, without credentials, model files or listening history. Freeze private held-out cases separately from public calibration and keep human judgments pending until actually supplied. Candidate unshown pairs are not automatically missed useful connections. Host replay timings are not Android performance evidence.

Policy version is persisted independently of EmbeddingIdentity. The grouping source signature includes policy settings; incompatible checkpoints cannot reuse accepted-document flags. Reprocess existing vectors, preserve eligible continuity/interest definitions, and expose replacement snapshots through explicit Refresh. No new content download or embedding regeneration is required by policy changes.

Policy 14 also withholds generic document containers Body, Abstract, Main text and
Full text as subject labels, while retaining candidates and allowing eligible
literal source-phrase naming. Focused candidate formation shares that generic-label
set. The [WOS diagnostic](discovery-wos-evaluation.md) records the failing-first
publication proof and host MiniLM/BGE/EmbeddingGemma 2 comparisons. Those source
splits are not family-held-out human acceptance. Runtime model and thresholds
remain unchanged. The earlier LiteRT-LM static gate is separate from the new
text-only Gemma ONNX host experiment; neither establishes Android qualification
for the other runtime.

Requested bulk work additionally requires external power or at least 30% battery;
unknown unplugged charge waits. The process-owned listener feeds the existing
worker gate without delaying source withdrawal or foreground search. Recheck
eligibility after query/mutex waits before inference. Pending non-reused passages
drive a bounded device-rate estimate of active embedding time; source/reset/model
changes withdraw incompatible estimates. Passive UI text distinguishes waiting
from user pause and explicitly excludes unestimated subject/connection duration.

## Accessibility and verification procedures

Shipping connection state contains a bounded preview and an opaque catalog
reference. Load collection windows and selected comparisons asynchronously against
that generation; never expose a file-backed list to UI equality or rendering.
Retain the inspected generation until explicit Refresh, and retire released pages.
Source withdrawal must purge both current and pinned evidence before acknowledgement;
failed mandatory cleanup discards derived catalog storage and reports recovery.
Reject stale compound-window reads rather than combine cache revisions. Named
reference opportunities share the resumable passage/target sweep with independent
admission and evidence identity. Persist the title uniqueness index with that
source/model/policy-bound cursor and remove source titles on withdrawal. The lazy
connection collection retains a viewport-adjacent window of at most sixty evidence
rows while preserving explicitly revealed positions and saved scroll/focus on
return. Earlier rows reload when revisited. Small metadata and bounded row counts
alone do not establish the Android memory acceptance gate.

Home is the default resume surface; Discover contains no listening-history or
queue preview. A shared Your interests collection is reached through Home's
bounded card preview and Discover's single View your interests entry, including
when the model is absent. Manual-open recency is separate from semantic inputs:
twenty private identity/timestamp records, five eligible cached Note cards, no
persisted titles/content. Clear it with history/full reset. Before semantic
cleanup acknowledges a source invalidation, cap acknowledgement at the latest
generation durably processed by recent-note history. Late events remain pending
for both consumers. Failed history deletion is visible and retryable, with no
playback consequence.

Distinguish model unavailable/download/error, indexing/paused/ready, incomplete
coverage, no matches and storage exhaustion. Preserve queries, facets, previews,
scroll and focus across Note navigation. Background updates never steal focus,
reorder the inspected list or repeatedly announce result counts. Use real focus
targets, semantic actions, keyboard-operable controls and repository A11y helpers.

JUnit4 tests with destructive hand-written fakes cover chunk/source stability,
tokenizer parity, dimensions/normalization, cosine/ranking/top-K/deduplication,
metadata filters, hashes/reuse, cancellation, reset/revision/eviction races,
atomic interrupted recovery, corruption/model changes, offline retrieval and
playback isolation. Prove regression checks fail for the intended reason.

Run the project verification skill, JVM suite, debug assembly, androidTest
compilation when affected and targeted wrapper/device checks. Pure Compose
semantics belong on JVM where practical. Human TalkBack speech/listening remains
a separate acceptance gate.

## Trustworthy Discovery implementation and acceptance

Product directive from the governing contracts: Discover broadly, validate carefully, personalize explicitly.
The north-star metric is the number of distinct, meaningful, evidence-supported subjects users can successfully discover and explore from their Notes, balanced against incorrect publication, missed topics, membership accuracy, and user usefulness.

The authoritative Product/UI/Integration amendments dated 2026-10-17 supersede
earlier publication minima and capacity-limited terminal sweeps. Historical policy
descriptions in decisions.md do not authorize source truncation
or a three-Note minimum for the sole engine.

- Use the current pinned Gemma INT4 pipeline, exact similarity and one engine across all builds. Form candidates,
  validate relevant body evidence, name specific source-supported subjects, and
  publish separately. Retain uncertain candidates internally. Do not optimize
  topic count, force unrelated material together, or merge on member overlap alone.
- Present established topics (two or more distinct Notes) and Emerging topics
  (exactly one) separately. Emerging labels need literal support in that Note;
  standalone headings remain insufficient evidence. Follow uses the same existing
  confirmation and independent saved-interest definition. Promotion/demotion does
  not itself change identity or saved matching text.
- A substantive discussion under Non-goals, deferred-work or out-of-scope headings
  may establish a subject about that discussion without asserting the capability
  exists. Reference-only material remains insufficient thematic evidence. Named
  reference classification continues to require an explicit citation/instruction
  and unique complete current title; a title mention alone is insufficient.
- Bound each work slice, not total candidate opportunity. Persist versioned progress
  tied to source/model/policy/reset identity. Every eligible section eventually
  receives opportunity; report processed/eligible sections and partial state.
  Pause on resource or storage limits. Rejected writes and cancellation preserve
  the previous valid generation. Resume on eligibility, with unchanged scheduling
  and source-removal/privacy rules. Inspected snapshots change only on Refresh
  except mandatory eligibility removals; only Play changes playback.

### Independent reference retrieval in the sole engine

The live Product/UI/Integration contracts require independent reference retrieval
in the sole engine. Retrieve explicit
instructions/citations naming a unique complete current eligible cached Note title
independently of semantic thresholds, cross-topic membership and thematic prose
admission. Reference lists may provide reference evidence, never topic membership
or Similar passages evidence. Exclude standalone headings, incidental/partial or
ambiguous mentions and negated instructions. Both endpoints must be currently
cached/indexed at their accepted revisions. Show the literal referring passage and
literal target context, explicitly identifying the latter as context rather than
proof of thematic similarity or a verified hyperlink. Keep canonical unordered pair
identities, source invalidation, explicit Refresh and session isolation.
All builds use this same retrieval/publication policy.

### Quality and Android acceptance gates

Human evaluation is developer acceptance work using public, consented or
purpose-built representative Notes. End users need not label, rate or approve
topics, and private-library feedback is optional. Follow/Unfollow expresses user
choice rather than quality validation.

Use a separate synthetic corpus with calibration and held-out queries, hard
paraphrases, misleading keywords, irrelevant Notes and long-document matches.
Calibrate recommendation precision to >=80%; require held-out Recall@5 >=80%.
Report nDCG@5 and keyword baseline. Physical ARM64 measurements include cold
initialization, warm query p50/p95, throughput, native/process memory, storage and
playback contention. Target warm p95 below two seconds at 10,000 passages.
Failed or unmeasured quality/device gates remain explicit; arithmetic tests alone
do not establish semantic quality.

Freeze Note-family calibration/held-out partitions before tuning. Human-reviewed,
provisional and unrated cases are separate populations. Require at least twenty
independently human-reviewed held-out subjects and fifty membership judgments:
false publication <=10%, missed-subject recall >=80%, membership precision >=90%,
and useful specific source-supported labels >=80%. Include emerging, overlapping,
missed, duplicate, misleading-label and reference-only cases; report denominators
and failures. Preserve the separate recommendation precision >=80%, held-out
Recall@5 >=80%, nDCG@5 and keyword-baseline reporting. Counts and silhouette scores
cannot replace these judgments.

Before general release enablement, measure shipping Android complete processing of 10,000
passages across more than 2,000 uneven sections: <=120 seconds of active processing,
<=128 MiB peak additional Java plus native memory over idle model-loaded baseline,
pause/cancellation observed within one second, durable resume with no missing
sections, and <=64 MiB derived storage. Existing warm-query p95 <2 seconds remains
binding. Record cold/warm times, process/native/Java peaks, storage, device/API and
repeated observations. Bulk work pauses during playback; require no new errors,
skips or session mutations attributable to Discovery. Host performance is not
Android evidence. Human TalkBack speech/listening acceptance stays pending until
observed. Failed or unmeasured gates block general release enablement. All builds now use the sole engine; adoption does not mark acceptance passed.

### Current evidence limits

Decision round 72 records the authorized debug trial and replacement verification.
The physical Pixel 8 Pro API37 formation run completed the public 10,000-passage /
2,500-uneven-section repeated-direction workload in about 15 seconds, but measured
about 189 MiB added Java/native memory against the 128 MiB limit. That model-free
synthetic result does not qualify model-loaded memory, varied real-library quality,
heat, playback coexistence or human TalkBack acceptance. Candidate totals and
provisional editorial annotations are not independently verified human judgments.
Keep those failed or unmeasured gates open; see decisions.md for scoped evidence.

## Superseded mechanics

The streaming anchor engine, its 64-anchor admission limits and calibration sweep
are retired. Historical rationale and measurements remain in decisions.md; they
are not executable alternatives or current processing requirements.


## Prepared analysis and authored metadata acceleration

A single coordinator-owned generation retains records, normalized evidence, cluster
sums and current projection state across slices. Its key includes exact source
revisions, metadata signature, model/policy/engine and cache revision. Imported
checkpoints are fully validated; only unchanged process-owned results skip repeated
validation and prefix replay. Orderly query/power/window yields retain completed
work; source/model/reset invalidation remains a hard abort. Completed formation
checkpoints survive interruption before connections. Memory release drops optional
workspace references and interrupts in-flight work without deleting durable progress.

Authored `area` and `topics` organize optional evidence vector balls. Cauchy bounds,
including float-rounding margins, may exclude a bucket only when every member is
provably below the existing threshold. Unlabelled and cross-label evidence remains
eligible; uncertain bounds use exact scores. This changes evaluation cost, not the
publication policy, source coverage, similarity threshold or strongest-passage tie.
A bounded identity+exact-input memo avoids repeated inference; current passages
provide current offsets, source IDs and metadata. Clears advance a generation token
so an older in-flight call cannot repopulate private cached inputs afterward.

Comparison counters describe completed checks over passage opportunities, not a
forecast of Note pairs or a whole-pipeline ETA. INT4 reduces embedding cost but
keeps 768-dimensional comparison vectors. See the architecture review and decisions
for evidence and limits; the exact hierarchy remains quadratic in the worst case.
