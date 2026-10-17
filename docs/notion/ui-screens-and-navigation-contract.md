<!--
  GENERATED FILE - do not edit by hand.
  Exported from Notion by tools/export-notion-doc.ps1.
  Source page: Notion Reader — UI Screens & Navigation Contract
  Page id:     3dec9345-e10a-81b6-9cd2-d54532ad6fcd
  Notion URL:  https://app.notion.com/p/Notion-Reader-UI-Screens-Navigation-Contract-3dec9345e10a81b69cd2d54532ad6fcd
  Notion last edited: 10/17/2026 06:12:00
  Notion is authoritative. Re-run the exporter after editing there.
-->

# Notion Reader — UI Screens & Navigation Contract

> Governing document, mirrored from Notion. See the header above for provenance.

# Summary

This specification defines the user-facing screen structure, navigation model, screen responsibilities, Android Back behavior, playback surfaces, state restoration, queue navigation, and accessibility-navigation requirements for Notion Reader.

It does not define how Notion content is fetched, normalized, persisted, or spoken internally. Those details belong to their corresponding implementation specifications.

This specification depends on Notion Reader — Product & Architecture Contract.

Terms such as Browse, Note, Saved Queue, Smart Queue, Active Queue, Player, Play, Continue, Add to queue, and Play all use the meanings defined by that contract.

# Goals
- Keep top-level navigation small and predictable.
- Make continuing previously paused listening fast.
- Provide useful Discover discovery without duplicating Browse.
- Support manual Note reading independently from spoken playback.
- Make Browse powerful without recreating folders or Notion database navigation.
- Clearly distinguish listening history, Saved Queues, and Smart Queues.
- Keep persistent queue management separate from frozen playback sessions.
- Preserve Browse context when opening Notes or Player.
- Make Android Back behavior deterministic.
- Keep TalkBack focus movement efficient and predictable.
- Avoid requiring drag-and-drop or visual-only interaction.
- Keep playback available while navigating elsewhere in the application.
- Support arbitrary readable Notion pages in addition to organized workspace content.

# Non-goals

This specification does not define:
- colors, typography, spacing, branding, or visual themes;
- exact Jetpack Compose components;
- Notion API implementation;
- ReaderDocument parsing and chunking;
- persistence technology;
- TTS-engine implementation;
- Android media-session implementation;
- detailed Saved Queue persistence;
- detailed Smart Queue query execution;
- voice recognition;
- recommendation models.

# Top-level navigation

The application has five top-level destinations:

```plain text
Home
Discover
Browse
Queues
Settings
```

These should use a persistent Android top-level navigation mechanism appropriate for the device form factor.

On standard phone layouts, bottom navigation is expected.

The Player is a secondary destination and is not a bottom-navigation destination.

The Note screen is not a top-level destination.

Both are secondary screens reached from the primary navigation surfaces.

# Global navigation structure

```plain text
App
│
├── Home
│   ├── Continue listening and Recent listening → Player
│   ├── Recently opened Notes → Note
│   └── Your interests → interest details / shared interests collection
│
├── Discover
│   ├── Search cached Notes, topics and connections → source-linked Notes
│   └── View your interests → shared interests collection
│
├── Browse
│   ├── Explore → complete Explore Catalog → pre-filtered Browse
│   ├── Search
│   ├── Filters
│   ├── Sorting
│   ├── Result → Note
│   ├── Play all → Player
│   ├── Add all to queue → Queue Destination
│   └── Browse Notion structure → Raw Notion Navigation
│
├── Queues
│   ├── Listening
│   │   ├── Continue listening
│   │   └── Recently completed
│   ├── Saved
│   │   └── Saved Queue Details
│   └── Smart
│       └── Smart Queue Details
│
└── Settings

Secondary screens:

Explore Catalog
Note
Player
Queue Destination
Create Saved Queue
Saved Queue Details
Edit Saved Queue
Smart Queue Details
Edit Smart Queue
Raw Notion Navigation
Raw Notion Database Pages
```

# Global interaction principles

## Navigation does not own playback

Moving between application screens must not itself:
- pause playback;
- stop playback;
- move to another Note;
- move to another section;
- modify Active Queue membership;
- modify playback progress.

Playback state belongs to the listening session.

## Opening content does not imply playback

Opening a Note means:

```plain text
Open Note
→ show Note screen
→ manual TalkBack reading available
→ existing playback remains unchanged
```

The Note screen must never automatically start Reader TTS merely because it was opened.

## Playback may outlive the Player screen

Leaving Player does not stop playback.

Example:

```plain text
Browse
→ Play all
→ Player
→ Back
→ Browse

Playback continues.
```

The mini-player represents the existing session elsewhere in the app.

# Application shell and mini-player

When a playing or resumable Active Queue session exists, the app should expose a compact playback surface outside the full Player screen.

Conceptually:

```plain text
Browse

Search
Filters
Results
...

────────────────────
Current Note title
Playing
[Pause]
```

The mini-player reflects the existing Player session.

It is not a second playback engine or second playback state.

## Mini-player contents

Required information:
- current Note title;
- Playing or Paused state.

