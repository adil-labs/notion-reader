# Decision record — block conversion, resume, and cross-spec conflicts

**Date:** 2026-09-17
**Status:** revision 3 — tracks the first round of spec amendments.
**Method:** read all four governing Notion pages in full plus every accessible data-source schema,
settled each open decision against the text, had the result reviewed against the same corpus
(revision 2), then reviewed the resulting spec edits against the same corpus again (revision 3).

Revision history: rev 1 was the first draft. Rev 2 applied the product review's corrections to
D2/D5/D6/D9/D10/D11 and to C3 (§0). Rev 3 records the 11:16 spec amendment: C1 resolved via
option (b), and three new internal inconsistencies it introduced (§7, N1–N4).

Full mirrors of the governing pages are generated from Notion by
`tools/export-notion-doc.ps1 -All` into `docs/notion/`. Every quotation below is from those mirrors.

---

## 0. What revision 2 changed

Revision 1 was wrong, or too confident, on five points. The corrections are applied throughout; the
reasoning is recorded here because the reasoning is the durable part.

| # | Revision 1 said | Revision 2 says | What revision 1 got wrong |
|---|---|---|---|
| D2 | The leading section always exists, so the `TITLE` chunk has a home | An intro section is created **only when there is content before the first heading** | It invented an unconditional section purely to solve a problem that D9 dissolves. Empty structure to serve a workaround is not a requirement. |
| D6 | Unsupported blocks emit a `UNSUPPORTED` chunk with empty text | Unsupported blocks are preserved as **normalized nodes** and emit **no chunk** | It inferred "the enum has an `UNSUPPORTED` member, therefore it must be instantiated". That does not follow — and the spec's own Chunking test says the opposite (see below). |
| D9 | The title is emitted as a `TITLE` chunk, and the Player must not re-speak it | **No `TITLE` chunk.** `ReaderDocument.title` carries it; the Player announces it | It created the double-announcement problem and then patched it with a cross-spec instruction to the Player — an implementation spec dictating Player behaviour. Revision 2 removes the cause. |
| D10 | `to_do` checked state is metadata and is **never spoken** | Metadata, and *whether it is announced* is a Player/Note accessibility policy | "Never spoken" overreached: mapping decides representation, not narration policy. |
| D11 | `MAX_CHUNK_CHARS = 300` with a defined fallback chain | Splitting at sentence boundaries is settled; **the threshold is not**. It is an implementation constant pending test evidence | It canonised a number no governing document supports, and dressed a guess as a decision. |

It also over-reached on C3 (see §6).

The D6 correction has the sharpest lesson. Revision 1 quoted the enum and reasoned backwards from
its existence. The spec contains a direct counter-instruction in its own test strategy:

> ### Chunking
> - deterministic IDs/order for unchanged input;
> - long paragraphs split predictably;
> - lists remain one item per chunk;
> - **empty blocks do not generate useless chunks.**

An empty `UNSUPPORTED` chunk *is* a useless chunk. Revision 1's reading made the spec contradict
itself; revision 2's does not. When an enum member and an explicit test requirement conflict, the
test requirement wins — the enum was written before the requirement was thought through.

---

## 1. Precedence

Two documents disagree, so precedence has to be fixed first. The Product & Architecture Contract
states its own primacy:

> Coding agents must treat this page as the authoritative product contract when resolving conflicts
> between implementation specifications.

and defines the protocol for a genuine conflict:

> 1. update this Product & Architecture Contract;
> 2. review the effect on dependent specifications;
> 3. update affected specifications;
> 4. only then implement the new semantics.

| Rank | Document | id |
|---|---|---|
| 1 | Product & Architecture Contract | `…81ab-aa68-cf0b2ee17e45` |
| 2 | UI Screens & Navigation Contract | `…81b6-9cd2-d54532ad6fcd` |
| 2 | Notion Integration Implementation Spec | `…81e8-8b28-e7d04672e53e` |

