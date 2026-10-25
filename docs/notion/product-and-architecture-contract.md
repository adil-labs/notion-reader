<!--
  GENERATED FILE - do not edit by hand.
  Exported from Notion by tools/export-notion-doc.ps1.
  Source page: Notion Reader — Product & Architecture Contract
  Page id:     3dec9345-e10a-81ab-aa68-cf0b2ee17e45
  Notion URL:  https://app.notion.com/p/Notion-Reader-Product-Architecture-Contract-3dec9345e10a81abaa68cf0b2ee17e45
  Notion last edited: 10/25/2026 15:38:00
  Notion is authoritative. Re-run the exporter after editing there.
-->

# Notion Reader — Product & Architecture Contract

> Governing document, mirrored from Notion. See the header above for provenance.

# Summary

Notion Reader is an Android-first, accessibility-first application for discovering, manually reading, and continuously listening to content stored in Notion.

The application is not intended to reproduce the Notion user interface. It exposes reader-oriented concepts such as Browse, Note, Saved Queue, Smart Queue, Active Queue, and Player.

Organized Notion metadata can improve discovery, filtering, sorting, and queue creation, but arbitrary accessible Notion pages must remain readable even when they do not match a recognized workspace schema.

This document defines the global product terminology, behavioral invariants, architecture boundaries, and cross-feature rules that all downstream implementation specifications must follow.

# Goals
- Provide a simple mental model for reading and listening to Notion content.
- Support manual reading with TalkBack and continuous spoken playback as separate interaction modes.
- Make continuous playback independent of visual navigation.
- Support single-note and multi-note listening through one consistent Active Queue architecture.
- Allow organized workspace metadata to improve discovery without making that organization mandatory.
- Keep playback deterministic and recoverable.
- Separate persistent user-managed queue definitions from ephemeral playback sessions.
- Prevent active reading queues from changing unexpectedly while the user is listening.
- Minimize visible playback actions and avoid exposing implementation complexity to the user.
- Establish stable contracts that downstream implementation specifications and coding agents must not redefine independently.

# Non-goals

This product is not intended to:
- reproduce the complete Notion application;
- provide a full Notion editor;
- require AI for normal reading or navigation;
- require the user's Notion workspace to follow one specific organizational schema;
- treat Notion databases as the primary user-facing navigation model;
- dynamically update an Active Queue while it is being read;
- expose every available playback operation as a prominent button;
- treat visual focus as playback state;
- infer that creation date represents a learning order;
- maintain permanent resume positions for individual Notes independently of listening sessions;
- expose an ambiguous global concept of a Current queue while the user is merely browsing the application.

# Core product concepts

## Browse

Browse is the complete reader-oriented library with keyword search and metadata filtering. Discover is the primary semantic exploration destination.

It may combine content from multiple Notion databases and standalone pages into one normalized discovery experience.

Browse may support:
- search;
- content type;
- Area;
- Topics;
- Date;
- Project relation;
- Status where meaningful;
- sorting.

These properties are filters and discovery dimensions. They must not be presented as a mandatory folder hierarchy.

The Browse experience does not imply that a physical Library database exists in Notion.

## Note

A Note is one readable document.

A Note may come from:
- a recognized organized database;
- an arbitrary Notion database;
- a standalone Notion page.

Opening a Note does not automatically start spoken playback.

The Note screen allows manual inspection and reading using TalkBack.

## Saved Queue

A Saved Queue is a persistent, user-managed ordered collection of specific Notes.

It behaves like a playlist.

The user may:
- create it;
- name it;
- add Notes to it;
- add a Browse result set to it;
- remove Notes;
- reorder Notes;
- rename it;
- delete it;
- play it.

Saved Queues are valid destinations for Add to queue and Add all to queue.

Editing a Saved Queue does not alter an Active Queue that was previously created from it.

## Smart Queue

A Smart Queue is a persistent saved query definition rather than a manually maintained list of Note identities.

A Smart Queue may store:
- content type;
- Area;
- Topics;
- date conditions;
- Project relation;
- Status;
- sort order;
- other supported discovery filters.

Its membership is evaluated from current content when the Smart Queue is opened or played.

Because Smart Queue membership is defined by filters, users do not manually add Notes or result sets to a Smart Queue.

Changes in Notion content may change the Smart Queue's future results, but they must never mutate an Active Queue that was already created from it.

## Active Queue

An Active Queue is an ephemeral, immutable-membership playback snapshot associated with one listening session.

All spoken playback occurs through an Active Queue.

Playing one Note therefore creates an Active Queue containing one item.

Playing a Saved Queue, Smart Queue, or Browse result set creates a new Active Queue snapshot.

Once created, its membership and order remain fixed for that listening session.

An Active Queue is playback state. It is not a general persistent destination for organizing content from Browse.

## Player

The Player represents the active spoken-reading session.

The Player consumes an Active Queue and maintains the current listening position within that session.

The Player is responsible for continuous reading and playback navigation.

Playback must not depend on the current visual screen or accessibility focus.

## Raw Notion navigation

Raw Notion navigation is a secondary escape hatch for navigating content through the underlying Notion structure.

It may expose:

```plain text
Database
→ Pages
→ Note
```

It is not the primary application navigation model.

# Primary product flows

## Discover and play

```plain text
Notion content
      ↓
Browse
      ↓
Note or ordered result set
      ↓
Play / Play all
      ↓
Active Queue snapshot
      ↓
Player
```

## Organize into a persistent queue

```plain text
Note or result set
      ↓
Add to queue / Add all to queue
      ↓
Choose existing Saved Queue
or
Create new Saved Queue
```

## Raw Notion navigation

```plain text
Notion structure
      ↓
Database
      ↓
Page
      ↓
Note
```

The resulting Note then uses the same playback and Saved Queue flows as any other Note.

# Primary action semantics

The primary vocabulary must remain intentionally small.

## Open

Open displays content for manual reading or inspection.

Opening content does not start Reader TTS.

## Play

Play starts a new listening session.

For one Note:

```plain text
Note
→ Active Queue containing one Note
→ Player starts from the beginning
```

For a Saved Queue:

```plain text
Saved Queue
→ snapshot current saved membership and order
→ new Active Queue
→ Player starts
```

For a Smart Queue:

```plain text
Smart Queue
→ evaluate current filters and sort
→ snapshot results
→ new Active Queue
→ Player starts
```

Play must not mean resume.

## Continue

Continue resumes an existing listening session.

It restores the existing Active Queue snapshot and saved playback position.

Resume is therefore a queue/session concept rather than a permanent per-note concept.

## Add to queue

Add to queue operates on persistent Saved Queues.

It opens a Saved Queue destination flow.

The user may:
- choose an existing Saved Queue; or
- create a new Saved Queue and add the Note to it.

The operation must not start or interrupt playback.

Smart Queues are not valid destinations because their membership is query-defined.

If no Saved Queue exists, the UI may go directly to creating one.

## Play all

Play all operates on the currently visible ordered Browse result set.

