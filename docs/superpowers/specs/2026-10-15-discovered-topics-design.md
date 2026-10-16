# Discovered topics and individual connections

Status: approved and implemented as an initial bounded discovery pipeline.
Governing Notion amendments preceded product code. Actual verification and
remaining acceptance gates are recorded in decisions.md section 45.

## Purpose and agreed constraints

Make Discover a useful knowledge-exploration destination before the user supplies
interests. Discover themes in indexed cached Notes and connect individual Notes
through source passages. Keep automatic Topics in your Notes distinct from
explicit Your interests. Multi-topic Notes must receive independent memberships.
Retain navigation stability, optional local processing and process-scoped playback.
Do not turn Browse into a topic browser or require every Note to join a topic.

The ten requirements in the user request are acceptance requirements. This design
does not introduce behavioral profiling, cloud inference, Python runtime,
generative labels, automatic queue changes or whole-workspace downloads.

## Approach and alternatives

Use overlapping passage neighborhoods with stable source anchors. Each Note can
qualify independently for several topics through different passages. Membership
requires a direct passage-to-anchor similarity match, not a chain of weak links.
Document centroids are insufficient: averaging a Note about two unrelated subjects
can erase both. A Note contributes at most one vote to each topic's distinct count.

A conventional k-means/document partition is rejected because its exclusive
assignment violates the product requirement. A full passage-neighbor graph with
density/community discovery is deferred: it adds quadratic-work risks and complex
split/merge behavior before the core exploration flow is proven. The initial
anchor approach trades some discovery breadth for bounded work and inspectability.
It is an overlapping discovery heuristic, not a claim to recover every true theme.

## Discover and secondary navigation

Order: search and compact Filters; Continue listening; Topics in your Notes;
Connections between Notes; Your interests and interest recommendations; Older
matching Notes; queue shortcuts; deterministic Explore. A submitted search shows
its query/filter summary so edited drafts cannot mislabel old results.

Show at most six topic previews with distinct currently available Note counts and
up to three representative titles. See all topics opens the complete available
topic list. Initial display order uses persisted creation order, not volatile
membership size. Topic details has source-backed label information, Follow topic
or View followed interest, representative Notes, paginated members and Preview
listening. Member rows expose a readable matching excerpt/section and Open Note.
All memberships and examples are revalidated against current cache eligibility.

Add interest opens Describe a topic or Choose a Note. Remove the permanent seed
picker and inline editing form from the main tab. Keep model/network controls,
pause/resume, storage, clear and rebuild in Settings. Discover shows concise
coverage and a contextual setup action when needed.

Topics are optional. Empty topics do not hide search, Browse, individual Related
Notes, existing interests or Explore. Distinguish insufficient eligible Notes,
no supported groups, processing incomplete, disabled discovery and failed work.
Counts refer to distinct available Notes, not passages or the whole workspace.

## Membership, labels and evidence

Every topic stores an opaque topic ID, creation order, anchor source references,
label provenance and versioned membership evidence. Each membership identifies the
Note's best matching passage, anchor passage, model identity and source revisions.
Multiple passages from one Note cannot create a spurious three-Note topic. A
visible automatic topic requires at least three distinct eligible Notes; fewer
survivors keep its identity but make the topic unavailable as a group.

Candidate anchors are actual indexed passages. Prefer meaningful section headings,
then titles, then bounded literal phrases for label candidates. Score labels by
support across distinct members and penalize generic or repeated boilerplate.
Do not synthesize a label from unrelated words. When no trustworthy common phrase
exists, use Notes related to [a literal anchor title] with its source link. A label
is a navigational suggestion, not a generated semantic summary. Disambiguate equal
labels using source context without changing topic IDs.

Representative Notes are selected by direct evidence strength with deterministic
page/source tie-breaking and distinct Note identities. Details says Matching
passage and shows the member excerpt and the anchor context; it does not invent a
sentence claiming why two concepts relate. A multi-topic test Note must show
different relevant evidence in its different groups. Label changes require source
loss, unsupported wording or explicit refresh adopting a justified replacement;
small score fluctuations never rename a topic.

Similarity thresholds for joining, retaining and separating topics are versioned
policy parameters, calibrated on a separate topic corpus. Retention uses a lower
threshold than admission to reduce boundary oscillation. Retrieval's existing
recommendation cutoff is not automatically valid for clustering. Record chosen
parameters and held-out results before enabling the algorithm as production-ready.

## Stable identity, updates and topic lifecycle

Persist identity independently of labels, rank and anchor vector. On a changed
document, remove its old evidence and reevaluate its new passages against retained
anchors; unchanged documents reuse vectors and membership evidence. An unchanged
source anchor preserves identity. If it disappears, choose a surviving member
passage that best preserves the existing neighborhood, with deterministic ties.
Reconcile replacement groups by surviving source/member overlap before creating
new identities. Similarity alone must not recycle an unrelated topic ID.