Both rank-2 documents are downstream implementation specifications; the UI contract declares its
dependency explicitly ("This specification depends on Notion Reader — Product & Architecture
Contract").

**Useful consequence:** the Product contract explicitly defers the decisions in §2 and §3 to
implementation specs —

> This contract intentionally does not define: … precise Notion authentication implementation;
> **individual Notion block conversion policies**

so settling block-conversion rules requires **no** change to the Product contract. Only §7's
conflict needs the protocol.

---

## 2. Block-conversion decisions — settled

### D1 — Sections are a flat list; hierarchy lives in `level` — SETTLED

*Evidence:* the UI contract makes sections the Player's primary navigation unit and shows a single
"current section", which requires a linear sequence:

> Expected primary controls: `Previous section / Play / Pause / Next section / Queue`
> Show: … current section when available

A nested structure cannot answer "previous section" unambiguously. The integration spec separately
requires that "Lower-level headings should remain navigable without flattening away hierarchy" —
`level` satisfies that without nesting.

### D2 — An intro section is created only when needed — SETTLED (revised)

An intro section (`title = null`, `level = 0`, id `<pageId>:intro`) exists **only when there is
content before the first heading**. A page that opens with a heading has no leading section.

*Evidence:* the spec says "Content before the first heading belongs to an implicit introductory
section" — a conditional, not an unconditional section. Nothing requires an empty intro to exist.
Revision 1 created one solely to shelter the `TITLE` chunk; with D9 revised there is nothing left
for it to shelter.

### D3 — Headings are both boundaries and spoken — SETTLED

A heading opens a section **and** emits a `HEADING` chunk as that section's first chunk.

*Evidence:* the spec's chunking default is explicit — "one heading announcement = one chunk of type
`HEADING`" — so headings are spoken. The UI contract independently makes them navigation
boundaries. Both roles hold simultaneously; this was never either/or.

### D4 — An empty heading is ignored entirely — SETTLED

No section boundary, no chunk. Content after it stays in the enclosing section, and any children the
empty heading has are still read.

*Evidence:* "Empty headings must not produce unusable navigation targets." Because the UI contract
makes Previous/Next section the Player's *primary* control, an untitled empty section would be a
control that does nothing. Ignoring the heading must not drop content — the integration spec's
invariant 5 ("Unsupported blocks degrade locally rather than failing the whole note") applies by
analogy.

### D5 — A divider is preserved structurally, but emits no chunk and no section — SETTLED (revised)

Preserved as a structural node in the **normalized** block tree. It produces no `ReaderChunk` and no
`ReaderSection` in v1.

*Evidence:* the spec says a divider "produces navigation structure but no spoken chunk by default".
The domain model offers exactly two kinds of navigation structure — sections and chunks — and
neither can represent a divider: sections are heading-derived (D1/D3) and displayed by title in the
UI, and there is no `DIVIDER` chunk kind. Revision 1 concluded the clause was therefore vacuous and
discarded the divider.

Revision 2's refinement is better: the phrase "navigation structure" does not have to mean
*reader-domain* structure. Preserving the divider as a normalized node satisfies the sentence
without inventing a reader-domain concept, and keeps the information for a future rendering or
spoken-pause policy. The reader-domain output is the same in both readings; revision 2 merely stops
throwing away data on the way there.

### D6 — Unsupported blocks are preserved and emit no chunk — SETTLED (revised)

Preserved as normalized nodes carrying diagnostics (the raw Notion block type). They produce **no
`ReaderChunk`**, not even an empty one.

*Evidence — three independent requirements, all pointing the same way:*

- "Unsupported blocks must never crash the document. Represent them as skipped/unsupported nodes
  with enough metadata for diagnostics."
- "Do not read raw opaque JSON aloud."
- the Chunking test requirement: "empty blocks do not generate useless chunks."

The first two are satisfied by a normalized node plus diagnostics. The third actively forbids the
empty chunk revision 1 proposed. The `UNSUPPORTED` enum member is left unused — see §3.

### D7 — `chunk.id = "<sourceBlockId>:<part>"`, applied uniformly including `:0` — SETTLED

*Evidence:* "IDs used for persistence must derive from stable Notion identities where possible
rather than list indexes alone" and "Do not persist only a numeric chunk index because edits can
shift indexes". The Product contract requires the session to restore "current speakable chunk or
equivalent stable position"; the UI contract requires that Continue "restores its exact saved
playback position".

Uniformity is load-bearing: if an unsplit block's id were bare `A` but became `A:0`/`A:1` once it
grew, a saved position recorded as `A` would silently stop resolving.

### D8 — `section.id` is the heading block's id; intro is `<pageId>:intro` — SETTLED

Same evidence as D7. Never an index.

### D9 — The title is not a chunk — SETTLED (revised)

**No `TITLE` chunk is emitted.** `ReaderDocument.title` carries the title; the Player announces it on
session start and on note transitions.

*Evidence:* this is what the UI contract already implies. It places title speech in the Player's
transition behaviour rather than in content:

> A brief title announcement may identify the next Note.
> Detailed spoken transition behavior belongs to the Player specification.

Modelling the title as a content chunk would force the Player to either duplicate the announcement
or carry an instruction to suppress it. Revision 1 chose the instruction; revision 2 removes the
cause. The spec's "The page title represents the document title, **not an ordinary content block**"
reads more naturally as *exclude it from content* than as *give it a special chunk kind*.

### D10 — `to_do` checked state is metadata — SETTLED (revised)

Stored in `ChunkMetadata.checked`, never baked into `chunk.text`. Whether it is *announced* is a
Player/Note accessibility policy, not a mapping decision.

*Evidence:* the spec's metadata-to-speech boundary keeps metadata out of ordinary playback text. But
the boundary governs *text composition*, not narration policy, so revision 1's "never spoken" was
one step too far. Checked state is meaningful to a user and the Note screen may legitimately expose
it through accessibility semantics, which the UI contract owns.

### D11 — Sentence-boundary splitting is settled; the threshold is not — SETTLED (revised)

Long blocks **must** split at sentence boundaries. The character threshold is an **implementation
constant, pending test evidence** — it is not a product or spec number.

*Evidence:* "Very long text blocks should be split at sentence boundaries where possible." The
requirement is the splitting; the hedge ("where possible") implies a fallback, which implies a
threshold, but no governing document supplies one. The Product contract defers "individual Notion
block conversion policies" here, so the constant belongs to this spec — and should be chosen from
the fixture evidence described in `phase0-fixture-contract.md` §6, not guessed.

Note the consequence recorded in §3: because the threshold is unfrozen, chunk *counts* for long
blocks are not part of the expected-document contract. Threshold-independent properties are.

### D12 — Chunk text never carries identifiers, URLs, or status — SETTLED

Links keep their display text only. *Evidence:* "do not speak raw URLs by default", plus the
metadata-to-speech boundary, plus the UI contract listing links and code as *inspectable content* on
the Note screen (so they must survive mapping — they just must not be narrated as machinery).

### `ChunkMetadata` — proposed definition

The spec references `ChunkMetadata` but never defines it. Backing D10 and D12:

```kotlin
data class ChunkMetadata(
    val checked: Boolean? = null,                  // to_do only
    val language: String? = null,                  // code only
    val linkTargets: List<String> = emptyList(),   // hrefs, in source order
    val hasInlineCode: Boolean = false,            // hint for later speech policy
)
```

`unsupportedType` is deliberately **absent**: under revised D6 unsupported blocks produce no chunk,
so there would be no chunk to carry it. Diagnostics live on the normalized node instead.

---

## 3. Knock-on consequences of revision 2

Revision 2's corrections are individually small but they invalidate parts of the spec's own domain
model. Recording these explicitly so they are not discovered later as surprises.

### 3.1 `ReaderChunkKind` loses two members

The spec's enum is:

```kotlin
enum class ReaderChunkKind {
    TITLE, HEADING, PARAGRAPH, LIST_ITEM, TODO, QUOTE, CODE, UNSUPPORTED,
}
```

Revised D9 means `TITLE` is **never produced**. Revised D6 means `UNSUPPORTED` is **never
produced**. Two of the eight members become dead code, and a `when` over the enum will need branches
that cannot be reached.

Amend the enum to six members. Do not keep dead members "for later" — an unreachable branch in the
mapper is exactly how a future reader concludes the mapper must be wrong.

### 3.2 The Phase 0 expected document loses two chunks

Fully deterministic expected output was the point of Phase 0, and two of the twelve decisions change
that output:

- D9 removes the `TITLE` chunk (fixture total 26 → 25).
- D6 removes the `UNSUPPORTED` chunk for the `image` block (25 → 24).

`docs/phase0-fixture-contract.md` §3 is updated to 24 chunks accordingly.

### 3.3 The expected document is no longer fully deterministic

Unfreezing the threshold (D11) means chunk boundaries for long blocks are implementation-defined, so
the fixture cannot assert an exact count for block 22. This is a real weakening of the Phase 0
oracle and should be acknowledged rather than papered over.

The mitigation is to assert *threshold-independent* properties instead, which the fixture contract
now does: block 22 yields at least 2 chunks, every part ends at a sentence boundary, the parts
concatenate back to the original text losslessly, and ids are contiguous from `:0`. Those hold for
any threshold and are what a test should assert until the constant is chosen.

### 3.4 The normalized layer is now load-bearing

Revision 1 could treat the normalized block tree as an implementation detail of the mapper. Revised
D5 and D6 make it the only home for a divider and for unsupported-block diagnostics, so it becomes a
required artifact with a defined responsibility. This is the layer the spec calls
`NotionBlockNormalizer` / "Notion tree / normalized blocks" — it needs a real model, not a
pass-through.

### 3.5 No decision is unaffected

`ReaderSessionStore` (spec §Architecture) is what persists session state. Revised C1 changes what it
owns. Under D9 it no longer needs to store or emit a title chunk. Under D11 it cannot rely on chunk
counts being stable across a threshold change — another reason D7's id scheme, rather than an index,
is the right key.

---

## 4. The integration spec's six open questions — settled

### Q1. Which authentication for the private POC?

**Internal integration / trusted token behind a `NotionAuthProvider`; OAuth later.** The spec already
permits this — "Abstract authentication behind a token provider so the POC can use a trusted
personal/internal credential while production can move to an installable OAuth flow without
rewriting the repository layer" — and the Product contract defers "precise Notion authentication
implementation" here. The repo already implements it (`KtorNotionApi` + bare `ntn_` token in
`.env`). No new work.