It:
1. takes the currently visible result set;
1. preserves the visible order;
1. creates a snapshot;
1. creates a new Active Queue;
1. starts a new listening session.

The Active Queue does not continue tracking the original result set after creation.

## Add all to queue

Add all to queue operates on the currently visible Browse result set.

It opens Saved Queue destination selection.

The user may:
- choose an existing Saved Queue; or
- create a new Saved Queue.

It does not modify an Active Queue and does not interrupt playback.

Duplicate Notes must not be inserted more than once into the same Saved Queue when their stable Note/page identity is already present.

Smart Queues are not valid manual-add destinations.

# Queue ownership model

The distinction between queue definitions and playback state is mandatory.

```plain text
Saved Queue
= persistent manually managed Note collection

Smart Queue
= persistent query/filter definition

Active Queue
= frozen playback snapshot
```

The product rule is:

> Saved and Smart Queues define future playback. Active Queues define current or resumable playback.

Another useful rule is:

> Saved Queues are edited. Smart Queues are configured. Active Queues are played.

The application must not expose an ambiguous application-wide Current queue destination from Browse.

If future Player-specific behavior permits modifying an active playback session, that behavior must be explicitly scoped to the Active Queue/Player and must not redefine the global Add to queue action.

# Queue snapshot contract

Every playback source is converted into an Active Queue snapshot before playback begins.

Examples:

```plain text
Single Note
→ snapshot of 1 Note
→ Active Queue
```

```plain text
Browse result set
→ snapshot visible results and order
→ Active Queue
```

```plain text
Saved Queue
→ snapshot current saved membership and order
→ Active Queue
```

```plain text
Smart Queue
→ evaluate query now
→ snapshot matching ordered Notes
→ Active Queue
```

Once the Active Queue exists:
- newly created Notion Notes do not enter it;
- newly matching Smart Queue results do not enter it;
- removing or adding Notes in a Saved Queue does not change it;
- changing Smart Queue filters does not change it;
- changing Browse filters does not change it;
- changing Browse sort does not change it.

The Active Queue may advance through its existing Notes as playback progresses, but its membership and order remain stable.

# Playback invariants

The following rules are mandatory across all downstream specifications.
1. All spoken playback occurs through an Active Queue.
1. Playing one Note creates an Active Queue containing exactly that Note.
1. Play starts a new listening session.
1. Continue resumes an existing listening session.
1. Resume and playback progress belong to the listening session and Active Queue, not permanently to individual Notes.
1. Manual Note reading does not alter Player progress.
1. Active Queue membership and ordering are fixed snapshots for the lifetime of that listening session.
1. New Notion content must not silently appear inside an existing Active Queue.
1. Saved Queue edits must not mutate an Active Queue already created from that Saved Queue.
1. Smart Queue result changes or filter changes must not mutate an Active Queue already created from that Smart Queue.
1. Browse filter or sort changes must not mutate an Active Queue already created from that result set.
1. Play all must preserve the ordering visible to the user at queue creation time.
1. Playback position must not depend on visual focus.
1. Navigating away from the Player must not itself stop playback.
1. An arbitrary readable Notion page must remain playable even when organizational metadata is absent.
1. Add to queue and Add all to queue modify Saved Queues, not an ambiguous playback queue.
1. Smart Queue membership is controlled by its query and cannot be populated through Add actions.
1. Downstream features must not redefine Play, Continue, Saved Queue, Smart Queue, Active Queue, Note, Browse, or Player semantics without first changing this contract.

# Saved Queue contract

A Saved Queue stores:
- a stable queue identity;
- a user-visible name;
- ordered Note identities;
- persistence metadata required by the implementation.

Supported product operations include:

```plain text
Create
Rename
Add Notes
Remove Notes
Reorder
Delete
Play
```

Adding Notes to a Saved Queue must not start playback.

Duplicate handling must use stable Note/page identity rather than title text.

A Note already present in the destination Saved Queue should not be added again unless a future specification intentionally introduces duplicate-entry semantics.

# Smart Queue contract

A Smart Queue stores a query definition rather than fixed Note membership.

Evaluation is conceptually:

```plain text
Smart Queue definition
        ↓
query current accessible content
        ↓
apply filters
        ↓
apply saved sorting
        ↓
ordered result set
```

Playback is then:

```plain text
ordered result set
        ↓
snapshot
        ↓
new Active Queue
```

A Smart Queue may return different results each time it is evaluated.

An Active Queue created from it must remain unchanged for the duration of that listening session.

Individual Notes and Browse result sets cannot be manually added to a Smart Queue.

# Active Queue contract

An Active Queue is created only for playback.

It contains enough information to support deterministic listening and continuation, including at minimum:
- ordered Note identities;
- current Note position;
- current structural position within that Note;
- playback/session state;
- origin information where useful.

Possible origins include:

```plain text
Single Note
Browse result set
Saved Queue
Smart Queue
```

Origin information is descriptive.

It must never cause the Active Queue to re-evaluate or synchronize with its source while playback is active or resumable.

# Session and resume contract

Resume state belongs to a listening session.

The session must be capable of restoring enough state to continue from the previous listening position, including at minimum:
- Active Queue snapshot;
- current Note;
- current structural location;
- current speakable chunk or equivalent stable position;
- finer playback offset where supported.

Continue restores this session.

A new Play action starts a new session instead of implicitly restoring historical per-note progress.

A one-Note playback session follows exactly the same session model as a multi-Note queue.

# Manual Note reading versus Player playback

Manual reading and spoken playback are separate modes.

## Manual reading

The Note screen is navigated using normal Android accessibility semantics and TalkBack.

The user may inspect supported content such as:
- headings;
- paragraphs;
- lists;
- links;
- code;
- structured content.

Moving accessibility focus through the Note must not alter Player progress.

## Player playback

Reader TTS performs continuous spoken playback.

Player state progresses independently from TalkBack focus.

If a future feature provides an explicit action such as Play from here, that action must intentionally transfer the selected structural position into playback state.

Merely focusing content must never do this implicitly.

# Accessibility contract

Accessibility is part of the architecture rather than a later UI enhancement.

TalkBack and Reader TTS have different responsibilities.

```plain text
TalkBack
→ interface controls
→ labels
→ focus
→ manual content exploration
→ UI state announcements

Reader TTS
→ continuous Note content playback
```

Required principles:
- Player TTS must not move TalkBack focus block-by-block during playback.
- Note content must remain usable through manual TalkBack navigation without starting Player TTS.
- Core playback behavior must not require the Player screen to remain visually open.
- Playback must eventually support screen-off and background use.
- Core Player commands must be representable independently of individual UI widgets so Android media controls, accessibility actions, hardware controls, and future voice control can invoke the same behavior.
- Accessibility requirements must appear inside every downstream feature specification where they apply.

# Generic content contract

Recognized workspace organization is an enhancement.

It is not a prerequisite for reading.

Recognized content may provide:

```plain text
Type
Area
Topics
Date
Summary
Project relations
Status
```