For a split, the branch with the strongest surviving anchor/member continuity keeps
the old ID and other branches receive new IDs. For a merge, the oldest continuing
ID survives; the other IDs become unavailable records with an explicit replacement
link. A previously opened destination never silently redirects. Followed interests
are neither merged nor split. If no evidence survives temporary eviction, retain
only an opaque continuity record and display Topic currently unavailable without
its former derived title. Recaching those sources allows identity reconciliation.

Each screen pins a completed topic snapshot and its stable item IDs. Background
work offers Topics updated with Refresh; it does not change the inspected ordering,
labels or groups. Refresh preserves the current topic ID/scroll/focus where valid.
Mandatory source invalidations immediately remove ineligible evidence and update
counts, even in a pinned view. Removed targets fall back to a surviving adjacent
control or the section heading. Freeze prepared listening previews separately.

Continuity records have a 1 MiB sub-budget within the total derived budget. They
are not silently evicted: reaching the bound pauses creation of new topic identities
and reports incomplete topic coverage. Index clear is the explicit way to discard
this registry; identity continuity across an explicit index reset is not promised.

## Durable following

Follow topic opens an editable confirmation containing a proposed display name
and plain-language matching text derived from the supported label and terms. Save
creates an ordinary independent explicit interest with its own ID, name, matching
text and optional origin topic ID. Cancel changes nothing. The form explains that
recommendations follow the saved text, not changing group membership.

Never save an anchor vector, source excerpt or hidden seed-content snapshot as a
follow. Query the existing semantic retrieval pipeline using the saved text, so
model replacement re-embeds the user's definition normally. The same origin topic
opens its existing follow; edits preserve interest identity. Cluster rename,
split, merge, absence and source eviction do not rewrite or delete the interest.
Unfollow removes only that interest. If a merge brings two previously followed
topics together, retain both explicit interests until the user removes one.

Confirmed deletion/revocation removes affected source links, not the user-confirmed
name or matching text. These are explicitly saved user data. Index clear and model
deletion preserve them; full local-data reset removes them. Existing text and seed
interest records require backward-compatible decoding and retain their behavior.

## Cross-topic individual connections

Reuse streamed exact cosine retrieval for pairs of individual Notes, including
different-topic and grouped-to-ungrouped pairs. A pair qualifies as cross-topic
when each Note has at least one topic absent from the other, or exactly one Note
is ungrouped. Sharing some topics does not prohibit a connection. Two ungrouped
Notes remain eligible for ordinary Related Notes without a cross-topic claim.

Retrieve by bounded source-passage queries, not an averaged whole-Note vector.
Connection records contain the unordered canonical Note pair, matching source
passages on both sides, score/model/source revisions and current topic context.
Deduplicate the pair regardless of discovery direction. Show Similar passages and
both excerpts; topic difference describes the current grouping only. It does not
claim a new conceptual bridge, causal relation or contradiction.

The initial automatic preview is bounded to three pairs. Each eligible worker batch
checks at most four seed Notes in a checkpointed deterministic round-robin order,
using up to three section-diverse passages per Note and existing bounded top-K
search. Keep up to twenty eligible pair candidates. Expose partial coverage while
the pass is unfinished. Any Note can request its own connections/Related Notes on
demand; absence from the bounded preview is never evidence that it has no links.
Connection details opens either Note and can prepare a two-Note listening preview.

## Android execution and storage

Keep the existing domain/data/application layer rules. Domain owns pure topic
membership, label selection, identity reconciliation, follow definitions and pair
ranking. Add ports for atomic topic snapshots/continuity and typed cache lifecycle
events. Data owns private backup-excluded persistence; application owns orchestration
through the existing single process-scoped indexing worker. Root supplies wiring.

Reuse cached SemanticRecords and their immutable embedding identities; do not
re-embed unchanged passages for grouping. Stream one document at a time against
at most 64 active anchor vectors. New candidates consume vacant anchor slots and
must accumulate three distinct Notes before publication. Overflow remains searchable
and is reported as incomplete topic coverage, not silently assigned elsewhere.
Provisional candidates that fail the three-Note requirement after two completed
passes release their slots. A checkpointed round-robin source cursor advances past
previously considered candidates so early noise cannot permanently starve later
themes. Existing supported topics are not replaced merely to fill a preview.
No all-passage pair matrix, Python runtime, vector database or extra model is added.

Process at most 64 passages between checkpoints, yielding to foreground queries
between documents/passages. Pause during playback, backgrounding and resource
pressure. Checkpoints contain immutable source/model/index generations, completed
document identities and bounded candidate state; vectors in checkpoint state still
obey cache retention. Reject stale completions after reset, eviction or newer content.
Topic builds use staging and atomic completed-generation publication. Source
invalidations take precedence over an in-progress build. Resume eligible unfinished
work without requiring a persistent service.

