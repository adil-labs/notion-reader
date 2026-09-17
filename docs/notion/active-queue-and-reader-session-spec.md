<!--
  GENERATED FILE - do not edit by hand.
  Exported from Notion by tools/export-notion-doc.ps1.
  Source page: Notion Reader — Active Queue & Reader Session Spec
  Page id:     3dec9345-e10a-814c-a8f3-c8b3539ac8e1
  Notion URL:  https://app.notion.com/p/Notion-Reader-Active-Queue-Reader-Session-Spec-3dec9345e10a814ca8f3c8b3539ac8e1
  Notion last edited: 09/17/2026 15:38:00
  Notion is authoritative. Re-run the exporter after editing there.
-->

# Notion Reader — Active Queue & Reader Session Spec

> Governing document, mirrored from Notion. See the header above for provenance.

> Governing implementation specification. Settles listening-session persistence, resume behavior, and listening history for Notion Reader.

# Summary

This specification defines what a listening session is, how it is persisted, how it is restored, and what the listener hears when a restored position no longer exists in the Note.

It exists because the Notion Integration Implementation Spec explicitly refused to own session persistence. That specification says:

> This specification does not own listening-session persistence.

and

> The Notion integration layer must not itself decide whether playback resumes, where Continue falls back, or what the user hears when a saved position no longer exists. Those behaviors belong to the future Active Queue & Reader Session specification.

This is that specification. The integration layer's obligation ends at exposing stable page, section, source-block, and chunk identities; everything this document defines is expressed only in those identities.

# Goals
- Make listening survive the process. A session that is paused, backgrounded, or interrupted by process death can be continued later from the same place.
- Give Continue listening, Recently completed, and the mini-player a real history to read from, so Home and the Listening tab stop being projections of the live session alone.
- Settle resume policy once, in the layer the Product & Architecture Contract says owns it, so no downstream feature decides it a second time.
- Define a persistence boundary narrow enough that a Notes content is never a second source of truth.
- Define retention limits, which the UI Screens & Navigation Contract explicitly defers here.
- Define exact process-death restoration, which the UI contract explicitly defers to persistence/session specifications.
- Keep the session state machine synchronous and scope-free, so its logic remains testable without a device, a dispatcher, or the network.

# Non-goals

This specification does not:
- define how Notion content is fetched, normalized, chunked, or identified;
- define Saved Queue or Smart Queue persistence, or any queue-management behavior;
- define TTS integration, speech synthesis, or hands-free command handling;
- define Browse filtering, sorting, or metadata discovery;
- introduce a per-Note permanent resume position;
- cache Note content, or persist anything derived from a Note that could disagree with the source;
- define playback state reporting vocabulary, which the UI contract and the existing Player screen already fix;
- define what the Player, Home, or the Listening tab look like.

# Ownership boundary

## This specification owns
- the Active Queue snapshot as a persisted object;
- the current Note within that snapshot;
- the current structural position within that Note;
- session state sufficient to restore playback continuation;
- listening history, its ordering, and its retention;
- resume, fallback, and the spoken announcement of a non-exact restore;
- the difference in behavior between Continue, Play, and activating a completed session.

## This specification does not own
- block, section, and chunk identity rules, which belong to the Notion Integration Implementation Spec;
- the Active Queue contract itself, which belongs to the Product & Architecture Contract;
- which surfaces display history, which belongs to the UI Screens & Navigation Contract;
- Saved Queue definitions and Smart Queue query evaluation, which belong to the future Queue Creation & Queue Management specification.

## The identity rule this specification inherits and must not weaken

The integration spec is unambiguous that numeric chunk indexes alone are not stable persistence identities because document edits can shift indexes. The handoff type is:

```plain text
ReaderDocumentPosition(
    sourceBlockId: String?,
    chunkId: String?,
    sectionId: String?,
    characterOffset: Int
)
```

This specification persists that type verbatim. It must not add, store, or persist a numeric chunk index, even as a convenience or a cache hint, because a stored index invites exactly the silent remapping the integration spec forbids. A numeric index is a transient playback coordinate and is converted to identities by the integration layer while it still holds the document.