Required direct control:
- Play/Pause.

Activating the rest of the mini-player opens Player.

The mini-player must remain deliberately compact.

It should not expose:
- queue editing;
- detailed section navigation;
- filtering;
- large groups of playback controls.

If no resumable session exists, the mini-player is absent.

# Home screen

Home is the default destination for a new launch. Restore an existing task's destination without forcing Home. Back from other top-level destinations returns Home; Back from Home follows system behavior. Home contains, in order: one compact Continue listening card; up to three other compact Recent listening session rows; up to five Recently opened Notes; and up to three Your interests cards in stable saved order with View all interests. Empty sections use concise useful actions rather than blank lists. Listening cards use the existing Continue/replay commands and frozen snapshots. The full history remains in Queues → Listening. Recent Note cards open manual reading without changing playback. Only successful explicit opens count; failed, canceled, superseded or background loads do not. Page references and timestamps are bounded to twenty private records, resolved against current eligible content and cleared with history/full reset. An evicted Note has no retained title or content snapshot. Confirmed deletion or denied permission removes its reference. These recents never influence semantic ranking or interest discovery.

# Discover screen

Discover focuses on Search your cached Notes with facets and indexed coverage, Topics in your Notes, Connections between Notes, and one View your interests entry. It contains no listening history, Continue listening, recent queue list or expanded interest collection. Matching and older Notes belong in individual interest/topic/Note details. Existing mini-player behavior remains independently governed; removing the listening history section does not stop background playback. Use an integrated accessible Search action in the search field and the keyboard Search action; do not show a separate disabled Search button for an empty query. Keep indexed cached-content coverage visible and Filters compact. Discovery progress has one primary state: ready updates take precedence over background updating text and expose an explicit Refresh action, without silently changing the inspected snapshot. Topic previews show a concise source-backed label, distinct Note count and one representative Note; the full evidence and member list remain in details. Generic or long fallback labels may use an honest Similar Notes or Related Notes presentation, without rewriting stored labels, identities, memberships or followed interests. Home keeps its primary Continue card, uses compact vertical rows for other sessions with queue/interest names distinguishing them, and provides a brief recent-Note empty state with access to Browse. Use a compass for Discover. Preserve section headings, real focus targets, stable Back behavior, scalable text and accessible vertical navigation.

# Shared Your interests screen

Home View all interests and Discover View your interests open the same secondary collection, preserving their own source focus and scroll on Back. Show explicit text, seed and followed-topic interests as compact cards, with Add interest and existing editing/removal routes. Empty or model-unavailable collections still allow interest management. Selecting an interest opens its existing source-linked matches and optional listening preview; only explicit Play starts a session.

# Browse — Explore

Browse exposes a compact Explore entry opening the complete deterministic Explore Catalog. Explore is not repeated in Discover. It provides metadata shortcuts into the Browse result set, not an automatic topic taxonomy.

It does not automatically create Saved or Smart Queues.

Example:

```plain text
Explore

Chess
11 notes

Mind & Learning
8 notes

Business & Strategy
4 notes

See all Explore
```

Selecting:

```plain text
Mindfulness
```

may open Browse with:

```plain text
Topic = mindfulness
```

Selecting:

```plain text
Chess
```

may open Browse with:

```plain text
Area = Chess
```

Each catalog entry is a reader-oriented shortcut into a result set. Selecting Explore in Browse opens the Explore Catalog.

They are not persistent queues.

## Explore Catalog

The Explore Catalog is the complete deterministic entry point into workspace discovery. It lists every Area and Topic that occurs on at least one accessible Note, with its note count.

Catalog order and selection are intentionally simple:
- Areas appear before Topics, so every populated Area remains discoverable even when one Area has many Topics;
- each section is ordered alphabetically using a case-insensitive deterministic comparison;
- selecting an Area opens Browse with that Area facet, and selecting a Topic opens Browse with that Topic facet.

The catalog must not use opaque AI recommendations or a shared popularity cut-off. Browse exposes a stable Explore entry linking to this complete catalog.

# Home and Discover invariant

Home resumes familiar content; Discover explores cached knowledge. Neither duplicates Browse or queue management. Explore remains in Browse. Opening any card is visual navigation unless it explicitly invokes an existing listening command.

# Browse screen

## Purpose

Browse is the primary keyword-search and metadata-browsing interface. Discover owns local semantic search, automatic topics and individual semantic connections.

It answers:

> What do I want to read or listen to?

Browse should not require the user to know which Notion database contains a Note.

It should operate over normalized reader content.

# Browse default state

Browse must not open as an empty screen.

When no search or filters are active:

```plain text
Browse

Search notes
Filters
Sort: Recently updated

All Notes
...
```

The default result set is:

```plain text
All Notes
Sort = Recently updated
```

# Search

Search should be immediately available near the beginning of the Browse screen.

It narrows the same Browse result set rather than forcing the user into a separate navigation hierarchy.

Search should conceptually match reader-useful fields such as:

```plain text
Title
Summary
searchable normalized content
```

The exact search implementation belongs to the Browse/Search implementation specification.

# Browse filters

Filters are facets, not folders.

Frequently used filters should include:

```plain text
Type
Area
Topics
Project
More
```

Less commonly used filters such as Date and Status may live under More.

The user should be able to combine filters.

Example:

```plain text
Type = Practice
Area = Chess
Topic = Sicilian Defense
```

This produces one ordered result set.

The application must not require navigation such as:

```plain text
Areas
→ Chess
→ Practice
→ Sicilian
→ Notes
```

Metadata combinations should update the current results directly.

# Active filter summary

When filters are active, Browse should expose a concise summary.

Example:

```plain text
3 filters:
Reflection, Mind & Learning, mindfulness

12 results

Clear filters
```

The user should not need to individually inspect every filter control to understand the current Browse state.

TalkBack announcements should remain concise when filters change.

# Browse sorting

Sort order is user-visible because it directly determines Play all order.

Possible sorts may include:

```plain text
Recently updated
Newest date
Oldest date
Title
Relevance
```

Not every sorting method must apply to every query.

Search may default to Relevance.

Chronological Reflection or Practice result sets may use Date.

Knowledge must not silently treat creation date as a learning sequence.

# Browse result-set model

Browse conceptually produces:

```plain text
BrowseQuery
      ↓
OrderedResultSet
```

This result set is the central unit of Browse.

The user may:

```plain text
Open one result
→ Note
```

or:

```plain text
Play all
→ Active Queue snapshot
```

or:

```plain text
Add all to queue
→ Saved Queue destination
```

# Result items

Each result represents one Note.

A result should contain enough information to identify it without becoming excessively verbose.

Typical presentation:

```plain text
Thoughts are less reactive but still frequent
Reflection · Mind & Learning · Sep 17
```

TalkBack should normally treat one result row as one main focus target.

The application should avoid creating separate focus stops for:

```plain text
Title
Type
Date
Area
Summary
menu
```

unless those controls require independent interaction.

A useful default spoken result could be:

> Thoughts are less reactive but still frequent. Reflection. Mind and Learning. September 17.

Topics and summaries should not automatically make every result excessively verbose.

# Result activation

Activating a Browse result opens the Note screen.

It does not start playback.

# Browse result-set actions

For a non-empty result set, Browse exposes:

```plain text
Play all
Add all to queue
```

These controls belong to the result set as a whole.

They should not be repeated on every individual result row.

# Play all

Play all:
1. captures the current visible result set;
1. preserves the displayed ordering;
1. creates a frozen Active Queue snapshot;
1. starts a new listening session;
1. opens Player.

Changes to Browse after this point do not affect the Active Queue.

# Add all to queue

Add all to queue:
1. captures the current result set;
1. opens Queue Destination;
1. lets the user select an existing Saved Queue or create one;
1. adds the result set to that Saved Queue;
1. does not start playback;
1. does not modify an Active Queue.

# Browse empty state

When no Notes match:

```plain text
No matching Notes.
```

The screen should retain search and filter controls.

Play all and Add all to queue must not appear when there are no results.

A clear path to adjusting or removing filters must remain available.

# Browse state restoration

For:

```plain text
Browse
→ Note
→ Back
```

the application should restore, where technically practical:
- search text;
- filters;
- sort;
- result position;
- prior meaningful TalkBack focus target.

Opening a Note must not force the user to rebuild the Browse query.

The same principle applies when briefly opening Player and returning.

# Browse Explore to filtered Browse

Explore Catalog shortcuts reached from Browse resolve into Browse state.

Example:

```plain text
Browse
→ Explore Catalog: Mindfulness
→ Browse
→ Topic = mindfulness
```

Browse then behaves normally.

The user can further:

```plain text
change filters
change sort
open Notes
Play all
Add all to queue
```

# Raw Notion navigation

Raw Notion navigation is a secondary escape hatch.

Browse may expose a secondary action such as:

```plain text
Browse Notion structure
```

This should not visually compete with Search, Filters, and the result set.

Conceptual flow:

```plain text
Browse
→ Browse Notion structure
→ Database
→ Pages
→ Note
```

Generic Notion pages use the same standard Note and Player screens as organized content.

# Queues screen

## Purpose

Queues is the dedicated surface for:
- listening history;
- persistent manually curated queues;
- persistent query-based queues.

Selecting the Queues bottom-navigation destination opens a screen with three top tabs:

```plain text
Listening | Saved | Smart
```

The selected tab must expose proper Android/TalkBack tab semantics.

Example:

> Listening, tab, selected, 1 of 3.

# Listening tab

## Purpose

Listening represents recent listening sessions.

Its mental model is:

> What have I been listening to?

It contains two sections.

# Listening — Continue listening

Contains unfinished or currently active listening sessions.

Examples:

```plain text
Continue listening

Android accessibility
Playing · Note 2 of 9

Mindfulness reflections
Paused · Note 3 of 8 · 2 hours ago

Chess practice
Paused · Note 7 of 12 · Yesterday
```

Selecting an unfinished session:

```plain text
Continue
→ restore exact Active Queue snapshot
→ restore exact playback position
→ Player
```

The source is not re-evaluated.

# Listening — Recently completed

Contains completed listening sessions.

Example:

```plain text
Recently completed

AI inference notes
8 notes · Completed yesterday

Sicilian practice
12 notes · Completed Sep 15
```

Activating a completed session should start a new playback session using that historical frozen snapshot from the beginning.

It should not regenerate the original Smart Queue or Browse result set.

If the user wants fresh Smart Queue results, they use:

```plain text
Queues → Smart → queue → Play
```

# Listening ordering

Listening items should be ordered by most recent listening activity first.

If one of the two sections contains no items, omit the empty section heading.

The exact retention limit for completed listening history belongs to the Active Queue & Reader Session specification.

# Saved tab

## Purpose

Saved contains persistent manually curated queues.

Mental model:

> Playlists I built.

Example:

```plain text
Saved

Android accessibility
11 notes

Chess calculation review
6 notes

Mindfulness reading
9 notes

Create saved queue
```

Each queue should normally be one logical TalkBack focus target.

Activating one opens Saved Queue Details.

It does not immediately start playback.

# Create Saved Queue

The Saved tab may expose:

```plain text
Create saved queue
```

This allows an empty Saved Queue to be created deliberately.

The more common creation path may still be:

```plain text
Add to queue
or
Add all to queue
→ Create new queue
```

The required user input is initially only:

```plain text
Queue name
```

Creating a Saved Queue does not start playback.

# Saved Queue Details

## Purpose

Saved Queue Details is primarily for:
- understanding the queue;
- opening Notes;
- starting playback.

Example:

```plain text
Android accessibility

11 notes

Play

1. Android TTS overview
2. AccessibilityService notes
3. TalkBack event handling
...

Edit queue
```

## Play

Play:
1. snapshots the Saved Queue's current membership and order;
1. creates a new Active Queue;
1. opens Player;
1. starts from the first Note.

Later edits to the Saved Queue do not affect the Active Queue already created.

## Note activation

Activating an individual queue item opens that Note.

It does not start playback.

# Edit Saved Queue

Membership changes are performed through an explicit Edit Queue mode.

The normal Saved Queue Details screen should not expose multiple destructive/reorder controls on every Note.

Example:

```plain text
Edit Android accessibility

1. Android TTS overview
   Move up
   Move down
   Move to top
   Move to bottom
   Remove

2. AccessibilityService notes
   Move up
   Move down
   Move to top
   Move to bottom
   Remove

Done
```

## Accessibility requirement

Queue reordering must never require drag-and-drop.

Accessible deterministic actions must be available, including:

```plain text
Move up
Move down
Move to top
Move to bottom
```

A visual drag handle may be added as an additional interaction for sighted users, but it cannot be the only method.

# Smart tab

## Purpose

Smart contains persistent query-defined queues.

Mental model:

> Reusable searches I saved.

Example:

```plain text
Smart

Mindfulness reflections
Reflection · mindfulness · newest first
12 matches

Recent chess practice
Practice · Chess · last 30 days
8 matches

Create smart queue
```

Activating one opens Smart Queue Details.

It does not immediately begin playback.

# Smart Queue Details

## Purpose

Smart Queue Details explains the query and allows it to be played or edited.

Example:

```plain text
Mindfulness reflections

Type: Reflection
Topic: mindfulness
Sort: Newest first

12 current matches

Play

Preview results
Thoughts are less reactive but still frequent
Mindfulness practice observations
...

Edit filters
Rename
Delete
```

# Smart Queue playback

Play:
1. evaluates the Smart Queue against current content;
1. applies its stored filters;
1. applies its stored sorting;
1. creates an ordered result set;
1. snapshots that result set into a new Active Queue;
1. opens Player;
1. starts playback.

Future Smart Queue result changes do not affect that Active Queue.

# Smart Queue preview

Smart Queue Details may show current matching Notes.

This preview may change as current content changes because it represents the live query result.

That is different from an Active Queue.

The preview is informational and must never dynamically modify an already-running listening session.

# Editing Smart Queues

Smart Queue editing changes its query definition.

Possible actions include:

```plain text
Edit filters
Change sort
Rename
Delete
```

There is no manual Add Note or Remove Note membership operation.

Smart membership comes from its query.

# Queue tab mental model

The three Queue tabs must remain semantically distinct:

```plain text
Listening
= things I have been listening to

Saved
= playlists I manually built

Smart
= reusable dynamic queries
```

# Queue Destination screen

## Purpose

Queue Destination is used by:

```plain text
Add to queue
Add all to queue
```

It chooses which persistent Saved Queue receives the content.

## Destinations

Show:
- existing Saved Queues;
- Create new queue.

Do not show:
- Smart Queues;
- Active Queue;
- ambiguous Current Queue.

# Existing Saved Queue selection

Selecting an existing Saved Queue:
1. adds incoming Notes;
1. deduplicates by stable Note identity;
1. preserves existing queue order;
1. does not start playback;
1. does not affect the Active Queue.

# No Saved Queues

If no Saved Queue exists, the application may directly open Create Saved Queue rather than showing an empty destination screen.

# Note screen

## Purpose

The Note screen is for manual reading and inspection.

It is not the primary continuous listening interface.

## Content

