# Phase 0 — Fixture and expected `ReaderDocument` contract

Phase 0 of `docs/notion/notion-integration-implementation-spec.md` requires two things before any parser is written:

1. one dedicated Notion page containing every block type the POC must support plus the edge
   cases the spec's test strategy calls out;
2. the expected `ReaderDocument` for that page, written down **by hand**.

This file is the result. It is the acceptance oracle for Phase 3 (normalization) and Phase 4
(reader mapping). Do not change it to match the implementation — change the implementation to
match it, or change this file deliberately and say why.

---

## 1. The fixture page

| | |
|---|---|
| Title | `Reader Fixture — Block Type Coverage` |
| Page ID | `3dec9345-e10a-8180-867d-fa96b49bd30b` |
| URL | <https://app.notion.com/p/Reader-Fixture-Block-Type-Coverage-3dec9345e10a8180867dfa96b49bd30b> |
| Top-level blocks | 23 |
| Re-seed with | `pwsh -File tools/seed-reader-fixture.ps1` |

Re-seeding creates a **duplicate** page and therefore a new page ID. If the page is ever
re-created, every ID in section 4 below must be updated.

### Block inventory

Order is the order the API returns, which is the order the reader must preserve.

| # | Type | Has children | Block ID | Content |
|---|---|---|---|---|
| 1 | `paragraph` | | `3dec9345-e10a-81ce-a1a0-f5eaf14193b1` | Intro paragraph before any heading. |
| 2 | `paragraph` | | `3dec9345-e10a-81c5-9d27-d7ae2a10c453` | 3 fragments, 2nd is bold |
| 3 | `paragraph` | | `3dec9345-e10a-8189-becc-c72f3acff82a` | fragment + link + fragment |
| 4 | `paragraph` | | `3dec9345-e10a-81f2-b62f-f88c9184f316` | fragment + inline-code + fragment |
| 5 | `heading_1` | | `3dec9345-e10a-81b2-8de5-d4a070b01d1e` | Primary Heading |
| 6 | `paragraph` | | `3dec9345-e10a-81bb-ac31-c8546d8725af` | Body under the primary heading. |
| 7 | `heading_2` | | `3dec9345-e10a-81ad-afda-e576ba917ae4` | Secondary Heading |
| 8 | `bulleted_list_item` | | `3dec9345-e10a-81aa-864c-d1bdb6e5b361` | First bullet |
| 9 | `bulleted_list_item` | **yes** | `3dec9345-e10a-817d-a39c-ffe3baa1cd5e` | Second bullet |
| 9.1 | `bulleted_list_item` | | `3dec9345-e10a-8123-b215-ea1c79c92568` | Nested bullet under the second bullet. |
| 10 | `numbered_list_item` | **yes** | `3dec9345-e10a-8102-922b-eb7d15365341` | First numbered item |
| 10.1 | `bulleted_list_item` | | `3dec9345-e10a-814e-8814-ca751f274aa3` | Nested bullet under the first numbered item. |
| 11 | `numbered_list_item` | | `3dec9345-e10a-8105-95a5-e623b6b577c0` | Second numbered item |
| 12 | `to_do` | | `3dec9345-e10a-810e-a8d7-fcbfb3dc92bd` | Unchecked task (`checked=false`) |
| 13 | `to_do` | | `3dec9345-e10a-8108-a329-d796f372ae08` | Checked task (`checked=true`) |
| 14 | `quote` | | `3dec9345-e10a-814a-b203-c60426c9030f` | Quoted text for the fixture. |
| 15 | `code` | | `3dec9345-e10a-81bc-a791-c6788c5038ed` | `val answer = 42`, `language=kotlin` |
| 16 | `divider` | | `3dec9345-e10a-814a-841f-debd8be3d6ed` | (none) |
| 17 | `heading_3` | | `3dec9345-e10a-818d-9595-c7dac1c3c555` | Tertiary Heading |
| 18 | `paragraph` | | `3dec9345-e10a-814e-bd10-de300f0710f6` | Body under the tertiary heading. |
| 19 | `heading_2` | | `3dec9345-e10a-81db-862b-fbbd7a2670e1` | **empty** |
| 20 | `paragraph` | | `3dec9345-e10a-816f-999e-f7b91a594c5a` | Paragraph after the empty heading. |
| 21 | `image` | | `3dec9345-e10a-81b4-880e-dfe9bf40f95e` | unsupported; caption "Unsupported image block" |
| 22 | `paragraph` | | `3dec9345-e10a-815d-a804-ce592e7b7181` | 6 sentences, ~380 chars |
| 23 | `toggle` | **yes** | `3dec9345-e10a-8123-a2bb-f87323ee38e8` | Toggle summary |
| 23.1 | `paragraph` | | `3dec9345-e10a-812a-860d-e1f49d4f72c0` | Inside the toggle. |
| 24 | `paragraph` | | `3dec9345-e10a-817c-999b-d31318d4cbee` | 422 chars, **one sentence** (single full stop, at the end) |
| 25 | `paragraph` | | `3dec9345-e10a-8136-9c8d-edb3b8a6ee8a` | 382 chars, **no full stop anywhere** |

