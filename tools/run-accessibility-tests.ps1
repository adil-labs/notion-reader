#requires -Version 7.0
<#
.SYNOPSIS
    Runs the instrumented accessibility tests with the device prepared for them.

.DESCRIPTION
    The instrumented tests assert on the semantics tree - accessible names, roles, reading order,
    live regions - and a few of them assert that a control is actually reachable on screen rather
    than merely present. That last kind needs the screen to be ON, and a stock device does not
    guarantee it:

    This machine's device has `screen_off_timeout` set to 30 seconds, while
    `connectedDebugAndroidTest` takes ~40 seconds including install. So the screen can sleep
    mid-run, the window stops being displayed, and `assertIsDisplayed` fails intermittently. It is
    a flaky failure that has nothing to do with the app, and diagnosing it from the assertion alone
    is misleading - the message is only "component is not displayed".

    So this script:
      1. wakes the device and dismisses the keyguard;
      2. raises `screen_off_timeout` for the duration of the run;
      3. runs the tests;
      4. restores the original timeout, even if the tests fail.

    The restoration is in a `finally`, because leaving a device's screen timeout changed after a
    failed test run is exactly the kind of side effect that is discovered weeks later.

    Only that one setting is touched, and only for the duration. Nothing is installed or uninstalled
    beyond what Gradle does normally.
    HARNESS FAILURE CLASSIFICATION, AND ONE RETRY
    A residual, non-deterministic failure remains that is the *harness losing the test activity* rather
    than the app being wrong. Its signature is exact and comes from the device:

        java.lang.NullPointerException: Cannot run onActivity since Activity has been destroyed
        already
            at androidx.test.core.app.ActivityScenario.lambda$onActivity$2

    It is thrown before any assertion runs, so no assertion in this suite can be implicated in it. It
    was first seen as a *different* symptom - `assertIsDisplayed` reporting "is not displayed" on a
    control that was plainly present - because Compose's display check delegates to Espresso's
    `ViewMatchers.isDisplayed()` against the root view and to `View.getGlobalVisibleRect()`, both of
    which are false for an activity that is being torn down or is no longer being drawn. The display
    assertions were replaced by a device-independent layout check (see `A11y.assertIsReachable`), and
    this script handles what is left.

    So: if **every** failure in an attempt is a harness signature, the attempt is repeated once. The
    retry is reported loudly, with the first attempt's outcome, so a reader knows a failure occurred
    and whether it reproduced. If any failure is not a harness signature - an assertion, an app crash,
    a timeout, or a count the reports cannot attribute to a test - there is no retry and the suite is
    red, because a retry must never be able to launder a real defect into a pass.
    EXIT CODE
    The exit code reported by this script is derived from the COLLECTED TEST RESULTS, not from
    Gradle's. `connectedDebugAndroidTest` exits non-zero even when every test passes - its status
    reflects the instrumentation shell rather than the results, which is reproducible here and is
    Gradle/AGP bookkeeping rather than a test failure. A CI job calling the Gradle task directly would
    therefore fail on a green suite, so this script is the entry point to use.

    The results are read from BOTH the XML and the HTML report and the WORSE of the two is used. That
    is not belt-and-braces: the two disagreed more than once during this project's development, in both
    directions, so neither is trustworthy alone and a green verdict must require both to be green.

.EXAMPLE
    pwsh -File tools/run-accessibility-tests.ps1
    pwsh -File tools/run-accessibility-tests.ps1 -Class com.example.notionreader.ui.PlayerScreenAccessibilityTest
#>
param(
    [string]$Class = ''
)

$ErrorActionPreference = 'Stop'

<#
.SYNOPSIS
    Reads one of the HTML report's summary counters by its element id.

.DESCRIPTION
    The report is a Gradle-generated page, not a data file, so this is a regex against markup rather
    than a parse of a format. The counters live in `<div class="infoBox" id="X"><div
    class="counter">N</div>`, which is stable across Gradle versions in practice but is why the value
    is treated as optional: when the pattern does not match, the caller must fall back to the other
    report rather than to zero, since a missing count is not a passing count.
