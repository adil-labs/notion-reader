# Bundled SentencePiece source offsets

The Android app uses LiteRT-LM's Kotlin `EmbeddingEngine` for inference. That API
does not expose source token offsets. This JNI bridge uses Google's SentencePiece
0.2.1 implementation through locked `sentencepiece`/`sentencepiece-sys` 0.14.0
bindings (MIT OR Apache-2.0 bindings; Apache-2.0 Google implementation).

Read the pinned tokenizer section directly into memory from the verified published
`.litertlm` bundle. Never write, convert, export or train a tokenizer/model. Section
offset/length/digest and BOS/EOS belong to `GemmaModelPack` and are independently
checked by `tools/semantic-discovery/inspect-litert-tokenizer.py` against the bundle
schema and metadata. Convert SentencePiece UTF-8 byte spans into UTF-16 offsets
before returning evidence to Kotlin. Literal control strings retain the bundle's
own behavior; there is no HF compatibility adapter.

`tools/build-discovery-tokenizer.ps1` builds host JNI and Android arm64-v8a/x86_64
with NDK r29, CMake 3.22.1, API 26 and 16 KiB alignment. C++ runtime archives are
linked statically. Link the C++ ABI archive by absolute path: adding the NDK's
unversioned library directory to `-L` can shadow x86_64 shared libc stubs with
non-PIC libc.a. The real-device test detects native-loader failures that compilation
alone cannot detect.

Run the asset-backed native tests with `GEMMA_LITERT_BUNDLE` pointing to the pinned
bundle, and `CARGO_TARGET_DIR` pointing under repository `build/`. The ordinary JVM
suite does not download model assets. `:app:verifyGemmaTokenizer` accepts the
`gemmaModelBundle` Gradle property and checks 109 independent Python SentencePiece
token/offset references, context boundaries and close/reopen through actual JNI.

Official guidance: [Android](https://developers.google.com/edge/litert-lm/android),
[embeddings](https://developers.google.com/edge/litert-lm/embedding_models),
[SentencePiece](https://github.com/google/sentencepiece).