It should expose document structure using accessible Android semantics.

Content may include:
- title;
- headings;
- paragraphs;
- lists;
- links;
- code;
- supported structured content.

Detailed rendering rules belong to the Note Reading Screen specification.

# Note primary actions

Keep primary actions minimal:

```plain text
Play
Add to queue
```

# Note — Play

Play:
1. creates a new Active Queue containing only this Note;
1. starts from the beginning;
1. opens Player.

Historical per-Note progress is not automatically resumed.

# Note — Add to queue

Add to queue opens Queue Destination.

It does not:
- start playback;
- stop playback;
- modify an Active Queue.

# Note and TalkBack position

Moving TalkBack focus through the Note must not change Player progress.

If playback is occurring while another Note is manually inspected, the existing playback session remains unchanged.

# Player screen

## Purpose

Player is the dedicated continuous spoken-reading interface.

It should emphasize listening rather than metadata or document editing.

# Player primary information

Show:
- current Note title;
- current section when available;
- queue position such as Note 3 of 8;
- useful progress state;
- Playing or Paused state.

# Player primary controls

Keep the visible control set small.

Expected primary controls:

```plain text
Previous section
Play / Pause
Next section
Queue
```

Fine-grained chunk navigation and advanced commands may exist as secondary actions.

# Player Queue action

Queue opens a view of the current frozen Active Queue/session if such a view is required.

This represents the current playback snapshot.

It is not the underlying Saved Queue or Smart Queue definition.

# Player Back behavior

Back returns to the previous visual context when possible.

Playback continues.

Example:

```plain text
Browse
→ Play all
→ Player
→ Back
→ Browse

Active playback continues through mini-player.
```

# Note transitions during playback

At the end of one Note, Player should continue automatically to the next Note in the Active Queue.

A brief title announcement may identify the next Note.

Detailed spoken transition behavior belongs to the Player specification.

# End of Active Queue

At the final Note:
- announce that the queue has ended;
- stop automatic progression;
- mark the session completed according to the Reader Session specification.

It may then appear under:

```plain text
Queues → Listening → Recently completed
```

# Raw Notion Navigation

## Purpose

Raw Notion Navigation exists for content that users want to locate using underlying Notion structure.

It remains secondary.

Conceptual flow:

```plain text
Browse
→ Browse Notion structure
→ Database
→ Pages
→ Note
```

Selecting a page always opens the standard Note screen.

Raw Notion pages must not require a separate reader implementation.

# Settings screen

Settings contains application configuration rather than content navigation.

Possible future categories include:

```plain text
TTS preferences
Playback preferences
Notion account/connection
Accessibility preferences
Background playback preferences
```

Specific settings are introduced by the specifications that require them.

Settings must not become a dumping ground for frequently used playback actions.

# Android Back contract

General rule:

> Back reverses visual navigation. It does not reverse playback.

Examples:

```plain text
Note
→ Back
→ originating screen
```

```plain text
Player
→ Back
→ previous visual screen
→ playback continues
```

```plain text
Queue Destination
→ Back
→ cancel destination selection
→ no queue modified
```

```plain text
Create Saved Queue
→ Back
→ cancel creation
→ pending Notes remain unapplied
```

```plain text
Edit Saved Queue
→ Back
→ follow explicit edit-state behavior defined by queue-management spec
```

At top-level destinations, Android system Back follows normal application behavior.

Back must never implicitly mean:

```plain text
Pause
Stop
Previous section
Previous Note
```

# Top-level navigation state

Switching between:

```plain text
Home
Discover
Browse
Queues
Settings
```

should preserve reasonable local state.

Browse should retain:
- search;
- filters;
- sort;
- result position.

Queues should retain:
- selected top tab;
- scroll position where practical.

Returning to a top-level screen should not unnecessarily reset the user's context.

Exact process-death restoration belongs to persistence/session specifications.

# Playback replacement behavior

A new Play or Play all action creates a new listening session.

The navigation layer should not show a confirmation merely because another paused or resumable session exists.

The previous session can remain available under Listening history according to the Reader Session specification.

Navigation simply opens the new Player session.

# Loading states

Screens dependent on remote content must distinguish loading from empty states. When a persisted Browse index exists, Browse and Explore Catalog render that cached data immediately while a foreground refresh runs; a refresh failure retains the cached data rather than blanking the discovery surfaces.

Example:

```plain text
Loading Notes…
```

is not equivalent to:

```plain text
No matching Notes.
```

Loading must not be communicated only through animation.

TalkBack should receive useful but non-repetitive status updates.

# Error states

Errors should appear where recovery is meaningful.

Examples:

```plain text
Could not load Notes.
Retry
```

```plain text
Could not load this Note.
Retry
```

Permission problems must not be collapsed into generic network errors.

Detailed error categories belong to the underlying implementation specifications.

# Accessibility navigation contract

## Initial focus

Opening a full screen should place TalkBack focus at a meaningful starting point, normally:
- the screen heading; or
- primary content heading when more useful.

It must not unpredictably land on:
- bottom navigation;
- mini-player;
- decorative content;
- arbitrary coordinates.

## Returning with Back