The existing 64 MiB derived budget includes vectors, topic records, connections,
continuity and staging. Account for peak atomic-write space; pause with an explicit
reason rather than deleting cache content, truncating Notes or losing identity.
Model identity changes invalidate evidence and trigger rebuild; source-identity
continuity may be reconciled without mixing incompatible vectors.

## Cache removal and permanent access loss

Add typed lifecycle reasons: budget/age eviction, explicit cache clear, corruption,
confirmed not-found, permission denied and full reset. Network/partial-refresh
failure is not a destructive event. Retain monotonic generations and an atomic
invalidation path so confirmed access loss is processed even for an already-evicted
Note. CachedDocumentRepository must propagate this reason rather than calling an
undifferentiated remove. Cache snapshots alone cannot reconstruct the distinction.

All cache removals immediately remove passage vectors/text, automatic labels that
depend exclusively on removed sources, memberships and pair evidence. Temporary
removal retains only opaque topic/page/source IDs, creation order and state; it
does not retain private excerpts or topic-content snapshots. Recache revalidates
the source and can restore continuity. Confirmed not-found/denied additionally
purges those page/source IDs and links from the registry and follow provenance.
It does not infer that every Note in the former topic is unavailable.

Cache clear preserves only the continuity registry and explicit interests. Index
clear and model deletion clear that registry as well as automatic derived content
and pause rebuilding. Full reset clears all discovery state and explicit interests.
Preserve existing Saved Queue/session retention: source invalidation does not
silently edit frozen playback or historical queues.

## TalkBack and listening

Use section headings, semantic collections, one named topic navigation stop with
its distinct available Note count, and explicit named Open/Follow/Preview actions.
Evidence is readable non-interactive text. Avoid a required visual graph, horizontal
carousel or drag-only control. Detail lists paginate without losing the current
item identity. Apply the established real-focus-target restoration pattern to
topic, connection, interest and settings controls.

Topic preview listening contains at most five representative Notes in relevance
order; connection preview contains its two Notes in displayed order. Reuse existing
remove/reorder, Play and Saved Queue destination actions. Preview preparation,
following, saving and navigation leave the listening session unchanged. Only Play
freezes the preview into a new Discovery-origin session; restoration never reruns
topics or connections. Source availability changes are reported without silently
reordering the preview or rewriting existing sessions.

## Verification and delivery slices

1. Update governing product/navigation/integration/session sources, regenerate and
   inspect mirrors; align this design and the hand-authored queue/semantic specs.
2. Deliver cache-reason propagation, pure overlapping memberships, stable identity,
   evidence/labels and atomic persistence with failing-first JVM contract tests.
3. Deliver Discover previews, topic details, durable Follow and individual connections
   through the existing preview/play/save flow. Include stable Back/focus behavior.
4. Add bounded incremental checkpoints, resource/budget handling and recovery tests;
   benchmark the complete pipeline and record all unmeasured acceptance gates.

Tests must cover a two-theme Note joining both groups with distinct passage evidence;
three passages from one Note not forming a three-Note topic; ungrouped searchable
Notes; boilerplate/noise; unchanged labels/IDs on reorder and irrelevant additions;
anchor loss, split, merge and recache; follows surviving each change; permanent
revocation after eviction; model/reset/cancellation races; corrupt topic storage;
budget limits; pair deduplication/evidence; playback and history isolation.

Use a dedicated synthetic topic corpus, separate calibration and held-out Notes,
multi-label reference memberships, generic headings and cross-topic pairs. Require
held-out membership and pair precision at least 80%; report multi-label recall,
topic coverage, label-source validity, unrelated-addition churn and split/merge
outcomes. Do not force a topic-count/recall target by weakening relevance. Existing
semantic-search quality gates must continue to pass. Thresholds and measured
limitations belong in the decision record, not confidence percentages in UI.

Run JVM tests, debug assembly, affected androidTest compilation and targeted
repository-wrapper tests for snapshot stability, Back/focus, following, source
invalidation and uninterrupted playback. Measure physical ARM64 clustering time,
incremental edit cost, peak process/native memory, disk peak, warm-query contention
at 10,000 passages and pause/resume. Preserve the existing search p95 under two
seconds target. Human TalkBack speech/listening acceptance remains a separate gate.

## Implementation boundary

The initial application flow now includes automatic topics, durable follows and
paired individual connections. Topic labels use supported literal headings or
shared source phrases with an honest title fallback. Inference stays on device.
Implementation is distinguished from measured quality, device and human acceptance
in decisions.md section 45; no synthetic corpus establishes general usefulness.

## Listening-first layout amendment

The subsequent user-approved layout and governing Notion amendments (decisions §46) supersede the earlier section order: one compact Continue listening card first, then semantic search, Topics in your Notes, individual connections and Your interests. Browse owns deterministic Explore and its complete catalog. Missing models use one setup card; completed newer results remain explicitly refreshable even from an empty inspected generation. These layout changes preserve topic identities, source retention and listening ownership.