Blocks 24 and 25 were appended later by `tools/extend-reader-fixture.ps1` to give D11's fallback chain
something to exercise. Because this Notion API version cannot insert at a position, they land at the
**end** of the document, inside section 3.

### What each block is for

| Spec requirement | Block(s) |
|---|---|
| Content before first heading → intro section | 1–4 |
| Rich text fragments join in order, no doubled whitespace | 2 |
| Visual styling ignored, text preserved | 2 (bold fragment) |
| Link display text kept, href kept as metadata | 3 |
| Inline code preserved as text + content-type hint | 4 |
| Heading defines section, level retained | 5, 7, 17 |
| Nested block children stay in source order | 9.1, 10.1, 23.1 |
| One list item = one chunk | 8–11 |
| To-do state captured | 12, 13 |
| Quote = one chunk | 14 |
| Code = one chunk | 15 |
| Divider → no spoken chunk | 16 |
| Empty heading → no unusable navigation target | 19 |
| Unsupported block degrades locally | 21 — preserved in the normalized tree, emits no chunk (D6) |
| Long paragraph splits at sentence boundaries | 22 — exact count threshold-dependent (D11) |
| Fall-through to the word-boundary rule | 24 — one sentence, no internal boundary to split on |
| Fall-through past the sentence rule entirely | 25 — no sentence-ending punctuation at all |
| Container (toggle) children read in order | 23, 23.1 |
| Pagination (`page_size` default is 100) | *not covered — see §6* |

---

## 2. Decisions this contract depends on

The spec fixes the shape of the domain model but leaves several mapping rules open. All twelve
rules below are **settled**, with the supporting quotations, in
[`docs/decisions.md`](decisions.md) §2. That record is the authority for the reasoning; this table
is the summary the fixture was built against.

Rows marked **revised** were changed by product review after this document's first draft.

| # | Decision | Status |
|---|---|---|
| D1 | `ReaderSection.level` = the heading level (`heading_1` → 1 … `heading_3` → 3). Sections stay a **flat list**; hierarchy is carried by `level`, not by nesting. | settled |
| D2 | An intro section is created **only when there is content before the first heading**. A page that opens with a heading has no leading section at all. | **revised** — no empty intro section merely to hold the title |
| D3 | Each heading opens a new section **and** emits a `HEADING` chunk as that section's first chunk. Boundary and spoken unit are both roles of the same heading. | settled |
| D4 | An **empty** heading is ignored entirely: no section boundary and no chunk. Following content stays in the enclosing section. Its children, if any, are still read. | settled |
| D5 | A **divider** is preserved as a structural normalized node and emits **no spoken chunk and no `ReaderSection`** in v1. The spec's "produces navigation structure" clause is satisfied at the normalized layer, not the reader-domain layer. | **revised** — was "discarded"; now preserved structurally |
| D6 | An **unsupported** block is preserved as a normalized node with diagnostics. It emits **no `ReaderChunk`** — not even an empty one. | **revised** — an empty chunk would itself be a useless navigation target, which the spec's Chunking test forbids |
| D7 | `chunk.id = "<sourceBlockId>:<part>"`, 0-based, applied **uniformly** — an unsplit block is always `:0`. Uniformity matters: if `A` became `A:0`/`A:1` when a paragraph grew, a resume position saved as `A` would break. | settled |
| D8 | `section.id` = the heading block's ID; an intro section uses `"<pageId>:intro"`. Never an index. | settled |
| D9 | **No `TITLE` chunk is emitted.** `ReaderDocument.title` carries the title; the Player announces it on session start and on note transitions. | **revised** — resolves the double-announcement problem; note-transition speech is Player behaviour per the UI contract |
| D10 | `to_do` checked state is preserved in `ChunkMetadata.checked` and never baked into `chunk.text`. Whether it is *announced* is a Player/Note accessibility policy, not a mapping decision. | **revised** — "never speak it" was too strong |
| D11 | Long blocks **must** split at sentence boundaries. The `300`-character threshold is **not** settled: it is an implementation constant pending test evidence. | **revised** — no governing spec provides a number |
| D12 | Chunk text never includes Notion identifiers, URLs, or status. Links keep their display text. | settled |

