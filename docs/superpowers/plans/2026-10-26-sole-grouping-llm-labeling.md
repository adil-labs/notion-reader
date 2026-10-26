# Existing grouping with local LLM labels implementation plan

> Execute inline with superpowers:executing-plans. The user approved the sole grouping-plus-labeling direction on 2026-10-26. The former full-concept-formation plan is superseded for current implementation.

**Goal:** Replace the current topic catalog in place with the existing canonical groups and evidence-grounded local Gemma labels, as the only Discovery pipeline.

**Architecture:** Domain owns immutable label requests/results and source validation. Data owns pinned acquisition, LiteRT-LM and bounded label persistence. Application schedules through the existing process-owned coordinator and serialized gate; composition root wires dependencies. There is no second formation engine, catalog or worker. Domain ports are used because data cannot import application.

**Design:** docs/discovery-v2/sole-grouping-labeling-design.md and the owning Notion amendments. Tech remains Kotlin, JUnit4/handwritten fakes, LiteRT-LM0.18.1 with verified Gemma4 E2B artifact. No implicit acquisition or cloud path.

## 1. Typed label contract and invalidation

Create domain/discovery/TopicLabeling.kt and TopicLabelingTest.kt. Define immutable evidence, identity, request, generated label and explicit runtime outcomes. Label response contains only a name and evidence IDs, never topic membership edits. Reject changed source epoch/fingerprint, altered/duplicate citations, blank/oversized/malformed Unicode labels. Bind cache reuse to full group evidence plus selected bundle and generator identity; errors are never successful cache entries. Tests must fail without each guard; verify accepted labels preserve topic ID. Structural validation does not establish semantic truth.

## 2. Real runtime and optional acquisition

Implement data/discovery/GemmaTopicLabeler.kt and GenerativeModelManager.kt behind domain ports. Reuse existing atomic acquisition/storage patterns and the verified artifact bytes/checksum, with separate file accounting. Use bounded closed JSON and actual tokenizer/context accounting, not character estimates. Cover missing/wrong pack, malformed output, native failure, cancellation and destructive engine close with fakes and actual public S25 execution. Freeze the chosen naming prompt/schema before fresh quality evaluation. No new full-formation prompt work.

## 3. Serialized scheduling and durable label work

Integrate with DiscoveryRuntime, DiscoveryWorkCoordinator, TopicCoordinator and ReaderApplication. Share the existing bulk gate; close embedding engine before labeling and close generator before search. Native cancellation must be bridged explicitly and terminal observed. Coalesce identical work, reuse successful compatible labels, persist pending fairness cursors, defer under power/memory pressure and pause during playback. Tests cover search preemption, no generation on open, no duplicate calls on unchanged evidence, version-only relabel without embeddings, source deletion/reset during inference and process restart. One coordinator owns all work.

## 4. Bounded persistence and effective display labels

Use bounded atomic FileTopicLabelStore sidecars keyed by canonical topic ID; preserve the existing StoredTopicGraph passage/vector deduplication. This 2026-10-26 implementation ruling avoids rewriting all topic evidence on each completed label. Integrate sidecar accounting and cleanup with canonical storage, without a second catalog. Store no repeated vectors/bodies in label records. Atomically save current compatible labels after source revalidation. Migrate old records as pending labels without clearing topics, Notes, follows, interests, queues or compatible embeddings. Test interrupted/oversized/corrupt writes, rollback, stale restore and late callbacks. Display precedence is explicit user alias, current generated label, then neutral unnamed status. Original cluster labels are evidence only, never display/default-follow/fallback names. Audit titles, recommendations, queue names and accessibility semantics for raw cluster-label leakage.

## 5. Single publication and catalog integration

Apply naming to grouping candidates before label-dependent publication so poor extractive names can improve. Keep membership/source/publication/continuity rules. Adapt literal-name validators to accept supported generated-label provenance without pretending deterministic checks prove semantics. Discover, See all topics, topic details and followed-topic views consume the same canonical records. No parallel catalog, label-based merge, global cap or algorithm selector. Test identical names on unrelated groups, follow/alias preservation, one-Note status, withdrawal from pinned views, lazy catalog and stable inspection until Refresh.

## 6. Settings and accessible status