### Q2. Are headings spoken, or only boundaries?

**Yes — spoken, and also boundaries.** See D3. The spec's chunking default is explicit.

### Q3. How are code blocks spoken?

**One `CODE` chunk, verbatim in v1**, with `language` preserved as metadata. A later Player policy
may offer different code-reading behaviour; that is a Player concern and must not change the
document model. Consistency check: the Product contract lists "code" among manual-reading content
and the UI contract lists it among Note screen content, so code must survive mapping either way.

### Q4. Do child-page references auto-descend during playback?

**No. Explicit navigation only.** Child pages are separate Notes.

*Evidence:* the UI contract's Raw Notion navigation flow is explicitly `Database → Pages → Note`, and
"Selecting a page always opens the standard Note screen. Raw Notion pages must not require a
separate reader implementation." Structurally, the Active Queue is a frozen snapshot of *Note
identities*, and "A Note is one readable document" — auto-descent would make a Note's content depend
on live structure, so the frozen snapshot could not be trusted. Nothing bounds the recursion either:
a child page can contain a child page.

This confirms the existing `NON_RECURSIVE_BLOCK_TYPES` behaviour for `child_page` and
`child_database`.

### Q5. Maximum traversal depth?

**A guard and defined diagnostic behaviour are required; the number `5` is not a spec or product
number.**