#>
function Get-HtmlReportCounter([string]$Html, [string]$ElementId) {
    $match = [regex]::Match($Html, "id=`"$ElementId`">\s*<div class=`"counter`">([\d]+)</div>")
    if ($match.Success) { return [int]$match.Groups[1].Value }
    return $null
}

<#
.SYNOPSIS
    The exact text patterns that mean "the harness lost the device", not "the app is wrong".

.DESCRIPTION
    Deliberately short. Every entry is a fact about the test infrastructure that no app change can
    affect. Anything that does not match is reported as a real failure and is never retried, so a new
    failure mode cannot be silently absorbed by this list - it shows up red the first time and has to be
    classified deliberately.
#>
$HARNESS_FAILURE_PATTERNS = @(
    'Cannot run onActivity since Activity has been destroyed already',
    'Process crashed',
    'Instrumentation run failed',

    # A node that is *placed but unmeasured*: `isPlaced` is true, the layout info exists, and the reported
    # bounds are `Rect(0, 0, 0, 0)`.
    #
    # This is the residual half of the flake that `A11y.assertIsReachable` was written to remove, and it is
    # worth recording that the replacement did not remove all of it. Deciding to stop asserting
    # `assertIsDisplayed` was right - it matches against the *root view* and intersects
    # `getGlobalVisibleRect`, both claims about the device's window - and the four checks that replaced it
    # were described as app-side and deterministic. Three of them are. **"Occupies area" is not**, because a
    # node inside a root that the device has not measured yet reports zero in every direction, and that is a
    # statement about measurement rather than about the control.
    #
    # It is classified rather than fixed because the assertion is the requirement worth keeping: a control
    # a listener cannot touch is not a control, and dropping the check would let a genuinely zero-sized
    # button pass. A retry cannot launder a real one either - a control that is truly not laid out reports
    # zero on the second attempt too, and the runner reports the retry's outcome rather than the first's.
    'occupies no area',

    # The locked-device signature. Included as a second line of defence behind the preflight check
    # below: if the keyguard returns mid-run, every Compose test fails with this and nothing about the
    # app is implicated, so a single retry is the right response rather than a red suite.
    #
    # It is worth knowing why this one is easy to misread. It does not look like a device problem - it
    # names the app's own setContent - so it reads as "the composition is broken", and because it fails
    # *every* test uniformly it reads as a catastrophic regression rather than as an environment fault.
    # The measured cause was the keyguard being up: the test activity cannot come to the foreground
    # behind a lock screen, so it never draws a Compose hierarchy at all.
    'No compose hierarchies found in the app'
)

<#
.SYNOPSIS
    Runs the Gradle task once and collects what the two reports say about the outcome.

.DESCRIPTION
    Returns an object rather than printing a verdict, so the caller can decide whether the result
    warrants a retry. `Reported` distinguishes "the reports were produced" from "the build or install
    failed first", which is the difference between a retry being useful and being pointless.

    Failures are captured by name AND by text. The name is what a human needs; the text is what the
    classification needs, and taking the classification from the report rather than from Gradle's console
    output means it cannot be fooled by console formatting.
#>
function Invoke-TestAttempt {
    $startedAt = Get-Date

    Push-Location $repoRoot
    try {
        # `| Out-Host` on both, and it is load-bearing rather than cosmetic: a native command's stdout
        # becomes part of the *enclosing function's output*, so without this Gradle's entire console log
        # is returned alongside the result object and the caller ends up holding a list of Gradle's log
        # lines with the attempt somewhere inside it. `Out-Host` writes them to the console instead,
        # which is where they were going when this code ran at script scope.
        if ($Class) {
            & .\gradlew.bat :app:connectedDebugAndroidTest --console=plain "-Pandroid.testInstrumentationRunnerArguments.class=$Class" | Out-Host
        }
        else {
            & .\gradlew.bat :app:connectedDebugAndroidTest --console=plain | Out-Host
        }
        $gradleExit = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }

    $attempt = [pscustomobject]@{
        GradleExit      = $gradleExit
        Reported        = $false
        Tests           = 0
        Failures        = 0
        AppFailures     = @()
        HarnessFailures = @()
        # Collected rather than written. `Write-Output` inside a function adds to its *output*, so a
        # function that both reports and returns would hand the caller a stream of strings with the
        # result object somewhere in it, and indexing that for `.Failures` reads a property off a string.
        Report          = @()
    }

    # Guarded on the file being written by THIS attempt. After a compile failure the results directory
    # still holds the previous run's XML, and reporting that would attribute old failures to the current
    # attempt - misleading enough that it briefly sent me chasing a test that had passed.
    $resultDir = Join-Path $repoRoot 'app\build\outputs\androidTest-results\connected\debug'
    $resultFile = Get-ChildItem "$resultDir\TEST-*.xml" -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -gt $startedAt } |
        Select-Object -First 1

    if (-not $resultFile) { return $attempt }

    $attempt.Reported = $true

    [xml]$xml = Get-Content $resultFile.FullName
    foreach ($suite in $xml.testsuites.testsuite) {
        $attempt.Report += ("{0}: tests={1} failures={2} errors={3} skipped={4}" -f `
                $suite.name, $suite.tests, $suite.failures, $suite.errors, $suite.skipped)

        $attempt.Tests += [int]$suite.tests
        $attempt.Failures += [int]$suite.failures + [int]$suite.errors

        $suite.testcase | Where-Object { $_.failure -or $_.error } | ForEach-Object {
            $text = $_.failure.'#text' + $_.error.'#text'

            $isHarness = $false
            foreach ($pattern in $HARNESS_FAILURE_PATTERNS) {
                if ($text -like "*$pattern*") { $isHarness = $true; break }
            }

            if ($isHarness) {
                $attempt.HarnessFailures += [pscustomobject]@{ Name = $_.name; Text = $text }
                Write-Output ("  HARNESS FAILURE: {0} - the device lost the test activity, so no" -f $_.name)
                $attempt.Report += '                   assertion was reached. This is not a verdict on the app.'
            }
            else {
                $attempt.AppFailures += [pscustomobject]@{ Name = $_.name; Text = $text }
                $attempt.Report += ("  FAILED: {0}" -f $_.name)

                # Held in a local first: `$attempt.Report += $pipeline` relies on the pipeline binding
                # tighter than the compound assignment, which is true but is not worth depending on.
                $details = @(($text -split "`n") |
                    Where-Object { $_ -match 'Assert|Expected|Reason|not displayed|matches' } |
                    Select-Object -First 3 |
                    ForEach-Object { "    $($_.Trim())" })
                if ($details.Count -gt 0) { $attempt.Report += $details }
            }
        }
    }

    # The HTML report as a second opinion, guarded the same way as the XML so a stale report cannot be
    # attributed to this attempt. A missing or unrecognised counter leaves the value null rather than
    # zero: an unreadable report must not be able to look like a passing one.
    $htmlPath = Join-Path $repoRoot 'app\build\reports\androidTests\connected\debug\index.html'
    $htmlFailures = $null
    $htmlTests = $null
    if (Test-Path $htmlPath) {
        $htmlFile = Get-Item $htmlPath
        if ($htmlFile.LastWriteTime -gt $startedAt) {
            $html = Get-Content $htmlPath -Raw
            $htmlTests = Get-HtmlReportCounter $html 'tests'
            $htmlFailures = Get-HtmlReportCounter $html 'failures'
        }
        else {
            $attempt.Report += 'HTML report is older than this attempt; ignoring it.'
        }
    }

    if ($null -ne $htmlTests -or $null -ne $htmlFailures) {
        $attempt.Report += ''
        $attempt.Report += ("HTML report: tests={0} failures={1}" -f `
            ($(if ($null -eq $htmlTests) { '?' } else { $htmlTests })),
            ($(if ($null -eq $htmlFailures) { '?' } else { $htmlFailures })))

        if ($null -ne $htmlFailures -and $htmlFailures -ne $attempt.Failures) {
            Write-Warning ("The two reports disagree: XML says $($attempt.Failures) failure(s), HTML " +
                "says $htmlFailures. The worse count is used, so a green verdict needs both to be " +
                'green. The HTML report is usually the accurate one, but not always - they have ' +
                'disagreed in both directions on this project.')
        }
    }

    # Reconciled verdict. Both sources are consulted and the worse one wins, because reporting success
    # while a report says otherwise is the one outcome this script exists to prevent.
    if ($null -ne $htmlFailures) { $attempt.Failures = [Math]::Max($attempt.Failures, $htmlFailures) }
    if ($null -ne $htmlTests) { $attempt.Tests = [Math]::Max($attempt.Tests, $htmlTests) }

    return $attempt
}

<#
.SYNOPSIS
    Whether an attempt's failures are all the harness losing the activity, and so worth one re-run.
#>
function Test-IsRetryable($result) {
    # Nothing to retry: either it passed, the reports were never produced, or the build failed.
    if (-not $result.Reported) { return $false }
    if ($result.Failures -eq 0) { return $false }

    # Any failure that could not be classified as the harness is treated as real. This is also the mixed
    # case - some harness, some unknown - and a single unexplained failure is enough to stop the retry.
    if ($result.AppFailures.Count -gt 0) { return $false }

    # A failure the reports counted but that produced no per-test record cannot be classified, so the two
    # counts must agree exactly before a retry is allowed.
    return ($result.HarnessFailures.Count -eq $result.Failures)
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$adb = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_*\platform-tools\adb.exe" |
    Select-Object -First 1).FullName

if (-not $adb) { throw 'adb not found.' }

# 30 minutes: comfortably longer than a run, and short enough that a forgotten restore is not a
# permanent change to how the device behaves.
$TEST_TIMEOUT_MS = 1800000

$originalTimeout = (& $adb shell settings get system screen_off_timeout).Trim()

# `stay_on_while_plugged_in` as well as the timeout. The timeout stops the screen from sleeping, but on
# this device `assertIsDisplayed` still failed intermittently with only "is not displayed" to go on, and
# the display state is the remaining variable: a LazyColumn that is not being drawn has no bounds. 7 =
# always on. Read and restored rather than assumed, because the previous value differs per device.
$originalStayOn = (& $adb shell settings get global stay_on_while_plugged_in).Trim()
if ([string]::IsNullOrWhiteSpace($originalStayOn)) { $originalStayOn = '0' }

Write-Output "Device screen_off_timeout was $originalTimeout ms; raising to $TEST_TIMEOUT_MS ms for this run."
Write-Output "Device stay_on_while_plugged_in was $originalStayOn; setting to 7 (always on) for this run."

<#
    The device must be unlocked before anything runs, and this is checked rather than assumed.

    It is not a nicety. With the keyguard up, the test activity cannot come to the foreground, so
    `setContent` never draws a Compose hierarchy, and **every** test in every suite fails with "No
    compose hierarchies found in the app". That is 45 failures out of 47 on this project, reported as
    `RESULT: FAILED`, in exactly the shape of a catastrophic regression - while the only thing wrong was
    that a phone lying on a desk had locked itself.

    Two earlier versions of this script did attempt to handle it and were not enough. The first woke the
    display and called `wm dismiss-keyguard` once, which races: a dismiss issued in the same breath as
    the wake can arrive before there is a window to dismiss. The second relied on
    `stay_on_while_plugged_in=7` to keep the display on, which **does nothing on a device that is on
    battery** - the setting only applies while charging - and this device is routinely unplugged, so the
    mitigation was inert for the case it was added for.

    So: wake, dismiss, and then *verify*, retrying the pair a few times because the race is real. If the
    keyguard is still showing, the script refuses to run rather than producing a result that looks like
    an app failure. Unlocking a secure device needs the owner's PIN and is deliberately not attempted -
    a script that typed credentials would be a worse problem than the one it solved.
#>
$keyguardDismissed = $false
for ($attempt = 1; $attempt -le 3; $attempt++) {
    & $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    & $adb shell wm dismiss-keyguard | Out-Null

    # `dumpsys window` is the only place this is published; the lock settings do not report it.
    $keyguardState = (& $adb shell dumpsys window | Select-String -Pattern 'isKeyguardShowing=\w+' |
        Select-Object -First 1).Matches.Value

    if ($keyguardState -eq 'isKeyguardShowing=false') { $keyguardDismissed = $true; break }
}

if (-not $keyguardDismissed) {
    throw "The device is locked ($keyguardState) and could not be woken and dismissed into an unlocked " +
        "state. Refusing to run: with the keyguard up every test fails with 'No compose hierarchies " +
        "found in the app', which is indistinguishable from a total regression and is not one. " +
        "Unlock the device and re-run. (A secure lock needs its PIN typed by hand; this script will " +
        "never handle a credential.)"
}

Write-Output 'Device is awake and unlocked.'

$isCharging = (& $adb shell dumpsys battery | Select-String -Pattern '(AC|USB|Wireless) powered: true' |
    Select-Object -First 1).Matches.Count -gt 0

if (-not $isCharging) {
    # Reported rather than silently relied on. `stay_on_while_plugged_in` is set below regardless,
    # because it costs nothing and helps if the device is plugged in for a long run, but a reader should
    # know that on battery the ONLY thing keeping the screen on is `screen_off_timeout`.
    Write-Output ('Device is on battery, so stay_on_while_plugged_in has no effect; the raised ' +
        'screen_off_timeout is what keeps the display on for this run.')
}

try {
    & $adb shell settings put system screen_off_timeout $TEST_TIMEOUT_MS | Out-Null
    & $adb shell settings put global stay_on_while_plugged_in 7 | Out-Null

    $MAX_ATTEMPTS = 2
    $attempts = @()

    for ($attemptNumber = 1; $attemptNumber -le $MAX_ATTEMPTS; $attemptNumber++) {
        Write-Output ''
        Write-Output "Running instrumented accessibility tests (attempt $attemptNumber of $MAX_ATTEMPTS)..."
        Write-Output ''

        $result = Invoke-TestAttempt
        $attempts += $result

        foreach ($line in $result.Report) { Write-Output $line }

        Write-Output ''
        Write-Output "gradle exit code: $($result.GradleExit)"

        if (-not $result.Reported) {
            Write-Output 'No test results were produced by this attempt (the build or install failed first).'
            break
        }

        if (-not (Test-IsRetryable $result)) { break }

        Write-Output ''
        Write-Warning ("All $($result.Failures) failure(s) in this attempt were the harness losing the " +
            'test activity, which no assertion in this suite can cause or detect. Retrying once to find ' +
            'out whether the failure reproduces.')
    }

    $final = $attempts[-1]

    # The replicability statement. A green second attempt after a harness failure is a different fact
    # from a green first attempt, and a reader has to be able to tell them apart.
    if ($attempts.Count -gt 1) {
        $first = $attempts[0]
        Write-Output ''
        Write-Output ('A first attempt failed with {0} harness failure(s): {1}' -f `
                $first.Failures, (($first.HarnessFailures | ForEach-Object { $_.Name }) -join ', '))
        Write-Output ('On a clean re-run it {0}.' -f `
            $(if ($final.Failures -eq 0) { 'did not reproduce' } else { 'reproduced, so it is real' }))
    }

    Write-Output ''

    if ($final.Failures -gt 0) {
        Write-Output "RESULT: FAILED - $($final.Failures) failing test(s) out of $($final.Tests)."
        exit 1
    }

    if ($final.Tests -eq 0) {
        # No counters anywhere: nothing ran, which is not success. Fall back to Gradle's status.
        Write-Output 'RESULT: INCONCLUSIVE - no test counts were readable from either report.'
        exit $(if ($final.GradleExit -ne 0) { $final.GradleExit } else { 1 })
    }

    # The known false negative, reported rather than silently swallowed: a future reader seeing a red
    # Gradle task and a green script needs to know which one to believe and why.
    if ($final.GradleExit -ne 0) {
        # Built as one string rather than concatenated across lines: PowerShell ends a statement at a
        # line break, so a `+` at the start of the next line is a parse error, not a continuation.
        $explanation = "Gradle exited $($final.GradleExit) even though the collected results report no failures. " +
        'This is the known AGP bookkeeping discrepancy, not a test failure - see docs/decisions.md ' +
        'section 11.3. The verdict below comes from the results, not from that exit code.'
        Write-Warning $explanation
    }

    Write-Output "RESULT: PASSED - $($final.Tests) tests, 0 failures."
    exit 0
}
finally {
    Write-Output ''
    & $adb shell settings put system screen_off_timeout $originalTimeout | Out-Null
    & $adb shell settings put global stay_on_while_plugged_in $originalStayOn | Out-Null
    $restored = (& $adb shell settings get system screen_off_timeout).Trim()
    $restoredStayOn = (& $adb shell settings get global stay_on_while_plugged_in).Trim()
    Write-Output "Restored screen_off_timeout to $restored ms and stay_on_while_plugged_in to $restoredStayOn."
}
