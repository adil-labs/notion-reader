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

> **Superseded in §20.** Both P0 items and P1 are closed (§17), and the list above has been re-derived
> against the UI contract as it now stands; §20 is the current order. The last row's "P0" dependency meant
> the flake, not a priority on A′ itself, and it has lifted.

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
- **On device, end to end** (`tools/probe-listening-tab.ps1`, which reads tap targets out of the
  accessibility dump rather than hard-coding coordinates). `Browse → Play all → Player` shows
  "Note 1 of 19", then **`am force-stop`** → relaunch → **Queues → Listening** shows:

  ```
  Continue listening
  Notion Reader - Active Queue & Reader Session Spec
  Paused · Note 1 of 19 · Just now
  Continue
  ```

  That is the contract's own example shape (`Paused · Note 2 of 9 · 2 hours ago`), the session named, its
  queue position stated, a relative time and no raw timestamp, and Continue as a named control beside the
  entry rather than part of its announcement.

  One detail in that transcript is worth keeping, because it looks like a bug and is not: **the same Note
  appears twice**, once as "Just now" and once as "1 minute ago". Those are two *listening occurrences*,
  which is exactly what the spec requires — "two sessions that read the same Note are two entries" — and it
  is `session.id` rather than the page id that makes it true. Two probe runs produced two sessions, which
  is the identity rule working, not history duplicating.

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

## 19. Eleventh round — an architecture audit, and six ownership corrections

§18 put the layers in the tree and enforced them. This round asked a different question of the same code:
*for each piece of mutable state, who owns it, what event changes it, and can any other component
independently disagree with it?* The layering was already sound — the audit found no new cycle, no Notion
DTO above the integration boundary, no second playback model, and no state that two holders could drift
apart on. What it found instead were four places where a **result was applied without checking whether it
was still wanted**, one place where the **session's lifetime was a screen's**, and one contract clause that
was satisfiable in three lines and unmet.

The classification §18 used is repeated here, because it is what decided the change set: **A** correct and
left alone, **B** works with unnecessary complexity, **C** plausible bug or race, **D** violates a governing
specification, **E** missing behaviour for the current milestone. Nothing in this round is a **B**; a
cosmetic refactor is not what was asked for.

### 19.1 The worst one: a superseded fetch could be read aloud as the new session's Note 1 (D, C)

`ReaderSession.supplyDocument(document)` checked only `QueueState.awaiting`, which answers "is a Note
wanted?" and not "is *this* Note wanted?". Those differ exactly when a session is replaced while a fetch is
in flight, which is not an exotic interleaving on Notion: opening one page is several HTTP requests, and
§4 already measured a 343-chunk page taking tens of seconds.

```
playAll(A)   queue = A, awaiting, A0's fetch begins and suspends
playAll(B)   queue = B, awaiting, B0's fetch begins
A0's fetch returns
             -> accepted, because the session is awaiting something
```

The session then announced and read **A0 as B's first Note**, with B's queue position on screen. If A0 is
not in B at all the listener hears a Note they never queued, at a position claiming to be note 1 — and the
route to it is ordinary: `Play all`, Back to Browse, change the search, `Play all` again. `failCurrentNote`
had the mirror-image hole: a slow *failure* from the replaced session would stall the one that replaced it,
offering Retry for a Note that session had never asked for.

**The correction is a rule of the state machine rather than a guard at the call site:** a document may enter
a session only when its `pageId` is the page the queue currently awaits, and a failure must name the page it
belongs to. A caller cannot be trusted to know whether its own request is still current — by the time it
returns, the thing it was loading for may not exist — so the check is where the state is, and it holds for
every caller, including the next one to be written.

*Why `pageId` and not a request counter.* A queue identifies its Notes by page id, so a matching document
*is* the awaited document by definition; two entries with the same page id are the same Note, and the
residual window (same id, superseded session) resolves to the right content. This also kept the change to
two lines and five test sites rather than twenty-five, which is what the same fix written as a token
threaded through every `supplyDocument` call would have cost.

### 19.2 A Note with nothing to read was a silent dead end (E, D)

`NotionRepository` deliberately treats a page with **no blocks** as a valid empty document rather than a
failure, and that is right — the document is a faithful description of the page. It is not, however,
*playable*, and the queue advances on a completion signal an empty document never produces:
`ReaderEngineImpl.play()` returns immediately when there is nothing to speak, so `hasReachedEnd` never
becomes true and `onCurrentNoteFinished` is never called. A titled but empty Notion page — reachable, and
listed by Browse — left the session on that Note for the life of the process, with an enabled Play button
that did nothing and nothing on screen to explain it.

`QueueLoader` now reports an unplayable document the way it reports an unreadable one, on the taxonomy's
existing `UNSUPPORTED_DOCUMENT_STRUCTURE` ("This page has no content the reader can speak"): the listener is
told, the queue keeps its position, and the Note is not silently skipped — the choice §14.3 (B3) already
made. The repository's decision is untouched; only the *loader*, which owns "did this Note load?", changed.

### 19.3 Two screens could describe different Notes, and Retry reloaded the wrong one (C)

`NoteViewer.load` let the **last to finish** win, which is not the same as the last to be *asked for*, and
the two diverge precisely when one page is slower than the next. Worse, the viewer wrote its `pageId` when
the load started and its `document` when it finished, so a late result left the two describing **different
pages** — and Retry then re-loaded the interactive one's id while the other one's text was on screen. That
is the ambiguity this class exists to remove, so it is now resolved the only way that has a stable answer:
a request counter, and a superseded load publishes nothing — not its document, not its state, not an error.
`BrowseModel.load` had the identical shape and got the identical fix, because a superseded index reload
could replace a newer one.

The counter is not a `Job`: the caller owns the coroutine and therefore owns cancellation, and neither class
may acquire a scope — the same reason `ReaderSession` has none.

### 19.4 The session's lifetime was the Activity's, and a configuration change is not a navigation (D)

`MainActivity` built `ReaderApp` in `onCreate` and called `shutdown()` in `onDestroy`, and the manifest
declares no `android:configChanges`. So **every configuration change** — rotating the phone, raising the font
size, or the system switching to dark mode at dusk — shut the speech engine down and discarded the live
session, replacing it with a fresh one on the demo document and re-fetching the Browse index. None of that is
navigation (which the contract forbids from owning playback) and none of it is process death (which the
Active Queue & Reader Session spec does handle). It was a plainly reachable loss of the listener's place.

Ownership moved one level up, to a `ReaderViewModel`: state whose lifetime is longer than a screen's and
shorter than the process's, which is exactly the boundary the session needs. `onCleared` — called when the
Activity is *finished*, not when it is recreated — became the single shutdown point. Because it is a
composition-root concern it lives in the root package, which is the one package allowed to name `data`,
`platform` and `application` together.

The three collectors and the two loaders went with it, so the Activity stopped being the thing that writes
history and fetches the index, and they are now subscribed once per task rather than once per rebuild. Two
job slots were added at the same time, so a superseded fetch is **cancelled** as well as ignored: the counter
is the correctness property (it holds however the caller behaves) and the cancellation is the saving, and
both are deliberate for that reason. The ordering constraint from §17 survives verbatim and is now stated in
one place: `loadHistory()` must finish before the persistence collector subscribes, or the first write
persists an empty history plus the live session and erases everything on disk.

### 19.5 The same request was fetched twice (C)

`Continue` restores a session *and* the queue observer sees the same state change, so `loadPendingQueueNote`
was reached twice for one Note — two fetches of a page that is several requests, and an inconsistent state if
exactly one of the two failed, because a failure cannot be told from a request still running. `QueueLoader`
now guards on the **request** rather than on "something is in flight", which is what keeps superseding
immediate: a new `Play all`, or a Continue on a different session, is a different request and starts at once
instead of queueing behind the one it replaced. The session supplies the request identity
(`PendingNoteRequest` = page id + a counter that changes on every transition that issues a request), because
which Note is wanted is session state and a loader tracking it itself would be a second copy of the queue.

### 19.6 Browse could not keep the user's place, and three lines fixed it (D)

Invariant 13 is "Browse state survives temporary navigation where practical", and Browse's *query* was
already safe — it lives in `BrowseModel`, which is application state above the screens, and the result set is
derived from it rather than stored. The **result position** was not: the shell's `when (current)` removes
`BrowseScreen` from composition, and its `LazyColumn`'s `rememberSaveable` list state went with it. A
listener who scrolled forty results, opened one and came back was at the top again.

The shell now wraps the destination in a `rememberSaveableStateHolder().SaveableStateProvider(current.name)`,
which is the mechanism `NavHost` uses for the same purpose: each destination keeps its own `rememberSaveable`
state while another is on screen. Applied at the shell rather than inside `BrowseScreen`, because it is a
property of *switching surfaces*, not of one screen.

### 19.7 Audited and deliberately not changed (A)

Each of these was read with the question in the section heading above, and each answered it:

- **Persistence failure isolation.** `DataStoreSessionStore` swallows read and write failures at the
  boundary, `SessionStore` has no error channel, and `ListeningHistory` is pure. The spec's "a failed write
  must not stop, pause, or alter playback" is satisfied by construction rather than by handling.
- **What is stored.** `StoredSession` holds identities, a `ReaderDocumentPosition` and no numeric chunk
  index, no content, no derived coordinate, and no per-Note position. Retention and ordering are pure
  functions with the live session pinned. Unchanged.
- **Navigation cannot own playback.** `ReaderNavigator` holds no session reference, so Back *cannot* mean
  Pause — a stronger guarantee than a comment asking the next person not to.
- **The Active Queue is a snapshot.** `ActiveQueue`'s constructor is private and `snapshotOf` copies; a queue
  built from Browse results is not a live view of them. Unchanged since §15.
- **One playback state, two surfaces.** The Player and the mini-player both read `session.state`, and Home's
  card and the mini-player share `ContinueListeningCard`, so they cannot describe one session differently.
  No second playback model was found anywhere.
- **The engine's stale-callback guard.** `utteranceToken` discards a completion from a cancelled utterance.
  Correct, and it is the pattern the three new staleness guards follow.
- **Notion DTOs stay below the boundary.** `ui`, `application` and `domain` name nothing in `data.notion`,
  and §18's rule table fails the build if that changes.
- **Everything deferred for a missing specification.** Settings, the Saved and Smart tabs, `Add to queue`,
  `Add all to queue`, Browse's facets, raw Notion navigation, the Player's `Queue` action and background
  playback are all still absent for the same reason as before: each needs a specification that does not
  exist, and shipping an inert control is the objection §12.5 already settled. Not touched.

### 19.8 Verified

- **303 JVM tests, 0 failures** (was 295). Eight additions, and every one of the fixes above has a test that
  **fails when its guard is removed and no others do** — the same failing-first discipline §13 used, applied
  to all six:
  - removing the document-identity check fails exactly `aDocumentForANoteTheQueueIsNotWaitingForIsRefused`;
  - removing the failure-identity check fails exactly `aFailureForANoteTheQueueIsNotWaitingForIsIgnored`;
  - removing the unplayable-Note branch fails exactly `aQueueNoteWithNothingToReadStallsInsteadOfStallingSilently`;
  - removing either request counter fails exactly its two race tests (4 in total);
  - removing the queue loader's guard fails exactly `aNoteIsFetchedOnceEvenWhenItIsAskedForTwice`.
- **Five existing test files were migrated, not weakened.** The session's identity rule means a test must
  supply the document the queue is actually awaiting, so `TestDocument` gained `originalDocumentFor(pageId)`
  and `testDocument(..., pageId = ...)`. The resume tests were handing a session a document whose page id had
  never matched the queued Note's; they now say which Note it is, which is what the session always assumed.
- **47 instrumented tests, 0 failures**, unchanged and unaffected: they call `setContent` with one screen and
  never build `MainActivity`, which is why §19.4 is proved on the device instead.
- **The debug APK builds.** `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`.
- **On device, a configuration change under a live session** (`tools/probe-config-change.ps1`, new). It walks
  `Browse → Play all → Player`, moves the reader two sections in with `Next section`, changes the **system
  font scale** — the least invasive change that still rebuilds the Activity, restored in a `finally` — and
  reads the accessibility tree on both sides:

  ```
  before:  Note 1 of 19 | Section 3 of 64 · Goals | Paragraph 10 of 263 | Playing
  after:   Note 1 of 19 | Section 3 of 64 · Goals | Paragraph 11 of 263 | Playing
  ```

  All five checks pass, and one detail in that transcript is the whole point: **the paragraph advanced from
  10 to 11 across the rebuild**, so speech was never interrupted rather than merely resumed afterwards. Then
  `Next section` moved *this* session to "Section 4 of 64 · Non-goals", which a rebuilt app could not do
  because it would be sitting on Home with the demo document. The checks are discriminating on purpose: a
  Player on top, the same queue line, a position that did not reset, `Playing`, and a live engine still
  answering.
- **On device, the flow from §18 is unchanged** (`tools/probe-listening-tab.ps1`: install → Browse → Play all
  → Player → `am force-stop` → relaunch → Queues). All five checks pass, so moving the persistence
  orchestrators into the ViewModel did not disturb the load-before-subscribe ordering that history depends
  on.

### 19.9 What this round did not do

- **The result-position fix is verified by construction, not by a test.** It is four lines of Compose
  runtime behaviour with no JVM-testable surface, and the instrumented suite tests screens in isolation, so
  the shell it lives in has no test — the same gap as slice A′. Stated rather than implied.
- **A superseded Note load is still *fetched*, once, before it is discarded.** Cancellation now usually wins
  (the ViewModel cancels the previous job), but the session's identity rule is what makes the outcome
  correct, and it is what holds if a future caller has no job to cancel.
- **A failed Note load in a queue is still a dead end.** §14.3 (B3) chose stall-with-Retry over skip or
  abort, and §19.2 follows it, so a Note that will never load — one with no content, say — stops the queue
  with Retry as the only option and no way past. That is a product gap owned by a queue-management
  specification that does not exist, not a defect in this round's corrections.
- **Play on the Note screen with an unplayable document** still does nothing visible, for the same reason.
  The single-Note case has no queue to stall, and §19.2's fix is in the queue loader.
- **Everything §18.9 listed** — the TalkBack listening gate, background playback, Saved and Smart Queues,
  slice A′, the Compose v2 rule, `playFromStart()`, the unconsumed `NOTION_PAGE_ID`, the unobserved inexact
  restore — is still open. This round was an audit, and an audit that starts refactoring things that are
  already correct is the failure mode it was written to avoid.

---

## 20. The UI contract as the remaining work, and the order of the next slices

**Date:** 2026-09-18. **Scope:** read the UI Screens & Navigation Contract as a work list for the second
time — §12 did it when almost nothing had shipped, and §17–§19 have since shipped the queue, the session,
the shell, Browse's default state and the Listening tab — then enumerate what is still unmet, what each
piece waits on, and in what order the next slices go. No code.

### 20.1 What the contract asks for and does not exist

| # | Contract requirement | Waits on |
|---|---|---|
| 1 | **Settings**, the fourth top-level destination (invariant 1) | nothing, because there is nothing to put in it — "specific settings are introduced by the specifications that require them". Not a slice until a specification requires a setting; the named candidates are background playback and TTS preferences |
| 2 | **Home → Your queues** (invariant 4) | the Queue Creation & Queue Management spec — there is no Saved or Smart Queue to surface |
| 3 | **Home → Explore** (invariant 4, "reader-oriented shortcuts into result sets") | the Content Metadata & Workspace Schema Adapters spec |
| 4 | **Browse → facets** (invariant 7, "filters are facets, not folders") | the metadata spec, plus §5's recorded workspace gaps |
| 5 | **Browse → the active filter summary** | facets — with none active there is nothing to summarise, and with them absent nothing can become active |
| 6 | **Browse → a sort control** (invariant 7 names sorting; "sort order is user-visible because it directly determines `Play all` order") | nothing for `Title`; a content date for the date orders; the Browse/Search spec for `Relevance` |
| 7 | **Browse → `Add all to queue`** (invariant 12) | Queue Destination → Saved Queues → the Queue Creation spec |
| 8 | **Browse → `Browse Notion structure`** | nothing |
| 9 | **Note → `Add to queue`** ("keep primary actions minimal: Play, Add to queue") | the Queue Creation spec |
| 10 | **Player → `Queue`** ("expected primary controls: `Previous section / Play / Pause / Next section / Queue`") | **nothing** — see §20.2 |
| 11 | **Queues → Saved**, Saved Queue Details, Create Saved Queue, Edit Saved Queue (invariants 14, 17, 19, 20, 21) | the Queue Creation spec |
| 12 | **Queues → Smart**, Smart Queue Details, Edit Smart Queue (invariants 14, 18, 22) | the Queue Creation spec, and the metadata spec for "12 current matches" |
| 13 | **Queue Destination** (invariants 12, 22, 23) | the Queue Creation spec |
| 14 | **Raw Notion Navigation** and **Raw Notion Database Pages** (invariant 30) | nothing, but it needs API surface `NotionApi` does not have — see slice K |
| 15 | **Back restores the previous meaningful focus target** ("`Browse` result 7 → `Note` → Back should preferably restore focus to result 7") | nothing; only the *scroll position* is restored today (§19.6) |
| 16 | **Slice A′** — Home's and Browse's own instrumented checks, and the shell's | nothing; neither screen has any, and the shell is covered by nothing but `tools/probe-*.ps1` |
| 17 | **The Compose v2 test rule** (§17.3) | nothing |

Three further items are not in the UI contract but are the product's core, and two are recorded as measured
losses rather than as improvements:

| # | Item | Evidence |
|---|---|---|
| 18 | Image and file captions are speakable text and are dropped | round 4's census: 8 blocks in this workspace |
| 19 | A page dominated by a child database reads as ~empty | round 4: 1 chunk from 2 blocks (§16.2) |
| 20 | Ordered-list numbering is not recoverable | one `LIST_ITEM` kind serves both list styles |

And one is a whole specification away: **background playback**, whose rank §16.4 already records as the
plan's largest disagreement with product value.

### 20.2 The one control that became unblocked and was not noticed

The Player's `Queue` action is the fourth of the four primary controls the contract names, and three ship.
It was deferred as Saved Queue territory, and that reading is wrong: the contract says the opposite twice
over — "`Queue` opens a view of the current frozen Active Queue/session if such a view is required. This
represents the current playback snapshot. **It is not the underlying Saved Queue or Smart Queue
definition.**"

The condition in that sentence is now satisfiable without inventing anything, because §17's session spec
defines the snapshot and `ReaderSession` already publishes it: `QueueState.queue.items` is a
`List<QueuedNote>` — identity plus title, in frozen order, for every member — and `QueueState.index` says
which one is current. A queue view is therefore a **read-only rendering of state that already exists**: no
new state, no fetch, no second playback model. That is the same rule invariant 26 requires of the
mini-player, applied to the one other surface that describes a session.

It is also the only place where §15.1's between-Notes announcement has a visible counterpart, and the only
control on the contract's Player list that is cheap and unblocked.

### 20.3 The ordering principle, unchanged, and what it now ranks

§16.1 ranks four things, none by user-visible value: **remove ambiguity from future rounds**, **things the
existing code was already shaped for**, **user-visible unlock**, **silent data loss**. Three things have
changed since it was written, and each moves the order:

- **Its rank-2 argument has been spent.** The session spec was ranked first because
  `ReaderDocumentPosition`, its resolver and the unused DataStore dependency had been built to wait for it.
  They no longer wait for anything. Whatever is next inherits that argument only if the code was shaped for
  it, and the one remaining candidate shaped this way is slice F, whose `ReaderSession.start(queue)` and
  `ActiveQueue.snapshotOf` were written to be reused rather than replaced.
- **Two items are rank 1, and one of them is the shell's tests.** Slice A′ has now been deferred across
  four rounds, and each of those rounds closed by verifying the shell "on device by dump" or "by
  construction". §19.9 stated the cost: the four-line fix at the centre of that round has no test at all.
  A shell nothing tests makes every later round's shell change ambiguous — including slice M, which adds
  four secondary screens and two tabs to it.
- **The largest unlock is a document.** `Add to queue` is the contract's own second primary action for a
  Note and it does not exist; four secondary screens, two tabs and two result-set actions wait on the same
  missing specification, and two of §20.1's rows (#2, #12) wait on it as well.

### 20.4 The plan

Named G onward, so that the roadmap letters A–F keep meaning what §12.4 made them mean.

| # | Slice | Delivers | Blocked by | Verified by |
|---|---|---|---|---|
| **G** | **The shell's instrumented checks** (slice A′, completed) | Home's and Browse's own checks; tab semantics for the destinations that ship; the mini-player being two focus stops rather than one or six, and absent on Player; Back returning to the originating tab; Browse's position surviving a trip to a Note | nothing | the instrumented suite, Home and Browse added to the three screens that have one |
| **H** | **Restoring focus on Back** | `Browse` result 7 → `Note` → Back lands on result 7; the same for a queue row, and for returning from Player | nothing | G's harness, extended — the contract's own example is the test |
| **I** | **The Player's `Queue` view** | the fourth named primary control, and a read-only rendering of the frozen Active Queue | nothing (§20.2) | JVM: a view built from `QueueState` cannot disagree with the session it came from; instrumented: one row is one focus stop, and the current row says so |
| **J** | **Content fidelity from the census** | captions read aloud; a child-database-dominated page no longer reads as empty; ordered-list numbering recoverable | nothing | JVM against the frozen census fixture |
| **K** | **Raw Notion navigation** (roadmap slice E) | `Browse Notion structure` → Database → Page → Note | nothing, but it needs API surface `NotionApi` lacks: a database listing (`POST /v1/search` filtered to `object = database`, where `searchBody` currently pins `page`) and a database's pages | JVM: a database's pages are listed in a deterministic order; instrumented: the standard Note screen opens unchanged |
| **L** | **Browse's sort control** | `Sort: Title` beside `Recently updated`, over the same result set, so `Play all` order is visible | the date orders wait on the metadata spec; `Relevance` on the Browse/Search spec | JVM: the ordering is already a pure function of the query, which is the shape `BrowseQuery`'s tests take |
| **M** | **Saved and Smart Queues, Queue Destination, `Add to queue`, `Add all to queue`** (roadmap slice F) | four secondary screens, two tabs, a Note's second primary action, Browse's second result-set action | the Queue Creation & Queue Management spec, which does not exist — **M1 is writing it** | a session-store round trip for a Saved Queue; the destination's dedupe-by-identity rule; edit-mode reordering without drag |
| **N** | **The Compose v2 test rule** | the harness stops losing the activity rather than classifying the loss | nothing | the same 47 tests, with the retry classifier removed and their assertions unchanged |
| **O** | **Background playback** | playback with the screen off, lock-screen controls, headset buttons | the Background Playback, Media Controls & Hands-Free Commands spec | on device, with the screen off — the one slice whose verification cannot be JVM |

The order:

| Priority | Slice | Why here |
|---|---|---|
| P0 | **G, then H** | rank 1 and rank 3, unblocked, and one round's work: H cannot be verified without G, and most of G's value is that it makes H's verdict mean something. It is also the honest response to four rounds of "verified by dump" |
| P1 | **M1 — write the Queue Creation & Queue Management spec** | rank 2 (the code was shaped for it) and the largest unlock on the list. §16.3's "settle persistence once" argument now points here: a session store exists, so Saved Queues must reuse it rather than invent a second |
| P2 | **M2 — slice F** | implement what M1 settles |
| P3 | **I, J and L** | three small unblocked slices, and the right work while a document is being written. I leads because it completes a control set the contract names |
| P4 | **K** | unblocked, and the last roadmap slice, but the contract's own lowest-value destination: "secondary escape hatch" |
| P5 | **The metadata spec, then Browse's facets and Home's Explore** | unlocks three surfaces at once, but it is the largest and most uncertain document here, and §5 already recorded that this workspace cannot supply part of what it would have to say |
| — | **N** | removes ambiguity, but it is a harness change with 47 tests riding on it, so it belongs in a round with no other risk |
| — | **O** | the largest product gap — a listening app that stops when the screen sleeps — and last by unblocking order. §16.4's disagreement, restated rather than resolved |

### 20.5 Decisions the next round needs from the product owner

1. **The P0 choice.** G + H by the §16.1 ordering, because that pair is the only one that removes
   ambiguity; M1 leads instead if the priority is visible surface. This plan ranks ambiguity first and says
   so, so that the choice is visible rather than emergent — the same courtesy §16.4 pays background
   playback.
2. **Background playback's rank.** §16.4 recorded that ordering by unblocking puts the largest functional
   gap in a listening app last. Nothing here changes that, and repeating it is not agreement. A decision
   to rank by product value moves O to P1 and makes *its* specification the next document written instead
   of the queue spec. This is the highest-leverage decision in the plan, and it is a product call rather
   than an engineering one.
3. **Slice L's scope.** Whether to ship `Title` alone and be explicit that it is one of five named orders,
   or to hold sorting entirely until the date orders are real. One selectable order is a smaller lie than
   a control offering three over data that does not exist — which is §15.3's reason for not adding the
   enum member early in the first place.
4. **Slice J's ordering within itself.** Captions are the largest *measured* loss; a child-database page is
   the most *misleading* failure, because it reads as empty while looking full. Which of those is a defect
   to fix first and which is an enhancement is a product call.

### 20.6 What this plan does not propose

- **No new destination.** The contract's rule is that "a downstream specification should justify creating
  another major destination rather than overloading one of these responsibilities", and every screen in
  §20.1 is one of the fourteen the contract already names.
- **No change to what is read aloud in G, H, I, K, L, M or N.** J changes exactly that, which is why it is
  separated rather than folded into the queue work.
- **No resolution of §19.9's dead end** — a Note that will never load stops a queue with Retry as the only
  way past. That belongs to M, because "skip this Note" is a queue-management rule, and inventing it here
  would repeat the thing §14.3 avoided by naming it a product decision.
- **No Settings screen.** Adding one still needs a specification that requires a setting, which is the
  condition §12.5's deferral was written with.

---

## 21. Twelfth round — the shell gets tests (G), and Back restores focus (H)

**Date:** 2026-09-18. **Scope:** §20.4's P0 pair, implemented end to end. G is the shell's instrumented
checks — called slice A′ for four rounds — and H is the UI contract's "returning with Back restores the
previous meaningful focus target". No specification was needed for either, and none was changed.

The two are one round because H is not verifiable without G: the assertion H needs *is* "focus is on result
7", and until the shell was renderable by a test there was nowhere to write it. That is also why the
enabling refactor (§21.1) came with the tests rather than before them.

### 21.1 The shell was the one surface with no test, and the reason was where it lived

`MainActivity.onCreate` built the surface inline: the `when (current)` over destinations, the mini-player,
the tab bar and the `SaveableStateProvider` all sat inside `setContent`. That made the shell the **only
shipped surface in the application with no instrumentation of any kind** — Note, Player and Queues each had
their own semantics suite — and the cost was recorded in three consecutive rounds:

| Round | How the shell was verified | What was admitted |
|---|---|---|
| §15 | `tools/probe-player-back.ps1`, read by a human | the shell's own checks were "not written" |
| §18.9 | the same | "Home is still the only shipped surface with no instrumented test of its own" |
| §19.9 | the same, plus reasoning | Browse's result-position fix is "verified by construction, not by a test" |

Nothing was wrong with those probes; the problem is that a scripted tap sequence produces a conclusion
rather than a verdict, and a conclusion has to be believed by whoever reads it. The fix is not more probes,
it is a surface a test can render — and the only thing standing in the way was that the shell needed a
`ReaderApp` and a coroutine scope, both of which `MainActivity` supplied from inside itself.

So the shell moved to **`ui/ReaderShell.kt`**, taking the application object and the five I/O-bearing
operations as parameters. `MainActivity` is now about sixty lines: build the retained root, collect the one
value that does not come from it, render. The five callbacks are the boundary that already existed
conceptually — the shell forwards intent, and the owner with the scope performs it — it just had not been
drawn there.

The class comment on `ReaderShell` states this, and the Activity's states what it *stopped* being, because
the placement of the shell was not an accident of style: it was the reason four rounds could not assert the
things the contract cares most about.

### 21.2 G — what the shell's checks are for

Nine checks in `ReaderShellAccessibilityTest`, plus two new screen suites. Each answers a question the
contract asks and that nothing could previously answer:

| Assertion | Contract requirement |
|---|---|
| exactly three tabs, asserted **while the Player is on screen** | "The Player is not a fifth bottom-navigation destination" |
| exactly three tabs with the Note on screen | "The Note screen is not a top-level destination" |
| the mini-player is exactly **two** focus stops | "a logically grouped accessible region", with "Play/Pause ... an explicit accessible control" |
| the mini-player names the current Note and not the queue | "should not read the complete queue state every time focus reaches it" |
| the mini-player is absent on the Player | D25 and "the mini-player must remain deliberately compact" |
| the mini-player is absent with nothing played | D25: surfaced "once playback has begun" |
| nothing is focused on entry | "must not unpredictably land on: bottom navigation; mini-player" |
| Back from Browse lands on Home; from a Note on its tab; from the Player on the Note | the Back table |
| Browse keeps its result position across a trip to a Note | "Browse state survives temporary navigation" |

The Player-not-a-tab check is made with the Player *on screen* on purpose. A count taken on Home would pass
whether or not the Player had been added as a tab, because a fourth tab is only visible in the state where
it exists.

`HomeScreenAccessibilityTest` and `BrowseScreenAccessibilityTest` close the other half of the gap: Home had
no instrumented test at all, and Browse's screen had none either — its *behaviour* was covered by
`BrowseModelTest` and `BrowseQueryTest`, which cannot see an announcement. Both assert the semantics tree
rather than the composables, because that is what TalkBack consumes, and the reading-order helper reads
order out of the tree for the same reason.

Two Browse checks are worth naming because they encode a defect rather than a shape: the error message is
asserted to be **the live region itself** and to **not contain "Retry"**, which is the failure this project
already found once (a live region on a parent that does not merge its descendants creates its own node, so
TalkBack watches an empty container and the announcement never fires), and the Retry control is asserted to
be absent entirely for a category whose `canRetry` is false.

### 21.3 H — how focus gets back

The mechanism is three small pieces, and the placement of each is the decision:

**The identity is recorded when the screen is pushed**, because that is the only moment the fact exists.
`ReaderNavigator.open(destination, fromFocusId)` stores a `ReaderStop`, and `back()` surfaces the popped
stop's id as `ReaderNavigation.focusTarget`. Modelling the stop as a pair rather than as a bare destination
is what makes `Browse → Note → Play → Player → Back → Back` restore *Play*, then *result 7* — one screen at
a time, each from its own record. A single field on the navigator could not.

**The id is a plain string, and the navigation layer does not know what it means.** It cannot hold a
`FocusRequester`, and it must not know which screen owns the id. A Browse row's id is its page id and a
history entry's is its session id — both of which already exist and are already the row's identity
everywhere else — so a parallel naming scheme would have been a second source of truth for the same fact.
For the two controls with no content identity (`Note`'s Play, Browse's `Play all`) `ReaderFocus` holds
namespaced constants, and the reason they live in `application` rather than beside the buttons is the
architecture rule: a screen may not define the id the navigation layer records, because `application`
cannot depend on `ui`.

**The item claims the hint, not the screen.** `restoreFocusOn` is a `Modifier` extension, so each row asks
"am I the one?" and requests focus if it is. This is not a stylistic choice; it is the shape Compose
supports. A screen cannot search the composed tree for a target, and an item that is not composed cannot be
focused at all. That degrades correctly rather than surprisingly: the listener activated a row they could
see, the destination's scroll position is preserved (§19.6), so the row is composed by the time Back
returns.

The hint is **one-shot**, and consumed even when it could not be honoured. `focusHandled()` clears it, and a
screen that is handed an id it does not recognise clears it too — a hint left pending would fire on some
later, unrelated visit, which is exactly the unpredictable focus movement the contract's rule exists to
prevent. The trade is stated rather than hidden: a target whose row has genuinely been disposed is dropped
instead of being honoured later.

The other half of the fix is that navigating *forward* clears a stale hint, and so does selecting a tab.
Without that, a hint recorded for another tab's list could survive and fire in a list it never described.

### 21.4 The mini-player became a traversal group, for a second reason

The contract asks for a "logically grouped accessible region", and `Modifier.semantics { isTraversalGroup =
true }` is Compose's spelling of that: traversal does not leave the region until it has visited everything
in it. That is a correctness improvement on its own.

It also made the count assertable, which is the check §12.4 named four rounds ago: "the mini-player is two
focus targets rather than one or six". A count taken over the whole screen would have to be revised whenever
anything else on the screen changed; scoped to the region, it is a statement about the mini-player.

