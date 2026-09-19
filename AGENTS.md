# Notion Reader — agent guide

Android POC, one Gradle module (`:app`), Kotlin + Compose, single activity. A TalkBack-first
*listening* reader that turns a Notion page into sequential, hands-free spoken playback — closer to an
audio player than to a Notion editor.

[README.md](README.md) is the product description: behaviour rules, the layer diagram, the
"not yet implemented" list with the reason for each gap, and the build notes. Read it before changing
behaviour, and keep it current. The sections below are what is *not* inferable from the code.

## This repository is specification-driven — do not invent semantics

Work is governed by **seven** documents. **Five are Notion pages, mirrored (generated) into `docs/notion/`;
two are hand-authored in `docs/` and have no Notion counterpart.**

| Rank | Document | Location |
|---|---|---|
| 1 | Product & Architecture Contract | `docs/notion/product-and-architecture-contract.md` |
| 2 | UI Screens & Navigation Contract | `docs/notion/ui-screens-and-navigation-contract.md` |
| 2 | Notion Integration Implementation Spec | `docs/notion/notion-integration-implementation-spec.md` |
| 3 | Active Queue & Reader Session Spec | `docs/notion/active-queue-and-reader-session-spec.md` |
| 3 | Core Player & TTS Playback Spec | `docs/notion/core-player-and-tts-playback-spec.md` |
| 3 | Background Playback, Media Controls & Hands-Free Commands | `docs/background-playback-and-media-controls-spec.md` **(hand-authored, not a mirror)** |
| 3 | Queue Creation & Queue Management | `docs/queue-creation-and-queue-management-spec.md` **(hand-authored, not a mirror)** |
| — | Notion Note Organization (note-authoring skill, not an app spec) | `docs/notion/notion-note-organization.md` |

**The location tells you how to edit it.** Everything under `docs/notion/` is generated: regenerate with
`tools/export-notion-doc.ps1`, never hand-edit, and put a semantic change in Notion instead. Everything in
`docs/` directly (`decisions.md`, `phase0-fixture-contract.md`, and the two hand-authored specs) is
hand-authored and **is authoritative as it stands** — Notion is not the source for those, and the exporter
does not know about them.

The consequence of having both, which is easy to trip over: **a hand-authored document cannot amend a
mirrored one.** An amendment to a mirrored spec still has to be made in Notion and re-exported. One is
outstanding for exactly this reason — the background playback spec depends on reading the session spec's
"live-region announcements" as "also exposed as a live region", and records that alternative as an
amendment candidate in Notion (see [docs/decisions.md §23.9](docs/decisions.md)).

Precedence, and the conflict protocol (amend the contract in Notion → review dependents → update
affected specs → *only then* implement), are recorded in [docs/decisions.md §1](docs/decisions.md).
The Active Queue & Reader Session Spec is a third-rank dependency of the integration spec. The Core
Player & TTS Playback Spec owns the Player's **closed command set**; the Background Playback, Media
Controls & Hands-Free Commands spec *consumes* those commands and may not add, rename or redefine one;
and the Queue Creation & Queue Management spec owns Saved Queue persistence, Queue Destination and both
`Add` actions — **with Smart Queues deliberately out of scope** (`docs/decisions.md` §23.3, D30), because
they need this document *and* the still-unwritten Content Metadata & Workspace Schema Adapters spec.

One writing constraint worth knowing before authoring here: the exporter has **no `table` case**, so a
markdown table in a *Notion* page never reaches its mirror. The five mirrored specs therefore contain no
tables. Hand-authored documents are unaffected and do use them.

Three consequences that are violated more often than anything else:

- **`docs/notion/*.md` are generated mirrors — never hand-edit them.** Regenerate with
  `pwsh -File tools/export-notion-doc.ps1 -All`; Notion remains authoritative. Always diff a
  re-export rather than trusting it, or an exporter bug reads as a spec change.
- **`docs/decisions.md` is the decision record**, one numbered section per round, newest last. Read
  its later sections before starting work; append a new section when you finish, recording what was
  wrong as well as what was done. This file is the durable output of the project.
