# Evaluation, release gates and requirement traceability

Status: proposed test plan. Existing numerical Discovery, playback, resource and accessibility gates remain binding unless amended in their owning contracts.

## Evidence classes
1. Reproducible purpose-built/public fixtures with frozen expected concepts and negative cases.
2. Independent semantic judgments (record who authored fixtures and who reviewed outputs); agent-reviewed debug evidence is **not** human general-release evidence.
3. S25 and other-device measurements for CPU/native/PSS, swap, latency, cancellation, energy/playback interactions and Android termination diagnostics.
4. TalkBack speech/navigation observation plus automated accessibility assertions.
5. Consented real-workspace usability trials only after privacy approval; no unconsented Notes in test artifacts.

## Acceptance matrix
| UR | Verification |
|---|---|
| 01,10 | Blind find-and-open passage task succeeds; exact source anchor and eligible revision |
| 02,03,09 | Fixture hierarchy, block nesting, properties, links and relations match source |
| 04,05,06,07,14 | Reviewed concept recall, generic-heading false publications, membership precision/recall, duplicate rate, source citations; incomplete scans identified |
| 08 | Human/independent judged top-three suggestions are relevant and supporting passages correct |
| 11,12,13 | Offline/restart/edit/move/model-unavailable tests preserve reads, search, queues and state |
| 15 | TalkBack focus, announcements, headings, route restoration, large list behavior |
| 16,20 | Open-note latency, search p95, memory/PSS/swap, resource pressure, cancellation, playback contention under published gates |
| 17,18 | Queue creation, cross-Note resume and concept history journeys |
| 19 | Deferred; no inferred timeline marketed as an explicit learning record |

## Release policy
For semantic experiments compare native/explicit-only, deterministic+embedding, and current Gemma-named concept approaches on compatible sources without changing published policy mid-holdout. Measure false positives **and** omissions. Hard reject invalid/withdrawn evidence regardless of aggregate score. Record coverage denominator, reasons for skipped samples, device version, model hashes, runtime, seeds and limitations.

Carry forward active numerical limits from [Discovery v2 acceptance](../discovery-v2/evaluation-acceptance.md) and [generative direction](../discovery-v2/generative-direction.md). Previously documented 256 MiB added generation allocation gate and 128 MiB embedding gate are *project provisional gates*, not Android/Google guarantees. Do not call a test passed based on source inspection or absent CI status.

## Evidence record template
Requirement ID; code commit; fixture revision; device/API; model/policy identity; steps; expected vs actual; source/evidence location; human/agent reviewer identity; outcome (pass/fail/unobserved); known limits; date. Unobserved remains open, not pass.