The spec requires traversal have "explicit traversal safeguards" and that unsupported structure be
represented "with enough metadata for diagnostics". So the depth limit must exist and exceeding it
must be visible rather than silent. But canonising `5` would dress an arbitrary constant as a
contract.

The current implementation violates the *behavioural* requirement: `NotionRepository.collectParagraphs`
stops descending at `MAX_BLOCK_DEPTH` and **discards everything below with no signal at all**. Settled:
exceeding the limit produces a diagnostic on the normalized node. The number itself stays an
implementation constant, in the same category as D11's threshold.

### Q6. Does v1 resume inside a chunk?

**Chunk boundary in v1, keeping a finer offset field available for later support.** The Product
contract lists "finer playback offset **where supported**", i.e. optional.
`currentPosition.characterOffset` is retained with a default of `0` so it can be populated later
without a schema migration.

---

## 5. Verified workspace facts (live API, 2026-09-17)

Read directly from `POST /v1/search` and `GET /v1/data_sources/{id}`, not inferred. Reproducible with
`pwsh -File tools/notion-tool.ps1 -Action list`.

| Data source | Title property | Metadata properties |
|---|---|---|
| Reflections | `Title` | `Date`, `Area` (select), `Topics` (multi_select), `Summary` (rich_text) |
| Practice & Learning | `Title` | `Topics`, `Date`, `Area`, `Summary` |
| Projects | `Project name` | `Owner` (people), `Status` (status), `Dates` (date) |
| **Todo List** | **`Task name`** | `Assignee` (people), `Status` (status), `Due date` (date) |
| Inbox | `Title` | `Captured` (created_time), `Processed` (checkbox) |
| Skills | `Skill name` | `Tags` (multi_select), `Files`, `Created by`, `Description` |

Area options, verbatim:

