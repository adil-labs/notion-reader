# Semantic Discovery Implementation Specification

Hand-authored governing implementation specification subordinate to the Product,
UI, Integration and Session contracts amended in Notion on 2026-10-15.

## Product and surfaces

Discover replaces Home as the default destination alongside Browse, Queues and
Settings. Continue listening appears first as one compact card, followed by search
and discovery cards. Browse owns deterministic Explore and its complete catalog.
Explicit interests are compact navigation cards. Their individual details load up
to 20 matching cached Notes on demand, retain source evidence, and offer a five-Note
listening preview with a descriptive Interest label. Only explicit Play starts its
frozen queue. Cached-seed interests share that route and exclude their seed.
Retain Saved Queue shortcuts. Add semantic search, explicit text/seed-Note interests, related Notes,
older matching Notes and guided listening. Browse keyword search, metadata facets,
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

Candidate: English sentence-transformers/all-MiniLM-L6-v2, Apache-2.0, publisher
ARM64 INT8 ONNX export, 384 dimensions. ONNX Runtime CPU is MIT. Preserve exact
BERT uncased tokenization, attention-mask mean pooling and L2 normalization.
Before production adoption prove actual API26 loading, ABI/native packaging,
licensing, tokenizer/output parity, physical memory and retrieval quality. A failed
gate blocks adoption; do not raise minSdk or substitute a model silently. Record
comparison with LiteRT conversion and multilingual MiniLM.

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

## Index lifecycle and retrieval

Extend cache enumeration without touching LRU timestamps. Observe all puts,
removals, clears, pruning, corruption deletion and internal budget eviction. One
process-scoped worker reconciles on launch/cache/index changes. Bulk indexing
pauses during playback, app backgrounding and resource pressure; resume on next
eligible launch. Do not add a persistent service or force a workspace download.

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

## Automatic topic implementation contract

The detailed design is
[Discovered topics and individual connections](superpowers/specs/2026-10-15-discovered-topics-design.md).
That document records the approved design; the initial implementation and measured
acceptance evidence are recorded in decisions.md section 45. Governing Notion
amendments take precedence.

Use overlapping direct passage-to-anchor memberships, never an exclusive document
partition. Reuse indexed embeddings with at most 64 resident active anchors and
checkpoint work after at most 64 passages. A visible topic requires three distinct
eligible Notes. Version/calibrate topic thresholds separately from recommendation
thresholds. Process source changes incrementally; no unbounded pair matrix or Python
runtime. Grouping and connection work shares the existing single process-scoped
worker and its query/playback/background/memory scheduling policy.

Policy version 5 excludes standalone heading blocks from automatic topic and
connection evidence, using the normalized mapper's section/source-block identity.
Heading embeddings remain available to ordinary semantic search. Each Note may
introduce at most two new candidate anchors in one pass; all body passages still
match retained anchors for independent multi-label assignments. Rotate document
order and long-Note passage opportunity between passes. Persist a bounded candidate
sweep through all body positions plus a final reconciliation pass. Stop once that
sweep is exhausted or all anchor slots contain supported topics; unchanged
capacity-limited sources retain an honest incomplete-coverage status. Source or
policy changes permit a fresh round. Persist the per-Note seeding budget through
mid-document checkpoints. Candidate expiry after two passes is separate from
the full source sweep.
Overlapping windows from the same source block can count as equivalent evidence
within the same reader chunk for consolidation when at least half the shorter span overlaps; common Note
membership alone never merges independent themes.

Quality evaluation must include production-shaped heading chunks and repeated
generic headings. Check the selected representative passage's theme, in addition
to Note-level membership precision/recall. Synthetic quality success does not
establish real-library relevance or the performance gate.

Generic-heading calibration selected admission .65, retention .59 and separation
.75 from a fixed grid using calibration precision >=80%, then maximum recall and
the highest admission tie-breaker. Held-out labels are evaluated afterward.

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

## Accessibility and acceptance

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

Use a separate synthetic corpus with calibration and held-out queries, hard
paraphrases, misleading keywords, irrelevant Notes and long-document matches.
Calibrate recommendation precision to >=80%; require held-out Recall@5 >=80%.
Report nDCG@5 and keyword baseline. Physical ARM64 measurements include cold
initialization, warm query p50/p95, throughput, native/process memory, storage and
playback contention. Target warm p95 below two seconds at 10,000 passages.
Failed or unmeasured quality/device gates remain explicit; arithmetic tests alone
do not establish semantic quality.

