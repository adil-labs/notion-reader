# Notion Reader — agent guide

Notion Reader is an Android POC with one Gradle module (`:app`), Kotlin + Compose, and a single Activity. It is a TalkBack-first **listening reader**: it turns a Notion page into sequential, hands-free spoken playback and should behave more like an audio player than a Notion editor.

This file contains repository-specific rules and non-obvious constraints that agents must preserve. Product semantics come from the governing specifications, not from inference, existing UI gaps, or generic Android conventions.

## 1. Authority and precedence

### Governing specifications

Work is governed by these seven documents, in this precedence order:

| Rank | Document | Location |
| --- | --- | --- |
| 1 | Product & Architecture Contract | `docs/notion/product-and-architecture-contract.md` |
| 2 | UI Screens & Navigation Contract | `docs/notion/ui-screens-and-navigation-contract.md` |
| 2 | Notion Integration Implementation Spec | `docs/notion/notion-integration-implementation-spec.md` |
| 3 | Active Queue & Reader Session Spec | `docs/notion/active-queue-and-reader-session-spec.md` |
| 3 | Core Player & TTS Playback Spec | `docs/notion/core-player-and-tts-playback-spec.md` |
| 3 | Background Playback, Media Controls & Hands-Free Commands | `docs/background-playback-and-media-controls-spec.md` |
| 3 | Queue Creation & Queue Management | `docs/queue-creation-and-queue-management-spec.md` |

`docs/notion/notion-note-organization.md` is a note-authoring skill, not an app specification.

### What the other documents mean

- `README.md` is a **derived product/repository summary**: behavior overview, architecture diagram, build notes, and known not-yet-implemented areas. Keep it aligned, but it does not override a governing specification.
- `docs/decisions.md` is the **decision record, rationale, verification history, amendment queue, and current slice/open-work record**. Read the latest relevant sections before changing behavior. It does not silently override a governing specification.
- Code and tests describe the current implementation. They do **not** create product semantics when the governing documents say otherwise.

If two documents of different rank conflict, the higher-ranked document wins. If two governing documents of the **same rank** conflict or leave a material semantic ambiguity, stop implementation and resolve the specification first; do not choose one by convenience.

The conflict protocol is:

1. amend the governing source of truth;
2. review dependent specifications;
3. update affected specs/mirrors;
4. only then implement code.

A hand-authored document cannot amend a mirrored Notion specification by itself.

## 2. Generated vs hand-authored documents

Everything under `docs/notion/` is generated from Notion. **Never hand-edit it.** Make semantic changes in Notion, then regenerate:

```powershell
pwsh -File tools/export-notion-doc.ps1 -All
```

Always diff the regenerated files. An exporter defect must not be mistaken for a specification change.

The hand-authored files under `docs/` are edited directly. In particular, the two hand-authored governing specs above are authoritative in their own scope.

The Notion exporter currently has no table case. Do not add a Markdown table to a governing Notion page and assume it will appear in its mirror.

The integration spec stores the reader domain model in one Notion code block. An in-place patch to part of that block can replace the whole block. Inspect the target's full current text before patching it.

## 3. Before changing behavior

Before implementing a behavioral change:

1. Read the relevant governing specification(s).
2. Read the latest relevant sections of `docs/decisions.md`.
3. Check `README.md` for the current implementation boundary and known gaps.
4. Inspect existing tests that express the affected contract.
5. If semantics are missing or contradictory, fix the specification first.

**Do not "complete" a UI or workflow merely because it looks unfinished.** A missing control or screen may be deliberately blocked pending a specification. The current slice plan and open work belong in `docs/decisions.md`; do not duplicate that backlog here.

## 4. Architecture invariants

The layer rules are enforced by `LayerRulesTest`:

```text
domain      -> nothing
player      -> domain
application -> domain, player
data        -> domain
platform    -> player
ui          -> domain, player, application
```

`MainActivity` and `ReaderViewModel` live at the composition root. That root is the only place allowed to name `data`, `platform`, and `application` together for wiring. If a new dependency is genuinely required, change the architecture contract and `LayerRulesTest` deliberately rather than bypassing the rule.

