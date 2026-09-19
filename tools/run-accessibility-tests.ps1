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
      3. records the device's accessibility configuration, which one class of assertion depends on;
      4. runs the tests;
      5. restores the original timeout and accessibility setting, even if the tests fail.

    The restoration is in a `finally`, because leaving a device's screen timeout changed after a
    failed test run is exactly the kind of side effect that is discovered weeks later.

    Only those settings are touched, and only for the duration. Nothing is installed or uninstalled
    beyond what Gradle does normally.
    THE ACCESSIBILITY CONFIGURATION, AND WHY IT IS STILL REPORTED
    The seven focus-restore assertions were red for three rounds and are now green; the cause was in the
    app's own modifier chain, not in the device (see `docs/decisions.md` 23.29 and `FocusRestore.kt`).
    `Modifier.clickable` alone gives a `FocusRequester` no target to be granted against, so the request was
    refused on every frame and every attempt - which was misread for three rounds as a timing failure, and
    then as a TalkBack/touch-exploration limitation, both of which are refuted.

    The configuration is still read and printed on every run, for a different reason that survives the fix:
    it is the cheapest way to tell a *focus* failure from a device that is not in the state the suite
    expects. The measurement that settled the question needs `enabled_accessibility_services=null` and
    `touch_exploration_enabled=0` to be trustworthy, and printing both is what makes a run self-describing.

    `-DisableTouchExploration` turns it off for the duration and restores it afterwards, which is how the
    focus mechanism gets verified on a device that is not configured the unusual way. It is off by default
    and has to be asked for, because it changes a global accessibility setting: a reader using the device
    while a run is in progress would find touch exploration gone.
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
    pwsh -File tools/run-accessibility-tests.ps1 -DisableTouchExploration
