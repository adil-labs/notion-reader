<!--
  GENERATED FILE - do not edit by hand.
  Exported from Notion by tools/export-notion-doc.ps1.
  Source page: Notion Reader — UI Screens & Navigation Contract
  Page id:     3dec9345-e10a-81b6-9cd2-d54532ad6fcd
  Notion URL:  https://app.notion.com/p/Notion-Reader-UI-Screens-Navigation-Contract-3dec9345e10a81b69cd2d54532ad6fcd
  Notion last edited: 10/05/2026 03:24:00
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
- Provide useful Home discovery without duplicating Browse.
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

The application has four top-level destinations:

```plain text
Home
Browse
Queues
Settings
```

These should use a persistent Android top-level navigation mechanism appropriate for the device form factor.

On standard phone layouts, bottom navigation is expected.

The Player is not a fifth bottom-navigation destination.

The Note screen is not a top-level destination.

Both are secondary screens reached from the primary navigation surfaces.

# Global navigation structure

```plain text
App
│
├── Home
│   ├── Continue listening → Player
│   ├── Your queues → Queue Details
│   └── Explore preview
│       ├── Area or Topic → pre-filtered Browse
│       └── See all Explore → Explore Catalog
│
├── Browse
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

## Purpose

Home provides useful starting points.

It should answer:

```plain text
What was I listening to?
What queues do I already have?
What might I want to explore?
```

Home is not a complete Browse screen and not a complete Queue-management screen.

It has three primary sections.

# Home — Continue listening

This section shows a small number of recent unfinished listening sessions.

These are frozen Active Queue snapshots.

Example:

```plain text
Continue listening

Mindfulness reflections
Note 3 of 8
Paused 2 hours ago
[Continue]

Chess practice
Note 7 of 12
Paused yesterday
[Continue]
```

Selecting Continue:
1. restores that exact Active Queue snapshot;
1. restores its exact saved playback position;
1. opens Player;
1. does not re-evaluate the source Browse query, Saved Queue, or Smart Queue.

The full listening history is available under:

```plain text
Queues → Listening
```

Home should show only a small useful subset.

If no resumable session exists, omit the section.

# Home — Your queues

This section surfaces a small number of useful persistent queues.

It may contain both:

```plain text
Saved Queue
Smart Queue
```

Example:

```plain text
Your queues

Android accessibility
Saved Queue · 11 notes

Recent mindfulness reflections
Smart Queue · 8 current matches

Chess calculation review
Saved Queue · 6 notes

See all queues
```

Activating an item opens its Queue Details screen.

It does not immediately start playback.

Home should show a limited number based primarily on recent usage or recent queue activity.

The full collection remains under Queues.

# Home — Explore

Home contains a bounded, deterministic Explore preview. It is a set of discovery shortcuts into Browse, not the complete metadata taxonomy.

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

Each preview entry is a reader-oriented shortcut into a result set. Selecting See all Explore opens the Explore Catalog.

They are not persistent queues.

## Explore Catalog

The Explore Catalog is the complete deterministic entry point into workspace discovery. It lists every Area and Topic that occurs on at least one accessible Note, with its note count.

Catalog order and selection are intentionally simple:
- Areas appear before Topics, so every populated Area remains discoverable even when one Area has many Topics;
- each section is ordered alphabetically using a case-insensitive deterministic comparison;
- selecting an Area opens Browse with that Area facet, and selecting a Topic opens Browse with that Topic facet.

The catalog must not use opaque AI recommendations or a shared popularity cut-off. Home preview selection may be ranked separately, but it must link to this complete catalog.

# Home invariant

Home may surface useful content, sessions, and queue shortcuts, but must not duplicate the full functionality of Browse or Queues.

The Home structure is therefore:

```plain text
Home

Continue listening
→ resumable Active Queue snapshots

Your queues
→ Saved and Smart Queue definitions

Explore preview
→ filtered entry points into Browse
→ Explore Catalog for the complete Area and Topic taxonomy
```

# Browse screen

## Purpose

Browse is the primary semantic discovery interface.

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

# Home Explore to Browse

Explore shortcuts on Home resolve into Browse state.

Example:

```plain text
Home
→ Explore: Mindfulness
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

Screens dependent on remote content must distinguish loading from empty states. When a persisted Browse index exists, Browse, Home Explore, and Explore Catalog render that cached data immediately while a foreground refresh runs; a refresh failure retains the cached data rather than blanking the discovery surfaces.

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
Home
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
1. Home, Browse, Queues, and Settings are top-level destinations.
1. Player is not a permanent bottom-navigation tab.
1. Note is not a top-level destination.
1. Home contains Continue listening, Your queues, and a bounded Explore preview; Explore Catalog is a secondary destination.
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
- the four top-level destinations are explicit;
- Home has clear responsibilities and three defined sections;
- Browse has a defined default state;
- Search/filter/sort/result-set behavior is unambiguous;
- Home Explore and Browse have an explicit relationship;
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
- exact Home Explore ranking algorithm;
- exact number of Home entries shown;
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

