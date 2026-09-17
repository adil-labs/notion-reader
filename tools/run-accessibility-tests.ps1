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

try {
    # Keyguard first: a locked device will not display the test activity either.
    & $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    & $adb shell wm dismiss-keyguard | Out-Null
    & $adb shell settings put system screen_off_timeout $TEST_TIMEOUT_MS | Out-Null
    & $adb shell settings put global stay_on_while_plugged_in 7 | Out-Null

    Write-Output 'Running instrumented accessibility tests...'
    Write-Output ''

    $startedAt = Get-Date

    Push-Location $repoRoot
    try {
        if ($Class) {
            & .\gradlew.bat :app:connectedDebugAndroidTest --console=plain "-Pandroid.testInstrumentationRunnerArguments.class=$Class"
        }
        else {
            & .\gradlew.bat :app:connectedDebugAndroidTest --console=plain
        }
        $exitCode = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }

    Write-Output ''
    Write-Output "gradle exit code: $exitCode"

    # Report the outcome from the machine-readable results rather than from the console, because the
    # console only says "there were failing tests" without naming them.
    #
    # Guarded on the file being written by THIS run. After a compile failure the results directory
    # still holds the previous run's XML, and reporting that would attribute old failures to the
    # current attempt - misleading enough that it briefly sent me chasing a test that had passed.
    $resultDir = Join-Path $repoRoot 'app\build\outputs\androidTest-results\connected\debug'
    $resultFile = Get-ChildItem "$resultDir\TEST-*.xml" -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -gt $startedAt } |
        Select-Object -First 1

    if (-not $resultFile) {
        Write-Output 'No test results were produced by this run (the build or install failed first).'
        if ($exitCode -ne 0) { exit $exitCode }
        return
    }

    [xml]$xml = Get-Content $resultFile.FullName
    $xmlTests = 0
    $xmlFailures = 0
    foreach ($suite in $xml.testsuites.testsuite) {
        Write-Output ("{0}: tests={1} failures={2} errors={3} skipped={4}" -f `
                $suite.name, $suite.tests, $suite.failures, $suite.errors, $suite.skipped)

        $xmlTests += [int]$suite.tests
        $xmlFailures += [int]$suite.failures + [int]$suite.errors

        $suite.testcase | Where-Object { $_.failure -or $_.error } | ForEach-Object {
            Write-Output ("  FAILED: {0}" -f $_.name)
            (($_.failure.'#text' + $_.error.'#text') -split "`n") |
                Where-Object { $_ -match 'Assert|Expected|Reason|not displayed|matches' } |
                Select-Object -First 3 |
                ForEach-Object { Write-Output "    $($_.Trim())" }
        }
    }

    # The HTML report as a second opinion, guarded the same way as the XML so a stale report cannot be
    # attributed to this run. A missing or unrecognised counter leaves the value null rather than zero:
    # an unreadable report must not be able to look like a passing one.
    $htmlPath = Join-Path $repoRoot 'app\build\reports\androidTests\connected\debug\index.html'
    $htmlTests = $null
    $htmlFailures = $null
    if (Test-Path $htmlPath) {
        $htmlFile = Get-Item $htmlPath
        if ($htmlFile.LastWriteTime -gt $startedAt) {
            $html = Get-Content $htmlPath -Raw
            $htmlTests = Get-HtmlReportCounter $html 'tests'
            $htmlFailures = Get-HtmlReportCounter $html 'failures'
        }
        else {
            Write-Output 'HTML report is older than this run; ignoring it.'
        }
    }

    if ($null -ne $htmlTests -or $null -ne $htmlFailures) {
        Write-Output ''
        Write-Output ("HTML report: tests={0} failures={1}" -f `
            ($(if ($null -eq $htmlTests) { '?' } else { $htmlTests })),
            ($(if ($null -eq $htmlFailures) { '?' } else { $htmlFailures })))

        if ($null -ne $htmlFailures -and $htmlFailures -ne $xmlFailures) {
            Write-Warning ("The two reports disagree: XML says $xmlFailures failure(s), HTML says " +
                "$htmlFailures. The worse count is used, so a green verdict needs both to be green. " +
                'The HTML report is usually the accurate one, but not always - they have disagreed in ' +
                'both directions on this project.')
        }
    }

    # Reconciled verdict. Both sources are consulted and the worse one wins, because reporting success
    # while a report says otherwise is the one outcome this script exists to prevent.
    $failureCount = $xmlFailures
    if ($null -ne $htmlFailures) { $failureCount = [Math]::Max($failureCount, $htmlFailures) }

    $testCount = $xmlTests
    if ($null -ne $htmlTests) { $testCount = [Math]::Max($testCount, $htmlTests) }

    Write-Output ''

    if ($failureCount -gt 0) {
        Write-Output "RESULT: FAILED - $failureCount failing test(s) out of $testCount."
        exit 1
    }

    if ($testCount -eq 0) {
        # No counters anywhere: nothing ran, which is not success. Fall back to Gradle's status.
        Write-Output 'RESULT: INCONCLUSIVE - no test counts were readable from either report.'
        exit $(if ($exitCode -ne 0) { $exitCode } else { 1 })
    }

    # The known false negative, reported rather than silently swallowed: a future reader seeing a red
    # Gradle task and a green script needs to know which one to believe and why.
    if ($exitCode -ne 0) {
        # Built as one string rather than concatenated across lines: PowerShell ends a statement at a
        # line break, so a `+` at the start of the next line is a parse error, not a continuation.
        $explanation = "Gradle exited $exitCode even though the collected results report no failures. " +
        'This is the known AGP bookkeeping discrepancy, not a test failure - see docs/decisions.md ' +
        'section 11.3. The verdict below comes from the results, not from that exit code.'
        Write-Warning $explanation
    }

    Write-Output "RESULT: PASSED - $testCount tests, 0 failures."
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
