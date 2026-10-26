# Unified Knowledge System — design suite

Status: **proposed target architecture**, 2026-10-26. These documents do not authorize behavioral changes or establish completed verification.

## Authority and reading order
The [documentation guide](../README.md) and seven governing specifications retain precedence. In particular: [Product & Architecture](../notion/product-and-architecture-contract.md), [Notion Integration](../notion/notion-integration-implementation-spec.md), [UI & Navigation](../notion/ui-screens-and-navigation-contract.md), playback/queue contracts, and [Discovery direction](../discovery-v2/generative-direction.md). Notion documents here are generated mirrors: change the authoritative Notion page, export with `tools/export-notion-doc.ps1 -All`, inspect the diff; **never edit mirrors manually**.

Read [requirements](product-requirements.md) → [architecture](system-architecture.md) → [data model](knowledge-data-model.md) → [sync](ingestion-and-sync.md) → [discovery](discovery-and-retrieval.md) → [queries](knowledge-query-and-ranking.md) → [UX](user-experience-and-accessibility.md) → [acceptance](evaluation-and-acceptance.md) → [roadmap](implementation-roadmap.md).

## Scope
One local-first knowledge representation joins three **distinct** layers: (1) authoritative Notion structure; (2) explicit Notion references; (3) inferred semantic concepts/relationships. Do not write inferred data back to Notion or conflate similarity with factual dependency.

## Current vs target
Existing main branch has a section-aware semantic index, canonical topic catalog, Gemma topic label/definition overlays, retrieval of eligible Note passages, source invalidation and accessible topic screens. Coverage of *complete* Notion hierarchy, relations, direct passage navigation and unified graph queries has **not been exhaustively audited**. The target architecture and acceptance criteria below are proposals, not claims of shipped functionality.

## Approval protocol
Before coding, inventory existing APIs/storage/UI, reconcile any conflict in authoritative Notion contracts, preserve existing Discovery lifecycle and numerical gates, capture a decision in [decisions](../decisions.md), then implement against explicit tests. No new parallel topic catalog or competing native worker. 
