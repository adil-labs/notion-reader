# Historical ONNX controls

These unchanged sources preserve the MiniLM and Gemma ONNX benchmark/inference
assertions used before the LiteRT migration. They are outside the reader app's
production and instrumented source sets. No ONNX runtime or legacy native HF
tokenizer is needed by the app or its test APK.

Fourteen historical device tests were moved here intentionally when retiring
their runtime. This is a source-set retirement, not evidence that those checks
passed under LiteRT. Their original model identities, vector references, quality
thresholds and assertions remain intact. Historical reproduction requires the
corresponding repository revision's harness and native tokenizer. The separate
MiniLM `android-benchmark` harness consumes the archived provider/tokenizer sources.

Current verification uses `LiteRtGemmaDeviceTest`, `GemmaAcquisitionDeviceTest`,
`GemmaDeploymentDeviceTest` and `GemmaDiscoveryJourneyTest` against the published
LiteRT bundle. Old ONNX cosine measurements are diagnostic comparisons, not a
claim that the published LiteRT quantization reproduces ONNX vectors or quality.