When returning from a secondary destination, restore the previous meaningful focus target when it still exists.

Example:

```plain text
Browse result 7
→ Note
→ Back
```

should preferably restore focus to result 7.

## Result-list efficiency

One Browse result or one Queue row should normally be one primary focus item.

Avoid forcing the user through several metadata controls for every item.

## Dynamic announcements

Updates such as:

```plain text
Filters changed
Queue updated
Playback paused
Loading completed
```

should use concise accessibility announcements only when useful.

Large parts of the interface should not be repeatedly re-announced for small state changes.

## Reordering

Any content reordering must have a non-drag accessible method.

# Mini-player accessibility

The mini-player should be a logically grouped accessible region.

A useful announcement is approximately:

> Current Note title. Playing. Open Player.

Play/Pause should remain an explicit accessible control.

The mini-player should not read the complete queue state every time focus reaches it.

# Screen ownership boundaries

Each primary screen has one responsibility:

```plain text
Discover
→ useful starting points

Browse
→ discover and build result sets

Note
→ manually inspect/read

Queues
→ listening history and persistent queue organization

Player
→ continuously listen

Settings
→ configure

Raw Notion
→ structural escape hatch
```

A downstream specification should justify creating another major destination rather than overloading one of these responsibilities.

# Navigation invariants
1. Home, Discover, Browse, Queues, and Settings are top-level destinations.
1. Player is not a permanent bottom-navigation tab.
1. Note is not a top-level destination.
1. Home owns listening recents and interest shortcuts; Discover owns semantic search and knowledge exploration, without listening history; Browse owns deterministic Explore.
1. Explore preview and catalog entries open pre-filtered Browse rather than creating persistent queues; the catalog exposes every populated Area and Topic.
1. Browse defaults to All Notes sorted by Recently updated.
1. Browse combines Search, facets, sorting, and one ordered result set.
1. Browse metadata filters are not represented as a folder hierarchy.
1. Activating a Browse result opens Note.
1. Opening a Note never automatically starts playback.
1. Play all snapshots exactly the current ordered Browse result set.
1. Add all to queue routes to Saved Queue destination selection.
1. Browse state survives temporary navigation where practical.
1. Queues uses three top tabs: Listening, Saved, Smart.
1. Listening contains Continue listening and Recently completed.
1. Listening history represents frozen Active Queue sessions.
1. Saved represents manually curated persistent queues.
1. Smart represents persistent query-defined queues.
1. Activating Saved or Smart Queue rows opens details rather than immediately playing.
1. Saved Queue membership editing occurs through explicit Edit Queue mode.
1. Accessible Saved Queue reordering must not require drag-and-drop.
1. Smart Queue membership cannot be manually edited.
1. Add to queue and Add all to queue target Saved Queues only.
1. There is no generic Current Queue destination.
1. Navigating away from Player does not stop playback.
1. The mini-player reflects the existing Player session and creates no separate playback state.
1. Android Back changes visual navigation, not playback state.
1. Manual TalkBack Note navigation never changes Player progress.
1. Generic and organized Notes use the same Note and Player surfaces.
1. Raw Notion navigation remains secondary and ultimately opens the standard Note screen.

# Dependencies

Depends on:

Notion Reader — Product & Architecture Contract

Future specifications that depend on this contract include:
- Notion Connection, Retrieval & Normalization;
- Reader Document Model & Chunking;
- Content Metadata & Workspace Schema Adapters;
- Browse, Search, Filtering & Result Sets;
- Active Queue & Reader Session;
- Queue Creation & Queue Management;
- Core Player & TTS Playback;
- Note Reading Screen;
- Background Playback, Media Controls & Hands-Free Commands.

# Acceptance criteria

This specification is ready when:
- the five top-level destinations are explicit;
- Home provides resume/recency shortcuts and Discover focuses on search, topics and connections;
- Browse has a defined default state;
- Search/filter/sort/result-set behavior is unambiguous;
- Browse owns Explore and its complete catalog;
- Play all and Add all to queue have precise navigation effects;
- Listening, Saved, and Smart Queue tabs have distinct mental models;
- unfinished and completed listening sessions have defined behavior;
- Saved Queue details and explicit Edit Queue behavior are defined;
- Smart Queue details and query editing behavior are defined;
- accessible queue reordering does not depend on drag-and-drop;
- Note and Player responsibilities are distinct;
- mini-player responsibility is defined;
- Android Back behavior is deterministic;
- Browse and TalkBack focus restoration expectations are explicit;
- loading, empty, and error states are distinguishable;
- no downstream coding agent needs to invent a navigation-level product rule.

# Explicitly deferred work

This specification intentionally does not define:
- visual style;
- Compose implementation details;
- precise screen spacing;
- exact filter widgets;
- exact Browse search backend;
- exact ordering of optional Browse Explore shortcuts;
- exact number of Discover entries shown;
- exact listening-history retention limit;
- exact Saved Queue persistence schema;
- exact Smart Queue query representation;
- exact queue-edit confirmation behavior;
- detailed Note rendering;
- Player secondary controls;
- TTS internals;
- media-session implementation;
- lock-screen controls;
- voice command UI.