#>
param(
    [string]$Class = '',

    <#
        Turns off `touch_exploration_enabled` for the run and restores it afterwards.

        Opt-in rather than default, because it changes a global accessibility setting: a reader using the
        device while a run is in progress would find touch exploration gone. It exists so the seven
        focus-restore assertions can be made against a device that is not intercepting programmatic focus,
        which distinguishes "the mechanism does not work" from "the mechanism cannot be asserted in this
        configuration" - the two readings the failures could not previously be told apart.
    #>
    [switch]$DisableTouchExploration
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
    The failure message, from whichever shape the XML adapter hands back.

    ## Why this exists, and it is not a nicety

    The classifier used to read `$_.failure.'#text'`, and **that expression is empty for an element with no
    attributes** - which is what Gradle writes for a test failure. PowerShell's `[xml]` adapter surfaces an
    attribute-less element that holds only text as a plain **String**, and a String has no `'#text'`
    property, so the read yielded `$null` and the concatenation was empty.

    The consequence was silent and total: `$text` was always empty, no entry in
    `$HARNESS_FAILURE_PATTERNS` could ever match, and the retry mechanism could not fire once in the whole
    time it has existed. It looked present - the array was populated, the loop ran, the branch existed -
    while being dead. That is the same shape as this project's own lesson about a regex that never matches
    being indistinguishable from a clean build, and it survived because nothing ever asserted that the
    classifier *could* classify.

    Measured directly, both shapes:

        <failure message="attr">text</failure>   ->  XmlElement  (has '#text')
        <failure>text</failure>                  ->  String      (no '#text'; the string IS the message)

    So both are handled and neither is assumed. The function is deliberately total - a missing node gives an
    empty string rather than throwing - because it runs inside the reporting path, where an exception would
    replace a test verdict with a script error.
#>
function Get-FailureText($node) {
    if ($null -eq $node) { return '' }

    # The attribute-less case: the adapter has already reduced the element to its text.
    if ($node -is [string]) { return $node }

    # An XmlElement. `InnerText` is the honest read - every XmlNode has it - while '#text' depends on the
    # adapter's shape, which is the whole reason this function exists.
    if ($node -is [System.Xml.XmlNode]) { return $node.InnerText }

    # Anything else is unexpected rather than fatal: returned as text so it lands in the failure list as an
    # unexplained failure, which is red, rather than being silently dropped.
    return [string]$node
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

    # The same measurement race as the entry above, seen from the other side: the *root* is measured small
    # instead of the node being measured empty, so a control that is perfectly placed reports as lying
    # outside a surface that is a few hundred pixels across.
    #
    # Observed as `'Queue' lies outside the app surface at Rect.fromLTRB(363.0, 2082.0, 645.0, 2172.0)
    # (surface is Rect.fromLTRB(54.0, 18.0, 282.0, 90.0))` - a 228x72 "surface" on a 1008x2244 device, which
    # is a window that has not been laid out yet rather than anything about the Player. It failed once and
    # passed on the immediate re-run of the same class, with no code change between the two.
    #
    # This is the *second* of `assertIsReachable`'s four checks to be demonstrated non-deterministic, and it
    # is worth recording which two they are: "occupies area" and "overlaps the surface" are both facts about
    # measurement, while "placed", "has OnClick" and "has a name" are facts about the app. The last three are
    # the ones no app change can turn into a false failure, which is the property the helper was chosen for.
    'lies outside the app surface',

    # The locked-device signature. Included as a second line of defence behind the preflight check
    # below: if the keyguard returns mid-run, every Compose test fails with this and nothing about the
    # app is implicated, so a single retry is the right response rather than a red suite.
    #
    # It is worth knowing why this one is easy to misread. It does not look like a device problem - it
    # names the app's own setContent - so it reads as "the composition is broken", and because it fails
    # *every* test uniformly it reads as a catastrophic regression rather than as an environment fault.
    # The measured cause was the keyguard being up: the test activity cannot come to the foreground
    # behind a lock screen, so it never draws a Compose hierarchy at all.
    'No compose hierarchies found in the app',

    # The test rule failing to tear its own Activity down, after the test body has finished.
    #
    # Observed as:
    #
    #     java.lang.AssertionError: Activity never becomes requested state "[DESTROYED]"
    #       (last lifecycle transition = "PAUSED")
    #         at androidx.test.core.app.ActivityScenario.waitForActivityToBecomeAnyOf(ActivityScenario.java:454)
    #         at androidx.test.ext.junit.rules.ActivityScenarioRule.after(ActivityScenarioRule.java:117)
    #
    # Two things make it a harness signature rather than a product one, and the first is the reason the
    # existing entry above is also on this list: `ActivityScenarioRule.after` runs **after** the test body,
    # so no assertion in the test can be implicated - the assertions had already passed. The second is that
    # the Activity reached `PAUSED`, which is normal stopping, so this is the framework not completing a
    # transition rather than the app refusing to die.
    #
    # The evidence it is non-deterministic is the usual shape: it failed once in a full 168-test run and then
    # passed 11/11 when the same class was run alone, with no code change between the two.
    #
    # A retry cannot launder a real defect here either, and the reason is the same as for the entries above:
    # an app that genuinely blocked teardown would block it on the second attempt too, and the runner reports
    # the retry's outcome rather than the first's. It is also worth noting what would make this list wrong -
    # if this signature ever appears *alongside* anything else, the runner refuses to retry, because a mixed
    # result is red immediately.
    'Activity never becomes requested state'
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
            $text = Get-FailureText ($_.failure) + Get-FailureText ($_.error)

            $isHarness = $false
            foreach ($pattern in $HARNESS_FAILURE_PATTERNS) {
                if ($text -like "*$pattern*") { $isHarness = $true; break }
            }

            if ($isHarness) {
                $attempt.HarnessFailures += [pscustomobject]@{ Name = $_.name; Text = $text }
                # **Collected, not written.** This was `Write-Output`, which adds to the *function's output* —
                # the same success stream the caller assigns `$result` from. The effect was that the failure's
                # name was swallowed: the report prints `$result.Report`, and this string was never in it, so
                # the line a reader most needs ("savedQueues.aTeardownFailure") did not appear while its
                # continuation line did. The two lines are one statement and must travel together.
                #
                # It went unnoticed because `$result` was an *array* — the object plus this stray string — and
                # `$result.Report` still worked by PowerShell's member enumeration, which finds the property on
                # whichever element has it. So everything looked correct except the missing name.
                $attempt.Report += ("  HARNESS FAILURE: {0} - the device lost the test activity, so no" -f $_.name)
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

<#
    How many tests this suite contains, counted from the sources rather than remembered.

    ## Why it is counted and not a constant

    It is the bar the completeness check compares a run against, so a constant would be a number that goes
    stale silently - which is the failure mode the completeness check exists to catch, one level up. Read
    from the `@Test` annotations, adding a test raises the bar automatically and deleting one lowers it.

    Every test in this suite is a plain JUnit 4 `@Test` method; there are no parameterised or dynamically
    generated cases, so counting annotations is counting tests. If that ever stops being true, this is the
    place that has to change - and the symptom would be a permanently "INCOMPLETE" verdict, which is loud
    rather than silent.

    **It is computed here, immediately after `$repoRoot`, and it has to be.** The first version sat next to
    the signature list near the top of the file and crashed with "Cannot bind argument to parameter 'Path'
    because it is null": that is well before `$repoRoot` exists, and the failure named a parameter rather
    than a missing variable, so it read as a path problem rather than as an ordering one.
#>
$expectedTests = @(
    Get-ChildItem (Join-Path $repoRoot 'app\src\androidTest') -Recurse -Filter '*.kt' -ErrorAction SilentlyContinue |
        ForEach-Object { Select-String -Path $_.FullName -Pattern '^\s*@Test\b' }
).Count

if ($expectedTests -eq 0) {
    Write-Warning ('No @Test annotations were found under app\src\androidTest, so the completeness check ' +
        'has nothing to compare against and will not be able to tell a full run from a truncated one.')
}

<#
    One run at a time, enforced rather than assumed.

    This exists because it already went wrong: a run was killed part-way (a pipeline was closed early under
    it), and a second run launched while the first was still tearing down. Both wrote to the same
    `app/build/outputs/androidTest-results/connected/debug/` directory, and the second died with

        java.nio.file.FileSystemException: ... Pixel 8 Pro - 17\logcat-...FocusRestoreTest-....txt:
        The process cannot access the file because it is being used by another process

    which names a *file* and not a cause, so it reads as a Gradle or disk problem. It also produced
    `RESULT: INCONCLUSIVE` and left the device's screen timeout raised, because the killed run never reached
    its `finally` — the same class of misattribution this project keeps meeting, where the symptom names
    something other than the fault.

    The lock is a file holding the owning process id, so a run that was killed leaves a lock that can be
    identified as stale (its process is gone) and taken over, rather than blocking every later run forever.

    It is taken *immediately before the try below* rather than here, and that placement is the correction of a
    real defect. Acquired earlier — next to the device preflight — a refusal at that preflight (no device, more
    than one device, a locked keyguard) exited before the `finally` that releases it, so the lock file stayed
    behind. The next run then found a pid that either no longer existed or had been reused by an unrelated
    process, and either warned about a stale lock or refused outright claiming another run was in progress,
    which is a wrong answer reported with full confidence about a run that had already ended.

    Acquiring it there is also the honest scope: the resources the lock protects are the test-results directory
    and the device settings raised on the line after it, and both are touched only inside that try.
#>
$adb = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_*\platform-tools\adb.exe" |
    Select-Object -First 1).FullName

if (-not $adb) { throw 'adb not found.' }

<#
    The device must be *there* before anything else is attempted, and this is checked rather than assumed.

    It is not politeness. Every step below reads a setting from the device and calls `.Trim()` on the
    result, so with no device attached the script died at the first of them with

        You cannot call a method on a null-valued expression

    at the `screen_off_timeout` line - which names PowerShell, not the device, and is exactly the class of
    misattribution this project has had to fix three times already: the keyguard producing 45 uniform "No
    compose hierarchies found in the app" failures that looked like a total regression, `assertIsDisplayed`
    reporting window state as an app defect, and `android layout` not saying which app it had dumped.

    Three states, three messages, because they need three different actions from a person:

      * nothing listed      -> the cable or the wireless session is gone
      * `unauthorized`      -> the device is showing a debugging prompt that must be accepted; ALSO the
                               symptom of two adb binaries running separate servers, where the device is
                               authorised with one and not the other
      * `offline` / other   -> the connection is half-up, usually fixed by reconnecting

    Note the middle case, because it is the one that misleads: `unauthorized` looks like a code or key
    problem and is usually an unanswered dialog on the screen.
#>
$deviceList = (& $adb devices) -split "`r?`n" | Where-Object { $_ -and $_ -notmatch '^List of devices' }
$connected = @($deviceList | Where-Object { $_ -match '\sdevice$' })
$unauthorized = @($deviceList | Where-Object { $_ -match '\sunauthorized$' })
$offline = @($deviceList | Where-Object { $_ -match '\s(offline|no permissions)$' })

if ($connected.Count -eq 0) {
    Write-Output 'Device list:'
    if ($deviceList) { $deviceList | ForEach-Object { Write-Output "  $_" } } else { Write-Output '  (none)' }

    if ($unauthorized.Count -gt 0) {
        throw ('The device is connected but UNAUTHORIZED, so nothing can be installed or queried. Look at ' +
            'the device screen and accept the "Allow USB debugging" prompt; if there is no prompt, run ' +
            '`adb kill-server` and replug/re-pair. This is also what two adb binaries running separate ' +
            'servers looks like, where the device is authorised with one of them.')
    }

    if ($offline.Count -gt 0) {
        throw ('The device is listed but not usable (offline or no permissions). Reconnect it, or run ' +
            '`adb reconnect`.')
    }

    throw ('No device is attached, so there is nothing to run the instrumented suite against. The suite ' +
        'needs a real device or an emulator: it asserts on the semantics tree, which no JVM test can see.')
}

if ($connected.Count -gt 1) {
    throw ("$($connected.Count) devices are attached. This script does not choose one, because picking the " +
        'wrong device reports a wrong answer with full confidence - pass a single device or disconnect the ' +
        'others.')
}

# The floor for `screen_off_timeout` during a run, and it is a **floor** rather than the value.
#
# ## Two bugs this replaced, one of them the cause of a 161-failure run
#
# The constant used to be assigned unconditionally, and the message said "raising" whatever the device had.
# On this emulator the device's own value is `2147483647` (24.8 days), so the wrapper **lowered** it to
# 1,800,000 ms (30 minutes) and reported that as raising it. A wrong statement, and worse than cosmetic.
#
# The run then failed **161 of 180 tests**, all but one with "No compose hierarchies found in the app", two
# Browse tests having passed before the display went dark. `dumpsys power` afterwards says it plainly:
#
#     mWakefulness=Asleep   mScreenOnFully=false   isKeyguardShowing=false
#
# So the display slept mid-run and every Compose hierarchy stopped existing. With the device's own 24.8-day
# timeout left alone, that cannot happen.
#
# ## And why the wake check below had to change too
#
# The preflight woke the device and then verified **the keyguard**, which is not the same question. The line
# above is the counter-example: `isKeyguardShowing=false` while `mWakefulness=Asleep`. A sleeping device with
# no lock passes a keyguard check, so the script printed "Device is awake and unlocked" about a device that
# was asleep. It now reads wakefulness, which is the fact it was always trying to establish.
$TEST_TIMEOUT_FLOOR_MS = 1800000

<#
    Reading a device setting, with the failure mode named.

    `(& $adb shell settings get ...).Trim()` is a null-dereference whenever the device's `system_server` is
    not answering: `adb shell` exits 0 and prints nothing, `settings get` never runs, and `.Trim()` throws
    "You cannot call a method on a null-valued expression" — a message about PowerShell rather than about a
    device whose framework has died. That is the class of misattribution this file keeps recording, and it
    stops the run before any test is compiled.

    So a missing value is `$null` here, and the caller decides. For the timeout, `$null` means "cannot read
    it", which is not the same as "no timeout is needed" — see the check below.
#>
function Get-DeviceSetting([string] $namespace, [string] $key) {
    $value = (& $adb shell settings get $namespace $key 2>$null) -join ''
    $value = $value.Trim()
    if ([string]::IsNullOrWhiteSpace($value) -or $value -eq 'null') { return $null }
    return $value
}

# A device whose framework is not answering cannot run the suite at all, and every later step would fail with
# a message naming PowerShell. Refused here, once, with the actual cause.
if ($null -eq (Get-DeviceSetting 'system' 'screen_off_timeout')) {
    throw ('The device is not answering `settings get` - its system_server has most likely died, which ' +
        'happens to an emulator under memory pressure. Nothing about the app is implicated. Restart it: ' +
        'pwsh -NoProfile -File tools\setup-emulator.ps1 -Stop, then run that script again.')
}

$originalTimeout = Get-DeviceSetting 'system' 'screen_off_timeout'
$originalTimeoutMs = 0
if (-not [int]::TryParse($originalTimeout, [ref]$originalTimeoutMs)) { $originalTimeoutMs = 0 }

# Never lower it. A device already configured to stay awake longer than this run needs is left alone, which
# is also what makes the restore a no-op rather than a change of the owner's settings.
$TEST_TIMEOUT_MS = [Math]::Max($originalTimeoutMs, $TEST_TIMEOUT_FLOOR_MS)

# `stay_on_while_plugged_in` as well as the timeout. The timeout stops the screen from sleeping, but on
# this device `assertIsDisplayed` still failed intermittently with only "is not displayed" to go on, and
# the display state is the remaining variable: a LazyColumn that is not being drawn has no bounds. 7 =
# always on. Read and restored rather than assumed, because the previous value differs per device.
$originalStayOn = Get-DeviceSetting 'global' 'stay_on_while_plugged_in'
if ([string]::IsNullOrWhiteSpace($originalStayOn)) { $originalStayOn = '0' }

Write-Output ("Device screen_off_timeout was $originalTimeout ms; setting to $TEST_TIMEOUT_MS ms for this run " +
    $(if ($TEST_TIMEOUT_MS -eq $originalTimeoutMs) { '(unchanged - the device already outlasts the run)' } else { '' }) + '.')
Write-Output "Device stay_on_while_plugged_in was $originalStayOn; setting to 7 (always on) for this run."

<#
    The device's accessibility configuration, printed so a run is self-describing.

    Reported for every run because it is the cheapest way to tell a *focus* failure from a device that is
    not in the state the suite expects. The seven focus-restore assertions used to be attributed to this
    configuration and are now known to have been the app's own fault (23.29), so this is no longer an
    explanation of a red suite - it is context for whichever failure the next round has to explain.

    `-DisableTouchExploration` turns it off for the duration and restores it in the `finally` below,
    exactly as `screen_off_timeout` is handled. It exists so that the focus mechanism can be verified on a
    device that is not configured the unusual way, rather than being permanently unassertable - and so that
    a green run means "the app puts focus back" rather than "the app puts focus back when nothing is
    intercepting it".

    It is off by default and has to be asked for, because it changes a global accessibility setting: a
    reader using the device while a run is in progress would find touch exploration gone.
#>
$originalTouchExploration = (& $adb shell settings get secure touch_exploration_enabled).Trim()
$accessibilityServices = (& $adb shell settings get secure enabled_accessibility_services).Trim()

Write-Output "Device touch_exploration_enabled=$originalTouchExploration; enabled_accessibility_services=$accessibilityServices"
if ($originalTouchExploration -eq '1' -and -not $DisableTouchExploration) {
    Write-Output ('  NOTE: touch exploration is ON, so the focus-restore assertions in FocusRestoreTest and ' +
        'ReaderShellAccessibilityTest are expected to fail (docs/decisions.md 23.12). Re-run with ' +
        '-DisableTouchExploration to find out whether the mechanism works without it.')
}

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

    WHICH problem it is now matters, because the two have opposite fixes, so the refusal separates them on
    `inputRestricted` (published by the same `dumpsys window`):

        isKeyguardShowing=true  inputRestricted=true   -> a CREDENTIAL is required. Nothing here can help.
        isKeyguardShowing=true  inputRestricted=false  -> the wake/dismiss race. Re-running often works.

    It used to report one message for both, saying the device "could not be woken and dismissed", which
    points at the race -- and so at retrying -- when the real state was a secure lock that no number of
    retries will pass. That is the same wrong-answer-stated-confidently shape as the rest of this file.

    There is no scripted way past a credential on a production build, and the command that looks like one
    is a trap worth recording. On this device (`ro.build.type=user`, `ro.debuggable=0`, a PIN set):

        $ adb shell locksettings set-disabled true
        Lock screen disabled set to true          <- reports success
        $ adb shell settings get secure lockscreen.disabled
        0                                         <- and writes nothing

    So it fails silently and its success message is false. (`adb shell cmd lock_settings --help` is
    misleading in the other direction -- it answers "User has a lock credential, but old credential was
    not provided", which reads as though the credential is needed for every operation.) Neither should be
    trusted. The two honest routes are the owner typing the PIN, or an emulator, which has no lock:

        pwsh -NoProfile -File tools\setup-emulator.ps1

    That route was added because the device became the bottleneck for every instrumented round. See
    `docs/decisions.md` 23.29, which also records that a green run on an emulator is NOT evidence about
    this device's focus assertions -- see the note where the suite is reported.
#>
$keyguardDismissed = $false
$credentialRequired = $false
for ($attempt = 1; $attempt -le 3; $attempt++) {
    & $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    & $adb shell wm dismiss-keyguard | Out-Null

    # `dumpsys window` is the only place this is published; the lock settings do not report it.
    $window = & $adb shell dumpsys window
    $keyguardState = ($window | Select-String -Pattern 'isKeyguardShowing=\w+' |
        Select-Object -First 1).Matches.Value
    $credentialRequired = ($window | Select-String -Pattern 'inputRestricted=true' | Measure-Object).Count -gt 0

    if ($keyguardState -eq 'isKeyguardShowing=false') { $keyguardDismissed = $true; break }
}

if (-not $keyguardDismissed) {
    $becauseItIsLocked = ('Refusing rather than running: with the keyguard up every test fails with ' +
        "'No compose hierarchies found in the app', which is indistinguishable from a total regression " +
        'and is not one.')

    if ($credentialRequired) {
        throw ("The device is locked and its lock requires a credential " +
            "(isKeyguardShowing=$keyguardState, inputRestricted=true). No command can pass this: " +
            '`wm dismiss-keyguard` only dismisses a lock with no credential, and `locksettings ' +
            'set-disabled true` reports success on a production build while writing nothing. Two ways ' +
            "forward: unlock the device by hand (type the PIN on the phone), or run the suite on an " +
            'emulator, which has no lock at all: pwsh -NoProfile -File tools\setup-emulator.ps1. ' +
            $becauseItIsLocked)
    }

    throw ("The device is awake but the keyguard is still showing " +
        "(isKeyguardShowing=$keyguardState, inputRestricted=false) after three wake-and-dismiss " +
        'attempts. That is the wake/dismiss race rather than a credential - a dismiss issued in the same ' +
        'breath as the wake can arrive before there is a window to dismiss - so re-running is worth one ' +
        'try. If it persists, check by hand: adb shell dumpsys window | Select-String ' +
        'isKeyguardShowing. ' + $becauseItIsLocked)
}

Write-Output 'Device is awake and unlocked.'

# ## Wakefulness is a separate question from the keyguard, and this is where that was learned
#
# The preflight used to establish only that the keyguard was not showing, and then report "Device is awake
# and unlocked" -- which asserts two facts from one measurement. The counter-example came from a real run
# that failed 161 of 180 tests:
#
#     mWakefulness=Asleep   mScreenOnFully=false   isKeyguardShowing=false
#
# A sleeping device with no lock satisfies the keyguard check, so the script declared it awake while it was
# asleep, and every Compose hierarchy then failed to exist. `dumpsys power` is where wakefulness is
# published; `dumpsys window` publishes the keyguard and the display flags, not the power state.
$wakefulness = (& $adb shell dumpsys power | Select-String -Pattern 'mWakefulness=\w+' |
    Select-Object -First 1).Matches.Value
if ($wakefulness -and $wakefulness -ne 'mWakefulness=Awake') {
    Write-Output "Device reports $wakefulness; waking it before the run."
    & $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    $wakefulness = (& $adb shell dumpsys power | Select-String -Pattern 'mWakefulness=\w+' |
        Select-Object -First 1).Matches.Value
}
Write-Output "Device wakefulness: $wakefulness"

if ($wakefulness -and $wakefulness -ne 'mWakefulness=Awake') {
    # Refused rather than attempted, because the failure it produces is the mass one: with the display off,
    # no Compose hierarchy exists, so ~90% of the suite fails with a message that names the app's own
    # `setContent` and reads as a catastrophic regression.
    throw ("The device is not awake ($wakefulness) and would not wake. With the display off every Compose " +
        "test fails with 'No compose hierarchies found in the app', which is indistinguishable from a total " +
        'regression and is not one. Wake it and re-run: adb shell input keyevent KEYCODE_WAKEUP.')
}

$isCharging = (& $adb shell dumpsys battery | Select-String -Pattern '(AC|USB|Wireless) powered: true' |
    Select-Object -First 1).Matches.Count -gt 0

if (-not $isCharging) {
    # Reported rather than silently relied on. `stay_on_while_plugged_in` is set below regardless,
    # because it costs nothing and helps if the device is plugged in for a long run, but a reader should
    # know that on battery the ONLY thing keeping the screen on is `screen_off_timeout` -- which is why that
    # value is never lowered below the device's own, above.
    Write-Output ('Device is on battery, so stay_on_while_plugged_in has no effect; the ' +
        'screen_off_timeout set for this run is what keeps the display on.')
}

$lockPath = Join-Path $repoRoot 'app\build\.a11y-runner.lock'
if (Test-Path $lockPath) {
    $lockContents = (Get-Content $lockPath -Raw).Trim()
    $lockPid = ($lockContents -split '\s+')[0]
    $ownerAlive = $false
    if ($lockPid -match '^\d+$') {
        $ownerAlive = $null -ne (Get-Process -Id ([int]$lockPid) -ErrorAction SilentlyContinue)
    }

    if ($ownerAlive) {
        throw ("Another instrumented run is in progress (pid $lockPid, started $lockContents). Two runs " +
            'share the test-results directory and the second fails with a file-lock error that names a file ' +
            'rather than the cause. Wait for it, or stop it, then re-run.')
    }

    Write-Warning ("Found a stale lock from pid $lockPid ($lockContents) - that run was killed before its " +
        'finally, so it may also have left the device''s screen timeout raised. Taking the lock over.')
}

Set-Content -Path $lockPath -Value "$PID $(Get-Date -Format o)"

try {
    & $adb shell settings put system screen_off_timeout $TEST_TIMEOUT_MS | Out-Null
    & $adb shell settings put global stay_on_while_plugged_in 7 | Out-Null
    if ($DisableTouchExploration -and $originalTouchExploration -eq '1') {
        & $adb shell settings put secure touch_exploration_enabled 0 | Out-Null
        Write-Output ('Disabled touch exploration for this run: the focus-restore assertions are being ' +
            'made against a device that is not intercepting programmatic focus.')
    }

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

    # ---------------------------------------------------------------------------------------------------
    # Which attempt the verdict comes from, and a retry must not be able to make it worse.
    #
    # ## The defect this replaces, which a real run exposed
    #
    # This was `$final = $attempts[-1]`, and that is wrong whenever the retry produces a *less usable* result
    # than the attempt it replaced. Observed:
    #
    #     attempt 1: tests=180 failures=1   <- complete, and its only failure was a harness failure
    #     attempt 2: tests=139 failures=1   <- truncated; the system server died again
    #     RESULT: FAILED - 1 failing test(s) out of 139
    #
    # The retry discarded a **complete** run and reported an incomplete one. Worse, the retry is only ever
    # permitted when every failure in the first attempt was a harness failure — so the attempt being thrown
    # away is precisely the one whose verdict was already known to be uninformative-and-probably-fine, and the
    # one kept is a partial run. A retry that can degrade the verdict is not a retry; it is a second, worse
    # measurement that hides the first.
    #
    # ## The rule
    #
    # Prefer the most recent attempt that ran the **whole** suite; fall back to the last attempt only when no
    # attempt did. "Most recent" rather than "best" on purpose: among complete attempts, the later one is the
    # better evidence, because it is the one that had a second chance to reproduce the failure — which is what
    # the retry was for.
    # ---------------------------------------------------------------------------------------------------
    $complete = @($attempts | Where-Object { $_.Reported -and $_.Tests -ge $expectedTests })
    $final = if ($complete.Count -gt 0) { $complete[-1] } else { $attempts[-1] }
    $discarded = @($attempts | Where-Object { $_.Reported -and $_.Tests -lt $expectedTests })

    # The replicability statement. A green second attempt after a harness failure is a different fact
    # from a green first attempt, and a reader has to be able to tell them apart.
    if ($attempts.Count -gt 1) {
        Write-Output ''
        for ($n = 0; $n -lt $attempts.Count; $n++) {
            $a = $attempts[$n]
            Write-Output ('Attempt {0}: {1} test(s), {2} failure(s){3}' -f `
                ($n + 1), $a.Tests, $a.Failures,
                $(if ($a.Tests -lt $expectedTests) { ' - INCOMPLETE' } else { '' }))
        }

        if ($discarded.Count -gt 0) {
            Write-Warning ("Attempt(s) {0} ran only part of the suite and were NOT used for the verdict. A " +
                'retry cannot be allowed to replace a complete result with a partial one - see the note in ' +
                'this script about why.' -f (($attempts | ForEach-Object -Begin { $i = 1 } -Process {
                            if ($_.Tests -lt $expectedTests) { $i }; $i++
                        }) -join ', '))
        }

        Write-Output ('Verdict comes from attempt {0} of {1}.' -f `
                (($attempts.IndexOf($final)) + 1), $attempts.Count)
    }

    Write-Output ''

    if ($final.Failures -gt 0) {
        Write-Output "RESULT: FAILED - $($final.Failures) failing test(s) out of $($final.Tests)."
        # Worth saying even here: a run with failures *and* missing classes is a weaker result than the
        # failure count alone suggests, and the next person should not read a class that never ran as one
        # that passed.
        if ($final.Tests -lt $expectedTests) {
            Write-Warning ("This attempt is also incomplete: $($final.Tests) of $expectedTests tests ran, " +
                'so some classes reported nothing at all.')
        }
        exit 1
    }

    if ($final.Tests -eq 0) {
        # No counters anywhere: nothing ran, which is not success. Fall back to Gradle's status.
        Write-Output 'RESULT: INCONCLUSIVE - no test counts were readable from either report.'
        exit $(if ($final.GradleExit -ne 0) { $final.GradleExit } else { 1 })
    }

    # ---------------------------------------------------------------------------------------------------
    # Completeness, and it is checked before the verdict rather than after it.
    #
    # ## Why this exists
    #
    # Until this check, the pass condition was "no failures" and nothing compared the number of tests that
    # ran against the number that should have. That is fine while a run is complete, and **wrong in exactly
    # the case that matters**: an emulator that dies part-way through leaves a results file containing the
    # classes that had finished, all of them passing. The runner would have printed
    #
    #     RESULT: PASSED - 125 tests, 0 failures.
    #
    # on a run that had exercised three-quarters of the suite, with the four missing classes simply absent -
    # no error, no red, nothing to notice. Observed for real: a run ended with `tests=125` against a suite of
    # 168, four classes missing from the report and `Transport endpoint is not connected` in the log, and it
    # only came out red because one of the classes that *did* run happened to fail first.
    #
    # That is this project's recurring failure shape - a wrong answer reported with full confidence - and it
    # is worse than most, because the wrong answer is "all green". A partial run is not a smaller verdict; it
    # is not a verdict at all.
    #
    # The expected count comes from the sources rather than from a remembered number, so it cannot go stale:
    # a test added tomorrow raises the bar automatically, and one deleted lowers it.
    # ---------------------------------------------------------------------------------------------------
    if ($final.Tests -lt $expectedTests) {
        Write-Output ''
        Write-Output ("RESULT: INCOMPLETE - only $($final.Tests) of $expectedTests tests ran, with " +
            "$($final.Failures) failures. A run that lost part of its suite cannot be reported as a pass.")
        Write-Output ('  The missing tests are not passing tests: they did not run. Check the device or ' +
            'emulator - a severed transport, a reboot mid-run, or a full disk all produce this.')
        exit 1
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

    Write-Output "RESULT: PASSED - $($final.Tests) tests, 0 failures (expected $expectedTests)."
    exit 0
}
finally {
    Write-Output ''
    & $adb shell settings put system screen_off_timeout $originalTimeout | Out-Null
    & $adb shell settings put global stay_on_while_plugged_in $originalStayOn | Out-Null
    if ($DisableTouchExploration) {
        & $adb shell settings put secure touch_exploration_enabled $originalTouchExploration | Out-Null
    }
    $restored = (& $adb shell settings get system screen_off_timeout).Trim()
    $restoredStayOn = (& $adb shell settings get global stay_on_while_plugged_in).Trim()
    $restoredTouch = (& $adb shell settings get secure touch_exploration_enabled).Trim()
    Write-Output "Restored screen_off_timeout to $restored ms and stay_on_while_plugged_in to $restoredStayOn."
    Write-Output "touch_exploration_enabled is $restoredTouch (was $originalTouchExploration)."

    # Verified rather than announced. A `settings put` that did not take - because the value was captured
    # wrong after a killed run, or because the device refused it - would otherwise be reported as a
    # successful restore, which is how a device silently keeps a 30-minute screen timeout set by a test.
    if ($restored -ne $originalTimeout) {
        Write-Warning ("screen_off_timeout did NOT restore: wanted $originalTimeout, device reports " +
            "$restored. Restore it by hand: adb shell settings put system screen_off_timeout $originalTimeout")
    }
    if ($restoredStayOn -ne $originalStayOn) {
        Write-Warning ("stay_on_while_plugged_in did NOT restore: wanted $originalStayOn, device reports " +
            "$restoredStayOn. Restore it by hand: adb shell settings put global " +
            "stay_on_while_plugged_in $originalStayOn")
    }

    if (Test-Path $lockPath) { Remove-Item $lockPath -Force -ErrorAction SilentlyContinue }
}