These values can improve:
- Browse;
- filtering;
- sorting;
- queue generation;
- previews.

If these properties are missing or the source schema is unknown, the application must fall back to Generic content behavior.

A Generic page must still support:

```plain text
Open Note
Play
Add to queue
ReaderDocument generation
Player playback
```

Missing metadata must never make otherwise readable content unusable.

# Architectural boundaries

The high-level architecture is:

```plain text
Notion API
    ↓
Notion integration layer
    ↓
Normalized Notion content
    ↓
Reader domain
    ├── ReaderDocument
    └── normalized discovery metadata
    ↓
Browse / Saved Queue / Smart Queue / Active Queue / Session domain
    ↓
Player
    ↓
Android TTS and media integration
```

## Boundary rules

Notion API DTOs must not leak into:
- Player logic;
- Queue/session logic;
- Browse domain logic;
- UI state outside the integration boundary.

Android TTS implementation details must not leak into:
- Notion retrieval;
- content normalization;
- ReaderDocument construction;
- discovery metadata.

Workspace-specific database property names must be translated through schema/profile adapters before being consumed by generic reader-domain features.

Saved Queue persistence must not depend on Player implementation details.

Smart Queue persistence must store query semantics rather than resolved playback membership.

Active Queue/session state must not become the authoritative persistent definition of a Saved Queue or Smart Queue.

# UI simplicity contract

The application should expose the smallest useful set of primary actions.

The presence of an internal capability does not require a dedicated visible button.

Primary actions should remain centered around:

```plain text
Open
Play
Continue
Add to queue
Play all
Add all to queue
```

Add to queue and Add all to queue lead to one Saved Queue destination workflow rather than exposing separate buttons for every possible destination.

Advanced operations such as fine-grained chunk navigation, restart behavior, debugging information, or specialized content handling may be exposed contextually or through secondary actions.

The main interface must not become a control panel for every Player capability.

# Navigation-level concepts

The expected top-level product concepts are:

```plain text
Home
Discover
Browse
Queues
Settings
```

Secondary destinations include:

```plain text
Note
Player
Saved Queue details
Smart Queue details
Active session details where needed
Raw Notion navigation
Search/filter configuration where required
Queue destination selection
Queue creation
```

Exact screen structure, Android navigation behavior, Back behavior, mini-player behavior, state restoration, queue-selection interaction, and accessibility focus rules are defined by the separate UI Screens & Navigation Contract.

This document defines only the semantic responsibilities of those destinations.

# Cross-specification contract

All downstream Notion Reader implementation specifications depend on this document.

A downstream specification may add detail, but it must not silently redefine the global semantics defined here.

If implementation work reveals that one of these product-level rules must change:
1. update this Product & Architecture Contract;
1. review the effect on dependent specifications;
1. update affected specifications;
1. only then implement the new semantics.

Coding agents must treat this page as the authoritative product contract when resolving conflicts between implementation specifications.

# Dependencies

None.

This is the root specification for the Notion Reader project.

# Acceptance criteria

This contract is considered complete when:
- the meanings of Browse, Note, Saved Queue, Smart Queue, Active Queue, and Player are unambiguous;
- the difference between persistent queue definitions and playback-session state is explicit;
- Play and Continue have distinct and unambiguous meanings;
- queue/session ownership of resume state is explicit;
- single-note playback and multi-note playback use the same Active Queue architecture;
- Add to queue and Add all to queue have explicit Saved Queue destination semantics;
- no ambiguous application-wide Current queue concept is required;
- Smart Queues cannot be manually populated;
- Active Queue membership and ordering are explicitly frozen for each listening session;
- Saved Queue, Smart Queue, Browse, or Notion changes cannot silently mutate an existing Active Queue;
- manual Note reading and Player playback are explicitly independent;
- Generic Notion content has a functional fallback path;
- accessibility responsibilities between TalkBack and Reader TTS are explicit;
- architecture boundaries prevent Notion API and Android TTS implementation details from leaking across layers;
- downstream specifications have a clear rule for handling conflicts with this contract;
- no unresolved product-level decision is required to implement dependent specifications.

# Explicitly deferred work

This contract intentionally does not define:
- exact Android screen layouts;
- colors, typography, visual design, or themes;
- detailed Android navigation implementation;
- exact Saved Queue destination-picker UI;
- exact queue-creation form;
- whether Active Queues may later support Player-scoped manual modification;
- exact filter UI;
- exact TTS engine implementation;
- precise Notion authentication implementation;
- individual Notion block conversion policies;
- persistence technology;
- local database schema;
- voice recognition;
- cross-device synchronization.

Those decisions belong to their corresponding implementation specifications.

# Open questions

None.

# Home recency contract

Home shows one primary resumable session and up to three other recent listening sessions without duplicating it. These use existing frozen session semantics and queue-origin labels; full history remains in Queues. Recently opened Notes shows up to five successfully opened manual Notes, newest first and deduplicated by canonical identity. Keep at most twenty opaque page references and timestamps privately; resolve titles from currently eligible content, without retaining an evicted-content snapshot. Background loading, playback, prefetch, indexing and previews do not record opens. Clear recent Note references with listening-history clearing and full local reset; confirmed deletion or permission loss removes the reference. Recency is a navigation convenience, never an input to inferred interests, topic grouping or recommendation ranking. Home shows up to three explicit interests in stable saved order, with View all interests. Browse keeps Explore. Tab switching and all manual history navigation preserve process-scoped playback and frozen queues.

# Semantic discovery contract

Notion Reader is an accessible, privacy-first personal knowledge exploration and listening companion. Home is the default top-level destination for a new launch; restoring an existing task preserves its visual destination. Browse retains its existing keyword search, metadata filters, sorts and full accessible library. Home, Discover, Browse, Queues and Settings are the five top-level destinations.

Discover operates only on cached normalized ReaderDocuments and their derived local embeddings. It never implies that uncached workspace content has been searched. Model acquisition is explicit and optional; ordinary reading, Browse, queues and listening remain available without a model.

Discovery is a core knowledge-exploration capability. Topics in your Notes automatically discovers overlapping themes from indexed cached content, independently of Your interests. A Note may belong to several topics through different matching passages, and may belong to none. Topic discovery and individual cross-topic Note connections work without followed interests. Neither topics nor interests are inferred from reading or listening activity. Deterministic Explore belongs to Browse, including its complete Area-and-Topic Explore Catalog and pre-filtered Browse routes. Home prioritizes Continue listening, recent listening sessions, recently opened Notes and compact explicit-interest cards. Discover contains cached semantic search, automatic topics and individual connections, with one View your interests entry; no listening history, Continue listening or queue shortcuts appear in Discover. Model and index management belong to Settings; unavailable semantic features use one concise setup or recovery card rather than repeated empty discovery sections.