- Reflections: `Mind & Learning, Career & Work, Relationships, Life`
- Practice & Learning: `Chess, Music & Audio, AI & ML, Software Engineering, Accessibility, Mind & Learning`

Status options, verbatim:

- Projects: `Planning, In progress, Paused, Backlog, Done, Cancelled`
- Todo List: `Not started, In progress, Done`

**No accessible database named Knowledge, Tasks, or Archive.** A search for `Knowledge` returns zero
results. `archive` appears only as a note-organization concept, and the Skill itself warns: "Do not
assume that migration has occurred merely because it was discussed."

**No data source has a `relation` property.** Verified across all six.

---

## 6. Cross-document conflicts

### C1 — Resume ownership contradicts the Product contract — HIGH, blocks Phase 5

The Product contract is unambiguous:

> Resume and playback progress belong to the listening session and Active Queue, not permanently to
> individual Notes.

with the non-goal:

> maintain permanent resume positions for individual Notes independently of listening sessions

and for `Play`:

> A new Play action starts a new session instead of implicitly restoring historical per-note progress.

The integration spec instead defines `ReaderProgress` keyed for lookup by `pageId` — a permanent
per-note position, precisely the non-goal.

**Resolution (Product contract wins):** resume state becomes session-owned. The target shape:

```kotlin
data class ReaderSession(
    val activeQueueSnapshot: List<String>,   // frozen ordered Note/page identities
    val currentQueueIndex: Int,
    val currentNoteId: String,
    val currentPosition: ReaderPosition,
    val updatedAt: Instant,
)

data class ReaderPosition(
    val sourceBlockId: String?,
    val chunkId: String?,
    val sectionId: String?,
    val characterOffset: Int = 0,            // Q6: reserved, unused in v1
)
```

The existing stable-ID fallback algorithm is **retained unchanged** as the *mechanism* — only its
**ownership** moves:

1. exact `chunkId` still exists;
2. matching `sourceBlockId` still exists;
3. matching `sectionId` exists;
4. fall back to the start of the current Note.

`pageId` stops being a lookup key and becomes just `currentNoteId` inside the session record. This
does not block Phases 0–4, but the spec must be amended before Phase 5 or Phase 5 will be built on
the wrong ownership model — the failure the conflict protocol exists to prevent.

### C2 — Project relations: capability gap, not a product contradiction — MEDIUM

No accessible schema has a `relation` property, so:

- `ReaderLibraryEntry.projectIds` is always empty in this workspace;
- the Product contract's Browse filters include "Project relation", and Smart Queues "may store …
  Project relation".

**Resolution:** `projectIds` stays in the model and is simply empty where unsupported — consistent
with the Product contract's principle that metadata enhances reading but is never required. The
**Browse "Project" facet must be hidden when no normalized project data exists**, rather than shown
empty or disabled. The Smart Queue filter set must likewise omit the Project dimension when the
workspace cannot populate it.

No product-contract change needed: "Status **where meaningful**" already sets this precedent for
schema-dependent facets.

### C3 — Schema coverage — MEDIUM-HIGH (corrected)

The Note Organization skill names Inbox, **Knowledge**, Projects, Practice & Learning, Reflections,
**Tasks**, and **Archive** as authoritative homes. The live workspace backs only some of that.

| Accessible source | `ReaderEntryKind` | `ReaderSourceSchema` |
|---|---|---|
| Reflections | `REFLECTION` ✅ | `REFLECTIONS` ✅ |
| Practice & Learning | `PRACTICE` ✅ | `PRACTICE_AND_LEARNING` ✅ |
| Projects | `PROJECT` ✅ | `PROJECTS` ✅ |
| **Todo List** (title prop `Task name`) | `TASK` ✅ | `TASKS` ✅ recognized **by schema, not by database name** |
| Skills | `GENERIC` ✅ | `GENERIC` ✅ |
| Inbox | `GENERIC` ✅ | `GENERIC` ✅ |
| Knowledge | `KNOWLEDGE` — member exists, **no accessible source** | `KNOWLEDGE` — same |

**Correction to revision 1.** Revision 1 argued that Skills and Inbox "need" new enum values. That
was wrong. `GENERIC` exists precisely for "readable content whose schema is not recognized", and the
spec requires fallback rather than making content unclassifiable:

> If these properties are missing or the source schema is unknown, the application must fall back to
> Generic content behavior.

Skills and Inbox stay Generic until first-class filtering for them is actually wanted. Adding enum
members "because a database exists" is not a requirement — it is speculative generality, and it
would ship two members no adapter claims.