### Lifetime is part of the architecture

**Nothing that must outlive a screen or task may be owned by that screen or task.**

The listening session, active queue, queue-advancement loop, and next-Note loading loop are process-scoped through `ReaderHost`, built by `ReaderApplication`. They are not owned by `MainActivity` or `ReaderViewModel`.

`ReaderViewModel.onCleared()` deliberately does not shut the listening session down. Moving only the session object without the loops that feed it is also incorrect; multi-note playback must continue after the task/screen lifecycle changes.

### Notion boundary

Notion DTOs never reach the reader domain. Content mapping consumes normalized data (`NormalizedBlock`), not raw `NotionBlock` DTOs. Keep transport/API shapes in the data layer and reader-domain types in `domain`.

## 5. Player and feature ownership

Respect ownership boundaries between specifications:

- Core Player & TTS Playback owns the Player's **closed command set**.
- Background Playback / Media Controls consumes those commands; it may not add, rename, or redefine them.
- Queue Creation & Queue Management owns Saved Queue persistence, Queue Destination, and the queue `Add` actions.
- Features whose semantics are not yet governed by the required specification remain unimplemented.

Do not invent a new cross-spec semantic because it makes an implementation easier.

## 6. Test conventions

This repository uses:

- JUnit 4 and `org.junit.Assert`;
- hand-written fakes/doubles beside tests;
- no mocking framework unless the repository contract is explicitly changed.

A fake must preserve destructive behavior when the real dependency is destructive. A harmless fake that cannot reproduce the production failure mode is not adequate evidence.

Test names are camelCase sentences that state the property, not the mechanism, for example:

```text
loadingANoteWhilePlayingLeavesTheSessionAlone
aSupersededNoteLoadCannotOverwriteTheNewerOne
```

Prefer contract/property assertions over implementation-shaped assertions.

### Regression discipline

Regression guards are **failing-first**:

1. remove or disable the production guard/fix;
2. confirm the intended regression test(s) fail for the expected reason;
3. confirm unrelated tests do not become the evidence accidentally;
4. restore the production fix;
5. confirm the tests pass.

Do not make a failing test green by deleting the assertion, weakening the property, widening a timeout without evidence, or ignoring the test.

A verification check is evidence only if it can fail for the reason it claims to detect.

### Fixtures and HTTP tests

`app/src/test/resources/fixtures/reader-fixture.json` is a real API capture governed by `docs/phase0-fixture-contract.md`. Re-capture it with `tools/capture-reader-fixture.ps1`; do not hand-edit it.

Ktor tests must use the production `configureForNotion()` configuration over `MockEngine`, so production headers, retry behavior, and error mapping remain under test.

## 7. Build and verification

Use the cheap JVM suite as the default development check:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