Topic labels are grounded in currently indexed titles, headings or phrases with source references. Representative Notes show their matching passage and section as evidence; shared wording or membership is not proof of a conceptual relationship. Cross-topic connections between individual Notes show relevant excerpts from both Notes, including connections to ungrouped Notes, without generated explanations, confidence percentages or chatbot answers. Distinguish Named references from Similar passages: a named reference requires an explicit instruction or citation naming the other eligible cached Note by its unique full current title. A coincident phrase, partial title or similarity score alone does not qualify. This identifies a source reference without asserting a verified Notion hyperlink, conceptual novelty or causal relationship. Named references are retrieved independently of semantic similarity, while Similar passages require eligible thematic body evidence. Standalone section headings remain searchable but cannot alone establish topic membership or a Note connection; evidence must come from body passages. Bounded work slices distribute opportunities across Notes, sections and passages without an anchor-admission or topic-count ceiling. Discovery quality policies distinguish substantive body evidence from explicit reference lists, link-only/navigation passages and specification-routing boilerplate. Those weak passages remain searchable but cannot independently establish automatic membership or Similar passages connections; explicit citations may establish Named references under the independent retrieval requirements below. Sparse results are preferable to unsupported groups. Consolidate redundant neighborhoods only when both Note overlap and same/near-duplicate passage evidence support redundancy; preserve independent multi-topic passages. Labels prefer supported complete headings or distinctive bounded literal phrases and reject generic reference headings, pronoun fragments and incomplete predicate clauses, including negative and modal clauses. Literal repetition does not make a clause a subject. A shared application, project or document-family title prefix alone is not a specific discovered subject; retain its candidate evidence internally while withholding ambiguous family-only labels. A candidate with only a generic source fallback label remains internal rather than becoming a user-facing topic; retain its eligible evidence and opaque identity for reconstruction. Near-identical member families require distinct, independently supported passage themes and subject labels before appearing as separate topics. Membership overlap alone must not merge independently supported multi-topic evidence. Semantic search, individual Note connections and saved interests remain available independently of topic publication. Rank supported connections deterministically and bound repeated use of the same Note in the displayed collection. These policies use cached content only and never infer user behavior. Capacity-limited coverage is reported honestly and does not repeatedly rescan unchanged sources without progress.

Your interests are explicit editable text interests, cached seed-Note interests or followed topics. Following a topic creates an independent persisted interest with a user-confirmed name and editable plain-language matching text proposed from its source-backed label and terms. The matching text, not a live cluster, determines future interest recommendations. Its origin topic ID is provenance only. Cluster updates, renaming, splitting, merging, inactivity, eviction or disappearance never silently rewrite or delete that interest. Unfollow removes the interest, not the automatic topic. Existing seed interests retain their cache-bound availability semantics.

Automatic topics have persistent opaque identities independent of labels and list positions. Updates preserve identity when surviving passage/member evidence supports continuity; genuine splits, merges and label-source loss are handled explicitly without arbitrarily recycling identities. A user's inspected topic, visible ordering and focus do not change under background work. A newer snapshot is offered explicitly; removed or revoked content is withdrawn promptly even from a pinned view. Topic navigation never redirects silently to another topic.

Related Notes exclude the seed and do not start playback when opened. Older Notes are matching cached Notes last edited at least 90 days ago; unknown dates are excluded and no unread or forgotten claim is made.

Each explicit interest is an independent entry into matching cached Notes and guided listening. Home presents a bounded interest-card preview. A shared Your interests collection is reachable from Home and Discover; individual matches belong to that interest's details, rather than one pooled feed. A guided listening preview contains at most five recommended Notes from the selected interest, topic, Note or connection, initially in relevance order, with accessible removal and reordering. The seed is not automatically included. Only explicit Play freezes its visible membership and order into a new Active Queue and starts a new session. Preview changes, model/index changes, saved interests and recommendations cannot mutate an active or resumable queue.

Discovery is an additional descriptive queue origin. Persist only its label alongside the ordinary frozen session snapshot, never a live query or connection to interests. Existing session records retain their meaning.

Discovery Add to queue and Add all to queue use the existing Saved Queue destination and identity deduplication rules; they never alter playback or populate a Smart Queue.

Derived vectors, excerpts, automatic content labels, membership evidence and connection evidence remain cache-bound. Temporary eviction or cache clearing removes that content immediately but does not assert remote deletion. A minimal private continuity registry may retain only opaque topic IDs, canonical page/source identities, creation order and unavailable state, with no vectors, titles, excerpts or derived labels from evicted Notes. Recaching revalidates content and may restore topic continuity. Confirmed deletion or permission loss removes the affected page/source identities and all discovery evidence; failed or partial refresh, timeout or absence from Browse never proves deletion. A topic with other valid sources survives; an empty topic is unavailable rather than redirected. User-confirmed interest names and matching text are explicit user data and survive source loss, while any stored source links are removed on confirmed access loss. Index clear removes derived topics, connections and continuity registry and pauses rebuilding; model deletion does the same while preserving explicit interests. Full local-data reset also clears interests. Private content is never sent to an embedding service or logged. No silent model updates or behavioral profiling are permitted.

Semantic extraction and ranking consume reader-domain representations, not Notion DTOs. Domain ports, data adapters and application orchestration preserve existing layer dependencies and process-scoped playback ownership.

# Automatic topics: formation, publication and acceptance

The sole section-aware hierarchical Discovery engine uses the following formation and publication requirements; non-goal, deferred-work and out-of-scope headings do not blanket-exclude substantive discussion. Discover meaningful broad and focused subjects without a target or maximum topic count. Preserve independently supported narrow subjects and overlapping Note membership; ungrouped passages and Notes remain valid. Candidate formation, evidence validation, source-supported naming and publication are separate decisions. Withhold uncertain or generic labels while retaining eligible internal candidate evidence and identity. Assess subject distinctness before consolidation; shared members alone never justify merging.

Established topics have relevant body evidence from at least two distinct eligible cached Notes. Emerging topics are validated subjects with relevant body evidence from exactly one eligible cached Note; publish them in a separately labelled Emerging topics collection. Apply the same specific source-supported subject and body-evidence requirements, with literal label support from the single Note sufficient for emerging publication. A heading alone, generic keyword collocation or title-family prefix is insufficient. Promotion or demotion between these collections preserves identity when surviving evidence supports continuity. The status is independent of saved interests and candidate identity.

A substantive discussion of a non-goal or deferred capability may support a subject about that discussion; it never proves the capability exists. Reject unsupported capability assertions and reference-only evidence, rather than excluding all prose under a heading. Named references remain distinct from Similar passages and cannot alone establish an independent thematic relationship. Preserve current explicit full-unique-title citation rules and literal passage provenance.