`isTraversalGroup` is **not sufficient as a region identifier**, and the test says so: a Material3
`NavigationBar` is also a traversal group, so counting by it alone would count the tabs. The matcher
combines it with the one thing only the mini-player contains — the session title — which is also the right
scope semantically, since the point of grouping the region is that it is about one session.

### 21.5 What these tests cannot see

**Focus asserted here is Compose's input focus.** `assertIsFocused` reads `SemanticsProperties.Focused`,
which is the node the focus system moved to. Whether TalkBack's *speech* focus follows a programmatic
request, and what it then says, is not in the semantics tree and is not something any test in this project
can assert.

That is the same gap the integration spec's oldest open item names — the complete flow performed with
TalkBack enabled, *heard* rather than asserted — and this round narrows what is left of it without closing
it. H answers "does the app put focus back where the listener left it"; it does not answer "does the
listener hear that". §21.7 keeps the gate open rather than implying G and H retired it.

### 21.6 Verified

- **314 JVM tests, 0 failures** (was 303). The additions are 7 in `ReaderNavigatorTest` and 4 in
  `ReaderAppTest`, and the focus guard has a **failing-first proof**: making `back()` drop the id it had
  just popped failed exactly 8 tests — the 4 `ReaderAppTest` route tests and 4 of the 7 navigator tests —
  and no others, and restoring it made the suite green again.
  - The three navigator tests that passed either way are the ones asserting the *clearing* rather than the
    recording (`theFocusHintIsConsumedOnceSoItCannotFireLater`, `selectingATabDiscardsAStaleFocusHint`,
    `backWithNothingRecordedLeavesNoFocusHint`). That is recorded rather than glossed: they guard a
    different property from the one the failing-first check exercised.
- **The debug APK builds**, and `:app:compileDebugAndroidTestKotlin` was compiled explicitly — the JVM
  tasks and `assembleDebug` do not compile `androidTest`, which §18.6 records as how a broken instrumented
  source set was once committed while the suite reported green.

### 21.7 What this round did not do

- **The TalkBack listening gate is still open.** It is now the oldest item on the project by a wide margin,
  and §21.5 says precisely what G and H did and did not take off it: the app's own focus movement is now
  asserted, and whether a listener *hears* it is not.
- **A focus target that is no longer composed is dropped.** Stated in §21.3 rather than papered over. The
  alternative — keeping the hint pending until a matching row appears — would fire on an unrelated visit.
- **The mini-player does not take focus back** when the Player returns. The listener activated a *region*
  rather than an identified element, and it is on screen on every top-level destination, so a hint would
  have to name a region rather than an element and the listener is one swipe from it either way. Recorded as
  a decision rather than an omission.
- **Nothing in the shell's *behaviour* changed** beyond the focus work. G is instrumentation; the only
  production changes it needed were the extraction itself and the mini-player's traversal group.
- Everything §20.6 listed as deliberately not proposed, unchanged: slices I through O, the metadata and
  queue-management specifications, and §19.9's unreadable-Note dead end (which is M's to settle).

---

## 22. Thirteenth round — Note navigation, the Active Queue's view, and what "queue management" is allowed to mean

**Date:** 2026-09-18. **Scope:** the Player's `Next note` / `Previous note` controls, the Active Queue's
snapshot as a surface, and queue management. The first two were implemented; the third was **audited and
mostly refused**, and that refusal is the most important thing in this section.

### 22.1 What was found, and what it was found in

Work had started on this before the audit, and it did not compile: the queue view was written against
`QueueState.items`, which did not exist. That is the shallow finding. The others came from asking the
question this project's audits ask — *who owns this, and can two things disagree about it?*

| # | Finding | Class |
|---|---|---|
| F1 | `QueueState.items` referenced but never declared; the module did not build | defect |
| F2 | A superseding Note load was **invisible**, so the queue could wait for a request nobody made — forever | liveness |
| F3 | `Next note` while **paused** started speaking | defect |
| F4 | Selecting the queue row you are already on **rewound** it | defect |
| F5 | The queue view called every row before the current one "Completed" | false claim |
| F6 | The decorative `▶` marker was read aloud by TalkBack | a11y defect |
| F7 | The queue view relied on `BackHandler` registration order | fragility |
| F8 | Active Queue *editing* (remove / reorder) is **forbidden by the contract** | boundary |
| F9 | Saved and Smart Queue *management* needs a specification that does not exist | blocked |

### 22.2 F2 — the one that could hang the app, and why a boolean was the wrong question

`ReaderViewModel` fetched an awaited Note by collecting `queueState.map { it.awaiting }`, which is a
boolean. `awaiting` is `true` on **both sides** of a superseding transition — a transition that replaces an
in-flight request keeps the queue waiting, so the observable value does not change, `distinctUntilChanged`
swallows it, and nothing fetches the replacement. Meanwhile the arrival of the superseded document is
correctly refused by §19's identity guard.

```
Next note        queue = [beta, gamma], index 1, awaiting = true, fetch(beta) begins
Next note        queue = [beta, gamma], index 2, awaiting = true, fetch(gamma) ...
                 -> no emission: awaiting was already true
fetch(beta)      arrives, refused: the queue awaits gamma
                 -> the session waits forever for a request nobody made
```

The Player shows `Loading…` with every control disabled, permanently, and the only way out is to leave and
start again. It takes two presses on a page that loads slowly, and on this integration a page is several
HTTP requests — §4 measured one at 343 chunks.

**The lesson is the same one §19 taught and is worth restating because it recurred**: the session already
had the right abstraction — `PendingNoteRequest`, page id plus a load token — and the *loader* compared
requests while the *observer* compared a boolean. Two components, one fact, two questions. The fix is not a
new guard but a single definition of "what needs loading?":

```kotlin
val QueueState.pendingRequest: PendingNoteRequest?
```

with the token moved out of a private field and into `QueueState.requestToken`. Four callers — the session,
the loader, the observer, and the tests — now read the same answer, and a superseding request is
distinguishable precisely because the token travels with the state rather than beside it.

### 22.3 F3 — a navigation control that owned playback

`supplyDocument` called `engine.play()` whenever a document arrived, so pressing `Next note` while **paused**
loaded the following Note and started speaking it. That is the product contract's "navigation does not own
playback" in its plainest form: the listener had just said they wanted silence.

It also contradicted this project's own established behaviour. `ReaderEngineImpl.move()` re-speaks only when
the state says it is playing, so `Next section` while paused moves the position and stays silent — and the
README documents exactly that ("while paused they only move the position"). One rule for sections and
another for Notes was never a decision; it was an omission.

The fix records the intent at the transition (`playWhenSupplied`), captured **before** the pause so it is the
listener's intent rather than a consequence of the engine having been stopped, and `supplyDocument` honours
it: the Note loads, the Player shows it, and nothing is spoken.

**And that fix introduced the next bug, which a test caught before it shipped.** A silent load leaves "stay
silent" recorded, so the *next* transition — the automatic one at the end of the Note — would have inherited
it and loaded silently too. A listener who pressed `Next note` while paused and then `Play` would have had
hands-free reading stop dead at the end of that Note. `onCurrentNoteFinished` now states
`playWhenSupplied = true` explicitly, because automatic progression is by definition a continue-reading
transition. That is recorded here rather than only in a comment because it is the second-order failure of a
correct fix, which is the kind that ships.

### 22.4 F5 — a label the session could not support

The queue view marked every row before the current position "Completed". The session knows one fact —
**which Note is current** — and nothing about which Notes were read: a listener who presses `Next note`
twice, or taps a row near the end of the queue, moves *past* Notes that were never read. The rows now say
"Now playing", "Earlier in the queue", "Later in the queue".

A false "Completed" is worse than a vaguer true label, because a listener deciding whether to go back would
rely on it. The same discipline the project applies everywhere else it does not have a fact applies here.

### 22.5 F8 and F9 — the audit's answer on "queue management": no, and the contract says so

This is the part of the request that was **refused**, and the reasoning is the durable output of the round.

**The Active Queue cannot be edited, because three separate statements forbid it.** The product contract's
queue ownership model:

> Saved Queues are **edited**. Smart Queues are **configured**. Active Queues are **played**.

its playback invariants: "Active Queue membership and ordering are fixed snapshots for the lifetime of that
listening session" (§7) and "New Notion content must not silently appear inside an existing Active Queue"
(§8); and its snapshot contract, which lists six things that must not change a queue once it exists.

The contract does leave one door open, and it is worth quoting because it is the only way this feature could
ever be legitimate:

> If future Player-specific behavior permits modifying an active playback session, that behavior must be
> explicitly scoped to the Active Queue/Player and must not redefine the global Add to queue action.

So removing or reordering Notes in a playing queue is **not an unfinished feature. It is a product change**,
requiring the contract to be amended in Notion *first*, dependents reviewed, and only then implemented —
which is the conflict protocol the product contract sets out and §1 already recorded. Building it now would
be inventing the semantics, which is the thing every round of this project has refused to do.

What the queue surface therefore ships is what the contract does allow: the frozen snapshot, read-only, with
navigation within it — which is what `Queue` is *for*. Its doc comment says what it must not offer and why,
so the absence reads as a requirement rather than an omission.

**Saved and Smart Queue management is blocked on a document that does not exist.** Create, rename, reorder,
remove, delete, and the Queue Destination all live in the Queue Creation & Queue Management specification,
which is slice M1 in §20.4. The UI contract defers the persistence schema to it; the integration spec
explicitly delegates Saved Queue and Smart Queue persistence away from itself. There is nothing here to
"complete" without writing that document first, and writing it as a side effect of implementing the screen it
belongs to would invert the order the project depends on: the specification is what decides, and the screen
follows.

### 22.6 The one deliberate enablement, and why it is not a contract breach

`Next note` and `Previous note` stay **enabled while the queue is stalled** on a Note that will not load.

§19.9 recorded that a failed Note in a queue is "still a dead end, with Retry as the only option and no way
past", and attributed it to a queue-management specification. That attribution was right in the sense that
the *policy* belongs there — but the dead end is also reachable today, and the fix available today does not
need a new policy. The spec's rule is that "**it is never skipped**", about the app advancing on its own;
this is a listener pressing a control that says what it will do, on a queue whose membership has not
changed. Without it, one missing page costs the listener every Note after it, with no way to continue — which
is a worse outcome than the one the rule protects against, and is not what the rule is about.

The distinction is stated in the code at the `enabled` expression, and §19.9's item is narrowed rather than
closed: what remains for M is whether a stalled Note should offer anything beyond Retry and move-on.

### 22.7 F7 — Back, and a note on how the fix was verified

The queue view is a view of the Player, not a destination: the contract's ten secondary screens name
`Queue Destination`, which is the Saved Queue chooser that `Add all to queue` routes through, and is a
different thing. So Back closes the view rather than reversing navigation, and it is the one Back rule the
contract states as a prohibition — "Back must never implicitly mean: Pause, Stop, Previous section, Previous
note" — which holds structurally because the view is given no playback callback at all.

The implementation relies on the inner `BackHandler` beating the shell's, which is the framework's
registration-order rule. It works, and it is the fragile kind of thing that stops working silently, so it is
asserted by a test that dispatches a real Back press through `onBackPressedDispatcher` — which needs the
Activity, hence `createAndroidComposeRule` in its own small class rather than switching the whole shell suite
over to it.

### 22.8 Verified

- **327 JVM tests, 0 failures** (was 314). Thirteen additions, all in `ReaderSessionQueueTest`, and three of
  them have **failing-first proofs**:

  | Guard removed | Tests that noticed |
  |---|---|
  | the request identity in `QueueState.pendingRequest` (token forced to a constant) | `aMoveThatReplacesAnInFlightFetchIsVisibleToAnObserver` |
  | jumping to the current Note is a no-op | `jumpingToTheCurrentNoteIsNotATransition` |
  | the advance path states its playback intent | `aSilentlyLoadedNoteStillAdvancesLoudlyWhenItFinishes` |
  | the awaited Note honours the recorded playback intent | `movingWhilePausedLoadsTheNoteWithoutStartingIt` **and** `aSilentlyLoadedNoteStillAdvancesLoudlyWhenItFinishes` |

  Each was verified by removing exactly that guard, observing the named tests and no others, and restoring
  it. The fourth case failing *two* tests is the right answer and is recorded rather than smoothed: one
  asserts the silent load directly, the other needs a silent load to exist before it can observe that the
  following transition is loud.

- **The debug APK builds**, and both new instrumented suites compile — checked with
  `:app:compileDebugAndroidTestKotlin` explicitly, because the JVM tasks and `assembleDebug` do not compile
  the instrumented source set (§18.6).