If `androidTest` changed, compile it explicitly because the JVM test and assemble tasks do not guarantee that it compiles:

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin
```

Instrumented tests are expensive and should be targeted while iterating. Use them when the change depends on real Android behavior such as accessibility semantics/focus, Back dispatch, device lifecycle/platform behavior, or an end-to-end smoke path.

Run instrumented accessibility tests through the repository wrapper, **not raw Gradle**:

```powershell
pwsh -File tools/run-accessibility-tests.ps1
pwsh -File tools/run-accessibility-tests.ps1 -Class com.example.notionreader.ui.PlayerScreenAccessibilityTest
```

### Testing speed and efficiency strategy (docs/decisions.md §24 & §25)

The instrumented suite is ~3,000× more expensive per test than the JVM suite (~4,700 ms vs ~1.5 ms per test). To keep development fast, reliable, and respectful of machine resources, follow these rules:

1. **Default to the cheap JVM suite for your inner loop:**
   `.\gradlew.bat :app:testDebugUnitTest` runs 560+ tests in ~2 seconds across parallel forks (`maxParallelForks`). Run this on every iteration.
2. **Never run the full instrumented suite while iterating:**
   Always target the specific affected class using `-Class` (e.g. `-Class com.example.notionreader.ui.PlayerScreenAccessibilityTest`). A single class finishes in **20–30 seconds**, compared to 10–15 minutes for the entire suite.
3. **Guard against emulator aging (§25):**
   The emulator's `android.hardware.graphics.composer3-service.ranchu` driver accumulates guest CPU load over extended sessions (load average climbing from 0.06 to 10+). This degrades per-test execution from 1.0s to 4.7s. Before a full instrumented suite run, restart the emulator cleanly:
   ```powershell
   pwsh -NoProfile -File tools/setup-emulator.ps1 -Stop
   pwsh -NoProfile -File tools/setup-emulator.ps1
   ```
4. **Pure Compose UI tests belong on the JVM with Robolectric (§24.2):**
   Compose UI semantics tests (`compose.setContent { ... }` with in-memory doubles) do not require real hardware sensors or Android kernel drivers. Moving pure UI tests to Robolectric drops per-test execution from 4,700 ms to ~50–100 ms on the JVM. Reserve device instrumentation for true platform integrations (MediaSession, Background Audio Service, and device life-cycle).

The wrapper owns device preparation, completeness checks, harness classification, retries, and the final verdict. A partial run is never a pass.

For flaky/racy behavior, **one green run is not proof of a fix**. Re-run enough to test the hypothesis and report the observed ratio honestly.

### Minimum definition of done

Do not call a change complete until all applicable items below are satisfied:

1. Relevant governing specs and latest decisions were checked.
2. Behavior was not invented to fill an unspecified gap.
3. Relevant unit/regression tests exist and can fail for the right reason.
4. `:app:testDebugUnitTest` passes.
5. `:app:assembleDebug` passes when production/build code changed.
6. `:app:compileDebugAndroidTestKotlin` passes when `androidTest` changed.
7. Relevant instrumented accessibility/device tests pass when the behavior requires them.
8. Generated Notion mirrors/fixtures were regenerated rather than hand-edited.
9. `README.md` and `docs/decisions.md` were updated when their summaries/history are affected.
10. New repository files are tracked intentionally; verification tooling is not left only in ignored build output.

If `.agents/skills/verify-notion-reader` is available, use it to execute the project verification order. The definition of done above remains authoritative even if the skill is unavailable.

## 8. Accessibility testing invariants

`app/src/androidTest/` tests the **semantics tree**, because that is what TalkBack consumes.

Use the repository `A11y` helpers:

- `assertIsReachable` for controls;
- `assertIsReadable` for readable non-controls.

Do not make a non-control tappable merely to satisfy a reachability assertion.

Drive controls using:

```kotlin
performSemanticsAction(SemanticsActions.OnClick)
```

Do not rely on synthetic touches for accessibility behavior. Assert reading order from the semantics tree rather than reconstructing it from geometry.

Focus restoration requires a real focus target. In this project, a `FocusRequester` attached only to `clickable` is not sufficient; the established `restoreFocusOn` pattern provides `focusRequester(...).focusable()` before `clickable`.

If `requestFocus()` returns `false`, treat that as evidence that the request was refused. Do not immediately convert a structural failure into a timing/retry hypothesis. Measure the primitive first. Historical details are in `docs/decisions.md` §23.29.

Do not delete, relax, or `@Ignore` accessibility assertions merely to obtain a green run.

## 9. Verification code must verify itself

This repository has had verification code report reassuring results while failing to observe its own subject. Preserve these rules:

- **Prove a check can fail for the right reason.**
- Prefer agreement between independent counts/signals over "no complaint was found".
- Test the shipping verification code, not a retyped copy that can drift.
- A retry is allowed only when every failure matches a known harness signature; mixed or unknown failures remain red.
- The expected instrumented-test count must come from the sources/reports, not from a remembered constant.

When changing `tools/run-accessibility-tests.ps1`, run the wrapper self-checks that apply, including the completeness/classifier/signature checks in `tools/`.

Historical failure modes and their proofs live in `docs/decisions.md` §23.30–§23.32; they do not need to be re-derived from memory.

## 10. Repository/tooling rules

### Android/device tooling

`tools/setup-emulator.ps1` is the preferred way to create an emulator for instrumented work when a physical device lock would otherwise block automation.

A locked physical device can make the accessibility suite look catastrophically broken. Let the wrapper prepare/validate the device before diagnosing application regressions.

A green emulator run is not automatically evidence for behavior that is specific to a different API level, device implementation, or TalkBack speech behavior. Match the test environment to the claim being made.

`connectedDebugAndroidTest`'s process exit code is not the repository's final verdict; the wrapper derives the verdict from collected results and completeness.

### `android` CLI

The Google `android` CLI is available on `PATH`. **Use it before guessing and before searching the web** — it
returns official Android documentation and reads the real device, which is what this project's rules already
require ("prefer `android docs search` / `docs fetch` over memory for current Android API, Compose semantics,
testing, and lifecycle behavior").

#### The documentation loop: search, then fetch

`search` returns a ranked list of `kb://` article URLs with one-line summaries; `fetch` returns the article.
Two steps rather than one, and the first run downloads a ~19 MB index, so it is slow once and fast after.

