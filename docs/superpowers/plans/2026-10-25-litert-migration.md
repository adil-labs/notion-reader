# LiteRT migration implementation plan

**Goal:** Replace ONNX with published LiteRT/LiteRT-LM artifacts of the same EmbeddingGemma 2 text model and exercise real emulator inference.

**Architecture:** Keep the existing domain/provider boundary, bundled SentencePiece source-offset tokenizer, serialized process worker and explicit atomic model acquisition. LiteRT-LM owns inference through LiteRT; app ABIs become arm64-v8a and x86_64, API 26 remains.

**Spec:** Product and Integration contracts, user-requested LiteRT runtime migration amendment. User explicitly requires published official-repository artifacts: no local conversion/export.

## Tasks

- [x] Amend governing Notion sources, regenerate and inspect mirrors; preserve existing user changes.
- [x] Pin the published text-only INT4 bundle with its own authoritative tokenizer; prove artifact identity tests fail before replacing the pack.
- [x] Replace runtime provider/dependency and remove production MiniLM/ONNX path; migrate device checks without weakening historical reference assertions.
- [x] Verify real emulator CPU inference, output shape/norm, public input discrimination, source offsets and quantify old ONNX-reference differences separately.
- [x] Run JVM, APK and androidTest compilation, targeted wrapper tests and emulator probe; inspect packaged native ABIs and record evidence/limitations in README and decisions.

## Review focus

Old packs must remain until an explicit successful replacement; source offsets and token-budget checks must remain meaningful; native failure must not masquerade as a passing inference test; reference drift must be reported instead of weakening an old parity assertion; preserve all unrelated workspace changes.

## Execution ledger

Runtime 0.18.1 AAR inspected: arm64-v8a and x86_64 only, 16 KiB aligned, manifest minimum 24. User authorized dropping existing path after ABI question. Published model bytes downloaded through hf and SHA256 verified. Final design removes the external HF tokenizer and uses Google SentencePiece directly on the pinned bundle section in memory. Official clustering text prefix, trimmed input and preprocessing version 4 replace the old query prefix. Historical ONNX device controls are archived outside app source sets, while pure historical JVM policy helpers are test-only. User additionally requested S25 end-to-end verification; detected SM-S931B/API37 and obtained unlock. ARM64+x86_64 native builds, two native tests, 109-case JNI parity, JVM suite and APK/androidTest compile have passed; final device checks and record remain.

Final evidence: 1,113 JVM tests, both native tests, 109-case JNI parity, APK/androidTest compilation, emulator inference/journey, and S25 acquisition/inference/journey passed. Record and public metrics are in decisions section 115 and litert-migration-2026-10-25.json. Subsequent user-requested automatic plus Settings CPU/GPU/NPU selection is a separate next round.