# Definitions
- Session — one listening occurrence. It owns an Active Queue snapshot, a current Note, a position, and a playback state.
- Unfinished session — a session that has begun and has not reached the end of its queue. This is what Continue listening offers.
- Completed session — a session whose final Note finished. This is what Recently completed lists.
- Session record — the persisted form of a session. It is a description, never a source of truth for Note content.
- Exact restore — a restore where the saved position still resolves to the same chunk.
- Inexact restore — a restore where the saved position resolves to something less precise, or to nothing.
- Listening activity — the last moment the session changed in a way the listener would recognize: starting, resuming, pausing, advancing a Note, or a position change while playing.

# The session model

## One model for one Note and for many

A one-Note playback session follows exactly the same model as a multi-Note queue. This is a Product & Architecture Contract invariant and it is restated because persistence is where it is easiest to break: there must be no separate code path, store shape, or restore behavior for a session of one.

Playing one Note creates an Active Queue containing exactly that Note. That session is persisted, surfaced in Continue listening while unfinished, and listed in Recently completed when finished, like any other.

## What the session owns, and what stays outside it

The session owns sequence and intent. It does not perform I/O. Loading a Note is the responsibility of the layer that owns cancellation, retry, and the error taxonomy, and persistence is the responsibility of the layer that owns storage. Two consequences, both deliberate and both already reflected in the implementation:
- the session reports that it is waiting for a Note and may be handed a document, rather than fetching one;
- the session exposes its state as a value that can be snapshotted, rather than writing that value anywhere itself.

This keeps the session's whole state machine synchronous, which is what makes restore and transition behavior testable without a dispatcher and without waiting.

## Snapshot immutability survives persistence

An Active Queue's membership and order remain fixed for the lifetime of its session, and restoring a session must not change that. In particular, restoring must never re-evaluate the thing the queue was created from. A queue whose origin is a Browse result set restores its stored membership; it does not re-run the Browse query. A queue whose origin is a Smart Queue or a Saved Queue restores its stored membership; it does not re-evaluate the query and does not read the Saved Queue again.

Origin is descriptive. It is persisted for explanation, never for reconstruction.

# The persisted session

## Shape

A session record stores, at minimum:
- a schema version;
- a session identity;
- the Active Queue snapshot: an ordered list of Note identities, each carrying the stable page identity and the title;
- the queue origin;
- the index of the current Note within the snapshot;
- the structural position within the current Note, as a ReaderDocumentPosition;
- the session's completion state — unfinished or completed;
- the time of the last listening activity;
- whether the session was playing or paused when last written.

## Why the title is stored

A Note identity is a page id, and a page id cannot be spoken. The queue must be able to name the Note it is about to read before that Note document has been fetched, because the title is announced at a Note transition and because Continue listening must be able to describe a session before anything is loaded. The title is therefore persisted as a display label, and it is never used as identity. Duplicate handling, ordering, and position all use the page id.

A title stored here can become stale after the source page is renamed. That is accepted: the title is a label for a history entry, and correcting it requires fetching the page, which restoration deliberately does not do.

## What is deliberately not stored
- Note content. Chunks and section text are fetched, never cached. A cache would be a second source of truth that can disagree with the workspace, and disagreeing with the workspace is precisely what a reading app must not do silently.
- Per-Note resume positions. The Product & Architecture Contract lists maintaining permanent resume positions for individual Notes independently of listening sessions as a non-goal. Exactly one position is stored, and it belongs to the session, not to the Note.
- A numeric chunk index. See the inherited identity rule above.
- Any derived playback coordinate. Sections, spans, and progress fractions are recomputed from the document after loading.

## Schema versioning

A session record carries a schema version. If a stored record's version is not one this implementation understands, the record is discarded rather than migrated. This is a deliberate v1 policy: a migration that guesses wrong restores a listener to a place they were not, which is worse than an empty history that the listener can immediately rebuild by playing something. The policy is recorded here so that a later version is a decision rather than an accident.

# Position capture and resolution

## Capture

A position is captured from the document while the session still holds it, which is the only moment the conversion from a transient index to stable identities can be made. The session captures a position when:
- a Note starts or resumes,
- the current chunk changes during playback,
- the listener moves to the next or previous section,
- the session pauses or stops,
- the session advances to the next Note,
- the session completes.

