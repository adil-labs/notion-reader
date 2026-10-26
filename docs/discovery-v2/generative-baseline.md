# Generative Discovery baseline — 2026-10-25

Baseline production commit: `a57d772` on `codex/litert-backend-selection`. The two existing uncommitted files at task start were the LLM plan and its Discovery v2 index link; they are retained.

## Current production

- Formation: `SectionHierarchicalEngine.VERSION = 4`, shipping policy version `16` with formation distance `.10`, membership `.90`, retention `.89`, work quantum `250_000`. Constructor defaults `.35`/`.60` are not the shipping configuration (`domain/discovery/TopicDiscoveryEngine.kt`). Exact engine, not a generative pipeline.
- Embedding runtime: `com.google.ai.edge.litertlm:litertlm-android:0.18.1` (`app/build.gradle.kts`). Base EmbeddingGemma bundle remains the existing pinned 164626432-byte artifact; see governing contracts and production model manager for full identity.
- Backend selection: Automatic/CPU/GPU/NPU through `LiteRtBackendRuntime`; no generation backend has been measured.
- S25 connected as `RZGL30JYLEN`; emulator `emulator-5554` also present. No device performance claim follows from connection status.
- JVM baseline command `:app:testDebugUnitTest` succeeded with task UP-TO-DATE on 2026-10-25. XML reports contain 1,123 tests, zero failures/errors/skips. This checks Gradle cache validity; it is not a newly executed regression proof.

## Frozen comparisons still required

No new public-corpus baseline replay, reviewed gold partition, generative topic output or S25 generation measurement exists in this round yet. Historical results in `docs/discovery-wos-evaluation.md`, `docs/auxiliary-discovery-model-evaluation.md` and `tools/semantic-discovery/topic-naming-experiment/` are baseline context, not substitutes for the plan's identical-source/model comparison.

Phase 0 source amendments and requirement disposition do not complete the baseline replay or human-quality gates. Track these as open rather than silently marking Phase 0 finished.

## 2026-10-26 — identical-source shipping replay

The isolated S25 probe embedded all 87 eligible passages (one revoked passage excluded) across 34 cases using the pinned production EmbeddingGemma artifact, CPU with two threads, LiteRT-LM 0.18.1. Embedding took 7,156 ms; this excludes model validation, host preparation and formation. The host preparation compiles the actual production tokenizer/chunker and metadata sources. The host replay compiles the actual production formation engine and applies GemmaModelPack policy version 16, not constructor defaults.

The replay completed with 52 proposals. Compact proposals are in `tools/semantic-discovery/generative/evidence/s25-shipping-baseline-proposals.json`; compressed raw prepared inputs, embedded vectors and complete snapshots sit beside it with SHA256 identities in the proposal manifest. These are formation proposals, not final publication or semantic-quality acceptance. Random topic identities are preserved from this run; repeat-run ID equality is not asserted. Semantic review and label-only comparison remain open.

Extracted the unchanged ModelArtifact value type into its own data-layer file so the isolated host runner can compile production artifact metadata without the Android model manager. Main application JVM tests and debug assembly passed after the move. The evaluator's 19 tests also pass. No production topic-generation behavior was enabled.
