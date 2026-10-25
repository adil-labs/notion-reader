# Documentation guide

Start with [the repository summary](../README.md) for implemented behavior, then
[the current decision-record entry](decisions.md#115-published-litert-migration-and-s25-end-to-end-verification-2026-10-25)
for evidence and remaining work. Read the governing contract before changing behavior.

## Authority

The seven governing specifications, in precedence order, are:

1. [Product & Architecture](notion/product-and-architecture-contract.md).
2. [UI Screens & Navigation](notion/ui-screens-and-navigation-contract.md) and
   [Notion Integration](notion/notion-integration-implementation-spec.md).
3. [Active Queue & Reader Session](notion/active-queue-and-reader-session-spec.md),
   [Core Player & TTS](notion/core-player-and-tts-playback-spec.md),
   [Background Playback & Media Controls](background-playback-and-media-controls-spec.md),
   and [Queue Creation & Management](queue-creation-and-queue-management-spec.md).

Higher rank wins. Resolve material conflicts at the same rank in the governing
sources before implementing. Files in `notion/` are generated mirrors: amend
Notion, run `tools/export-notion-doc.ps1 -All`, and inspect the diff. Never patch a
mirror directly. The two hand-authored governing specs are edited locally.

## Discovery reading path

- The Product contract owns automatic discovery, evidence, privacy, interests,
  publication status and release acceptance. UI owns presentation and navigation;
  Integration owns the cache, index and persistence boundary.
- [The implementation companion](semantic-discovery-implementation-spec.md)
  explains local algorithms and limits subordinate to those contracts. Historical
  baseline policy does not override replacement-engine requirements.
- [The decision record](decisions.md) preserves rationale, regression proofs and
  observed verification. Its current entry points to the latest slice. Earlier
  numbered rounds are history, including their then-current open-work lists.
- [The delivery plan](superpowers/plans/2026-10-17-trustworthy-discovery.md) is a
  planning artifact; current completion and unresolved gates come from the latest
  decision round, not unchecked or historical plan prose.
- [Local AI evaluation](discovery-local-ai-evaluation-report.md) and
  [auxiliary model evaluation](auxiliary-discovery-model-evaluation.md) record
  experiments and provenance, not permission to migrate models or enable release.
  [Evaluation tooling](../tools/semantic-discovery/README.md) explains reproduction.

The authorized debug trial is distinct from general release acceptance. Users do
not need to judge private Notes. Developers evaluate representative public,
consented or purpose-built material; unobserved human listening gates remain open.

## Maintenance

Keep current requirements in their owning contract, implementation explanation in
the companion, and rationale/evidence in numbered decisions. Fold superseded
amendments into current requirements rather than accumulating conflicting rules.
Preserve numbered historical references so tests, tools and future investigations
can still find their evidence. Do not copy the backlog into `AGENTS.md`.

[The agent guide](../AGENTS.md) supplies repository workflow constraints and
[the verification skill](../.agents/skills/verify-notion-reader/SKILL.md) supplies
verification order. Neither establishes product behavior. Other plans under
`superpowers/` are historical designs unless a current decision explicitly adopts
them. [The fixture contract](phase0-fixture-contract.md) governs real API capture;
[Note organization](notion/notion-note-organization.md) is authoring guidance,
not an app specification.
