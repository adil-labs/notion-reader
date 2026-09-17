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

**Round 1 reviewed 2026-09-17 11:16** (Integration spec edited in Notion: §7.1 and §7.2 applied via
option (b)).

**Round 2 reviewed 2026-09-17 12:54.** Nine items are now applied and verified by re-reading the
generated mirror rather than trusting the edit script.

| Item | Status |
|---|---|
| §7.1 session-owned resume | **applied** — `# State and persistence` → `# Session persistence boundary` |
| §7.2 `# Change handling` re-scope | **applied** |
| §7.3 chunk construction (D5/D6/D11) | **applied** — divider and unsupported no-chunk rules stated, and the threshold recorded as "an implementation constant rather than a specification value, so block-to-chunk counts are not contractual for long blocks" |
| §7.4 conditional intro section (D2) | **applied** — "An introductory section is created only when content precedes the first heading." |
| §7.5 domain model (§3.1, ChunkMetadata) | **applied** — `ReaderChunkKind` is down to the six reachable members, and `ChunkMetadata` is defined |
| §7.6 supported block set (to_do/code) | **applied** — the `to_do` checked-state rule and the one-`CODE`-chunk rule are stated |
| §7.7 `status` field, projectIds note | **applied** — `status: String? = null` added; the paragraph now records that `projectIds` may be empty and that Status is schema-scoped rather than a global facet (C2, C5) |
| §7.8 TASK-by-schema, no KNOWLEDGE adapter | **applied** — recognition is stated as schema-based with the `Task name` title property named, and no adapter may claim `KNOWLEDGE` |
| §7.9 Progress tests | **applied** — `### Progress` → `### Stable position handoff` |
| §7.10 session-level interface | superseded by §7.1's handoff |
| N1 Phase 5 contradicted the session boundary | **applied** — Phase 5 now reads "Expose stable page, source-block, section, and chunk identities, and prove that unchanged content produces unchanged identities across edits. This phase does not implement resume or persistence" |
| N2 stale characterOffset question | **applied** — the question is gone from `# Open questions` |
| N3 boundary statement inside `## Components` | **applied** — now `### Out of scope: session persistence` |
| N4 residual per-note framing in Goals | not actioned; intent is still correct |

**Nothing on this list is outstanding any more.** Round 3 applied the last three (§7.5's drift, §7.7
and §7.8) with `tools/apply-spec-amendments.ps1`, and re-exporting the mirror confirmed the change and
no other drift. The amendment history is complete and the four governing documents now agree with the
code and with the verified workspace facts.

The §7.5 drift was the one item applied incompletely. §7.5 was marked applied in round 2, but the
spec's `ChunkMetadata` was missing `headingLevel` (D17), which the Note screen depends on to render
heading hierarchy. Adding it to the block that holds the domain model also surfaced a hazard worth
recording: **the spec stores its entire reader domain model in a single code block**, so patching it
means restating `ReaderDocument`, `ReaderSection`, `ReaderChunk` and `ReaderChunkKind` as well. A
replacement containing only `ChunkMetadata` would have silently deleted the rest of the model from the
governing document. The script therefore prints a target block's full current text under `-Inspect`,
and that step is what caught it.

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

---

## 9. Second implementation round — error taxonomy and the Note/Player split

**Date:** 2026-09-17. **Scope:** the POC's two remaining acceptance criteria (7, "authentication,
permission, network, and not-found errors are distinguishable" and 8, "unit tests cover … pagination
behavior, and error mapping"), plus the UI contract's Note and Player screens.

D1–D12 and Q1–Q6 are unchanged. Two of the decisions below *extend* `ChunkMetadata` and
`ReaderState`, so §2's `ChunkMetadata` definition and the fixture contract in
`phase0-fixture-contract.md` are amended rather than replaced.

### D13 — Retry policy is derived from the error category, not from the HTTP status — SETTLED

C7 already confirmed that "the two agree" and that "the taxonomy stands as written", but left open
*how* each failure reaches a category and what may be retried. The spec's requirements are that
"retry only transient failures" and "do not automatically retry permission/authentication failures",
and the UI contract adds "errors should appear where recovery is meaningful" with a `Retry` action.

Those three statements are one statement if the category carries the answer. So
`ReaderErrorCategory` gains `canRetry`, true for exactly `RATE_LIMITED`, `NETWORK_UNAVAILABLE` and
`SERVER_ERROR`, and both the automatic retry loop and the UI's Retry button read it. This removes
the previous arrangement in which the retry decision was a `when (status)` on the transport side and
would have been a second, independent guess on the UI side.

Mapping, with Notion's own `code` preferred over the status because the code is more precise
(`unauthorized` and `restricted_resource` can both arrive as 403):

| Notion code | HTTP status | Category |
|---|---|---|
| `unauthorized` | 401 | `AUTHORIZATION_REQUIRED` |
| `restricted_resource` | 403 | `PERMISSION_DENIED` |
| `object_not_found` | 404 | `PAGE_NOT_FOUND` |
| `rate_limited` | 429 | `RATE_LIMITED` |
| `service_overload` | 529 | `SERVER_ERROR` |
| — | other 5xx | `SERVER_ERROR` |
| — | any other status | `MALFORMED_RESPONSE` |
| `IOException`, timeout | — | `NETWORK_UNAVAILABLE` |

`MALFORMED_RESPONSE` is deliberately the catch-all rather than a narrow "the JSON was broken"
category: for an unmapped rejection such as a 400 on a malformed cursor, what is true from the
outside is that "the exchange did not complete and it is not a credential, access, rate or
connectivity problem". A tenth category would express that more precisely, but the spec fixes the
list at nine and adding to it would be a contract change, not an implementation choice.

### D14 — Two messages per failure: one to read aloud, one to log — SETTLED

`ReaderLoadException` carries `category` plus two strings. `message` is the category's single
sentence and is what the screen shows and TalkBack reads; `diagnostic` holds the status, Notion's
`code` and a truncated copy of Notion's `message`, and is never shown.

*Evidence:* the spec requires both "user-meaningful categories" **and** "preserve enough diagnostic
context for logs without exposing tokens or private note content unnecessarily", which one string
cannot do. Previously the two were concatenated, so Notion's raw error text — which can name a page
ID — sat inside the sentence read aloud.

Redaction is structural rather than a filter: the token travels in an `Authorization` header, never
in a URL, path, query or body, so nothing that reaches `diagnostic` can contain it. The one place a
mistyped token *could* have reached a log is the page reference, so an unparseable reference logs its
length only (`NotionRepository`), and a test asserts the value is absent from both fields.

### D15 — `CANCELLED` is a real category, carried by a `CancellationException` subtype — SETTLED

The spec requires that "cancellation must remain cancellation and must not be translated into a
network error". A `Result`-returning use case is normally implemented with `runCatching`, which
swallows `CancellationException` and would report "the user left the screen" as a load failure.

`LoadReaderDocument` therefore catches `CancellationException` and rethrows it as
`ReaderLoadCancelledException`, which extends `CancellationException`. Coroutine machinery still sees
an ordinary cancellation (so nothing logs it as a crash and structured concurrency is intact), while
the taxonomy's ninth member is carried by a real type instead of being decorative. The original
exception is preserved as the cause, and the wrapper is idempotent.

*Rejected alternative:* dropping `CANCELLED` from the enum because cancellation is thrown rather
than returned. The spec fixes the list, and a category that can only be observed by catching is still
observed — this is different from §3.1, where a member had **no** producer at all.

### D16 — `UNSUPPORTED_DOCUMENT_STRUCTURE` fires only when a page yields nothing speakable — SETTLED

Raised when a page has at least one block and maps to zero chunks. A page with no blocks is empty,
not broken, and stays a valid empty document.

This is the boundary the spec demands from both sides at once — "a single unsupported block should
not invalidate an otherwise readable page" **and** the category's existence. A single `image` among
readable paragraphs stays silent and is recorded as a diagnostic on the normalized node (D6); only a
page that is *entirely* unreadable becomes this failure. The alternative — showing an empty reader
with no explanation — is the worst outcome for a TalkBack user who asked for a page and received
silence.

### D17 — `ChunkMetadata` gains `headingLevel` — SETTLED (amends §2)

```kotlin
val headingLevel: Int? = null,   // heading_1..3 → 1..3, null otherwise
```