Run the project verification skill, JVM suite, debug assembly, androidTest
compilation when affected and targeted wrapper/device checks. Pure Compose
semantics belong on JVM where practical. Human TalkBack speech/listening remains
a separate acceptance gate.

## Home and Discover presentation refinement

The governing navigation contract defines compact recent-session rows on Home, integrated field/IME search on Discover, one representative per topic preview, and ready-update precedence in the single discovery progress state. Generic fallback captions are presentation only: persisted labels, identities, membership, saved interests and frozen listening queues remain unchanged. Full passage evidence remains in details.

Topic detail provenance and distinct anchor contexts are collapsed behind Why these Notes?, with saved expansion and source-control return focus. Representative members are marked inline rather than listed twice. Connections use single-action compact previews with two titles and one excerpt; comparison details show both full excerpts. Generic labels have source-Note attribution resolved from currently eligible members. No ranking, identity or followed-interest policy changes are implied.

## Discovery evidence policy 7

Checkpoint persistence reports validation rejection (with content-free structural reason), budget exhaustion, I/O failure and unknown legacy-adapter failure separately. Only budget exhaustion is described as storage full. Rejected writes preserve the prior valid generation and cancellation propagates. During split/merge reconciliation, reserve continuity identities before assigning fallback candidate IDs; a moved or merged identity cannot also identify its former candidate.

An explicitly requested developer review may export a private snapshot to ignored local build storage, without credentials, model files or listening history. Freeze private held-out cases separately from public calibration and keep human judgments pending until actually supplied. Candidate unshown pairs are not automatically missed useful connections. Host replay timings are not Android performance evidence.

Policy version is persisted independently of EmbeddingIdentity. The grouping source signature includes policy settings; incompatible checkpoints cannot reuse accepted-document flags. Reprocess existing vectors, preserve eligible continuity/interest definitions, and expose replacement snapshots through explicit Refresh. No new content download or embedding regeneration is required by policy changes.

Automatic evidence excludes standalone headings, explicit reference/link-only lists, specification-routing boilerplate and short question-only checklist prompts. Reference headings need explanatory prose (at least ten words with a sentence, or eight words with two sentences, plus an English prose verb); this conservative English-first heuristic may suppress useful short entries. It does not remove text from semantic search, Browse or manual reading.

Near-duplicate neighborhood consolidation requires member/source evidence overlap and compatible anchors. A bounded ordered four-word-window overlap of at least 0.85 recognizes formatting/near-copy duplication across different blocks; differing negation markers reject that shortcut. It is not a semantic equivalence or contradiction detector. Independent passage themes and multi-label membership remain supported. Labels reject generic reference headings and pronoun fragments, retain supported source phrases and explicit source fallback, and preserve opaque identities during improvements.

Connection ranking keeps the existing calibrated cosine floor, canonical pair identities, and score/ID order. Greedy selection caps each Note at two exposures across at most twenty pairs, including during bounded candidate pruning; sparse supported results are acceptable. No novelty/causal explanation is inferred.

Evaluation uses the original disjoint-theme corpus plus a public 29-Note/33-passage hard-negative extension. Report calibration and held-out membership precision/recall separately. Developer tooling may use Python; the Android runtime does not. The actual Pixel sample exposed a checklist/reference false match not represented by the first synthetic extension, now guarded independently. Synthetic quality is not real-library human relevance acceptance.

EmbeddingGemma 2 was assessed as a replacement candidate, not adopted. Pinned LiteRT-LM 0.18.0 lacks ARMv7/x86 libraries; its manifest minSdk24 and 16KiB ELF alignment do not prove API26 runtime loading, tokenizer/prompt parity or Pixel playback contention. The pinned generic converted model advertises 484,622,336 bytes. Default migration remains blocked; current optional MiniLM/ONNX stays installed. See tools/semantic-discovery/gemma2-gate-report.json and the reproducible static probe. No minimum Android/ABI support was changed and no model was silently downloaded or replaced.