`ChunkMetadata` is referenced by the spec but never defined; the definition backing D6/D10/D12 is in
`decisions.md` §2. Note that D6 removes `unsupportedType`'s consumer, so unsupported-block
diagnostics move to the normalized block tree.

---

## 3. Expected `ReaderDocument`

```kotlin
ReaderDocument(
    pageId = "3dec9345-e10a-8180-867d-fa96b49bd30b",
    title  = "Reader Fixture — Block Type Coverage",
    sections = [ /* 4 sections, 24 chunks — table below */ ],
    lastEditedAt = <page last_edited_time, non-null>,
)
```

**4 sections; ≥28 chunks** — 22 threshold-independent, plus at least 2 each for the three overlong
blocks (22, 24, 25). At the current implementation constant (`DEFAULT_MAX_CHUNK_CHARS = 300`) the
count is **exactly 28**, which is what the device produced (§3a).

| Sec | id | title | level | # chunks |
|---|---|---|---|---|
| 0 | `3dec9345-e10a-8180-867d-fa96b49bd30b:intro` | `null` | 0 | 4 |
| 1 | `3dec9345-e10a-81b2-8de5-d4a070b01d1e` | Primary Heading | 1 | 2 |
| 2 | `3dec9345-e10a-81ad-afda-e576ba917ae4` | Secondary Heading | 2 | 11 |
| 3 | `3dec9345-e10a-818d-9595-c7dac1c3c555` | Tertiary Heading | 3 | ≥11 † |

† Section 3's count depends on the split threshold. Its 5 non-splitting chunks are fixed; blocks
22, 24 and 25 contribute at least 2 each.
### Section 0 — intro

| # | chunk id | kind | text | metadata |
|---|---|---|---|---|
| 0 | `…a1a0-f5eaf14193b1:0` | `PARAGRAPH` | Intro paragraph before any heading. | |
| 1 | `…d7ae2a10c453:0` | `PARAGRAPH` | Fragment one, fragment two, and fragment three. | |
| 2 | `…c72f3acff82a:0` | `PARAGRAPH` | See the Notion docs for details. | `linkTargets=["https://developers.notion.com/reference/intro"]` |
| 3 | `…f88c9184f316:0` | `PARAGRAPH` | Inline code_span inside a paragraph. | `hasInlineCode=true` |

There is no `TITLE` chunk (revised D9); `ReaderDocument.title` carries the title and the Player
announces it. Note that #1 is a four-sentence block at ~70 characters, so it stays a single chunk
under any plausible threshold — this section's count is threshold-independent.

