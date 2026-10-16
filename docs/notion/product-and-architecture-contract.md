<!--
  GENERATED FILE - do not edit by hand.
  Exported from Notion by tools/export-notion-doc.ps1.
  Source page: Notion Reader — Product & Architecture Contract
  Page id:     3dec9345-e10a-81ab-aa68-cf0b2ee17e45
  Notion URL:  https://app.notion.com/p/Notion-Reader-Product-Architecture-Contract-3dec9345e10a81abaa68cf0b2ee17e45
  Notion last edited: 10/16/2026 05:33:00
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

Topic labels are grounded in currently indexed titles, headings or phrases with source references. Representative Notes show their matching passage and section as evidence; shared wording or membership is not proof of a conceptual relationship. Cross-topic connections between individual Notes show relevant excerpts from both Notes, including connections to ungrouped Notes, without generated explanations, confidence percentages or chatbot answers. Standalone section headings remain searchable but cannot alone establish topic membership or a Note connection; evidence must come from body passages. Bounded candidate selection must distribute opportunities across Notes instead of allowing one long Note to consume the anchor budget. Discovery quality policies distinguish substantive body evidence from explicit reference lists, link-only/navigation passages and specification-routing boilerplate. Those weak passages remain searchable but cannot independently establish automatic membership or connections. Sparse results are preferable to unsupported groups. Consolidate redundant neighborhoods only when both Note overlap and same/near-duplicate passage evidence support redundancy; preserve independent multi-topic passages. Labels prefer supported complete headings or distinctive bounded literal phrases, reject generic reference headings and pronoun fragments, and retain explicit source fallback when unsupported. Rank supported connections deterministically and bound repeated use of the same Note in the displayed collection. These policies use cached content only and never infer user behavior. Capacity-limited coverage is reported honestly and does not repeatedly rescan unchanged sources without progress.

Your interests are explicit editable text interests, cached seed-Note interests or followed topics. Following a topic creates an independent persisted interest with a user-confirmed name and editable plain-language matching text proposed from its source-backed label and terms. The matching text, not a live cluster, determines future interest recommendations. Its origin topic ID is provenance only. Cluster updates, renaming, splitting, merging, inactivity, eviction or disappearance never silently rewrite or delete that interest. Unfollow removes the interest, not the automatic topic. Existing seed interests retain their cache-bound availability semantics.

Automatic topics have persistent opaque identities independent of labels and list positions. Updates preserve identity when surviving passage/member evidence supports continuity; genuine splits, merges and label-source loss are handled explicitly without arbitrarily recycling identities. A user's inspected topic, visible ordering and focus do not change under background work. A newer snapshot is offered explicitly; removed or revoked content is withdrawn promptly even from a pinned view. Topic navigation never redirects silently to another topic.

Related Notes exclude the seed and do not start playback when opened. Older Notes are matching cached Notes last edited at least 90 days ago; unknown dates are excluded and no unread or forgotten claim is made.

Each explicit interest is an independent entry into matching cached Notes and guided listening. Home presents a bounded interest-card preview. A shared Your interests collection is reachable from Home and Discover; individual matches belong to that interest's details, rather than one pooled feed. A guided listening preview contains at most five recommended Notes from the selected interest, topic, Note or connection, initially in relevance order, with accessible removal and reordering. The seed is not automatically included. Only explicit Play freezes its visible membership and order into a new Active Queue and starts a new session. Preview changes, model/index changes, saved interests and recommendations cannot mutate an active or resumable queue.

Discovery is an additional descriptive queue origin. Persist only its label alongside the ordinary frozen session snapshot, never a live query or connection to interests. Existing session records retain their meaning.

Discovery Add to queue and Add all to queue use the existing Saved Queue destination and identity deduplication rules; they never alter playback or populate a Smart Queue.

Derived vectors, excerpts, automatic content labels, membership evidence and connection evidence remain cache-bound. Temporary eviction or cache clearing removes that content immediately but does not assert remote deletion. A minimal private continuity registry may retain only opaque topic IDs, canonical page/source identities, creation order and unavailable state, with no vectors, titles, excerpts or derived labels from evicted Notes. Recaching revalidates content and may restore topic continuity. Confirmed deletion or permission loss removes the affected page/source identities and all discovery evidence; failed or partial refresh, timeout or absence from Browse never proves deletion. A topic with other valid sources survives; an empty topic is unavailable rather than redirected. User-confirmed interest names and matching text are explicit user data and survive source loss, while any stored source links are removed on confirmed access loss. Index clear removes derived topics, connections and continuity registry and pauses rebuilding; model deletion does the same while preserving explicit interests. Full local-data reset also clears interests. Private content is never sent to an embedding service or logged. No silent model updates or behavioral profiling are permitted.

Semantic extraction and ranking consume reader-domain representations, not Notion DTOs. Domain ports, data adapters and application orchestration preserve existing layer dependencies and process-scoped playback ownership.

