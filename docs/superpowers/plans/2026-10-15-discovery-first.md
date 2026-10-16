# Discovery-first Notion Reader Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans with test-driven-development. Record actual task evidence below.

**Goal:** Accessible local semantic retrieval, explicit interests and frozen guided listening.

**Architecture:** Domain ports/pure algorithms, data adapters, process-scoped application worker and existing composition root.

**Tech Stack:** Kotlin/Compose, coroutines, atomic private files; ONNX CPU/MiniLM only after feasibility gates.

**Spec:** docs/semantic-discovery-implementation-spec.md and authoritative Notion contracts.

## Constraints and review focus

Keep API26, layer rules, Browse semantics and playback ownership. Test cache/reset
races, partial refresh, complete token budgets, history compatibility and privacy.

## Tasks

- [x] Audit instructions, specs, decisions, cache, Browse, queues and test infrastructure.
- [x] Amend live Notion governing sources before behavior changes; fetch to verify.
- [x] Regenerate mirrors and inspect exporter changes.
- [x] Feasibility: pin artifacts; prove API26/ABIs/native packaging, parity, physical memory and quality before production dependency adoption.
- [x] Domain chunking/ranking/filters/ports: failing tests, intended RED, implement, GREEN suite.
- [x] Cache observation/persistence/interests/reset: failing-first destructive/race tests, implement, verify.
- [x] Worker/query orchestration: interruption/resume/priority/playback tests, implement process lifecycle.
- [x] Model acquisition/inference: integrity/cancellation/telemetry tests and actual native verification.
- [x] Discover/related/interests/settings/preview surfaces: application/navigation/semantics RED then GREEN.
- [x] Discovery origin: wire/history compatibility RED then GREEN, preserve old records.
- [ ] Actual retrieval evaluation and physical benchmarks; report failed gates honestly.
- [x] Project verification skill, JVM/build/androidTest and applicable targeted device checks.
- [x] Whole-change review; README and numbered decisions evidence with deferred/blocked work.

## Execution record

2026-10-15: User approved the full design and requested implementation. Working on
codex/discovery-first in the clean existing checkout. No commits/pushes requested.
Live product/UI/integration/session Notion sources amended; exporter running.
Runtime 1.31.0 AAR manifest declares minSdk24 and four ABIs; telemetry initializer
must be removed. Feasibility harness delegated; production dependency remains gated.

Latest boundary (15 October 2026): the local semantic, UI, interest, model-download and frozen queue-origin slices are implemented. Initial native/API26/tokenizer/quality evidence is in decisions §43 and tools/semantic-discovery. Automatic topics and the listening-first UI follow in §§45–46. The concurrent 10,000-passage physical performance target remains failed; TalkBack speech and listed hardware gates remain unmeasured. Checkmarks record delivered work, not blanket acceptance.