# Open questions

None.

# Discover semantic surfaces

Discover is a primary knowledge-exploration experience, separate from the default Home destination. Its order is semantic search with facets and coverage, Topics in your Notes, Emerging topics, Connections between Notes and one View your interests entry. No listening history or queue shortcuts appear here. Topic and connection previews are bounded cards linking to secondary lists. Explore remains in Browse. Search and individual Notes never require topic membership or interests. Show one concise setup/recovery state for unavailable semantic features; keep interest management reachable even without a model.

Search is labelled Search your cached Notes and shows indexed versus cached coverage. It supports the existing metadata facets and returns up to 20 distinct Notes in semantic relevance order. Browse keeps its existing keyword behavior and navigation state. Search query, facets, visible results and return-focus identity survive trips into a Note.

Your interests contains only explicitly saved text, seed-Note and followed-topic interests. Add interest offers Describe a topic and Choose a Note; the Note picker explains that only cached Notes are available. Automatic topics remain in Topics in your Notes, not in Your interests. Without followed interests, show automatic topics and individual connections when supported by evidence, plus a concise Add interest invitation; never fabricate personalized recommendations. An evicted seed is unavailable until recached and retains no content snapshot.

Each automatic-topic preview is a labelled navigation item with its distinct available Note count and one representative title. See all topics opens a stable list. Topic details prioritizes the topic name, distinct available Note count, Preview listening, Follow topic or View followed interest, and member Notes with matching excerpts. The first three members are marked Representative without duplicating their titles in a separate list. Explain that following saves an interest and only Play starts listening. Put cached/indexed coverage, label provenance with Open label source Note, and anchor context behind a collapsed Why these Notes? control; retain its expanded state and real return-focus targets across Note navigation. Do not publish generic fallback-labelled candidates, incomplete predicate clauses or ambiguous document-family-only labels in topic previews or the topic list. Retain eligible candidate evidence and identities internally; offer source-supported subject labels when sufficient evidence becomes available. Consolidate near-identical member families in the displayed collection unless distinct passage-level themes and labels justify separate topics. Previously followed interests retain their independent definitions. An inspected snapshot remains stable until explicit Refresh, subject to mandatory source-eligibility removals; search and individual Note discovery do not require a published topic. Each Note has a source-linked matching excerpt and section. Connection cards identify Named reference or Similar passages, with both source excerpts available in details and manual Open Note actions. The complete connection list separates Similar passages and Named references under headings with pair counts, preserving order within each group. A named-reference label indicates an explicit instruction or citation naming the other cached Note by its unique full current title; it does not promise an activatable Notion hyperlink. Preview listening preserves the displayed pair order and uses the evidence type as its descriptive origin; only explicit Play changes the listening session. Keep existing canonical pair identities, focus targets and Back behavior. A Note can appear in multiple topic details; counts deduplicate canonical Note identities within each topic. No useful groups, insufficient indexed content, indexing paused, model unavailable and processing incomplete have distinct states; Browse and search remain available.

Follow topic opens a confirmation form with the proposed name and editable matching text. Save creates a durable independent interest; Cancel changes nothing. Existing follows open the saved interest rather than duplicating it. Renaming or regrouping the automatic topic never changes its saved interest. Your interests is a shared secondary card collection with a bounded preview on Home; do not expand pooled interest recommendations or older matching Notes into the main feed. Selecting a card opens that individual interest, its saved definition, matching cached Notes with passage evidence, and an optional older-matches subsection. Text and cached-seed interests use the same details route; an unavailable seed has an explicit recache state and no retained content snapshot. Interest details offers Preview listening for up to five matches in relevance order, with a descriptive Interest origin label. Preview, edits, saving and opening Notes leave the current session alone; only explicit Play creates the frozen Discovery-origin Active Queue. Details also offers editing and removal. Topic disappearance leaves the saved interest usable.

Connections between Notes uses compact single-action cards with two separately readable Note titles, one short source excerpt and a Compare passages cue. The card opens a comparison showing full excerpts and section labels from both Notes with named Open Note actions; the accessible navigation label identifies both Notes. Preview listening remains optional in details. Do not repeat both full excerpts in the main feed. It may surface different-topic or grouped-to-ungrouped pairs, deduplicates unordered canonical pairs, and does not require every Note to have a topic. Connection details permits opening either Note and Preview listening. A generic Similar passages label describes the evidence without claiming cause, contradiction, agreement or a generated conceptual relationship.

Topic, connection and member lists use structured headings, list semantics and explicit labelled controls. A topic row is one navigation stop with its name and distinct Note count; representative titles are readable without separate repeated open controls on the preview. Details provides named Open Note actions and readable evidence. All destinations and rows have stable ID-based focus targets. Back restores the originating topic, member or connection control and its saved scroll position. When a source becomes unavailable, withdraw its evidence, keep the destination with an honest unavailable state and restore focus to the nearest surviving control or section heading.

Background indexing may build a newer snapshot but does not reorder, rename or regroup an inspected list. Offer New discoveries available and an explicit Refresh action without repeated announcements. User refresh adopts the newer snapshot and restores the same topic ID when it survives. Opening Note and returning preserves the inspected snapshot except for mandatory eligibility removals. Topic changes do not alter a prepared listening preview.