Two real consequences remain:

1. **`TASKS` must be recognized by schema, not by database name.** The live database is named
   "Todo List", so a name-matching adapter would miss it. The spec already forbids name coupling:
   "Do not hard-code playback logic to a database name." Discriminate on the title property
   (`Task name`) together with `Status`/`Due date`.
2. **`KNOWLEDGE` stays a defined future kind with no adapter.** No accessible source backs it, so no
   adapter may claim it, and nothing may assume it exists.

### C4 — `Area` is not a controlled vocabulary — MEDIUM

The skill declares ten "Stable Areas". The schemas disagree with it and with each other: Reflections
offers `Relationships` and `Life`, neither of which is in the skill's list, while the skill's
`Cloud & Infrastructure`, `Technology & Devices`, and `Finance & Personal Systems` appear in no
select at all. Only `Mind & Learning` is shared between the two select-bearing databases.

**Resolution:** Browse's Area facet is the union of per-schema options with per-type scoping, not a
global constant. The skill's list is aspirational. Affects the Browse phase only.

### C5 — `Status` is a genuine metadata-model omission — MEDIUM

The Product contract allows filters on "Status where meaningful", and Browse may filter on it, but
`ReaderLibraryEntry` has no status field at all. Status vocabularies differ per schema
(six values in Projects, three in Todo List).

**Resolution:** add

```kotlin
val status: String? = null
```

with schema adapters supplying the source's user-facing value. No universal status enum yet.

**Consequence to keep in view:** a raw string is not a cross-schema facet. `In progress` happens to
exist in both vocabularies, but `Backlog` has no Todo List equivalent and `Not started` has no
Projects equivalent. So Browse's Status filter is **schema-scoped in v1** — it filters within a Type
selection, not across all content. If a workspace-wide Status facet is later wanted, that is the
point at which a normalized enum becomes justified. Recording this now prevents someone building a
global facet and discovering the mixture later.

### C6 — Documentation structure drift — LOW

The UI contract names its future dependencies as distinct documents — "Notion Connection, Retrieval
& Normalization", "Reader Document Model & Chunking", "Content Metadata & Workspace Schema Adapters"
— whereas one integration spec currently spans all three. Not a contradiction, but agents will look
for three specs and find one.

Worth noting for expectation-setting: the UI contract specifies four top-level destinations, a
mini-player, Browse with search/filters/sort, three queue tabs, Note, Player, Raw Notion Navigation
and Settings. The repository today has a single `ReaderScreen`. The distance between the contract
set and the code is far larger than the distance between the integration spec and the code.

### C7 — No conflict: error taxonomy is confirmed

The UI contract requires that "Permission problems must not be collapsed into generic network
errors" and defers specifics ("Detailed error categories belong to the underlying implementation
specifications"). The integration spec's nine-category taxonomy is that detail; the two agree. The
taxonomy stands as written.

### C8 — Skill/schema synchronisation is outside the reader — LOW, but recorded

The Note Organization skill asserts Knowledge and Tasks databases and describes automatic Project
relations, while the live workspace matches that only partially. This is a **skill/schema
synchronisation issue**, not a reader architecture issue.

The reader's handling is to tolerate it through capability-based behaviour: Generic fallback (C3),
hidden facets where data cannot exist (C2), schema-scoped status (C5). The reader must **not**
hard-code the skill's assumptions. Fixing the skill is separate work and should be tracked
separately.

---

## 7. Amendments required to the Integration spec

Precise list, with the section headings as they appear in the Notion page.

All ten are amendments to the **Integration spec**, which is a rank-2 document conforming *upward* to
the Product contract. So no Product-contract change (protocol step 1) is triggered anywhere,
including for C1 — the Product contract is already correct and the spec is the document that
drifted. C1 is the largest amendment because it changes an ownership model rather than wording, but
it is still just protocol step 3, "update affected specifications".

### Status of this list

**Round 1 reviewed 2026-09-17 11:16.** The Integration spec was edited in Notion: **§7.1 and §7.2
are applied**, via **option (b)**.

| Item | Status |
|---|---|
| §7.1 session-owned resume | **applied** — `# State and persistence` → `# Session persistence boundary` |
| §7.2 `# Change handling` re-scope | **applied** |
| §7.9 Progress tests | **applied** — `### Progress` → `### Stable position handoff` |
| §7.3 chunk construction (D5/D6/D11) | outstanding |
| §7.4 conditional intro section (D2) | outstanding |
| §7.5 domain model (§3.1, ChunkMetadata) | outstanding |
| §7.6 supported block set (to_do/code) | outstanding |
| §7.7 `status` field, projectIds note | outstanding |
| §7.8 TASK-by-schema, no KNOWLEDGE adapter | outstanding |
| §7.10 session-level interface | superseded by §7.1's handoff |

