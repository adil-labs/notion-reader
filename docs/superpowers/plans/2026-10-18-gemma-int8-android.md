# Gemma INT8 Android integration plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task by task. Preserve the existing dirty worktree; no reset or automatic commit.

**Goal:** Integrate EmbeddingGemma 2 INT8 into the Android app and verify real inference, discovered topics, connections, search and accessible navigation end to end on a device.

**Architecture:** Keep ONNX Runtime Android and the existing process-owned Discovery worker. Replace the embedding pack, tokenizer and identity coherently; preserve explicit acquisition, privacy, source withdrawal, interests and listening state. Keep one formation engine; host HNSW metrics do not establish the Android engine's calibration.

**Authority:** The user explicitly requests Gemma integration. Amend the live Product, UI and Integration sources to supersede their MiniLM-only clauses before changing production behavior; export and diff generated mirrors. Independent release-quality and human TalkBack gates remain separate from proving this integration works.

## Constraints

- Pin community export daa72c51243991dfcaf9f9137d2c573d8f7790c0 and exact INT8 graph, external weights and tokenizer hashes already recorded in the quantization artifact.
- Use 768-dimensional sentence_embedding output, never mean-pool the exported hidden states. Text-only modality inputs are empty float32 arrays with shape [0,512]. CPU execution uses two threads initially.
- Match the reference tokenizer exactly, including BOS/EOS, byte fallback, Unicode, whitespace, special tokens and offsets used for passage evidence. Never silently truncate input.
- Preserve the task: clustering | query: instruction used in the tested embedding space. Check search behavior explicitly before claiming it works.
- Do not reuse MiniLM vectors or checkpoints with Gemma. Preserve saved interests, queues and unrelated settings.
- External model weights belong beside the graph under its expected filename. Verify every asset before installation; interruption must never expose a partial model.
- Existing power, foreground, playback and pressure gates remain. Estimate actual on-device embedding work and report later analysis separately.
- D: has only about 140 MB free at investigation time. Keep model assets, large build outputs and device artifacts on C:; do not delete unrelated data.
- Do not clear or overwrite the attached phone's existing private library to seed a demonstration. Use isolated test fixtures and restore temporary device state.

## Tasks

### 1. Reference contract and migration

- [x] Inspect provider, model manager, tokenizer, chunker, wiring, current governing clauses and attached devices.
- [x] Amend live sources, export mirrors, inspect dependent requirements and record the requested replacement.
- [x] Establish a versioned Gemma pack/identity contract; add failing tests for all assets, external-file URL routing, old-pack rejection and partial download recovery.
- [x] Implement pack acquisition, verified atomic installation and truthful model/download UI.

### 2. Tokenizer and provider

- [x] Freeze reference token IDs and offsets from the pinned tokenizer for multilingual, whitespace, literal special-token and passage-boundary cases, plus real public passages.
- [x] Choose an Android-compatible implementation and prove parity; do not substitute the BERT tokenizer or approximate word splitting.
- [x] Add failing provider-contract tests for 768-dimensional normalized finite output, required inputs and explicit context overflow.
- [x] Integrate Gemma provider, retain bounded context with explicit instruction allowance, wire model identity and prove incompatible vector/checkpoint invalidation.
- [x] Compare actual Android embeddings with the host reference on the same inputs before interpreting discovery results.

### 3. Discovery and UI behavior

- [x] Evaluate the actual shipping formation/membership/connection path with Gemma vectors; calibrate distinct decisions rather than copying MiniLM cosine thresholds or attributing host graph results to it.
- [x] Add failing-first regression cases for supported subject publication, meaningful paired connections and unrelated-note exclusion.
- [x] Exercise search, topic evidence, full connection catalog, comparison, Back/focus, loading/power/pause states and estimated work. Fix observed defects.
- [x] Preserve source removal, index/model clearing, interrupted resume and playback isolation.

### 4. Device evidence and completion

- [x] Run JVM suite, debug assembly and Android-test compilation.
- [x] Run targeted instrumented accessibility/device tests through the repository wrapper with explicit serial.
- [x] Run real Gemma inference and complete a public-fixture indexing-to-topic/connection/search journey on device. Record device/API, actual model identity, work counts, outputs, timing and memory; inspect UI evidence.
- [x] Review changes, prove regression checks can fail, update README and numbered decisions with evidence and remaining release gates.
- [x] Audit the user's full integration objective against current artifacts before declaring completion.

## Initial historical findings

ONNX Runtime Android is pinned to 1.31.0. The existing provider hardcodes MiniLM, BERT token IDs, mean pooling and 384 dimensions. Acquisition hardcodes the MiniLM repository and routes only .onnx files into its onnx folder, so an external .onnx_data file would be fetched incorrectly. Gemma's pinned tokenizer has a 262,144-entry BPE vocabulary, 514,906 merges and 24 added tokens; its literal-space replacement, byte fallback and special-token processing require parity evidence. The phone connected during inspection is a Pixel 8 Pro; no emulator was connected. No Gemma Android inference has yet been established.


## Completion record

Integration tasks completed; see decisions §94 for native/device integration and
§95 for automatic background continuation and durable process recovery. The
original foreground constraint above was superseded through approved live-source
amendments. Representative quality, API26 runtime, full-scale resource and human
TalkBack release gates remain open. Physical API37 native evidence and API36
background evidence are distinct. The initial phone-test data-loss incident is
recorded in §94 and the integration artifact. No automatic commit was made.