Note and Player secondary navigation offer up to five Related Notes with source excerpts and section labels. Opening a result is manual reading and leaves playback alone. Related lists exclude the source Note. Older Notes use the last-edited date and a 90-day cutoff, excluding unknown dates.

Guided listening is a secondary preview of at most five recommended Notes, excluding the seed unless explicitly added through a future specified action. Provide labelled remove and move-up/move-down controls, Play, and existing Saved Queue destination actions. Play snapshots the preview order into a new session; other actions never replace or interrupt playback. Keep Play and Save to queue in a persistent action area outside the scrollable ordered-Note preview, with one accessible instance of each action. Long excerpts and large text must not require scrolling through the Note list to reach either action. Keep the Note list independently scrollable, preserve labelled editing controls and Back, and disable Play and Save when the preview is empty or contains an unavailable source. Respect the existing shell and system insets rather than overlaying content.

Discovery settings expose model download size, source, license, network choice, download cancellation/retry/deletion, indexing coverage/progress/pause/resume, index clear/rebuild, and derived storage use. Clearing the index pauses rebuilding until explicit resume. Model deletion clears vectors and excerpts. Loading, unavailable model, incomplete index, empty results, errors and storage-budget exhaustion are distinct accessible states. A topic/checkpoint validation rejection or I/O error must show an accurate recovery description and must not be called storage full. Keep the prior valid discoveries accessible; do not clear the source cache or silently raise storage limits to hide failure.

Recommendation rows are source-linked, keyboard-operable list items. Background updates do not move focus, reorder the currently inspected list, or repeatedly announce counts. Back reverses visual navigation and restores a real focus target. No graph is required to operate Discovery.

# Automatic topics: formation, publication and acceptance

The semantic Discovery screen presents separate Topics in your Notes and Emerging topics collections. Established topics have at least two distinct supporting Notes; validated single-Note subjects appear only under Emerging topics. Both use the existing compact topic row, passage-backed details, Preview listening and Follow topic confirmation flow. Expose one-Note counts honestly and preserve stable topic-ID navigation and focus when a subject moves between collections after Refresh.

Follow topic proposes the source-supported name and editable matching text, saves only on confirmation, and opens an existing interest instead of duplicating it. Removing a follow uses the existing Your interests editing/removal flow. Show incomplete processing with processed and eligible section counts and a pause or recovery explanation, never a complete empty-state claim when candidate coverage is unfinished. Preserve inspected ordering, labels, evidence and focus until explicit Refresh except immediate mandatory source removals. No automatic processing, navigation, Follow or preview action changes playback; only explicit Play does.

No fixed topic-count target or publication cap is a product goal. Allow supported broad, focused and overlapping subjects to remain separately reachable. Non-goal or deferred-work discussions may be subjects without implying an implemented capability. Reference-only material does not establish a thematic topic. Apply the Product contract quality and physical Android acceptance gates before general release enablement of the replacement engine; semantic tests cannot substitute for human TalkBack speech/listening acceptance.

# Independent explicit Note references

In the replacement engine, retrieve explicit Note references independently of semantic similarity thresholds, cross-topic membership and thematic body-evidence admission. An explicit instruction or citation naming the unique complete current title of another eligible cached Note establishes a reference, including instructions in reference lists; a bare coincidental or partial title mention, ambiguous title, standalone heading, or negated instruction does not. Reference-list evidence never independently establishes topic membership or a Similar passages relationship. Both endpoints must remain currently cached and indexed, with source revision and title uniqueness checked. Display the literal referencing passage and a literal target passage as target context; explain that the target excerpt is context, not proof of thematic similarity, agreement or a verified Notion hyperlink. Keep Named references separate from Similar passages in lists, evidence descriptions and evaluation. Preserve unordered canonical pair identities, explicit Refresh, source-removal/privacy rules and session isolation. Independent retrieval uses the experimental engine flag. General release enablement requires the governing gates; the authorized debug trial is permitted as described below. The release baseline retains classification of already eligible pairs.

# Developer evaluation and authorized debug trial

Discovery is automatic. End users are not required to label, rate, validate or approve discovered topics or supporting passages before using it. Follow and Unfollow express a personal choice, not a topic-quality judgment. Human quality evaluation is developer acceptance work on public, consented or purpose-built representative evaluation Notes; it must not require any user to submit or review their private library. Private-library feedback is optional and stays local unless the user explicitly chooses otherwise. Preserve the existing evaluation thresholds and distinguish verified human judgments, provisional annotations and unrated cases.

The user has authorized a debug-build trial of the implemented replacement Discovery engine on their Android device. Debug builds may enable the replacement for this trial while general release builds retain the baseline. This is not a declaration that quality, memory, playback or human TalkBack acceptance has passed. Keep failed and unmeasured gates recorded, including the latest physical-device additional-memory result, and retain the existing gates for general release enablement. The trial uses the existing local MiniLM model and automatic indexing lifecycle, without a new model download, required private-data review, source-cache reset, saved-interest reset or playback-command change.

