---
name: verify-notion-reader
description: "Use when a change in the notion-reader workspace is ready to be called done, before committing, or when a build or test result looks too good or reads as a catastrophic regression. Runs the verification order this project needs — JVM suite, androidTest compilation, the instrumented accessibility suite through its wrapper, a device probe for user-visible flows, and the decisions.md round record — and lists the failure modes that make a green result meaningless here."
---

# Verifying a change in notion-reader

## Why this is a skill and not a command

Several checks in this repository can pass while proving nothing, and several can fail while the app
is fine. A single `gradlew test` is not verification here: it does not compile the instrumented
source set, it cannot see semantics, and it cannot see the device at all. This is the order that has
actually caught things, and the reason each step exists.

Read [AGENTS.md](../../../AGENTS.md) first if you have not. The governing documents and the decision
record are described there.

## 0. Classify the change

| Change | Steps |
|---|---|
| `docs/notion/*.md` regeneration, or a Notion spec amendment | 4 only |
| Pure `domain` / `data` / `application` Kotlin | 1, 2 |
| Anything a screen or the shell renders, or an accessibility property | 1, 2, 3 |
| A user-visible flow (navigation, playback, queues, persistence) | 1, 2, 3, **5** |
| A regression guard (a bug fix with a new test) | 1 (+**failing-first proof**, see below) |

## 1. JVM suite

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expect ~534 tests and 0 failures. The count is not the point; a *drop* is — it usually means a test
class stopped being discovered rather than that tests were removed.

For a **regression guard**, produce a failing-first proof before moving on: remove or disable exactly
the guard, confirm that **exactly the named tests** fail and no others, then restore it. Record the
result as a table (`Guard removed | Tests that noticed`) in the round's `docs/decisions.md` section.
One guard failing two tests is a legitimate result — say so rather than smoothing it.

## 2. Compile the instrumented source set

```powershell
.\gradlew.bat :app:assembleDebug :app:compileDebugAndroidTestKotlin
```

Step 1 and `assembleDebug` **do not compile `androidTest`**. A broken instrumented suite has been
committed behind a fully green JVM run here, and `LayerRulesTest` cannot catch it either, because it
scans `src/main` only. Never skip this after touching `app/src/androidTest/`.

## 3. Instrumented accessibility suite

**Before you run this, ask whether it is the right instrument** (§24). It is 180 tests at **4.7 s each —
~15 minutes** — and the cost is *fixed per-test setup*, identical for every class. A change about a semantics
tree that a screen renders from state passed in does not need a device. Use `-Class` while iterating:

```powershell
pwsh -File tools\run-accessibility-tests.ps1
pwsh -File tools\run-accessibility-tests.ps1 -Class com.example.notionreader.ui.PlayerScreenAccessibilityTest
```

Never invoke `connectedDebugAndroidTest` directly:

- **It requires an unlocked device and will not stop until it has one.** With the keyguard up the test
  activity never foregrounds, nothing is drawn, and *every* test fails with "No compose hierarchies
  found in the app" — a shape that reads as a total regression when the only problem is a phone that
  locked itself. If you see many uniform failures, check the keyguard before suspecting the app.
- **It raises `screen_off_timeout`** and restores it in a `finally`. `stay_on_while_plugged_in` does
  nothing on battery, which is the usual case.
- **Its exit code comes from the collected results**, read from both XML and HTML with the worse count,
  because the two have disagreed in both directions. `connectedDebugAndroidTest` itself can exit
  non-zero with every test passing.
- **It retries only when every failure matches a known harness signature.** A mixed or unclassified
  result is red immediately. That refusal is correct: if the run retried, it would bury a real failure
  inside a "flaky" verdict. A failure recorded with **no message and no trace** counts as the harness
  (§23.32) — an assertion always leaves a trace, so an empty record means the process never produced one.
- **It reports `INCOMPLETE` rather than `PASSED` when fewer tests ran than the sources contain**, counted
  from the `@Test` annotations rather than remembered, and it reports `UNACCOUNTED` when the failures the
  reports counted outnumber the per-test records it read. Both exist because this suite's failure mode is a
  *silently partial* run, and neither can be trusted to a green-looking number.