Every eligible section eventually receives candidate opportunity and evidence evaluation. Resource bounds limit a work slice, never silently truncate the source or declare a capacity-limited sweep complete. Persist versioned resumable progress tied to current source/model/policy/reset identity. Cancellation, backgrounding, playback, memory pressure and budget exhaustion pause work honestly; resume from durable progress on eligibility. Report processed versus eligible sections and partial status. Preserve the prior valid published generation on failed writes or cancellation. A private, non-exported Android bound Discovery service owns the single bulk-work coroutine and cancels it on service destruction. The application holds the binding, independently of screens and tasks. All expensive discovery work, including tokenization, inference, formation, publication filtering and connection retrieval, runs off the main thread. The service does not grant permission to process while backgrounded, during playback or under resource pressure: bulk work still pauses and resumes from durable checkpoints. Do not introduce a foreground notification, sticky restart or a second indexing worker. Preserve the existing private derived-storage budget and source-removal rules.

Following either an established or emerging topic opens the existing name and editable matching-text confirmation. Save creates an independent explicit interest; Cancel changes nothing. Existing follows open that interest. Removal through Your interests removes the saved interest, not the discovered subject. Renaming, split, merge, promotion, demotion or source loss never silently changes or deletes confirmed matching text. Background work offers explicit Refresh for an inspected generation; mandatory eligibility removals are immediate. Only explicit Play creates or replaces the listening session.

Quality acceptance is independent of topic count and recommendation retrieval. Freeze calibration and held-out partitions by Note family before tuning. Record actual human judgments, provisional annotations and unrated cases separately; no unreviewed candidate or topic count is quality evidence. On at least twenty independently human-reviewed held-out subjects and fifty membership judgments, require false publication at most ten percent, missed-subject recall at least eighty percent, membership precision at least ninety percent and useful specific source-supported labels at least eighty percent. Include emerging, overlap, duplicate, misleading-label, missed-subject and reference-only cases. Report denominators and errors; insufficient judgments leave acceptance unmeasured. Existing recommendation precision at least eighty percent, held-out Recall@5 at least eighty percent and warm-query p95 below two seconds at ten thousand passages remain separate binding gates.

Android performance acceptance requires measuring shipping Android processing of ten thousand passages across more than two thousand sections, including uneven section sizes: complete eligible coverage within one hundred twenty seconds of active processing and peak additional Java plus native memory at most one hundred twenty-eight MiB over the idle model-loaded baseline. Pause/cancellation must be observed within one second and durable resume must eventually complete without missing sections. Derived storage follows the adaptive local storage policy. Measure cold/warm runtime, process/native/Java peaks, storage and scheduling with playback. Bulk work must pause during playback under the existing scheduling contract; require no new playback errors, skips or session changes attributable to discovery. Report device/API and repeated observations; host timings do not qualify. Human TalkBack speech/listening acceptance remains separate and pending until observed. MiniLM remains the embedding pipeline. Failed or unmeasured gates remain explicit production-readiness failures; they do not authorize retaining or executing a different topic algorithm.

# Independent explicit Note references

In the sole Discovery engine, retrieve explicit Note references independently of semantic similarity thresholds, cross-topic membership and thematic body-evidence admission. An explicit instruction or citation naming the unique complete current title of another eligible cached Note establishes a reference, including instructions in reference lists; a bare coincidental or partial title mention, ambiguous title, standalone heading, or negated instruction does not. Reference-list evidence never independently establishes topic membership or a Similar passages relationship. Both endpoints must remain currently cached and indexed, with source revision and title uniqueness checked. Display the literal referencing passage and a literal target passage as target context; explain that the target excerpt is context, not proof of thematic similarity, agreement or a verified Notion hyperlink. Keep Named references separate from Similar passages in lists, evidence descriptions and evaluation. Preserve unordered canonical pair identities, explicit Refresh, source-removal/privacy rules and session isolation. Independent retrieval is part of the sole Discovery engine in both debug and release builds. There is no engine-selection flag or fallback algorithm. Quality and Android performance acceptance are reported separately.

# Developer evaluation and acceptance reporting

Discovery is automatic. End users are not required to label, rate, validate or approve discovered topics or supporting passages before using it. Follow and Unfollow express a personal choice, not a topic-quality judgment. Human quality evaluation is developer acceptance work on public, consented or purpose-built representative evaluation Notes; it must not require any user to submit or review their private library. Private-library feedback is optional and stays local unless the user explicitly chooses otherwise. Preserve the existing evaluation thresholds and distinguish verified human judgments, provisional annotations and unrated cases. Experimental review exports must begin with an explicit unreviewed state, keep machine suggestions separate from confirmed human verdicts, and require deliberate per-item confirmation; an untouched export must contain zero confirmed judgments. Persisted reviewer decisions must be scoped to a corpus fingerprint and stable candidate identity, never a global browser-storage key plus cluster number. Restored decisions must agree with visible filters and counts. Validate zero-interaction export, one-item confirmation, cross-corpus ID collisions and keyboard/screen-reader review before treating exported data as ground truth. The October 16 Phase 2 HTML prototype is known to preselect decisions and store them under the unscoped key topic_judgments_v2; cross-corpus contamination is a source-code-supported risk, not yet a browser-reproduced incident. This is an evaluation-tooling safeguard, not an Android feature requirement.

All debug and release builds use one section-aware, hierarchical, evidence-backed Discovery engine. Retire the streaming64anchors algorithm, legacy topic builder, engine-selection flag and build property, switching branches and legacy-specific active checkpoints. Historical results may remain as documentation; no legacy algorithm executes, including during migration. The optional pinned MiniLM ONNX pipeline, local privacy, saved interests, source embeddings and listening behavior remain. This architectural decision does not declare quality, memory, playback or human TalkBack acceptance passed; report measured failures and unmeasured gates independently.

# Unified Discovery stages and safe upgrade

The sole engine separates indexing, candidate subject formation, passage-level membership projection, evidence validation, naming, publication, independent connection retrieval and persistence/UI projection. Every eligible cached section and passage eventually receives opportunity, including late subjects and long multi-topic sections. Preserve broad, focused, overlapping and single-Note Emerging subjects without an arbitrary topic-count limit. Validate semantic body membership independently of naming. A specific literal label from a representative eligible source may name a subject without occurring in every member; missing a common literal phrase must not erase valid semantic evidence. Retain valid unnamed candidates and their provenance while withholding unsupported labels. Named references remain independent of similarity; semantic connection opportunities extend beyond the first three sections.

Upgrade recognizes legacy topic/checkpoint formats and removes incompatible derived clustering state without executing the legacy algorithm. Reuse embeddings only when model, tokenizer and current source identities match. Rebuild solely from eligible cached evidence, reconciling opaque topic identity only when genuine surviving passage evidence supports continuity; never retain misleading membership merely to preserve an ID. Previously hierarchical progress resumes only when source/model/policy/progress invariants match, otherwise restart safely without skipped evidence. Migration and replacement publication must recover idempotently from interruption, corruption and insufficient storage and expose only atomic consistent generations.