Expose explicit generative pack download/cancel/delete, separate bytes and missing/failure state. Explain preparation versus naming, pending work and pressure/power waits without fake estimates or announcement spam. Preserve reading/search when unavailable. Compile changed androidTest sources and target relevant accessibility classes through tools/run-accessibility-tests.ps1. Verify real focus targets and neutral unnamed views; never restore cluster wording as a display name.

## 7. Acceptance and sole-path adoption

Freeze code/model/prompt/schema/group policy and a fresh family-separated naming corpus, preserving previous failed formation and descriptive control results. Meet the governing semantic sample sizes/targets without counting expected memberships as predicted observations. Benchmark S25 and slower supported device: representative10k passages/>2k sections, active time, cancellation, native allocation/total pressure, engine swaps, warm-search p95, playback, process death and source removal. Incremental relabeling savings must be measured rather than extrapolated. Use verify-notion-reader for JVM suite, debug build, androidTest compile and relevant wrapper/device evidence. Independent human speech/general-release gates remain unobserved until performed.

## Verification and commits

For each behavioral guard, observe the intended regression fail with the guard absent, restore it and pass the same tests. Run the JVM suite for each implementation slice; debug assembly for production changes. Record exact evidence and open gates in docs/decisions.md and execution-ledger.md. Keep focused prose commits after project verification, preserve unrelated work and never push automatically. Old experiment tooling may remain outside shipping code as historical evidence; it must not become a selectable production path.


## Current implementation checkpoint — 2026-10-26

Domain requests/validation, real native label adapter and separate model-manager components exist. Earlier public S25 native-port generation/cancellation smoke is preserved. Shared runtime scheduling now centralizes all application embedding calls, gives foreground requests priority, waits for native cleanup before releasing gates, and monitors policy/source/model validity during labeling. Existing search/interest/indexing paths use that runtime. Controller accepts the label runtime but composition-root construction and the actual grouping-to-label work loop are still pending.

Atomic label sidecars, stable bounded evidence selection and cache-reuse validation exist and have failing-first checks. Durable keys exclude live epoch while commit tickets still reject stale callbacks. The sidecars are not yet wired into TopicCoordinator reset/withdrawal, storage inventory, display overlay, resumable/fair pending work or settings. Continue with those integration steps rather than treating the presence of these components as end-to-end completion. Full JVM suite has 1,159 passing tests; debug assembly and androidTest Kotlin compilation pass. No new catalog/runtime device acceptance follows from JVM ordering tests alone.

## Continuation checkpoint — canonical lifecycle and presentation

TopicCoordinator now invokes the label collaborator after canonical grouping commits and on unchanged completed grouping. It restores compatible saved labels on restart, accounts sidecar bytes, prunes/clears with source/reset, and publishes names outside native execution locks while retaining inspected views until Refresh. DiscoveryController connects the optional store/identity/runtime through DiscoveryRuntime. Shared display helpers cover cards/details/relationship links/preview titles and explicit aliases; follow defaults never use cluster names. Naming availability has its own state and remains visible when embedding/grouping work is complete. Current evidence: 1,182 passing JVM tests, debug assembly and Android-test compilation; S25 complete wrapper results8/8 topic rows and22/22 navigation. Root construction/storage inventory/model controls, unnamed candidate/rejected-name policy, exact naming citations and real native/fresh acceptance remain open. Do not activate native labeling from ReaderApplication before those gates.

## Continuation checkpoint — root/model/settings integration

ReaderApplication now constructs the native generator, separate optional model manager and atomic label store, includes labels in the derived inventory, and exposes separate model controls through ReaderShell/ReaderApp. Model-owned bytes measure actual current/staging/previous files without counting siblings; clear-all deletes the generator. Native deletion closes under existing inference ownership and preserves compatible saved discoveries/embeddings/interests. Device-memory facts are sampled at bounded frequency and forced before expensive work; modern UI-hidden trim events release native resources. Full suite1187/1187, debug assembly/androidTest compile, S25 settings2/2 with failing-first evidence. User explicitly targets production enablement. The acceptance flag remains off with visible explanation until the remaining actual native/fresh semantic/scale/resource gates pass. Real-root UI probe remains unverified because S25 keyguard stayed focused after a successful cold launch. Continue with unnamed-candidate publication, rejected-name behavior and exact naming-evidence display, then real-worker and final acceptance.
