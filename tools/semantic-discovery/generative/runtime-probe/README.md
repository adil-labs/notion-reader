# Isolated Gemma 4 E2B Android probe

This separate developer application has no Reader dependency, credentials, Internet permission or private Note access. It runs purpose-built `cases.json` through the real pinned LiteRT-LM Android 0.18.1 API. It does not install a production model or establish release acceptance.

Build from repository root:

```powershell
.\gradlew.bat -p tools/semantic-discovery/generative/runtime-probe assembleDebug
```

Acquire the exact published artifact described in `docs/discovery-v2/gemma4-e2b-runtime-report.md` into ignored `build/generative-discovery/model/`, preserving its immutable revision, byte count and SHA256. Then run:

```powershell
pwsh -NoProfile -File tools/semantic-discovery/generative/run-runtime-probe.ps1 -Device RZGL30JYLEN -Backend CPU
pwsh -NoProfile -File tools/semantic-discovery/generative/run-runtime-probe.ps1 -Device RZGL30JYLEN -Backend GPU -SkipStage
```

The runner verifies both host/device artifact identity (device-only when already staged), stages only this probe package, clears only its previous report and starts a new public-input run. An observation timeout does not mean native inference ended: inspect the same live process before restarting. Never launch a second probe while one is running.

Each invocation loads once and creates two fresh conversations to observe cold/warm generation with the bundled chat template, no tools, thinking disabled and top-k 1. Initial experiment settings are 2,048 context tokens / 512 output tokens, not production acceptance limits. Native context validation still applies; no text is silently truncated. Raw public outputs, first-token/total generation timing, load timing and sampled PSS are saved externally under the probe package and copied to ignored build output. Preserve accepted evidence in intentional tracked reports after review.

The 50-ms sampled process PSS maximum is not an allocation-trace peak or the governing added Java/native memory metric. This first probe does not yet prove cancellation latency, thermal stability, tokenizer/source budgeting, engine swapping, search/playback contention or corpus-scale processing. A completed report establishes only that the bounded invocation returned; review its actual output independently.

Shipping-worker lifecycle cases use the actual controller, shared gate, native embedding/label engines and file stores compiled verbatim from Reader. They operate on three public seed Notes with one distinct repeated body, so they establish bounded integration behavior rather than representative scale or semantic acceptance:

```powershell
pwsh -NoProfile -File tools/semantic-discovery/generative/run-runtime-probe.ps1 -Device RZGL30JYLEN -Backend GPU -Case production-worker-withdraw -SkipStage
pwsh -NoProfile -File tools/semantic-discovery/generative/run-runtime-probe.ps1 -Device RZGL30JYLEN -Backend GPU -Case production-worker-reset -SkipStage
```

Each waits for an observed native generation start, waits 500 ms, requires generation still active, then removes one actual cached source or calls the actual controller reset. It requires a native cancellation terminal, no saved stale label/cursor and the expected source/index state. Model availability stays unchanged until after the interrupted worker returns, avoiding a model-withdrawal cancellation confound. Reset retains the three cached source Notes. The runner changes only the separate probe package; it must never target Reader. A negative-control run with only the native watcher disabled returned a normal native terminal and failed these checks, rather than producing a false pass. Frozen reports and source/APK manifests are in ../evidence/.

`-Case production-worker-storage` injects StorageRefused(DEVICE_SPACE) at the actual label store's admission boundary after real generation. It requires an actionable storage error, no unsaved displayed/cache name, retained source/index files, then lifts the refusal and requires successful retry. This is not a physically full-device experiment.

`-Case production-worker-model-delete` moves only the probe's already-verified external generator artifact into the actual GenerativeModelManager's current directory, writes its revision receipt and re-verifies installation. After actual labeling and saving a follow alias, it calls the real manager's delete with the same release-before-file-deletion callback arrangement as Reader. It requires absent model files, zero manager-owned bytes, retained cached labels/alias/index/embedding pack and working search after controller recreation. This tests deletion after generation, not deletion during active native generation. A successful run intentionally consumes the probe copy: re-stage the pinned host artifact with adb push to the probe's external-files root and verify SHA256 before another run. Never target Reader's directories or replace its installed model. Frozen reports/manifests identify the exact harness/source revision used.

`-Case topic-name-queries -Backend CPU` reuses the completed fresh-v1 public worker fixture and actual generated labels. It requires the original source/corpus hashes and current cache/index revisions, then uses the shipping GemmaEmbeddingProvider and cosine calculation to score each named topic against every source record. It records a missing name as skipped rather than inventing a query. It does not change source memberships or Reader behavior. Run score_name_queries.py with the frozen origin, explicit earlier reference judgments and complete query report for a seen-corpus calibration sweep. No threshold in this diagnostic is a production policy or independent semantic acceptance.