Explicitly saved interest IDs, names and matching text survive derived-state migration, including interests whose old origin topic becomes unavailable. Preserve saved listening state, queues and unrelated settings. Quality acceptance still requires independent held-out judgments under the existing recall, precision, false-publication and supported-label targets. Android acceptance still requires at least ten thousand passages across more than two thousand uneven sections, at most one hundred twenty seconds active topic processing, at most one hundred twenty-eight MiB added Java/native memory over model-loaded idle, cancellation within one second, complete durable resume, combined derived storage governed by the adaptive local storage policy, warm-search p95 below two seconds and no attributable playback failure or state mutation. Diverse representative workloads are required; repeated identical-vector synthetic workloads alone cannot establish these gates.

# Subject hierarchy and complete connection catalog

Discovery retains MiniLM and the sole existing formation engine. Major subjects and specific subtopics are first-class evidence-backed subjects connected by a directed acyclic graph, with multiple parents and variable depth allowed. Related-subject links remain distinct from parent-child edges. Every specific subject remains directly accessible; uncertain or unsupported parents never hide a valid child.

Publish a parent-child edge only when current eligible substantive body evidence explicitly states that the child subject is a subtopic, type, kind or part of the parent subject, with both source-supported subject labels identified literally and unambiguously. Authored heading ancestry may propose candidates but cannot establish an edge by itself. Similarity, member overlap, shared title prefixes and generic headings are not semantic subsumption. Preserve the literal relationship passage, canonical Note identity, section and revision; invalidate an edge if either subject or its supporting source becomes ineligible. Reject self-links and cycles. This conservative explicit-evidence policy is the initial hierarchy implementation; missing supported broader evidence leaves a specific subject at the root rather than inventing an umbrella label.

Each Note-subject membership uses its strongest eligible substantive body passage across the library with current section provenance and revision, ranked deterministically. Section/title context clarifies meaning but does not independently qualify unrelated body text. Parent membership is evaluated independently and never inherited from child membership.

Retrieve Similar passages across all eligible Notes independently of their topic memberships, including equal, overlapping, subset and empty topic sets. Keep independent Named references under their existing evidence rules. Retain the strongest validated paired excerpts per unordered canonical Note pair and evidence kind; neither kind erases the other. Candidate and catalog retention have no homepage count, per-Note exposure or per-passage top-k limit. Use display diversity limits only when projecting previews.

Persist a complete pageable catalog of validated connections with versioned resumable candidate progress. Complete means every eligible candidate opportunity was evaluated under the declared policy, not every possible relationship was semantically proven. Higher semantic evidence thresholds are calibrated on developer calibration data, separately from explicit-reference retrieval, and must not be tuned against held-out judgments. Storage/resource exhaustion preserves the previous valid generation and reports incomplete coverage; never silently truncate the catalog or report pruned old progress as complete.

Evaluate hierarchy subsumption and useful connections independently using actual held-out human judgments, with false-positive and missed-edge counts and evidence-kind denominators; unmeasured results remain unmeasured. The 500-Note developer evaluation uses public, consented or purpose-built material, frozen family partitions and explicit human/provisional/unrated provenance. Compare formation methods on identical MiniLM vectors before benchmarking embedding challengers on the selected pipeline. No production model replacement or auxiliary model is authorized by this amendment. Existing Android, privacy, playback and quality gates remain binding.

# User-requested Discovery power eligibility and time estimates

A longer local embedding runtime is acceptable when it provides independently demonstrated better discovery quality. Do not reject a candidate solely for slower processing. Model replacement still requires explicit acquisition, model/tokenizer identity migration, supported Android execution, privacy, memory/storage, cancellation, playback and independent quality verification; host benchmark speed is not a production acceptance verdict.

Model download, Resume indexing and Rebuild index are explicit requests to prepare cached Notes under the existing indexing lifecycle; Pause and index/model clearing retain their existing meanings. Bulk Discovery work may run only while the device is connected to external power or its reported battery level is at least thirty percent. Unknown battery status without external power is ineligible. Battery/power changes pause and resume the existing single worker from compatible checkpoints; they do not create another scheduler, foreground service, sticky restart or hidden model download. Existing foreground, playback and resource-pressure pauses remain mandatory. Power deferral never delays mandatory source withdrawal or prevents reading, search, listening or inspection of previous valid discoveries.

Discover and Discovery settings show cached/indexed Note coverage, a clear waiting-for-power reason and a coarse estimated remaining embedding-time range derived from the active model and actual device inference throughput over remaining non-reused passages. Exclude pauses and foreground-query waits from throughput observations, account for reusable compatible embeddings and reset estimates when model/source/reset work changes. Show estimating on this device until enough measurements exist. Separate embedding time from subsequent subject and connection analysis: when those durations cannot be estimated reliably, say they are additional and still being estimated rather than claim an embedding-only estimate is the total completion time. Estimates are approximate active processing time, not deadlines; they may change with Note length, temperature, load or changed cached content. Progress changes must not steal focus or produce constant TalkBack announcements.

# User-requested EmbeddingGemma 2 INT8 Android integration

The user requests integration and device verification of EmbeddingGemma 2 INT8 as the optional local Discovery embedding pipeline. This amendment supersedes earlier requirements to retain MiniLM and earlier statements that model replacement is not authorized. Retain the sole existing section-aware formation engine, process-owned worker and evidence-backed subject hierarchy and connection catalog. Host graph benchmark results do not establish shipping-engine quality. Independent release acceptance, privacy, source withdrawal, playback and human TalkBack gates remain binding and must be reported separately from successful integration.

Acquire the model only through the existing explicit optional download flow. Pin the onnx-community/embeddinggemma-2-ONNX export revision daa72c51243991dfcaf9f9137d2c573d8f7790c0 and verify the INT8 graph, external weights and tokenizer assets before atomic installation. Use the exported sentence_embedding output at 768 dimensions, text-only inputs and local ONNX execution. Include model, tokenizer, instruction, dimension and preprocessing identities in compatibility checks. Never reuse MiniLM vectors or incompatible derived checkpoints. Preserve explicit interests, queues, listening state and unrelated settings during migration; never silently update or send private content to a service.

Tokenization must match the pinned reference including special tokens, Unicode, whitespace, byte fallback and source offsets. Account for task instruction and special-token overhead when forming passage inputs; never silently truncate source evidence. Validate the actual Android provider against fixed host token and vector references. Calibrate search, membership and connection decisions for the new embedding space using the shipping pipeline; do not copy MiniLM similarity thresholds or present similarity as confidence. Device verification must establish actual model execution, indexed source coverage, published supported subjects, paired connections, search and navigation using inspectable public or purpose-built evidence, without replacing private device content.

Discover and Settings identify EmbeddingGemma 2 INT8 and its complete download size and acquisition state. Expose loading, indexing, power waiting, errors, partial coverage and approximate device-derived embedding-time estimates truthfully through existing accessible surfaces. Preserve existing foreground, playback, resource-pressure and thirty-percent-or-connected power eligibility. Keep progress passive and Back/focus restoration stable. A successful small device journey does not establish representative release quality, full-scale memory/time/storage or human speech acceptance.

# Approved automatic Discovery scheduling and effort settings

