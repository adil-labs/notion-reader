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

.EXAMPLE
    pwsh -File tools/run-accessibility-tests.ps1
    pwsh -File tools/run-accessibility-tests.ps1 -Class com.example.notionreader.ui.PlayerScreenAccessibilityTest
#>
param(
    [string]$Class = ''
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$adb = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_*\platform-tools\adb.exe" |
    Select-Object -First 1).FullName

if (-not $adb) { throw 'adb not found.' }

# 30 minutes: comfortably longer than a run, and short enough that a forgotten restore is not a
# permanent change to how the device behaves.
$TEST_TIMEOUT_MS = 1800000

$originalTimeout = (& $adb shell settings get system screen_off_timeout).Trim()

Write-Output "Device screen_off_timeout was $originalTimeout ms; raising to $TEST_TIMEOUT_MS ms for this run."

try {
    # Keyguard first: a locked device will not display the test activity either.
    & $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    & $adb shell wm dismiss-keyguard | Out-Null
    & $adb shell settings put system screen_off_timeout $TEST_TIMEOUT_MS | Out-Null

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
    foreach ($suite in $xml.testsuites.testsuite) {
        Write-Output ("{0}: tests={1} failures={2} errors={3} skipped={4}" -f `
                $suite.name, $suite.tests, $suite.failures, $suite.errors, $suite.skipped)

        $suite.testcase | Where-Object { $_.failure -or $_.error } | ForEach-Object {
            Write-Output ("  FAILED: {0}" -f $_.name)
            (($_.failure.'#text' + $_.error.'#text') -split "`n") |
                Where-Object { $_ -match 'Assert|Expected|Reason|not displayed|matches' } |
                Select-Object -First 3 |
                ForEach-Object { Write-Output "    $($_.Trim())" }
        }
    }

    if ($exitCode -ne 0) { exit $exitCode }
}
finally {
    Write-Output ''
    & $adb shell settings put system screen_off_timeout $originalTimeout | Out-Null
    $restored = (& $adb shell settings get system screen_off_timeout).Trim()
    Write-Output "Restored screen_off_timeout to $restored ms."
}