Capturing at every chunk boundary is required rather than optional. Capturing only on pause would mean a session interrupted by process death while playing loses everything since the last pause, which is the common case on a phone.

## Resolution

On restore, the stored position is resolved against the freshly loaded document. Resolution reports facts, and the integration spec fixes its vocabulary:
- CHUNK — the exact chunk still exists.
- BLOCK — the source block survived but that particular part did not.
- SECTION — the block is gone but the section it lived in survives.
- NONE — nothing survived.

Resolution never remaps a position to whatever now occupies the old numeric index.

The match quality is recorded as a fact when a session is restored inexactly. It is never presented to the listener as a technical report.

# Resume behavior

These rules are normative. They settle what the integration spec refused to decide.

## CHUNK — silent, exact

Playback resumes at the resolved chunk. Nothing is announced. The listener was inside a sentence or a paragraph, and the text itself is the continuation.

## BLOCK — announce the section, then play

Playback resumes at the first surviving chunk of the source block. Because the listener has moved backwards to the start of a block, and because the block may have been shortened, lengthened, or re-split, the session announces the containing section title when the section has one, then plays.

## SECTION — announce the section, then play

Playback resumes at the first chunk of the surviving section. The section title is announced when it has one, then playback begins. This is the narrowest truthful answer: the listener's own paragraph is gone, but the section it belonged to is not.

## NONE — announce the loss, then start the Note from its beginning

The position is lost. The session must:
1. start the Note from its first chunk;
1. announce that the previous position could not be found, before speaking any content;
1. keep the session and its queue position intact.

It must not silently start from the beginning, because a listener who resumes mid-book and hears the first sentence again without explanation will believe the app lost their place and cannot tell whether it did so deliberately. It must not skip the Note, because the Note is still readable and skipping silently drops content the listener asked to hear in the same breath. It must not abort the queue, because the remaining Notes are unaffected.

## Moving between Notes is not a resume

When a session is restored, only the current Note gets a position. Advancing to the next Note always starts that Note from its beginning, exactly as it does in a live session. A restored position must never be applied to a Note other than the one it was captured for.

# Continue, Play, and completion

## Continue only ever restores

Continue resumes an existing listening session. It restores that exact Active Queue snapshot and that exact saved position, opens the Player, and does not start a new session.

## Play always starts a new session

Play must not mean resume. A new Play action creates a new Active Queue and a new listening session and starts from the beginning of the new queue's first Note. It never restores historical progress, and it never merges with an existing session.

For a single Note, this means the Note screen's Play always begins at the first chunk, even when a previous session on that same Note is sitting in Continue listening with a saved position. The previous session remains available under Continue listening and is unaffected.

## Activating a completed session starts a new session from the beginning

A completed session is history, not resumable state. Activating it:
1. starts a new listening session;
1. using the historical frozen snapshot's membership and order, unchanged;
1. from the beginning of its first Note;
1. without regenerating the original Smart Queue, Saved Queue, or Browse result set.

The new session gets its own identity and is persisted as its own record. The historical record is left as it was.

## Completion is a fact, not a deletion

Reaching the end of the final Note marks the session completed and stops automatic progression. It does not remove the session from history, and it does not implicitly start anything.

# Listening history

## Two lists, one order

History is the set of retained session records, presented as two lists:
- Continue listening — unfinished sessions, which are resumable.
- Recently completed — completed sessions, which are replayable from the beginning.

Both are ordered by most recent listening activity first. A session moves from the first list to the second when it completes.

## What is surfaced where

The UI Screens & Navigation Contract owns which surface shows what, and this specification does not change it. Two consequences are stated here because they follow from the model rather than from layout:
- Home shows a small subset of Continue listening and omits the section when there is nothing to resume.
- The Listening tab shows both lists and omits a section heading whose list is empty.

## The live session is history's source

The in-memory session is the truth while it exists. History is a projection of what has been written from it, plus previously retained sessions. A session currently in memory must appear in Continue listening without waiting for a write to complete, so that a listener who pauses and immediately looks at Home sees the session they just paused.

## Identity and deduplication

History entries are identified by session identity, not by Note identity. Two sessions that read the same Note are two entries, because they are two listening occurrences. Sessions are never deduplicated by title.