**Option (b) was chosen**, and it was the better call. The amendment does not merely keep a
page-keyed position and re-label it — it *refuses ownership explicitly*:

> This specification does not own listening-session persistence.
> Playback progress belongs to the listening session and frozen Active Queue defined by
> Notion Reader — Product & Architecture Contract.
> The Notion integration layer owns only the stable document identities required by that session
> layer. It must not persist a permanent resume position keyed only by `pageId`, and it must not
> define Play or Continue semantics.

The integration spec now exposes `ReaderDocumentPosition(sourceBlockId, chunkId, sectionId,
characterOffset)` as a **handoff type** rather than a persistence record — which preserves D7's
identity scheme (so the session layer has stable keys to persist) while discarding the ownership
that C1 objected to. The component formerly known as `ReaderSessionStore` is gone, replaced by a
`### Session persistence boundary` statement. `characterOffset` is explicitly documented as possibly
remaining `0`, which settles Q6 in the spec text.

Notably the amendment is *stricter* than this record proposed: §7.1 suggested keeping the fallback
chain in place as "the mechanism", but the spec now delegates the fallback rules themselves
("chunk/block/section fallback rules for Continue") to the session spec. That is the more correct
boundary — the fallback chain needs to know about the frozen Active Queue, which the integration
layer has no visibility into.

### New problems introduced by the amendment

The amendment fixed C1 but created three internal inconsistencies, in descending severity.

#### N1 — Phase 5 now contradicts its own spec — HIGH

`## Phase 5 — Local progress persistence` still reads:

> Add stable resume state and edit-tolerant restoration.

This is now the *opposite* of `# Session persistence boundary`, which says the integration layer
"must not persist a permanent resume position" and must not decide "where Continue falls back".
Phase 5 instructs implementers to build exactly what the spec has just disclaimed.

An agent following the phases in order would build the rejected design. Phase 5 must be re-scoped —
either deleted (with the work pointed at the future session spec) or reduced to "expose stable
identities and prove they survive edits", which is the integration layer's actual remaining duty.
This is the highest-value outstanding fix because the phase list is what an implementer executes.

#### N2 — A stale open question — MEDIUM

`# Open questions` still asks:

> Should resume track sentence/character offsets inside a chunk in v1, or initially resume only at
> chunk boundaries?

The amendment already answers it — "`characterOffset` may remain 0 in the first implementation."
An answered question left open invites someone to answer it differently. Close it.

#### N3 — A non-component in the component list — LOW

`### Session persistence boundary` sits under `## Components`, alongside `NotionAuthProvider`,
`NotionApiClient` and the rest. A boundary statement is not a component. It reads as a deliberate
placeholder, which is fine, but it will confuse a reader who is enumerating the things to build.
Either move it to its own top-level section (it already has one at `# Session persistence
boundary`) or rename the entry to say what the integration layer does *not* own.

#### N4 — Residual per-note framing in Goals — LOW

`# Goals` still says "Preserve block/page identifiers for resume and navigation", which is fine,
but the pairing goal "Keep playback state local to the reader rather than writing progress into
Notion" now reads ambiguously: "the reader" here means the app rather than the integration layer.
Worth a word of clarification, or leave it — the intent is still correct.

### 7.1 ~~`# State and persistence`~~ — APPLIED

Replaced by `# Session persistence boundary`. See the status table above.

### 7.2 ~~`# Change handling`~~ — APPLIED

Now reads "When a Notion page is fetched after it has changed", and explicitly declines to decide
resume behaviour. Correct.

### 7.3 `## Speakable chunk construction`

Three changes, all still outstanding:

1. the divider sentence — record that a divider emits no chunk and no section, and is preserved at
   the normalized layer (D5);
2. add that unsupported blocks emit no chunk at all, with a pointer to the test requirement that
   forbids useless chunks (D6);
3. record that the split threshold is an implementation constant pending evidence (D11), and that
   block-to-chunk counts are therefore not contractual for long blocks.

Verified unchanged after round 1: the divider line still reads "divider produces navigation
structure but no spoken chunk by default", and "Very long text blocks should be split at sentence
boundaries where possible" still carries no note.