- **A blocked feature stays unimplemented on purpose.** Absent controls (`Add to queue`, Saved/Smart
  queue tabs, Browse facets) are decisions, not omissions. Before "completing" something that looks
  unfinished, check §20.4's slice plan, §22.5 (what queue management is *allowed* to mean) and
  README's "Not yet implemented". If the semantics are not settled by a governing document, the
  answer is to write the specification first, not to guess.

A near-miss worth repeating: the integration spec stores its whole reader domain model in a single
Notion code block, so patching any part of that block replaces all of it. Print a target's full
current text before an in-place patch; a before/after character count will not reveal a destructive
edit.

## Build and test

```powershell
.\gradlew.bat :app:testDebugUnitTest              # 534 JVM tests, ~3 s, no device, this is the default check
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:compileDebugAndroidTestKotlin  # REQUIRED after touching androidTest
pwsh -File tools\run-accessibility-tests.ps1      # instrumented a11y suite; needs a device or emulator
pwsh -File tools\setup-emulator.ps1              # installs/boots one, so a PIN is not on the path
```

**The cost asymmetry is the single most important operational fact here** (§24): the instrumented suite is
**180 tests in 873 s (4.7 s per test)**, and the JVM suite is **534 tests in 0.8 s (~1.5 ms per test)**. The
instrumented cost is **flat across every class**, which means it is fixed per-test setup — Activity launch,
instrumentation, Compose idle, teardown — and not the assertions. ~99% of a 15-minute run is therefore spent
paying for a device to assert properties of a semantics tree that does not need one.

Three consequences for how you work here:

- **Default to `:app:testDebugUnitTest`.** Reach for the instrumented suite when the change is about
  semantics, focus, Back dispatch, or something a real Android does — not as a general-purpose check.
- **Use `-Class <fqcn>` while iterating.** One class is ~1 minute; the whole suite is ~15, and on this
  emulator it can also die partway (§23.32).
- **The plan is to move the pure-UI classes to Robolectric** (§24), keeping on the device only focus, Back
dispatch, and one smoke test. That is a **spike first**, because Robolectric does not render and this
suite's helper asserts geometry — see §24.3 before starting it, and note that a green run is not the
acceptance criterion.

- `:app:testDebugUnitTest` and `:app:assembleDebug` **do not compile `androidTest`**, so a broken
  instrumented suite can be committed behind a green run. Compile it explicitly.