# Retention

These limits are settled here because the UI Screens & Navigation Contract defers them to this specification.
- Unfinished sessions: keep the 10 most recently active. The session that is currently in memory is always retained regardless of the limit.
- Completed sessions: keep the 20 most recently active, and no completed session older than 30 days.
- When a limit is exceeded, the least recently active entry of that list is discarded.
- Retention applies to stored records. It never removes or interrupts the live session, and it never changes what is currently playing.

Rationale for the asymmetry: an unfinished session is a place the listener intends to return to, and losing it means losing a place. A completed session is replayable from the beginning at any time and its value decays with age, so it can be pruned more aggressively.

# Process death and launch

These rules settle what the UI contract defers to persistence and session specifications.

## Writes

Persistence is never on the playback path. A session record is written when the session's persisted-visible state changes — starting, resuming, pausing, changing Note, changing position, completing — and a failed write must not stop, pause, or alter playback. A write failure is a lost history entry, not a playback failure.

## Launch restores state, not playback

On launch:
1. history is read and surfaced;
1. the most recent unfinished session is not played, not opened, and not resumed automatically;
1. no Note content is fetched for a session that the listener has not asked to continue.

Nothing in the application may start speaking without a listener action. This follows the same principle as the UI contract's requirement that opening a Note never automatically starts playback, applied to launch.

## Continue is what restores playback

Pressing Continue on a history entry restores the snapshot and the position, opens the Player, and begins playback. Restoration of the state is therefore offline and immediate; restoration of playback requires fetching the current Note, and may therefore fail like any other load.

## If history cannot be read

A history read failure is reported as an empty history. Continue listening and Recently completed are omitted, the rest of the application remains usable, and no error state is presented for a feature the listener did not ask for. Home must still offer a route out, as it does when there is nothing to continue.

# Failure handling

## A restored Note that will not load

The existing error taxonomy applies unchanged. A Note that cannot be loaded during a restore stalls the session at that Note, exactly as it does mid-queue: the position is kept, nothing advances, and Retry re-attempts the same Note. A restore must never skip a Note it cannot load.

## A restored Note the credential can no longer see

A Note that is deleted, moved out of the integration's access, or otherwise unavailable resolves as an ordinary load failure and stalls. The session is not discarded, because the remaining Notes in the queue may still be readable.

## A restore into an empty document

If the resolved document has no chunks, the existing UNSUPPORTED_DOCUMENT_STRUCTURE behavior applies: it is a normal load failure for that Note, and it stalls the queue.

## A save that fails

Already defined above: a failed write affects history only. It must not surface as a playback error, and it must not block the writing of later records.

# Accessibility
- A history entry is one focus stop. Its title, its queue position, and its state are read as one item, and Continue is an explicit named control beside it, not part of the entry's announcement.
- Relative times are spoken as relative time. A history entry must not announce a raw timestamp.
- The announcements required by inexact restore are live-region announcements, added to the spoken sequence, and must be brief. They must not replace or delay the Note's content beyond the announcement itself.
- A section title announced by an inexact restore must be announced once. It must not be announced again when the Player renders, because a duplicate announcement immediately after a resume is indistinguishable from a stutter.
- Nothing in history may be reachable only by a gesture. Continue, and Play for a completed session, are ordinary accessible controls.

# Test strategy

## Unit tests

Persistence logic must be unit-testable on the JVM with no device, no dispatcher, and no network. The store is an interface, and its test double is in-memory, so that:
- a session record round-trips: snapshot, index, origin, position, completion state and activity time all survive a write and a read;
- a one-Note session and a multi-Note session round-trip through the same shape;
- a record whose schema version is unknown is discarded rather than migrated;
- retention keeps the 10 most recently active unfinished sessions and the 20 most recently active completed sessions, and drops a completed session older than 30 days;
- retention never discards the session currently in memory;
- history is ordered by most recent listening activity first;
- a session moves from Continue listening to Recently completed on completion, and does not leave the first list behind;
- activating a completed session creates a new session from the beginning and leaves the historical record untouched;
- Play on a Note with a retained session creates a new session and does not consume the retained position;
- origin is never re-evaluated: a restored Browse-origin queue keeps its stored membership even when the underlying source would now produce a different set.