### 7.4 `## Section construction`

Record the conditional intro section (D2) — created only when content precedes the first heading.

Still outstanding: the section still says only "Content before the first heading belongs to an
implicit introductory section."

### 7.5 `# Reader domain model`

Remove `TITLE` and `UNSUPPORTED` from `ReaderChunkKind` (§3.1) and add the `ChunkMetadata`
definition from §2. Still outstanding — the enum still has all eight members.

### 7.6 `## Supported block set for POC`

The must-support list already contains `to_do` and `code`, but the current repository silently drops
both — they are absent from `SUPPORTED_TEXT_BLOCKS`, absent from the `when (type)` branches, and
`NotionBlock` has no `to_do`/`code` DTO fields. Also record the `to_do` representation rule (D10)
and that `divider`/unsupported blocks are handled per D5/D6.

### 7.7 `## Normalized library metadata`

Add `val status: String? = null` (C5), and record that `projectIds` may be empty where no relation
property exists (C2). Still outstanding.

### 7.8 `## Workspace schema adapters`

Record that `TASKS` is recognized from the `Todo List` database by schema (title property
`Task name`), not by database name (C3), and that no adapter claims `KNOWLEDGE` yet.
Still outstanding — the section still only illustrates the Reflections mapping.

### 7.9 `### Chunking` and `### Progress` (test strategy)

`### Progress` → **applied**, now `### Stable position handoff`. The four replacement bullets are
the identity-stability properties this record asked for, plus an explicit note that session-level
resume and fallback are tested in the future session spec. Good amendment.

`### Chunking` — still needs the threshold-independent assertions from §3.3. Its existing "empty
blocks do not generate useless chunks" line is **unchanged and must stay**; it is the citation that
settles D6.

### 7.10 `# Interfaces`

`LoadReaderDocument` is unaffected — correct as written. The session-level interface question is
now answered by §7.1's handoff type, so this item is closed with no edit needed.

---

## 8. What is settled vs still open

**Settled — safe to implement:** D1–D12, Q1–Q6, and the refinements in §3.

**Amend the spec before the phase it blocks:**

| Priority | Item | Blocks |
|---|---|---|
| 1 | **N1** — Phase 5 contradicts the new session boundary (§7) | any implementation |
| 2 | §3.1 — drop the two dead enum members (§7.5) | Phase 4 |
| 3 | D5/D6/D11 wording (§7.3) | Phase 4 |
| 4 | C3 — TASK-by-schema; no KNOWLEDGE adapter (§7.8) | Phase 8 |
| 5 | §7.4 — conditional intro section (D2) | Phase 4 |
| 6 | C5 — `status` field (§7.7) | Browse / Smart Queue phase |
| 7 | C2 — hide the Project facet (§7.7) | Browse phase |
| 8 | N2 — close the answered characterOffset question (§7) | none, housekeeping |

None block Phase 3 (normalization) or Phase 4 (reader mapping) against the fixture, except insofar
as §3.1, §7.3 and §7.4 define the mapper's behaviour — which is the point of settling them now.

**Deliberately left open, with no invented answer:**

- **`synced_block` traversal.** No governing document addresses it; the spec lists synced block in
  the "useful next set" rather than the must-support set. Reading it matches Notion's rendered
  order; skipping it avoids duplicating content that also exists on its source page. Current
  behaviour (skip) preserved. Genuinely undecided.
- **The split threshold** (D11) and **the depth limit** (Q5). Both are implementation constants that
  should be chosen from fixture evidence; neither should be canonised in a spec. Their *behavioural*
  requirements are settled: split at sentence boundaries, and diagnose rather than silently drop.
- **Cross-schema Status unification** (C5). Deferred by construction: schema-scoped facets in v1.

**Resolved since the first draft:**

- **C1** — fixed by the 11:16 amendment via option (b). Session persistence is now explicitly owned
  by the future Active Queue & Reader Session specification, and the integration layer exposes
  `ReaderDocumentPosition` as a handoff. The spec no longer contains `ReaderProgress` or
  `ReaderSessionStore`.
- **Q6** — answered in the spec text: "`characterOffset` may remain 0 in the first implementation."

**Still true, and still the reason not to start Phase 5:** the phase list itself is stale (N1). The
`ReaderProgress` design is gone from the spec's model but survives in Phase 5's instruction, so
following the phases in order would reintroduce the rejected design.
