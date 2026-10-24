# Coordinated Storage Settings Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. AGY implements and audits; Codex independently reviews and fixes.

**Goal:** Make storage accounting and consequences clear across Discovery and Offline & storage.

**Architecture:** Presentation changes over existing storage policy and persisted settings. Reuse Compose settings components; expose separate typed usage fields instead of parsing concatenated display strings where necessary.

**Tech Stack:** Kotlin, Compose Material 3, existing JUnit 4 and accessibility test infrastructure.

**Spec:** docs/superpowers/specs/2026-10-24-storage-settings-design.md

## Global Constraints

- Read AGENTS.md, governing storage amendments, latest decisions and existing storage tests first.
- Preserve all pre-existing workspace changes; no commits, resets, stash, or private data deletion.
- Never hand-edit docs/notion or fixtures. Preserve actual storage policy, persistence, clear/reset effects and source withdrawal.
- No new dependencies. MiB/GiB labels retain binary meanings. Preserve accurate partial/resource-limit messages.

## Review Focus

- Zero usage is distinct from missing measurements; do not manufacture a measured zero.
- Custom limits survive toggling Automatic; lowering a Discovery maximum retains existing eligible data.
- Note-cache reductions retain confirmation and protected-queue deferral.
- Larger limits affect both categories and remain discoverable when explanation is collapsed.
- Accessible adjustment, radio selection, headings and expansion work without touch coordinates.

## Task 1: Coordinate presentation

Files: ui/settings/StorageControls.kt, OfflineSettingsScreen.kt, DiscoverySettingsScreen.kt; ui/ReaderShell.kt for separate measured model/data usage.

- [x] Inspect existing storage and settings accessibility tests and production APIs. Read governing specs and the design.
- [x] Add focused contract assertions for the five review cases where this presentation changes observable behavior. Demonstrate failure against the old presentation before implementing; preserve existing assertions where behavior remains applicable.
- [x] Implement shared usage-first sections, accessible Automatic/custom radio selection, explanatory expansion and truthful consequences. Keep existing callbacks and authoritative settings state. Adjust affected callers/tests without weakening properties.
- [x] Run :app:testDebugUnitTest; fix failures within scope.

## Task 2: Verify and document

- [x] Use .agents/skills/verify-notion-reader/SKILL.md. Run assembleDebug and compileDebugAndroidTestKotlin. Run targeted affected accessibility classes through tools/run-accessibility-tests.ps1, never raw connectedDebugAndroidTest. Prefer emulator for synthetic test actions; do not mutate private phone storage.
- [x] Probe user-visible flow if a suitable device is available; inspect foreground before interpreting layout. If unavailable, record the limitation accurately.
- [x] Audit the final diff for policy drift, duplicate accessibility nodes, hidden active warnings, misleading usage and broken persistence. Fix findings.
- [x] Append a new numbered decisions section and align README/docs entrypoint as appropriate. Report exact commands/results, failing-first evidence, remaining gates and changed files. Do not claim unavailable device or human TalkBack acceptance.