This approved automatic Discovery amendment supersedes earlier foreground-only bulk-work restrictions, bound-service-exclusive worker ownership, and requirements to press Continue at work-slice boundaries. After explicit setup and verified optional model acquisition, cached-Note embeddings, topics and connections are processed automatically without further user interaction. Work may run with the screen off or app task closed during Android-granted execution windows. Work remains deferred by Android scheduling restrictions; force-stop requires reopening. No uninterrupted execution or completion deadline is promised.

A process-owned coordinator serializes all foreground and persistent WorkManager requests through one bulk execution gate and the same Discovery engine, stores and source/model/policy/reset checkpoints. WorkManager grants bounded ordinary non-expedited execution windows and recovery across ordinary process death and reboot. Replace the former bound-service-only ownership deliberately; do not retain a competing infinite loop, second inference worker, permanent foreground service, notification or sticky restart. Expensive work remains off the main thread. Foreground queries retain priority; playback and resource pressure suspend bulk work. Source withdrawal and access-loss handling remain mandatory while bulk work is deferred.

Discovery effort is persisted in Settings with Balanced as the default and Charging only as the alternative. Balanced permits bulk work on external power or known battery of at least thirty percent; unknown battery without external power waits. Charging only permits bulk work exclusively on external power. Effort controls execution availability, never evidence quality, similarity thresholds, eligible-source coverage or an artificial topic-count limit. Source/model changes, setup completion and restored eligibility arrange work automatically; unfinished slices resume without a new edit or Continue action.

Explicit user Pause remains persisted and requires explicit Resume. Index clear and model deletion retain their rebuilding-disabled semantics; Resume re-enables automatic work, and model acquisition remains explicit. Reset/model deletion cancel queued work and invalidate stale commits. Existing private storage budgets, source eligibility, cache-only inference, inspection pinning, independent interests, frozen listening queues and privacy rules remain binding. No automatic remote-library download or silent model update is introduced.

Discover and Settings distinguish processing, waiting for power, waiting for an Android execution window, explicit user pause and incomplete storage coverage. Remove Continue discovery for ordinary unfinished work. Refresh only adopts a new consistent generation in an already inspected view and never starts or completes processing. Keep indexed versus eligible Note and processed versus eligible section coverage visible. Show approximate measured remaining embedding time separately from still-unestimated topic/connection analysis; exclude OS waits and other pauses from active processing estimates. Status changes never steal focus or create constant TalkBack announcements.

# User-requested EmbeddingGemma 2 INT4 default

The user requests that EmbeddingGemma 2 INT4 become the default optional local Discovery model, prioritizing measured local inference speed and the smaller pack. This explicitly supersedes the INT8 precision/default requirement in the prior Gemma integration amendment. Keep local ONNX Runtime, the text-only 768-dimensional sentence_embedding output, instruction/tokenizer/source evidence semantics, the sole section-aware exact formation engine and automatic process-owned background work. Benchmark quality remains publisher-label calibration rather than human release acceptance.

Pin onnx-community/embeddinggemma-2-ONNX revision daa72c51243991dfcaf9f9137d2c573d8f7790c0. Select model_q4.onnx (490742 bytes, SHA256 f9eeba97acddf139b8ee2ddf04bc30dceafa88de93fadf74d7644e0d61a477a9) and model_q4.onnx_data (174028800 bytes, SHA256 c3975f2d1ab7a1878ae31a7d7a9b7804a827aff3800b60dfceafce21cac3df49), with the existing verified tokenizer/config assets. The complete pack is 206696682 bytes, displayed approximately as 207 MB. Give INT4 a precision-and-artifact-specific identity; reject INT8 vectors and derived checkpoints rather than mixing embedding spaces.

Existing optional acquisition and explicit download consent remain binding. Recognize an old INT8 installation as incompatible without deleting its bytes merely during startup validation or silently downloading a replacement. A successful explicitly requested verified installation may atomically replace the old model. Preserve Notes, explicit interests, Saved Queues, listening state and unrelated settings; only incompatible derived Discovery state is eligible for normal identity invalidation. Do not migrate private phone content as part of developer verification.

Discover and Settings identify INT4 and its actual complete pack size. Revalidate the shipping Android provider against pinned INT4 host vector references and retain INT8 benchmark fixtures under their original identities. Quantization can change membership outcomes; document INT4 policy calibration and independent quality limits. Comparison progress must describe completed passage/section-level work truthfully rather than imply a forecast or a Note-pair count. Preserve automatic resumable processing, exact source coverage, accessibility and existing power/playback/privacy gates.

# Adaptive local storage

Note cache capacity defaults to 128 MiB for new and legacy settings without an explicit size choice, targeting one hundred Notes of up to one MiB encoded size each, subject to actual device capacity. Preserve explicit user choices and the existing automatic-download defaults; offer one hundred Notes as an additional count choice. Retain the two-hundred-entry resource guard initially and report it separately from the byte limit. Note count alone is never a guarantee for arbitrarily large content.

Discovery storage defaults to Automatic and grows only as eligible cached content is indexed, without preallocation or a fixed sixty-four MiB ceiling. Sixty-four MiB remains a historical benchmark reference. An advanced Custom maximum defaults to 256 MiB when selected. Measure actual serialized bytes, including passage text, 768-dimensional float32 vectors, reusable progress, topics, connection catalogs, continuity, checkpoints and overlapping staging. Model weights use a separate displayed category; model quantization does not quantize persisted vectors. Preserve separate memory, continuity and processing bounds, reporting incomplete coverage when reached. Preserve a separate per-record memory guard: semantic record headers at most eight MiB and semantic/topic envelopes at most sixty-four MiB each. Refuse oversized new records with an accurate resource-limit/incomplete-coverage explanation, preserve the prior valid generation and validate lengths before allocating when reading. These are per-record memory safeguards, never a library-wide storage ceiling.

All growth respects the actual private-storage volume: leave at least the greater of two GiB or ten percent of total capacity free, and recheck before significant writes. Unknown capacity defers growth. Serialize admissions across stores and account for old plus replacement generations and model download staging. Normal storage controls offer up to two GiB per category; an explicit extended-storage choice allows up to eighty percent of managed bytes plus free bytes above the reserve, dynamically constrained by physical free space. The eighty-percent value is a ceiling, never a usage target. Persist byte choices; changing free space does not silently change preferences.

On capacity exhaustion pause additional indexing or downloads and retain eligible valid generations; distinguish storage, validation and I/O failures. Capacity recovery retries permitted unfinished work but never overrides explicit Pause, index clear or model deletion. Compact stale derived data and abandoned staging only when no active writer or valid generation references it. Source eviction still removes dependent evidence under existing privacy rules; capacity pressure does not authorize retaining evidence for unavailable sources. Keep persistent indexes outside disposable cacheDir.

Evict speculative prefetch Notes before least recently used unprotected Notes. Protect the current and upcoming active-queue Notes through writes and pruning. Reducing a limit explains offline removal before confirmation; defer cleanup where protected content exceeds a limit and explain the overage. Never delete saved queues, interests, listening history or listening snapshots to satisfy cache limits. Increasing capacity never downloads the whole library or expands cache-only Discovery eligibility.