Note on #2: three source fragments concatenate to `Fragment one, fragment two, and fragment
three.` — the trailing space inside `"Fragment one, "` is preserved and the bold styling is
dropped without leaving marks. Note on #3: the href is **not** spoken and is not appended to the
text; `the Notion docs` survives as the display text.

### Section 1 — Primary Heading

| # | chunk id | kind | text |
|---|---|---|---|
| 0 | `…d4a070b01d1e:0` | `HEADING` | Primary Heading |
| 1 | `…c8546d8725af:0` | `PARAGRAPH` | Body under the primary heading. |

### Section 2 — Secondary Heading

| # | chunk id | kind | text | metadata |
|---|---|---|---|---|
| 0 | `…e576ba917ae4:0` | `HEADING` | Secondary Heading | |
| 1 | `…d1bdb6e5b361:0` | `LIST_ITEM` | First bullet | |
| 2 | `…ffe3baa1cd5e:0` | `LIST_ITEM` | Second bullet | |
| 3 | `…ea1c79c92568:0` | `LIST_ITEM` | Nested bullet under the second bullet. | |
| 4 | `…eb7d15365341:0` | `LIST_ITEM` | First numbered item | |
| 5 | `…ca751f274aa3:0` | `LIST_ITEM` | Nested bullet under the first numbered item. | |
| 6 | `…e623b6b577c0:0` | `LIST_ITEM` | Second numbered item | |
| 7 | `…fcbfb3dc92bd:0` | `TODO` | Unchecked task | `checked=false` |
| 8 | `…d796f372ae08:0` | `TODO` | Checked task | `checked=true` |
| 9 | `…c60426c9030f:0` | `QUOTE` | Quoted text for the fixture. | |
| 10 | `…c6788c5038ed:0` | `CODE` | val answer = 42 | `language="kotlin"` |

Block 16 (divider) produces **no chunk and no section**, and is preserved only in the normalized
block tree (revised D5). That is why this section has 11 chunks from 12 blocks. Chunks 3 and 5 are
the nested children — they follow their parent immediately, which is what "nested block children
preserve source order" means.

Note that `bulleted_list_item` and `numbered_list_item` both map to `LIST_ITEM`; list *style* is
not in the domain model, so it is not distinguishable in a chunk. If list numbering needs to be
spoken, `ChunkMetadata` must grow a field and this contract must change.

### Section 3 — Tertiary Heading

| # | chunk id | kind | text | metadata |
|---|---|---|---|---|
| 0 | `…c7dac1c3c555:0` | `HEADING` | Tertiary Heading | |
| 1 | `…de300f0710f6:0` | `PARAGRAPH` | Body under the tertiary heading. | |
| 2 | `…f7b91a594c5a:0` | `PARAGRAPH` | Paragraph after the empty heading. | |
| 3 | `…ce592e7b7181:0` | `PARAGRAPH` | Long paragraph sentence one, … past three hundred characters. | |
| 4 | `…ce592e7b7181:1` | `PARAGRAPH` | Long paragraph sentence five pushes it further still. Long paragraph sentence six ends the block. | |
| 5 | `…f87323ee38e8:0` | `PARAGRAPH` | Toggle summary | |
| 6 | `…e1f49d4f72c0:0` | `PARAGRAPH` | Inside the toggle. | |
| 7–n | `…d31318d4cbee:<i>` | `PARAGRAPH` | block 24, split by the **word-boundary** rule | |
| … | `…edb3b8a6ee8a:<i>` | `PARAGRAPH` | block 25, split with **no sentence boundary to use** | |

Block 19 (empty heading) produces no section and no chunk (D4), so block 20 lands here rather
than opening a section of its own.

Block 21 (the `image`) produces **no chunk at all** under revised D6, so it occupies no position
in this list. It is present only in the normalized block tree, with diagnostics.

**† Threshold dependency.** Chunks 3 and 4 are the two parts of block 22; chunks 7–n and beyond
are blocks 24 and 25. Their exact split points, and therefore this section's total count, depend on
the split threshold, which revised D11 left unfrozen. The *threshold-independent* assertions a test
can still make are:

1. each of blocks 22, 24 and 25 yields **at least 2** chunks under any threshold below its length;
2. for block 22 every part ends at a sentence boundary;
3. for block 24, which has only one sentence, at least one part must end **mid-sentence**, at a word
   boundary — this is the assertion that proves the word-boundary rule exists at all;
4. for block 25, which has no sentence-ending punctuation, every part must end at a word boundary,
   since the sentence rule has nothing to work with;
5. the parts of each block concatenate back to the original block text with no loss or duplication;
6. each part's id is `<blockId>:<index>`, contiguous from `:0`.

Those six properties are the contract. The specific counts are illustrative of one reasonable
threshold.

---

## 3a. On-device validation (2026-09-17)

This contract was verified against the real API on a real device, not only against the offline
snapshot. Evidence, in the order it was gathered:

| Observation | Result |
|---|---|
| Device | Pixel 8 Pro (`husky`), app v0.1, via `adb` |
| Page loaded | `Reader Fixture — Block Type Coverage` |
| Discovered by | `POST /v1/search` most-recently-edited (no page id configured) |
| Chunk count shown | **Paragraph 28 of 28** — exactly the predicted total |
| Read to the end | last chunk was the tail of block 25 |
| App process log | 8 lines total, **zero warnings or errors**, no exceptions |
| TTS | bound and connected to `com.google.android.tts` |

Walking backwards eleven chunks from the end reproduced the contract **at every index**:

| On device | Expected by this contract |
|---|---|
| 28 `fallback chain defines and which should essentially never be reached by ordinary prose` | block 25 `:1` |
| 27 `no sentence ending punctuation appears anywhere in this block …` | block 25 `:0` |
| 26 `precisely the fallback path that must be exercised …` | block 24 `:1` |
| 25 `This single sentence deliberately runs well past any plausible chunk threshold …` | block 24 `:0` |
| 24 `Inside the toggle.` | block 23.1 |
| 23 `Toggle summary` | block 23 |
| 22 `Long paragraph sentence five … sentence six ends the block.` | block 22 `:1` |
| 21 `Long paragraph sentence one …` | block 22 `:0` |
| 20 `Paragraph after the empty heading.` | block 20 |
| 19 `Body under the tertiary heading.` | block 18 |
| 18 `Tertiary Heading` | block 17 |
| 17 `val answer = 42` | block 15 — end of section 2 |
| 16 `Quoted text for the fixture.` | block 14 |
| 15 `Checked task` | block 13 |

Three things this establishes that the offline tests could not:

1. **All three rules of the D11 fallback chain ran.** Block 22 split at a *sentence* boundary
   (rule 1); blocks 24 and 25 split mid-sentence at a *word* boundary (rule 2) — chunk 27 visibly
   ends on "…the last resort **the**" with the remainder starting at "fallback chain". The fixture's
two added blocks earned their place.
2. **D4, D5 and D6 hold on real API data.** The page contains an empty heading, a divider and an
   unsupported `image`; had any produced a chunk the total would have been 29 or 30, and the
   per-index match above would have shifted. It is 28 and nothing shifted.
3. **Nesting and containers work end to end.** Chunks 23/24 are the toggle and its child, adjacent
   and in order, so children are read in place rather than appended or dropped.

The regression risk this leaves: the chunk *indices* above are only valid while
`DEFAULT_MAX_CHUNK_CHARS` is 300. Changing that constant renumbers every chunk after the first
oversized block, so this table must be re-derived if the threshold is retuned — which is why D11
kept the threshold out of the specification.

---

## 4. Traceability to the spec's acceptance criteria

| Acceptance criterion | Covered here |
|---|---|
| Known page ID loads | yes — fixture ID; also **verified on a device via discovery** (§3a) |
| All paginated children retrieved in order | yes — order is §1 and was confirmed on device (§3a) |
| Nested supported content included | yes — 9.1, 10.1, 23.1 |
| Raw response converted to `ReaderDocument` | yes — §3 is the target |
| Title, sections, chunks deterministic and inspectable | yes — D7/D8 make IDs deterministic |
| Unsupported blocks do not crash loading | yes — block 21, confirmed on device (§3a) |
| Auth / permission / network / not-found distinguishable | **no** — needs a separate error-mapping fixture, not this page |
| Unit tests cover mapping, hierarchy, pagination, errors | partly — this page drives mapping and hierarchy; **pagination and error mapping are still untested** |
| No TTS dependency in repository or mapper | n/a — structural, checked by review |

---

## 5. What must be true once Phase 4 is done

A single test should be able to assert §3 against the live page, or against a captured JSON
snapshot of it, without any Android or TTS classes involved.

Blocking prerequisites, none of which exist yet:

1. `ReaderDocument` gains `sections` and loses flat `paragraphs` — the current
   `data class ReaderDocument(id, title, paragraphs: List<String>)` cannot express §3 at all.
2. A normalized block model between the DTOs and the mapper (Phase 3). Revised D5 and D6 both
   *require* this layer: a divider and an unsupported block now need somewhere to live, and the
   chunk list is no longer that place. Unsupported-block diagnostics attach here too.
3. `ChunkMetadata`, which the spec references but never defines.
4. The split threshold, as a named constant at minimum — see §6.

---

## 6. Known gaps in this fixture

- **Pagination is not exercised.** The fixture is one page of 23 blocks, well under the default
  `page_size` of 100. A second fixture with >100 top-level blocks, or a fake HTTP layer, is
  needed for the pagination tests in the spec's repository test list.
- **No `child_page` / `child_database` / `synced_block` / `table` / `callout` block.** Callout and
  synced block are in the spec's "useful next set"; the current repository already treats
  `callout`, `toggle`, `child_page`, `child_database` and `synced_block` specially, so those
  choices are untested by this page.
- **No deeply nested chain** (fixture max depth is 2 below the page). The spec asks what maximum
  nesting depth to permit; the fixture cannot answer it. Q5 settled the *behaviour* at the limit —
  exceeding it must emit a diagnostic rather than silently dropping content, which the current
  `NotionRepository` violates — but the fixture has no chain deep enough to test that.
- **The split fallback chain is now exercised, but the threshold is still unfrozen.** Revised D11
  settles that splitting must happen at sentence boundaries but not the character threshold. Blocks
  24 and 25 were added to reach the rules block 22 cannot: 24 is a single 422-character sentence, so
  a correct splitter *must* break it mid-sentence at a word boundary, and 25 is 382 characters with
  no full stop at all, so the sentence rule has nothing to match. Together with block 22 these cover
  all three rules. The threshold constant still has to be chosen, but it can now be chosen from
  observed behaviour rather than taste.

---

## 7. Conflicts with the other governing documents — see `decisions.md`

The workspace holds four governing contracts, not one. They were read in full and reconciled in
[`docs/decisions.md`](decisions.md). Summary of what affects this fixture:

- **C1 (was high — now RESOLVED)** — resume ownership. The Product & Architecture Contract states
  that "Resume and playback progress belong to the listening session and Active Queue, not
  permanently to individual Notes", and lists per-note permanent resume as a non-goal. The
  integration spec's `ReaderProgress` was keyed by `pageId`, which is that non-goal. **Fixed** by the
  2026-09-17 11:16 amendment: `# State and persistence` became `# Session persistence boundary`,
  which explicitly refuses ownership and delegates to the future Active Queue & Reader Session
  specification, exposing `ReaderDocumentPosition` as a handoff instead.
- **N1 (high — new, blocks any implementation)** — that same amendment left
  `## Phase 5 — Local progress persistence` asking for "stable resume state and edit-tolerant
  restoration", which is exactly what the new boundary disclaims. The phase list contradicts the
  spec body, so an implementer following phases in order would rebuild the rejected design.
- **C3 (medium-high, corrected)** — the spec's enums do **not** need new members for Skills or
  Inbox: `GENERIC` exists for unrecognized schemas and the spec mandates fallback. The live "Todo
  List" database must map to `TASKS` **by schema** (`Task name`/`Status`/`Due date`), not by
  database name, and `KNOWLEDGE` stays a future kind with no adapter until a source exists.
- **C2 (medium)** — no schema has a `relation` property, so `ReaderLibraryEntry.projectIds` cannot
  be populated and the Browse "Project" facet has nothing behind it.

None of these block Phases 3–4 against this fixture. N1 blocks any phase-by-phase implementation;
C2/C4/C5 block the Browse phase; C3 blocks Phase 8 (schema adapters).

**Round 1 of spec amendments landed 2026-09-17 11:16** — §7.1, §7.2 and §7.9 of `decisions.md`
applied; §7.3–§7.8 still outstanding. Mirrors in `docs/notion/` are regenerated and current.
