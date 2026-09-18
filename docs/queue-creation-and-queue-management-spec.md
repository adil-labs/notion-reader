<!--
  HAND-AUTHORED GOVERNING DOCUMENT - not a Notion mirror.

  Like docs/background-playback-and-media-controls-spec.md, this file is NOT generated and has no Notion
  counterpart: it is authoritative as it stands, in this repository. tools/export-notion-doc.ps1 does not
  know about it. Editing it here is the supported workflow.

  Because it has no page in Notion it cannot amend any document that IS a mirror. An amendment to one of
  those still has to be made in Notion and re-exported. See docs/decisions.md 23.9.
-->

# Notion Reader — Queue Creation & Queue Management

> Governing implementation specification. Settles what a Saved Queue is, how it is stored, and every
> operation that creates, changes or plays one.

# Summary

This specification exists because it is the one thing the queue work is blocked on, and because reading the
three contracts against each other shows the blocker is narrower than it looked.

The Product & Architecture Contract already settles almost all of Saved Queue *behaviour*: the operations
(create, name, add, add a result set, remove, reorder, rename, delete, play), the identity rule
("deduplicate by stable Note identity", never by title), the rule that adding must not start or interrupt
playback, and the stored fields ("a stable queue identity; a user-visible name; ordered Note identities;
persistence metadata"). The UI Screens & Navigation Contract settles the surfaces and the interaction: the
Saved tab, Saved Queue Details, an explicit **Edit** mode whose reordering "must never require drag-and-drop",
and the Queue Destination rules.

**Exactly one thing across the whole feature has no owner, and this is it: the persistence schema.** The UI
contract explicitly defers "exact Saved Queue persistence schema"; the Notion Integration Implementation Spec
delegates Saved and Smart Queue persistence away from itself; and the Active Queue & Reader Session spec scopes
both out twice. That is a small gap to fill and a large amount of behaviour to *not* re-decide, and the shape
of this document reflects that: most of it is derived, and what is decided here is decided once.

The consequence, recorded because it is easy to trip over: **a downstream specification may add detail but
must not silently redefine** the operations or identity rules the Product & Architecture Contract already
states. Where this document restates one, it is restating rather than deciding, and the contract wins.

# Goals

- Give Saved Queues a stored form that a later version can change deliberately rather than by accident.
- Settle the reorder, duplicate and rename semantics precisely enough that the Edit screen is unambiguous.
- Make every operation's failure mode explicit, so that a storage problem is never something the listener
  experiences as a playback problem.
- Reuse the store the session already has rather than inventing a second one with its own version policy.
- Close the one dead end in the queue work: a Note that will not load.
- Keep everything here unit-testable on the JVM with no device and no dispatcher.

# Non-goals

This specification does not:

- **define Smart Queues**, which are out of scope by decision (`docs/decisions.md` §23.3, D30). They need
  this document *and* the Content Metadata & Workspace Schema Adapters specification, because a Smart Queue
  is a stored filter set and §5 measured that this workspace cannot supply several of the fields such a set
  would name. Nothing here may be read as settling one;
- **define the Active Queue**, which the Product & Architecture Contract owns and the Active Queue & Reader
  Session spec implements. This document consumes `ActiveQueue.snapshotOf` and adds nothing to it;
- define Browse, search, filtering or sorting;
- define the Player, its commands, or playback;
- reproduce the Notion editor. A Saved Queue is a playlist over Notes, not a database;
- define cross-device synchronisation, sharing, or any form of collaboration. A Saved Queue is local;
- define a Saved Queue's *content*. A queue stores identities, never Note text, for the reason the session
  spec gives about its own records: a stored copy of content is a second source of truth that can disagree
  with the workspace.

# Ownership boundary

## This specification owns

- the **persistence schema** of a Saved Queue, and its version policy;
- **reorder semantics** at the boundaries: what each of the four accessible moves means, and that a move
  that changes nothing is a no-op rather than a write;
- **duplicate semantics** for every add path, including what a caller is told when nothing was added;
- **name rules**: what is accepted, what is rejected, and whether two queues may share a name;
- **delete and rename** behaviour, including what happens to a playing session that came from the queue;
- the **consistency model**: one writer, atomic writes, and what a failed write means;
- the **Queue Destination**: what it lists, what it deliberately does not, and its dedupe rule;
- **Edit mode** as a state rather than a mode flag, and its exit behaviour;
- what a **stalled Note** offers in a queue, which §23.7 recorded as this document's to settle;
- the **store**: whether a Saved Queue reuses the session store's technology and policy.

## This specification does not own

- which screens exist, or their layout. The UI contract names Saved Queue Details, Edit Saved Queue and
  Queue Destination; this document defines what they *do*, not how they look;
- the operations themselves. They are the Product & Architecture Contract's;
- the Active Queue's rules, including that editing a Saved Queue "does not alter an Active Queue that was
  previously created from it". That is a contract invariant this document must satisfy, not one it sets;
- `Play` and `Continue` as verbs. "Play starts a new listening session" is the contract's.

## The conflict rule, restated because this document is a specification

If implementing this reveals that a contract-level rule must change, the Product & Architecture Contract's
protocol applies **in order**: update the contract in Notion, review the effect on dependent specifications,
update those, and only then implement. That order is not advisory. It is recorded here because this document
is the first place a Saved Queue's semantics would be changed, and therefore the most likely place for the
order to be inverted by accident.

# Definitions

- **Saved Queue** — a persistent, user-managed ordered collection of Note identities, with a stable identity
  and a user-visible name. "Playlists I built."
- **Queue identity** — the stable identifier of the queue itself, distinct from every Note identity in it
  and from its name. Never a title, and never a name.
- **Member** — one entry in a Saved Queue: a Note identity in a position. An entry is not the Note; the same
  Note may be a member of many queues, and a queue's order is a property of its *entries*.
- **Add** — the operation that appends members to an existing Saved Queue, deduplicating by Note identity.
- **Queue Destination** — the chooser that `Add to queue` and `Add all to queue` route through.
- **Edit mode** — the explicit state in which a Saved Queue's membership or order may be changed.
- **Reorder** — one of the four accessible moves: up, down, to top, to bottom.
- **Stall** — the state of a session whose current Note will not load. Defined by the Active Queue & Reader
  Session spec; this document defines only what a queue offers in that state.

# The Saved Queue

## Identity

A Saved Queue has a **queue identity** that is assigned when it is created and never changes. It is not its
name and it is not derived from its contents.

Three separate things are easy to conflate here and must not be:

| What | Stable across | Used for |
|---|---|---|
| queue identity | everything, including a rename | referring to *this* queue |
| queue name | until renamed | display, and the listener's recognition |
| member entries | until the queue is edited | ordering and display |

The distinction matters because of `QueueOrigin`. The contract says origin "is descriptive. It must never
cause the Active Queue to re-evaluate or synchronize with its source", and a name satisfies that — but a name
is also *not an identifier*: two queues may share one, and a rename would make a stored name stale. So
**`QueueOrigin.Saved` carries the queue identity, with the name as a label**, and the same for `Smart`
(which this document does not otherwise define). That is a schema change and is recorded as one below.

## Name

- **Required.** A Saved Queue always has a non-blank name, because the entire Saved tab lists queues by name
  and a blank one is unidentifiable to a listener using a screen reader.
- **Not unique per queue identity, but warned about.** Two queues may share a name. Enforcing uniqueness would
  mean rejecting a name a listener chose, and the queue identity already disambiguates; but the Create and
  Rename screens must say when another queue already has that name, so a listener who did not intend a
  duplicate can see it before saving. This is the same posture as a duplicate Note in a queue: permitted, and
  not hidden.
- **Trimmed, and compared case-insensitively for the warning only.** `"Reading"` and `"reading"` are two
  different names that a listener will read as the same one, so the warning covers both; identity never
  depends on the comparison.
- **No length limit is imposed by this specification.** A stored name is a label bounded by what the store can
  hold, and inventing a limit the contracts do not name would reject a name for no reason a listener could
  understand.

## Membership

A queue's members are an **ordered list of Note identities**. Three rules:

1. **Order is a property of the list, and it is the user's.** Nothing sorts a Saved Queue implicitly — not
   title, not date, not "recently added". The Saved tab lists queues; the queue lists members in the order
   the user put them, because "Play" snapshots that order and plays it.
2. **A Note may appear at most once.** This is the contract's duplicate handling rule, and it applies to
   every path that adds members. It does *not* apply to a queue constructed from one, which is the Active
   Queue's business.
3. **An empty Saved Queue is valid.** It is what "Create saved queue" produces before anything is added, and
   the contract names creating one deliberately as a supported operation. It is shown, not hidden.

## Origin, and what it is for

A queue does not store its own origin. Origin describes *a listening session* — where its snapshot came
from — and is written by the session, not by the queue. The only consequence for this specification is the
identity rule above: when a session is created from a Saved Queue, the origin it carries names that queue by
**identity**, so that a later surface can say which queue a session came from even after a rename.

# Persistence

## The schema

A stored Saved Queue is a record with:

| Field | Purpose |
|---|---|
| `schemaVersion` | the version policy gate — see below |
| `id` | the queue identity |
| `name` | the display label |
| `notes` | the ordered member entries, each a stable page identity, each carrying its title |
| `lastEditedAt` | persistence metadata, and the ordering key for the Saved tab |

The title is stored per member for the same reason the session record stores one: **a page id cannot be
spoken.** The Saved tab and the queue's own list must name a member before its document has been fetched, and
a queue is a list of names to the listener. Like the session's, a stored title can go stale after a rename of
the source page; that is accepted, and correcting it requires fetching the page, which listing a queue
deliberately does not do.

Nothing else is stored. In particular:

- **No Note content.** A stored copy of content is a second source of truth that can disagree with the
  workspace, which is the failure a reading app must never have silently.
- **No playback position.** Resume state belongs to a listening session and is the session spec's. A Saved
  Queue has no position; where a listener got to is a fact about a session, and both the Product contract
  ("maintain permanent resume positions for individual Notes independently of listening sessions" is a
  non-goal) and the session spec forbid this.
- **No member count.** It is `notes.size`, and a stored copy is a second thing to keep in step.
- **No origin.** See above.

## Version policy

A stored record carries `schemaVersion`. **A record whose version is not the one this implementation
understands is discarded rather than migrated**, which is the same v1 policy the session spec states and for
the same reason: a migration that guesses wrong hands the listener a queue they did not build, and losing a
queue is visible and recoverable while a silently wrong one is neither.

The policy is per-record, so one unrecognised queue does not make the rest unreadable. That has a direct
consequence for the decoder, recorded because it is easy to get wrong in the same way twice: **each record is
decoded on its own, not as one array decode**, exactly as `SessionCodec` already does. A set-wide decode turns
a record-wide failure into a set-wide one.

## The store, reused rather than invented

**A Saved Queue reuses the session store's technology and its version policy**, and this is a requirement
rather than a preference. `docs/decisions.md` §20.4 ranked this work second partly on the argument that "a
session store exists, so Saved Queues must reuse it rather than invent a second", and a second codec with its
own schema version is a second thing to get wrong: two decoders can drift on what an unknown version means,
on whether unknown keys are dropped, and on how malformed input is reported.

Concretely, the pattern to follow is the one already in the tree:

- an interface in `domain` (`SavedQueueStore`), Android-free, with `suspend` methods and **no error channel**;
- an encoding object beside it (`SavedQueueCodec`), pure and JVM-testable, decoding record by record;
- an implementation in `data.local` (`DataStoreSavedQueueStore`) holding the bytes, catching storage
  exceptions narrowly, and reporting a failed read as "no queues" and a failed write as a dropped write.

Both interfaces return **no `Result` and no error type**, for the reason `SessionStore` documents: failure is
not a condition a caller branches on, and giving the interface an error channel invites exactly that
branching. A storage failure is a lost queue, never a playback failure.

### The one divergence, and it is forced rather than chosen

Queues are changed through **a single operation that reads, decides and writes** — `SavedQueueStore.transact` —
rather than through a read followed by a write.

The two-step version is unsafe for a reason that follows directly from the rule above. A read must report a store
it cannot read as "no queues", because that is what a caller *displaying* queues needs. So a decision taken on
top of that answer is a decision about an empty library, and writing the result back wholesale removes every
queue the store actually holds. Of the six operations, exactly one reaches this: **`Create`**, whose result does
not depend on what was read — so it alone would have replaced a listener's whole library with the single queue
they had just made, in an app that otherwise looks fine. Every other operation needs its target to have been
read, and so answers "no such queue" and writes nothing.

Doing the read and the write in one call makes that impossible rather than merely avoided: an implementation
performs both inside one transaction, so a read that fails means the transaction never runs and nothing is
committed. The rule this adds is not a new product rule — a failed read is still reported as "no queues" by
`read`, and a failed write is still a dropped write — but it does add one structural requirement, and a store
implementation that ignores it reintroduces the whole failure.

## The `QueueOrigin` change, and its version bump

`QueueOrigin.Saved(name: String)` and `QueueOrigin.Smart(name: String)` are replaced by variants carrying
**an identity and a label**. This is a deliberate `SESSION_SCHEMA_VERSION` bump: stored sessions carrying the
old shape are discarded rather than reinterpreted, which is the stated v1 policy.

It is worth stating why this is a bump rather than a compatible addition. The old field's *meaning* changes —
from "which queue, approximately" to "which queue, exactly" — and a decoder that accepted both would produce
a session whose origin identifies nothing, which is worse than losing the session, because the session still
works and only the explanation is wrong.

# Operations

Every operation below is the Product & Architecture Contract's. What this document adds is the precise
semantics, and in particular the failure and no-op cases the contract leaves open.

## Create

- Assigns a new queue identity, at the moment the screen is confirmed rather than when it is opened. An
  abandoned Create screen creates nothing.
- Requires a non-blank name.
- Produces an empty queue, which is a valid state.
- **Does not start playback**, per the contract.
- Presented as `Create saved queue` on the Saved tab, and reachable from Queue Destination when the user
  chooses to create rather than select.

## Rename

- Changes the name and nothing else. Identity, membership and order are untouched.
- Applying the same name is a **no-op, not a write**. A write that changes nothing is a chance to lose data
  for no benefit.
- A rename does **not** reach into any stored session's origin as a *label* update — but because origin
  carries identity, a renamed queue is still correctly identified. **This is the whole reason for the
  identity change above**, and it is the case a name-keyed origin would have got wrong: the session's origin
  would have gone on naming a queue that no longer exists under that name.

## Add

- Appends to the **end** of the queue. There is no insert-at-position operation: the contract's reorder
  operations exist for placing a member, and an Add that also positioned would be a second way to do one
  thing.
- **Deduplicates by Note identity**, per the contract, and never by title.
- **Reports what it did.** A caller is told how many members were added and how many were already present.
  This is not decoration: the common case is `Add all to queue` over a result set that overlaps the
  destination, and a listener who adds twelve things and is told nothing cannot tell "all twelve were already
  there" from "the app failed". The contract requires the dedupe; being silent about it is what makes the
  dedupe indistinguishable from a failure.
- **Adding nothing is a success, not an error.** If every incoming Note is already a member, the operation
  succeeded and changed nothing. It is reported as such.
- **Does not start or interrupt playback**, and does not modify an Active Queue.

## Remove

- Removes one member by Note identity.
- Removing the only member leaves an empty queue, which is valid. It is not a delete.
- Removing a Note that is not a member is a no-op, not an error — the postcondition the caller wants is
  already true.

## Reorder

Four accessible moves, per the UI contract: **Move up, Move down, Move to top, Move to bottom**. Each is
defined by its postcondition rather than by an index arithmetic, so an implementation cannot invent a
different boundary behaviour:

| Move | Postcondition |
|---|---|
| Move up | the member is immediately above where it was; at the top, nothing changes |
| Move down | the member is immediately below where it was; at the bottom, nothing changes |
| Move to top | the member is first; if already first, nothing changes |
| Move to bottom | the member is last; if already last, nothing changes |

- **A move that changes nothing is a no-op and does not write.** This is the same rule as a rename to the same
  name, and it matters more here: a listener pressing "Move up" at the top of a list is telling the app what
  they want, and the app should not touch storage to achieve it.
- **Reordering never requires drag-and-drop.** The four moves are ordinary controls with names. A visual drag
  handle may exist as an additional interaction for sighted users, but the four moves are the mechanism, and
  an implementation that offers only a handle has not implemented this section.
- **Reordering does not touch playback.** No member's identity changes, so any Active Queue created earlier
  is unaffected — the contract's "Saved Queue edits must not mutate an Active Queue already created from that
  Saved Queue".

## Delete

- Removes the queue and its members. The Notes themselves are untouched: a queue contains identities, and
  deleting a playlist does not delete the songs.
- **Destructive, so it is confirmed**, and confirmed in a way that names the queue. The Product contract's
  rule that the interface "must not become a control panel" applies in reverse here: one clearly-named
  confirmation is right, and a confirmation that does not say which queue is a hazard.
- **A playing session created from it is unaffected.** Its snapshot is its own, and the contract is explicit
  that origin "must never cause the Active Queue to re-evaluate or synchronize with its source"; a session
  whose source has been deleted is a session with a stale explanation and a perfectly valid queue. It is not
  an error state, and nothing is offered to "fix" it.

## Play

Per the contract, and this document adds nothing:

1. snapshot the queue's current membership and order;
1. create a new Active Queue from that snapshot;
1. start a new listening session;
1. open the Player, from the first member.

`Play` must not mean resume: a queue that has been played before is played again from the beginning, and any
existing session is replaced rather than continued.

**Play on an empty queue is not offered.** The control is disabled rather than inert, because a Play that can
do nothing is a control a listener cannot use, and "this queue is empty" is the information they actually
need.

# Queue Destination

`Add to queue` and `Add all to queue` both route here. The UI contract is explicit about what it shows, and
this section states the dedupe rule precisely because that is the part left open.

**Shows:**

- every existing Saved Queue, with its member count, most recently edited first;
- `Create new queue`.

**Does not show:**

- Smart Queues, which "are not valid manual-add destinations" — their membership is query-defined;
- the Active Queue, which "is playback state. It is not a general persistent destination for organizing
  content from Browse";
- any concept of a current or default queue. The contract's non-goal is an "ambiguous global concept of a
  Current queue while the user is merely browsing", and a destination screen is exactly where such a concept
  would take hold.

**Selecting a Saved Queue:**

- adds the incoming Notes to the **end** of it;
- deduplicates by Note identity;
- preserves the destination's existing order, which is to say it changes nothing about existing members;
- does not start playback;
- does not affect any Active Queue;
- reports what it did, on the same rule as Add above.

**If no Saved Queue exists**, the application may go directly to Create Saved Queue rather than showing an
empty chooser, per the UI contract. The new queue receives the incoming Notes, so the listener's intent —
"these things, into a queue" — is completed rather than abandoned at a screen with nothing on it.

# Edit mode

## State, not a flag

Saved Queue Details has two states, and they are modelled as such:

```plain text
SavedQueueDetailsState = Viewing | Editing
```

Not a boolean, deliberately. A boolean named `isEditing` would be the same fact, and the reason to model the
state instead is that the *operations available* differ between the two and are derived from the state rather
than checked beside it: `Remove`, the four moves and `Done` exist in Editing and nowhere else. That is the
same shape as this project's other corrections — a boolean answers the wrong question when what matters is
which state you are in (`docs/decisions.md` §19, §22.2).

**In Viewing:** the queue's members, each opening its Note; `Play`; `Edit queue`; `Rename`; `Delete`.

**In Editing:** the queue's members, each with the four moves and `Remove`; and `Done`.

The UI contract's reason for the split is that "the normal Saved Queue Details screen should not expose
multiple destructive/reorder controls on every Note". That is an accessibility requirement as much as a
visual one: a screen where every row carries five controls is a screen a screen-reader user cannot traverse,
and it puts a destructive action one focus stop from an ordinary one.

## Entering and leaving

- **Entering Edit mode is explicit.** Nothing about opening a queue puts it in a state where it can be
  changed.
- **`Done` leaves it.** The label states what it does; "Cancel" would promise to undo, which this
  specification does not offer.
- **Android Back leaves Edit mode**, and does not leave the screen. This is the one place where Back does
  something other than reverse navigation, and it is the UI contract's own rule for Edit Saved Queue
  ("follow explicit edit-state behavior defined by queue-management spec") — this is that definition. The
  alternative, Back leaving the screen with edits applied, would make the platform's most reflexive gesture
  commit changes.
- **Edits are applied as they are made**, not held until `Done`. `Done` is therefore an exit, not a commit.
  Stated because the opposite is a defensible design and the difference is user-visible: a listener who
  reorders and then leaves by some route that is not `Done` should not silently lose the reorder.

# Consistency

- **One writer.** The application is the only writer of its own store. There is no cross-process, no
  cross-device, no shared file.
- **Writes are atomic**, which the reused Preferences DataStore already provides. A queue is written whole or
  not at all, so an interrupted write cannot leave a half-reordered queue.
- **A failed write is a lost edit, not an error state.** No operation in this specification reports a storage
  failure to the listener as a failure of the operation. This is the session spec's posture applied to
  queues: persistence is never on the playing path, and a supporting surface's failure is not the primary
  action's failure.
- **No last-write-wins reconciliation is defined, because there is no second writer to reconcile with.** If a
  future version adds one, this is the section that changes.

# The stalled Note

`docs/decisions.md` §19.9 recorded that a Note which will not load is a dead end in a queue, with Retry as
its only offer, and §23.7 attributed the policy to this document. This section settles it, and it settles it
narrowly.

**What already holds, and does not change:** a Note that cannot be loaded **stalls** the session at that
Note. The position is kept, nothing advances, and Retry re-attempts the same Note. The Active Queue & Reader
Session spec is explicit that it "is never skipped", and that rule is about the *application* never advancing
past a failure on its own. It is not amended here.

**What this document adds:** the listener may move past it deliberately, using the Player's existing
`Previous note` and `Next note`. That is already implemented (`docs/decisions.md` §22.6) and this document
ratifies rather than introduces it, because the distinction is the whole point: an app that skips silently
drops content the listener asked to hear, and a listener pressing a control that says what it will do on a
queue whose membership has not changed is not that.

**And nothing further.** Specifically, this specification does **not**:

- remove the Note from the queue. That is an edit to a queue, and the session's queue is frozen — editing an
  Active Queue is forbidden by the Product contract's "Active Queues are played", and this document has no
  standing to permit it;
- mark it failed, skipped, or unavailable anywhere. There is no such stored state, and inventing one for a
  transient network condition would be a permanent record of a temporary fact;
- offer an automatic skip after a timeout. Silence that resolves itself is indistinguishable from an app
  that lost the listener's place.

So a stalled Note offers exactly two things — **Retry**, and **move on** — and that is a decision rather than
an omission.

# Relationship to the other queue kinds

| | Saved Queue | Smart Queue | Active Queue |
|---|---|---|---|
| owner | this document | the Content Metadata spec (not written) | the Active Queue & Reader Session spec |
| membership | entered by the user | evaluated from a query | frozen snapshot |
| changing it | editing it | editing its query | not possible |
| the contract's verb | **edited** | **configured** | **played** |

The contract's summary — "Saved Queues are edited. Smart Queues are configured. Active Queues are played" —
is the rule this table exists to keep visible, because the three are adjacent in the UI and it is easy to
implement one as another.

**A Saved Queue is never re-evaluated.** Playing it snapshots it; editing it afterwards does not touch the
snapshot. There is no code path by which a Saved Queue write reaches a live session, and that absence is the
implementation of the contract's invariant rather than a discipline anyone maintains.

# Accessibility

- **Every operation is an ordinary named control.** No operation is reachable only by a gesture, and
  reordering specifically does not require drag — the contract states it as a requirement and this document
  treats an implementation without it as incomplete.
- **The moves are named by their effect**: `Move up`, `Move down`, `Move to top`, `Move to bottom`, `Remove`,
  `Done`, `Rename`, `Delete`. Nothing is called "more", "options" or an icon-only label.
- **A disabled move is disabled, not inert.** `Move up` on the first member is unavailable, and a listener
  is told so rather than pressing something that does nothing. This is the same rule the Player's controls
  follow.
- **One queue row is one focus stop**, and one member row in Details is one focus stop whose moves are
  separate controls beside it — the shape Queues already uses for history entries.
- **A reorder announces the result.** Moving a member changes the list, and a listener who cannot see the
  list has no other way to know where the member went. The announcement is the member's name and its new
  position, brief and once — this is the one operation here whose outcome is not otherwise observable.
- **The dedupe report is announced**, because "nothing was added" is information (see Add).
- **Times are relative, never raw**, for `lastEditedAt`, as elsewhere in this application.

# Test strategy

## Unit tests

All of this is JVM-testable, because the model, the codec and the store are all Android-free and there is no
dispatcher in either.

- a queue round-trips: identity, name, ordered members, titles and `lastEditedAt` all survive a write and a
  read;
- a record whose `schemaVersion` is unknown is **discarded, and the other records still decode** — the
  per-record rule, asserted with a mixed list rather than a single bad record, because a single bad record
  cannot distinguish per-record decoding from set-wide;
- encoding is stable: the same queue encodes to the same bytes twice, so a rewrite that changed nothing
  cannot silently churn the store;
- **Add deduplicates by identity, and two Notes with the same title are two members** — the case a
  title-keyed implementation gets wrong;
- **Add reports counts**, including the all-duplicates case which must be reported as success with zero
  added rather than as a failure;
- each of the four moves satisfies its postcondition at both boundaries, and a move that changes nothing
  produces **no write** — asserted against a recording store, not by inspecting the result;
- a rename to the same name produces no write, and a rename is visible through the *identity* afterwards;
- removing a non-member is a no-op and not an error;
- deleting a queue does not affect a session whose origin names it;
- Play on an empty queue is unavailable, and Play on a non-empty one snapshots rather than aliases — the
  assertion `ActiveQueue.snapshotOf`'s own test already makes at the session layer, repeated here because a
  Saved Queue is the second source that could hand a live list in;
- a store that cannot read reports no queues, and one that cannot write drops the write, with neither
  raising.

## Instrumented tests

Against the semantics tree, because that is what a screen reader consumes:

- Saved Queue Details in Viewing exposes no move and no Remove control;
- in Editing it exposes all four moves and Remove, and `Done`;
- the moves are disabled rather than inert at the boundaries;
- **reordering is possible without any gesture** — every move is reachable by an ordinary press;
- a reorder announces the member and its new position, once;
- Queue Destination lists Saved Queues and `Create new queue`, and contains no Smart queue and no current
  or default queue;
- an empty Saved Queue shows `Play` disabled and explains why.

## Device validation

A saved queue survives a process death (create it, `am force-stop`, relaunch, and find it unchanged with its
order intact), and an add over a result set that overlaps an existing queue reports the dedupe honestly.

# Implementation phases

## Phase 1 — The record and the store

`SavedQueue`, `SavedQueueStore`, `SavedQueueCodec`, an in-memory store and the DataStore implementation,
following the session pattern. No surface reads it. JVM-verifiable.

## Phase 2 — The model and its invariants

Create, rename, add, remove, reorder and delete, with the no-op and duplicate rules, as a pure model over the
store. JVM-verifiable, and this is where the failing-first proofs belong — the no-op rules in particular,
because "wrote nothing" is invisible from a screen.

## Phase 3 — The `QueueOrigin` change

Identity-carrying origin, the `SESSION_SCHEMA_VERSION` bump, and the session-side change that consumes it.
Deliberately its own phase: it is the one part of this that reaches into existing, working persistence.

## Phase 4 — The Saved tab and Saved Queue Details

Listing, Details in Viewing, and Play. Instrumented tests with it.

## Phase 5 — Edit mode

The moves, Remove, `Done`, and Back-leaves-edit-mode. The instrumented tests for reordering belong here.

## Phase 6 — Create, Rename, Delete

The three named operations and their confirmations.

## Phase 7 — Queue Destination, `Add to queue` and `Add all to queue`

The chooser, the dedupe report, and the create-if-empty path.

## Phase 8 — Home's Your queues

The last consumer, because it is a projection of what the first seven produce.

# Acceptance criteria

1. A Saved Queue survives process death with its identity, name, membership and order intact.
2. A record with an unknown `schemaVersion` is discarded, and the remaining records still decode.
3. Add never inserts a Note already present, by identity rather than by title, on any add path.
4. Add reports how many members were added, including when the answer is none.
5. Adding nothing is a success and is reported as one.
6. Every one of the four moves satisfies its postcondition at both boundaries.
7. A move or rename that changes nothing performs no write.
8. Reordering never requires drag-and-drop.
9. `Play` on a Saved Queue snapshots its current membership and order and starts a new session.
10. `Play` never resumes, and is unavailable on an empty queue.
11. Editing or deleting a Saved Queue leaves any Active Queue created from it untouched.
12. A `QueueOrigin` names a queue by identity, so a rename does not invalidate a stored session's origin.
13. Queue Destination lists Saved Queues and create-new, and nothing else.
14. No storage failure is ever reported as a failure of the operation the listener performed.
15. A stalled Note offers Retry and the ability to move on, and nothing more.
16. Back from Edit mode returns to Viewing rather than leaving the screen.
17. Every operation is an ordinary named control, and no operation is gesture-only.
18. A reorder is announced once, with the member and its new position.

# Architectural invariants

1. A Saved Queue stores identities and labels, never content and never a position.
2. A queue's membership and order change only through an explicit operation, and only in Edit mode for
   membership and order.
3. A Saved Queue write never reaches a live session; the Active Queue's snapshot is its own.
4. An operation that changes nothing writes nothing.
5. Persistence is never on the playback path, and a storage failure is never a playback failure.
6. A task-scoped surface owns no store, and the store is reached from the application layer.
7. The model, the codec and the store are Android-free and testable with no device.
8. No member's identity depends on its position, its title, or the queue's name.
9. Smart Queues are out of scope, and nothing here may be read as defining one.

# Open questions

- **Retention.** Sessions are pruned (10 unfinished, 20 completed, 30 days). Saved Queues are **not pruned
  by this specification**, because they are user-managed: discarding something a listener built is not the
  same as discarding a record of something they did. If a limit is ever wanted it needs a decision about
  what to do when it is reached — refuse a new queue, or discard the least recently used — and neither is
  implied by the contracts.
- **Where a queue's own identity is surfaced.** This document gives a queue an identity and does not name a
  surface that shows it. A listener identifies a queue by its name, which is correct; the identity exists so
  that *stored references* are stable, not so it is displayed.
- **Import and export.** Not defined, and not implied. Both would make a Saved Queue leave the device, which
  is a larger feature with its own questions about identity.
- **The exact edit-state behaviour of Back when edits have been made.** This document settles that edits are
  applied as they are made, so Back losing them is not possible; whether Back should also announce anything
  is a detail the Note-screen precedent would answer if it ever matters.
- **Whether a member's stored title should ever be refreshed.** Refreshing means fetching pages to list a
  queue, which is the eager loading this design avoids everywhere else. Listed as open rather than decided,
  because the cheap answer (never refresh) and the correct answer (refresh when the Note is opened anyway)
  are both defensible and the difference is only observable after a source page is renamed.

# Next implementation step

**Phase 1**, which is JVM-verifiable with no device and no surface: the record, the codec, the store
interface, an in-memory double and the DataStore implementation, following `SessionCodec` and
`DataStoreSessionStore` closely enough that the two cannot drift on the version policy. Then Phase 2's pure
model, where the no-op and duplicate rules get their failing-first proofs.

Phase 3 is the one to keep separate and to do deliberately: it is the only part of this that changes
persistence that already works, and it is the only part whose mistake is silent.