```powershell
# One topic per search. Keywords work; a question also works.
android docs search "Compose semantics mergeDescendants live region"

# Then fetch by the kb:// URL the search returned. This is the part that answers the question.
android docs fetch kb://android/develop/ui/compose/accessibility/merging-clearing
```

Worked examples from §24, which is what this section exists to make repeatable:

| Question | Search | What the docs actually said |
|---|---|---|
| Can these UI tests run without a device? | `"run Compose UI tests on the JVM with Robolectric"` | Yes — and the guidance named this suite's exact shape: "isolated UI behavior tests … don't interact with external dependencies" |
| Is there a hardware-optimised test image? | `"Automated Test Device ATD image for instrumented tests performance"` | Yes, but **"ATDs currently support only API level 30"** — a disqualifier found in one fetch that would otherwise have cost a device round |
| Is sharding worth it? | `"Scale your tests with build-managed devices"` | It divides wall clock, not cost, and the same page warns it is "resource intensive" |
| Do animations cost real time in a Compose test? | `"Compose UI test performance speed up test execution time"` → `kb://android/develop/ui/compose/animation/testing` | No — `mainClock` is **virtual**, so `advanceTimeBy` is not wall clock. That killed an experiment before it was run |

**Read the constraint sections, not just the capability.** Three of those four answers were decided by a
single documented limitation, and the limitation is the sentence that saves the round.

#### The device loop: look before you assert

```powershell
# Which app is actually focused? Not optional before interpreting a layout dump - with the keyguard up
# this describes the LOCK SCREEN in the same JSON shape as the application.
adb shell dumpsys window | Select-String mCurrentFocus

android layout --flat --diff --pretty              # UI tree as JSON; --diff keeps context small
android describe --project_dir .                   # prints the real APK path
android run --apks=app\build\outputs\apk\debug\app-debug.apk --activity=.MainActivity
```

For a probe that must *find* a control, prefer `android layout`'s JSON to `uiautomator dump`, and read the
target out of the dump rather than hard-coding coordinates — a probe that misses reports a wrong answer with
full confidence, and that has already happened here.

#### Measuring the device, which is where the time is

The instrumented suite's cost is dominated by the *device*, not the assertions (§24), so the useful CLI moves
are the ones that describe the device. These are read-only and cheap, and they are how the §24 numbers were
obtained:

```powershell
adb shell uptime                       # load average - the single best predictor of a slow run
adb shell cat /proc/meminfo            # MemTotal AND MemAvailable; a starved device kills system_server
adb shell top -b -n 1 -o %CPU,RES,ARGS # what is actually consuming the device
adb shell dumpsys SurfaceFlinger | Select-String GLES:   # the renderer really in use
adb shell wm size ; adb shell wm density                 # pixels composed per frame
adb shell nproc                                          # vCPUs, to compare against the host's
adb shell settings get global window_animation_scale     # and transition_/animator_
```