*Evidence:* D3 makes a heading both a section title and a spoken chunk, so the Note screen has two
sources for the same text. Reading the level off the section works for the section's own heading and
silently degrades for a nested one (`heading_3` inside a toggle's children), and rendering both
sources draws every heading twice. Carrying the level on the chunk makes the hierarchy a property of
the thing being drawn.

This is the second field added to a type the spec names but never defines, after D10's `checked`.
`§7.5` should therefore define `ChunkMetadata` as it now stands: `checked`, `language`,
`linkTargets`, `hasInlineCode`, `headingLevel`.

### D18 — Three playback states, not two — SETTLED

`ReaderState` gains `hasReachedEnd`, and `playbackStateDescription` reports `Playing`, `Paused` or
`End of note`.

*Evidence:* the spec requires that "state changes such as paused, resumed, end of section, end of
note, and errors must have concise spoken feedback" — an explicit obligation to say *why* reading
stopped. It cannot be derived after the fact, because `isAtLastParagraph` is equally true while the
last paragraph is still being read. `finish()` sets it; an explicit pause does not; any move that
changes position clears it, and a move that changes nothing returns the same state so a boundary
press cannot silently clear it.

### D19 — Section navigation is "start of section", in both directions — SETTLED

*Evidence:* the UI contract makes `Previous section / Play / Pause / Next section` the Player's
**primary** controls, so their behaviour has to be defined rather than assumed.

- **Next section** → the first paragraph of the following section, or the last paragraph of the
  document when already in the final section.
- **Previous section** → the start of the current section if the position is past its start,
  otherwise the start of the previous section, otherwise no move.

Landing on a section's *first* paragraph is deliberate: a heading is a spoken chunk (D3), so "next
section" begins by reading that heading. The consequence for Previous is that one press re-reads the
current section from its heading and a second press steps back a section, which is what makes the
control usable mid-document. Without an outline both degrade to a single paragraph move, so a
hand-built state stays meaningful.

### D20 — The Note and Player screens are split, and what each omits — SETTLED

The UI contract states the boundary — "The Note screen is for manual reading and inspection. It is
not the primary continuous listening interface" — and lists the Player's primary information and
controls. The single mixed `ReaderScreen` satisfied neither, so it is replaced by `NoteScreen` and
`PlayerScreen`.

Honoured: Note = document structure in reading order with a minimal primary action; Player = current
Note title, current section, progress, playback state, and the three section-level controls. `Play`
on the Note means what the contract says — "creates a new Active Queue containing only this Note;
starts from the beginning; opens Player" — implemented as `playFromStart()`, so historical position
is not resumed.

Deliberately omitted, each with a reason rather than an omission:

| Omitted | Why |
|---|---|
| Player `Queue` control | It "opens a view of the current frozen Active Queue/session", and no Active Queue or session specification exists. A control that does nothing when pressed is worse for a TalkBack user than an absent one. |
| Note `Add to queue` | Same: Queue Destination does not exist. |
| Mini-player | Owned by the UI contract's shell section and meaningful only once a session exists outside the Player screen. |
| Ordered-list numbering | One `LIST_ITEM` kind serves bulleted and numbered items, so numbering is not recoverable. The contract defers detailed rendering to the Note Reading Screen specification, which does not exist; inventing a model field before it does would be guessing. Recorded as a known gap. |
| Link activation / href display | The href is metadata and the contract lists URLs among things that "should not be spoken during ordinary reading"; activating links needs a navigation surface this round has none of. |
| Explicit initial TalkBack focus | The contract wants focus at the screen heading on entry. Both screens expose a `paneTitle` and heading semantics, which is what Compose offers without a focus requester; sequencing focus properly belongs with the real navigation graph. |
| Navigation Compose | The contract's graph is four top-level destinations plus ten secondary screens reached from surfaces that do not exist. A two-screen `when` in the Activity does not freeze a graph shape it cannot yet know. |

`paneTitle` is used on both screens because the contract requires that opening a full screen "should
place TalkBack focus at a meaningful starting point" and "must not unpredictably land on bottom
navigation, mini-player, decorative content, arbitrary coordinates".

### D21 — One device-visible behaviour changed: the load error is now actionable

Previously a Notion failure left the demo document on screen with the reason appended to a status
line. Now the Note screen shows the failure as a distinct state with its category sentence, and a
`Retry` action **only** when `canRetry` is true.

The demo document still stays loaded and playable underneath, which the on-device record depends on
(the app is testable with no network and with a rejected token). What changed is that "could not
load" is no longer silent. Verified on device with a deliberately rejected token: the screen read
"Could not load this Note. Notion authorization is required. Check that the integration token is
still active." with **no** Retry button, which is correct — pressing Retry cannot fix a revoked
credential, and offering it would be a lie.

The `Retry` button's *appearance* for a retryable category is covered by unit test rather than by
device: `NETWORK_UNAVAILABLE` cannot be produced on this device without dropping the Wi-Fi
connection that `adb` itself runs over.

---

## 10. Third round — Phase 5, identity stability

**Date:** 2026-09-17. **Scope:** the integration spec's Phase 5, now that §7.3–§7.6 and N1 have been
applied and the phase no longer contradicts the session boundary. Phase 5 reads:

> Expose stable page, source-block, section, and chunk identities, and prove that unchanged content
> produces unchanged identities across edits. This phase does not implement resume or persistence.

D1–D21 are unchanged. Two decisions below are new; the evidence for the phase as a whole lives in
`phase0-fixture-contract.md` §3d.

### D22 — The identity claim is verified against the real API, not against an edited fixture — SETTLED

Every id we produce is derived from the Notion block id, so **our mapper cannot renumber anything**. A
test that hand-edited a JSON string and fed it back would therefore prove only that our code is
self-consistent. It would not test the assumption the entire scheme rests on, which is a fact about
Notion rather than about us:

> does a block keep its id when it, or its neighbours, change?

So `tools/verify-identity-stability.ps1` asks Notion directly: it edits the fixture page, captures what
the API reports, restores the page, and **fails** if the restore does not verify on both ids and text.
The two committed snapshots let 19 offline tests assert the property against real API data.

Three edits, each covering an identity the spec names:

| Edit | Identity under test |
|---|---|
| one paragraph's text changed in place | a block's id and chunk id survive **their own** content changing |
| one paragraph appended, then really deleted | insertion and deletion move no other identity |
| one heading blanked (D4) | a vanished **section** shifts later paragraph indexes without moving any id |

*Rejected alternative:* declaring the property self-evident because ids come from `block.id`. That
argument is exactly why the test is needed — it establishes that the *premise* holds, which no amount
of inspecting our own code can do. It is also the cheapest available check: the whole script is ~140
lines and its cost is one page edit.

**Limitation, recorded rather than glossed:** blanking a heading removes exactly **one** chunk. D4 keeps
an empty heading's content in the enclosing section and drops only the heading, so later paragraphs
shift by one position, not by a whole section. One position is sufficient to disprove any positional
scheme — the first edit breaks it — but the shift is smaller than "a section vanished" suggests. A
larger shift was not reachable: this API version rejects `after`, so a mid-page block cannot be deleted
and put back where it was.

### D23 — Position resolution reports facts; the session layer decides — SETTLED

`ReaderDocument.resolve(position)` returns how precisely a saved position was located and where, but
never whether to resume, where to fall back to, or what to announce:

```kotlin
ReaderDocumentPosition(sourceBlockId?, chunkId?, sectionId?, characterOffset)
→ ReaderPositionResolution(match, paragraphIndex?, resolvedSectionId?, blockSurvived, sectionSurvived)

enum class PositionMatch { CHUNK, BLOCK, SECTION, NONE }
```

*Evidence:* the spec delegates the choices emphatically — "The Notion integration layer must not itself
decide whether playback resumes, where Continue falls back, or what the user hears when a saved
position no longer exists. Those behaviors belong to the future Active Queue & Reader Session
specification." A `shouldResume` flag or a chosen fallback would be precisely that overreach, so the
type has neither.

Two consequences inside the resolution, both deliberate:

- **A `BLOCK` match anchors to the block's first remaining part.** When a part index has gone because
  the block was re-split, landing mid-re-split would drop the listener into content out of order.
- **`PositionMatch.NONE` returns no index at all.** Reporting "position lost" *and* a number to resume
  from would be a fallback decision smuggled in as data, and the caller would use it.

The type also carries **no numeric index**. The spec is explicit that "numeric chunk indexes alone are
not stable persistence identities because document edits can shift indexes", so carrying one would
invite exactly the bug the type exists to prevent. `ReaderDocument.positionAt(index)` converts a
transient playback coordinate into identities while the document that defines it is still in hand —
the only moment at which that conversion is possible.

### Still open

- **No spec amendments remain.** All of §7.1–§7.10 and N1–N4 are applied except N4, which was
deliberately not actioned because its wording is still correct. Phase 8 and Browse are now unblocked
on the documentation side; what they still lack is the Content Metadata & Workspace Schema Adapters
spec the UI contract names.
- **Accessibility validation with TalkBack** is the outstanding POC gate. See §11.
- **The third-party parser evaluation** is deferred by product decision, not overlooked. The
  conclusion reached was that the only Android-capable JVM client replaces the smallest, best-tested
  part of the pipeline while leaving the mapping logic — the part that actually encodes D1–D12 — as
  ours, and adds a second JSON stack (Gson) and a version-drift surface of its own. It is worth
  revisiting if write operations or a much wider block-type set arrive.

---

## 11. Fourth round — spec closeout and accessibility validation

**Date:** 2026-09-17. **Scope:** the two items §10 left as next steps: finish the spec amendments, and
close the integration spec's accessibility-validation gate.

### 11.1 Spec amendments — complete

`tools/apply-spec-amendments.ps1` applied the last three (§7.5's drift, §7.7, §7.8). **No spec
amendment remains outstanding**; §7.1–§7.10 and N1–N3 are all applied, and N4 was deliberately left
because its wording is still correct. The script is idempotent (each amendment detects its own
applied marker) and dry-run by default.

**The `-Inspect` step earned its place immediately.** Patching a Notion block replaces its whole
`rich_text`, and the spec stores its **entire reader domain model in a single code block**. The
amendment's target was `ChunkMetadata`, but the block also holds `ReaderDocument`, `ReaderSection`,
`ReaderChunk` and `ReaderChunkKind`. A replacement containing only `ChunkMetadata` — which is what the
amendment logically "is" — would have silently deleted the rest of the domain model from the governing
document. Nothing in the dry-run lengths would have made that obvious; printing the target's full
current text did. The script now restates every declaration in that block and says why.

This generalises: *for any in-place patch, print the whole target before writing.* A dry run that
reports only "would change block X (748 → 1064 chars)" is not enough to catch a destructive edit.

### 11.2 Accessibility validation — automated half done, listening half outstanding

Two instrumented suites were added under `app/src/androidTest/` — 32 tests covering both screens:

| Contract requirement | Asserted by |
|---|---|
| "focus at a meaningful starting point" | both screens expose a `paneTitle` |
| "stable accessible names" | no clickable node is unlabelled |
| "focus order must match logical reading order" | content is rendered top-to-bottom in document order |
| "metadata should not be spoken during ordinary reading" | decorative bullets and check glyphs are absent from the semantics tree; no block id or chunk id is on screen |
| "loading is not equivalent to empty" | three distinct states, loading stated in text |
| "permission problems must not be collapsed into generic network errors" | the authorization message is named and Retry is absent |
| "state changes … must have concise spoken feedback" | playback state is a polite live region; end-of-note is distinct from paused |
| "Playback must continue without keeping visual focus on the currently spoken block" | the position line is deliberately **not** a live region |

**A real defect was found and fixed.** The Note screen's error announcement was on the `Column` that
wraps the error text and the Retry button. Semantics applied to a parent that does not merge its
descendants creates its own node, so TalkBack would have watched an empty container while the text sat
in a child that never changed — the announcement would have silently never fired. The live region is
now on the message `Text` itself, with the Retry button outside it, and a test pins both facts. This is
exactly the class of bug the automated half exists to catch, and it is invisible to unit tests.

**Still open: the listening half.** The device has TalkBack installed
(`com.google.android.marvin.talkback/.TalkBackService`) but **not enabled** — the active accessibility
service is Bitwarden. Enabling it changes the device's touch behaviour globally, so it was not done
without asking. Three things therefore remain unverified: whether TalkBack's own speech and the
reader's TTS overlap in practice, where focus actually lands on entry, and whether the announcements
sound right. The spec's phrase is "perform the complete playback/navigation flow with TalkBack
enabled", which no instrumentation can satisfy.

### 11.3 Toolchain problems found, and why each fix is structural

Four separate issues had to be resolved before any of the 32 tests could run. None were app defects,
but each would have cost someone a long debugging session, so they are recorded.

| Symptom | Cause | Fix |
|---|---|---|
| All 32 tests failed in `Espresso.onIdle` | `ui-test-junit4` brings Espresso **3.5.0** transitively, whose idle path reflects on the hidden `InputManager.getInstance()` on Android 17 / API 37 | pin `espresso-core:3.7.0` |
| Random single test failed with "not displayed" | the device's `screen_off_timeout` is **30 s** while a run takes ~40 s, so the screen slept mid-run | `tools/run-accessibility-tests.ps1` raises it and restores it in a `finally` |
| A test's click was silently dropped | synthetic touches are unreliable on a real device | drive `SemanticsActions.OnClick` instead, which is what an accessibility service actually does |
| Whole runs aborted with "Process crashed" | a transient device-side process death; the runner then retried under the orchestrator, and **the XML and HTML reports disagreed** about how many tests had run | `execution = "ANDROIDX_TEST_ORCHESTRATOR"`, one process per test |

The first is worth generalising: **a transitive test dependency is still a version you are choosing.**
The failure presented as 32 unrelated assertion failures, which looks like a broken test suite rather
than a stale library.

The last is the reason to distrust a single source of truth for test results here. During this round
the XML reported 0 failures while Gradle reported failure, and on an earlier run the XML reported 0
while the HTML reported 1. The HTML report proved the accurate one. The result of the final run is
**32 tests, 0 failures, 100% successful**, per that report.

**Known remaining flake, documented rather than hidden:** the `connectedDebugAndroidTest` task still
exits non-zero even when every test passes, because AGP's exit-code bookkeeping reflects the
instrumentation shell's status rather than the collected results. It is a false negative from the
runner, reproducible, and not a test failure — but a CI job would fail on it, so it needs resolving
before this suite can gate anything.

---

## 12. Fifth round — the UI Screens & Navigation Contract as an implementation plan

**Date:** 2026-09-17. **Scope:** order the UI contract into implementable slices, and name the first one.
This section changes no code.

### 12.1 Reading the contract as a work list

The contract has 30 navigation invariants, but they are not 30 independent tasks. Most of them are
statements about *ownership* — who is allowed to change what, and what must not happen as a side effect
of navigation. That is why most of them cannot be verified by looking at a screen. They are verified by
looking at which component is allowed to call which operation.

The invariants audited against the code as it stands:

| Invariant | Requires | Today | Verdict |
|---|---|---|---|
| 25 Navigating away from Player does not stop playback | the session outlives the screen | already true — Back out of Player does not touch the engine | satisfied, but incidentally |
| 26 The mini-player reflects the existing session and creates no second state | one session, two observers | one session exists; nothing observes it from a second screen | not implemented |
| 27 Back changes visual navigation, not playback | the Back handler must not call the engine | true — `MainActivity`'s `BackHandler` only flips a value | satisfied |
| 1–3 Four top-level destinations; Player is not one; Note is not one | an application shell | `ReaderDestination` is a two-value enum, `Note ⇄ Player` | not implemented |
| 28 Manual TalkBack Note navigation never changes Player progress | inspection and playback must be different state | **the engine holds one document, and loading a document stops playback** | **violated in structure** |
| 11, 12 `Play all` / `Add all to queue` | an Active Queue and a Saved Queue | neither exists | blocked |
| 14–23 Queues | three queue kinds | none exists | blocked |

### 12.2 The decisive finding: `setDocument` cannot be called while a session is running

`ReaderEngineImpl` holds exactly one `document` StateFlow and one `ReaderState`. `setDocument` does:

```kotlin
invalidateUtterance()
speechEngine.stop()
_document.value = document
_state.value = document.stateAt(startParagraphIndex)
```

so *loading a Note to read is the same operation as ending playback*. The interface's own doc comment
for `document` says "The document currently loaded, for the UI title", and `MainActivity` passes that one
document to the Note screen and `document.title` to the Player. A single document serves both screens.

This is not a defect today, and it is worth being precise about why: today there is no way to open a Note
other than the one already loaded, so the Note screen and the session are the same document by
construction. It becomes a defect the first time a second producer of Notes exists — Browse, Home's
Continue, a Saved Queue, or Raw Notion navigation — because "open this Note so I can read it" would then
silently kill the listening session. The contract forbids exactly that, twice, in different words:
> If playback is occurring while another Note is manually inspected, the existing playback session
> remains unchanged.

> Moving accessibility focus through the Note must not alter Player progress.

and the product contract's "Manual reading and spoken playback are separate modes".

**The cheapest moment to separate them is the slice that adds the application shell**, for a specific
reason: the shell is what makes a session observable from a second screen, because the mini-player is
that second observer. Until then a regression here has nowhere to appear and no test can see it. Waiting
until Browse lands means unpicking the coupling at the same time as adding Browse, with the symptom
surfacing in a screen that is not the cause.

> **Corrected in §13.1.** "Not a defect today" was wrong. The coupling was reachable in the shipped
> build, because `MainActivity` starts its load after `setContent`: tapping Play on the demo document and
> letting the load finish stopped playback silently, and Retry did the same. The conclusion — separate
> them now — was right; the severity was understated.

### 12.3 Decisions

**D24 — The listening session and the inspected Note are separate state. SETTLED.**
A session holder owns the Active Queue snapshot, the currently playing Note, the playback position and
`isPlaying`. The Note screen owns a separate *inspection* document with its own load state. Two load
paths, one speech engine.

Not two engines: the session holder is owned above the navigation host, so switching destinations neither
recreates nor pauses it, which is what makes invariant 26 true by construction rather than by discipline.
`ReaderEngineImpl`'s behaviour does not change — it stops being an application-level singleton and
becomes the session's playback engine. For this slice a session is still one Note, which is what the
product contract says single-note playback is ("Playing one Note therefore creates an Active Queue
containing one item"), so no queue semantics are invented here.

**D25 — "Resumable" means playback has started and the queue has not completed. SETTLED for the shell.**
The contract's presence rule is "when a playing or resumable Active Queue session exists" and "if no
resumable session exists, the mini-player is absent". Read literally, the demo document loaded at launch
is already a session, so the mini-player would be on screen from first launch describing a Note the user
never chose to play. The rule adopted: a session is surfaced once playback has begun, and stops being
surfaced at `hasReachedEnd` — a finished session belongs to `Queues → Listening → Recently completed`,
not to the mini-player. Retention of completed sessions stays with the session spec that does not exist
yet; this decision only fixes what the mini-player shows.

**D26 — Adopt Navigation Compose in the shell slice. PROPOSED.**
The comment in `MainActivity` explains why a navigation library was avoided: the real graph was not yet
determined, so freezing its shape by introducing one to move between two screens would have been
premature. That reason has now expired. The contract names four top-level destinations and ten secondary
screens, it names the Back rule ("Back reverses visual navigation. It does not reverse playback."), and
it names the retention rule (Browse keeps search, filters, sort and result position; Queues keeps its
selected tab). A hand-written stack grown to fourteen destinations duplicates what the library does.

Recorded as PROPOSED rather than SETTLED because it has to be verified before being depended on: the
artifact must build and dex against minSdk 26 / compileSdk 37, and per §11.3 and the Ktor 3.2.0 lesson the
*transitive* set is part of that choice. A `NavHost` also changes how per-destination state survives, so
the retention rule needs explicit `saveState`/`restoreState` rather than a fresh instance per switch. If
the spike fails, the fallback is a hand-written stack — cheap for four destinations, but it is the thing
the contract keeps growing.

**D27 — The mini-player is an observer, not a control surface, and it is absent on Player. SETTLED
except for the Note screen.** It reads the session's flows and calls the session's play/pause; it holds
no state of its own. Shown on the four top-level destinations, hidden on Player, whose full surface
already owns the same controls. Whether it appears on the Note screen is a genuine ambiguity rather than
an oversight — §12.5.

### 12.4 The plan

Ordered by what unblocks what, not by screenshot order. "Blocked" means a governing document does not
exist yet.

| # | Slice | Delivers | Blocked by | Verified by |
|---|---|---|---|---|
| A | App shell, session ownership, mini-player | the session hoisted above the nav host; the mini-player; Home with a real Continue listening section; Player Back returning to the originating destination. Destinations ship only when they have content (§12.6) | nothing | JVM: a navigation sweep calls nothing on the engine; one session identity; the presence rule; session replacement. Instrumented: tab semantics for the destinations that ship; the mini-player is two focus targets; entry focus is neither the bar nor the mini-player |
| B | Active Queue (multi-note) and lazy Note loading | an ordered queue of Note identities; auto-advance across Notes; "Note 3 of 8"; the end-of-queue announcement | nothing — the product contract already defines the snapshot | JVM: order frozen against later source mutation; the next Note is loaded at the transition, not upfront; position maps onto `ReaderDocumentPosition` |
| C | Browse default state | `All Notes`, `Sort: Recently updated`, search, result → Note, `Play all` | default state and search: nothing. Facets (Type/Area/Topics/Project): the absent Content Metadata & Workspace Schema Adapters spec, plus §5's verified gaps | JVM: the default query and its ordering; result activation opens the Note without touching the session. Instrumented: one result row is one focus target |
| D | Queues → Listening tab | Continue listening and Recently completed; Continue restores a frozen session | the Active Queue & Reader Session spec — the integration spec explicitly refuses persistence ownership (§6, C1) | a session-store round trip; `ReaderDocumentPosition` resolved against a since-edited document |
| E | Raw Notion navigation | Browse Notion structure → Database → Page → Note | nothing | an unorganised page renders in the standard Note screen unchanged |
| F | Saved and Smart Queues, Queue Destination | the remaining Queues surfaces, `Add to queue`, `Add all to queue` | two specs that do not exist (Queue Creation & Queue Management; Active Queue & Reader Session) | — |

Two ordering points are worth stating rather than leaving implicit:

- **B before C.** `Play all` is a Browse action, but multi-note playback is session behaviour. Building
  the Active Queue against a fixture producer and JVM tests keeps Browse out of the engine's design, and
  leaves C as wiring.
- **E after C.** Raw Notion navigation is the contract's "secondary escape hatch" and C is the primary
  discovery surface. E is cheap and unblocked, which makes it the right thing to do *while* C's facets
  wait on a missing spec — not the right thing to do first.

B also needs something C does not: a session spanning many Notes cannot load every Note upfront, so the
transition has to fetch the next `ReaderDocument` at the moment it is reached. That is the difference
between a queue of identities and a queue of documents.

> **This order was revised in §14.1.** A (the shell) cannot precede B and C, because a shell needs two
> populated top-level destinations and Browse is the only unblocked candidate for the second one. The
> revised order is B → runner fix → C → A.

### 12.5 Ambiguities — resolved by product decision

The contract's own acceptance criterion is "no downstream coding agent needs to invent a
navigation-level product rule". Four were open. All four now have answers, recorded here so the shell
slice does not have to invent them.

| # | Question | Answer | Effect |
|---|---|---|---|
| 1 | Back from a top-level destination other than Home | **return to Home** | the standard bottom-navigation convention: Back from Browse or Queues lands on Home rather than finishing the Activity |
| 2 | Mini-player on the Note screen | **shown** | a reading session is exactly when the control is wanted. The entry-focus rule still applies, so the mini-player must not take focus on entry — the same constraint as elsewhere, now with a second surface to check |
| 3 | Empty Settings | **omit the tab until it has content** | see §12.6 — a recorded scoped deferral, not a redefinition |
| 4 | Is a never-played session resumable? | **no** | confirms D25 |

Answer 3 is the only one that touches a governing document. UI-contract invariant 1 names Home, Browse,
Queues and Settings as the four top-level destinations, so shipping three is a temporary deviation. It is
recorded as a **scoped deferral with a condition** rather than as a decision that overrides the contract,
which keeps it inside the product contract's conflict protocol: the reading is "not yet", not "no
longer". The moment Settings is dropped permanently rather than deferred, invariant 1 has to be amended
in Notion first, dependents reviewed, and only then implemented.

The rule behind answer 3 is broader than Settings, and that is where it becomes interesting: Settings
was omitted *because it would have been empty*, and the same test applies to Browse and Queues here.

### 12.6 What answer 3 does to this slice, and to D26

Applying "do not ship an empty surface" consistently changes slice A rather than merely trimming it, and
the change runs against D26. It is recorded here so the scope was chosen deliberately rather than
drifting into place.

Browse and Queues have no content in this slice — Browse's default state is slice C and the Listening tab
is slice D — so by the same argument that removed Settings they should not ship either. What remains is a
shell hosting **Home, Note and Player**, where Home carries the one section that is genuinely
implementable today: Continue listening, which is a projection of the resumable session and therefore the
same object the mini-player already observes. Home's Your queues and Explore sections are omitted for now,
which the contract explicitly permits ("If no resumable session exists, omit the section").

Two consequences follow.

**D26's justification weakens, and its spike matters more.** D26 rests on the contract's *eventual* graph —
four top-level destinations and ten secondary screens — not on this slice's three. Three destinations are
cheap to hand-write, so the honest position is that adopting Navigation Compose in slice A is a bet on the
graph arriving, not a present need. It should still be spiked first, and the fallback is now clearly
adequate rather than merely acceptable.

**D24 is unaffected, which is the point.** The session/Note separation is verified by navigation between
*any* two surfaces, so Home supplies the second top-level one and the mini-player needs a surface that is
not Player. The structural work and its tests therefore do not depend on how many destinations ship —
which also means a cheaper slice A is available: fix D24 on its own, with the session holder and its JVM
tests but no shell, and add the shell when slice C gives Browse real content. That is a legitimate option
and should be chosen explicitly if chosen at all.

### 12.7 Sequencing constraints and risks

- **The instrumented runner's false negative has to be fixed first.** §11.3 records that
  `connectedDebugAndroidTest` exits non-zero even when every test passes. The shell is the slice whose
  most valuable checks are instrumented — focus landing, tab semantics, the mini-player's focus count —
  so a runner that always reports failure is now blocking, not merely inconvenient.
- **The existing 32 tests must be extended, not replaced.** They already pin "focus lands somewhere
  meaningful" and "loading is not empty" for two screens. A `Scaffold` with a bottom bar is precisely the
  change that can move entry focus onto the bar, so those tests are the canary for this slice and their
  `paneTitle` assertions should keep passing unchanged.
- **One unused dependency is already sitting in the build.** `androidx.datastore:datastore-preferences`
  is declared in `app/build.gradle.kts` and referenced nowhere in the codebase; it was added for the
  persistence work that §6's C1 then moved to a spec that does not exist. Slice A must not build
  persistence. Slice D is where this dependency earns its place or is removed.
- **Deliberately not in slice A:** queue semantics, Browse content, persistence, and any change to what
  is read aloud. A is a structural slice — it moves ownership and adds one surface — and it should be
  verifiable almost entirely by JVM tests that assert *that no engine call happened*.

### Still open

- The immediate order recorded here — D26 spike, runner exit code, then slice A — was superseded on
  2026-09-17: D24 alone was implemented first instead (§13), because it turned out to be a live defect
  and because its fix and its tests need neither the device nor the navigation decision. The spike and
  the runner exit code are both still open, and both still precede the shell.
- Slices D and F are blocked on governing documents, not on effort: the Active Queue & Reader Session
  spec and the Queue Creation & Queue Management spec. The UI contract names both as dependents and
  leaves them the details it defers — retention limits, the Saved Queue persistence schema, the Smart
  Queue query representation.
- Slice C's facets are blocked on the Content Metadata & Workspace Schema Adapters spec, and §5 already
  records that the workspace cannot satisfy part of what it would have to say: no data source has a
  `relation` property, `Status` vocabularies differ per schema, and `ReaderEntryKind`/`ReaderSourceSchema`
  have no value for Skills or Inbox.
- The four ambiguities in §12.5 are resolved as of 2026-09-17: none remain open. Answer 3 was the one
  that touched a governing document, and it changed the slice's scope rather than only its presentation
  — see §12.6.

---

## 13. Sixth round — D24 implemented: the session and the inspected Note are separate state

**Date:** 2026-09-17. **Scope:** §12.6's cheaper option, chosen deliberately. The shell, the mini-player
and the persistence are not in this round.

### 13.1 The defect was live, not latent

§12.2 called the coupling "not a defect today". That was wrong, and the correction is the reason this
round came first. The coupling was reachable in the shipped build:

```kotlin
setContent { /* ... */ }              // the UI is live from here
if (BuildConfig.NOTION_TOKEN.isNotBlank()) loadNotionDocument()

// on success:
readerEngine.setDocument(document)    // -> speechEngine.stop(), position reset to 0
```

Because the load starts after `setContent`, Play is tappable while the load is in flight. Tap Play on
the demo document, let the Notion load finish, and `setDocument` stops the speech and resets the
position — silently, with no message and no visible failure. Two variants are worse than the timing
window suggests, because `loadNotionDocument` is *also* the Retry action: a failed load leaves the demo
document playing, and the retry that follows kills it. Retry is pressed most often when a load is slow,
which is when the window is widest.

Nothing caught it. The 32 instrumented tests assert semantics trees, and no test played while a load was
in flight. The window is the load latency, so the symptom is intermittent — the shape of bug that gets
reported as "sometimes the reading stops".

### 13.2 The shape of the fix

Ownership is now stated once, in `ReaderApp`, and the table is in its doc comment. State that outlives a
screen is held by the Activity, above the surface being rendered:

| State | Owner | Lifetime |
|---|---|---|
| what is being read aloud, and where | `ReaderSession` | the process, across every navigation |
| what the Note screen shows | `NoteViewer` | until another Note is loaded |
| which surface is on screen | `ReaderNavigator` | the process |

The enforcement is structural rather than disciplinary, which is the part that matters:

- **`NoteViewer` holds no reference to a session.** There is no call it *could* make that interrupts
  playback, so the bug is unreachable rather than fixed. `load` writes to two of its own flows.
- **`ReaderNavigator` holds no reference to a session either.** Back therefore cannot mean Pause, Stop
  or Previous — the contract's "Back must never implicitly mean" list — because nothing in that file is
  able to reach playback. Both classes are Android-free as a consequence, so both are JVM-testable.
- **`ReaderApp.playNote()` is the only operation in the application that hands a document to the
  session.** Load a different Note while one is playing and the session keeps reading the old one. That
  is the product contract's "Saved Queue edits must not mutate an Active Queue already created from that
  Saved Queue" arriving at one-Note scale: the frozen snapshot is not a rule that was added, it is what
  falling out of a single transfer point looks like. It also gives slice B the exact place to put a real
  queue snapshot.

`ReaderEngineImpl` is unchanged. It stops being an application-level singleton and becomes the session's
playback engine, which is all §12.2 needed: the engine still does not know what a screen is.

### 13.3 Verification

**157 JVM tests, 0 failures** (was 146; the 11 new ones are `ReaderAppTest`, plus `FakeReaderEngine`).

**The regression tests were shown to have teeth, not merely to pass.** Re-introducing the coupling —
one line in `loadNote` calling `session.engine.setDocument(...)` — failed exactly five tests and no
others:

```
ReaderAppTest > loadingANoteWhilePlayingLeavesTheSessionAlone FAILED
ReaderAppTest > retryingAFailedLoadWhilePlayingDoesNotInterruptPlayback FAILED
ReaderAppTest > aFailedLoadReportsTheCategoryWithoutBlankingTheNote FAILED
ReaderAppTest > aLoadInFlightReportsLoadingWhileThePreviousNoteStaysReadable FAILED
ReaderAppTest > playFreezesTheInspectedNoteSoALaterLoadCannotChangeWhatIsRead FAILED
11 tests completed, 5 failed
```

Then the line was removed and the suite was green again. This is the check that distinguishes a test
that asserts the invariant from a test that happens to pass, and it is cheap enough to be routine.

`FakeReaderEngine` is a *recording* engine — "zero calls" is a statement about the engine boundary, so
it is asserted there rather than inferred from speech calls further down. It deliberately **mirrors the
real engine's destructive `setDocument`**, because a double that quietly made `setDocument` harmless
would make the regression impossible to reproduce: a test double that cannot fail the way the real
dependency fails is not evidence.

**On device, via `tools/probe-player-back.ps1`:** Note → Play → Player (`Section 1 of 59 · Summary`,
`Paragraph 2 of 349`, `Playing`) → Back → Note, with a new utterance created by `com.google.android.tts`
after Back, so playback survived.

### 13.4 Found while verifying

- **The probe was reporting wrong answers.** Its screen classifier matched the document title
  `Reader Fixture — Block Type Coverage`, but the app discovers the *most recently edited* page, and the
  spec-amendment work made a spec page the most recently edited one — so the Note screen stopped
  matching and was reported as `PLAYER`. It now classifies by the Player's own controls. A probe that
  misreports the thing it exists to check is worse than no probe, because it produces a conclusion.
- **Its audio check was measuring TalkBack.** `dumpsys audio | Select-String 'new player'` took the
  newest player regardless of process, and TalkBack's earcons are SoundPool players too. On this device
  that meant the "is the reader still speaking?" check was reading TalkBack's timestamps and looking
  like evidence. It now excludes TalkBack explicitly.
- **TalkBack is enabled on the device.** `settings get secure enabled_accessibility_services` returns
  `com.x8bit.bitwarden/...:com.google.android.marvin.talkback/.TalkBackService`. §11.2 records it as
  installed but not enabled, so that is now out of date, and the integration spec's long-open gate —
  "perform the complete playback/navigation flow with TalkBack enabled" — is testable in its current
  device state. It has not been done. It carries a real risk the automated half cannot see: whether
  TalkBack's speech and the reader's TTS overlap in practice.
- **`ReaderEngine.playFromStart()` now has no production caller.** `ReaderSession.start` uses
  `setDocument(...) + play()` because the document itself is being replaced, and `playFromStart` restarts
  the document already loaded. It is not dead code — it is still covered by three `ReaderEngineTest`
  cases and the UI contract names "restart behavior" as a future contextual action — but it is currently
  reachable only from tests, which is worth knowing rather than discovering later.

### 13.5 What is deliberately not here

Recorded because each is a plausible thing to add while in the neighbourhood, and each needs a consumer
to be correct:

- **No queue.** The session is one Note, because the product contract says single-Note playback *is* a
  one-item Active Queue. A queue of one is not a queue; slice B gives it ordered Note identities and the
  lazy loading of the next Note.
- **No persistence.** The integration spec explicitly refuses to own it (C1), so it waits for the Active
  Queue & Reader Session specification.
- **No mini-player, and therefore no `isResumable` presence rule.** A presence rule with no surface to
  render it is a guess about what "resumable" should mean. D25 already settles the answer; the code
  arrives with the surface.
- **No destination beyond Note and Player**, and no navigation library. §12.6 explains why the library
  decision is now weaker: with three eventual destinations a hand-written stack is adequate, so adopting
  Navigation Compose is a bet on the eventual fourteen-destination graph rather than a present need.
  D26 stays PROPOSED and is deferred to slice C, where Browse gives the graph something to hold.

### Still open

- **The integration spec's TalkBack gate** (§11.2) is now testable, since TalkBack is enabled — see
  §13.4. Not done, and the user's call.
- **The instrumented runner's exit code** (§11.3) still needs fixing before any instrumented test can
  gate anything. It matters less than it did: slice A's structural half is now verified on the JVM, so
  instrumentation is needed for the shell's focus and tab-semantics checks rather than for this.
- **Slice A's remaining half** — the shell, Home with Continue listening, and the mini-player — is
  unblocked and is the natural next slice, along with slice B (multi-note Active Queue and lazy Note
  loading), which `ReaderApp.playNote` and `ReaderSession.start` were shaped to receive.
- Slices D and F remain blocked on governing documents; slice C's facets remain blocked on the Content
  Metadata & Workspace Schema Adapters spec (§12's "Still open" still holds).

---

## 14. Seventh round — the plan for the queue, and a correction to §12.4

**Date:** 2026-09-17. **Scope:** order the next slices and name the decisions each needs first. No code.

### 14.1 The correction: a shell cannot ship with one top-level destination

§12.4 put the shell (A) first, and §12.6 then shrank it to Home, Note and Player. Reading that against
the contract exposes the flaw: Note and Player are explicitly *secondary* destinations ("The Player is
not a fifth bottom-navigation destination", "The Note screen is not a top-level destination"). So A as
scoped had exactly **one** top-level destination — and a bottom navigation bar with one item is not a
shell, there is nothing to navigate between.

A shell therefore needs a second populated top-level destination. The candidates:

| Candidate | Content available today | Verdict |
|---|---|---|
| Settings | none | omitted by the §12.5 answer |
| Queues | none: Listening needs persistence (blocked), Saved and Smart need two specs | blocked |
| Browse | the default state is implementable now | **the only unblocked candidate** |

Browse is only *partly* blocked, which §12 stated but did not exploit: the missing Content Metadata &
Workspace Schema Adapters spec blocks Browse's **facets** (Type, Area, Topics, Project) and Home's
Explore section, not its default state. The contract's default is "`All Notes`, `Sort = Recently
updated`" with search, and the pieces for that already exist or are small:

- `NotionApi.searchMostRecentPage()` already calls `POST /v1/search`; a list-returning variant with
  cursor pagination is a small addition rather than a new capability.
- Titles come from `NotionPage.title()`, which already exists and is already used for the single-page
  load. So a result row can show a real title without the metadata spec.
- "Recently updated" is `last_edited_time`, already carried on `NotionPage`.

Browse's result-set actions include `Play all`, which needs an Active Queue. So the natural companion of
Browse v0 needs B first. **Revised order: B → runner fix → C → A.**

This is recorded as a correction because §12.4's reasoning was not wrong, it was incomplete: it ordered
slices by which unblocked which, and did not account for the shell needing two populated destinations to
exist at all.

### 14.2 The immediate slice: the Active Queue (B)

Why this is next:

- **It is the product's core.** The premise is listening through many Notes; today the engine reads one
  document, and the Player's position line says "Paragraph 2 of 349" with no notion of a Note 3 of 8.
- **It is unblocked.** The product contract defines the Active Queue, the snapshot contract, the four
  origins, and the session/resume contract. Nothing here waits on a missing document.
- **Its verification is JVM**, with a fixture producer for the parser — the same shape as §13, which
  needed no device, no runner fix, and no navigation decision. Given §11.3's broken exit code, a slice
  whose best evidence is JVM is worth more than one whose best evidence is instrumented.
- **Three later things are already expressed in its terms.** `Play all`; Home's "Note 3 of 8" and
  "11 notes"; the Listening tab's frozen sessions. All three of the UI contract's own examples are
  multi-Note, which is why building the surfaces first would mean building displays for a concept that
  does not exist.
- **The engine does not change.** `setDocument` already exists, and a queue is many documents in
  sequence. This is the same leverage §13 got: the structural fix was in ownership, not in the engine.

### 14.3 Decisions needed before implementing B

**B1 — What is a Note identity in a queue? → `pageId` + `title`, content loaded lazily.**
The title is carried because `Note 3 of 8`, the between-Notes announcement, and the later queue rows all
need to name a Note at a point where its document is *not* loaded. It is cheap: it arrives in the page
response. The alternative — identity as `pageId` alone — would force I/O to render a list. This is the
"ordered Note identities" the contract requires and nothing more; the spec's `ReaderLibraryEntry` will
*widen* it when the metadata spec exists rather than replace it. Deliberately **not** carried: a
per-Note resume position, because the product contract puts resume on the session — "Resume and playback
progress belong to the listening session and Active Queue, not permanently to individual Notes".

**B2 — Who performs the lazy load? → the session owns the sequence and the transition *intent*; the
caller owns the I/O.**
The session advances to the next identity and reports that it is waiting for a document; a coordinator
above it loads that document and hands it in. This keeps cancellation, retry and the error taxonomy out
of the playback state machine, keeps the session synchronously testable with no coroutine scope of its
own, and preserves §13's rule that only a deliberate, named transfer hands a document to playback. The
cost is a real between-Notes state — which the Player needs regardless, because something has to be on
screen while the next Note loads.

**B3 — What happens when a mid-queue Note fails to load? → needs a product answer; it is audible.**
**Neither contract states this.** The UI contract says "at the end of one Note, Player should continue
automatically to the next Note", and its error section offers "Could not load this Note. Retry" — but
nothing says whether the queue stalls, skips, or aborts. Recommendation: **stall and retry.** The queue
holds its position, the Player shows the failure with Retry, and playback does not advance past it.
Skipping would silently drop a Note the user asked to hear — the audible form of the mutation the
snapshot rule exists to prevent — and aborting discards a remaining queue that is still valid, for a
failure the taxonomy already marks as often transient.

**B4 — End of queue.**
The contract asks for an announcement, an end to automatic progression, and the session marked completed
"according to the Reader Session specification" — which does not exist. So: expose completion as a
session *fact* and invent no retention. `ReaderState.hasReachedEnd` already covers one document; the
session needs the queue-level equivalent. Separately, the contract says a brief title announcement "may"
identify the next Note: do it, because the alternative is silence between Notes, which is
indistinguishable from a stall.

**B5 — Carry `origin` from the start? → yes.**
The contract lists "origin information where useful" among the Active Queue's minimum contents. It is one
field, and it is what a later Listening row displays ("Smart Queue · 8 current matches").

### 14.4 Order after B

| # | Step | Why here |
|---|---|---|
| 1 | **B** — Active Queue, lazy next-Note load, end-of-queue | §14.2 |
| 2 | **The instrumented runner's exit code** (§11.3) | bounded, and it gates every instrumented check below |
| 3 | **C** — Browse default state, search, `Play all` | the second top-level destination, so it is what makes a shell possible; independently the app becomes navigable |
| 4 | **A** — the shell over Home + Browse, plus the mini-player | needs 3 and 1 |
| 5 | **The missing spec, then D** — persistence, Listening tab, a Home with real history | D is blocked on the Active Queue & Reader Session spec, which §6's C1 shows the integration spec refuses to own |

### 14.5 The open question this exposes: Home is thin until persistence exists

Home's Continue listening shows *recent unfinished* sessions — plural, with "Paused 2 hours ago". That is
history, and history is persistence. With an in-memory session only, Home can show at most the session
that is currently loaded, which is very nearly what the mini-player shows.

So the shell's Home is the weaker surface, and the two options are in direct tension with a decision
already made:

- **Ship Home now**, with Continue listening reading the live session and Your queues / Explore absent.
  The contract permits the section to be absent ("If no resumable session exists, omit the section") —
  but a Home with none of its three sections is an empty screen, which is exactly what §12.5's answer
  rejected for Settings.
- **Defer the shell until D**, so Home is never the weak surface, and accept that Browse v0 ships
  without a shell — still useful (it is a screen and a query), just not reachable as a tab.

Surfaced rather than resolved: it is a product answer, and it trades visible progress against a surface
that would be argued about. Note also that the §12.5 answer's own logic cuts both ways — Settings was
omitted for *never* having content, while Home's emptiness is conditional and temporary.

### 14.6 The outstanding validation needs ears, not tests

§13.4 found TalkBack is now **enabled** on the device, so the integration spec's long-open gate — "perform
the complete playback/navigation flow with TalkBack enabled" — is finally testable. It stays open because
nothing in it can be verified by instrumentation:

- whether TalkBack's own speech and the reader's TTS overlap in practice;
- where focus actually lands on entry, as opposed to what the semantics tree says;
- whether the chunking *sounds* right on a real page.

The third deserves naming plainly. Every acceptance criterion closed so far is about **structure** —
chunk counts, index alignment, id stability, semantics trees. None of them is about whether listening to
a real page is pleasant, and the largest real page in this workspace produces 343 chunks of a
specification. A pipeline can be exactly correct and still be unpleasant to listen to, and that
distinction is invisible to every test in this repository.

### Still open

- **B3** needs a product answer, and it is the only one blocking B's implementation.
- **§14.5** needs a product answer before the shell is built.
- The runner exit code (§11.3) and the TalkBack listening validation (§14.6) both remain open.
- `ReaderEngine.playFromStart()` still has no production caller (§13.4).

---

## 15. Eighth round — the queue, Browse, and the shell

**Date:** 2026-09-17. **Scope:** §14's plan, implemented end to end: B (Active Queue), the runner's exit
code, C (Browse's default state) and A (the shell, Home and the mini-player).

### 15.1 Slice B — the Active Queue

**What a queue element is** (§14.3 B1): `QueuedNote(pageId, title)`. Identity plus a label, no content —
a `Play all` over hundreds of pages must not fetch them all upfront, and the title is needed *before* its
document exists, for `Note 3 of 8` and for the between-Notes announcement. `ReaderLibraryEntry` will widen
this rather than replace it.

**Who loads** (B2): `ReaderSession` owns the queue, the position and the playback state, and is
**synchronous and scope-free** — it reports `QueueState.awaiting` and a caller fetches. `QueueLoader`
performs the one step of I/O. The state machine that decides *what should happen* has no coroutine, no
dispatcher and no failure path, so the between-Notes transition — the part most likely to be wrong — is
tested one step at a time with no waiting.

**A failed mid-queue Note stalls** (B3, product decision, since neither contract states it). Skipping
would silently drop a Note the user asked to hear — the audible form of the mutation the snapshot rule
exists to prevent — and aborting would discard Notes that are still playable for a failure the taxonomy
already calls transient. The position is kept and Retry re-loads the same Note.

**The queue's own bug, found by its test.** The first `ActiveQueue` took the caller's list, so a queue
built from a Browse result set was a *live view* of it: re-sorting the results would have re-sorted a
playing queue. `ActiveQueueTest`'s "snapshot" case caught it, and the fix is a private constructor plus
`snapshotOf`, which copies. A queue that shares storage with its source is not frozen, however immutable
it looks — and the contract's four "must not mutate" invariants all rest on this one property.

**End of queue** (B4): a session fact (`QueueState.completed`) and no invented retention, because
retention belongs to the Active Queue & Reader Session specification that does not exist. The next Note's
title is announced through the engine (`announceThenPlay`), because D9 says the title is not a chunk and,
for a listener with the screen off, an unannounced Note change is indistinguishable from the reader
having stopped.

**`origin`** is carried from the start (B5): `SingleNote`, `BrowseResults`, or a saved/smart queue by
*name* rather than by query, since holding a query would invite the re-evaluation four invariants forbid.

### 15.2 The instrumented runner's exit code — fixed

`connectedDebugAndroidTest` exits non-zero on a green suite, and a CI job would therefore fail on one. The
script now **derives its exit code from the collected results**, and reads **both** reports, using the
worse count:

```
HTML report: tests=32 failures=0
WARNING: Gradle exited 1 even though the collected results report no failures. This is the known AGP
bookkeeping discrepancy, not a test failure - see docs/decisions.md section 11.3.
RESULT: PASSED - 32 tests, 0 failures.
SCRIPT EXIT: 0
```

Reading both is not belt-and-braces: the two reports have disagreed in **both** directions on this
project, so neither is trustworthy alone and a green verdict requires both to be green. A missing counter
is treated as unknown rather than as zero, because an unreadable report must not be able to look like a
passing one.

### 15.3 Slice C — Browse's default state

The correction in §14.1 held up: Browse's default state needed no metadata specification. `POST /v1/search`
already returns exactly the pages shared with the integration, so "All Notes" is a fact about permissions
rather than a filter the app invents. On device, Browse listed **18 pages**, newest first — which matches
the block census recorded in round 4 exactly, so the index is complete as well as ordered.

Two design consequences worth naming:

- **The result set is derived, not stored.** `BrowseState.results` is `query.applyTo(entries)`, so the
  contract's "Browse state survives temporary navigation" is satisfied by construction: there is nothing
  to save and therefore nothing to forget to save.
- **`ReaderIndexEntry` lives in the reader domain, not in `browse`.** The product contract's architecture
  puts "normalized discovery metadata" *below* Browse, so a repository returning a Browse type would point
  the dependency the wrong way down its own diagram. That was the first version, and it was moved.

**The second bug its tests found:** `BrowseIndex.Loaded(entries)` / `Error(category, message)` meant a
failed reload had to overwrite the loaded entries with the error, destroying content that was on screen
and readable — the same rule the Note screen already follows for a failed refresh. Entries and status are
now separate fields, so neither can be destroyed by the other changing.

**Two interpretations recorded rather than left implicit:** `Play all` snapshots the whole result set, not
the rows currently visible in the viewport (18 queued, 9 on screen) — the contract says "the currently
visible result set", which reads as the set being looked at rather than the window of it. And search
matches **titles only**, because the Browse/Search specification that owns the real answer does not exist;
it cannot match a word that appears only in a Note's body.

### 15.4 Slice A — the shell

`ReaderDestination` gained Home and Browse and a split between top-level and secondary destinations, and
`ReaderNavigator` grew from one value to `ReaderNavigation(top, secondary: List)`, because Back stopped
being one edge:

| From | Back goes to |
|---|---|
| Player opened from Browse | Browse |
| Player opened from a **Note** | Note |
| Note | the tab it was opened from |
| Browse | Home (§12.5) |

The second row is why it is a list: Play is reachable from the Note screen, so the previous visual context
is not always a tab. Note and Player — the entire graph until this round — are now both *secondary*.
`ReaderNavigator` still holds no reference to playback, so "Back does not reverse playback" remains
structural rather than observed.

**Home ships with only Continue listening.** Your queues needs two missing specifications and Explore
needs the metadata specification, so two of the three sections are absent. With nothing to continue, Home
explains itself and offers Browse rather than rendering as a blank list — which is what distinguishes it
from the "empty result set" the contract warns about, and is why it is not the empty-Settings case.
It remains the weakest surface until persistence exists (§14.5).

**The mini-player** takes a `ContinueListeningCard` — a description plus two callbacks — and holds no
state, so it cannot start playback because it is not given the means to. It is absent on the Player, whose
full surface owns the same controls, and absent with no resumable session (D25).

### 15.5 Verified on device

`tools/probe-player-back.ps1` was rewritten to walk the contract's own example and to read tap targets out
of the accessibility dump instead of hard-coding coordinates:

```
home:        HOME | mini-player: absent
browse:      BROWSE | mini-player: absent
player:      PLAYER | Section 1 of 59 · Summary || Paragraph 2 of 349 || Playing | mini-player: absent
after back:  BROWSE | mini-player: Note 1 of 18 · Playing
speech after back: 20:00:37  com.google.android.tts  usage=USAGE_MEDIA content=CONTENT_TYPE_SPEECH
```

So: `Play all` → Player over a snapshot of 18 Notes → Back → Browse → playback still producing utterances,
with the mini-player present. The old version hard-coded Play at `(139,2154)`, which silently became a tap
on empty space the moment the shell changed what was on screen — worth recording as a class: *a probe that
misses reports a wrong answer with full confidence.* Two other defects in it were fixed in round 6.

**Not verified on device:** the between-Notes transition and the end-of-queue announcement. Reaching either
means reading a 349-paragraph document to its end. They are covered by JVM tests, which is the reason the
session was built scope-free.

### 15.6 What went wrong, and what is still wrong

**Two mistakes of mine, both caught by tests rather than review**, and both the same shape — a type that
looked immutable but was not:

1. `ActiveQueue` storing the caller's list (§15.1). The queue was not a snapshot.
2. `BrowseIndex.Error` replacing entries on a failed reload (§15.3). A failed refresh blanked the screen.

Both were found by writing the test that states the *property* ("the queue is a snapshot", "a failed
reload must not blank the screen") rather than the test that states the mechanism. Worth generalising: on
this project the property-shaped test has found more than the mechanism-shaped one.

**A wrong fix, reverted.** The intermittent instrumented `assertIsDisplayed` failure was attacked with
`performScrollTo().assertIsDisplayed()`. It throws — *"Semantic Node has no parent layout with a Scroll
SemanticsAction"* — because those controls are siblings of the scrolling content, not inside it. The
intent was right (assert reachability, which is how a listener reaches a control) and the mechanism was
wrong. Reverted, with the reason recorded at the assertion.

**Still open: the instrumented suite is intermittently flaky, and now visibly so.** Across four full runs
after the changes: 32/0, 32/1, 32/2, 32/0. A *different* test fails each time and the symptom is always
`assertIsDisplayed` reporting `is not displayed` about a control that is plainly present. Two candidate
causes were ruled out by measurement: the device's `screen_off_timeout` was already raised, and
`stay_on_while_plugged_in` was raised too (read and restored, not assumed — it was `15`, not `0`), with no
effect.

The prime suspects are now the **two enabled accessibility services**: `TalkBackService` and Bitwarden's.
`assertIsDisplayed` depends on the activity being resumed and drawn, which an accessibility overlay or an
autofill service that acts on focus changes can affect, and these tests drive Compose directly through
`createComposeRule`. That hypothesis is testable by disabling them for a run — which is a change to the
device's accessibility configuration, so it was **not done without asking**.

Two things follow, and the second is the important one:

- The runner now **fails honestly** on these runs. That is the intended behaviour and not a regression:
  an intermittent failure reported as a failure is what a gate is for. What is unresolved is the test or
  device stability, not the verdict.
- The narrowest fix that keeps the requirement without the device dependency is to assert the control is
  laid out with real bounds (`fetchSemanticsNode().size`) instead of that it is displayed in the window.
  That is a deliberate narrowing of an accessibility assertion, so it is left as a recommendation rather
  than applied unilaterally at the end of a round.

### Still open

- **The instrumented flake** (§15.6), with the prime suspects named and the two candidate fixes.
- **The TalkBack listening gate** (§14.6) is still open, and it is now more valuable than before: there is
  a queue, an announcement between Notes and a mini-player, all of which have only been verified as
  structure.
- Slice A's own instrumented checks — tab semantics, and the mini-player being two focus stops rather than
  one or six — are **not written**. The shell was verified on device by dump instead.
- Slices D and F remain blocked on governing documents; slice C's facets remain blocked on the metadata
  specification.
- `ReaderEngine.playFromStart()` still has no production caller (§13.4).
- `androidx.datastore:datastore-preferences` is still declared and still referenced nowhere.

---

## 16. Priority order

**Date:** 2026-09-17. **Scope:** what to do next and in what order, after §15. No code.

### 16.1 The ordering principle, stated so it can be overruled

Four things are ranked, and they are **not** ranked by user-visible value:

1. **Remove ambiguity from future rounds.** An intermittent failure that reports honestly makes every
   subsequent round's verdict ambiguous, so it is paid for first even though it adds no feature.
2. **Things the existing code was already shaped for.** Phase 5 built `ReaderDocumentPosition`, its
   resolver and four tests *as a handoff* to the session specification and deliberately gave them no
   production caller. `androidx.datastore:datastore-preferences` is declared and unused for the same
   reason. Code built to wait for a document is the clearest possible statement of which document is next.
3. **User-visible unlock.**
4. **Silent data loss.**

Where this diverges from product value is recorded in §16.4, because it does: the single most valuable
thing for a listening app is arguably not on this list at all.

### 16.2 The order

| # | Item | Kind | Why here | Blocked on |
|---|---|---|---|---|
| P0 | **The instrumented flake** (§15.6) | decision, then ~30 min | every future round's verdict is currently ambiguous: a random failure appears as "1 failing" and has to be explained | one answer from the product owner |
| P0 | **The launch-load defect** | ~15 min | `loadNotionDocument()` opens the Note then calls `openHome()`, which is top-level and clears the secondary, so the preloaded page is fetched on every launch and displayed nowhere. It is not a missing feature; it is a wired-up flow going nowhere | nothing |
| P1 | **Active Queue & Reader Session spec**, then implement it | document, then code | §16.3 | writing it |
| P2 | **Queue Creation & Queue Management spec**, then slice F | document, then code | the largest user-visible gap: the contract's Note actions are "Play, Add to queue" and only Play exists, so a primary action is simply absent | writing it |
| P3 | **Content Metadata & Workspace Schema Adapters spec** | document, then slice C's facets | unlocks Browse's filters, Home's Explore, and Smart Queues — which are impossible without it | writing it; partly a record of what the workspace cannot support |
| P4 | **Background Playback, Media Controls & Hands-Free Commands spec** | document | see §16.4 | writing it |
| — | **Small evidence-backed fixes** | code | can slot into any gap: image captions are speakable and dropped (8 blocks in this workspace), and a page dominated by a child database reads as ~empty (1 chunk from 2 blocks). Both from round 4's census | nothing |
| — | **Slice E — Raw Notion navigation** | code | the one roadmap slice still undone, and genuinely unblocked, but the lowest-value destination in the contract ("secondary escape hatch") | nothing |
| — | **Slice A′ — the shell's instrumented checks** | code | tab semantics and the mini-player's focus count; completes slice A, which was verified by accessibility dump instead | P0 |

### 16.3 Why the session spec is P1 rather than P2, given P2 unlocks more visible surface

Three reasons, none of them preference:

- **The code was already built for it.** `ReaderDocumentPosition`, `positionAt(index)`, `resolve(position)`
  and `ReaderIdentityStabilityTest` were written in Phase 5 as the handoff to this specification, and the
  integration spec *refuses* to own resume policy precisely so this document can. A second persistence
  client existing first would mean choosing the storage story twice.
- **It is what makes the app remember**, which is what makes Home, the mini-player and the Listening tab
  thin. §14.5 recorded Home as "the weaker surface until persistence exists" — one document away.
- **It settles persistence before Saved Queues need it**, which is the same argument from the other side:
  P2's saved queues need a store, and the store should be decided once.

### 16.4 Where this order disagrees with product value

**Background playback is the highest-value item on the list and is ranked last.** The Player holds the
screen awake with `keepScreenOn` as a stated workaround, which means this listening app cannot currently
be listened to while walking, with the screen off, or from a headset button. For an application whose
purpose is hands-free listening, that is the largest functional gap in the product — larger than saved
queues.

It is ranked P4 anyway because it blocks nothing and is blocked by nothing, so it loses every comparison
on the critical path. That is a real cost of ordering by unblocking, and it is written down so the choice
is visible rather than emergent: **if the priority is product value rather than unblocking, background
playback moves to P1.**

### 16.5 Two decisions needed

- **The flake** (§15.6): whether to test the accessibility-service hypothesis by disabling TalkBack and
  Bitwarden for one run — a change to the device's accessibility configuration, so not done unilaterally —
  or to narrow the assertion to laid-out bounds and keep the requirement without the device dependency.
- **P1 vs P4**: whether to keep ordering by unblocking (then the session spec is next), or by product value
  (then background playback is).

### Still open

- All of §15's open items, unchanged: the flake, the TalkBack listening gate, slice A′, slices D and F,
  slice C's facets, `playFromStart`'s absent caller, and the unused DataStore dependency.

---

## 17. Tenth round — P0 closed, and the missing session spec written and implemented

**Date:** 2026-09-17. **Scope:** §16's P0 and P1. Both decided and executed.

The two decisions §16.5 asked for were taken as follows, and both were taken in the direction that keeps
the project's own evidence standard:

- **The flake:** neither of the two options offered. The hypothesis in §15.6 was wrong, and the real cause
  turned out to be readable out of the library (§17.3).
- **P1 vs P4:** the unblocking order was kept, so the session spec is this round's work. §17.7 records that
  background playback is still the largest gap in the product and is still ranked last.

### 17.1 P0a — the launch load is gone, not redirected

`loadNotionDocument()` was deleted along with its call. It opened the Note screen and then called
`openHome()`, and Home is a top-level destination whose entire job is to clear the secondary it sits on, so
the page was fetched on every launch and displayed nowhere.

The other half of §16.5's choice — giving Home a "most recent Note" row — was **rejected**, and the reason
is the conflict protocol rather than taste: Home has exactly three sections, and a most-recent row is none
of them. Explore is the closest and is generated from metadata that does not exist yet, so the row would
have been an invented section, which means amending the UI contract in Notion *first*. Deleting a leftover
from the two-screen architecture needs no amendment.

The class comment says so, and the README was corrected: it had claimed `NOTION_PAGE_ID` "selects a
specific page", which stopped being true. `NOTION_PAGE_ID`, `NOTION_PAGE_URL` and the reader's
blank-reference auto-discovery path are now **resolved and reported by the build but read by nothing at
runtime** — recorded as open rather than deleted, because pinning the app to one known page is how the
pagination and block-coverage criteria were verified on device.

### 17.2 P0b — the six `assertIsDisplayed` calls were replaced, and the reason is mechanical

The flake was not the two accessibility services. Compose 1.12.0's `checkIsDisplayed` was read out of the
AAR's bytecode rather than guessed at, and it requires, in order: the node's layout info to be placed, then
**`androidx.test.espresso.matcher.ViewMatchers.isDisplayed()` matched against the root view**, then a
bounds-in-window intersection, then `View.getGlobalVisibleRect()`.

Those last two are claims about **the device's window state** — whether the Compose view is currently being
drawn and has a non-empty visible rectangle on the display. They are not claims about the app. That is why
the failure moved between runs and why it was always reported on a control that was plainly present.

`A11y.assertIsReachable` replaces them: the node is placed, occupies area, overlaps the surface root, offers
an `OnClick` action and has a non-blank accessible name. All five are deterministic, and the last two are
*stricter* than what they replace for the thing that actually matters — a screen-reader user moves focus and
activates, and does not look at the screen. What it gives up is stated in the code rather than left implicit.

### 17.3 The residual flake is the harness losing the activity, and it is now classified

With the assertions fixed, a run failed anyway — on a **different** test, one whose only assertion is
`assertExists`. The failure text is decisive:

```plaintext
java.lang.NullPointerException: Cannot run onActivity since Activity has been destroyed already
    at androidx.test.core.app.ActivityScenario.lambda$onActivity$2
```

Thrown before any assertion runs, so no assertion in the suite can be implicated. The earlier "is not
displayed" failures are consistent with the same event seen at the assertion instead of at setup: an
activity being torn down or no longer drawn fails `getGlobalVisibleRect` too.

Ruled out by measurement rather than by argument: `always_finish_activities` is 0, the keyguard is not
showing (`isKeyguardShowing=false`, `deviceLocked=0`), the device is awake, and animations are at 1.0. The
device is connected over Wi-Fi ADB (`192.168.0.101_5555`), which is recorded as a suspicion and not a
finding.

`tools/run-accessibility-tests.ps1` now classifies every failure against a deliberately short list of
harness signatures and repeats the run **once** if and only if every failure matches. A retry is reported
loudly with the first attempt's outcome and whether it reproduced, and any unclassified, mixed, or
count-mismatched failure refuses the retry — so a retry can never launder a real defect into a pass. The
classifier was verified against its seven decision cases before being trusted.

**The v2 Compose test rule was deliberately not adopted this round.** It exists in 1.12.0 and its own
deprecation notice says it moves from `UnconfinedTestDispatcher` to `StandardTestDispatcher`, which means
tests that assert synchronously after `setContent` need reworking. Migrating 32 passing tests while also
changing their assertions would conflate two risks; it is recorded as the next candidate for a fix of the
harness rather than a workaround for it.

Two bugs were found in the rewritten runner itself, both worth recording:

- A function that calls `Write-Output` **returns** those lines as part of its value, so the attempt object
  came back buried in Gradle's console log. And a native command's stdout inside a function is part of that
  function's output too, which is why the Gradle invocation now ends in `| Out-Host`.
- The verification of the classifier "failed" for a reason that was the verification's fault: `@(0)` is a
  one-element array, not an empty one, so an empty failure list has to be built rather than written.

### 17.4 P1 — the Active Queue & Reader Session spec is written, in Notion

Created as a row in **Projects**, as a sibling of the other three, so it is governed by the same process:
`docs/notion/` is generated, and the mirror is diffed rather than trusted.

**Notion page id `3dec9345-e10a-814c-a8f3-c8b3539ac8e1`** — `docs/notion/active-queue-and-reader-session-spec.md`.
Added to `tools/export-notion-doc.ps1`'s `-All` map; re-exporting confirmed the four existing mirrors show
**no drift** and the new one round-trips its headings, blockquotes, code fence and lists intact.

One artifact was caught by looking at the mirror rather than at the page: the exporter already writes the
page title as a heading, and the page's first block was a duplicate of it. The duplicate block was deleted
after asserting its type and text, and the mirror went from 404 to 402 lines.

The spec settles the things the other three explicitly deferred: **retention** (10 unfinished, 20 completed,
30 days for completed, the live session never evicted), **exact process-death restoration** (state is
restored at launch; playback never is), and **resume policy** — CHUNK silent, BLOCK and SECTION announce the
section, NONE says the position was lost and then starts from the beginning rather than silently restarting
or skipping the Note.

### 17.5 P1 — implemented, and the implementation found its own defect

Phases 1–5 of the spec's plan are in, JVM-testable with no device and no network: `StoredSession`,
`SessionStore`, `SessionCodec`, `ListeningHistory`, `ResumePolicy`, `ReaderSession.snapshot`/`restore`/
`replay`/`currentPosition`, `DataStoreSessionStore`, `ReaderApp`'s history, and Home's real list.

**The defect the tests caught is the one worth recording.** `ReaderEngine.announceThenPlay` hard-reset the
document to its first chunk. That was correct for the only caller it had — a Note transition, where the
document has just been replaced and has no position — and wrong for the second: an inexact resume would
have announced "Two" and then read the Note from the top. It now takes `startParagraphIndex`, defaulting to
0, and the test asserts the announcement and the start index together.

Three other findings, each from a test that was written to assert a property rather than a mechanism:

- `completedSessions` read only the stored history, so a session that had just finished did not move to
  Recently completed until a disk write. Both lists now read the same merged set, so a session can be in
  neither for no window at all.
- A stalled session *does* still know the position of the Note it is trying to load, so a snapshot during a
  stall preserves it. The first test asserted the opposite, which would have thrown the position away on
  every mid-queue failure.
- `androidx.datastore:datastore-preferences` — declared in round 5 and referenced nowhere since — now earns
  its place. §12 recorded it as waiting for exactly this.

The write path has one ordering constraint that is a correctness requirement: `loadHistory()` must complete
before the persistence collector subscribes. `persistSession` writes the retained set, which is history
**plus** the live session, so a write that won before the load would persist an empty history and discard
everything on disk — the listener's whole Continue listening list, erased by launching the app.

### 17.6 Verified

- **276 JVM tests, 0 failures** (was 215).
- **32 instrumented accessibility tests, 0 failures**, in one attempt — no spurious retry, and the runner
  finally exits 0 on a green suite by its own verdict rather than by Gradle's.
- **On device, end to end.** Fresh launch: Home says "Nothing to continue yet" and fetches nothing. Browse →
  `Play all` → Player reads the queue ("Note 1 of 19", "Paragraph 1 of 263", Playing). `Next section` ×3 →
  "Section 6 of 64 · This specification owns", "Paragraph 30 of 263". **`adb shell am force-stop`** →
  relaunch → Home shows "Continue listening / Notion Reader — Active Queue & Reader Session Spec / Note 1 of
  19 / Paused · Just now". `Continue` → the Player resumes at **Section 6**, and playback advances from there.

The honest gap in that last check: an exact resume and a fallback to the section start both produce audio
near the right place, so the *audio* does not distinguish them, and playback had advanced a few chunks by the
time the screen was read. The resume-quality record (`ReaderSession.lastResume`) is what makes that
distinguishable, and it is asserted by unit test; on the device the evidence for an exact match is the
*absence* of a log line plus the unit test for an unchanged document, not a positive device observation. The
device was not edited to produce an inexact restore, because that means editing the fixture page.

`tools/probe-history.ps1` was added to drive this. It reads tap targets out of the accessibility dump
rather than hard-coding them, which is the lesson `probe-player-back.ps1` already learned.

### 17.7 What this round did not do

- **Background playback is still last.** It is still the largest functional gap in a listening app — the
  Player still holds the screen awake with `keepScreenOn` — and it still blocks nothing and is blocked by
  nothing, so the unblocking order still puts it at P4. §16.4 is unchanged.
- **No Listening tab.** Completed sessions are readable through `ReaderApp.completedSessions` but have no
  surface; that belongs to the Queues slice, which needs the Saved and Smart Queue specs.
- **The TalkBack listening gate is still open**, and is still the only acceptance criterion nobody can
  close with a test.
- **Slice A′** (the shell's own instrumented checks) is still unwritten, and Home is now a surface that
  deserves one.
- **`ReaderEngine.playFromStart()`** still has no production caller.

### Still open

- The TalkBack listening gate (unchanged since §11).
- The Compose test rule's v2 migration (§17.3) as the proper fix for the harness losing the activity.
- Slice A′ (the shell's instrumented checks), slice D (Listening tab), slice E (raw Notion navigation), and
  slice F (Saved/Smart queues + `Add to queue`), plus slice C's facets.
- `ReaderEngine.playFromStart()`'s absent caller (§13.4).
- `BuildConfig.NOTION_PAGE_ID` and the blank-reference auto-discovery path, both now unconsumed (§17.1).
- An inexact restore has not been observed on a device; it is covered by unit tests only (§17.6).

# 18. The architecture stated in the tree, and the Listening tab

Round 11. Two things were asked for together: implement what the specifications still require, and move the
code toward the architecture the product contract already declares. They turned out to be the same job in
one place — the contract names the layers and the boundary rules, and the code was contradicting them.

## 18.1 What was actually wrong, measured rather than guessed

The internal import graph, read out of the source tree, had two cycles:

| Cycle | Edges |
|---|---|
| application ⇄ ui | `ReaderApp` → `ui.ContinueListeningCard`, while `BrowseScreen` → `app.BrowseIndexStatus` and `HomeScreen` → `app.describeLastActivity` |
| reader ⇄ notion | `ReaderDocumentMapper` → `notion.NormalizedBlock`, while `NotionApi` → `reader.ReaderErrorCategory` |

The first is the one that mattered. An application layer that depends on the presentation layer cannot be
exercised without it, and "which direction does this dependency point" stops having an answer. The second is
the product contract's own rule, stated twice — "Notion API DTOs must not leak into ... ReaderDocument
construction" — being false in the direction nobody checks, since the mapper reaching *down* into the Notion
DTOs is the leak the rule names.

Three more, each smaller and each real:

- `DataStoreSessionStore` lived in the application package. An adapter that constructs `DataStore` is not
  application state.
- `SessionCodec` lived in the reading domain. A wire format is an adapter concern; the domain should not
  contain the thing that knows how the domain is written to disk.
- The `SpeechEngine` port and `AndroidSpeechEngine` were in one package, so the port could not be seen to be
  a port.

**None of this was a behaviour.** 276 tests were green before the change and the same 276 were green after
it, which is the point: a dependency cycle is not a behaviour, it is a property nobody was asserting.

## 18.2 What the tree looks like now

| Package | Holds | May depend on |
|---|---|---|
| `domain` | the reader model, the Active Queue and session domain, normalized discovery metadata | nothing |
| `domain.discovery`, `domain.queue`, `domain.session` | the three sub-domains | `domain` |
| `player` | the engine, its state, and the `SpeechEngine` port | `domain` |
| `application` | use cases, application state, read models, navigation | `domain`, `player` |
| `data.content` | normalized content, the mapper, the chunker | `domain` |
| `data.notion`, `data.local` | the API client and its DTOs; the wire format | `domain` |
| `platform` | the Android TTS adapter | `player` |
| `ui` | the screens | `domain`, `player`, `application` |
| *(root)* | `MainActivity`, the composition root | anything |

Two of the moves were not tidying, and both are worth recording because the *placement* was the fix:

- **`ContinueListeningCard` moved from `ui` to `application`.** It was declared in `ui/HomeScreen.kt` and
  imported by `ReaderApp`, and that single import was the whole cycle. It belongs to the application because
  of what it is — a read model that `ReaderApp` builds and two screens render, containing no Compose type and
  no formatting (`lastActivityAt` is a raw instant precisely so the UI decides how to say it). A value the
  application layer constructs and the UI layer renders is an application-layer value; that it was written
  down in the file of its first consumer was an accident of history.
- **`DocumentLoadState` moved *out* of `ReaderDocument.kt` into `application`.** The same reasoning in
  reverse: it is the outcome of a use case, not a fact about a document, and its `Error` arm carries a display
  message and a retryable flag. Leaving it beside the document is what put an `application` import into the
  `domain` once the file had moved.

## 18.3 The rules are enforced, not documented

`LayerRulesTest` scans the sources and fails the build on a forbidden edge. Before it existed it reported
seven violations, of which the five documented above were the real ones. It also asserts two things that
matter more than they look:

- **Every file's package matches its directory.** Kotlin does not require this, which is exactly why it is
  asserted: the migration rewrote package declarations before it moved files, so a build green in between
  would have described a tree that did not exist.
- **Every layer named in the rule table exists as a package.** Without this, renaming a package in the tree
  and not in the table makes `allowed[ownFamily]` return null and the loop `continue` — the whole layer stops
  being checked, silently, while the suite stays green.

Two scoping decisions inside the test are deliberate. Rules are stated per **family** (`domain.queue` and
`domain` are both `domain`), because the contract's diagram is about layers and not sub-packages and an
intra-family ordering would be invented. And comments are stripped before scanning, because a KDoc link to
another layer is prose, not a dependency, and flagging it would push the explanation out of the file that
needs it.

## 18.4 The Listening tab

Queues is now the third shipped top-level destination, with the Listening tab populated. It was blocked on
nothing but a decision: §17.7 recorded that `completedSessions` could list what the listener had finished and
nothing could show it, because Saved and Smart Queues — the other two of the contract's three tabs — need two
specifications that do not exist.

**One of three tabs ships, and that is a scoped deferral of exactly the kind §12.5 already settled.** Two
permanently unusable controls one tap from a populated surface is the same wrong as an empty screen, only
better hidden. The tab row is present with one tab so that the structure which will hold three is in place;
TalkBack therefore announces "Listening, tab, selected, 1 of 1", which is true, and the contract's "1 of 3"
arrives with the two tabs it counts.

The spec had already settled everything the surface needs, so this round added a read model and a screen, not
a rule:

- **`HistoryEntry` is a second type rather than a reuse of `ContinueListeningCard`.** The two surfaces show
  different things about the same sessions, and the difference is normative: an entry can be *completed*,
  which is a session with no playback state to describe and no position to resume, whose control starts a new
  session rather than continuing this one. Merging them would make each carry a field it does not use. Two
  types, because "a completed session is history, not resumable state" is one rule with two consequences.
- **Two controls, two verbs.** Continue restores that exact session; Play on a completed entry starts a new
  session over the same Notes from the beginning. One label for both would be a lie about what happens next.
- **Home stays capped at 3 and Listening is uncapped**, which is the contract's "a small useful subset" for
  Home against the full history under `Queues → Listening`. Asserted, because the failure mode is silent: if
  the Listening tab had inherited Home's cap, session four would have nowhere to be seen.

`ListeningTabTest` covers the surface-independent half (which sessions, in what order, what each says) and
`QueuesScreenAccessibilityTest` the semantics: one focus stop per entry, a named control beside it, an omitted
section heading rather than a blank one, relative times and no raw timestamp, tab role and selection, a pane
title.

## 18.5 Two defects the tests found, and one the tests could not

**`assertIsReachable` was conflating "visible" with "activatable".** Its first use on a history entry failed
for the right reason: the entry has no `OnClick`, because the spec requires Continue to be a separate named
control rather than part of the entry's announcement. The alternative fixes were both worse than the
observation — relaxing the assertion would have lost the control check elsewhere, and making the whole row
tappable would make the title an unlabelled gesture target, which is the "reachable only by a gesture" case
the spec also forbids. So `A11y.assertIsReadable` was added: laid out on the surface, with something to say,
and no claim about activation. The two assertions now answer the two different questions.

**A locked device reads as a total regression, and the runner was not checking.** The instrumented suite
reported 45 failures out of 47, uniformly, with `No compose hierarchies found in the app`. That message names
the app's own `setContent`, so it reads as broken composition, and because it fails *every* test it reads as
catastrophe rather than environment. The cause, measured: `isKeyguardShowing=true`. Behind a lock screen the
test activity never comes to the foreground, so no Compose hierarchy is ever drawn.

Two earlier mitigations existed and were both insufficient, each for a reason worth keeping:

1. Waking the display and calling `wm dismiss-keyguard` **once, in the same breath as the wake**, races — the
   dismiss can arrive before there is a window to dismiss.
2. `stay_on_while_plugged_in=7` was relied on to keep the display on, and **does nothing on a device that is
   on battery**. This device is routinely unplugged, so the mitigation was inert for the case it was added
   for. That is now measured and reported rather than assumed.

The runner now wakes, dismisses and **verifies**, retrying the pair, and refuses to run if the keyguard is
still up — because a result that looks like 45 app failures when the phone is locked is worse than no result.
It does not attempt to unlock a secure device: that needs the owner's PIN, and a script that typed
credentials would be a worse problem than the one it solved. The locked-device signature is also classified as
a harness failure, as a second line of defence.

**What no test could have found, and did not:** the migration tooling, which is §18.6.

## 18.6 The migration, and four ways PowerShell lied

`tools/apply-layer-refactor.ps1` moves the files and rewrites every reference; it is dry-run by default and
idempotent, like `apply-spec-amendments.ps1`. `tools/fix-unresolved-imports.ps1` then converges the imports
against the compiler. Four distinct traps, all recorded in the tools:

1. **`@{}` is a case-insensitive Hashtable.** `ReaderErrorCategory` (an enum) and `readerErrorCategory` (the
   extension property that maps a failure onto it) merged into one entry declared in two packages, so the
   index dropped it as ambiguous. Same for `QueuedNote`/`queuedNote` and `StoredSession`/`storedSession`. A
   name missing from the index is invisible: the prune does not remove it, the compiler is not asked about it,
   and nothing reports it. The index silently lost its most-referenced entries. Fixed with `Dictionary`/
   `HashSet` built on `StringComparer.Ordinal`.
2. **`-match` and `Sort-Object -Unique` are also case-insensitive.** `-match` made `readerErrorCategory`
   "present" in `ReaderErrorCategory` and added an import — which made *`domain` depend on `application`*, the
   one edge this refactor exists to remove, invisible because Kotlin does not report an unused import.
   `Sort-Object -Unique` deduplicated `io.ktor.http.ContentType` against `io.ktor.http.contentType` and kept
   one, dropping a Ktor type the file needed. Both are now ordinal.
3. **A function returning an empty collection returns null.** The pipeline unrolls it, so an empty `HashSet`
   arrives as `$null` and the failure surfaces at the caller as "cannot call a method on a null-valued
   expression". `,@collection` fixes it. This is the same family as the exporter's missing list numbers —
   the symptom points at the consumer, the cause is in the producer.
4. **A regex that never matches is indistinguishable from a build with no errors.** The error pattern used
   `(?<path>[^:]+)` for the file path, and a Windows path contains a colon of its own in the drive letter, so
   it matched `D` and failed on everything. The tool reported success twice over a tree full of unresolved
   references. The path is now captured lazily.

The convergent design is the durable lesson, and it replaced a wrong approach that is kept in the script
beside the right one: **a scan can decide what is *legal* to import, but not what is *needed*.** Only the
compiler can answer the second question, so the tool asks it and iterates to a fixpoint. The first version
tried to infer need from appearance, and `fun interface BlockChildrenSource` taught it why that fails — the
pattern captured `interface` as a declaration name, and every file containing the word inherited an import
for it.

One limitation is deliberate and documented: the index holds **top-level** declarations, so a legal import of
an extension declared as a *member of an object* (`import ...A11y.assertIsReachable`) is not vouched for and
was pruned. Members cannot simply be added — registering every member name would offer `import ...A11y.document`
to any file mentioning `document`, and because the *prune* is not compiler-driven, such an import would
survive unreferenced and could create a layer violation from nothing. The mitigation is the compile target
list, and it is the second version of it: the first compiled `main` and the unit tests only, which is every
source set **except the one that had lost the most**. `testDebugUnitTest` and `assembleDebug` do not compile
`androidTest`, so the breakage was committed with the suite reporting 295 green. The tool now compiles all
three, and a tool that reports success while a source set is broken is worse than no tool.

## 18.7 The frozen wire format

A persistence round-trip test that encodes and decodes within one build **cannot fail for the reason that
matters**: rename a field, or change a `QueueOrigin` wire name, and the encoder and decoder agree with each
other perfectly while disagreeing with every record already on a device.

What makes that worse than an ordinary compatibility break is what the application then does, by design: a
record that will not decode is silently dropped, the history reads as empty, and the first position change
writes the retained set — which is now history-without-those-records. The listener's entire Continue
listening list is erased by launching the app, with nothing shown, nothing logged, and no way to tell it from
a first run.

`SessionWireFormatTest` pins the encoder to the exact bytes of schema version 1 and feeds those same bytes
back to the decoder. Both directions, because they fail separately: a renamed field breaks the first, a field
made non-nullable breaks the second. Note what the frozen string shows — `"origin":{"type":"browse_results"}` —
that `QueueOrigin` is polymorphic, so its wire form is a discriminator plus a serial name and either half
changing breaks stored records while leaving a round trip green.

When that test fails, the question is not how to update the constant. It is whether the change breaks records
already written, and if so whether `SESSION_SCHEMA_VERSION` needs bumping so the old ones are discarded
deliberately instead of by accident.

## 18.8 Verified

- **295 JVM tests, 0 failures** (was 276). The additions are the 3 architecture rules, the 11 Listening-tab
  behaviours, the 4 wire-format checks, and 1 navigator case for the new destination.
- **47 instrumented tests, 0 failures**, across Note (17), Player (15) and Queues (15). One attempt, after the
  device was unlocked — the unlock was the whole cause of the earlier 45-failure run.
- **The debug APK builds**, and the refactor changed no behaviour: the same 276 tests passed before and after.

## 18.9 What this round did not do

- **The TalkBack listening gate is still open.** Unchanged since §11, and still the only acceptance criterion
  no test can close. It is now the oldest open item on the project.
- **Background playback is still last**, for the same reason as §16.4: it blocks nothing and is blocked by
  nothing, so it loses every critical-path comparison while being the largest functional gap in a listening
  app.
- **Saved and Smart Queues**, and `Add to queue` — the contract's primary Note action that still does not
  exist. Both need specifications authored in Notion first.
- **Slice A′** (Home's instrumented checks) is still unwritten, and Home is still the only shipped surface
  with no instrumented test of its own.
- **The Compose test rule's v2 migration** (§17.3) remains the proper fix for the harness losing the activity.
- **`ReaderEngine.playFromStart()`** still has no production caller.
- **`BuildConfig.NOTION_PAGE_ID`** and the blank-reference auto-discovery path are still unconsumed.
- **An inexact restore** has still not been observed on a device; it is covered by unit tests only.

## Still open

- The TalkBack listening gate (§11), now the oldest outstanding item.
- The Compose test rule's v2 migration (§17.3).
- Slice A′ (Home's instrumented checks), slice E (raw Notion navigation), slice F (Saved/Smart queues and
  `Add to queue`), plus slice C's facets and the two deferred Queues tabs.
- `ReaderEngine.playFromStart()`'s absent caller (§13.4).
- `BuildConfig.NOTION_PAGE_ID` and the blank-reference auto-discovery path (§17.1).
- An inexact restore on a device (§17.6).