## Resolution and resume tests

These are the behaviors the integration spec refused to define, so they are the tests that matter most:
- an unchanged document restores to the exact chunk, and announces nothing;
- an edited document restores to the source block when the chunk is gone, announces the section title once, and plays from the block's first surviving chunk;
- a deleted block restores to its surviving section, announces the section title once, and plays from the section's first chunk;
- a deleted block whose section is also gone starts the Note from the beginning, announces the loss before any content, and keeps the queue position and the session;
- a restored position is never applied to any Note other than the one it was captured for;
- a position captured before an edit and resolved after it is never applied to whatever chunk now occupies the saved index.

## Instrumented tests

Semantics-level checks for the surfaces this specification makes possible: that a history entry is one focus stop with a name and a state, that Continue and Play are reachable and labelled, that an empty history omits the section rather than rendering an empty list, and that the resume announcement is a live region on the announcement itself rather than on a container that does not merge its descendants.

## Device validation

Because the point of this specification is that listening survives things, the acceptance check is behavioral and on a device: start a queue, listen past several Notes, force-stop the process, relaunch, and confirm that Continue listening names the right Note at the right position and that Continue resumes there. Then edit the source Note mid-session and confirm the four resolution paths are distinguishable by ear.

Process death is deliberately induced with a force-stop rather than a backgrounding, because a backgrounding does not produce the case this specification exists for.

# Implementation phases

## Phase 1 — The record and the store

Define the session record type and the store interface. Implement an in-memory store and a device-backed store behind the same interface. No behavior change: nothing reads or writes history yet.

## Phase 2 — Snapshot and restore on the session

The session gains the ability to expose a snapshot of itself and to be restored from one, including its queue, its index, its position and its completion state. Still synchronous and still scope-free. No surface reads it yet.

## Phase 3 — Resume policy

Implement the four resolution paths and their announcements. This is the phase the integration spec was waiting for.

## Phase 4 — History and retention

Wire recording on session changes, reading at launch, ordering, completion transitions, and the retention limits.

## Phase 5 — Surfaces

Continue listening reads real history rather than only the live session. Recently completed becomes readable. The mini-player continues to observe the live session and is unaffected.

# Acceptance criteria
1. A session paused and force-stopped resumes, after a relaunch, at the same Note and the same chunk.
1. A session interrupted while playing resumes within one chunk of where it was, because positions are captured per chunk rather than per pause.
1. A Note edited after a position was captured produces the correct one of the four resolution outcomes, and the outcome is announced when it is not exact.
1. A Note whose entire saved position is gone starts from the beginning, says so first, and does not skip.
1. Continue listening lists unfinished sessions most-recent-first, and Recently completed lists completed sessions most-recent-first.
1. Activating a completed session plays the same Notes in the same order from the beginning and does not regenerate the source.
1. Play on a Note never resumes a retained position on that Note.
1. Retention holds at 10 unfinished, 20 completed, and 30 days for completed, without ever evicting the live session.
1. A history read failure leaves the application usable with no error state.
1. A history write failure does not affect playback.
1. No numeric chunk index is persisted anywhere.
1. The session remains synchronous and scope-free: no suspend function, and no coroutine scope, is held by the session.

# Architectural invariants
1. Resume state belongs to a listening session, never permanently to a Note.
1. Exactly one session position is stored, for the session's current Note.
1. Persisted positions are expressed only in the integration spec's stable identities.
1. An Active Queue's membership and order are never re-derived from its origin after creation.
1. Play starts a new session; Continue restores one; activating a completed session starts a new session from a historical snapshot.
1. A one-Note session is not a special case in storage, restore, or history.
1. Persistence is never on the playback path: no read and no write may stop, start, or delay playback.
1. Launch never begins playback.
1. A Note that cannot be loaded stalls; it is never skipped.
1. A history entry is identified by session identity, never by Note identity or title.

# Open questions

None. The two items this specification was created to settle — retention limits for listening history, and exact process-death restoration — are settled above.

# Next implementation step

Implement Phase 1 and Phase 2 against the existing reader domain, which already exposes the handoff types these phases consume. Both phases are JVM-testable with no device and no network, so the restore and resume behavior can be proven before any surface depends on it.