Settings expose actual Note-cache use, byte limit and entry guard; Discovery shows Automatic or Custom maximum, actual derived use, model download size and incomplete coverage truthfully. Sliders provide meaningful size labels and accessibility adjustment actions and persist after adjustment. Explain local automatic growth at setup and once to existing users; ordinary growth needs no repeated confirmation. Storage eligibility supplements existing power, scheduling, playback and resource restrictions; it never authorizes unlimited processing. WorkManager storage-not-low is a coarse guard alongside authoritative write-time checks.

# User-requested LiteRT runtime migration

The user directs replacement of the existing ONNX runtime path with Google LiteRT and LiteRT-LM, preserving the EmbeddingGemma 2 model family, text-only Discovery role, INT4 precision and normalized 768-dimensional embeddings. This supersedes earlier ONNX-runtime and ONNX-file requirements and four-ABI compatibility gates. Supported app ABIs become arm64-v8a and x86_64; minimum Android API remains 26. Remove the legacy MiniLM runtime path rather than retaining a competing production engine.

Use LiteRT-LM 0.18.1 and its LiteRT execution engine. Pin litert-community/embeddinggemma-2-text-270m-litert-lm revision 9be6e8b90982095dc05c2bd162e4b954ee4dbac7, file embeddinggemma-2-text-270m.litertlm, 164626432 bytes, SHA256 2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb. Preserve verified external tokenizer assets for source-offset chunking and test their agreement with the bundled tokenizer. Preserve existing clustering instruction semantics unless measured export requirements require a separately versioned preprocessing change. Runtime/export changes have a distinct embedding identity; never mix previous vectors or checkpoints with new ones.

Acquisition remains explicit, checksum-verified and atomic. An old ONNX installation is incompatible but startup validation must not delete it or download a replacement. Preserve Notes, explicit interests, Saved Queues, listening state and unrelated settings. Retain process ownership, one serialized worker, source provenance, storage, power, playback, accessibility and privacy rules. Validate real CPU inference on the x86_64 emulator, tokenizer/source offsets, finite normalized dimensions, meaningful public-input similarities and old-reference differences. Emulator evidence does not establish ARM64 performance, representative quality, API26 native execution or human listening acceptance. Accelerated inference requires separate correctness evidence and safe CPU fallback.

Use only the prebuilt models published in the official LiteRT community repositories. Do not locally convert or export model weights or graphs for LiteRT or LiteRT-LM. The source-offset tokenizer may be acquired separately from the pinned official google/embeddinggemma-2 repository; include it in the verified total pack size.

# LiteRT Android implementation refinement

Follow the official LiteRT-LM Android embedding API: a pinned published .litertlm bundle, background EmbeddingEngine initialization, serialized computeEmbedding calls and deterministic close/reopen. Use the existing EmbeddingGemma 2 Text 270M INT4 bundle without conversion or export. It contains the authoritative SentencePiece tokenizer. Source chunking, context budgeting and inference must all use that tokenizer; do not retain a separate HF tokenizer download or emulate its symbol rules.

The Kotlin embedding API does not expose source offsets. A small native bridge may read the unchanged SentencePiece section directly from the verified bundle into memory, run Google's SentencePiece processor and convert its UTF-8 byte offsets into Kotlin UTF-16 offsets. Pin the section location and digest to the immutable bundle identity; validate bounds and reject malformed input. No model or tokenizer conversion/export is performed. The complete pack is the single 164626432-byte published bundle, displayed approximately as 165 MB.

Use the documented clustering prefix task: clustering | text: with a space before trimmed input. Version preprocessing and tokenizer identity for this change, and rebuild incompatible derived state. Keep the existing sole exact Discovery engine, optional explicit atomic acquisition, source evidence, saved interests, listening state and resource/privacy rules. CPU remains the measured emulator baseline; a separate generic LiteRT interpreter or legacy ONNX provider is unnecessary. Historical ONNX benchmark sources and results may remain outside shipping application source sets. Public emulator tests must cover real acquisition, source offsets including Unicode and literal special strings, exact context budgeting, finite normalized embeddings and the Discovery journey. ARM64/API26 runtime, representative quality, peak memory and human speech acceptance remain separate gates.

# Discovery execution backend selection

Settings offer a persisted Automatic (default), CPU, GPU or NPU preference for on-device Discovery. Show the requested preference separately from the effective backend and any unavailable/fallback reason. Selection does not download anything, change listening state, discard interests, or clear compatible derived discoveries. Changes take effect at a serialized inference boundary; native work stays off the UI thread and old engines close before replacement.

Automatic compares CPU and available validated accelerators on fixed public short and longer inputs, using median warm execution time, and selects the faster validated backend for this process and preference. This is a bounded device-local comparison, not a universal throughput or battery claim. Explicit accelerator choices are best effort with truthful CPU fallback on unsupported initialization, invalid outputs or execution failure. Validate accelerated embeddings against CPU on fixed public probes before admitting them to the existing embedding identity; reject incompatible outputs. Avoid retry loops for an accelerator rejected during the current selection. CPU failure remains a visible failure, not fabricated successful output.

Use the official LiteRT-LM Android backend APIs and optional GPU native-library declarations. NPU execution requires the published matching-SoC model plus compatible vendor runtime/dispatch libraries; hardware presence alone is insufficient. Do not claim NPU execution when these prerequisites are absent. Additional model/runtime acquisition must remain explicit and verified, never triggered silently by selecting a preference. Do not convert or export models. Keep one serialized process-owned execution path and the existing resource/privacy limits. Emulator evidence does not establish S25 accelerator execution.

# Bounded accelerator context and optional S25 NPU pack

Accelerator execution is limited to at most 512 instructed tokens to avoid allocating all 8192-token GPU signatures. Longer valid inputs retain the 8192-token CPU path; explain the effective per-input fallback without truncating input or changing source chunking. Keep one live engine, and close it before switching input backends. Accelerator timings cover only the supported bounded context.

For SM8750 devices, Settings may explicitly acquire the additional official embeddinggemma-2-text-270m_Qualcomm_SM8750.litertlm artifact from the same pinned repository/revision: 187596800 bytes, SHA256 b5615709768db4ca4fb4f04514e7e41b0680ccb12dd5f90371193f7fd12e7c99. Show the additional approximately 188 MB, separate download/cancel/delete controls and Wi-Fi-only policy. Preserve the base bundle for CPU/GPU fallback and authoritative source tokenization. Reuse atomic checksum verification and managed-storage admission, with no implicit download. Package official Qualcomm dispatch and QAIRT runtime dependencies; validate actual execution before reuse of the embedding space. Published CPU and NPU tokenizer and embedding metadata sections must agree. Selecting NPU on other chipsets explains the absence of a supported published pack and falls back to CPU. Pixel 8 Pro Tensor G3 has no matching published NPU artifact in the pinned inventory, so evaluate CPU/GPU there without conversion.