**The finding these produced (§25): the emulator ages, and it costs more than any configuration change.**
The same 11-test class ran at **1.03 s per test immediately after a restart and 4.3–4.8 s per test on a device
that had been used for a while**, with load average 0.06 against 10.34 and `system_server` burning 108% CPU
while idle. Restarting the emulator before a full run is worth more than every flag in the docs combined.


### PowerShell traps worth keeping in working memory

- Quote Gradle `-P` flags.
- Everything after `adb shell` runs in the **device shell**, not PowerShell; capture output and filter it locally.
- Use ordinal/case-sensitive collections or regex APIs for identifiers when case matters; common PowerShell operators/hashtables are case-insensitive by default.
- A PowerShell function's emitted output participates in its return value; keep diagnostics from contaminating returned data.
- Prefer `pwsh -NoProfile -File tools/<script>.ps1` for project tooling. Notion tooling requires PowerShell 7.
- Never edit VS Code's live `state.vscdb`; query a copy.

### Build configuration

Treat dependency/toolchain versions in `build.gradle.kts`, the version catalog, and adjacent comments/decision records as the current source of truth. Do not duplicate volatile version numbers in this guide.

Some pins are deliberate compatibility fixes. Before changing one, inspect its build comment and relevant decision history rather than assuming it is stale.

Credentials are currently resolved from local configuration and compiled into `BuildConfig`. **This is a POC mechanism, not an approved production secret-storage design.** Do not treat APK-embedded credentials as secure or extend that pattern into a production architecture.

## 11. Tools and skills

`tools/` contains repository verification and maintenance scripts. Prefer the repository script over reconstructing the same workflow manually when one exists.

Important entry points:

| Tool | Purpose |
| --- | --- |
| `tools/run-accessibility-tests.ps1` | Instrumented accessibility suite and authoritative wrapper verdict |
| `tools/setup-emulator.ps1` | Install/create/boot/check/stop the test emulator |
| `tools/check-wrapper-completeness.ps1` and classifier/signature checks | Prove the accessibility wrapper cannot silently misclassify/truncate runs |
| `tools/proof-*.ps1` | Failing-first proofs for regression guards |
| `tools/probe-*.ps1` | Drive real UI/device probes without hard-coded tap coordinates |
| `tools/capture-reader-fixture.ps1` | Re-capture the real Notion fixture |
| `tools/export-notion-doc.ps1` | Regenerate `docs/notion/` mirrors |
| `tools/notion-tool.ps1` | Read-only live Notion inspection |

On-demand agent skills live under `.agents/skills/` when installed. Project rules in this file and the governing specs override generic/vendor skills when they disagree. A skill explains **how** to perform a task; it does not decide **whether** the product should behave that way.

Project-specific skills required for completion should be committed/tracked. Check `git status --short` when adding tooling; a working file in an ignored/untracked location is not durable repository infrastructure.

## 12. Documentation and change record

When a round materially changes behavior, architecture, verification, or the explanation of a past defect:

- update the governing source first when semantics changed;
- regenerate mirrors if the source is Notion;
- keep `README.md` aligned with the implemented product boundary;
- append a new numbered section to `docs/decisions.md` when the round adds a durable decision, investigation result, regression proof, or verification lesson.

Record what was wrong as well as what changed when that distinction matters to future debugging.

Commit subjects are prose summaries of the round, not Conventional Commits. A multi-paragraph body may record rationale when useful.

## 13. Current work

Start with `docs/README.md` for the documentation map and the current entrypoint at the top of `docs/decisions.md`. Do not copy the current backlog into this file. Use the latest numbered current-slice / **Still open** section for outstanding work; §20.4 preserves the historical slice plan and is not an instruction to repeat completed work. Earlier decision rounds record the state observed at that time, not current completion claims.

Some acceptance gates require human observation (for example, TalkBack speech/listening behavior that automated semantics tests cannot observe). Do not claim those gates passed from code or emulator evidence alone.