- **There is no test orchestrator** (§23.32), so the suite is a single instrumentation process. That is
  deliberate and it is a cost decision, not a reliability claim: the `system_server` deaths happen with or
  without it, and it cost one process start per test. `app/build.gradle.kts` records how to restore it.

Read the HTML report as the accurate source: `app/build/reports/androidTests/connected/debug/index.html`.

After touching the wrapper, run all four of its checkers — every defect they cover was invisible while looking
correct, and none was caught by the mechanism it broke:

```powershell
pwsh -File tools\check-wrapper-completeness.ps1
pwsh -File tools\check-harness-signatures.ps1
pwsh -File tools\check-classifier-on-real-output.ps1     # needs a run's XML to exist
pwsh -File tools\check-empty-failure-is-classified.ps1
```

**For anything believed to be flaky, one run is not a verdict**: run it twice and report the ratio (§23.32).
Also read the attempt list the runner prints — an unexpected number of attempts, or a blank one, was how a
`Write-Output` polluting a function's return value was found.

When a test fails, prefer to fix the *test's ability to see the mechanism* only if you can show the
production code is right — and say how you showed it. `docs/decisions.md` §22.9 is the worked example
of a correct fix whose test was not running it, and the node dump that distinguished the two cases.

## 4. Documentation, if the change touches the contract set

```powershell
pwsh -File tools\export-notion-doc.ps1 -All
git diff -- docs/notion
```

`docs/notion/*.md` are **generated mirrors**; regenerate rather than hand-edit, and **always diff the
re-export**. An exporter bug and a spec change look identical otherwise, and both have happened here
(numbered list markers vanishing, a page title duplicated as block 1).

For a spec amendment, `tools/apply-spec-amendments.ps1` is dry-run by default. Run it with `-Inspect`
first: the integration spec keeps its entire reader domain model in a single Notion code block, and
patching part of a block replaces all of it, so a logically additive amendment can silently delete
`ReaderDocument`, `ReaderSection` and `ReaderChunk` from the governing document. Print the full target
text; a character count will not warn you.

## 5. Device probe, for a user-visible flow

Nothing else can check that the session survives an Activity rebuild, that a killed process restores
the right position, or that Back does not touch playback. Pick the probe that matches, and read what
it actually observed rather than what it printed:

| Claim | Probe |
|---|---|
| Player Back leaves playback alone | `tools/probe-player-back.ps1` |
| Section navigation | `tools/probe-player-navigation.ps1` |
| Listening history across a force-stop | `tools/probe-listening-tab.ps1` / `tools/probe-history.ps1 -Action dump\|tap\|tapall\|back` |
| The session survives a configuration change | `tools/probe-config-change.ps1` |

These read tap targets out of the accessibility dump rather than hard-coding coordinates. Keep it that
way — a hard-coded tap that misses reports a wrong answer with full confidence, which has already
happened once.

For a **new** probe, prefer the `android` CLI's JSON dump over `uiautomator dump`:

```powershell
adb shell dumpsys window | Select-String mCurrentFocus   # WHICH app am I looking at?
android layout --flat --diff --pretty                    # JSON; --diff keeps context small
```

The first line is not optional. `android layout` never says which app it dumped, so with the keyguard
up it will hand you the lockscreen at exactly the moment a control appears to be missing. Confirm the
foreground app before interpreting anything.

State honestly what the probe **cannot** distinguish. A chunk-granularity resume sounds the same as a
block-granularity one, so the evidence for an exact resume on device is the absence of a log line plus
a unit test — record that rather than claiming an observation.

## 6. Record the round

- Append a numbered section to [docs/decisions.md](../../../docs/decisions.md): what was found, what
  class of problem each finding was (defect / liveness / a11y / boundary / blocked), what was
  **refused** and why, and the verification results. Record what was wrong as well as what was done.
- Update [README.md](../../../README.md) if user-visible behaviour changed, including its
  "Not yet implemented" list. Note that its test counts drift; correct them if they are visibly stale.
- Update the "Still open" list at the end of `docs/decisions.md`.

## Before you commit

- `git status` — this repository often carries a large uncommitted round. Do not assume a clean tree.
- Re-read the change against the governing documents. If it settles a semantic that no document
  settles, stop and write the specification instead; that refusal is the project's normal answer, not
  an obstacle.
- Commit subjects are prose summaries of the round, with a multi-paragraph body recording the
  reasoning.
