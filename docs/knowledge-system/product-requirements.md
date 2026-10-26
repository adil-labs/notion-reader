# Product requirements — user outcomes

Status: proposed; priorities are provisional, not validated user-research findings. Product goal: retrieve and apply saved knowledge with less effort than navigating Notion alone, without manual retagging.

## Users and jobs
- A reader who cannot remember a page title needs to find the relevant *passage* by meaning.
- A reader navigating a workspace needs the original page, database, block and section context.
- A learner needs grounded related material, resume/listen controls and predictable accessible navigation.
- A privacy-conscious offline reader needs usable core features without a generative model.

## Requirements and demonstrable outcomes
| ID | Priority | User need | Acceptance scenario |
|---|---|---|---|
| UR-01 | Must | Find knowledge without knowing its title | Semantic/text search leads to a current relevant source passage |
| UR-02 | Must | Browse authorized native Notion structure | Parent, children and database membership are correct |
| UR-03 | Must | Understand a Note in context | Breadcrumb, section and explicit relation kinds are distinguishable |
| UR-04 | Must | Discover meaningful concepts across Notes | Expected concepts appear in evaluated corpus |
| UR-05 | Must | Avoid generic topic labels | Repeated "Introduction" headings do not form topics by themselves |
| UR-06 | Must | Discover all supported Notes for a concept | Matching outside seed cluster is possible; incomplete scan is disclosed |
| UR-07 | Must | Know why a topic includes a Note | Open supporting eligible passage and revision |
| UR-08 | Must | Find unlinked but related knowledge | Suggested relationship has verifiable passage evidence |
| UR-09 | Must | Follow explicit Notion references | Relation, mention and hyperlink are navigable distinctly |
| UR-10 | Must | Jump to precise section or passage | Destination resolves or announces unavailable anchor |
| UR-11 | Must | Read and search offline | Cached permitted content works without network |
| UR-12 | Must | Preserve continuity after edits/moves | Unaffected progress and saved state remain; stale links withdraw |
| UR-13 | Must | Read without AI downloads | Core Browse, reader, TTS and supported lexical search function |
| UR-14 | Must | Avoid duplicates and false suggestions | False publications and redundant concepts measured |
| UR-15 | Must | Operate entirely with TalkBack | All routes and controls focusable, labeled and ordered |
| UR-16 | Must | Keep playback/navigation responsive | No mandatory synchronous discovery on open |
| UR-17 | Should | Continue learning across Notes | User can create and resume ordered contextual queue |
| UR-18 | Should | Revisit learning by concept | Browse previously saved related evidence and progress |
| UR-19 | Later | Review changes in understanding | Explicitly recorded changes distinguishable from inference |
| UR-20 | Must | Control privacy/resource consumption | Opt-in downloads, storage visibility, throttling and safe cancellation |

## Invariants
Notion remains authoritative for source content and explicit relationships. A Note may match multiple topics or none. Source withdrawal removes derived references promptly. Explicit links and inferred relations are labeled differently. User-authored aliases/follows/queues never vanish because a model rephrases a topic.

## Scope exclusion
Do not assume that semantic similarity establishes prerequisites, causality, factual correctness or learning order. Grounded synthesis, automatic learning plans and conversation-style answers are later work, subject to separate approval.

## Traceability
Implementation tasks and release evidence map to stable UR IDs in [acceptance](evaluation-and-acceptance.md) and [roadmap](implementation-roadmap.md). Existing governing requirements take precedence if they differ.
