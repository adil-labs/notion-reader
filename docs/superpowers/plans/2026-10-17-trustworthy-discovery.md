# Trustworthy Discovery Implementation Plan

**Status:** Implementation and debug-trial verification are recorded in
[decisions §72](../../decisions.md#72-automatic-discovery-implementation-and-authorized-android-debug-trial-2026-10-17).
Use [the current decision entry](../../decisions.md#73-current-implementation-open-work-and-documentation-map-2026-10-17)
for remaining acceptance work. This plan is not a governing specification.

**Goal:** Complete evidence-supported discovery without silent coverage limits, with explicit follows and a separate emerging-topic collection.

**Architecture:** Keep MiniLM and the experimental engine flag, enabled for the authorized debug trial and disabled for general release. Domain owns candidates, evidence and resumable state; the process-scoped coordinator persists bounded work and publishes stable snapshots. Saved interests remain independent user data.

**Spec:** User-approved Trustworthy Discovery delivery roadmap; authoritative Notion Product/UI amendments must be regenerated before behavior changes.

## Constraints and review focus

- Preserve existing uncommitted work and shipping behavior with the flag disabled.
- No private corpus text or human judgments in tracked artifacts; provisional annotations are not human truth.
- Resource limits pause work; they must not permanently skip eligible sections.
- Existing follows survive splits, merges and source loss; source removal still withdraws private evidence immediately.
- No device or human quality claims from synthetic JVM timing or semantics alone.

## Tasks

- [x] Audit and amend authoritative Product/UI contracts; regenerate mirrors and reconcile derived documentation.
- [x] Add failing coverage/resume/identity tests; implement bounded resumable formation, checkpoint validation and coordinator scheduling.
- [x] Add failing publication/emerging tests; separate validated emerging topics and preserve evidence-backed Follow/details routes.
- [x] Add evaluation provenance validation and same-corpus baseline comparison tooling; preserve unreviewed and provisional status.
- [x] Add Android coverage benchmarks beyond 2,000 sections and record scoped emulator/physical observations. The repeated-vector, model-free workload does not satisfy the full realistic resource and playback gate; physical added memory exceeded the budget.
- [x] Run JVM tests, assembly, Android-test compilation, targeted accessibility wrappers and device probes; record observed results and their limits in §72.
- [ ] Complete developer-owned held-out quality review, model-loaded varied Android resource/playback validation, resolve the physical memory overrun, and observe human TalkBack/listening acceptance before general release enablement.

## Acceptance

Coverage, restart, invalidation, order invariance, independent IDs, multi-membership and emerging promotion require contract tests. Publication requires source-backed labels/evidence. Quality and Android gates govern general release enablement. Human quality evaluation is development work on public, consented or purpose-built Notes; no end-user or private-library review is required.

The user authorized an Android debug trial on 2026-10-17 despite outstanding gates.
Debug builds now enable the replacement; release retains the baseline. Build with
`-PexperimentalDiscovery=false` for a debug rollback. This trial does not convert
provisional judgments into human ground truth or mark unresolved gates as passed.