- **On device, 103 instrumented tests, 8 failures on the first run.** All eight were informative: four were
  missing test-side imports or a second `setContent` in one test (Writing a test is not exempt from the
  project's own standards, and the suite caught it), and **four were a real defect in this round's focus
  restoration** — see §22.9.

### 22.9 The device found what reasoning had missed, twice in one round

**Focus restoration did not work at all.** All four of §21's new focus assertions — the ones this round
inherited and re-ran — failed on the device with focus still nowhere. The cause was not the mechanism but
its timing: `requestFocus()` on a target that is composed but **not attached and placed** does nothing and
reports nothing. And there is no single frame that is guaranteed to be late enough, because `LazyColumn`
places its items during layout.

It took three attempts, and the sequence is the part worth keeping, because the first two were both
plausible and both wrong.

| Attempt | Change | Outcome |
|---|---|---|
| A | request focus immediately | 4 failures — focus nowhere |
| B | wait **one frame**, then request | 4 failures — focus nowhere |
| C | retry across up to **eight** frames, stopping on grant | 4 failures — focus nowhere |
| D | C, **plus** the test advancing the clock | passing |

C is still correct and is kept: `requestFocus()` on a target that is composed but not attached and placed
does nothing and reports nothing, and there is no single frame guaranteed to be late enough because a
`LazyColumn` places its items during layout.

**What D adds is the realisation that the test was not running the mechanism.** `ComposeTestRule` installs a
**test frame clock**, so `withFrameNanos` callbacks only run when the test advances the clock, and
`waitForIdle()` settles recomposition without guaranteeing that a pending frame callback has been resumed.
The effect was waiting for a frame that never came, `onHandled()` never ran, and the assertion was asking
about a request that had not been made yet. The tests now use `waitUntil`, which advances the clock while
polling, and the note in `ReaderShellAccessibilityTest` says why a "wait for it" is a fix here and not a
weakened assertion: the timeout still elapses and the assertion still reports what it found.

The decisive evidence throughout was the node dump from the failing runs, and it is worth keeping because it
is what distinguishes "arrived too early" from "cannot work":

```
Node #124 at (l=54.0, t=766.0, r=954.0, b=915.0)px
Focused = 'false'
Text = '[Result 3, September 1]'
Actions = [..., OnClick, RequestFocus, ...]
```

Correct node, correct bounds, focus action available, focus not held. Three separate runs produced that same
picture while the mechanism was already correct — which is the signature of a *test* that cannot see the
mechanism, and is why attempt C's failure was not evidence against the production code.

This is the clearest example so far of the thing §21.1 argued: the shell had no instrumentation for four
rounds, and the first time it got some it found a defect in code that had been *"verified by construction"*
one round earlier. §21.5 had also claimed the limit of this approach was only TalkBack's *speech* focus —
that input focus was assertable. The mechanism was assertable; what was not yet true was that the assertion
was running it.

**A test that asserts about a node which was never drawn passes for the wrong reason.** Browse's
result-count check passed an empty query in the state while passing the query *text* separately, so the
summary it asserted about was never rendered — and "this is not a live region" is trivially true of nothing.
The fix is in the fixture, and the lesson is §15's, restated: an assertion about absence has to prove its
subject exists first.

**And a correction to §17.2, which claimed more than it could.** That section replaced six
`assertIsDisplayed` calls because they were claims about the device's window state, and described the four
checks that replaced them as "deterministic" and app-side. Three are. **"Occupies area" is not**, and
attempt B's run produced it twice:

```
'Play' is laid out but occupies no area, so nothing can be tapped: bounds=Rect.fromLTRB(0.0, 0.0, 0.0, 0.0)
'Continue' is laid out but occupies no area, so nothing can be tapped: bounds=Rect.fromLTRB(0.0, 0.0, 0.0, 0.0)
```

A node inside a root the device has not measured yet reports zero in every direction, and that is a fact
about measurement rather than about the control — on a `Button` that cannot legitimately have no size. It
did not reproduce in attempt C's run, which is what a measurement race looks like.

It is **classified rather than fixed**, and the reasoning is a deliberate trade: the assertion is the
requirement worth keeping — a control a listener cannot touch is not a control — so the check stays and the
runner learns the signature. A retry cannot launder a real one, because a control that is truly not laid out
reports zero on the second attempt as well and the runner reports the retry's outcome.

One more thing that run demonstrated is worth recording because it is the runner behaving exactly as
designed: it **refused to retry**, because its failures were mixed — two harness signatures and four real
assertions. A retry that fired there would have buried four genuine failures inside a "flaky" verdict, which
is the thing §17.3 built the classifier to prevent.




---

## 23. Fourteenth round — the Player and the queues as a work list, and an order that turned over

**Date:** 2026-09-18. **Scope:** read the Product & Architecture Contract, the UI Screens & Navigation
Contract and the Active Queue & Reader Session spec as a work list for *Player and queue features*,
enumerate what exists and what does not, and plan the implementation. **No product code was written.**
Five decisions were put to the product owner and all five were answered; two of them overturn the order
§16 and §20 had settled, so this section is a plan and a reversal rather than a plan alone.

### 23.1 The census

**Player.** Everything the UI contract names as primary exists: title, current section, queue position,
progress, Playing/Paused; `Previous section / Play-Pause / Next section / Queue`; the read-only Active
Queue view (§22); Back that does not touch playback (§21); the mini-player; the between-Notes transition
and the announced end of queue. Three things do not exist:

| Requirement | Source | Class |
|---|---|---|
| "Core Player commands must be representable independently of individual UI widgets so Android media controls, accessibility actions, hardware controls, and future voice control can invoke the same behavior" | Product contract, accessibility contract | **absent** — there is no `PlayerCommand`, no `customActions`, and no `MediaSession` anywhere in the tree |
| Player secondary controls — fine-grained chunk navigation, restart behaviour | UI contract explicitly defers these to the Core Player & TTS Playback specification, which does not exist | blocked on a document |
| Playback with the screen off, lock-screen controls, headset buttons | Product contract ("must eventually support"); UI contract | blocked on its own specification |

**Queues.** The Active Queue, the Listening tab, session persistence, retention and the four resume
paths all ship. Everything else the contract names for queues does not: the Saved tab, Saved Queue
Details, Create Saved Queue, Edit Saved Queue, Queue Destination, the Note's `Add to queue`, Browse's
`Add all to queue`, dedupe by identity, and Home's *Your queues*. The Smart tab, Smart Queue Details and
Edit Smart Queue do not exist either.

### 23.2 The finding: queue work waits on two documents, and only one of them is really missing

§20.4 recorded the whole of Saved and Smart Queues as "blocked on the Queue Creation & Queue Management
spec, which does not exist". Reading the three contracts against each other, that is true of the
**persistence schema** and of **Smart Queues**, and false of almost everything else:

- **Saved Queues are otherwise fully specified.** The Product contract settles the operations (create,
  name, add, add a result set, remove, reorder, rename, delete, play), the identity rule ("deduplicate by
  stable Note identity", never by title), that adding must not start or interrupt playback, and the stored
  fields ("a stable queue identity; a user-visible name; ordered Note identities; persistence metadata").
  The UI contract settles the screens and the interaction: Saved tab, Details, an **explicit Edit mode**
  with `Move up / Move down / Move to top / Move to bottom / Remove`, reordering that "must never require
drag-and-drop", Queue Destination showing Saved Queues and Create-new and explicitly **not** Smart,
  Active or a "Current Queue". Only the wire format is unowned — the UI contract defers "exact Saved
  Queue persistence schema", the integration spec delegates Saved and Smart persistence away from itself,
  and the session spec scopes both out twice.
- **Smart Queues are blocked twice.** They need the queue document *and* the Content Metadata & Workspace
  Schema Adapters document, because a Smart Queue is a stored filter set over Type / Area / Topics / Date /
  Project / Status — and §5 already measured that this workspace can supply no `relation` property at all
  and that Area is a per-database union rather than a vocabulary.

So the honest split is **Saved now, Smart later**, as a scoped deferral of exactly the kind §12.5 used for
the Settings destination and §18.4 used for the two absent tabs. That converts "blocked on a document"
from a wall into one small document plus real, shippable work.

### 23.3 The product owner's five decisions

| # | Decision | Consequence |
|---|---|---|
| **D28** | **Background playback is ranked first among this work, by product value rather than by unblocking order.** This supersedes the unresolved state §16.4 recorded and §20.5 restated: the principle that ranked it last ("blocked by nothing, blocks nothing, so it loses every critical-path comparison") is now explicitly overruled by the owner. | Slice O leads. §16.1's ordering principle is not repealed — it still orders everything else — but it no longer decides this item. |
| **D29** | **The specifications are written before the code they govern, and the next round is a document round.** | Three documents, in the dependency order §23.5 gives, before any of Stage 1. This is the project's existing rule ("the specification is what decides, and the screen follows", §22.5) applied to a plan that now spans three specifications at once. |
| **D30** | **Smart Queues are scoped out of the queue work; the Saved tab ships as two of three tabs.** | The Queues tab row goes from "1 of 3" to "2 of 3". Smart stays deferred on both the queue document and the metadata document, and the reason is recorded in the UI rather than only here. |
| **D31** | **Jetpack Navigation 3 is adopted for the shell**, replacing the hand-written destination model. This supersedes D26, which proposed Adopting Navigation Compose and was then weakened by §12.6 and deferred. | A shell-wide migration, its own slice, **before** the queue screens exist. §20.4's "spike before relying on it" is satisfied by the migration itself rather than a throwaway spike. |
| **D32** | **The Core Player & TTS Playback specification is written now, and Player secondary controls (restart, fine-grained chunk navigation) are exposed rather than left deliberately absent.** | Closes the gap §13.4 recorded for `ReaderEngine.playFromStart()`'s absent caller and §20.1's row for secondary controls. Also settles in a document the between-Notes transition behaviour §22.3 currently decides in code. |

### 23.4 The refactors, and the bug class each one removes

Every item below is proposed *because* it removes a failure this repository has already shipped once.
**R** here means refactor; the decision series is **D**.

| # | Refactor | Bug class it removes | Evidence in this repository |
|---|---|---|---|
| **R1** | **Destinations become routes that carry arguments, over a real back stack** (delivered by D31) | Screen state written at push time and read at pop time disagreeing about *which* item — a route with an argument cannot hold two answers | §19.3: `NoteViewer` wrote `pageId` at load start and `document` at finish, so a superseded load left the screen naming two different pages |
| **R2** | **One versioned local store, two collections.** Extract the schema-version, discard-on-unknown pattern `SessionCodec` + `DataStoreSessionStore` already implement, and let Saved Queues reuse it | Two codecs drifting on version policy, so a stored record is silently reinterpreted | §20.4's P1 reasoning: "a session store exists, so Saved Queues must reuse it rather than invent a second". `SessionWireFormatTest` exists because a same-build round trip stays green while stored records rot |
| **R3** | **`BrowseQuery` *is* the Smart Queue query**, and slice L (Browse's sort control) is done first to settle it | A stored query and a live query disagreeing, so "12 current matches" and what Play produces are different sets | §19 and §22 each found this once — "two components, one fact, two questions" (the loader vs the observer; `awaiting` vs `pendingRequest`) |
| **R4** | **`QueueOrigin.Saved(name)` / `Smart(name)` must carry an identity, with the name as a label** — a deliberate `SESSION_SCHEMA_VERSION` bump | Opening "the source queue" from a history entry landing on the wrong one, because two queues can share a name and a rename makes the stored origin a lie | Product contract: a Saved Queue stores "a stable queue identity"; origin is "descriptive … never re-evaluated", which makes a name safe for reconstruction and wrong for navigation |
| **R5** | **A `PlayerCommand` surface, exposed as `customActions`** | A control implemented twice with different enablement rules | §22.3: `Next note` started speaking while paused while `Next section` correctly did not — one rule for sections, another for Notes, never decided |
| **R6** | **A `SavedQueue` write model and a separate read model**, with Play snapshotting through the existing `ActiveQueue.snapshotOf` | A list rendering a mutable collection, so a snapshot is a live view of the thing it was taken from | §15: `ActiveQueue` stored the caller's list, so a queue built from Browse results re-sorted itself when the results were re-sorted |
| **R7** | **`ReaderViewModel` stays a scope-holder.** Queue I/O goes into an `application` use case, and the ViewModel only launches and cancels | Business rules accumulating in the root package, which `LayerRulesTest` cannot police because the root is allowed to name every layer — so they are untested by construction | §19.5 already moved the three collectors and two loaders out of the Activity for this reason |
| **R8** | **Edit mode is state, not a boolean** — `SavedQueueDetailsState { Viewing \| Editing }` | A destructive control reachable in view mode, and "a boolean guards the wrong question" | §19: `if (state.awaiting) accept(result)` asks *a* result is wanted, not *this* one; §22.2: the same shape again in `QueueLoader` |
| **R9** | **Instrumented coverage ships with each new screen, not after it** | A second untested shell | §21.1: the shell had no test for four rounds and the first one it got found a defect in code "verified by construction" the round before |
| **R10** | **The session's owner must stop being the task.** Today `ReaderViewModel` (an `AndroidViewModel`) owns `ReaderApp` → `ReaderSession` → `ReaderEngineImpl` → `AndroidSpeechEngine`, so playback dies with the task. A `MediaSessionService` has its own lifecycle, so the session either moves into the service or becomes reachable from it | A session the Player, the mini-player and the service each drive a copy of — two views of one session, and a second playback model appearing behind the mini-player | §19.4's rule —"nothing that must outlive a screen lives in a screen" — extends to *nothing that must outlive the task lives in the task*. This is the direct consequence of D28 and the largest single unknown in the plan |

### 23.5 The order

The documents decide, so they come first, and they come in dependency order rather than priority order:

| Stage | Work | Blocked by | Verified by |
|---|---|---|---|
| **0a** | **Core Player & TTS Playback spec.** Owns the command vocabulary, secondary controls and their enablement and announcement rules, and the transition behaviour §22.3 currently decides in code | nothing | the mirror diff; D32 |
| **0b** | **Background Playback, Media Controls & Hands-Free Commands spec.** *Consumes* 0a's command set; adds `MediaSessionService`, audio focus, the foreground-service lifecycle, lock-screen metadata and headset buttons, and what a killed service does to a session | 0a — otherwise two documents define one command set, which the conflict protocol forbids | the mirror diff; D28 |
| **0c** | **Queue Creation & Queue Management spec.** Saved Queues, Queue Destination, both `Add` actions, dedupe by identity, Edit mode, origin identity, store shape. Smart Queues explicitly out of scope with the reason recorded | nothing | the mirror diff; D30 |
| **1** | **The Player command surface**, from 0a: `PlayerCommand` + `customActions`, replacing the lambdas threaded through `PlayerScreen` | 0a | JVM, plus the existing Player suite |
| **2** | **Background playback**, from 0b: R10, then `MediaSessionService`, screen-off playback, media buttons | 0b, 1 | **device only**, with the screen off — the one slice whose acceptance cannot be JVM |
| **3** | **The enabling refactors for queues:** D31 (Navigation 3, R1), L (sort, R3), R2, R4, R7, R8 | 0c, and D31's migration | JVM, plus the existing shell and focus suites re-pointed at the new destination model |
| **4** | **Saved Queues end to end** (M2a–M2e): model + store → Saved tab, Details, create/rename/delete → Edit mode with accessible reordering → Queue Destination + Note `Add to queue` + Browse `Add all to queue` → Home *Your queues* | 0c, 3 | JVM, instrumented, and new device probes |

Stage 1 is a prerequisite of Stage 2 rather than a competitor to it: the contract requires media controls to invoke *the same* behaviour the on-screen controls do, and building that boundary first is what keeps the unverifiable service work from growing a second one.

### 23.6 Verification

Unchanged from the project's own order, per the `verify-notion-reader` skill: JVM suite →
`:app:assembleDebug :app:compileDebugAndroidTestKotlin` (the JVM tasks and `assembleDebug` do **not**
compile `androidTest`) → `tools\run-accessibility-tests.ps1` → a device probe for anything
user-visible → a `docs/decisions.md` round and a README correction. Every regression guard gets a
failing-first proof recorded as a `Guard removed | Tests that noticed` table.

Two consequences specific to this plan:

- **Stage 2's acceptance is device-only**, and its probe must check the screen state as well as the UI.
  `android layout` never says which app it dumped, so every probe checks
  `adb shell dumpsys window | Select-String mCurrentFocus` first — with the screen off, the failure this
  guards against is reporting the lockscreen as the app.
- **Stage 3 changes every focus-restore test**, because D31 replaces the destination model those tests
  assert against. That is the cost of adopting Navigation 3 *before* the four new screens rather than after
  them, and it is accepted deliberately.

### 23.7 What this round refused, and what it did not settle

- **No Saved or Smart Queue semantics were invented.** The parts of Saved Queues that no document settles
  — the persistence schema — are 0c's to settle, and the parts Smart Queues need are explicitly out of
  scope by D30 rather than guessed.
- **No Active Queue editing.** §22.5's refusal stands and is not revisited: "Saved Queues are edited.
  Smart Queues are configured. Active Queues are played" is a product contract statement, and the
  exemption it leaves ("Player-specific behavior … explicitly scoped to the Active Queue/Player") has not
  been asked for.
- **§19.9's unreadable-Note dead end is narrowed but still open.** §22.6 gave the listener `Next note` as
  a way past; whether a stalled Note should offer anything more is a queue-management rule and belongs to
  0c.
- **Settings remains absent**, on the unchanged condition that no specification requires a setting.
- **The TalkBack listening gate remains the oldest open item**, and Stage 2 makes it harder rather than
  easier: a service that speaks while TalkBack is speaking is exactly the overlap §14.6 said needed ears.

### 23.8 Stage 0a is written — the Core Player & TTS Playback Specification

**Written 2026-09-18.** Notion page `3dfc9345-e10a-814e-b9c7-e5e8b4268669`, a row in the same Projects
data source (`ae046fd9-25cc-49c2-b926-7e6323af069c`) as the other specs, Status `Planning`. Mirror:
`docs/notion/core-player-and-tts-playback-spec.md` (451 lines), registered in
`tools/export-notion-doc.ps1`'s `$docs` map. No product code was written.

It settles what D32 asked it to: a **closed set of eleven transport commands**, each with a stated
precondition and effect; the distinction between a transport command and a surface action; a single
published availability value that every surface reads, so a disabled control and an unadvertised media
action cannot disagree; the speech contract (one utterance per part, Pause is a stop, a failed utterance
is reported and never skipped, a late completion is discarded); the announcement budget; the secondary
controls; and the progress line's unit.

#### Four findings, two of them defects in shipped behaviour

- **P1 — the end of a queue is not spoken.** It is reported only in a live region. With the screen off —
  the mode §23.5's Stage 2 exists to create, and the mode a listener actually listens in — nothing is
  announced, so the reader's silence is **indistinguishable from the reader having failed**. The reader's
  own TTS is the only channel that survives the screen going dark. Phase 2; **defect**.
- **P2 — the progress line's unit is a claim the state cannot support.** `Paragraph N of M` counts
  *parts*: the engine builds `ReaderState(paragraphs = chunkTexts)`, and a chunk is produced for headings,
  list items, to-dos, quotes and code as well as paragraphs, with long paragraphs split. The noun is
  therefore false for every such part before the listener's position — the same class as §22.4's queue row
  that reported "Completed" for Notes the listener had not heard. Phase 3; **defect**. The spec settles the
  line as `Part N of M`, and `Nothing to read` for an empty document.
- **P3 — at the end of a one-Note session the Player is inert and does not say why.** Every control
  except `Queue` is disabled and nothing explains it. The route back exists
  (`Queues → Listening → Recently completed`), so this is not §19.9's dead end. `Restart note` is
  deliberately **unavailable** once a queue has completed, because completion is a fact the session spec
  owns and a restart would produce a session that is completed and playing at once; whether a completed
  queue may be replayed from the Player is a queue-level action belonging to 0c. **Boundary**.
- **P4 — `Queue` is not a command.** The UI contract names it among the four primary Player controls and
  the product contract requires core commands to be invocable without a widget; resolving the two, the new
  spec classifies it as a *surface action*, because its effect is to change what the phone is displaying.
  Without that, 0b could put it on a lock screen. The same section settles that `Stop` does not exist (a
  transport's stop maps to `Pause`), that a headset's track-skip maps to `NextNote` rather than to a second
  command with the same effect, and that the secondary controls sit behind **one labelled entry control**
  rather than adding four buttons — the product contract's "the main interface must not become a control
  panel for every Player capability". **Clarification**.

Two further things the spec states rather than assumes, because a later reader would otherwise have to
guess. The Player's transport **`Play` is not the product contract's session-level `Play`** — that is what
"Play must not mean resume" is about — so a transport must never map its play control onto the session
verb. And the Active Queue & Reader Session spec's description of inexact-restore announcements as
**"live-region announcements"** is adopted here as "also exposed as a live region", not "a live region is
the mechanism", because otherwise P1's gap applies to resume as well. That reading is **raised as an
amendment candidate for that document and not applied to it**, which is the conflict protocol's order.

#### Verified

- `tools/export-notion-doc.ps1 -All` mirrors six documents, and `git diff -- docs/notion` shows **zero
  content drift in the five existing mirrors** — so this round changed no governing document except by
  adding one.
- The new mirror **round-tripped exactly**, and the check was a count rather than a glance: 37 numbered
  list items came back as `1. ` lines, which is precisely the 11 transport commands plus 2 surface actions
  plus 15 acceptance criteria plus 9 architectural invariants in the source, so nothing was silently
  dropped. The `plain text` fence survived, no line came back as the bare `. ` that the exporter's old
  `$indent1.` bug produced, and the page title was not duplicated as block 1.
- **The spec deliberately contains no tables.** The exporter has no `table` case, so a table would be
  dropped from the mirror without an error — which is why the other five mirrors contain none either.

#### What this section does not claim

- **P1 and P2 are documented, not fixed.** They are Phases 2 and 3 of the new spec, and both change
  user-visible behaviour, so the README's `Paragraph N of M` phrasing and its "Not yet implemented" list
  are corrected with them rather than now.
- **No other specification was amended.** The session spec's live-region sentence is an amendment
  candidate recorded above, to be applied through the Notion-first process when 0b is written.
- **0b and 0c remain unwritten**, and 0b must not be written before reading this one: it consumes the
  command set and may not add, rename or redefine a command.

### 23.9 Stage 0b is written — Background Playback, Media Controls & Hands-Free Commands

**Written 2026-09-18.** `docs/background-playback-and-media-controls-spec.md`, **hand-authored**: it has no
Notion page and no mirror. §23.8's closing note that 0b and 0c are both unwritten now holds only for 0c.

**D33 — the product owner's decision on where a specification is authored.** Documents may be authored
directly in `docs/` instead of Notion. The rule this creates is stated in `AGENTS.md` so the two kinds are
distinguishable by location: everything under `docs/notion/` is **generated** (regenerate, never
hand-edit, semantic changes go to Notion first) and everything directly in `docs/` is **hand-authored and
authoritative as it stands**. Two consequences are recorded rather than hidden. The exporter does not know
about a hand-authored document, so it can never be regenerated and `git` is its only history. And **a
hand-authored document cannot amend a mirrored one** — §23.8's amendment candidate, the session spec's
"live-region announcements" wording, therefore stays pending in Notion, and 0b records that it *depends*
on the adopted reading rather than pretending the wording is settled.

#### The finding that shaped the document: Android 17 is already enforcing this

The gap was known — the Player holds the display awake as "a stated workaround" — but the *platform
requirement* was not, and it is not inferable from the code. Read from
`kb://android/about/versions/17/changes/bg-audio` rather than recalled:

| Fact | Consequence |
|---|---|
| Background audio hardening applies to **all** apps on Android 17, "whether or not the app targets API level 37" | The device is API 37 and the app's `targetSdk` is 36, so **this is live today**, not a future migration |
| An app interacting with audio without a visible activity must run a foreground service that is not `SHORT_SERVICE` | A `mediaPlayback` FGS, with `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (required from API 34) |
| Targeting API 37 additionally requires **while-in-use** capability | Not enforced at `targetSdk 36`, but the design is still to start the service while the app is visible, because that is what earns WIU and it costs nothing |
| **Every failure is silent**: playback is "silenced, with no exception and no failure message from any API", focus returns `AUDIOFOCUS_REQUEST_FAILED`, volume calls are ignored | The spec's central verification is `adb shell cmd audio set-enable-hardening throw`, which converts silent failure into `IllegalStateException`, plus `dumpsys audio`'s `AudioHardening` entry (`level: full` = FGS without WIU, `level: partial` = no FGS) |
| Media3's `MediaSessionService` is the documented mitigation; apps using it are "not likely to be impacted" | Adopting Media3 rather than a hand-managed FGS, and `SimpleBasePlayer` — confirmed in `kb://android/media/media3/session/player` as "helpful when using a custom player that you want to connect to a `MediaSession`" — rather than implementing `Player` by hand |

This is the third appearance of one failure shape on this project, and the specification says so:
`§22.9`'s `assertIsDisplayed` was a claim about the window rather than the control, `§19` and `§22.2` were
two components answering one question from different state, and here is a platform **reporting success
while doing nothing**. The verification is therefore written to make the failure loud before it is
trusted to be absent.

#### R10 resolved: the session holder

The refactor §23.4 listed as the plan's largest unknown now has an answer, and it is deliberately *not*
"the session moves into the service". A session that lives in the service is unreachable to the UI when
the service is stopped, and the service stops on pause. Instead the session moves to a **process-scoped
session holder** — created once, owned by neither the Activity nor the ViewModel, reached by both — and the
service holds only the foreground claim and the `MediaSession`.

Two rules are stated as invariants rather than left to the implementer. **`ReaderViewModel.onCleared` must
not end playback**, because it is destroyed on every configuration change and on task dismissal; it keeps
its scope, its three collectors and its two loaders, which is what §19.4 gave it. And **the UI observes the
session directly rather than through `MediaController`**, because the Player renders facts the `Player`
interface does not model — section spans, the queue as identities and titles, the resume decision, the
announcement text — and routing them through Media3 would create a lossy second model of the session. One
session object, two front ends.

#### The lifecycle and focus decisions, and the two that go against convention

- **A dismissed task keeps playing.** Media3's own documentation template calls
  `pauseAllPlayersAndStopSelf()` from `onTaskRemoved`; the spec diverges deliberately and says why, because
  clearing recents is a habitual gesture and for a listening app it would silently stop the reading. The
  UI contract's "Back changes visual navigation, not playback" is the same rule at its largest scale.
- **No playback resumption, ever.** Media3 offers it; the session spec's "launch never begins playback"
  forbids it, and a book that starts reading itself aloud after a reboot is not a convenience.
- **Ducking is refused in favour of stopping**, on `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`. Attenuated speech
  is a murmur the listener cannot follow, and stopping plus resuming on regain is the honest reading.
- **Becoming noisy stops the reader silently**, and this is the one place the spec accepts silence. The
  alternative is speaking `Paused` through the phone's loudspeaker because the headphones were unplugged,
  which broadcasts the listener's reading to the room. Privacy outranks the announcement rule here, and the
  exception is bounded to that one cause.
- **`QUEUE` is explicitly excluded from the notification**, following 0a's P4 classification: its effect is
  to change what the phone is displaying, so it is not a transport command and must not appear on a lock
  screen.

#### The command mapping landed on the seat that was already shaped for it

`SimpleBasePlayer.setAvailableCommands(Commands…)` **is** 0a's single published availability value, in the
platform's vocabulary — so the same evaluation that disables a Player button also decides which commands
the media session advertises. That is not a translation layer, and it is the second time in two rounds that
an existing abstraction turned out to be exactly the seam a new feature needed.

One compromise is recorded rather than smoothed: the platform has one "seek forward" and one "seek back",
so **`NextPart` and `NextSection` share a platform command**. The on-screen Player keeps both distinct
controls; only transports are conflated, and the limitation is stated in the mapping table so a transport
author cannot mistake it for a decision.

#### Two items this round could not settle, and did not pretend to

- **The TalkBack interaction is a measurement, not a design.** TalkBack speaks through the same platform
  TTS service, so three overlaps exist and none is answerable from documentation — two TTS clients
  interrupting each other, TalkBack's own speech arriving at this app **as an audio focus change** (which
  under this spec's own rules would pause the reader every time the screen reader speaks), and the media
  notification being read aloud as a new spoken surface. The spec lists the measurement first in the device
  sequence, and leaves the resolution to the evidence. This **narrows §11's oldest open item rather than
  duplicating it**: that gate is now about two actively interfering speech sources instead of one passive
  one.
- **An instrumented assertion must be inverted, not deleted.** The suite asserts the Player holds the
  display awake while playing. Removing `keepScreenOn` is part of Phase 5 — it is what makes the feature's
  own acceptance observable, since the screen must be allowed to sleep — and the assertion guarded a real
  property, so its replacement guards the opposite one.

#### Verified

**Documentation only; no product code changed.** `git status` shows `AGENTS.md`, `docs/decisions.md`, the
new spec, `tools/export-notion-doc.ps1` (§23.8) and the §23.8 mirror — no Kotlin, so neither test suite is
implicated by this round. `AGENTS.md` now states the hand-authored-versus-generated rule and the exporter's
missing `table` case, which is the constraint that explains why the five mirrored specs contain no tables
and this one does.

### 23.10 What is specified versus what is implemented, and the README correction

**Date:** 2026-09-18. §23.8 and §23.9 are **specifications**, and it is worth stating plainly what that
does and does not mean, because the two are easy to conflate when a round produces documents rather than
code.

**No product code changed in §23.8 or §23.9.** Both findings they record — P1 and P2 — are defects in
shipped behaviour, and both are still present in the application. They are written as Phases 2 and 3 of the
Core Player & TTS Playback spec, and nothing in this repository has been changed to fix them yet.

| Item | Specified where | Implemented | Correcting document |
|---|---|---|---|
| **P1** — the end of a queue is not spoken | Core Player spec, Phase 2 | **no** | README's accessibility rules, with Phase 2 |
| **P2** — `Paragraph N of M` counts parts | Core Player spec, Phase 3 | **no** | README's accessibility rules, with Phase 3 |
| **P3** — an un-explained inert Player at the end | Core Player spec, decision | n/a (a boundary decision, not a gap) | — |
| **P4** — `Queue` is a surface action, not a command | Core Player spec, command set | n/a (classification) | — |
| **The command surface** (R5) | Core Player spec, Phase 1 | **no** | — |
| **Background playback** (R10 + the service) | Background Playback spec, Phases 1–5 | **no** | README's workaround note, with Phase 5 |
| **The session spec's live-region wording** | §23.8, as an amendment candidate | **no — blocked** | the Notion page itself; see below |

**The README is corrected only where it is wrong today, and deliberately not where it describes shipped
behaviour that is about to change.** That distinction is the reason this round's README edit is small:

- **Corrected**, because the claim was already false: the JVM count (303 → **327**) and the instrumented
  count (47 → **108**), both measured rather than copied; the "45 failures out of 47" figure that repeated
  the stale total; and the assertion that a background `MediaSessionService` is "still the correct long-term
  answer", which described an intention rather than a decision and now has a document to point at.
- **Not corrected**, because it still describes what the application does: `keepScreenOn` is still set while
  playing, and the reading position still says "Paragraph N of M". Changing those now would make the README
  describe behaviour that does not exist, which is a worse failure than being out of date — a reader would
  believe the progress line had been fixed. Each is corrected **with the phase that changes it**, and the
  README's accessibility rules now carry a parenthetical naming P2 and its phase so the discrepancy is
  recorded rather than discovered later.

**One item genuinely cannot be closed from this repository.** §23.8's amendment candidate — the Active
Queue & Reader Session spec's description of inexact-restore announcements as "live-region announcements" —
is in a **Notion mirror**, and D33's rule is that a hand-authored document cannot amend a mirrored one. The
Background Playback spec therefore **depends on the adopted reading** ("also exposed as a live region")
rather than treating the wording as settled, and the amendment remains pending until it is made in Notion
and re-exported. That is the conflict protocol working as designed, not an omission.

#### Verified

- **Documentation only again; no Kotlin.** `git status` lists `AGENTS.md`, `docs/decisions.md`, `README.md`,
  `tools/export-notion-doc.ps1` and the two new documents — no file under `app/src`, so no test suite is
  implicated and the architecture rules cannot have been affected.
- Every stale number was checked **gone** by search rather than by reading the diff: zero occurrences of
  `303 JVM`, `47 tests` and `45 failures`, and the corrected `327` and `108` each present once.
- The instrumented figure is the count of **`@Test` methods** in `app/src/androidTest` (108), which is not
  the same as the number of tests a run *collects* — §22.8 recorded a run of 103. The README now states the
  source-set count and no longer attaches a number to the exit-code caveat, so it does not assert a run
  count this round did not measure.

### 23.11 The two Player defects are fixed — 0a's Phases 2 and 3, and the amendment applied

**Date:** 2026-09-18. The first product code of this round of work. Two things happened: §23.8's **P1** and
**P2** were implemented, and §23.10's blocked item — the session spec's live-region wording — was amended
in Notion and re-mirrored.

**Phases were done out of order, deliberately.** The Core Player spec numbers them Phase 1 (the command
surface), 2 (announcements) and 3 (the progress line), and Phases 2–3 were done first. They do not depend
on Phase 1: that phase is a boundary drawn around behaviour that already exists, and drawing it first would
have been churn in the same files for no verification benefit. Phase 1 remains the next step and nothing
here presumes its shape.

#### P1 — the end of a queue is now spoken

A new engine operation, `ReaderEngine.announce(text)`, speaks one utterance and reads nothing after it —
which `announceThenPlay` structurally cannot express, because its announcement is the *introduction* to
something. `ReaderSession.onCurrentNoteFinished` calls it when the last Note finishes, at the event rather
than from a surface: a surface that spoke it while rendering the completed state would re-speak it every
time the Player was reopened, and the requirement is once per completion. The guard that makes
`onCurrentNoteFinished` idempotent is the same guard that makes the announcement once-only.

Two judgments recorded because they were choices rather than facts:

- **The words are "End of queue"**, the same ones the Player's playback line shows, so the spoken and the
  displayed vocabulary are one vocabulary and a listener who can both hear and see is not told two things
  about one event. That also settles the single-Note case the spec names: a one-Note queue *ends*, so it
  gets one announcement rather than an "end of note" followed by an "end of queue".
- **`announce` is counted as speech, not as an engine invocation**, in the test double, so the existing
  `callCount` assertion — "navigation does not touch playback" — keeps meaning what it says.

#### P2 — the progress line names the unit it counts

`ReaderState.positionDescription` now reads `Part N of M`, and `Nothing to read` for an empty document. The
`ReaderState.paragraphs` field keeps its inherited name and is documented as historical: each entry is a
chunk, so the count was always a count of parts. Renaming the field would touch roughly forty sites across
the engine, the doubles and the tests, and the specification's Phase 3 asks for the user-visible wording,
not for an internal rename — stated here so the surviving inconsistency reads as a decision.

The README's **prose was fixed too**, which the phase did not explicitly ask for and which is worth
explaining: after the rule changed, the document said "the unit is the part — one speakable chunk" three
lines below a bullet that said "reads the current paragraph". Leaving that would have been the same defect
one level up, in the document that describes the behaviour. `paragraph` now appears in the README only as
the Notion block type it also is.

#### The Notion amendment, applied

`tools/amend-session-live-region.ps1` patches the session spec's sentence from "are live-region
announcements, added to the spoken sequence" to "are spoken by the reader's own TTS engine, and are also
exposed as live regions". It is dry-run by default, idempotent, and it **refuses** rather than guesses if
more than one block matches — because an amendment applied to the wrong block is indistinguishable from a
clean run afterwards.

Verified rather than assumed: `-Inspect` printed the block id, its type and its **full current text** before
anything was written, and the diff after re-exporting shows **one file, one sentence, plus the page's
last-edited timestamp** — no other mirror moved and no other block on the page changed. The dependency 0b
recorded is therefore closed, and a Notion-first amendment is now demonstrated to be a workable process
from this repository.

#### Verified

- **334 JVM tests, 0 failures** (was 327). Seven additions: 3 in `ReaderStateTest`, 3 in
  `ReaderSessionQueueTest`, 1 in `ReaderEngineTest`.
- **Failing-first proofs**, each verified by removing exactly its own guard and restoring it:

  | Guard removed | Tests that noticed |
  |---|---|
  | the `engine.announce(END_OF_QUEUE_ANNOUNCEMENT)` call | `theEndOfAQueueIsSpokenRatherThanOnlyShown`, `theEndOfAQueueIsSpokenOnceHoweverOftenTheCompletionSignalArrives` |
  | the `Part N of M` / `Nothing to read` wording | `thePositionLineCountsPartsAndSaysSo`, `thePositionLineNamesNoUnitForAnEmptyDocument` |

  Both runs failed **exactly** their named tests and nothing else, out of 334.
- **The third new P2 test passes either way, and that is recorded rather than smoothed**:
  `thePositionLineAgreesWithTheNumberOfThingsItCounts` asserts the count against the corpus, not the noun,
  so reverting the wording does not move it. It guards numeric coherence and the other two guard the unit —
  the same shape as §21.6, where three navigator tests guarded clearing rather than recording.
- `:app:assembleDebug :app:compileDebugAndroidTestKotlin` both succeed. The instrumented source set was
  compiled explicitly, because the JVM task does not compile it.

#### One test was written wrong, and the suite said so

`theEndOfAQueueIsSpokenRatherThanOnlyShown` first asserted `assertFalse(session.isPlaying)`. It failed —
because the test drives `onCurrentNoteFinished` directly, so the engine is still in whatever state
`supplyDocument` left it, and `isPlaying` was never going to be false there. The production code was right
and the assertion was about the *fixture*. It now asserts the engine's call counts (`playCount`,
`pauseCount`) instead, which is the property actually meant: announcing must not start a reading pass and
must not stop one. This is §22.9's lesson in miniature — an assertion has to be about the thing it claims.

### 23.12 The focus assertions fail, and the device says why

**Date:** 2026-09-18. The full instrumented run: **108 tests, 7 failures — and every one is a focus-restore
assertion**, in the two classes that own that mechanism.

| Class | Result |
|---|---|
| `FocusRestoreTest` | 4 tests, **3 failed** |
| `ReaderShellAccessibilityTest` | 16 tests, **4 failed** |
| `PlayerQueueViewAccessibilityTest` | 17/17 |
| `NoteScreenAccessibilityTest` | 17/17 |
| `PlayerScreenAccessibilityTest` | 15/15 — including this round's two changed assertions |
| `QueuesScreenAccessibilityTest` | 15/15 |
| `BrowseScreenAccessibilityTest` | 13/13 |
| `HomeScreenAccessibilityTest` | 9/9 |
| `ReaderShellQueueBackTest` | 2/2 |

#### Not caused by this round, and that was shown rather than assumed

`git stash push -- app/src`, re-run `FocusRestoreTest` against the unmodified tree, identical three
failures at HEAD, `git stash pop`. `FocusRestoreTest` has no dependency on anything this round changed —
it composes its own `Text("Row b")` with its own `FocusRequester` — so the experiment was cheap and its
answer is unambiguous. **The failures pre-date this round**, and `PlayerScreenAccessibilityTest`'s 15/15 is
the device evidence that P2's change is visible in the semantics tree.

#### The failure messages refute the hypothesis the test was written to test

`FocusRestoreTest` was written in §22.9's aftermath to print the two values rather than an inference. It
printed:

```plain text
requestFocus() returned false and hasWindowFocus() returned true
```

Its own class comment predicted the cause: "the window holding the composition is not focused … an
accessibility overlay or autofill window holding input focus". **The window is focused.** And the class's
fourth test *passes*: `requestFocus()` on a `.focusable()` node is granted while on a `.clickable()` node it
is refused, and the semantics `RequestFocus` action on a clickable node leaves it unfocused. So the refusal
is about the node's **kind of focus**, not about the device's window state.

#### What the device is actually configured as, measured

```plain text
enabled_accessibility_services = com.x8bit.bitwarden/.Accessibility.AccessibilityService:com.google.android.marvin.talkback/.TalkBackService
accessibility_enabled         = 1
touch_exploration_enabled     = 1
default_input_method          = com.google.android.marvin.talkback/...BrailleIme
autofill_service              = com.x8bit.bitwarden/...AutofillService
```

TalkBack is running **with touch exploration on**, and its braille keyboard is the active input method.
Under touch exploration, accessibility focus belongs to TalkBack, and an input-focus request against a node
that is not focusable in the Android sense is declined. `Modifier.clickable` is exactly such a node.

#### Why this matters beyond the test suite

`FocusRestore.kt` documents its own usage as

```kotlin
Modifier
    .restoreFocusOn(target = focusTarget, id = entry.pageId, onHandled = onFocusHandled)
    .clickable(onClick = onClick)
```

so the mechanism §21.3 chose restores **input focus** on a **clickable** node. A TalkBack user navigates by
**accessibility focus**. §21.5 stated the limit of that mechanism as "whether TalkBack's *speech* focus
follows a programmatic focus request is not in the semantics tree" — it assumed the input-focus half was
assertable. **The device says the input-focus half is not granted at all while TalkBack is running with
touch exploration.** So the contract's "restore the previous meaningful focus target when it still exists"
may require accessibility focus rather than input focus. That is a question about the *mechanism*, not about
its wiring, and it is the first time §11's oldest open gate has produced a concrete measurement instead of a
restatement.

#### The suite's verdict depends on a configuration the wrapper does not pin

`tools/run-accessibility-tests.ps1` pins the keyguard and `screen_off_timeout`, because a device in the wrong
state produces *misleading* failures — 45 uniform "No compose hierarchies" failures from a locked phone, and
intermittent "occupies no area" from a sleeping display. The accessibility configuration produces the
identical class of misleading result and is neither pinned nor recorded. That this round could not tell
"focus restoration is broken" from "focus restoration cannot be asserted in this configuration" until it had
measured the configuration is the argument for recording it.

The wrapper **refused to retry**, and that is right: its signatures are the harness losing the activity and
the locked-device signature, and these are real assertion failures.

#### What this round did not verify

**P1's audibility is not verified on a device.** Reaching the end of a queue needs a full read — the fixture
Note is hundreds of parts — and no existing probe does that. What *is* verified is that the session invokes
`engine.announce(...)` (JVM, with a failing-first proof) and that the utterance travels the same path
`announceThenPlay` already uses on device. The gap is stated rather than implied by a green JVM run.

### 23.13 The accessibility mechanism is deferred, and the app stays inclusive

**Date:** 2026-09-18. **D34 — the product owner's decision.** The focus mechanism §23.12 measured is left as
it is for now: the application keeps its current focus behaviour, and a redesign in terms of accessibility
focus is deferred until it is needed. The instruction is *keep the app inclusive*, which is a constraint on
the deferral rather than a licence to reduce accessibility, and it is recorded as one:

- **Nothing is removed or weakened.** No control, name, label, live region or announcement is dropped, and
  the assertions that found the mechanism's limit all stay in the suite. The deferral is about *which focus
  mechanism* is asserted, not about whether the product is accessible.
- **The seven failures stay red rather than being deleted or relaxed.** This project treats deleting an
  assertion or widening a timeout in response to a defect as itself a defect (§22.9, and `verify-notion-reader`
  §1). A red suite that names a real contract requirement — "returning with Back restores the previous
  meaningful focus target" — is more useful than a green one that no longer asks.
- **The finding is not lost.** §23.12 has the measurement, the configuration, and the reason the mechanism
  may need to change. When accessibility work is designed, this is the first item and it is already diagnosed
  rather than needing rediscovery.

**What the deferral costs, stated plainly.** The instrumented suite cannot gate on a red result, so until this
is settled the suite's verdict is only meaningful for the classes that exclude focus. That is recorded in the
README rather than left for whoever next reads a failure list.

**What would close it, and it is not a mystery.** The mechanism should restore **accessibility focus** rather
than input focus, because a TalkBack user navigates by accessibility focus and the contract's requirement is
about what the *listener* is on rather than about which node holds input focus. The current modifier targets
`clickable` rows with `FocusRequester`, which is the input-focus mechanism. That is a designed change, not a
debugging session, which is exactly why deferring it is reasonable.

### 23.14 The speech channel is the app's own TTS, and no screen-reader-conditional behaviour is built

**Date:** 2026-09-18. **D35 — the product owner's decision.** The app speaks through its own text-to-speech
engine, and it does not implement behaviour aimed at TalkBack. §23.13's deferral is therefore settled in
direction rather than left open: the accessibility work that *was* contemplated — redesigning focus
restoration in terms of accessible focus — is **not** planned.

**This is not a departure from the contract; it is one half of it.** The Product & Architecture Contract
splits the two responsibilities explicitly:

```plain text
TalkBack  → interface controls, labels, focus, manual content exploration, UI state announcements
Reader TTS → continuous Note content playback
```

D35 says to build the second half well and not to extend into the first. It also agrees with the rule the
Core Player spec already states normatively — "a required announcement is spoken by the reader's TTS engine;
a live region may accompany it and may never be the only mechanism" — which is why §23.11's P1 fix (the
spoken end of a queue) turns out to have been the first implementation of this decision rather than an
unrelated bug fix.

#### What changes

| Document | Change |
|---|---|
| Background Playback spec | "The TalkBack interaction, which must be measured rather than reasoned about" becomes **"The reader's speech channel, and what it does not coordinate with"** — the measurement item is gone, and the rejected alternative is recorded |
| Background Playback spec | Device-validation item 2 was "Does the TalkBack overlap pause the reader? Enable TalkBack…"; it is now **"Does audio focus behave as the table says?"**, tested with a call or a second media app rather than with a screen reader |
| Background Playback spec | Acceptance criterion 15 was "The TalkBack interaction is measured and recorded"; it is now that audio focus is handled uniformly and **no behaviour anywhere is conditional on a screen reader** |
| Background Playback spec | The open question is replaced by the decision, and the **now-stale live-region question is closed** — §23.11 amended that sentence in Notion, so the spec's dependency is on the text rather than on a reading |
| `README.md` | The accessibility rules open with the speech-channel stance, and the focus note says the mechanism is not being redesigned around TalkBack |
| `AGENTS.md` | The instrumented-test section records why the seven focus assertions are red and forbids deleting, relaxing or `@Ignore`-ing them to get a green run |

**The Core Player & TTS Playback spec needs no amendment, and that is worth stating rather than leaving to be
re-checked.** It already requires that "an announcement that is required for the listener to understand what
happened must be spoken by the reader's own TTS engine", that a live region "must never be the only
mechanism", and that nothing moves a screen reader's focus. D35 is that rule applied to the whole
application. So this is the one decision in this round that costs no Notion round-trip — and it is a reminder
that the announcement rule was written the way it was for a reason, namely the screen-off listener it names
in its own rationale.

#### What deliberately does not change, which is the part that matters

The decision is about **what the application coordinates with**, not about how usable it is. Nothing is
removed:

- **Every semantics rule stands** — self-explanatory control names, pane titles, headings, disabled rather
  than inert, decorative glyphs cleared, loading/empty/error distinguishable, nothing force-focused. These
  exist so the interface is usable *with* a screen reader, which is a different requirement from
  coordinating with one, and they are UI contract requirements.
- **The seven focus assertions stay in the suite and stay red.** Deleting or relaxing an assertion because
  it fails is treated as a defect here (§22.9 and `verify-notion-reader` §1). A red suite that names a real
  contract requirement is worth more than a green one that stopped asking.
- **The live regions stay.** Playback state remains a polite live region; what D35 changes is that a
  required announcement must never depend on one alone, which P1 already fixed for the end of a queue.

#### Two honest consequences, recorded rather than smoothed

1. **Two speech sources can still overlap**, because the platform has one TTS service and this application
   owns no queue in it. A screen reader reading the interface while the reader reads a Note is audible, and
   the listener resolves it by pausing one of them. A coordination protocol invented here would be a second
   mechanism pretending to be a platform one, and unverifiable.
2. **A screen reader's speech may arrive as an audio focus change**, in which case the focus rules will pause
   or resume the reader exactly as they would for any other application. The alternative — ignore transient
   losses while a screen reader is enabled — is **rejected**, because it special-cases one other client out
   of a platform rule. §11's listening gate exists precisely because that interaction is better *heard* than
   encoded, and this decision leaves that gate exactly where it was rather than closing it by fiat.

**One cost is stated plainly:** the instrumented suite cannot gate while those seven assertions are red.
Before this round that looked like an unfinished piece of the focus work; under D35 it is a durable property
of running those assertions on a device whose screen reader intercepts focus, and it is recorded in the
README so the next reader of a failure list knows which of the two they are looking at.

#### Verified

**Documentation only. No Kotlin changed**, so the JVM suite and the instrumented suite are unaffected by this
section — confirmed by `git status`, which lists only `README.md`, `AGENTS.md`, `docs/decisions.md` and the
background-playback spec against the code already verified in §23.11 and §23.12. The 334 JVM tests were
re-run on the externally-edited tree before this section was written and were green, which is what
established that the edits made outside this session did not disturb the P1/P2 work.

### 23.15 Stage 1 — the Player command surface

**Date:** 2026-09-18. 0a's Phase 1: the command type, the availability value, and every Player control routed
through both. Behaviour-preserving — no control is added or removed, nothing visible changes, and the only
new capability is that the commands have names and there is one answer to "can I do this?".

The phase was done **before** the media session rather than with it, which is the point: the contract
requires a command to be invokable without the widget it is drawn on, and building that boundary first is
what stops the transport in Stage 2 from growing a second implementation of it. This is the same move §13
made for the session, and the same reason: make the seam exist, then put the second caller behind it.

#### What was added

- **`player/PlayerCommand.kt`** — the closed set: eleven transport commands plus two surface actions, with
  `isTransport` carrying the split the spec calls the point of the classification. `PlayerCommandAvailability`
  holds the booleans and [`allows`] is the single place a command is matched to one.
- **`playerCommandAvailability(state, queue)`** — a free function rather than a method on either state type,
  because the answer is a function of *both*: `ReaderState` describes the document and `QueueState` describes
  where the session is in its queue, and neither can derive the other.
- **`ReaderApp.invoke(command, index)`** — the single entry point every transport uses. The Player's own
  buttons are one caller among several rather than a special case.
- **`ReaderEngine.restartNote()`**, **`ReaderState.movedToStart()`**, and three `ReaderSession` passthroughs
  (`nextPart`, `previousPart`, `restartNote`), because `restartNote` was specified and did not exist.

#### Four decisions worth recording

**Availability does not include `queue != null`.** The first draft required a queue, mirroring
`ReaderSession.canPlay`. That would have disabled Play in every instrumented Player test, because those
render the screen with a default `QueueState` — the screen is tested in isolation, which is this project's
established method. The condition is also unnecessary: a null queue is already expressed by the queue-level
commands being unavailable, and the Player is unreachable without a session. So availability describes **the
state being rendered**, and the available set is exactly right for a screen rendered on its own.

**`restartNote` is not `playFromStart`.** The spec requires restart to preserve the playing state — speaking
from the top while playing, moving silently while paused — and `playFromStart` always begins reading. Rather
than rename it and lose an operation, both now exist with the difference stated where each is defined. That
leaves §13.4's observation that `playFromStart` has no production caller **still true**, and now with a
reason: the control it might have served wants the other behaviour.

**The Player's controls assert against commands in tests now.** Two instrumented tests counted callbacks
(`onPlayPause = { playPause++ }`); they assert the *commands* that fired instead. That is strictly better
instrumentation — it is what a media session or a headset button will send — and it is why the tests changed
rather than the design being arranged around them.

**`OpenQueue` is refused by the dispatcher even when available.** Its availability is the surface question
("is there a queue to show?"), and the dispatcher refuses it anyway because its effect is to change what the
phone is displaying. That refusal is what keeps it off a lock screen, and a test asserts the refusal happens
even in a state where the action is available.

#### Verified

- **360 JVM tests, 0 failures** (was 334). Twenty-six additions: 13 in `PlayerCommandTest` and 13 in
  `PlayerCommandInvocationTest`.
- **A failing-first proof for the dispatch guard.** Removing `if (!availability.allows(command)) return false`
  failed **exactly** three tests and no others, out of 360:

  | Guard removed | Tests that noticed |
  |---|---|
  | the availability re-check at dispatch | `anUnavailableCommandChangesNothingAndAnnouncesNothing`, `aPartCommandIsUnavailableAtTheEndOfTheNoteRatherThanLoadingTheNextOne`, `restartIsRefusedOnceTheQueueHasCompleted` |

  Three is the right answer and is recorded rather than smoothed: the *surface-action* refusal and the
  *jump-without-an-index* refusal are dispatch branches of their own rather than consequences of this guard,
  so they pass either way — the same shape §21.6 recorded for three navigator tests that guarded clearing
  rather than recording.
- `:app:assembleDebug` and `:app:compileDebugAndroidTestKotlin` both succeed. The instrumented source set was
  compiled explicitly, because the JVM task does not compile it — and it was where the change actually broke:
  two call sites still passed the removed parameters, which the JVM run could not have seen.
- **The instrumented suite on a device: 108 tests, 7 failures — and the identical seven §23.12 recorded.**
  Every Player suite is green with the command-based assertions:

  | Class | Result |
  |---|---|
  | `PlayerScreenAccessibilityTest` | **15/15** — including the two rewritten to assert commands |
  | `PlayerQueueViewAccessibilityTest` | **17/17** — including `JumpToNote` carrying its index |
  | `ReaderShellQueueBackTest`, `NoteScreenAccessibilityTest`, `QueuesScreenAccessibilityTest` | 2/2, 17/17, 15/15 |
  | `BrowseScreenAccessibilityTest`, `HomeScreenAccessibilityTest` | 13/13, 9/9 |
  | `FocusRestoreTest` | 1/4 — the pre-existing focus failures |
  | `ReaderShellAccessibilityTest` | 12/16 — the same pre-existing focus failures |

  So the count, the failing classes and the failing test names are **unchanged from before this round**, which
  is the right shape for a behaviour-preserving refactor: the suite that describes the app's behaviour did not
  move, and the two tests that had to change changed because they now assert something better.

#### One mistake, made and corrected in this round

A batch edit to `FakeReaderEngine` silently dropped the `setDocumentCount++` increment, because I replaced a
line I meant to insert beside. Nothing failed immediately: the JVM suite was not run between that edit and
the next. It was caught while adding the `restartNote` override and fixed before any verification run, so no
result in this round is affected — but it is the reason the double's counters are asserted by the suite at
all, and it is recorded because it is exactly the failure mode this project keeps finding in other shapes:
an edit that removes something without the tooling noticing.

### 23.16 Stage 2, Phase 1 — R10: the session stops being owned by the task

**Date:** 2026-09-18. The background specification's Phase 1, which is the refactor rather than the service:
the object graph's lifetime becomes the process's instead of the task's. Behaviour-preserving, and the only
thing it makes possible is that a dismissed task no longer ends the reading.

#### The finding: the session was not the only thing that had to outlive the task

R10 was written as "move the session to a process-scoped holder", and that would have been **half a fix**,
with a failure mode worse than the original because it would have looked like an integration bug.

The session's two feeders lived on `viewModelScope` too:

| Loop | What it does | What its death would look like |
|---|---|---|
| `advanceTheQueue` | observes `hasReachedEnd` and calls `onCurrentNoteFinished` | the session never asks for the next Note |
| `feedTheQueue` | observes `pendingRequest` and loads that Note | a requested Note is never fetched |

So moving only the session would have produced this: with the task dismissed, playback continues to the end
of the *current* Note, the engine reports the end, nothing advances the queue, and the reader stops in
silence. **On a single-Note queue it would not happen at all**, which is the case anyone would check first
— and a one-Note queue is how every `Play` from the Note screen is expressed.

That is why the new type owns two things and the second is the one worth naming: the [state] *and the scope
that feeds it*. `ReaderHost` is documented as such, including the table of what is the host's and what is
still the task's.

#### What was built

- **`ReaderHost`** (root package) — owns the `ReaderApp` and an injected application-lifetime scope, and runs
the four loops: advancing, fetching, recording history, recording resume quality. No Android type and no
dispatcher of its own, so it is JVM-testable; the scope and the logger are constructor parameters.
- **`ReaderApplication`** — the platform's only object whose lifetime is exactly the process's, and therefore
the place this belongs. It builds the speech engine, the DataStore and the Notion loader, and hands the host
their real implementations. Named in the manifest.
- **`ReaderViewModel`** — obtains the host instead of building the graph, keeps only the I/O a *screen's*
action starts (`openNote`, `retryNoteLoad`, `retryBrowseIndex`, `continueSession`, `replayCompleted`,
`loadBrowseIndex`), and **`onCleared` no longer shuts anything down**.

#### One side benefit worth recording, because it was a real gap

The resume match quality used to be recorded with a direct `Log.i` call inside the ViewModel, which is
unreachable from a JVM test — so the one fact the Active Queue & Reader Session spec explicitly requires to
be recorded was untestable. The logger is now injected, and two tests assert both halves of its rule: a lost
position is recorded, and an **exact** resume records nothing.

#### Deliberately unchanged

- **`continueSession` still loads the pending Note itself**, even though the host's fetcher also observes the
  request. That looks like the duplicate fetch §19.6 removed, and it is not: `QueueLoader` guards on the
  request rather than on "something is in flight", which is exactly what that round put there. Removing the
explicit call would have been a behaviour change in a phase whose whole point is that there is none.
- **Browse's index still loads per task.** It is a screen's data, so re-fetching it for a new task is
  correct rather than wasteful.

#### Verified

- **365 JVM tests, 0 failures** (was 360). Five additions, all in `ReaderHostTest`.
- **A failing-first proof.** The guard here is *which scope the loops run on*, so the proof substitutes the
task scope for the application scope and asserts the outcome changes:

  | Guard removed | Tests that noticed |
  |---|---|
  | the loops run on the host's scope rather than the task's | `theQueueIsStillFedAfterTheTaskScopeIsCancelled` |

  Exactly one test and no others, out of 365 — correct rather than thin: the other four `ReaderHostTest`
  cases assert what the host *owns* (the session's identity, its state, the recording), which survives a
  cancelled scope either way. Only the feeding is a property of the scope, and only that test asserts it.
- `:app:assembleDebug` and `:app:compileDebugAndroidTestKotlin` both succeed.

#### Two tests were written wrong first, and both are the same mistake

`advancingToTheNextNoteIsItselfADutyOfTheHost` drove the fake engine's position to the last part and expected
the host to advance. It does not, and should not: `hasReachedEnd` is set when the last part is **spoken
through**, not when the position merely reaches the end, so the test was asserting about a state it had
never produced — §22.9's lesson, one layer down. It now uses the real `ReaderEngineImpl` with the fake speech
engine and completes the utterances.

`anExactResumeRecordsNothing` guessed a chunk id, which resolved to something other than `CHUNK`, so it
silently exercised the *loss* path while claiming to assert the exact one. It now builds the position with
`positionFrom` the document itself. Both are recorded because "an assertion about a case that was never
produced" is this project's most repeated lesson, and these are two more instances of it.

#### What is verified on a device, and what is not

**Verified:** the app installs, launches with the new `ReaderApplication`, reaches `MainActivity`, and
produces no crash — which is the specific risk of a manifest `android:name` change and of moving the graph's
construction. Confirmed by `adb shell dumpsys window` showing this package focused and by an empty
`AndroidRuntime:E` log.

**Not verified, and stated rather than implied:** that **task dismissal** now leaves playback running. The
mechanism is that `onCleared` no longer shuts the session down, and the JVM test proves the *feeding*
survives a cancelled task scope — but no device probe dismisses the task and listens. `am force-stop` is
**not** the check: it kills the process, which legitimately ends the session and is a different case the
Active Queue & Reader Session spec already covers. Confirming this properly needs a way to remove the task
while keeping the process, which is worth building once Stage 2's Phase 2 gives playback a notification to
observe it from. Recorded so that no later reader mistakes the JVM proof for a device observation.

#### `assertIsReachable`'s second non-deterministic check, demonstrated

The instrumented suite came back at **108 tests and 8 failures** — the seven known focus ones, plus
`PlayerQueueViewAccessibilityTest > theQueueControlIsPresentAndNamed`, which had been 17/17 in the previous
run of the same class with the same production code.

```plain text
'Queue' lies outside the app surface at Rect.fromLTRB(363.0, 2082.0, 645.0, 2172.0)
(surface is Rect.fromLTRB(54.0, 18.0, 282.0, 90.0)), so a listener would have to scroll to reach it
```

A 228×72 "surface" on a 1008×2244 device is a window that has not been laid out yet, not a layout defect —
and the control's own bounds are exactly where they belong. The immediate re-run of that class passed
**17/17 with no code change**, which is what makes this a measurement race rather than a regression.

**This is the second of `assertIsReachable`'s four checks to be shown non-deterministic**, and which two they
are is the useful part of the finding:

| Check | Kind | Can an app change make it a false failure? |
|---|---|---|
| `isPlaced` | the app | no |
| **"occupies area"** | **measurement** | **yes** — §22.9 |
| `OnClick` present | the app | no |
| non-blank name | the app | no |
| **"overlaps the surface"** | **measurement** | **yes** — this round |

§22.9 said "three of them are [app-side and deterministic]" and named the exception as "occupies area". The
honest correction is that **two** are measurement claims and only three are app claims. That does not make
the helper the wrong choice — `assertIsDisplayed` was strictly worse, being about the device's window rather
than about layout at all — but the claim that the replacement is fully deterministic needs retiring.

**Handled the way §22.9 handled the first one: classified, not fixed.** The check stays, because a control
outside its surface *is* a control a listener cannot reach and dropping the check would let a real one pass;
the runner learns the signature instead, with the pattern and this reasoning in
`tools/run-accessibility-tests.ps1`. A retry still cannot launder a real failure: a control that is genuinely
outside the surface reports so on the second attempt too, and the runner reports the retry's outcome.

Note what the wrapper did on the first run, and that it was right: the failures were **mixed** — seven
classified focus failures plus one unclassified — so it refused to retry. A retry there would have needed
the unclassified failure to be treated as a harness signature, which is the thing the classifier exists to
prevent. The one-entry addition above is the deliberate classification that follows.

### 23.18 The command mapping is corrected, and Media3 cannot express four of the eleven commands

**Date:** 2026-09-18. Stage 2 Phase 2's first piece: the Player's command set expressed in Media3's
vocabulary, so that the availability value the Player's buttons read produces the command set a
`MediaSession` advertises. `PlayerMediaCommands.kt` plus ten JVM tests. **379 JVM tests, 0 failures.**

#### The finding: the specification's own mapping table was wrong, and only reading the library showed it

The Background Playback spec paired the four granularity commands with platform seeks — `NextSection` with
`COMMAND_SEEK_TO_NEXT`, `NextPart` with `COMMAND_SEEK_FORWARD`, and so on — as a stated compromise ("the
platform has one next and one previous and this product has two of each"). Reading `BasePlayer`'s bytecode
showed the compromise is not a compromise; it is two wrong answers:

| Command | What it would have compiled to | What it would have done |
|---|---|---|
| `NextPart` | `seekToOffset(getSeekForwardIncrement(), …)` | moved by a **millisecond offset the transport chooses** — our content has no ms timeline |
| `NextSection` | `seekToNext()` → timeline check → media-item move | **skipped a whole Note** while claiming to move one section |

Neither is a granularity approximation. The first is a distance someone else picked; the second is a
different operation with the same label. And the failure mode is the one this project distrusts most: the
lock screen's next button would look right and do the wrong thing, and a device test would have shown only
"the next button skipped a Note" with no indication that the mapping was at fault.

So the four **map to nothing**, and the spec's table is corrected rather than left as an aspiration. The
distinction that resolves it is worth keeping, because it is what stops the correction from looking like an
omission: **`isTransport` asks whether a command needs a screen; the mapping asks whether *this* transport
can express it.** `NextSection` is a real transport command — a voice transport could say "next section" —
and Media3 simply has no way to carry it. Advertising a command whose effect would differ from its label is
worse than not advertising it.

**What that costs, stated rather than glossed:** a listener using only the lock screen gets Note-level
navigation and play/pause. Section and part navigation are available on the Player's own controls, which
remain four separately-labelled controls. That is a real reduction in what a transport can do, and it is
better than a control that lies about what it does.

**This is what a specification-first process is supposed to catch, and it is worth recording that it did.**
The table was written in Phase 0b, before the library was read; the correction came from the implementation,
and it landed in the spec (which is hand-authored, so D33's rule lets it be edited directly) with the reason
in both places.

#### `RestartNote` became exact, which simplified it

The original table had restart expressed as a media-item change *plus* a seek, to stop a transport turning it
into a track skip. `COMMAND_SEEK_TO_DEFAULT_POSITION` exists and means precisely "start the current item
again", so the concern that produced the clumsy mapping does not apply. One command, no ambiguity.

#### The other finding: Media3's constants are usable in a JVM test and its containers are not

`Player.COMMAND_*` are compile-time ints, so reading them needs no Android and the whole command mapping runs
as an ordinary unit test. `Player.Commands.Builder` is not usable there at all: it uses
`android.util.SparseBooleanArray`, which the stubbed `android.jar` refuses, so the class fails to initialize
and every subsequent call reports `NoClassDefFoundError: Could not initialize class ...$Commands$Builder`.

That corrected a claim the mapping file had made in its own documentation — that Media3's types are pure JVM
types throughout. They are not. The response was to put the boundary in the right place rather than to stop
testing: `mediaCommandSetFor` is the pure set and is fully tested, and `mediaCommandsFor` is the mechanical
construction of the container from it, so there is nothing left in it to get wrong.

`testOptions.unitTests.isReturnDefaultValues = true` was **rejected**: it would make the builder silently
succeed while doing nothing, turning a loud failure into a quiet one. That is the failure shape this project
has spent several rounds learning to distrust, and it is exactly what §23.9's Android 17 investigation was
about.

Two further API assumptions were wrong and the compiler named both: there is no `PLAYER_COMMAND_PLAY` or
`PLAYER_COMMAND_PAUSE` in this version, and no `addAllPlayerCommands` — so the two-level command model the
mapping was first written against does not exist, and the constants were then read out of the AAR with
`javap` rather than recalled. **Adding the dependency before the code that used it is what made those three
mistakes cheap.**

#### Verified

- **379 JVM tests, 0 failures** (was 375). Ten additions in `PlayerMediaCommandsTest`.
- The tests cover the negative space as much as the mapping: that a surface action has no media command, that
  an unavailable command contributes no command *even though it has a mapping* (the filtering is what keeps
  it off a lock screen), that the four granularity commands have no mapping **and remain `isTransport`**, and
  that a platform seek this app does not offer is refused rather than given a plausible meaning.
- The mapping round-trips for every command that can survive the trip, and the test **names** the three that
  cannot rather than excluding them silently: `Play` and `Pause` both map to `COMMAND_PLAY_PAUSE` because
  that is the one action the platform has, and `JumpToNote` carries an index a bare command int cannot
  supply.
- **Four of my own assertions were wrong before they were right**, and three were the same species —
  assuming a shape rather than checking it: a one-to-one mapping when eleven commands collapse onto four
  platform commands; a default fixture at the first part of a document, which correctly disables the two
  previous commands; and an assumption that a one-Note queue has nothing to jump to, when the queue view
  legitimately has one row and selecting the current Note is defined as a no-op. The fourth was arithmetic.
  All four are recorded because "an assertion about a case that was never produced" is this project's most
  repeated lesson, and this round produced it three times in ten tests.

#### Not yet done

The `SimpleBasePlayer` subclass that exposes the session through Media3, and the `MediaSessionService` itself.
Neither can be verified on the JVM, and the seek-path finding above is the argument for reading each API from
the AAR before writing against it rather than from memory — the next two classes touch far more of the library
than the mapping did.

### 23.19 The media session exists, and one property catches a dead branch

**Date:** 2026-09-18. Stage 2 Phase 2's second half: `ReaderSessionPlayer` (a `SimpleBasePlayer` that renders
the session), `PlaybackService` (a `MediaSessionService`), the manifest declarations, and the pure projection
they both depend on. **389 JVM tests, 0 failures.**

#### How next/previous Note and the queue snapshot are implemented — one mechanism, not two

The question that shaped this phase turned out to have a satisfying answer: **Media3's playlist *is* the
queue**, so next/previous Note and the queue snapshot are projections of the same two facts the session
already publishes:

| Session state | Media3 state | What it drives |
|---|---|---|
| `QueueState.items` (`List<QueuedNote>`) | `setPlaylist(List<MediaItemData>)` | the queue snapshot a lock screen lists |
| `QueueState.index` | `setCurrentMediaItemIndex(index)` | where in the queue you are |
| `ReaderState.isPlaying` | `setPlayWhenReady(...)` | play vs pause |
| `QueueState.awaiting/completed/failure` | `setPlaybackState(...)` | buffering / ended / ready |

One direction only: nothing reads the playlist back into the session, and `ReaderSessionPlayer` holds no
playback state of its own — it derives everything from the session on every `getState()`, and
`ReaderHost.observeSession` calls `invalidateState()` when either flow changes. That is the specification's
"one session object, two front ends. Neither is a second source of state", with the second front end actually
existing for the first time.

`handleSeek` dispatches **on the command, not on the position or the index**, which is the correction from
§23.18 paying off structurally: `positionMs` is a distance the *transport* chose and is meaningless for our
content, and the index is only trustworthy for the item-level commands — which is exactly why the four
granularity commands were left unmapped. Anything unrecognised does nothing.

#### The finding: an advertised command the reverse mapping did not accept

`handleSeek` matches on the Player command and dispatches to `JumpToNote` with the index. But
`commandForMediaCommand` did not map `COMMAND_SEEK_TO_MEDIA_ITEM` back to anything — so **"jump to that queue
row" would have been advertised, pressable, and silently ignored.** It compiled, and **no existing test could
see it**, because every test checked one direction of the mapping and none checked that the two directions
agree.

That is a genuinely new failure shape for this project and it has a generalisable fix: the property
**advertised implies accepted**, asserted over every transport command. It is the same discipline the
codebase applies elsewhere — ask whether the two halves agree, rather than testing each half — and it was
found by writing the consumer rather than by reading the mapping.

**Failing-first proof**, which is the part worth recording:

| Guard removed | Tests that noticed |
|---|---|
| `COMMAND_SEEK_TO_MEDIA_ITEM -> JumpToNote` in the reverse mapping | `everyAdvertisedCommandIsAlsoAccepted`, `everyPlatformCommandCanSendMapsToARealPlayerCommand` |

Exactly those two, out of 389, and the new property is the one whose message names the defect.

#### `SimpleBasePlayer`'s defaults throw, and that is the library refusing on our behalf

Read from the bytecode rather than assumed: every handler this class does *not* override throws
`IllegalStateException("Missing implementation to handle COMMAND_X")`. Two consequences, both wanted:

- **`handleSetMediaItems`, `handleAddMediaItems`, `handleMoveMediaItems` and `handleRemoveMediaItems` are
  deliberately not overridden**, so a media controller that tries to add, remove or reorder a Note gets an
  exception rather than a quiet no-op. The Product & Architecture Contract's "Active Queues are played, not
  edited" is therefore enforced a **fourth** time — the enum's `isTransport`, `ReaderApp.invoke`'s refusal,
  the command mapping's `null`, and now the library's own default — and this last one required no code at all,
  which is the strongest form it could take. §22.5's refusal now has an enforcement point that cannot be
  edited away by accident.
- **Everything we advertise must be overridden.** `handleSetPlayWhenReady` (which `play()` and `pause()` both
  funnel into, read from the bytecode), `handleSeek`, `handleStop`, `handlePrepare` and `handleRelease` are all
  implemented, each with its reason at its definition.

`handleRelease` is the one worth naming: it cancels the subscription and **does not** call `host.shutdown()`.
The player is a rendering of the session and the media session comes and goes with the foreground service,
so releasing it must not end the reading — the same ownership rule §23.16 corrected one layer up, now
guarding a platform callback that would otherwise be a natural place to put a shutdown.

#### Two project decisions that are now implemented rather than stated

- **`onTaskRemoved` is deliberately empty.** Media3's own documentation shows
  `pauseAllPlayersAndStopSelf()` there; this is written out and commented instead, because a listening app
  that stops when the listener clears their recents is broken in a way that looks like a feature working.
- **The uid is the queue *entry*, not the Note.** `pageId` is the obvious uid and it is wrong, because
  `ActiveQueue` permits duplicates deliberately — "Two identical Notes in one queue is a legitimate thing for
  a user to have asked for" — so two entries can share a page id while being distinct entries, and Media3
  uses the uid to identify an item across state updates. `"$index:$pageId"` it is, with `pageId` as the
  `mediaId` because a stable Note identity is what that field is for. §23.19 records this because it is the
  kind of thing that looks like over-engineering until a queue repeats a Note.

#### Verified, and not

- **389 JVM tests, 0 failures** (was 379). Ten additions: nine in `LockScreenProjectionTest`, one in
  `PlayerMediaCommandsTest`.
- `:app:assembleDebug` and `:app:compileDebugAndroidTestKotlin` both succeed.
- **On a device:** the app installs and launches with the new `PlaybackService` and player and logs no crash;
  and the merged manifest is confirmed to carry `android:foregroundServiceType="mediaPlayback"`,
  `android:exported="true"`, the `androidx.media3.session.MediaSessionService` intent filter and both
  `FOREGROUND_SERVICE*` permissions. That last check reads AGP's merged manifest rather than the device,
  because the device dropped off repeatedly during this round and a manifest merge is not a device fact.
- **Not verified, and this is the honest statement of where the feature stands: nothing starts the service.**
  `PlaybackService` is declared and correct, `ReaderSessionPlayer` exists and compiles, and **no code path
  starts the service on a playback command** — which is Phase 2's remaining half, and until it is done there is
  no media session on the device at all. `dumpsys media_session` was checked and reports no session for this
  package, which is the expected and correct result for the code as it currently stands rather than a defect.
  It is recorded this way so that no later reader mistakes the green suite for a working feature.

#### Two more mistakes of my own, both recorded

`LockScreenProjectionTest`'s duplicate-uid test **could not have failed for the right reason**: the fixture
derived page ids from the index, so two entries titled "Alpha" got `page-0` and `page-1` and the premise
("they are the same Note") was never true. The fixture now takes explicit page ids through a dedicated
`repeatingQueue`, and the comment says why. That is the third appearance this session of "an assertion about
a case that was never produced", which is now the project's most-repeated lesson by a distance.

**I also used the truncation trap again** — `adb shell dumpsys media_session | Select-Object -First 6` — which
hung the terminal for five minutes on a large dump. `AGENTS.md` warns about exactly this, I had read it, and I
did it anyway. Noted because the lesson clearly needs to be a habit rather than a documented fact.

#### Still owed in this phase

1. **Starting the service on a playback command**, which is what makes any of this observable.
2. **Audio attributes** (`USAGE_MEDIA`, `CONTENT_TYPE_SPEECH`) and the focus-outcome table — Phase 3, where
   the silent failures live, verified with `adb shell cmd audio set-enable-hardening throw`.
3. **The notification**, the session activity and the media keys — Phase 4.
4. **Removing `keepScreenOn`** and inverting its instrumented assertion — Phase 5.

The device was unreliable throughout this round — it dropped off mid-install three times, and a second
unauthorised device appeared in the list at one point. `tools/run-accessibility-tests.ps1` now refuses to run
with more than one device for exactly that reason, and that guard was written before this round rather than
because of it.

### 23.20 Starting the service, and the defect only a device could show

**Date:** 2026-09-18. Phase 2 completed: `PlaybackService.start(...)` and a trigger in `ReaderViewModel`, then
the session read back off a device. **389 JVM tests, 0 failures.**

#### The device changed, which every later result has to be read against

The Pixel 8 Pro (API 37) that every previous result in this project refers to **is no longer attached**. The
device now connected is a **Galaxy S24 (SM-S931B) on Android 16 / SDK 36**. That is not a trivial swap:

- **Android 17's background-audio hardening does not exist on Android 16.** So the whole failure mode §23.9
  investigated — audio suppressed with no exception for an app without a foreground service — **cannot be
  reproduced on this device at all**, and neither can the `set-enable-hardening` diagnostic.
- The seven focus-test failures were characterised on the Pixel and are not re-checked here.
- Every result below is *for this device*. It is recorded rather than glossed, because "verified on a device"
  and "verified on the device this project's findings were derived from" are different claims.

#### How the service is started, and why the trigger is what it is

`PlaybackService.start(context)` uses `ContextCompat.startForegroundService`, and the trigger is
**`session.state.isPlaying` becoming true** — not a button press. Two reasons, both load-bearing:

- The platform requires a `mediaPlayback` foreground service to be started **in response to a user-initiated
  operation while the application is visible**, because that is what grants while-in-use capability. Playback
  beginning is the tightest expression of "the listener asked for this": nothing in this application starts
  speaking on its own, so it cannot be anything else.
- A trigger on the *intent* would start the service for `Play all` before its first Note had been fetched, and
  would then owe the platform a foreground notification for a session that was only loading.

It lives in the ViewModel rather than the host because starting an Android service needs a `Context`, and
`ReaderHost` is deliberately Android-free. It is also correctly task-scoped: it responds to what a listener
did on a screen, and the service it starts outlives the ViewModel — so a cleared ViewModel leaves the service
running, which is the point.

#### Verified on the device: the media session is real and complete

The probe walked `HOME → BROWSE → PLAYER` ("Section 1 of 64 · Playing"), Back to Browse with the mini-player
showing "Note 1 of 20 · Playing", and then `dumpsys media_session` reported:

```plain text
package=com.example.notionreader   active=true   controllers: 3
state=PlaybackState {state=PLAYING(3), position=777, buffered position=777, speed=1.0,
                     actions=4770, active item id=0, error=null}
metadata: size=4, description=Notion Reader — Active Queue & Reader Session Spec, null, null
queueTitle=null, size=20
```

**A media session exists, reports playing with a real position, names the Note, and carries the whole
20-item queue** — which matches the mini-player's "Note 1 of 20" exactly. That is background playback's
foundation working.

#### The defect: a session the platform could command and not read

The **first** device run of the same probe reported this instead:

```plain text
state=PlaybackState {state=PLAYING(3), position=-1, buffered position=-1, speed=0.0,
                     actions=4770, active item id=-1, error=null}
metadata: null
queueTitle=null, size=0
```

A session that is *playing* with **no metadata, no position, no queue, and speed 0.0**. The lock screen would
have shown a play button and nothing else: no Note title, no position, and an empty queue — the feature
failing while looking like it worked.

**The cause was a misunderstanding in `PlayerMediaCommands`, and it is a good one to have found.** The first
version advertised *only* the transport commands, on the reasoning that `Commands` is what a transport may
invoke. That is wrong: `Commands` is also what gates Media3's ability to **describe** the session. The legacy
metadata, the position and the queue are all built from `COMMAND_GET_METADATA`, `COMMAND_GET_TIMELINE`,
`COMMAND_GET_CURRENT_MEDIA_ITEM` and their siblings — so without them the platform holds a player it can
command and cannot read, and reports position and speed as unknown with null metadata.

The fix is `Player.Commands.Builder.addAllReadOnlyCommands()`, Media3's own definition of that set rather
than an enumeration of ours, added **in `mediaCommandsFor` and not in `mediaCommandSetFor`**. That separation
is deliberate and is what let the defect be fixed without loosening anything the tests check:

| | What it is | Tested |
|---|---|---|
| `mediaCommandSetFor` | the **transport command set** the Core Player spec defines and availability governs | JVM, ten tests |
| `mediaCommandsFor` | the platform's **advertised capability set** — that, plus the read-only commands the platform needs to render it | by device, via `dumpsys media_session` |

The read-only commands are safe by construction and need no availability filtering: our availability governs
*actions*, and none of these is one.

**And this is the case for the specification-first process, stated as evidence rather than principle.** §23.9
argued that every failure in this area is silent and that the verification must therefore make the failure
loud. This one was silent in the strongest sense: no exception, no log line, a session that reported
`PLAYING` and an app that read its Note aloud perfectly — the *only* symptom was that the system had nothing
to display. Nothing in the JVM suite could see it, and no amount of reading the mapping would have, because
the mapping was internally consistent and wrong about what `Commands` means.

#### Read honestly: what was verified, and what was misread

**Verified:** the session exists and is complete (above), and the app launches, plays and does not crash.

**Misread, and corrected here.** I first reported that the service was *not* running, on two pieces of
evidence that do not support it:

1. `dumpsys activity services com.example.notionreader` returned "(nothing)" — **and that check ran after the
   probe, which ends with `am force-stop`** (`tools/probe-player-back.ps1` line 70). The app was already dead
   by design. The result says nothing about the foreground claim.
2. Samsung's own `SGM:FgCheckThread: pkgName: com.example.notionreader is not in foreground` lines look
   damning and are **not trustworthy for this**: the same log shows the same check naming our package as the
   foreground app on the preceding line, while the Player was on screen and reading aloud.

**So the foreground-service claim is unverified.** The specific reason it matters: Media3's `MediaSessionService`
goes foreground by posting a media notification, and **the notification is Phase 4's work**. Until it exists a
strong prediction should be recorded rather than discovered later — *on Android 16 playback continues because
16 has no hardening; on Android 17 the same build would have its audio suppressed, because there is no
foreground claim.* That is the next thing to test, on a device that can show it.

One incidental observation worth keeping, because it caused a confusing failure: the probe classified the
first screen as `OTHER`, and the log explains it — `isNotificationPanelExpanded=true`. **The notification
shade was stuck open**, covering the app, so every UI dump was of the shade. `adb shell input keyevent 3`
(Home) cleared it after `keyevent 4` and `cmd statusbar collapse` had both failed. A stuck shade is the same
class of trap as the keyguard and the locked device: a UI probe reporting a wrong answer with full
confidence, and the fix is to check what has focus before believing a dump.

#### Verified

- **389 JVM tests, 0 failures.** No test changes were needed for the fix — which is itself the point of having
  kept the pure set and the container separate.
- `:app:assembleDebug` and `:app:compileDebugAndroidTestKotlin` both succeed.
- **On device, twice**: the first run produced the defect above, the second produced the complete session.
  Both `dumpsys media_session` outputs are quoted in this section because the *before* is the evidence that
  the fix was necessary, and quoting only the after would hide that this was found by looking rather than
  built correctly by luck.

### 23.21 The foreground claim does not exist, and it cannot until the notification does

**Date:** 2026-09-18. §23.20 recorded a prediction rather than a finding, because the device it was made on
could not test it. The Pixel 8 Pro (API 37) reappeared, so it has now been tested. **The prediction was
correct, and the mechanism is now identified precisely enough to change the phase order.**

#### The measurement

With the app **on the Player and reading aloud in the foreground**:

```plain text
PlaybackService running: False
  ...and foreground:      False
```

The service my `ReaderViewModel` trigger starts **is not running at all**, and there is no crash, no log line
naming our package in the service/foreground/notification categories, and no change in the app's behaviour —
it reads aloud perfectly. A missing foreground claim with no symptom is exactly the silent-failure shape
§23.9 investigated.

#### The mechanism, which is the part that matters

`POST_NOTIFICATIONS` is **not declared in the manifest** and therefore not present in the installed package:

```plain text
POST_NOTIFICATIONS declared in the manifest: 0 occurrences
```

A Media3 `MediaSessionService` becomes foreground **by posting a media notification** — that notification *is*
the foreground notification; there is no second one. So an app that cannot post a notification cannot hold a
foreground claim, and on API 37 the platform then refuses its background audio APIs:
`AudioHardening background playback muted for <pkg>, level: partial` — "no foreground service at all" — with
no exception and no failure message, which is the documented behaviour and precisely what §23.9 was about.

#### What this changes: the phases are not independently orderable

The Background Playback specification lists the notification as **Phase 4**, after the service and after
audio focus, in a section that reads as four additive steps. **That ordering is wrong, and this is the
evidence.** The notification is not a surface added on top of working background playback — it *is* the
foreground claim, and without it:

- the service does not persist,
- the app holds no foreground claim,
- and on API 37 background audio is silently suppressed.

So **Phase 2 cannot work until the notification exists.** Whichever order the work is done in, the notification
has to land with or before the service for the service to do anything, and the specification's phase list
should be read as "the notification completes Phase 2" rather than "Phase 4 adds a notification".

This is recorded as a specification correction rather than an implementation detail, and it is the second time
in this area that reading the platform has overruled a plan: §23.18's mapping table was the first.

#### What is still not established, stated precisely

- **Whether the service start is even attempted** is not confirmed. The trigger is `isPlaying` becoming true,
  and the app was showing a **restored** session rather than one started by a tap — so it is possible the
  collector fired correctly and the service was refused, or that something about the restore path means it
  did not fire. Both produce the same observation, and telling them apart needs a log line that does not
  exist yet. Recorded rather than assumed.
- **Audio suppression itself was not observed.** The `AudioHardening` entries in this device's dump all name
  TalkBack; none name this app. That is consistent with the app never holding a foreground claim, but the
  decisive experiment — start playback, let the screen go off, and watch for a suppression entry naming our
  package — needs the app to first *attempt* background audio with a service that stays up.

#### Added: `tools/probe-background-audio.ps1`

Written to make this measurable rather than inferred, since the evidence for every claim above is the
platform's own audit log — the only thing that reports a *silent* suppression. It reads the device identity
first, navigates by tapping the accessibility dump, reports whether `PlaybackService` is running and
foreground, reports the notification permission's declared/present/granted state, then puts the screen to
sleep and reports whether reading continued and whether any `AudioHardening` entry names the app.

Its own first run failed on an assumption worth recording: it expected Home and tapped `Browse Notes`, but the
app was **on the Player, already playing a restored session**, because Home shows Continue listening when
there is something to resume. It now taps the bottom `Browse` tab, which exists on every top-level destination.
That is the third time in this script's short life that a probe reported — or nearly reported — a wrong answer
because it assumed a screen instead of reading one, after hard-coded coordinates and a title-based classifier.

#### Verified

- **389 JVM tests, 0 failures.** No product code changed in this section, so nothing else is implicated.
- The device identity is printed and was API 37 — the API level at which the claim under test exists at all.
- The service-not-running measurement was taken **while the app was reading aloud in the foreground**, from
  `dumpsys activity services`, before anything was allowed to exit.

### 23.22 The notification permission is declared, and the service still does not start

**Date:** 2026-09-18. §23.21 predicted that the missing notification permission explained the missing
foreground claim. The permission has been added; **the service still does not start**, so that prediction was
necessary-but-not-sufficient and §23.21's "Phase 2 cannot work until the notification exists" now needs one
more layer.

#### What was done

- **`POST_NOTIFICATIONS` declared** in the manifest, with the reasoning recorded there: a `MediaSessionService`
  becomes foreground *by posting a media notification*, so an app that cannot post one cannot hold a claim.
- **Requested at the first playback action**, not at launch — `MainActivity` observes `isPlaying` becoming true
  and asks once, with a **no-op result**. The discarded result is a requirement, not laziness: the Background
  Playback spec says a denial "must not stop playback, must not fail a command, and must not be reported as a
  playback error", so there is no branch to take. `Build.VERSION_CODES.TIRAMISU` is where the permission starts
  being required and `minSdk` is 26, so the version guard is load-bearing.

#### The measurement, after the fix

Permission confirmed present and granted on the device:

```plain text
android.permission.POST_NOTIFICATIONS: granted=true
PlaybackService present: False
isForeground=true:      False
```

No exception in our process's own log (`logcat --pid`), and no Media3 message. So **`PlaybackService.start()`
is either not being called or is being called and producing nothing**, and the notification permission was not
the blocker. §23.21 listed exactly this as unestablished — "whether the service start is even attempted" — and
it remains the open question, now with the permission ruled out as the cause.

#### A second, unexplained observation that must not be guessed at

While measuring the above, and reproducible: **after `am force-stop` followed by a fresh start, the app shows
the Player, reports `Playing`, and TTS utterances continue** — with the process confirmed new (`pidof` empty
after the stop, a different pid after the start).

```plain text
pid after start: 32326
screen: Notion Reader — Active Queue & Reader Session Spec | Note 1 of 19 | Section 8 of 64 |
        Paragraph 44 of 263 | Playing | Previous section | Pause | Next section
TTS utterances: 23:42:26, 23:42:29, 23:42:30, 23:42:32 (checked at 23:42:46)
```

If that is what it looks like, it contradicts the Active Queue & Reader Session spec twice — "the most recent
unfinished session is not played, not opened, and not resumed automatically" and "nothing in the application
may start speaking without a listener action" — and the UI contract's "opening content does not imply
playback".

**It is recorded as an observation and not as a defect, deliberately.** A search of `src/main` finds
`continueSession` and `replayCompleted` reachable only from the ViewModel's public methods, which are reached
only from the shell's tap handlers, so *nothing in the code I can see starts playback at launch*. Something
else is going on — a restored task, a resumed activity, a persisted state being acted on, or my own
interpretation of the screen being wrong — and **none of those is worth guessing at**, because the cost of
guessing wrong here is high: it is either a serious spec violation or a misreading, and the two need opposite
responses. The next step is to determine which, with the process and the audio state observed together rather
than inferred from a screen dump.

#### Still not verified, and now with one more thing ruled out

- The service start is not happening, and the notification permission is not why.
- **Audio suppression was still not observed** — the `AudioHardening` entries on this device all name TalkBack.
- The probe needs Browse's results to have loaded before `Play all` exists; its tap sequence is therefore
  order-fragile against a slow network, which is worth fixing before it is relied on again.

#### Verified

- **389 JVM tests, 0 failures**; the APK and the instrumented source set compile.
- The device is the **Pixel 8 Pro, API 37** — printed by the probe and confirmed directly, since the API level
  is what makes the hardening question meaningful at all.
- The permission is declared in the manifest and `granted=true` on the device, which is the part of this round
  that is unambiguous.

### 23.23 The diagnosis is blocked by a measurement problem, not by a code problem

**Date:** 2026-09-19. I set out to resolve §23.22's open question — is the service start even attempted? — by
logging at the call site. **The question is still open**, and the reason is worth more than the answer would
have been: *our log output does not appear on this device at all*, so no diagnosis in this area can currently
be made through logging.

#### What was added, and it is still right

Three diagnostic lines, each chosen to make a specific silent failure attributable:

| Where | What it distinguishes |
|---|---|
| `MainActivity.onCreate`, with `savedInstanceState == null` or not | a fresh process from a resumed Activity |
| `ReaderHost.start()` | that a fresh process really built a fresh host |
| `ReaderViewModel`, at the `PlaybackService.start` call site | **attempted and refused** from **never attempted** — §23.22's exact blocker |

The third also wraps the call in `runCatching { … }.onFailure { Log.w(…) }`, because `startForegroundService`
throwing would otherwise leave no trace at all. The reasoning is the background-playback spec's own: the
platform's default in this area is silence, so anything we can make audible we should.

#### Why the logs did not appear, and it is a measurement problem

Established by elimination, each step checked rather than assumed:

- **The code is installed.** The APK on disk contains the new string (`FOUND in classes7.dex`), was built at
  00:06:24, and `dumpsys package` reports `lastUpdateTime=2026-09-19 00:07:03` with the process starting at
  00:07:46 — so the running code is the new code.
- **The app is running and on screen.** `pidof` gives a pid; `topResumedActivity` is `MainActivity`;
  `mCurrentFocus` is our window.
- **Nothing is suppressing the tag.** `getprop` shows no `log.tag.NotionReader`.
- **The buffer is saturated.** `logcat -g` reports `main: ring buffer is 256 KiB (255 KiB consumed)`.

**So the most likely explanation is eviction.** A 256 KiB main buffer on a device this busy churns in seconds,
and a handful of startup lines from ten seconds ago is exactly what gets pushed out first. That is consistent
with every observation: reading the *whole* buffer finds nothing from our package, and a `-t 3000` window
covers a fraction of a second of this device's output.

**It is not proved.** It is the explanation that fits all the evidence and requires no new defect, and the
test that would confirm it is a **live capture** — `logcat` running *before* the launch, not `logcat -d`
afterwards — which is what the next attempt must do. Recording the reasoning rather than the conclusion,
because "likely" is not "measured".

The generalisable lesson is the same one this project keeps meeting in other shapes: **a tool that reports
nothing is not evidence that nothing happened.** `logcat -d` finding no line reads as "this code did not
run", and the actual cause was that the question was asked of a buffer that had already forgotten.

#### A correction: `am force-stop` needs longer than three seconds

An earlier reading in this round said the process **survived** `am force-stop` — pid 4833 still alive — which
looked like a significant finding. It was a race: with a 3-second sleep the process was still being torn down,
and with a 4–5 second wait `pidof` returns empty. Recorded because the false reading was nearly written up as
"force-stop does not work here", which would have sent the next round looking for a cause in the wrong place.

#### The launch-playing observation still stands, and is still unexplained

Reproduced again on a **fresh** process (`pidof` empty after a verified stop, then a new pid): the app shows
the Player, reports `Playing`, `Section 8 of 64`, `Paragraph 44 of 263`. It is not a stale dump — the process
is new and the Activity is `topResumed`.

It remains recorded as an **observation and not a defect**, for the reason §23.22 gave: a search of `src/main`
finds nothing that starts playback at launch, so either something outside my view is resuming it or I am
misreading the screen — and those need opposite responses. **The next attempt must use a live log capture**,
which is now the only route to the two answers this round was supposed to produce.

#### Verified

- **389 JVM tests, 0 failures**, after one test was corrected — see below.
- Diagnostics compile and are in the installed APK, which is all that can be said for them so far.

#### And a test-quality correction the diagnostics forced

`anExactResumeRecordsNothing` failed the moment a second, unrelated diagnostic was added, because it asserted
the **whole log sink** was empty:

```kotlin
assertEquals(emptyList<String>(), lines)
```

Its intent was "an *exact resume* records no resume diagnostic", and "nothing at all was logged" only
happened to be equivalent while the resume decision was the only thing that logged. It now filters for the
records it is about:

```kotlin
assertTrue(lines.filter { it.startsWith("resume ") }.isEmpty())
```

**The assertion was the defect, not the extra logging** — an assertion should be about the property it names,
and this one was about a coincidence. It is the same family as §22.9's node dump and §23.19's duplicate-uid
fixture: the test was not seeing the mechanism, it was seeing a side effect of the mechanism's absence.
Filtering by prefix also means a second *kind* of diagnostic can be added later without silently weakening
what this checks.

### 23.24 Stage 0c is written — the Queue Creation & Queue Management Specification

**Written:** 2026-09-19. `docs/queue-creation-and-queue-management-spec.md`, **hand-authored** per D33. The
last of the three documents Stage 0 owed, and the one that gates every Saved Queue surface: the Saved tab,
Saved Queue Details, Create, Edit, Queue Destination, the Note's `Add to queue` and Browse's
`Add all to queue`, and Home's *Your queues*.

#### The finding that shaped it: the blocker was one field wide

§20.4 recorded the whole of Saved and Smart Queues as "blocked on the Queue Creation & Queue Management spec,
which does not exist", and §23.2 found that this was true of the **persistence schema** and of **Smart
Queues**, and false of almost everything else. Writing the document confirmed it, and the confirmation is the
useful part:

- The **Product & Architecture Contract already settles** the operations (create, name, add, add a result
  set, remove, reorder, rename, delete, play), the identity rule (dedupe by stable page identity, never by
  title), that adding must not start or interrupt playback, and the stored fields — "a stable queue identity;
  a user-visible name; ordered Note identities; persistence metadata".
- The **UI contract already settles** the surfaces and the interaction: the Saved tab, Details, an explicit
  **Edit** mode with `Move up` / `Move down` / `Move to top` / `Move to bottom` / `Remove` that "must never
  require drag-and-drop", and the Queue Destination rules including what it must *not* show.
- **What had no owner was the persistence schema**, which the UI contract defers, the integration spec
  delegates away from itself, and the session spec scopes out twice.

So most of the document is derived rather than decided, and it says so explicitly — including that "where
this document restates one, it is restating rather than deciding, and the contract wins". A specification
that quietly re-decides contract-level semantics is the failure mode the conflict protocol exists to prevent,
and a Saved Queue is the feature where that would be easiest to do.

#### What is genuinely new, and why each one needed deciding

| Decision | Why it could not be inferred |
|---|---|
| **The store is reused, not invented** | §20.4 ranked this work partly on "a session store exists, so Saved Queues must reuse it"; a second codec with its own version policy is a second thing to drift on what an unknown version means and how malformed input is reported |
| **The record decodes per-record, not as one array** | `SessionCodec` already does this and its comment records why; the same mistake is available here, so the requirement is stated rather than left to be re-derived |
| **`QueueOrigin.Saved`/`Smart` carry an identity, not just a name** | the contract says origin must never re-evaluate its source — which a name satisfies — but a name is not an identifier: two queues may share one, and a rename makes a stored name stale. This is R4 and a deliberate `SESSION_SCHEMA_VERSION` bump |
| **Add reports counts, including "none"** | the contract requires dedupe and says nothing about telling the listener. The common case is `Add all to queue` over an overlapping result set, and a silent dedupe is indistinguishable from a failure |
| **A no-op writes nothing** | for a move at a boundary or a rename to the same name. Not derivable, and invisible from a screen, which is why it gets a failing-first proof against a recording store |
| **Edits are applied as they are made, not at `Done`** | the opposite is defensible. Stating it matters because Back leaves Edit mode, and the alternative would lose a reorder through the platform's most reflexive gesture |
| **Back leaves Edit mode rather than the screen** | the UI contract defers this to "queue-management spec" — this is that definition, and it is the one place Back does something other than reverse navigation |
| **A stalled Note offers Retry and move-on, and nothing else** | §19.9 recorded the dead end and §23.7 attributed the policy here. Ratified rather than invented: §22.6 already gave the listener `Next note`, and this states that removing or marking the Note would edit a frozen queue or record a transient fact permanently |
| **Saved Queues are never pruned** | sessions are (10/20/30 days). Discarding something a listener built is not the same as discarding a record of something they did, and a limit would need a decision about what happens when it is reached |

#### Recorded as open rather than guessed

Retention for Saved Queues; whether a queue's identity is ever displayed; import and export; and whether a
member's stored title should ever be refreshed. Each is listed with the reason it is open rather than
decided, on the same principle as everywhere else in this project: a specification that guesses is worse than
one that says what it has not settled.

#### Verified

- **Documentation only. No Kotlin changed**, confirmed by `git status` — so the JVM suite and the instrumented
  suite are unaffected. The 389-test JVM run from §23.23 stands.
- The document **uses tables**, which are permitted here and forbidden in `docs/notion/`: the exporter has no
  `table` case, so a table in a mirrored page never reaches its mirror. That is now recorded in `AGENTS.md`.
- `AGENTS.md`'s governing-document table is updated to **seven documents, five mirrored and two
  hand-authored**, with the Queue Creation spec added and its scope boundary stated: it owns Saved Queue
  persistence, Queue Destination and both `Add` actions, **with Smart Queues deliberately out of scope**
  (D30) because they need this document *and* the still-unwritten Content Metadata spec.

### 23.25 Saved Queue Phase 1 — the record, the codec and the store

**Date:** 2026-09-19. The first implementation of the Queue Creation & Queue Management spec, and it is the
phase that changes nothing: a record, a codec, a store interface, an in-memory double and a DataStore
implementation, with no surface reading any of it. **417 JVM tests, 0 failures** (was 389; 28 additions).

#### What was built, and what "reuse the store" actually meant

The spec requires that a Saved Queue **reuse the session store's technology and policy** rather than introduce
a second one, and the phase shows what that requirement is worth in practice — it is a constraint on *three
separate things*, and keeping them separate is the design:

| Reused | Not reused |
|---|---|
| the pattern: an Android-free interface in `domain`, a pure codec beside it, a device-backed implementation in `data.local` | **the record** — `SavedQueue` is not `StoredSession` |
| the version policy: unknown version → discard, never migrate | **the version constant** — `SAVED_QUEUE_SCHEMA_VERSION` is its own |
| the failure posture: no `Result`, no error type, a failed read is "none" and a failed write is a lost edit | **the file** — separate from listening history |

The version constant being separate is the one that looks like duplication and is not. The two records are
versioned independently, so a change to a session record never forces a re-read decision about queues; sharing
a constant would make a session bump a queue migration question by accident. A test asserts the *policy* is the
same while the *constant* is separate, which is the only way to hold both halves of that.

A **separate preferences file** for the same class of reason: the two codecs are already independent, so
sharing a file would not force a shared version — but it would mean a Saved Queue edit rewrites the listening
history's bytes for no benefit.

#### One deliberate difference from `SessionCodec`, and why it is not a drift

`SavedQueueCodec.encode` **sorts by identity**; `SessionCodec.encode` does not. That is a real difference, and
it exists because the two lists mean different things:

- sessions are stored in recency order because **retention reads that order** — the oldest is dropped to make
  room;
- queues are a **set with no meaningful order between them**, and the whole list is rewritten on every edit.
  Without a canonical order, editing one queue would reorder the bytes of every other, and the store would
  churn for nothing.

Sorting by **identity** rather than by name is the same argument one level down: a rename is the one edit that
changes a label and nothing else, so it must not change where a record sits in the file. A test asserts
exactly that — the bytes differ after a rename, and the identity order does not.

#### Two decisions worth recording, because both make a rule testable

**The duplicate rule is a diagnostic, not a validity gate.** `SavedQueue.hasUniqueMembers` reports the rule the
*operations* must maintain, and `isValid` deliberately ignores it. A stored record comes from disk rather than
from a value this application just built, so a duplicate is only reachable through a bug in an add path — and
discarding a listener's whole queue over it would be a disproportionate response to something that can still
be listed and played.

**An empty queue is valid, and that is the point of the validity rule.** "Create saved queue" deliberately
produces one, so a predicate that rejected emptiness would make the feature's own first step produce an
unusable record. It is asserted as a *property* — validity does not depend on the member list, for empty,
one-member and fifty-member cases — rather than as two separate cases, because "these two happen to be true" is
weaker than the members being irrelevant.

**The recording double exists for Phase 2, not for these tests.** `InMemorySavedQueueStore` counts writes and
keeps every list passed to `write()`, because Phase 2's rule that "a move at a boundary performs no write" is
**invisible in the resulting list** — the assertion has to be about the store's activity. Building that capability
now is what stops the model tests later being tempted to infer it from a value that cannot show it.

#### Verified

- **417 JVM tests, 0 failures** (was 389). 28 additions: 15 in `SavedQueueCodecTest`, 13 in `SavedQueueTest`.
- **Failing-first proofs**, both guards removed together and each noticed by its own tests:

  | Guard removed | Tests that noticed |
  |---|---|
  | the per-record `isValid` filter | `aRecordFromAnUnknownVersionIsDiscarded`, `aRecordWithABlankNameIsDiscardedByTheRecordsOwnValidityRule`, `oneUnusableRecordDoesNotMakeTheOthersUnreadable`, `anUnrecognisedRecordIsDiscardedEvenWhenItSitsBesideAValidOneOfTheSameName` |
  | the canonical `sortedBy { it.id }` | `theStoredBytesDoNotDependOnTheOrderTheCallerDecidedToHandThemOver` |

  Five failures, all named, and the split is the right answer: the validity filter has four tests because it is
  the version policy and the blankness rule together, and the sort has one because canonical order is one
  property. Both were restored from a file backup rather than by re-typing, so the restore is exact.
- `:app:assembleDebug` and `:app:compileDebugAndroidTestKotlin` both succeed.

#### What this phase deliberately does not do

No surface reads any of it, and no operation exists yet — `Create`, `Add`, `Remove`, the four moves and
`Delete` are Phase 2's, as a pure model over this store. The phase split is the spec's, and it is the same
reasoning this project uses elsewhere: land the thing that cannot be wrong, then the thing whose mistakes are
invisible from a screen.

Phase 3 remains the one to do deliberately — the identity-carrying `QueueOrigin` and its
`SESSION_SCHEMA_VERSION` bump — because it is the only part of this work that changes persistence that already
works, and its mistake would be silent.

### 23.26 Saved Queue Phase 2 — the model, and the data-loss defect the phase found

**Date:** 2026-09-19. The six operations as a pure model: one entry point, one place where "changed nothing"
is decided, and the counters a surface needs to report a de-duplication honestly. **459 JVM tests, 0
failures** (was 417; 42 additions).

#### The design point: one `when`, so four rules cannot be forgotten

`QueueEdit` is a sealed value and `QueueEdit.applyTo(queues, now)` is the only way to apply one, for the same
reason `ReaderApp.invoke(command)` is the only way to command the player. Six methods could each get one of the
shared rules right and one wrong, and the wrong one is invisible — the queue still lists, it just is not what
the listener asked for. As one value, four rules are stated once:

| Rule | How it is satisfied |
|---|---|
| a no-op performs no write | the candidate is compared with the input **once**, and `changed` is that comparison |
| a rejection performs no write | a rejection's candidate *is* its input, so the same comparison answers it |
| an edit touches one record | only the target's `lastEditedAt` is stamped, and `targetId` names it |
| an edit preserves everything else | every branch derives its result from the input list; no branch can drop a record except the one it deletes |

The ordering matters and cost a test to find. The comparison has to happen **before** the timestamp is
applied: stamping first changes `lastEditedAt`, which makes "rename to the name it already has" report a change
and write for nothing. `aRenameToTheNameItAlreadyHasPerformsNoWrite` is the test that names that.

The cost of stamping afterwards is that the effect had been built from the pre-stamp record, so it named a
record the outcome no longer contained — **two versions of one queue, differing only in a timestamp, handed to
one caller**. `SavedQueueEditsTest` caught this as a failed equality between an effect's queue and the
outcome's, and the fix is `QueueEffect.naming(final)`: five `copy` lines that re-point an effect at the record
the outcome holds. That is not tidying — it turns "an effect and its outcome name the same record" from a
coincidence of two expressions into a construction.

#### The defect: a failed read became a whole-list write

Phase 1's port was `read()` and `write()`. `read()` is required to report a store it cannot read as **no
queues** — right for a caller displaying queues. But the coordinator then read, decided and wrote, and the write
was the whole list. So a failed read produced an empty list, a decision was taken about an empty library, and
writing it back **removed every queue the store actually holds**.

Of the six operations exactly one reaches it: **`Create`**, whose result does not depend on what was read.
Rename, Add, Remove, Move and Delete all need their target to have been read, so they answer `NoSuchQueue` and
write nothing. So the reachable failure was: *a disk-read failure at the moment of a create leaves the listener
with the one queue they just made and silently without the library.* And the failure is silent in the worst way
— the app looks fine.

**The fix is structural rather than a guard.** The port is now `read()` and
`transact(decide: (List<SavedQueue>) -> QueueEditOutcome)`: the implementation decodes, decides and encodes
inside a single `DataStore.edit` block, so a read that fails means the transaction never runs and **nothing is
written at all**. A caller cannot get the sequence wrong because it cannot express the sequence. The
two methods now differ deliberately on their failure answers — `read` reports an unreadable store as no queues,
`transact` reports `StoreUnavailable` — and both are documented with the reason, because the difference is the
whole fix.

Two things fell out of it: `write` is **gone** rather than kept alongside, since an unguarded whole-list writer
is the defect itself; and `QueueEffect.StoreUnavailable` is a distinct case rather than a `Rejected`, because
the spec forbids reporting a storage failure as a failure of the operation the listener performed — the surface
shows no error and simply leaves the screen as it was. The double's `failWrites` deliberately still reports the
operation normally, so `StoreUnavailable` means exactly one thing: **the store could not be read, so nothing was
attempted.**

The Queue Creation spec gained a subsection for this, since the *shape* of the store is the spec's and this
diverges from it. §23.25's description of a whole-list `write` is superseded here.

#### Failing-first proofs

Three guards removed one at a time, each noticed only by its own tests, and the file restored from a backup
rather than re-edited. 459 tests throughout, so the removal changed no counts.

| Guard removed | Tests that noticed |
|---|---|
| the no-op comparison (`if (candidate.queues == to)`) | **19** — every no-op and rejection test in `SavedQueueEditsTest`, the four no-write tests in `SavedQueuesTest`, and `theDoubleRecordsWhatWasWrittenSoTheNoWriteRulesAreAssertable` |
| de-duplication by identity in `Add` | **5** — the by-identity-not-by-title test, the within-the-request test, the all-duplicates report, the count report, and the no-write version |
| the transaction guard (a failed read treated as empty again) | **1** — `anUnreadableStorePerformsNothingRatherThanWritingOverWhatItCouldNotRead`, the regression guard, alone |

The first is the widest because "nothing changed" is one decision serving two rules; the second is narrower and
exactly the five that assert it; the third is a single test, which is the right shape for a regression guard —
it is the only test that asserts the *store's* behaviour rather than the model's.

#### Two smaller decisions worth recording

**An empty request to `Add` is a rejection-free no-op that still reports counts.** `added = 0` with
`alreadyPresent = n` is a success, and the pair of facts "nothing changed" and "nothing to say" is why the
outcome separates `changed` from `effect`. The spec's reason is the common case: `Add all to queue` over a
result set that overlaps the destination, where a listener who adds twelve things and is told nothing cannot
tell that apart from a failure.

**`Remove` and `Move` report a null member rather than having a "nothing happened" case**, so the same posture
holds in three places at once: the effect always describes the **postcondition**, not the delta. Removing a
Note that is not a member is a success because the postcondition the caller wanted already holds.

**What Phase 2 deliberately does not do:** no surface reads any of it, `Play` is not implemented (Phase 4 owns
its availability rule), and nothing consumes `nameWarning` yet — the Create and Rename screens (Phase 6) do.
Phase 3 remains the next step that changes persistence that already works.

### 23.27 Saved Queue Phase 3 — the identity-carrying origin, and a version bump that turned out to be the whole guard

**Date:** 2026-09-19. `QueueOrigin.Saved` and `QueueOrigin.Smart` now carry a **queue identity** with the name as
a label, and `SESSION_SCHEMA_VERSION` is `2`. **461 JVM tests, 0 failures** (was 459; 2 additions).

#### The change

| | Before | After |
|---|---|---|
| `Saved` | `Saved(name)` | `Saved(queueId, name)` |
| `Smart` | `Smart(name)` | `Smart(queueId, name)` |
| `SessionNote` / `BrowseResults` | unchanged | unchanged |
| `SESSION_SCHEMA_VERSION` | `1` | `2` |

The name is kept rather than replaced, and that is a decision rather than inertia: a session must be explicable
without a lookup, so the label travels with the record. It is *allowed* to go stale — a queue renamed after the
session was stored leaves a label that is old — and that is precisely why it is a label and not an identifier.

The identity is what makes a rename findable, which is R4's whole reason. `Smart` gained one too even though
Smart Queues are deliberately out of scope here, because adding the field later would be a second breaking wire
change for no benefit now.

#### The finding: the shape change was not the guard

This is the part worth keeping. Two different mechanisms refuse an old record, and it is easy to credit the
wrong one:

| A version 1 record… | …decodes? | …is restorable? | What refuses it |
|---|---|---|---|
| with a `saved` or `smart` origin | **no** | no | the **wire shape** — the variant now requires a field the old record does not carry |
| with a `browse_results` or `single_note` origin | **yes**, perfectly | no | **only the version check** |

So the second row is the one the bump exists for. Those records decode into a `StoredSession` this build is
perfectly happy to construct, and under a schema whose meaning of "origin" has changed — the field went from
"which queue, approximately" to "which queue, exactly". Had the version not been bumped, they would have been
restored. `SessionCodec.decode` deliberately does not filter by `isValid` (the caller does), which is what makes
this visible: the test asserts the record **decodes** and is then **refused**, rather than asserting one outcome
and leaving the mechanism unstated.

#### No Notion amendment was needed, and that is worth recording

The project rule is that a hand-authored document cannot amend a mirrored one, and the origin's shape is
arguably the session spec's. It turned out not to be: the mirrored Active Queue & Reader Session spec describes
origin **semantically** — "origin is descriptive, persisted for explanation, never for reconstruction" — and
never fixes its fields, and `SESSION_SCHEMA_VERSION` is this repository's own constant rather than a spec term.
Adding an identity *strengthens* the property the spec states. So the check was made rather than assumed, and it
came back clean, which is the good outcome and not a reason to skip it next time.

#### The frozen wire format was kept, not replaced

`SessionWireFormatTest`'s frozen constant lost its job when the sample's shape stopped changing with it: it used
to pin the encoder, and now it pins **what is already on a device**. Deleting it would have made "old records are
discarded" untestable, which is the one property the bump exists to provide, so it is retained as decode-only
input and joined by a second, hand-written constant for the old `saved` origin shape — describing what a real
device holds rather than deriving it from the current encoder. Bumping the version *without* keeping the old
bytes would have made the bump look tested when the test could no longer see it.

#### Failing-first proof

| Guard removed | Tests that noticed |
|---|---|
| the version bump (`SESSION_SCHEMA_VERSION` reverted to `1`) | **4** — `theEncodingOfAKnownRecordIsUnchanged`, `aFrozenRecordStillDecodes`, `aRecordWrittenByALaterVersionStillDecodesAndIsThenRefused`, `aRecordWrittenBeforeTheOriginChangeIsRefused` |

The first three fail because the sample's version no longer matches the frozen bytes; the fourth is the new one
and fails for the reason that matters. Restored from a backup rather than re-edited; 461 tests, 0 failures.

#### Session-side consumption, and what is still missing

Grepping production for `.origin` finds three sites and all three only **carry** it: the queue into the stored
snapshot, and back out on restore. Nothing reads its fields yet, because nothing constructs a `Saved` origin
yet — that is Phase 4's `Play`. So this phase is a type and persistence change with no consumer, which is exactly
what the spec's phase split intends: the part that reaches into working persistence lands on its own, verified
by a wire-format test rather than by a screen.

### 23.32 Dropping the orchestrator, and the third check that could not see its own subject

**Date:** 2026-09-19. An experiment the previous round's cost justified: the instrumented runner, not the feature work, is where this project's time goes, so the highest-leverage change was to the runner.

#### The hypothesis, and how it was tested

`ANDROIDX_TEST_ORCHESTRATOR` starts **one process per test**. On a 4 GB emulator that is 180 process starts, competing for the same process budget as the `system_server` that kept dying. The half of its justification that mattered most — *a truncated run must not read as a clean one* — is now handled by the completeness check instead (§23.30), and nothing in this suite depends on process isolation: no test drives the real `MainActivity`, because they render `ComponentActivity` with in-memory doubles built by `ShellHarness`, so no on-disk session state is reachable from a test.

So it was removed (`app/build.gradle.kts` records both halves, what is given up, and how to put it back), and the suite was run twice from a **freshly booted** emulator — because the previous emulator was found wedged, with adb's transport saying `device` while `adb shell` did not answer in 20 seconds, which is the state the orchestrator runs left behind.

#### Run 1: a complete pass, and the hazard of believing it

```
HTML report: tests=180 failures=0
gradle exit code: 0
RESULT: PASSED - 180 tests, 0 failures (expected 180).
```

**All 15 classes, 180 tests, 0 failures, one attempt, no retry, 7.5 minutes.** For contrast: the orchestrator baseline was ~11 minutes, with runs reaching 39 minutes under thrash. And the single harness flake that had appeared in *every* previous full run — `Activity never becomes requested state` in `SavedQueueDetailsAccessibilityTest` — did not reproduce.

That is a satisfying result, and **it is not evidence that the orchestrator was the cause.** One run cannot distinguish "fixed" from "did not happen this time" — which is precisely the distinction this file keeps having to make, and the reason a second run was run rather than a conclusion written.

#### Run 2: it died anyway, so the orchestrator was not the cause

```
HTML report: tests=14 failures=1
RESULT: FAILED - 1 failing test(s) out of 14.
WARNING: This attempt is also incomplete: 14 of 180 tests ran, so some classes reported nothing at all.
```

Browse passed 13/13, then `CreateSavedQueueAccessibilityTest` reported **1 test, 1 failure**, and the run stopped — after 1.6 minutes. **The deaths persist without the orchestrator.** The measurement is therefore a *rejection*, not a confirmation: the orchestrator was not the proximate cause of the `system_server` deaths, whatever else it was costing.

Had the experiment stopped at run 1, this file would have recorded a cure that does not exist. That is the same failure as the three rounds that blamed TalkBack, touch exploration, and API 37 for the focus assertions (§23.29) — an explanation that fits the evidence available, adopted because the first piece of evidence was good.

What the second run *did* do was find a real defect.

#### The defect run 2 exposed: `<failure></failure>` is invisible to a truthiness test

The one failure in run 2 was reported by the suite counter and by nothing else:

```
com.example.notionreader.ui.BrowseScreenAccessibilityTest: tests=13 failures=0 errors=0 skipped=0
com.example.notionreader.ui.CreateSavedQueueAccessibilityTest: tests=1 failures=1 errors=0 skipped=0

HTML report: tests=14 failures=1
```

No `FAILED:` line. No `HARNESS FAILURE:` line. No name, no text, no retry. The XML said why:

```xml
<testcase name="createIsUnavailableUntilTheNameIsUsable"><failure></failure></testcase>
```

**An empty `<failure></failure>` deserialises to an empty String, and an empty String is falsy in PowerShell.** So the filter

```powershell
$suite.testcase | Where-Object { $_.failure -or $_.error } | ForEach-Object { ... }
```

**dropped the testcase entirely.** Measured rather than reasoned about, on the real output and on a synthetic one:

| expression | `<failure></failure>` | `<failure message="x">y</failure>` |
|---|---|---|
| `$_.failure -or $_.error` | **false** — dropped | true |
| `$null -ne $_.failure -or $null -ne $_.error` | true | true |

The consequence is a specific and nasty shape: the suite counter still said `failures="1"`, so the run came out **red while the report said nothing at all**. And because `Test-IsRetryable` gates the retry on `HarnessFailures.Count -eq Failures`, a count that could not hold, the one failure in the run was **neither reported nor retried**.

**Why it survived until now is the same reason the others did: every failure this runner had ever successfully handled carried a message.** The shape it could not see was the one with nothing inside it — and that is exactly the shape a device-side death leaves. The case it was blindest to was the case it most needed to recognise.

#### The check written to prove the classifier works had the identical bug

`tools/check-classifier-on-real-output.ps1` exists to prove the classifier can read the failures in a *real* run. It reports how many failures it found. On the file above — one failure, one empty element — it printed:

```
failures in this file     : 0
read as EMPTY by the reader: 0   <-- must be 0
```

**Zero.** Its own loop had the same filter:

```powershell
if (-not ($case.failure -or $case.error)) { continue }
```

So the instrument built to detect that the classifier could not see its subject **could not see the subject either**, and reported the most reassuring number available. A check that cannot see what it is checking does not fail — it returns zero, and zero reads as "nothing wrong".

After the fix, on the same file:

```
failures declared by the suites : 1
failures with a per-test record : 1   <-- must equal the line above
  ... empty, so the harness     : 1
  ... matched a pattern         : 0
  ... UNCLASSIFIED              : 0   <-- must be 0
```

#### The fix, in three parts

1. **The filter tests presence, not truthiness** — `$null -ne $_.failure -or $null -ne $_.error`.
2. **An empty failure is the harness, structurally.** An assertion failure always records a stack trace in the element's text, so *no message and no trace* means the process never produced a record at all. It is checked before the pattern list rather than added to it, because it is a property of the record rather than a string in one. This does not make a real defect retryable: the retry still has to pass to go green, and a genuine empty-text failure would reproduce and be reported from the second attempt.
3. **An accountability guard.** `Invoke-TestAttempt` now compares the failures the suites declared against the per-test records it actually read, and prints `UNACCOUNTED` when they disagree. This is the part that generalises: whatever shape a future drop takes, the two numbers move apart instead of both quietly deflating.

#### The proof, and it can fail for the right reason

`tools/check-empty-failure-is-classified.ps1` **lifts the wrapper's own filter by regex** and executes it as a scriptblock, so it cannot pass against a drifted copy. It asserts that the current filter sees 3 of 3 shapes — and that **the expression it replaced saw only 2 of 3**, which is what makes the check capable of failing rather than merely capable of passing. It also asserts the anti-laundering property directly: an unrecognised message-bearing failure still matches no signature and stays red.

#### The family this belongs to, now that there are three

| Round | The mechanism | How it was blind | What it produced |
|---|---|---|---|
| §23.30 | the failure classifier | read `$_.failure.'#text'`, empty for an attribute-less element | never matched anything, ever; the retry had never fired |
| §23.31 | the two-line harness report | the first line used `Write-Output` inside a function, the second `+=` to the report | the failure's *name* vanished; only its continuation line printed |
| §23.32 | the failure filter | truthiness, and an empty element is falsy | the failure vanished entirely; no name, no text, no retry |

Three mechanisms, one property: **each could not see its own subject while looking correct.** None was caught by the mechanism itself — the first was found by a checker, the second by a missing line in a report, the third by a second run that disagreed with the first. The pattern is worth stating in the form this project keeps arriving at: *a check that cannot distinguish anything still returns a number, and the number is usually reassuring.*

#### What was decided

**The orchestrator stays out.** It was removed for cost — 7.5 minutes against ~11, and no per-test process budget for the `system_server` to compete with — and the deaths happen either way, so reintroducing it would buy back nothing that was measured. This is a **cost** decision supported by two runs, not a reliability claim, and it is recorded as such: the emulator is simply less reliable than the phone was, and the mitigation is not a fix to the emulator but the completeness check plus the classifier — the runner now *says* when it is incomplete, and *says* when a death was a death.

#### Run 3, and the measurement that ended the round

Recorded after the classifier fix, on the same boot:

```
HTML report: tests=180 failures=0
gradle exit code: 0
RESULT: PASSED - 180 tests, 0 failures (expected 180).
```

**So the orchestrator-free suite is 2 clean runs of 3** — 180/180 in 7.5 min, a death at 14/180, then 180/180 in 15.0 min. The rate is unchanged from the orchestrator era, which is the finding: the deaths are the emulator's, and two of three runs are usable instead of none.

Run 3 also produced the number this round should really be judged on. Reading the per-suite `time` attribute out of the XML:

| | Tests | Test time | Per test |
|---|---|---|---|
| **Instrumented** | 180 | **873 s** (14.6 min) | **4,700 ms** |
| **JVM** (`--no-build-cache`) | 534 | **0.8 s** | **~1.5 ms** |

And the instrumented cost is **flat across every class** — 4.4 s for `HomeScreenAccessibilityTest`'s 9 tests, 5.6 s for `ReaderShellSavedQueueTest`'s 10, 4.4 s for `ReaderShellQueueBackTest`'s 2. A uniform cost per test regardless of what the test does is the signature of **fixed per-test setup** — Activity launch, instrumentation, Compose idle, teardown — and not of the assertions, which run in milliseconds.

The consequence is stark and is the subject of §24: this suite spends ~99% of its time paying for a device, to make assertions that are properties of a semantics tree the JVM can build without one.

#### Verification

- **534 JVM tests, 0 failures.**
- Run 1 without the orchestrator: **180/180, 0 failures, one attempt, 7.5 min, Gradle exit 0.**
- Run 2 without the orchestrator: truncated at 14 of 180, which is the finding, not a failure of the round.
- Run 3 without the orchestrator: **180/180, 0 failures, one attempt, 15.0 min, Gradle exit 0** — after the classifier fix, on the same boot.
- `tools/check-empty-failure-is-classified.ps1`: 7 assertions, including that the replaced filter saw only 2 of 3.
- `tools/check-classifier-on-real-output.ps1`: 1 declared, 1 read, 1 empty-as-harness, 0 unclassified — on the file that previously read as zero.
- `tools/check-wrapper-completeness.ps1` and `tools/check-harness-signatures.ps1` still pass.

### 23.31 Slice 3 — Edit mode, and a proof that proved nothing

**Date:** 2026-09-19. The four moves, `Remove`, `Done`, and Back leaving Edit mode rather than the screen. **534 JVM tests, 0 failures** (was 512; 22 additions) and **180 instrumented tests** (was 168; 12 additions).

#### What shipped

| Piece | Where |
|---|---|
| `QueueMove.isAvailableFor` — the boundary rule, derived | `SavedQueueEdits` |
| `moveSavedQueueMember`, `removeSavedQueueMember` | `ReaderApp` |
| `queueReport` → `queueMessage`, and `consumeQueueMessage` | `ReaderApp` |
| `SavedQueueDetailsState` (`Viewing \| Editing`) and the inner `BackHandler` | `SavedQueueDetailsScreen` |
| the four moves, `Remove`, `Edit queue` / `Done` | `SavedQueueDetailsScreen` |
| `moveQueueMember`, `removeQueueMember` | `ReaderViewModel` |

#### The one place where Back does something other than reverse navigation

The spec: "Android Back leaves Edit mode, and does not leave the screen. This is the one place where Back does something other than reverse navigation ... The alternative, Back leaving the screen with edits applied, would make the platform's most reflexive gesture commit changes."

The screen registers its **own** `BackHandler`, `enabled` only in Editing. That means in Viewing the handler is not registered at all and the shell's is the one that runs, so Back leaves the screen exactly as it did before. It works because the screen's handler is registered *later* — it is inside the shell's composition — and the innermost handler wins because it was added last, which is the behaviour `ReaderShellQueueBackTest` already established for the Player's queue view. So there are no new navigation mechanics, and the assertion that Back is *not* consumed in Viewing is a real one rather than a formality.

#### `Viewing | Editing` as a state, and what it buys

A boolean would be the same fact, and the reason to model the state instead is that the operations available are *derived* from it: the moves, `Remove` and `Done` are inside a branch on the state, so in Viewing they are **absent** rather than disabled. That distinction is the UI contract's requirement — "the normal Saved Queue Details screen should not expose multiple destructive/reorder controls on every Note" — and it is accessibility as much as tidiness, because a disabled control is still traversed.

The state is the screen's own, and deliberately not hoisted. Nothing outside the screen observes it: Back is handled here and the operations are driven by the controls, so hoisting it would create a second place where "is this queue being edited" is written. It is `rememberSaveable` under the state's *name*, so a third state added later cannot silently restore as the wrong one.

#### The rule is derived, so the control and the operation cannot disagree

`QueueMove.isAvailableFor` answers "would this do anything?" by **applying the move and comparing**. The alternative — `Up` needs `index > 0`, `Down` needs `index < size - 1` — is a second statement of the boundary rule that would agree with the first until one of them changed. Deriving it also means a fifth move gets the correct answer without being told what its boundary is, and one JVM test asserts the equivalence over every move, every position and four list sizes rather than naming cases.

#### The proof that proved nothing, which is the part worth keeping

Three proofs were written. Two fired immediately. The **third reported zero failures**, which reads as a finding — "this guard is not load-bearing" — and was not:

```plain text
PROOF 1  isAvailableFor reports every in-range move as available -> 534 tests, 7 failures
PROOF 2  a boundary move is announced as though it had moved the member -> 534 tests, 2 failures
...
```

and for the third, nothing at all. The cause was in the proof script: **`(Get-Content $file)` returns an array of lines, and `-replace` applies to each element independently, so a pattern spanning more than one line can never match.** The edit was silently never applied, and the suite was then run against unmodified code.

That is a *false negative* wearing the clothes of a result, and it is worse than a broken proof because it looks like information. The correction has two halves, and the second is the general one:

- read and write with `-Raw`, because a multi-line pattern needs the whole file as one string;
- **assert that the text changed**, and fail the script if it did not. A proof whose edit did not land must not report a number.

With that in place proof 1 fires on **7 tests** and proof 2 on **2**, so both guards are demonstrated rather than assumed.

#### And what the fourth proof found by not firing

Reshaping the third proof's lesson left one guard genuinely unprovable, and the reason is a fact about the code:

`moveSavedQueueMember` and `removeSavedQueueMember` carry **identical-looking guards** — `outcome.changed && effect.member != null` — and they are not the same guard:

| | at a boundary / for a non-member | `changed` | member | guard load-bearing? |
|---|---|---|---|---|
| `Move` | boundary move | `false` | **non-null** | **yes** — without `changed &&` a move that did nothing is announced |
| `Remove` | Note not a member | `false` | `null` | **no** — the null member already implies it |

So proof 4, which removed `changed &&` from the Remove, applied cleanly, ran, and produced zero failures — **correctly**, because that conjunct is redundant there. It is kept for uniformity, and the asymmetry is now written into `moveSavedQueueMember`'s documentation, because the tempting edit is to "simplify" the move guard by analogy with the remove one and the failure would be a message about a reorder that did not happen.

This is the same lesson as `FOCUS_ATTEMPT_FRAMES` in §23.29 and the dead classifier in §23.30, arrived at from the other side: a check that cannot distinguish anything is not evidence, whether it always passes or never runs.

#### The emulator was starved of memory, and that explains every emulator failure this session

The instrumented run for this slice failed **161 of 180 tests**, uniformly across every class — including classes with no Saved Queue code in them — with:

```plain text
java.lang.IllegalStateException: No compose hierarchies found in the app.
  Possible reasons include: (1) the Activity that calls setContent did not launch; ...
```

Two Browse tests had passed first, so the Activity had worked and then stopped. `dumpsys power` afterwards said why:

```plain text
mWakefulness=Asleep   mScreenOnFully=false   isKeyguardShowing=false
```

**The display had gone to sleep mid-run**, so every Compose hierarchy stopped existing. Two wrapper bugs sat underneath it, and the second is the one that caused it.

**The wrapper lowered the timeout and called that raising it.** It assigned `$TEST_TIMEOUT_MS = 1800000` (30 minutes) unconditionally and printed "Device screen_off_timeout was N ms; **raising** to 1800000 ms" whatever N was. This emulator's own value is `2147483647` — 24.8 days — so the script quietly *cut* it to 30 minutes and described that as an increase. It is now a **floor**: `[Math]::Max(device's value, 30 minutes)`, never lower, and the message says when it is unchanged. A run can no longer shorten the very setting that keeps its display on.

**The preflight asked the keyguard whether the device was awake.** It woke the device, verified `isKeyguardShowing=false`, and then printed "Device is awake and unlocked" — two claims from one measurement. The counter-example is the line above: a sleeping device with no lock passes a keyguard check. It now reads `mWakefulness` from `dumpsys power` and refuses if the device will not wake, because the failure that produces is the mass one.

**And then the deeper cause: the AVD had 2 GB of RAM on a machine with 47 GB free.**

```
hw.ramSize=2048     vm.heapSize=228
```

That is not a budget, it is a shortage. API 36, plus the AndroidX Test orchestrator starting **one process per test**, plus a Compose UI suite, exhausts 2 GB — and Android's low-memory killer then takes the largest process, which is `system_server`. Every emulator failure this session is consistent with exactly that, and **not one of them named memory**:

| Symptom | What it looked like | What it was |
|---|---|---|
| `Transport endpoint is not connected` | the emulator's transport failing | adb's daemon dying with the system |
| `cmd: Can't find service: package` | an adb or Gradle problem | the package manager dying with `system_server` |
| `mWakefulness=Asleep` mid-run, 161 failures | the display sleeping | the system restarting under pressure |
| a full run taking **39 minutes** against 11 | a slow suite | thrashing |
| `-Stop` racing a device still listed | a script bug (it was one too) | a device that was mid-restart |

`setup-emulator.ps1` now raises `hw.ramSize` to 4096 and `vm.heapSize` to 512, and the change is verified by effect — `MemTotal: 4013816 kB` rather than 2 GB, because this project's own rule is that the SDK tools report the wrong thing and only the filesystem or the device tells the truth.

The lesson is the same one this file keeps recording, in a new place: **five different symptoms, each of which named something other than the cause, and the actual cause was invisible because it was never asked about.** The question "how much memory does the device have?" was not in the diagnostic vocabulary, and every failure was attributed to the thing it mentioned instead.

#### And two real defects in the screen itself, found by the assertions rather than by reading it

With the emulator healthy, three failures remained — and **all three were real**, two of them in the UI.

**Five controls in a `Row` do not fit on a phone, and the fifth was unreachable.**

```plain text
'Remove' is laid out but occupies no area, so nothing can be tapped:
  bounds=Rect.fromLTRB(0.0, 0.0, 0.0, 0.0)
```

The moves in the same row passed, which is what located it: `Remove`, as the last child, was pushed outside the Row's bounds and clipped to zero area. The tree satisfied "in Editing it exposes all four moves and `Remove`" — every control present and named — while one of them could not be touched. **That is exactly the failure `assertIsReachable` exists for**, and it is why the helper asserts area and overlap rather than mere presence.

The fix is layout, not assertion: `FlowRow` wraps the controls onto as many lines as they need. A `Row` would overflow again the moment a listener enlarged the system font, which is the configuration this screen most needs to work in — so this is not only a phone-width fix.

**And a fixture parameter that nothing read.** The edit-mode test's `queue()` had a `report` parameter that was never applied, so the test asserting the reorder announcement asserted against a screen that had never been given one. A parameter that nothing reads is worse than no parameter, because the call site reads as though it works. `report` belongs to the *screen's* arguments, and the fixture no longer offers one.

Both are the same lesson as the classifier and the proof script above: **the assertion was right and the thing it was pointed at was wrong.** The tests were not too strict; they were correct about a screen nobody had rendered.

#### The completeness check paid for itself on its first real outing

One attempt ended:

```plain text
HTML report: tests=132 failures=1
RESULT: FAILED - 1 failing test(s) out of 132.
WARNING: This attempt is also incomplete: 132 of 180 tests ran, so some classes reported nothing at all.
```

**48 tests never ran.** Before §23.30 that would have been reported without the gap being visible at all — and had the one failing test passed, it would have said `PASSED - 132 tests`. The system server had died again (`Failure calling service package: Broken pipe (32)`), even at 4 GB.

So the memory rise was **not** the whole story, and it is worth stating as a correction to the table above rather than as a solved problem:

| | 2 GB | 4 GB |
|---|---|---|
| mass Compose failure | yes — 161 of 180 | no |
| a full 180-test run | never completed | completed once, then failed to repeat |
| `system_server` death | transport, then package service | package service, intermittently |

The honest position: the memory fix removed the **dominant** failure mode, and the emulator is still **less reliable than the phone was**. The completeness check is what makes a verdict from it mean anything, which is why it went in before this round rather than after. `tools/setup-emulator.ps1` still earns its place — a credential-locked phone cannot be prepared from a script at all — but "an emulator removes a dependency" must not be read as "an emulator is reliable".

#### Two more wrapper defects, both found by the emulator failing in ways that exposed them

**A retry could replace a complete result with a partial one, and that is what it did.**

```plain text
attempt 1: tests=180  failures=1     <- complete; its only failure was a harness failure
attempt 2: tests=139  failures=1     <- truncated; system_server died again
RESULT: FAILED - 1 failing test(s) out of 139.
```

The verdict came from `$attempts[-1]`. A retry is permitted only when **every** failure in the first attempt was a harness failure, so the attempt being discarded is precisely the one whose verdict was already known to be uninformative-and-probably-fine — and the one kept was a fragment. **A retry that can degrade the verdict is not a retry; it is a second, worse measurement that hides the first.**

The rule is now: prefer the most recent attempt that ran the **whole** suite, falling back to the last only when none did. "Most recent" rather than "best", because among complete attempts the later one had a second chance to reproduce the failure — which is what the retry was for. `check-wrapper-completeness.ps1` now exercises that selection as well as the verdict, including the exact two-attempt shape above.

**And the same null-dereference the setup script had, in the wrapper.** With `system_server` dead, `adb shell settings get` exits 0 and prints nothing, so `(& $adb shell settings get ...).Trim()` threw *"You cannot call a method on a null-valued expression"* — a message about PowerShell rather than about a device whose framework had died, and it aborted the run before anything was compiled. It now reads through a helper that returns `$null`, and a null timeout is refused with the actual cause:

```plain text
The device is not answering `settings get` - its system_server has most likely died, which happens to an
emulator under memory pressure. Nothing about the app is implicated. Restart it: ...
```

Two occurrences of the same shape, in two scripts, found in one round: **an unguarded read of a device that is not answering produces a PowerShell error that names PowerShell.**

**And a third: the harness failure's own name was being swallowed.** The report printed

```plain text
com.example.notionreader.ui.SavedQueueDetailsAccessibilityTest: tests=12 failures=1 errors=0 skipped=0
                   assertion was reached. This is not a verdict on the app.
```

— a continuation line with no first line. The two are added as a pair, and the first used `Write-Output` while the second used `$attempt.Report +=`. Inside a function, `Write-Output` adds to the function's **output**, which is the same stream the caller assigns `$result` from, so the name never reached the report. It went unnoticed because `$result` became an *array* — the object plus the stray string — and `$result.Report` still worked through PowerShell's member enumeration. Everything looked right except the one line a reader needs, which is **which test flaked**.

The function's own comment, 36 lines above, warns about exactly this. It is now `$attempt.Report +=` for both halves, and a scan of the function confirms no other `Write-Output` remains inside it.

#### Verification

- **534 JVM tests, 0 failures**; **180 instrumented tests**, and the run for this slice completed as a full 180-test run with **`SavedQueueEditModeAccessibilityTest: tests=11 failures=0`** — every new assertion green.

  One failure remained, in `SavedQueueDetailsAccessibilityTest`, and it was **classified as a harness failure** — meaning no assertion was reached, so it is not a verdict on the app. It is the `Activity never becomes requested state` flake from §23.30, now recognised by the mechanism that earlier could not recognise anything.
- Proofs: proof 1 → 7 failures, proof 2 → 2 failures, both restored green, and the script now throws if any proof edit fails to apply.
- `tools/check-classifier-on-real-output.ps1` confirms the classifier on **actual** run output rather than on a fixture: 160 of 161 failures matched a signature and **zero read as empty**. The fixture-based check could not have told us that, because the reason the classifier was dead for as long as it existed is a property of the XML Gradle actually writes. It reads the most recent run's artifacts, so it reports "run the suite first" when there are none rather than passing on an absence — as it did after this round's results directory was cleaned, which is the behaviour wanted.
- `tools/check-wrapper-completeness.ps1` now also proves the attempt selection: a retry cannot replace a complete attempt with a truncated one.

#### What this round cost, and what that says

Three feature slices have now shipped, and each took a fraction of the time that verifying it did. The instrumentation was the work: an emulator to build a script for, four SDK tooling traps, a memory starvation, two screen-wake bugs, a classifier that could never match, a missing completeness check, a retry that could degrade a verdict, three null-dereferences, and a swallowed failure name.

That ratio is worth naming as the project's current bottleneck, and it is why the next experiment is about the **orchestrator** rather than about a feature: `ANDROIDX_TEST_ORCHESTRATOR` starts **one process per test** — 180 process starts on a 4 GB device — and is the most likely proximate cause of the `system_server` deaths that truncate runs. Its original justification, that a transient process death produced reports disagreeing about how many tests ran, is now **handled by the completeness check**. So it is worth measuring whether removing it restores reliability without giving up the safety it provided.

### 23.30 Slice 2 — the Add path, and the increment that makes a queue usable

**Date:** 2026-09-19. `Add to queue` on a Note and `Add all to queue` on Browse, the Queue Destination they open, the de-duplication report, and the create-if-empty path. **512 JVM tests, 0 failures** (was 490; 22 additions) and **168 instrumented tests** (was 153; 15 additions).

#### Why this slice came before Edit mode

§23.28 recorded the ordering change and its reason: **membership enters a Saved Queue through exactly one operation, `Add`.** So until this slice landed, every Saved Queue that could exist was empty, `Play` on one was always unavailable, Edit mode would have had nothing to move, and the specification's own device validation — a queue surviving process death "with its identity, name, **membership** and order intact" — was unperformable. With this slice, the feature is usable end to end for the first time: create a queue, fill it from Browse or a Note, and play it.

#### What shipped

| Piece | Where |
|---|---|
| `beginAddToQueue`, `addPendingToQueue`, `cancelPendingAdd`, `pendingAddCount` | `ReaderApp` |
| `queueReport` and `consumeQueueReport` — the de-duplication report | `ReaderApp` |
| `createSavedQueue` now receives any pending Notes | `ReaderApp` (the create-if-empty path) |
| `QueueDestination`, a new secondary destination | `ReaderDestination` |
| `NoteAddToQueue`, `BrowseAddAllToQueue` | `ReaderFocus` |
| the destination screen | `QueueDestinationScreen` |
| `Add to queue` / `Add all to queue` | `NoteScreen`, `BrowseScreen` |

#### Three design points

**The pending set is application state, not a navigation subject.** Queue Destination is about *a set of Notes to place*, and `ReaderStop.subjectId` carries an identity — a string — because every other destination is about something that already exists and can be named. A pending set is a payload, and encoding a list of Notes into a navigation id would be a second serialization of the domain, in the one place nothing validates it. Holding it in `ReaderApp` also gives it an owner that can end its life, which a navigation stop has not.

**The capture is a copy, and it has a test.** `Add all to queue` captures Browse's *visible result set*, which re-sorts. Capturing a reference would place whatever the list happened to hold when the destination was finally chosen — the same class of defect as handing a live queue to a session, at the other end of the flow.

**Two callbacks, not one.** The first wiring passed a single `onAddToQueue` to both screens, which would have had to guess which was asking. The shell's `when` already knows which surface it is rendering, so the distinction is made at the one place that has both facts.

#### The defect the shell tests caught, and it was a real one

The report was consumed **as soon as it rendered** — `LaunchedEffect(report) { if (report != null) onReportShown() }` — so the sentence appeared for one frame and vanished. Two new end-to-end tests failed on it, and the fix is a lifetime rather than a delay:

| | |
|---|---|
| **Was** | cleared on render, so it could not be read twice |
| **Now** | shown for the whole visit, cleared by a `DisposableEffect` when the listener leaves |

That is a defect and not a test artefact: a listener who cannot see the screen and wants the sentence read again had nothing left to read, and the *whole* reason the report exists is the specification's observation that "a listener who adds twelve things and is told nothing cannot tell 'all twelve were already there' from 'the app failed'". A report that flashes is that same failure with more steps.

`rememberUpdatedState` is used for the disposer, because `DisposableEffect(Unit)` captures its first lambda and a stale reference would clear the wrong thing after any recomposition.

#### Two smaller defects the tests caught

**The all-duplicates sentence repeated itself.** The first version produced "Nothing was added, because every note was already in this queue. 1 was already in this queue." — one fact, twice. The count now belongs *in* the nothing-sentence.

**An empty pending set produced a report.** Reachable by choosing a second destination after the first placement, and it told the listener "nothing was added" about an action they had not taken. The report is now raised only when the operation had an incoming set at all.

#### A tooling bug in this round's own script

`tools/setup-emulator.ps1` crashed with *"You cannot call a method on a null-valued expression"* while waiting for a boot: it called `.Trim()` on the `$null` that `adb shell` returns when a device is listed but not yet answering, and `2>$null` had swallowed the message. The symptom named a method rather than a device state — the same misattribution shape as everything else in §23.29. Fixed by checking for the null, distinguishing `offline`/`unauthorized` from `device`, and keeping the serial the wait loop proved usable rather than re-listing it.

Worth recording separately: the emulator also **degraded** over a long session — one full run took **39 minutes** against 11 minutes for the same suite earlier. Restarting it restored the time. An instrumented round that suddenly takes three times as long is the emulator, not the app.

#### The classifier could never classify anything, and had never been able to

The full run reported one failure, and it was **not an assertion**:

```plain text
java.lang.AssertionError: Activity never becomes requested state "[DESTROYED]"
  (last lifecycle transition = "PAUSED")
    at androidx.test.core.app.ActivityScenario.waitForActivityToBecomeAnyOf(ActivityScenario.java:454)
    at androidx.test.ext.junit.rules.ActivityScenarioRule.after(ActivityScenarioRule.java:117)
```

Thrown from `ActivityScenarioRule.after` — the **teardown** — so the test body had already completed and its assertions had already passed. It also passed 11/11 when the same class was run alone, with no code change between.

That much was straightforward. What was not is **why the runner refused to retry**, and the answer is worth the round on its own.

The first diagnosis was "the signature is missing from the list", and adding it would have been exactly wrong — a change that looks like a fix and changes nothing. The real cause was found by checking whether the classification could work at all, rather than assuming it could:

```plain text
$_.failure.'#text'   -> empty
```

**PowerShell's `[xml]` adapter reduces an `<failure>` element that has no attributes and holds only text to a plain String**, and a String has no `'#text'` property. Gradle writes the attribute-less form. Measured both ways:

| fixture | node type | `'#text'` | message read |
|---|---|---|---|
| `<failure>text</failure>` | `String` | — | **empty** |
| `<failure message="x">text</failure>` | `XmlElement` | present | the text |

So `$text` was always empty, no entry in `$HARNESS_FAILURE_PATTERNS` could ever match, and **the retry mechanism had never once fired** — while appearing entirely present: the array was populated with six entries and their explanations, the loop ran, the branch existed, and the report language ("a green second attempt after a harness failure is a different fact from a green first attempt") described a behaviour that could not occur.

This is the project's own recorded lesson in a new place: **a check that can never match is indistinguishable from a clean run.** It is the same shape as the regex with a Windows path bug that silently matched nothing while two rounds reported success. It survived because nothing asserted that the classifier *could* classify — and the failure of that classifier looks exactly like "this failure is not a harness failure", which is a legitimate answer.

The fix reads the message from whichever shape the adapter returns — `String` or `XmlNode.InnerText` — and is deliberately total, because it runs inside the reporting path where an exception would replace a test verdict with a script error. The teardown signature is on the list as well, classified deliberately: thrown in the rule's `after`, the test body already passed, the Activity reached `PAUSED` which is normal stopping, and it passed 11/11 in isolation.

**Verified by a check that runs the classifier, not by reading it**: `tools/check-harness-signatures.ps1` lifts the wrapper's own `Get-FailureText` and the pattern list out of the script — so it cannot pass against a copy that has drifted — and asserts that both XML shapes read a non-empty message and that the new signature matches it. It also re-finds the two bugs its own first version had, both of which produced a *false* failure: a fixture whose message spanned several lines, and a pattern-extraction regex that matched apostrophes inside the explanatory comments and "found" eleven patterns, six of them sentences.

A retry still cannot launder a real defect: the read is now what it should be, any *mixed* result is still red immediately, and the runner reports the retry's outcome rather than the first's.

#### And then the same round found that a *partial* run could be reported as a pass

The next verification attempt ended with this:

```plain text
Failed to list /sdcard/Android/media/com.example.notionreader/additional_test_output:
  ls: ... Transport endpoint is not connected
Failed to uninstall package com.example.notionreader. Output:
...
HTML report: tests=125 failures=1
```

**The suite contains 168 tests.** The emulator's transport died part-way through, so four classes never ran, one class reported 8 of its 16 tests, and the results file contained only what had finished before the device went away. The run was not a verdict at all — 43 tests are simply absent.

It was reported red only by luck: one of the classes that *did* run happened to fail. **Had that class passed, the runner would have printed `RESULT: PASSED - 125 tests, 0 failures.`** on a run that had exercised three quarters of the suite, with no error, no warning, and nothing at all to notice. That is a wrong answer reported with full confidence — this project's recurring shape — and it is worse than most, because the wrong answer is "all green".

The pass condition was "no failures". Nothing compared how many tests ran against how many should have. The check that closes it sits **before** the verdict:

| | |
|---|---|
| **Was** | `failures > 0` → FAILED, otherwise PASSED |
| **Now** | `failures > 0` → FAILED; `tests < expected` → **INCOMPLETE**; otherwise PASSED |

`$expectedTests` is counted from the `@Test` annotations in the source tree rather than remembered, so adding a test raises the bar automatically and a stale constant cannot become the next silent wrong answer. The word is deliberately **INCOMPLETE** and not FAILED: the missing tests are not failing tests, they did not run, and conflating the two would send the next person looking for a defect in code that was never executed.

Proved rather than assumed, by `tools/check-wrapper-completeness.ps1`, which lifts the wrapper's own count expression out of the script and exercises the verdict across five scenarios including the one that happened:

```plain text
expected tests counted from sources : 168
  tests=168  failures=0  -> PASSED       complete run, 0 failures
  tests=168  failures=3  -> FAILED       complete run, 3 failures
  tests=126  failures=0  -> INCOMPLETE   TRUNCATED run, 0 failures
  tests=126  failures=1  -> FAILED       truncated run, 1 failure
  tests=0    failures=0  -> INCONCLUSIVE nothing ran
```

That third row is the whole point: before this change it read **PASSED**.

Two ordering and scoping bugs were found by running those checks rather than by reading them. `$expectedTests` was first placed beside the signature list near the top of the file, well before `$repoRoot` exists, and crashed with *"Cannot bind argument to parameter 'Path' because it is null"* — a failure that names a parameter rather than a missing variable. And the completeness checker itself invoked the extracted count block with `&`, which runs it **in its own scope**, so the assignment never escaped and the checker reported a failure against a wrapper that was working. That is the third time PowerShell's scoping rule has cost time in this project, after two accumulators built inside functions.

#### The emulator is now the least reliable component, and it is worth saying so

Three verification attempts in a row failed for reasons that were entirely the emulator or the harness:

1. a full run took **39 minutes** against 11 for the same suite (restarting fixed it);
2. the transport died mid-run, truncating the results to 125 of 168 tests;
3. `-Stop` returned before the device disappeared, so the following start saw a stale listing, skipped launching, and then timed out with *"did not finish booting (no emulator listed)"* — two true statements that together read as a broken script.

`-Stop` now waits for the device to actually go, and the completeness check means (2) can no longer masquerade as a pass. But the honest position is that the emulator is a **less trustworthy environment than the phone** was, and the suite's verdicts on it need the completeness check to mean anything. `tools/setup-emulator.ps1` still earns its place — a phone with a credential lock cannot be prepared from a script at all — but "an emulator removes a dependency" should not be read as "an emulator is more reliable".

#### Verification

- **512 JVM tests, 0 failures**; **168 instrumented tests, 0 failures** — every class, with the completeness check confirming the count against the sources:

  ```plain text
  HTML report: tests=168 failures=0
  gradle exit code: 0
  RESULT: PASSED - 168 tests, 0 failures (expected 168).
  Restored screen_off_timeout to 2147483647 ms and stay_on_while_plugged_in to 7.
  ```

  That closing line is new, and it is the point: before this round the runner could not have distinguished this run from the truncated one it also produced.
- The report-lifetime fix was confirmed on the affected class alone (10/10) before the full run, which is what the scoped run exists for.
- `tools/check-wrapper-completeness.ps1` proves a truncated run cannot be reported as a pass, and `tools/check-harness-signatures.ps1` proves the classifier can read a failure message and match a signature — the latter on **both** XML shapes, because the shape was the variable that broke it.

#### Not verified

The emulator is API 36 and the phone is unplugged, so everything in this slice is verified on the emulator only. Nothing here is API-37-dependent, but that is an argument rather than a measurement.

### 23.29 The emulator unblocks the device round, and the focus failures were ours all along

**Date:** 2026-09-19. Two things that had each cost several rounds: the instrumented suite no longer depends on a phone being unlocked, and the seven focus-restore assertions that had been red since §23.12 are **green because the cause was in this project's own code**. **490 JVM tests, 0 failures; 153 instrumented tests, 0 failures.**

#### The device was the bottleneck, and it is now off the critical path

§23.12–§23.17 left every instrumented round dependent on a phone whose screen lock needs a credential typed by hand. That is a real constraint and not a solvable one:

```plain text
$ adb shell locksettings set-disabled true
Lock screen disabled set to true          <- reports success
$ adb shell settings get secure lockscreen.disabled
0                                         <- and writes nothing
```

On a production build (`ro.build.type=user`, `ro.debuggable=0`) with a credential set, that command is a **false success**, and `cmd lock_settings --help` misleads the other way, answering "User has a lock credential, but old credential was not provided", which reads as though the credential gates every operation. Neither is trustworthy; there is no scripted route past a credential, which is what a lock is for.

So `tools/setup-emulator.ps1` was written: it installs the emulator and an API 36 image, creates a `medium_phone` AVD and boots it, and prints what the suite will read from it. `-Check` reports without changing anything and `-Stop` shuts it down. A **green run went from ~15 minutes plus a possible reboot into the PIN to 11 minutes unattended**, and a single class — which is what a diagnosis actually needs — now takes **32 seconds** instead of a device round.

Four tooling traps had to be found first, and all four report the wrong thing:

| Trap | Symptom |
|---|---|
| `sdkmanager` is deprecated **and crashes** on Windows | `--list` prints installed packages, then aborts with `0xC0000409`, so it never shows what is *available* and its exit code is meaningless. `--install` **installed the 4.4 GB image successfully and then returned the same abort code** |
| a package name containing `;` is split by the `.bat` wrapper | "Package android-36 not found" — reads as a bad package name, not as an argument-passing bug. The native `android` CLI takes it whole |
| `Join-Path` double-nests a value that already contains the root segment | a fully-installed image reported as `MISSING`, which would have triggered a pointless 4 GB re-download. **This was a bug in the new script, found by running it** |
| a PowerShell pipeline passed to `adb shell` runs on the *device* | `/system/bin/sh: Select-String: inaccessible or not found` — a message about a missing command rather than about the quoting mistake |

The wrapper also stopped conflating two states that have opposite fixes: `isKeyguardShowing=true` with `inputRestricted=true` is **a credential** (nothing here can help), while the same with `inputRestricted=false` is **the wake/dismiss race** (re-run). One message used to cover both, pointing at retrying when the real state was a secure lock. And its run lock is now taken immediately before the `try` whose `finally` releases it, because taking it earlier meant every preflight refusal **leaked the lock**, so the next run either warned about a stale lock or refused outright claiming another run was in progress — about a run that had already ended.

#### The finding: three rounds of device explanations, and the cause was one missing modifier

All ten failures of the first full run were diagnosed. Three were defects in the new tests (a name never set, a second `setContent` in one test, a no-op `onOpenNote`). **The other seven were the focus failures, and they were ours.**

The chain of explanations, each of which blamed the environment:

| Round | Explanation | Status |
|---|---|---|
| §23.12 | TalkBack holds accessibility focus | superseded |
| §23.17 | Touch exploration | **refuted by experiment** (same failures with it off) |
| §23.17 | A Compose-versus-API-37 interaction | **refuted** |
| §23.24–§23.28 | "A device reason, recorded rather than fixed" | **refuted — the device was never the variable** |

The cause, measured on a clean emulator with `enabled_accessibility_services=null` and `touch_exploration_enabled=0`: **`Modifier.clickable` does not give a `FocusRequester` a target the request can be granted against.**

| chain | granted | reports `Focused` | semantics nodes |
|---|---|---|---|
| `focusRequester + clickable` | **no** | no | 1 |
| `clickable + focusRequester` (order swapped) | **no** | no | 1 |
| `focusRequester + focusTarget + clickable` | yes | **no** | 1 |
| `focusRequester + focusable + clickable` | yes | **yes** | 1 |
| `focusRequester + clickable + focusable` | yes | no | 1 |

`restoreFocusOn` now returns `focusRequester(requester).focusable()`, placed before `clickable`. `focusable()` **before** `clickable` on both counts: `focusTarget()` is granted and takes focus but the node does not *report* being focused, so nothing can observe where focus went, and `focusable()` after `clickable` behaves the same way.

Two things this measurement changed about what was about to be written down:

- **`focusTarget()` was nearly justified on a false premise.** The reason it looked safer was that `focusable()` "would add a second focus stop to an already-clickable row", which the last column refutes — one semantics node either way.
- **`FOCUS_ATTEMPT_FRAMES = 8` was a fix for the wrong problem.** It was added because a refusal that neither threw nor was granted was read as a *timing* failure, and the response was to retry across frames. Setting it to `1` passes all 16 shell tests, including the `LazyColumn` case, so the retry is a bound rather than a requirement. It is kept and documented as such, because it cannot mask a failure — it returns as soon as a request succeeds, and a request that never succeeds leaves focus unrestored either way.

The lesson, and it is the third time this class has cost a round here: **`requestFocus()` returning `false` is a definite answer, not an ambiguous one.** The right question was "what is this request being granted *against*", not "when should it be retried". An emulator turns that question into a 32-second experiment, which is why the setup script is as much a part of this finding as the fix is.

#### One claim in the record was also wrong

`connectedDebugAndroidTest` "may exit non-zero when every test passed" has been repeated since §17 and is in `AGENTS.md`. On this run it **exited 0 with 153 tests passing**. That note is now qualified to the device it was observed on rather than stated as a general property, because a future reader would otherwise distrust a correct signal.

#### Verification

- **490 JVM tests, 0 failures**; **153 instrumented tests, 0 failures**, including all seven focus assertions that had been red since §23.12.
- The instrumentation is reproducible: the failure reproduces in 32 seconds scoped to one class, which is how the modifier matrix was obtained rather than reasoned about.
- **Not verified on the phone**: it was unplugged after the first full run, so the fix is confirmed on the emulator only. The three test bugs and the focus fix are both device-independent, but that is an argument rather than a measurement, and it is recorded as one.

#### Files

`tools/setup-emulator.ps1` (new), `tools/run-accessibility-tests.ps1` (lock placement, the two refusals, stale explanations), `FocusRestore.kt` (the fix), `FocusRestoreTest.kt` (was an investigation, now the regression guard for the fix), `AGENTS.md`, `README.md`.

### 23.28 Slice 1 — the Saved tab, Saved Queue Details, `Play` and `Create saved queue`

**Date:** 2026-09-19. The first surface for Saved Queues, and the first navigation destination that has to be told *which* one. **490 JVM tests, 0 failures** (was 461; 29 additions) and **151 instrumented tests** (was 108; 43 additions).

#### The ordering the specification's phases imply, and why it was changed

Before starting Phase 4, reading the six operations against the eight phases produced a fact visible from neither alone, and it changed the order this work is taken in.

**Membership enters a Saved Queue by exactly one operation: `Add`.** `Create` produces an empty queue by design; `Rename` changes a label; `Remove`, `Move` and `Delete` only modify membership that already exists. And `Add` is reachable only through Queue Destination, which is Phase 7. So **until Phase 7 lands, every Saved Queue that can exist is empty** — which means Phases 4, 5 and 6 produce surfaces that cannot be populated by hand at all:

- Details in Viewing always lists nothing;
- `Play` is always disabled, because the specification says it is not offered on an empty queue;
- Edit mode's four moves and `Remove` have nothing to move;
- and the specification's own device validation — "a saved queue survives a process death … with its identity, name, **membership** and order intact" — cannot be performed, because no queue can have membership.

That is not an argument against those phases; they are prerequisites, and they are verifiable against a seeded store. It is an argument that the specification's order optimises for **technical** dependency (read before write) rather than for a usable increment. Every phase from 4 to 8 adds surface, and only 7 makes the feature usable.

So two things were changed, by explicit decision rather than by drift:

| Change | Reason |
|---|---|
| **`Create` moves from Phase 6 into Phase 4** | The Saved tab's own contract line is `Create saved queue`, and the empty state's only route out is that control. Shipping the tab without it is a screen whose one documented action is missing — the empty-destination objection §12.5 already resolved twice. It is also the smallest operation (no confirmation, nothing destructive), and it lets the tab's instrumented tests build state through the UI rather than only by seeding a store. |
| **Phase 7 moves ahead of Phases 5 and 6** | Otherwise three consecutive slices have seeded-store tests as their only verification. With Add second, slices 1 and 2 together make the feature usable and device-validatable, and 3–5 become refinements on something real. |

The phases' **contents** are unchanged; only the order is. `README.md` now states the one visible consequence — *a Saved Queue can be created but not filled* — rather than leaving a reader to infer it from an absent control.

#### What shipped

| Piece | Where |
|---|---|
| the store seam, the coordinator, the published list, `createSavedQueue`, `playSavedQueue` | `ReaderApp` |
| `SavedQueueCard` and its projections — the ordering, and how a count is spoken | `application` |
| `SavedQueueDetails` and `CreateSavedQueue`, the first two secondary destinations added since the shell | `ReaderDestination` |
| `ReaderStop.subjectId` — "what this screen is about", as distinct from "what to focus on return" | `ReaderNavigator` |
| the tab host, and the Listening body split out of it | `QueuesScreen`, `ListeningTab`, `SavedTab` |
| the two new screens, and one read of the store per process | `SavedQueueDetailsScreen`, `CreateSavedQueueScreen`, `ReaderHost.readSavedQueues` |

`QueueOrigin.Saved(queueId, name)` now has a constructor, which closes the gap §23.27 left open by adding the identity and recording that nothing read it.

#### Three design points worth recording

**One publishing rule, and it is the same rule as the no-op rule.** An operation publishes `QueueEditOutcome.queues` **only when it changed something**:

```kotlin
if (outcome.changed) _savedQueues.value = outcome.queues
```

The non-obvious half is that this also makes an unreadable store safe. `QueueEditOutcome.unavailable()` is `changed == false` with an **empty** `queues` — that empty list is what a store that could not be read looks like, and publishing it would blank a list the listener can see rather than a file they cannot. So the condition that handles a no-op handles the failure too, and there is no second condition to remember. §23.26 documented the caveat; this is the file where it would have been violated.

**The first destination that has to be told which one it is.** Every destination before this was addressed by nothing (Home, Browse, Queues), by what is being played (Player), or by a page id it was opened for (Note). Saved Queue Details needs a *queue*, and the navigation model had no place to put one. `ReaderStop` gained `subjectId`, kept separate from `fromFocusId` — and they must not be one field even though they coincide on the one route that exists today, because the routes the contract already names separate them: Home's *Your queues* opens Details **about a queue** while returning focus to a *Home row*, and Queue Destination's `Create new queue` pushes a screen about nothing at all. This is the change `ReaderNavigator`'s own documentation flagged as due when a two-deep flow arrived.

**Queues becomes two tabs, and the ordinal changes.** TalkBack now announces "1 of 2" where it announced "1 of 1", and where the contract's example says "1 of 3". The ordinal counts the tabs that exist, and the third arrives with the tab it counts. `Smart` remains the recorded deferral rather than a shipped-but-disabled tab, because a permanently unusable control one tap from two populated surfaces is the objection §18.4 already resolved. The selected tab is `rememberSaveable`, which is what satisfies the contract's "Queues should retain: selected top tab" across both destination switches and process restart — and what is saved is the tab's **name** rather than its ordinal, so a third tab added in the middle cannot silently select a different one on restore.

#### A defect found in the verification tooling, while trying to verify

The instrumented suite could not run: the device was locked, and the wrapper refused — correctly, because a secure lock needs its owner's PIN and the script deliberately never handles a credential. But the refusal **left the run lock behind**, because the lock was acquired next to the device preflight while the `finally` that releases it belongs to a `try` that begins *after* it. Every preflight refusal therefore leaked a lock file, and the next run found a pid that either no longer existed or had been reused by an unrelated process — giving either a stale-lock warning or a flat refusal claiming another run was in progress, about a run that had already ended. That is this project's recurring failure shape: a wrong answer, reported with full confidence.

The fix is placement rather than a guard: the lock is now taken immediately before the `try`, which is also its honest scope, since the resources it protects — the test-results directory and the device settings raised on the next line — are touched only inside that block. Proved by running the wrapper with no lock present and confirming that a refusal creates none and leaves `screen_off_timeout` untouched.

#### Failing-first proofs

Four guards, each removed alone, each noticed by **exactly one** named test, and each restored from a file backup rather than re-edited. 490 tests throughout, so no removal changed the count.

| Guard removed | Test that noticed |
|---|---|
| publishing checked against `changed` | `anUnreadableStorePublishesNoQueuesAndLeavesThePublishedListAlone` |
| `isPlayable = members.isNotEmpty()` | `anEmptyQueueIsNotPlayableAndAPopulatedOneIs` |
| the singular member count | `oneNoteIsSpokenInTheSingular` |
| `sortedByDescending { it.lastEditedAt }` | `theListsMostRecentlyEditedQueueFirst` |

The first is the one that matters: it is the only test in the suite that can see the list-blanking defect, because it is the only one where a read fails while a list is already published.

#### What is deliberately not here, and what that costs

- **No Edit mode**, so no `Move`, `Remove`, `Done` or `Edit queue` — and the instrumented tests assert their *absence* rather than their being disabled, because the contract's objection is to a screen a screen-reader listener cannot traverse, and a disabled control is still traversed.
- **No `Rename` or `Delete`**, so Details renders with one control rather than the three the contract's Viewing state eventually lists. The screen is built so that their arrival is a branch rather than a flag.
- **A queue that is gone renders its own state**, offering nothing to repair — per the specification's rule for a session whose source queue was deleted. It is reachable, because the list underneath is not frozen and a store that could not be read reports no queues.
- **`ReaderViewModel.createSavedQueue`'s "leave only when a queue was created" branch has no test of the ViewModel**, which is an `AndroidViewModel` and has no JVM test in this project. It is covered by construction — the instrumented shell test wires a callback of the same shape — and the *refusal* path is unreachable through the UI at all, because the screen disables `Create` on a blank name. That makes the branch defence in depth rather than a behaviour a test can reach, which is worth stating rather than implying it is verified.

### 23.17 The touch-exploration explanation is wrong, tested rather than assumed

**Date:** 2026-09-18. §23.12 blamed the seven focus failures on the device's accessibility configuration:
"TalkBack is running **with touch exploration on**, and its braille keyboard is the active input method.
Under touch exploration, accessibility focus belongs to TalkBack, and an input-focus request against a node
that is not focusable in the Android sense is declined."

That was a plausible reading of a measured fact and it was **never tested**. It has now been tested, and it
is wrong.

**The experiment.** `tools/run-accessibility-tests.ps1 -DisableTouchExploration` sets
`touch_exploration_enabled=0` for the duration and restores it in the `finally`, exactly as it already does
for `screen_off_timeout`. The wrapper now prints the device's accessibility configuration on every run, so a
focus failure is attributable without re-deriving it.

**The result: the same three tests fail, unchanged.**

```plain text
Device touch_exploration_enabled=1; enabled_accessibility_services=...TalkBackService
Disabled touch exploration for this run: the focus-restore assertions are being made against a device
that is not intercepting programmatic focus.
com.example.notionreader.ui.FocusRestoreTest: tests=4 failures=3
  FAILED: theSemanticsFocusActionIsGrantedInstead
  FAILED: aPlainRequesterGrantedFromOutsideTheCompositionWorks
  FAILED: aRequestFromALaunchedEffectWorks
```

**What survives, and what does not:**

| Claim | Status |
|---|---|
| The window is focused; the refusal is not about window focus | **confirmed** — `requestFocus()` returned `false` while `hasWindowFocus()` returned `true` |
| A `focusable()` node is granted where a `clickable` one is refused | **confirmed** — `anExplicitFocusableProducesAWorkingTarget` passes in the same run |
| **Touch exploration is the cause** | **refuted** — identical failures with it off |
| TalkBack being enabled is the cause | **not established** — TalkBack was still enabled; only touch exploration was changed |

So the honest position is narrower and better than either previous statement: **`FocusRequester` is refused
on a `clickable` node on this device, and granted on a `focusable()` one, and that is independent of touch
exploration.** What is left as the difference between the two mechanisms is a Compose-versus-API-37
interaction, not a device accessibility setting — and the app's rows are `clickable`, which is why the seven
assertions fail.

**This does not reopen D34/D35.** The decision was to keep the app inclusive, not to redesign the mechanism
around TalkBack, and it did not depend on the cause being touch exploration. What changes is that the
*record* no longer states a cause that a test disproves — a wrong explanation in a decision record is worse
than none, because the next round would have started from it.

**Two fixes to the runner, both from failures this round produced in itself.**

The first is a guard against **concurrent runs**. A run was piped into `Select-Object -First N` — the
truncation trap `AGENTS.md` warns about — which closed the pipeline and killed the run part-way; a second run
was then started while the first was still tearing down. Both write to the same
`app/build/outputs/androidTest-results/connected/debug/` directory, and the second died with

```plain text
java.nio.file.FileSystemException: ...\logcat-...FocusRestoreTest-....txt:
The process cannot access the file because it is being used by another process
```

which names a **file** rather than a cause and reads as a Gradle or disk fault. The wrapper now takes a lock
file holding its own pid and refuses to start while another run holds it — and identifies a **stale** lock
(whose process is gone) and takes it over, because a killed run must not block every later run forever. That
is the same shape as every other fix in this section: a symptom that named the wrong thing.

The second is a **verified** restore. The killed run never reached its `finally`, so the device kept the
raised `screen_off_timeout`, and the next run then captured that raised value as its "original" — reporting a
successful restore to a value that was itself the damage. The `finally` now re-reads each setting and
**warns with the manual command** if the device does not match what was intended. A restore that is only
announced is not a restore.

---

## 24. What the instrumented suite actually costs, and the five ways to make it cheaper

**Date:** 2026-09-19. A question about testing cost, answered from measurement and from the official docs
(`android docs search` / `android docs fetch`) rather than from recollection.

### 24.1 The measurement, and it is not close

| | Tests | Test time | Per test |
|---|---|---|---|
| **Instrumented** | 180 | **873 s** (14.6 min) | **4,700 ms** |
| **JVM** (`--no-build-cache`) | 534 | **0.8 s** | **~1.5 ms** |

The instrumented suite is **~3,000× more expensive per test**, and the cost is **flat across every class** —
4.4 s for `HomeScreenAccessibilityTest`'s 9 tests, 5.6 s for `ReaderShellSavedQueueTest`'s 10, and 4.4 s for
`ReaderShellQueueBackTest`'s **2**. A uniform cost regardless of what the test does is the signature of
**fixed per-test setup** — Activity launch, instrumentation, Compose idle, teardown — rather than of the
assertions, which are semantics-tree reads and take milliseconds.

So roughly 99% of 15 minutes is spent paying for a device in order to assert properties of a tree that does
not need one. That framing is what makes the options separable, because it tells you which ones attack the
actual cost.

### 24.2 The five options, with what the documentation says about each

**1. Move the UI tests to Robolectric on the JVM — the only option that attacks the actual cost.**

The docs are unusually direct, and the recommendation matches this suite's shape almost word for word:

> Robolectric … lets you run tests in a simulated Android environment inside a JVM, without the overhead and
> flakiness of an emulator … Many big projects use Robolectric to increase the speed and reliability of their
> tests and reduce the expenses associated with running tests on real devices or emulators.
> — `kb://android/training/testing/local-tests/robolectric`

> **Use Robolectric for isolated UI behavior tests** for components, feature or application tests. In general
> these tests check the state management and behavior of the UIs and don't interact with external
> dependencies.
> — same page, on choosing between Robolectric and device tests

> …when a Compose test verifies that the UI has changed after clicking a button. You can run behavior tests on
> Robolectric as the fidelity needed for it is more than enough.
> — same page

That is what this suite is: 15 of 15 classes render a screen (or the shell) with state passed in, and the
shell tests use in-memory doubles built by `ShellHarness` precisely so that nothing external is involved. The
setup is two lines — `unitTests { isIncludeAndroidResources = true }` and
`testImplementation("org.robolectric:robolectric:4.13")` — and a UI test in the `test` source set runs under
Robolectric automatically.

**2. ATD images (Automated Test Device). Not available here, and worth saying so plainly.**
ATD images are headless and cut CPU and memory use, which is exactly the shape of this problem:
> [ATD] … is optimized to reduce CPU and memory resources when running your instrumented tests.
> — `kb://android/studio/test/managed-devices`

But the same page states the constraint three times: **"ATDs currently support only API level 30."** This
project's emulator is API 36 and the real target is API 37. So ATD is a dead end here, and recording that is
worth more than the options that are live, because "use an ATD image" is the recommendation an agent will
reach for first and then spend time discovering it cannot apply.

**3. Test sharding — parallel devices; multiplies the bill, does not reduce it.**
> …split your test suite across a number of identical virtual device instances, called *shards*, that run in
> parallel … **Warning:** Depending on the number of shards you specify, enabling this feature can be resource
> intensive. If Gradle is unable to provision the devices requested, the test run might fail with a timeout
> error.
> — `kb://android/studio/test/managed-devices`

Sharding divides **wall clock**, not cost, and it needs N devices alive at once. This machine starved a
**single** 2 GB AVD until the low-memory killer took `system_server` (§23.31), and the deaths remain at 4 GB
(§23.32). Running two or four instances to shorten a 15-minute run would trade a known cost for the exact
failure this suite has spent two rounds learning to characterise. **Rejected on measured grounds, not on
taste.**

**4. Build-managed devices — good properties, wrong round.**
> Build-managed devices improve consistency, performance, and reliability … uses emulator snapshots to improve
> device startup time and memory usage and restore devices to a clean state between tests … **Caches test
> results and reruns only tests that are likely to provide different results**
> — `kb://android/studio/test/managed-devices`

The snapshot restore and result caching are genuinely attractive, and this is the option to revisit **after**
option 1, not instead of it. Two reasons to sequence it second: it moves device lifecycle into Gradle, which
is a real change to how the project is run and worth doing once the suite is small enough to be worth
managing; and its caching benefit lands on *re-runs*, so it short-circuits the inner loop without fixing the
outer one.

**5. Parallelise the JVM suite — free, and not yet done.**
`maxParallelForks` is **unset**, so the 534 JVM tests already run in a single fork. This is not a fix for
today's problem (the JVM suite is 3 seconds) but it is the multiplier that makes option 1 pay off more: after
the move, ~160 additional JVM tests across the machine's cores cost a fraction of what they would serially.

### 24.3 The recommendation, and the reason it is a spike rather than a decision

**Move the 12 pure-UI classes to Robolectric, and leave on the device only what needs a device.**

| Stay on device | Why |
|---|---|
| `FocusRestoreTest` (5) | It asserts the focus *mechanism* against a real window. §23.29 established that the failure it guards is a property of the real Compose/Android interaction, and a simulated window is precisely the fidelity Robolectric does not promise. |
| `ReaderShellQueueBackTest` (2), `ReaderShellSavedQueueTest` (10) | Real `BackHandler`/Activity back-dispatch. |
| A thin device smoke test | That the app still starts and renders on a real Android — the one claim a JVM cannot make. |

| Move to the JVM | Why it is safe |
|---|---|
| The 12 screen/shell classes (~163 tests) | Each renders state passed in and asserts the semantics tree. That is the documented sweet spot. |

Expected shape: **~15 device tests (~1.5 min) + ~163 JVM tests (seconds)**, replacing 180 device tests
(14.6 min) — and the device suite small enough that a `system_server` death costs a re-run of a minute rather
than fifteen.

**But this is a spike before it is a decision**, because there is one honest risk and it is not small:
**Robolectric does not render.** The docs say it "doesn't have a screen like an emulator does", and this
suite's helper asserts *geometry* — `boundsInRoot`, `isPlaced`, and overlap with the surface root. Those
may be degenerate without a real layout pass, and a geometry assertion that silently always passes is
**exactly the failure mode §23.30–§23.32 spent three rounds on**: a check that cannot see its subject and
returns a reassuring number.

So the spike's acceptance criteria are about *detecting* that, not about the suite going green:

1. Move **one** class — `SavedQueueEditModeAccessibilityTest`, because it is the one that already found a real
   defect (`Remove` clipped to zero area) and whose assertions therefore have proven discriminating power.
2. Prove the geometry assertions still discriminate on the JVM: **re-clip `Remove` into a `Row` and confirm
   the moved tests fail there too.** A green run proves nothing; the regression must still be caught.
3. If the geometry assertions do not discriminate under Robolectric, split `A11y` rather than abandoning the
   move: keep the **tree facts** (`isPlaced`, non-blank name, an `OnClick` action, reading order — all
   semantics-only) on the JVM, and leave the **geometry facts** (`overlapsSurface`, non-zero area) to the
   device. §23.31 already found that "occupies area" and "overlaps the surface" are the two of
   `assertIsReachable`'s four checks demonstrated non-deterministic on a real device, while the other three
   are facts about the app — which is the same split, arrived at from the other direction.

### 24.4 Verification

- Instrumented baseline measured: **873 s of test time over 180 tests (4.7 s each), flat across all 15
  classes**, against **0.8 s over 534 JVM tests**.
- Run 3 without the orchestrator: **180/180, 0 failures, 15.0 min, Gradle exit 0.**
- `maxParallelForks` confirmed unset; `isIncludeAndroidResources` confirmed absent.
- ATD ruled out against the documented "API level 30" constraint; sharding rejected as resource-multiplying
  on a machine that starved at 2 GB and still dies at 4 GB.
- Doc sources: `kb://android/training/testing/local-tests/robolectric`,
  `kb://android/studio/test/managed-devices`, `kb://android/develop/ui/compose/testing/index`.

---

## 25. The instrumented suite's real cost is the emulator's own load, and four defects the investigation found

**Date:** 2026-09-19. A follow-up to §24, which established that the instrumented suite costs ~4.7 s per test
against ~1.5 ms on the JVM. This round asked where inside that 4.7 s the time goes, and found that almost all
of it is the *device*, not the test.

### 25.1 What was measured, and the doc-sourced options that were eliminated first

Four candidate levers were checked against the official documentation before any experiment, and three are
recorded as dead ends so they are not re-investigated:

| Lever | Verdict | The documented reason |
|---|---|---|
| ATD (Automated Test Device) images | **Dead end** | "ATDs currently support only API level 30" — this project is API 36, targeting 37 (`kb://android/studio/test/managed-devices`) |
| Test sharding | **Rejected** | Divides wall clock, not cost; needs N live devices; the docs warn it is "resource intensive", and this machine starved a single 2 GB AVD until `system_server` died (§23.31) |
| Animation scales | **Tested: no effect** | `mainClock` is **virtual** in Compose tests, so `advanceTimeBy` is not wall clock (`kb://android/develop/ui/compose/animation/testing`) |
| Emulator GPU mode | **Already optimal** | The AVD's `hw.gpu.mode=auto` resolved to the host GPU — `GLES: … NVIDIA GeForce RTX 5060`, not SwiftShader |

The animation case is worth its own line because it is the recommendation everyone reaches for, and it was
**measured rather than assumed**. Three runs of an 11-test class with all three scales set to `0.0`:

| | Per test |
|---|---|
| Animations on (1.0) | 4.79, 4.38, 4.32 s → mean **4.50 s** |
| Animations off (0.0) | 5.49, 3.57, 4.51 s → mean **4.52 s** |

**No effect.** That is what the virtual-clock finding predicts, and it is why the doc was read first: the
experiment cost nothing because the answer was already written down. The spread inside each group (±1 s) is
larger than the difference between them, which is itself the next clue.

### 25.2 The finding: the emulator's load, not the test

The spread in those numbers pointed at the *device* rather than the tests. Measuring it directly, the same
11-test class on the same emulator:

| Emulator state | Load average | Per test |
|---|---|---|
| Just restarted | **0.06** | **1.03, 1.03**, 1.96 s |
| After a period of use | **10.34** | 4.79, 4.38, 4.32 s |

**A 4.3× difference in per-test cost, for byte-identical tests, decided by how long the emulator had been
running.** And `system_server` was measured at **108% CPU while the device was completely idle**, with only
448 MB of 4 GB free — a device burning a whole core doing nothing.

The same effect on the **whole suite**, which is the number that matters:

| | Test time | Per test | Wall clock | Verdict |
|---|---|---|---|---|
| Aged device | 873 s | 4.85 s | **14.6 min** | 180/180, 0 failures |
| Fresh boot | **522 s** | **2.90 s** | **9.0 min** | 180/180, 0 failures |

**1.6× on the full suite from a restart alone**, with no configuration change, no code change, and the same
tests passing either way. And that 9.0 min is a **floor rather than a ceiling**: the run started at load
**5.88** — left over from the three 11-test runs that preceded it — not at 0.06, and it finished at 11.59. The
measured fresh rate was **1.03 s per test**, so a run that did not degrade would take about **3 minutes**, and
the 2.90 s average is the degradation dragging the later tests upward.

The load grows *within* a session, not just between them. Three consecutive 11-test runs took it from **0.06 to
6.39**, and the full 180-test run pushed it from 5.88 to **11.59** — so a full suite cannot be run cheaply by
restarting once and running it repeatedly. It is a restart per full run, not a restart per session.

**This is the answer to "why is the suite slow", and it is not the tests.** It also explains why §24's
`per class` costs looked so uniform: the cost is not a property of any class, it is a property of *how many
tests have already run against this device*.

### 25.3 The negative result that matters: it is not the app leaking

Sixteen runnable tasks on four vCPUs has an obvious suspect in a test suite — leaked processes. Checked
directly, mid-run, at load 16:

```plain text
process count                    : 379   (unchanged from idle)
com.example.notionreader         : 1     (the current test's app process)
com.example.notionreader.test    : 1     (the current test's instrumentation)
```

**One app process and one test process, exactly as there should be.** There is no accumulation, so this is
**not** an app lifecycle defect and nothing in the app needs fixing for it. That distinction matters: the
symptom was read as a plausible app leak, and had it been one, the fix would have been in production code
rather than in how the suite is run.

### 25.4 Four defects the investigation found, all in the runner

Investigating the timing required using `-Class` repeatedly, and that is what exposed them. Each is the same
family as §23.30–§23.32: **a report that could not describe the thing it was reporting on.**

**1. `-Class` reported every filtered run as `INCOMPLETE`, and exited 1.** The completeness bar was the
whole-suite `@Test` count, so a deliberately-filtered run could never reach it:

```plain text
pwsh -File tools\run-accessibility-tests.ps1 -Class ...SavedQueueEditModeAccessibilityTest
RESULT: INCOMPLETE - only 11 of 180 tests ran, with 0 failures.
```

Eleven tests ran and all eleven passed. **`AGENTS.md` and the verification skill both recommend `-Class` as the
way to iterate, so the documented workflow and the tool disagreed and the tool was wrong** about a run that had
done exactly what it was asked. The bar is now counted from the sources for the filtered classes, and it
accepts a comma-separated list — which is also how one *finds* that a comma-separated list is not supported by
the underlying runner property, since a filtered run of four classes silently ran one.

**2. `-f` binds tighter than `+`, so a warning could not name the attempt it was about.** The message ended
`'…about why.' -f (...)`, which applied the format to the final string fragment — one with no `{0}` in it — and
then concatenated. It printed `Attempt(s) {0} ran only part of the suite`. The format was a no-op on a string
that was then assembled, which reads correctly on the page and fails silently.

**3. The verdict fell back to the *last* attempt even when it had read less than an earlier one.** With no
attempt complete, the rule was `$attempts[-1]`:

```plain text
Attempt 1: 7 test(s), 2 failure(s) - INCOMPLETE    <- both failures classified as the harness
Attempt 2: 0 test(s), 0 failure(s) - INCOMPLETE    <- install failed; the device had died
Verdict comes from attempt 2 of 2.
RESULT: INCONCLUSIVE - no test counts were readable from either report.
```

Attempt 1 had done all the useful work and named both failures; the fallback discarded it and reported
`INCONCLUSIVE`, which reads as "this run told us nothing" when it had in fact told us quite a lot. The rule is
now: prefer the most recent **complete** attempt, else the one that read the **most** tests, else the last.

**4. The restore path threw a PowerShell null-dereference when the device had died.** `system_server` died
during collection, and `(& $adb shell settings get …).Trim()` then threw *"You cannot call a method on a
null-valued expression"* at line 988 — **replacing the run's verdict with a script error**, and hiding the fact
that the suite had already produced a usable result. This is the third instance of the same shape found in this
repository (an unguarded read of a device that is not answering produces an error naming PowerShell). The
restore now reads through a `Get-DeviceSetting` helper that treats three shapes as "no answer" — empty output,
`cmd: Can't find service: …`, and the literal `null` — and says the settings were not verified rather than
throwing.

### 25.5 A flake that is real, and is being recorded rather than chased

`SavedQueueEditModeAccessibilityTest.backInViewingIsNotConsumedByThisScreen` failed once in four runs:

```plain text
FAILED: backInViewingIsNotConsumedByThisScreen
    java.lang.AssertionError: Failed to assert count of nodes.
    Reason: Did not expect any node but found '1' node that satisfies: (… contains 'Edit queue' …)
```

The assertion is about Back **leaving** the screen when Back is not in edit mode. The observable is that
`Edit queue` (the Viewing control) is gone. A back-pressure race explains it: with the device at load 15, the
node can still be present when the assertion runs. Recorded as **real but only ~25% reproducible in four runs
at high load**, and explicitly not treated as evidence about the app — §23.29's lesson is that "it is the
environment" is the hypothesis an agent reaches for first, so it needs a rate rather than an assertion. Worth
re-testing on a fresh device, where §25.2 predicts it will not reproduce.

### 25.6 What to do, in order of measured value

1. **Restart the emulator before a full run.** **14.6 min → 9.0 min** (1.6×) on the full suite, free, and worth
   more than every flag in the documentation combined — and 4.3× on per-test cost for a class run against a
   truly fresh device. `tools/setup-emulator.ps1 -Stop` then run the wrapper (which boots if needed). Do not
   try to buy this by restarting once and reusing the device: the load climbs within a session, so it is a
   restart per full run.
2. **Use `-Class` while iterating** — now that it works, one class is ~20 s on a fresh device and ~70 s on a
   stale one.
3. **Move the pure-UI classes to Robolectric** (§24.3). Still the structural fix: it removes the device from
   the loop entirely for ~163 of 180 tests, which makes the emulator's load irrelevant for them. The freshness
   finding raises its value rather than replacing it — this round explains *why* removing the device is worth
   so much, and adds "a suite of 15 device tests cannot accumulate much load" to the argument.

**Explicitly not done:** giving the emulator more vCPUs. The AVD has 4 of the host's 24 cores and the guest load
reaches 16, so `hw.cpu.ncore` is the obvious next lever and it is **untested** — recorded as the next
experiment rather than claimed.

### 25.7 Verification

- Full suite on a **fresh boot**: **180/180, 0 failures, 9.0 min** (522 s of test time, 2.90 s per test),
  Gradle exit 0 — against **14.6 min** (873 s, 4.85 s per test) on the aged device.
- Per-test cost, same class, same emulator: **1.03 s immediately after a restart** (load 0.06) against
  **4.79/4.38/4.32 s after use** (load 10.34).
- Load trajectory: 0.06 → 6.39 over three 11-test runs; 5.88 → 11.59 across one full run.
- Animation scales: **no effect** (4.50 s mean on, 4.52 s mean off, 3 runs each).
- Renderer confirmed host-accelerated (`GLES: … NVIDIA GeForce RTX 5060`), not SwiftShader.
- No process accumulation at load 16: 379 processes, 1 app process, 1 test process.
- `-Class` now reports **PASSED - 11 tests, 0 failures (expected 11)** where it previously reported
  `INCOMPLETE - only 11 of 180`.
- `tools/check-wrapper-completeness.ps1` **now lifts the attempt-selection logic out of the wrapper by regex**
  instead of reimplementing it, so it cannot pass against a drifted copy — and it covers the new
  no-complete-attempt case. Proven failing-first: with the fix removed it throws at line 179 naming the
  incorrect choice; restored, it passes. (Its own first `throw` message printed a literal `{0}` for the same
  `-f` precedence reason as defect 2 — the same mistake, made again in the code written to test it.)
- `tools/check-harness-signatures.ps1` and `tools/check-empty-failure-is-classified.ps1` still pass.
- **Not measured, and stated as such:** `hw.cpu.ncore` (4 of the host's 24) was not varied, so the vCPU lever
  is an untested hypothesis rather than a recommendation. The fresh rate of 1.03 s per test was measured on an
  11-test class, so the ~3-minute projection for a non-degrading full run is arithmetic from that rate, not an
  observation.

---

## Still open

- **The TalkBack listening gate** (§11, §14.6). The oldest outstanding item on the project, and the only
  acceptance criterion no test can close: the integration spec's gate is a complete playback and
  navigation flow with TalkBack enabled, and the risk it carries is unique — whether TalkBack's speech and
  the reader's TTS overlap in practice. It needs ears, not code, and enabling it changes the device's touch
  behaviour globally, so it is the user's call.
- **All three Stage 0 documents are written** (§23.5): **0a** and **0b** in §23.8–§23.9, and **0c** — Queue
  Creation & Queue Management — in **§23.24** as `docs/queue-creation-and-queue-management-spec.md`. Nothing is
  now blocked on a document except Smart Queues, which need the still-unwritten Content Metadata & Workspace
  Schema Adapters spec as well (D30), and Browse's facets and Home's Explore, which need the same one.
- **Saved Queue implementation is under way and is the current queue work.** Phases 1–3 are done
  (§23.25–§23.27): the record, codec and store; the six operations as one pure model; and the
  identity-carrying `QueueOrigin` with its `SESSION_SCHEMA_VERSION` bump. §23.28 adds the first surface —
  the Saved tab, Details in Viewing, `Play` and `Create saved queue` — and records the slice order the
  remaining phases are taken in, which is **Add next** (spec Phase 7), then Edit mode, then Rename/Delete,
  then Home's *Your queues*. The reason is in §23.28: membership enters a queue only through `Add`, so until
  that lands every Saved Queue is empty and `Play` is always unavailable.
- **Stage 2 (background playback) is stopped on a diagnosis, not a decision**: §23.22–§23.23 could not
  establish whether the service start is attempted, because our log output does not reach `logcat -d` on this
  device — most likely a saturated ring buffer. The next attempt needs a **live capture**, with `logcat`
  running before the launch. The launch-playing observation from §23.22 is likewise still unexplained and
  still recorded as an observation rather than a defect.
- **The `probe-background-audio.ps1` tap sequence is order-fragile**: it taps `Browse` then `Play all`, but
  Browse's results load over the network, so `Play all` may not exist yet. Fix before relying on it.
- **The session spec's live-region wording cannot be amended from this repository** (D33). It is a Notion
  mirror, so the amendment §23.8 raised and §23.9 depends on still has to be made in Notion and
  re-exported. 0b records that it relies on the adopted reading rather than assuming the wording is settled.
- **The TalkBack overlap is now a measurement that can change a rule.** §23.9 lists three interactions — two
  TTS clients, TalkBack's speech possibly arriving as an audio focus change that would pause the reader on
  every utterance, and the read-aloud media notification — and deliberately leaves the resolution to device
  evidence. It is the first concrete narrowing of §11's gate in several rounds.
- **The keepScreenOn assertion must be inverted, not deleted**, when §23.9's Phase 5 removes the Player's
  display wake-lock. It guards a real property, so its replacement guards the opposite one.
- **The focus-restore assertions cannot currently be made on this device** (§23.12). Seven instrumented
  failures, all focus, all pre-dating §23.11 and proven so against a clean tree. The measured cause is the
  device's configuration — TalkBack running with touch exploration and its braille IME active — under which
  `FocusRequester.requestFocus()` is refused on a `clickable` node and granted on a `focusable()` one.
  **D34/D35 (§23.13–§23.14) settle this: the app speaks through its own TTS, no screen-reader-conditional
  behaviour is built, and the mechanism is not being redesigned around TalkBack.** The seven assertions stay
  in the suite and stay red rather than being deleted or relaxed.
- **The suite cannot gate while those seven are red**, so its verdict is only meaningful for the classes that
  exclude focus. Under D35 that is a durable property of running those assertions against a device whose
  screen reader intercepts focus, rather than an unfinished task — recorded in the README as well as here.
- **P1's audibility is unverified on a device** (§23.12). Reaching the end of a queue needs a full read, and
  no probe does that. JVM-proven that the session announces; the utterance path is the one `announceThenPlay`
  already uses on device.
- **The instrumented suite's verdict is configuration-dependent and the configuration is not recorded**, so
  "the suite is green" is not yet a stable property of this repository.
- **The two Player defects §23.8 found are now fixed** (§23.11): the end of a queue is spoken, and the
  progress line counts parts. **0a's Phases 1–3 are done** (§23.15 added Phase 1), so the Player's command
  set, its announcements and its progress line are implemented.
- **Stage 2 is next: background playback**, starting with **R10 — the process-scoped session holder**
  (§23.9, Phase 1 of the background specification). It is the refactor rather than the service, it changes
  no behaviour, and it is the one piece with a JVM-verifiable acceptance: destroying the ViewModel must no
  longer end the session. Everything after it — the `MediaSessionService`, audio focus, the notification,
  removing `keepScreenOn` — needs a device, and its device gate is now **audio focus behaviour** rather than
  a TalkBack measurement (D35).
- **The session spec's live-region wording was amended in Notion and re-mirrored** (§23.11), closing the
  dependency §23.9 recorded. `tools/amend-session-live-region.ps1` is the first demonstrated Notion-first
  amendment from this repository.
- **The plan's stages 1–4** (§23.5): the Player command surface; background playback with R10; the
  enabling refactors — D31 Navigation 3, R2, R3 with slice L, R4, R7, R8; and Saved Queues end to end.
- **D28 moved background playback to the front**, so §16.4's disagreement is closed by decision rather
  than left restated. Its acceptance is the one thing this project cannot verify on the JVM.
- **Slices J (captions and the child-database loss) and K (raw Notion navigation) are not in the plan** —
  neither is a Player or queue feature, and both were already unblocked and unstarted.
- **Slice C's facets and Home's Explore**, blocked on the Content Metadata & Workspace Schema Adapters
  spec, which §5 records this workspace can only partly supply. It now also gates Smart Queues (D30).
- **Slice N (the Compose v2 test rule, §17.3)** is unchanged and still worth its own risk-free round; D31's
  migration will re-point the affected tests anyway.
- `BuildConfig.NOTION_PAGE_ID` and the blank-reference auto-discovery path (§17.1).
- An inexact restore has not been observed on a device; it is covered by unit tests only (§17.6).

---

## 26. Playback crash fix and UI/UX implementation: Saved Queue Rename, Delete, and Home "Your queues"

**Date:** 2026-09-29. **Scope:** Fix playback crash (`ForegroundServiceDidNotStartInTimeException`) and implement missing UI/UX features from the governing contracts: Saved Queue `Rename` and `Delete`, and Home's *Your queues* section.

### 26.1 Playback crash fix: Media3 `MediaSessionService.addSession`

**The symptom:** On Android 14+ (API 34+), starting playback crashed after exactly 30 seconds with:
`android.app.ForegroundServiceDidNotStartInTimeException: Context.startForegroundService() did not then call Service.startForeground(): ServiceRecord{... com.example.notionreader/.PlaybackService}`

**The root cause:** In `PlaybackService.kt`, `MediaSession.Builder` instantiated a `MediaSession`, but never called `addSession(sessionInstance)`. Without `addSession()`, Media3's internal `MediaNotificationManager` did not know any session existed (`isSessionAdded` was false), never updated the notification, and never invoked `Service.startForeground()` while `PlaybackService` was active. After Android's 30-second foreground service start timeout, ActivityManager threw `ForegroundServiceDidNotStartInTimeException`.

**The fix:**
- In `PlaybackService.onCreate()`: invoked `addSession(sessionInstance)`.
- In `PlaybackService.onDestroy()`: invoked `removeSession(this)`.

**Verification:** Ran `probe-background-audio.ps1` on an API 36 emulator. Verified `PlaybackService running: True` and `...and foreground: True`. Logcat confirmed reading continued without `ForegroundServiceDidNotStartInTimeException`.

### 26.2 Saved Queue Rename and Delete (Phase 8 of Queue Creation & Queue Management)

**Contract:** `docs/queue-creation-and-queue-management-spec.md` (lines 354–360, 586–596).
In Viewing mode on `SavedQueueDetailsScreen`:
- Added `Rename` and `Delete` buttons next to `Edit queue`.
- `Rename` opens an `AlertDialog` allowing the listener to update the queue's name. A blank or whitespace-only name is refused. If the trimmed name is unchanged, no write is performed.
- `Delete` is destructive and requires an explicit confirmation naming the queue to prevent accidental deletion (`Delete "<name>"? The notes in this queue will not be deleted.`).
- If the listener deletes the queue while viewing its details, `ReaderApp.deleteSavedQueue` automatically navigates back so the screen does not display a deleted queue.

### 26.3 Home screen's "Your queues" section

**Contract:** `docs/notion/ui-screens-and-navigation-contract.md` (lines 83, 266–300, 1505).
- Displays "Your queues" below "Continue listening", showing up to 5 recently edited Saved Queues.
- Each queue row merges descendants into a single focus stop announcing title and member description (e.g. `Android accessibility \n Saved Queue · 11 notes`). Activating an item opens `SavedQueueDetails` without starting playback.
- A "See all queues" button at the bottom of the section navigates directly to the `Queues` tab.
- Empty state: When both `continueListening` and `yourQueues` are empty, the "Nothing to continue yet" prompt and "Browse Notes" button are shown. When either has items, only the populated section(s) appear.

### 26.4 Verification

- JVM unit tests: All 537 tests pass (`:app:testDebugUnitTest`), including new test cases for `renameSavedQueue` and `deleteSavedQueue` in `ReaderAppSavedQueuesTest`.
- Instrumented accessibility tests (via `tools/run-accessibility-tests.ps1`):
  - `HomeScreenAccessibilityTest`: 12/12 passing.
  - `SavedQueueDetailsAccessibilityTest`: 15/15 passing.
  - `ReaderShellSavedQueueTest`: 10/10 passing.
- `:app:compileDebugAndroidTestKotlin`: 0 errors.
- `:app:assembleDebug`: clean build.
- `tools/probe-background-audio.ps1`: verified foreground service lifecycle on device.

---

## 27. Player secondary controls & outline, Home spec alignment (Continue vs Play), Smart Queues end-to-end, and Audio Hardening

**Date:** 2026-09-29. **Scope:** Implement missing UI/UX features from the governing specs: Player granular part navigation and section outline jumping; Home screen alignment with Material Design practices and Explore cards; Smart Queues end-to-end (domain, persistence, UI, accessibility); and playback stability hardening against crashes and audio route changes.

### 27.1 Player secondary controls and section outline

**Spec Authority:** `docs/notion/ui-screens-and-navigation-contract.md` (lines 351–385, 401–419) and `docs/notion/core-player-and-tts-playback-spec.md`.

- **Granular Part Navigation:**
  - Added `jumpToParagraph(paragraphIndex: Int)` to `ReaderEngine` and `ReaderEngineImpl`, enabling precise navigation across speakable chunks within the current note.
  - Implemented state preservation: jumping while playing cancels current utterance and immediately begins speech at the new chunk; jumping while paused updates position without speaking.
  - Tucked secondary controls inside an accessible expandable surface labeled `More playback controls` to preserve clean, uncluttered primary transport controls (`Previous section`, `Play/Pause`, `Next section`, `Queue`).
  - Added `Restart note` (returns to paragraph 0, preserving playback state), `Previous part`, and `Next part`.
- **Section Outline & Jumping:**
  - Added `jumpToSection(span: ReaderSectionSpan)` to `ReaderSession` and `ReaderApp`.
  - Added `Sections` outline sheet/view in `PlayerScreen.kt` listing all heading sections hierarchically with heading levels (`Heading 1`, `Heading 2`, `Heading 3`).
  - Highlights the currently active section and allows activating any section to jump directly to its heading paragraph, announcing the target section.

### 27.2 Home screen: Material Design "Play" vs "Continue" & Explore section

**Spec Authority:** `docs/notion/ui-screens-and-navigation-contract.md` (lines 80–115, 260–295) and Google Material Design guidelines.

- **"Play" vs "Continue" Decision:**
  - For in-progress or paused reading sessions, user habit and anxiety around losing reading position dictate that the primary action must be labeled **"Continue"**. "Play" can imply starting over from the beginning, whereas "Continue" guarantees position continuity.
  - The inline button retains the compact toggle action (`Play`/`Pause`) for quick in-place listening.
- **Explore Section:**
  - Added domain model `ExploreCard(title, noteCount, queryText)`.
  - Added `Explore` section on Home displaying curated topic shortcuts with dynamic note counts.
  - Each explore row merges descendants into a single accessible focus target announcing title and count (e.g. `Architecture · 8 notes`).
  - Activating an Explore row navigates to `Browse` pre-filtered by that query topic.

### 27.3 Smart Queues feature end-to-end

**Spec Authority:** `docs/queue-creation-and-queue-management-spec.md` (lines 40–55, 600–625) and `docs/notion/ui-screens-and-navigation-contract.md`.

- **Domain Model & Isolation:**
  - Created `SmartQueue` model (`id`, `name`, `filterQuery`, `createdAt`, `updatedAt`, `schemaVersion = 1`).
  - Created dedicated `SmartQueueEffect` and `SmartQueueEditOutcome` domain types rather than coupling to `SavedQueue`'s effect type, which strictly requires `SavedQueue` and `QueuedNote` instances.
  - Implemented `SmartQueues` business logic for deterministic listing, creation, renaming, and deletion.
- **Persistence & Codec:**
  - Implemented `SmartQueueCodec` in `data/local` with robust element-by-element JSON parsing resilient against malformed items.
  - Implemented `DataStoreSmartQueueStore` using Preferences DataStore, wired into `ReaderHost` and `ReaderApplication`.
- **UI & Navigation:**
  - Expanded `QueuesScreen` to render all 3 tabs: `Listening`, `Saved`, and `Smart`.
  - `SmartTab` displays list of configured smart queues with live workspace match counts, or an accessible empty state when none exist.
  - `CreateSmartQueueScreen` allows entering a queue name and query filter with validation.
  - `SmartQueueDetailsScreen` evaluates matching notes in real-time against the workspace, provides `Play query` (which snapshots current matches into a new Active Queue session), `Rename`, and `Delete` (with accessible confirmation dialogs).
  - Wired into `ReaderDestination` and `ReaderApp` with comprehensive polite live region status announcements.

### 27.4 Audio Focus, Headset Unplug & Platform Crash Hardening

**Spec Authority:** `docs/background-playback-and-media-controls-spec.md` (lines 325–335).

- **Audio Attributes:** Set `AudioAttributes` (`USAGE_MEDIA`, `CONTENT_TYPE_SPEECH`) on the Android `TextToSpeech` engine, ensuring playback is routed to media audio streams and responds to system media volume controls.
- **Becoming Noisy:** Registered a `BroadcastReceiver` for `AudioManager.ACTION_AUDIO_BECOMING_NOISY` in `PlaybackService` to immediately and quietly pause playback when headphones are disconnected.
- **Speech Length Clamping & Exception Safety:**
  - Clamped utterance text to `TextToSpeech.getMaxSpeechInputLength()` to avoid transaction failures on exceptionally large blocks.
  - Added try-catch guards around platform TTS binder interactions (`speak`, `stop`, `shutdown`) to protect against dead binder exceptions and platform engine crashes.

### 27.5 Verification Evidence

- JVM unit tests: All 555+ unit tests pass cleanly (`:app:testDebugUnitTest`), including comprehensive test suites for `SmartQueuesTest`, `SmartQueueCodecTest`, and `ReaderEngineTest`.
- Android test compilation: `:app:compileDebugAndroidTestKotlin` 0 errors.
- Clean production build: `:app:assembleDebug` SUCCESS.
- Instrumented accessibility tests (`tools/run-accessibility-tests.ps1 -Device emulator-5554`):
  - `PlayerScreenAccessibilityTest`: 17/17 passing (verifying secondary controls, section outline, TalkBack reachability and actions).
  - `HomeScreenAccessibilityTest`: 14/14 passing (verifying Continue action, Your queues, Explore section, and empty state).
  - `SmartQueueDetailsAccessibilityTest`: 5/5 passing (verifying matching notes list, Play query, Rename, and Delete dialogs).
  - `QueuesScreenAccessibilityTest`: 22/22 passing (verifying all 3 tabs: Listening, Saved, Smart, and tab transitions).

---

## 28. Browse Tab Multi-Facet Filtering, Metadata Search & TalkBack Accessibility

**Date:** 2026-09-30. **Scope:** Implement the complete Browse tab filtering experience as specified in `docs/notion/ui-screens-and-navigation-contract.md` (lines 400–640), `docs/notion/notion-integration-implementation-spec.md` (lines 80–130), and `docs/decisions.md` §5.

### 28.1 Facet Discovery and Domain Model

**Spec Authority:** `docs/notion/ui-screens-and-navigation-contract.md` lines 434–485.

- **ReaderIndexEntry Metadata:**
  - Extended `ReaderIndexEntry` in the domain layer with optional metadata fields: `kind: String?`, `area: String?`, `topics: Set<String>`, `project: String?`, `status: String?` (all with default empty/null values for backward compatibility).
- **Notion DTO & Repository Deserialization:**
  - Widened `NotionProperty` in `NotionModels.kt` to parse `select`, `status`, `multi_select`, and `rich_text` property types from page representations returned by the Notion API.
  - In `NotionRepository.getBrowseIndex()`, extracted `Type`/`Kind`, `Area`, `Topics`/`Topic`/`Tags`, `Project`, and `Status` properties safely, preserving layer decoupling (no Notion DTOs leak to domain).
- **Dynamic Facet Discovery Rules (§5):**
  - Rather than hardcoding taxonomy values, available facet options are dynamically discovered from indexed workspace entries.
  - A facet chip is only shown if matching metadata exists in the workspace.
  - `availableAreas` is dynamically scoped to the selected `Type` when a Type filter is active, reflecting the workspace's nested taxonomy.

### 28.2 Query Filtering & Search Logic

**Spec Authority:** `docs/notion/ui-screens-and-navigation-contract.md` lines 500–560.

- **BrowseQuery & BrowseModel:**
  - Added `selectedType`, `selectedArea`, `selectedTopic`, `selectedProject`, `selectedStatus` to `BrowseQuery`.
  - Added query inspection helpers: `hasFilters`, `activeFilterLabels`, `activeFilterCount`.
  - In `BrowseQuery.applyTo(entries)`:
    - Text search matches concurrently across title, kind, area, topics, and project names.
    - Facet filters apply conjunctively (AND logic), matching case-insensitively.
    - Applied sort orders (`RecentlyEdited`, `AlphabeticalAsc`, `AlphabeticalDesc`, `CreatedAsc`, `CreatedDesc`) are strictly preserved.
  - `BrowseModel` exposes discrete mutators (`setTypeFilter`, `setAreaFilter`, `setTopicFilter`, `setProjectFilter`, `setStatusFilter`, `clearFilters`, `clearAll`).

### 28.3 User Experience & TalkBack Accessibility

**Spec Authority:** `docs/notion/ui-screens-and-navigation-contract.md` lines 565–640.

- **Horizontal Filter Chips:**
  - Rendered a horizontal scrollable row of Material3 `FilterChip`s for Type, Area, Topic, Project, and Status above the note list.
  - Activating a chip displays a clean `FacetSelectionDialog` (`AlertDialog`) allowing the user to select any discovered option or clear the facet.
- **Active Filter Summary:**
  - When filters are active, an accessible summary card appears displaying active filters and result count (e.g. `2 filters: Reflection, Mind & Learning · 3 results`).
  - Includes a direct `Clear filters` button for immediate reset.
  - Non-live-region discipline: summary is announced politely without intrusive live region churn during typing.
- **Result Row Semantic Merging:**
  - Each `BrowseResultRow` merges its descendants into a single focus stop (`mergeDescendants = true`).
  - Subtitle displays formatted metadata (e.g. `Reflection · Mind & Learning · September 17`).
  - Produces the exact TalkBack announcement mandated by the spec: *"Thoughts are less reactive but still frequent. Reflection. Mind & Learning. September 17."*
- **Empty States:**
  - Distinguishes between empty workspace, empty search, and empty facet filter combinations, providing appropriate contextual messages and one-tap clear buttons.

### 28.4 Verification Evidence

- **JVM Unit Tests:**
  - All 562+ JVM tests pass (`:app:testDebugUnitTest`).
  - `BrowseQueryTest`: 6 new tests covering facet filtering, multi-facet combinations, case insensitivity, and metadata search.
  - `BrowseModelTest`: Added `facetFiltersNarrowTheResultsAndCanBeCleared`.
  - `LayerRulesTest`: Clean pass (0 layer boundary violations).
- **Instrumented Accessibility Suite:**
  - Ran `BrowseScreenAccessibilityTest` via `tools/run-accessibility-tests.ps1`: **17/17 passing (100%)**.
  - Verified:
    - `facetFilterChipsAreExposedAndLabelled`: every chip has an accessible label and click action.
    - `activeFilterSummaryExposesActiveCountAndClearButton`: active count and clear button are readable and reachable.
    - `aResultWithMetadataMergesTypeAndAreaIntoItsFocusStop`: title, kind, area, and date are spoken as a single coherent announcement.
    - `everyControlCanDescribeItselfRatherThanBeingAnnouncedAsABareButton`: every control passes accessibility description checks.
- **Suite & Tooling Stability:**
  - Identified emulator aging/load-average bottleneck on long sessions (ranchu graphics composer driver eating CPU); fresh daemon reboot resolved test turnaround to 30s.
  - Added `-Device` argument support to `tools/run-accessibility-tests.ps1`.
  - All 4 wrapper self-checks (`check-wrapper-completeness.ps1`, `check-classifier-on-real-output.ps1`, `check-empty-failure-is-classified.ps1`, `check-harness-signatures.ps1`) pass cleanly.

---

## 29. Stale-While-Revalidate Document Cache, Offline Playback, Player Adaptive Stepping & UI/UX Refinements

**Date:** 2026-09-30
**Scope:** Document caching engine, offline listening resilience, suppression of random/stale text during note fetches, Player navigation granularity step mode, bottom tab bar refinements, mini-player touch partitioning, and home screen resume elevation.

### 29.1 Architecture & Storage Engine: Pure Kotlin Atomic File Cache

- **No Heavy Native NoSQL/SQL Dependencies:**
  - Evaluated Room/SQLite, Realm, and ObjectBox against requirements.
  - Traditional embedded databases introduce 15–25 MB native `.so` binaries, ABI splits, schema migration maintenance, and threading confinement issues for documents that are inherently tree-structured snapshots.
  - Chosen design: pure Kotlin atomic file-per-document store in `context.filesDir/document-cache`.
  - Crash safety: writes staged to `.tmp` files, synced to disk, and committed atomically via filesystem rename (`renameTo`).
  - Serialization: pure `kotlinx.serialization` JSON codec (`ReaderDocumentCodec`) storing `CachedDocument` with `schemaVersion = 1`, `lastEditedAt`, and `cachedAt`.
  - Cache size policy: LRU eviction cap of 200 documents in `FileDocumentCache`.

### 29.2 Stale-While-Revalidate Algorithm & Offline Resumption

- **Split Remote Source & Fast Metadata Query:**
  - Decoupled `RemoteDocumentSource` into `getMetadata(reference)` (fetches Notion page metadata without downloading block children) and `getDocument(reference)` (fetches full page and blocks).
  - Implemented in `NotionRepository` via `resolvePage()`.
- **CachedDocumentRepository:**
  - **Cache Hit (0ms / minimal network):** Checks local cache by normalized page UUID; fetches remote metadata; if `cached.lastEditedAt == remote.lastEditedAt`, returns cached document immediately without downloading block children.
  - **Cache Stale / Miss:** If remote metadata has a newer timestamp or page is not in cache, downloads full document and atomically commits it to cache.
  - **Offline Playback & Fallback:** If network is unavailable (`ReaderErrorCategory.NETWORK_UNAVAILABLE`), falls back to local cached copy with `DocumentFreshness.STALE_OFFLINE`. Hands-free spoken reading continues uninterrupted offline.
  - **Permission / Deletion Invalidation:** On permission errors (`PAGE_NOT_FOUND`, `AUTHORIZATION_REQUIRED`, or trashed page), invalidates cache to prevent serving unauthorized or deleted content.
  - **Active Queue Opportunistic Prefetching:** `ReaderHost.startQueuePrefetch()` watches `session.queueState` and silently prefetches the next 1–2 queued notes into the local cache in the background.

### 29.3 UI/UX Refinements & User-Facing Polish

- **Suppression of Stale / Random Text During Note Fetches:**
  - In `NoteScreen.kt`, suppressed chunk rendering during `DocumentLoadState.Loading` and added a `LinearProgressIndicator` above `"Loading Notes…"`. Prevents previous note paragraphs from flashing or being read while fetching a new note.
  - In `PlayerScreen.kt`, added `LinearProgressIndicator` during `queueState.awaiting` and ensured no stale paragraph text is rendered while transitioning notes.
- **Player Adaptive Navigation Granularity Stepping:**
  - Added `NavigationGranularity { SECTION, PART, NOTE }` enum with adaptive step cycling (`SECTION` $\leftrightarrow$ `PART` in single-note queues; `SECTION` $\to$ `PART` $\to$ `NOTE` in multi-note queues).
  - Prominent center `Play | Pause` button in the primary transport row.
  - Unified `Previous` and `Next` buttons that dynamically execute the active step granularity (`PreviousSection`/`NextSection`, `PreviousPart`/`NextPart`, `PreviousNote`/`NextNote`).
  - Polite TalkBack live-region feedback on mode switch (*"Stepping by section"*, *"Stepping by part"*, *"Stepping by note"*).
  - Preserved backward compatibility with all existing accessibility assertions in `PlayerScreenAccessibilityTest` and `PlayerQueueViewAccessibilityTest`.
- **Bottom Navigation Bar & Mini-Player Polish:**
  - Bottom navigation bar is hidden on `PlayerScreen` for immersive full-screen playback.
  - Mini-player wrapped in Material 3 tonal elevation with click partitioning between open player and 48dp Play/Pause touch target.
  - Home Screen `ContinueListeningCard` details column is directly clickable for instant resumption.

### 29.4 Verification Evidence

- **JVM Unit Tests:**
  - All 570+ JVM tests pass (`:app:testDebugUnitTest`).
  - `ReaderDocumentCodecTest`: 4 tests (round-trip serialization, empty document, corrupt JSON, schema mismatch).
  - `FileDocumentCacheTest`: 7 tests (put, get, remove, clear, atomic overwrite, LRU eviction cap).
  - `CachedDocumentRepositoryTest`: 8 tests (fresh hit, stale revalidation, offline fallback, uncached failure, trashed invalidation).
  - `LayerRulesTest`: 0 violations.
- **Instrumented Accessibility Suite (Emulator):**
  - `PlayerScreenAccessibilityTest`: 17/17 passing (100%).
  - `NoteScreenAccessibilityTest`: 17/17 passing (100%).
  - `PlayerQueueViewAccessibilityTest`: 17/17 passing (100%).
  - `HomeScreenAccessibilityTest`: 14/14 passing (100%).
- **Build Verification:**
  - `:app:compileDebugAndroidTestKotlin`: Clean.
  - `:app:assembleDebug`: Clean.