- Run the instrumented suite through the wrapper, never with raw Gradle. See [Skills](#skills).
- Pins that are deliberate, not incidental: Ktor ≥ 3.6.0 (3.2.0 cannot be dexed for `minSdk 26`),
  `espresso-core` 3.7.0 (compose's `ui-test-junit4` pulls 3.5.0, which fails on API 37).
- **No `ANDROIDX_TEST_ORCHESTRATOR`, and that is a decision rather than an omission.** It was removed in
  §23.32 after being added in §23.11: 180 process starts on an emulator that cannot afford them, for a
  guarantee the completeness check now provides by name. `app/build.gradle.kts` records both halves and how
  to put it back.
- Gradle 9.6.0, AGP 9.4.0, Kotlin 2.3.21, `compileSdk` 37, `minSdk` 26.

Use the **verify-notion-reader** skill when a change is ready to be called done — it encodes the
verification order these tasks actually need.

## Architecture

The layers are the contract's, and they are **enforced by a test**:
[`LayerRulesTest`](app/src/test/java/com/example/notionreader/architecture/LayerRulesTest.kt) reads
the source tree and fails the build if a package reaches into a layer above it, if a file's package
disagrees with its directory, or if a layer named in its rule table does not exist.

```
domain -> nothing      player -> domain            application -> domain, player
data -> domain         platform -> player          ui -> domain, player, application
```

The root package (`MainActivity`, `ReaderViewModel`) is the composition root and the only place
allowed to name `data`, `platform` and `application` together, because that is what wiring is. The
diagram and the three rules behind it are in [README.md](README.md#architecture); read them before
moving a file, and change the rule table deliberately if a dependency is genuinely legitimate.

Two rules that are about lifetime rather than direction, and both are load-bearing:

- **Nothing that must outlive a screen lives in a screen — or a task.** The listening session, its queue and
the loops that feed it are owned by the process-scoped `ReaderHost`, built by `ReaderApplication`, not by
`MainActivity` or `ReaderViewModel`. The system rebuilds the Activity for every configuration change
(rotation, font scale, dark mode at dusk) and destroys the ViewModel when the task ends; a session owned by
either would end with it. `ReaderViewModel.onCleared` deliberately does **not** shut the session down. A
subtlety worth keeping: it is not only the *session* that has to outlive the task but the loop that advances
the queue and the loop that fetches the next Note — move the session alone and playback stops one Note
later, which a single-Note queue never reveals (`docs/decisions.md` §23.16).
- **Notion DTOs never reach the reader domain.** The mapper consumes `NormalizedBlock`, not
  `NotionBlock`. That is why content mapping lives in `data.content` while the types it produces live
  in `domain`.

## Test conventions

- **JUnit 4, `org.junit.Assert`, no mocking framework.** Doubles are hand-written and live beside the
  tests (`FakeSpeechEngine`, `FakeReaderEngine`, `InMemorySessionStore`, `FixtureNotionApi`,
  `ShellHarness`). A double is deliberately faithful where the real thing is destructive —
  `FakeReaderEngine` mirrors `setDocument` stopping playback, because a harmless double could not
  reproduce the regression it guards.
- **Test names are camelCase sentences** that state the property, not the mechanism:
  `loadingANoteWhilePlayingLeavesTheSessionAlone`, `aSupersededNoteLoadCannotOverwriteTheNewerOne`.
  Assert the property ("is a snapshot", "must not blank the screen"); those tests have found more
  real bugs here than mechanism-shaped ones.
- **Regression tests are failing-first.** Remove the guard, confirm *exactly* the named tests fail and
  nothing else, restore it. `docs/decisions.md` §22.8 shows the table shape to record.
- **Unit tests must be able to fail for the right reason.** Reacting to a defect by deleting the
  assertion, or widening a timeout, is treated as a defect here. §22.9 documents the reverse case —
  a correct production fix whose test could not see the mechanism — and why a "wait for it" was the
  honest repair there.
- Fixtures: `app/src/test/resources/fixtures/reader-fixture.json` is a **real API capture** from the
  Phase 0 fixture page. Re-capture it with `tools/capture-reader-fixture.ps1` after editing the page;
  never hand-edit it. The contract it satisfies is
  [docs/phase0-fixture-contract.md](docs/phase0-fixture-contract.md).
- Never hand-roll a Ktor test client: build it from the production `configureForNotion()` extension
  over `MockEngine`, so the real headers, retry and error mapping are what is under test.

## Instrumented accessibility tests

`app/src/androidTest/` asserts on the **semantics tree**, because that is what TalkBack consumes:

```powershell
pwsh -File tools\run-accessibility-tests.ps1                                     # all
pwsh -File tools\run-accessibility-tests.ps1 -Class com.example.notionreader.ui.PlayerScreenAccessibilityTest
```

- Use [`A11y`](app/src/androidTest/java/com/example/notionreader/ui/A11y.kt) helpers, not
  `assertIsDisplayed`. `assertIsDisplayed` is a claim about the device's *window* state and flakes
  here; `assertIsReachable` (placed ∧ area ∧ overlaps surface ∧ `OnClick` ∧ named) is the app-side
  assertion for a **control**, and `assertIsReadable` is the same without the `OnClick` requirement,
  for a node that is deliberately not a control (a history entry is one — do not make it tappable to
  satisfy the other assertion).
- Drive controls with `performSemanticsAction(SemanticsActions.OnClick)`. Synthetic touches are
  silently dropped on a real device, and a semantics action is what an accessibility service
  actually performs.
- Assert reading order through the semantics tree, not by sorting geometry.
- The wrapper's HTML report is the accurate result: `app/build/reports/androidTests/connected/debug/index.html`.

**The focus-restore assertions are green, and the reason they were red was ours** (`docs/decisions.md` §23.29).
They failed for three rounds under a chain of explanations that were each wrong — TalkBack holding
accessibility focus, then touch exploration (§23.17 refuted that), then a Compose-versus-API-37 interaction.
Every one of them blamed the environment.

The measured cause: **`clickable` does not give a `FocusRequester` a target the request can be granted
against.** `Modifier.clickable` alone is refused on every attempt and every frame, while an explicit
`focusable()` in the same chain is granted — and `focusTarget()` is granted but does not *report* being
focused, so nothing can observe where focus went. `restoreFocusOn` now returns
`focusRequester(requester).focusable()`, which its callers place before `clickable`.

The lesson worth keeping, because this is the third time it has cost a round here: a refusal that neither
threw nor was granted was read as a *timing* failure, and the response was to retry across frames rather than
to ask what the request was being granted *against*. `requestFocus()` returning `false` is a definite answer,
not an ambiguous one. When a mechanism fails, and an emulator is available, measure the primitive rather than
reasoning about it — `tools/setup-emulator.ps1` makes that a half-minute experiment instead of a device round.

Do not delete, relax or `@Ignore` these assertions to get a green run; they name a real contract requirement,
and they now pass on their own merits.

## Verification discipline — a check that cannot fail is not evidence

This repository has now been bitten three times by one shape, so it is a rule here rather than a judgement
call. Each time a mechanism that *looked* correct could not see its own subject, and each returned a
reassuring number:

| Round | The mechanism | How it was blind | What it reported |
|---|---|---|---|
| §23.30 | the harness-failure classifier | read `$_.failure.'#text'`, which is empty for an attribute-less element | never matched anything; the retry had never fired once |
| §23.31 | the two-line harness report | the first line used `Write-Output` inside a function, so it joined the *return value* | the failure's name vanished; only its continuation printed |
| §23.32 | the failure filter | `$_.failure -or $_.error` is **falsy** for an empty `<failure></failure>` | the failure vanished entirely — no name, no text, no retry |

Three consequences to internalise before writing any check here:

- **Prove the check can fail for the right reason.** From the outside, a passing check and a broken one look
  identical, and the broken one is the reassuring case. `tools/check-empty-failure-is-classified.ps1` asserts
  that the filter it replaced saw **only 2 of 3** shapes — that is what makes it evidence rather than
  decoration.
- **Prefer an agreement between two independent counters to the absence of a complaint.** "No failures were
  found" passes when the reader is blind; `UNACCOUNTED: the reports counted N failure(s), but only M named
  test(s) could be read out of the XML` does not. That guard generalises: whatever shape a future drop takes,
  the two numbers separate instead of both quietly deflating.
- **Test the shipping code, not a copy of it.** This is why several scripts in `tools/` lift the wrapper's own
  functions and patterns out of the wrapper by regex. A copy can drift, and a check that tests a copy passes
  against something that is no longer deployed.

### One green run is not a cure

The orchestrator experiment (§23.32) is the worked example. Run 1 without it: 180/180, 0 failures, 7.5 min —
recorded as a fix, that would have been wrong. Run 2 without it: dead at 14 of 180. The rate was unchanged; the
first run simply did not reproduce it.

So for anything believed to be flaky or racy, **N = 1 cannot distinguish "fixed" from "did not happen this
time"** — and the confirming run is the one that arrives first. Run it twice and report the ratio honestly;
"two of three passed" is a finding, not an embarrassment to smooth over.

The mirror-image failure is older and cost three rounds: **three rounds blamed the device for focus failures
that were in the app** (§23.29 — TalkBack, then touch exploration, then an API-37 interaction). "It is the
environment" absolves the change under test, which is exactly why it is the hypothesis an agent reaches for
first. When a mechanism fails, measure the primitive rather than reasoning about it: `requestFocus()` returning
`false` is a definite answer, and `tools/setup-emulator.ps1` turns that measurement into a half-minute
experiment rather than a device round.

### Tooling belongs where it survives a clone

Nine verification scripts were written into `app/build/`, which `.gitignore` excludes — so the checks that make
the runner's verdicts trustworthy were the one part of two rounds that would not survive a clone. `.agents/` is
untracked for the same reason. **A gitignored directory is indistinguishable from a tracked one until someone
clones**, so check `git status --short` for `??` on new files instead of assuming they landed.

This repeats a finding already in this file, and the tell is that it is pleasant: the tool works, the run is
green, and nothing anywhere complains.

## Pitfalls

**Device/harness**

- **A locked device fails the whole instrumented suite** with "No compose hierarchies found in the
  app" — 45-odd uniform failures that read as a catastrophic regression. The wrapper wakes, dismisses
  and *verifies* the keyguard and refuses to run otherwise. Do not diagnose this as a broken app.
- **There is no scripted way past a lock that needs a credential**, and the command that looks like one is
  a trap. On this phone (`ro.build.type=user`, `ro.debuggable=0`):

  ```
  adb shell locksettings set-disabled true
  → Lock screen disabled set to true      # reports success
  adb shell settings get secure lockscreen.disabled
  → 0                                     # and writes nothing
  ```

  (`cmd lock_settings --help` misleads the other way, answering "User has a lock credential, but old
  credential was not provided", which reads as though the credential gates everything.) The wrapper now
  separates a **credential** (`inputRestricted=true` — nothing can help) from the **wake/dismiss race**
  (`inputRestricted=false` — re-run), because those have opposite fixes and used to share one message.
- **`tools/setup-emulator.ps1` is the way to avoid the lock entirely** (see `docs/decisions.md` §23.29).
  An emulator has no credential to clear, so instrumented rounds stop depending on the owner's PIN. Read
  its header first: the SDK tools it drives have three traps of their own, including exit codes that
  report failure *after* a successful install.
- **A green run on the emulator is not a green run on the phone.** The same seven focus-restore assertions
  used to fail on both, and they now pass on both for the same reason — the fix is in the app, not in the
  environment (§23.29). What an emulator still cannot tell you: API 37 background-audio hardening does not
  exist on API 36, and TalkBack's *speech* focus following a programmatic request is not observable by a test
  at all. Use `tools/setup-emulator.ps1` so a phone's screen lock is not on the critical path.
- `connectedDebugAndroidTest` may exit non-zero when every test passed. The wrapper derives its own
  exit code from the collected results.
- A retry is only permitted when **every** failure matches a known harness signature; a mixed or
  unclassified result is red immediately, because a retry must never launder a real defect into a pass.
- **A partial run must never be reported as a pass, and once it could be** (`docs/decisions.md` §23.30). The
  pass condition was "no failures" with nothing comparing how many tests ran against how many exist, so an
  emulator that died mid-run left a results file holding only the classes that had finished — all of them
  passing — and the runner would have printed `RESULT: PASSED - 125 tests, 0 failures.` on a run that had
  exercised three quarters of the suite. It is now `INCOMPLETE`, and the expected count is read from the
  `@Test` annotations rather than remembered. `tools/check-wrapper-completeness.ps1` proves a truncated
  run cannot pass. **The emulator is less reliable than the phone was**, so this check is what makes its
  verdicts mean anything.
- **That classifier was dead for as long as it existed, and looked healthy** (`docs/decisions.md` §23.30).
  It read the failure message as `$_.failure.'#text'`, which is **empty for an `<failure>` with no
  attributes** — PowerShell's `[xml]` adapter reduces such an element to a plain `String`, and a String has
  no `'#text'`. Gradle writes the attribute-less form, so no signature could ever match and the retry never
  fired once, while the array, the loop and the branch all looked correct. It is fixed by
  `Get-FailureText`, which handles both shapes. `tools/check-harness-signatures.ps1` asserts that the
  classifier can read and match — run it after touching the signature list, because the failure mode of a
  check like this is indistinguishable from a clean result.

**PowerShell** (this project's tooling is almost all `.ps1`; these traps have each cost a session)

- Quote Gradle `-P` flags (`'-Pandroid.foo=false'`) or the parser turns them into task names.
- **Everything after `adb shell` is argv for the *device's* shell**, which shares no vocabulary with
  PowerShell. `adb shell "dumpsys window | Select-String isKeyguardShowing"` returns
  "`/system/bin/sh: Select-String: inaccessible or not found`" — a message about a missing command rather
  than about the quoting mistake. Capture the output and filter it here.
- **A package name containing `;` is split by the `.bat` wrappers.** `sdkmanager.bat` turned
  `system-images;android-36;google_apis;x86_64` into four arguments and answered "Package android-36 not
  found", which reads as a bad package name rather than as an argument-passing bug. Use the native
  `android` CLI and quote it.
- `@{}`, `-match`, `-replace` and `Sort-Object -Unique` are all **case-insensitive**. Use
  `Dictionary`/`HashSet` with `StringComparer.Ordinal` and `[regex]::IsMatch` for identifier work.
- A function's output *is* its return value — `Write-Output` and a native command's stdout both end up
  in it. Collect report lines and print them at the caller; terminate native invocations with
  `| Out-Host`.
- Returning an empty collection returns `$null` (the pipeline unrolls it) — `return ,$x`. Same reason
  `return ,@($x)` is needed when Notion requires an array.
- `$home` and `$pid` are read-only automatic variables.
- Prefer `pwsh -NoProfile -File tools\script.ps1` over pasting multiple statements into the shared
  terminal, which loses lines and can carry stale variable type constraints. The Notion tooling needs
  **PS7 (`pwsh`)**; 5.1 mangles the rich-text JSON.
- Never edit VS Code's live `state.vscdb`; query a copy.

**Build**

- In `build.gradle.kts`, a top-level `val` read inside `android {}` must be declared **above** it
  (script property initialisers run in declaration order). `java.util.Properties()` needs an
  `import java.util.Properties` — the `java` extension shadows the package.
- Credentials come from `local.properties` then `.env` (which holds a **bare** token with no key), and
  are compiled into `BuildConfig`. `NOTION_PAGE_ID` / `NOTION_PAGE_URL` are resolved and reported by
  the build but **read by nothing at runtime**. A blank reference auto-discovers the most recently
  edited page — correct and tested, but only tests reach it.

## Tools

`tools/` is where this project's verification lives; several claims are only checkable there.

| Script | Purpose |
|---|---|
| `run-accessibility-tests.ps1` | The instrumented suite, with the device prepared. Use this, not Gradle. Derives its own verdict, and reports **INCOMPLETE** if fewer tests ran than the sources contain. |
| `check-wrapper-completeness.ps1`, `check-harness-signatures.ps1`, `check-classifier-on-real-output.ps1`, `check-empty-failure-is-classified.ps1` | Prove the wrapper's four claims: that a truncated run cannot pass and a retry cannot degrade a complete attempt, that the retry classifier can read and match a failure at all, that it matches on the XML Gradle **actually** writes, and that a failure recorded with no message and no trace is seen and classified as the harness. Three of the four lift the wrapper's own code out of the script rather than retyping it, so none can pass against a copy that has drifted. Run them after touching the wrapper — every one of these defects was invisible while looking correct, and none was caught by the mechanism it broke. |
| `proof-*.ps1`, `probe-xml-text.ps1` | Failing-first proofs for a regression guard, and small one-off device probes. A proof edits production code, runs the JVM suite, prints the tests that noticed, and restores — asserting the text actually changed, because a `-replace` that silently does not apply makes an unguarded suite read as "this guard is not needed". |
| `setup-emulator.ps1` | Installs the emulator + an API 36 image and creates/boots an AVD, so the suite can run without a phone and therefore without a PIN. Idempotent; `-Check` reports only, `-Stop` shuts it down and waits for it to go. |
| `probe-player-back.ps1`, `probe-player-navigation.ps1`, `probe-history.ps1`, `probe-listening-tab.ps1`, `probe-config-change.ps1` | Drive the real UI over `adb` and read the accessibility dump back. They read tap targets from the dump rather than hard-coding coordinates — keep it that way, because a probe that misses reports a wrong answer with full confidence. |
| `notion-tool.ps1` | Read-only `-Action list\|page\|children` inspector against the live Notion API. |
| `capture-reader-fixture.ps1`, `seed-reader-fixture.ps1`, `extend-reader-fixture.ps1`, `verify-identity-stability.ps1` | Fixture capture/seeding, and the API-level proof that block ids survive edits (edits, captures, restores and *verifies* the restore). |
| `export-notion-doc.ps1` | Mirrors the governing Notion pages into `docs/notion/`. `-All`, or `-Id <page> -OutFile <path>`. |
| `apply-spec-amendments.ps1` | Applies the amendments listed in `docs/decisions.md` §7. Dry-run by default; `-Inspect` prints a target's full text; `-Apply`, `-Only <id>`. |
| `apply-layer-refactor.ps1`, `fix-unresolved-imports.ps1` | One-off migration tools (dry-run by default). `fix-unresolved-imports.ps1` converges imports against the **compiler**, because "what is needed" is not derivable from a scan. |

Commit subjects are prose summaries of the round ("Persist the listening session, restore it across
process death, and put real history on Home"), often with a multi-paragraph body recording the
reasoning; they are not Conventional Commits.

### The `android` CLI

`android` is on `PATH` (Google's Android CLI, ~1.0). Four of its commands earn their place here:

```powershell
android docs search "Compose semantics mergeDescendants live region"   # returns kb:// article URLs
android docs fetch kb://android/develop/ui/compose/accessibility/merging-clearing
android describe --project_dir .                    # the exact APK path, no guessing
android layout --flat --diff --pretty               # UI as JSON, changed elements only
android run --apks=app\build\outputs\apk\debug\app-debug.apk --activity=.MainActivity
```

- **`docs search` / `docs fetch`** is the main reason to care. It searches the official Android
  knowledge base and returns the article text, so an Android API question is answered from current
  documentation rather than from memory. Prefer it over recollection for Compose semantics, test
  rules and lifecycle behaviour — this project's own live-region defect is explained in
  `kb://android/develop/ui/compose/accessibility/merging-clearing`.
- **`layout --flat --diff`** is a better probe primitive than `uiautomator dump`: JSON rather than XML,
  `center` coordinates ready for `adb shell input tap`, and `--diff` keeps the output small.
- **Not useful headlessly:** every `android studio` subcommand needs a running Studio instance
  (Quail 1+). `version-lookup`, `find-usages` and `render-compose-preview` all fail with "No
  compatible Studio instances found", so dependency upgrades still need the changelog.
- **`android layout` does not say which app it dumped.** With the keyguard up it returns the
  *lockscreen*, in the same output shape as an app, so a missing control looks like a real defect in a
  UI that is not on screen. Check `adb shell dumpsys window | Select-String mCurrentFocus` before
  believing a dump. Same failure class as the hard-coded tap coordinates: a wrong answer reported with
  full confidence.
- It installs a layout instrumentation server on first use, and `android describe` writes
  `.gradle/init.gradle.kts` (git-ignored). Neither is a problem; neither is silent either.
- Do not pipe these into `Select-Object -First N` — the early pipeline close truncates the output and
  returns a misleading non-zero exit code.

## Skills

On-demand instructions live in [`.agents/skills/`](.agents/skills/). Read one when its description
matches the task; they are not loaded otherwise.

- **`verify-notion-reader`** is this project's, and is the one to reach for before calling work done.
- Every other folder there (`adaptive`, `edge-to-edge`, `navigation-3`, `navigation-event`, `styles`,
  `testing-setup`, `android-profiler`) is **vendored from Google**, installed by
  `android skills add <id>` (see `android skills list`). They are generic Android guidance,
  not project decisions, so **`AGENTS.md` and the governing documents win wherever they disagree with
  one**. `testing-setup` is the live example: it assumes a mocking framework and possibly Robolectric,
  and this repository uses neither.
- Nothing in that folder is a substitute for the contract set above. A skill tells you *how* to do a
  thing; it never tells you whether this product does that thing at all.
- **The whole `.agents/` tree is currently untracked in git**, so no skill — including this project's
  own — survives a clone. Commit `verify-notion-reader`; the vendored ones are re-installable and
  arguably should stay out.

## Open work

[docs/decisions.md §20.4](docs/decisions.md) is the slice plan and the ordering rationale; the
"Still open" list at the end of that file is current. The oldest outstanding item is the **TalkBack
listening gate** — a full playback and navigation flow with TalkBack enabled, which needs ears rather
than code and changes the device's touch behaviour globally, so it is the user's call to enable.
