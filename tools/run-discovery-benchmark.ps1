#requires -Version 7.0
<#
Runs the frozen 100-Note Discovery baseline on a real device and saves a numeric report.

The benchmark test seeds a purpose-built corpus in an isolated app-file root and records only
counts and timings. This wrapper owns build, install, condition capture, the instrumentation
verdict, report completeness, and the run manifest. It fails on a partial or empty report:
a green instrumentation exit is not evidence that the benchmark observed its subject.
#>
param(
    [string]$Device = '',
    [ValidateSet('paired', 'cold', 'repeat')][string]$Mode = 'paired',
    [int]$Runs = 1,
    [int]$WindowSeconds = 150,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$package = 'com.example.notionreader'
$testPackage = 'com.example.notionreader.test'
$runner = 'androidx.test.runner.AndroidJUnitRunner'
$testClass = 'com.example.notionreader.ui.DiscoveryBaselineBenchmarkDeviceTest'
$reportDevicePath = 'files/discovery-benchmark.jsonl'
$reportExternalPath = "/storage/emulated/0/Android/data/$package/files/discovery-benchmark.jsonl"
$adbExecutable = (Get-Command adb -CommandType Application).Source
$devices = @(& $adbExecutable devices | Where-Object { $_ -match '\sdevice$' })
if ($Device) { $devices = @($devices | Where-Object { ($_ -split '\s+')[0] -ceq $Device }) }
if ($devices.Count -ne 1) { throw 'Select exactly one usable Android device with -Device when several are attached' }
$benchmarkDevice = ($devices[0] -split '\s+')[0]
function adb { & $script:adbExecutable -s $script:benchmarkDevice @args }
function Shell([string]$command) { return ((adb shell $command) -join "`n").Trim() }

$artifacts = Join-Path $repo 'build/discovery-benchmark'
$null = New-Item -ItemType Directory -Force $artifacts
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$reportLocal = Join-Path $artifacts "discovery-benchmark-$stamp.jsonl"
$manifestLocal = Join-Path $artifacts "discovery-benchmark-$stamp.manifest.json"

if (-not $SkipBuild) {
    & (Join-Path $repo 'gradlew.bat') ':app:assembleDebug' ':app:assembleDebugAndroidTest' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark APK build failed' }
}
$appApk = Join-Path $repo 'app/build/outputs/apk/debug/app-debug.apk'
$testApk = Join-Path $repo 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
foreach ($apk in @($appApk, $testApk)) { if (-not (Test-Path $apk)) { throw "Missing APK: $apk" } }

$modelRoot = Shell "run-as $package ls no_backup/discovery-model 2>/dev/null"
if ($LASTEXITCODE -ne 0 -or $modelRoot -notmatch 'generative') { throw 'The installed Gemma topic model is not present; the benchmark would skip silently' }

$battery = Shell 'dumpsys battery'
$thermal = Shell 'dumpsys thermalservice'
$power = Shell 'dumpsys power'
$screenState = (($power -split "`n") | Select-String -Pattern 'Display Power: state=' | Select-Object -First 1)
$conditions = [ordered]@{
    device = Shell 'getprop ro.product.model'
    manufacturer = Shell 'getprop ro.product.manufacturer'
    api = [int](Shell 'getprop ro.build.version.sdk')
    fingerprint = Shell 'getprop ro.build.fingerprint'
    screenState = if ($screenState) { "$screenState".Trim() } else { 'unknown' }
    batteryLevel = if ($battery -match 'level:\s*(\d+)') { [int]$Matches[1] } else { -1 }
    charging = $battery -match 'AC powered: true|USB powered: true|Wireless powered: true'
    thermalStatus = if ($thermal -match 'Thermal Status:\s*(\d+)') { [int]$Matches[1] } else { -1 }
    animationScales = [ordered]@{
        window = Shell 'settings get global window_animation_scale'
        transition = Shell 'settings get global transition_animation_scale'
        animator = Shell 'settings get global animator_duration_scale'
    }
}

adb shell input keyevent KEYCODE_WAKEUP | Out-Null
adb shell wm dismiss-keyguard | Out-Null
adb shell svc power stayon true | Out-Null
$windowDump = Shell 'dumpsys window'
$focus = (($windowDump -split "`n") | Select-String -Pattern 'mCurrentFocus=' | Select-Object -First 1)
if (-not $focus -or "$focus" -notmatch 'com\.example\.notionreader/') { Write-Warning "The reader is not the focused window: $focus" }

adb install -r $appApk | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'App APK install failed' }
# Never `pm clear`: the installed Gemma model lives under the app's no_backup directory, and
# clearing app data would delete it. The benchmark test owns its isolated root and wipes only
# that root for a cold measurement.
adb install -r $testApk | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Test APK install failed' }
adb shell rm -f $reportDevicePath $reportExternalPath | Out-Null
adb shell run-as $package rm -f $reportDevicePath | Out-Null

$startedAt = Get-Date
$instrumentArgs = @('-w', '-e', 'class', $testClass,
    '-e', 'discoveryBenchmarkMode', $Mode,
    '-e', 'discoveryBenchmarkRuns', "$Runs",
    '-e', 'discoveryBenchmarkWindowMs', "$($WindowSeconds * 1000)",
    "$testPackage/$runner")
$instrument = (adb shell am instrument @instrumentArgs) -join "`n"
$finishedAt = Get-Date
Write-Host $instrument

# Pull before judging: a native crash still leaves the records completed before it, and a
# partial report is exactly how a crash horizon becomes evidence instead of a lost run.
$pulled = $false
try {
    $lines = adb shell run-as $package cat $reportDevicePath 2>$null
    if ($LASTEXITCODE -eq 0 -and $lines) {
        Set-Content -Path $reportLocal -Value ($lines -join "`n") -Encoding utf8
        $pulled = $true
    }
} catch { $pulled = $false }
if (-not $pulled) {
    $null = (adb pull $reportExternalPath $reportLocal 2>$null)
    $pulled = Test-Path $reportLocal
}
$records = @()
if (Test-Path $reportLocal) {
    $records = @(Get-Content $reportLocal | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
}
$instrumentOk = $instrument -match 'OK \(\d+ tests?\)'
$expected = if ($Mode -eq 'paired') { $Runs * 2 } else { $Runs }
$failure = $null
if (-not $instrumentOk) { $failure = "Instrumentation did not report success: $instrument" }
elseif (-not (Test-Path $reportLocal)) { $failure = 'The benchmark produced no device report' }
elseif ($records.Count -ne $expected) { $failure = "Expected $expected benchmark records but observed $($records.Count)" }
else {
    foreach ($record in $records) {
        if ($record.corpusNotes -ne 100) { $failure = "A benchmark record did not cover 100 Notes: $($record.corpusNotes)"; break }
        if ($record.expectedUsefulSubjects -ne 10) { $failure = 'The usefulness denominator is missing'; break }
        if ($record.mode -notin @('cold', 'repeat')) { $failure = "Unexpected benchmark mode: $($record.mode)"; break }
        if ($record.newPublications -gt 0 -and $null -eq $record.firstVisibleTopicMs) {
            $failure = 'A run published without observing a first visible topic'; break
        }
        $hasRequest = ($record.PSObject.Properties.Name -contains 'work_proposalNativeRequest') -or
            ($record.PSObject.Properties.Name -contains 'work_proposalNativeRequestCount')
        # A run that never reached generation is valid only if it also published nothing.
        if (-not $hasRequest -and $record.newPublications -ne 0) {
            $failure = 'A run published without recording proposal requests'; break
        }
    }
    if (-not $failure -and -not ($records | Where-Object { $_.indexedAtEnd -gt 0 -or $_.startingIndexed -gt 0 })) {
        $failure = 'No record observed indexed coverage; the benchmark did not exercise its subject'
    }
    if (-not $failure -and $Mode -eq 'paired') {
        for ($i = 0; $i -lt $Runs; $i++) {
            $pair = @($records | Where-Object { $_.iteration -eq "$i" })
            if (@($pair | Where-Object { $_.mode -eq 'cold' }).Count -ne 1 -or
                @($pair | Where-Object { $_.mode -eq 'repeat' }).Count -ne 1) {
                $failure = "Iteration $i is not a cold/repeat pair"; break
            }
        }
    }
}

$manifest = [ordered]@{
    startedAt = $startedAt.ToString('o')
    finishedAt = $finishedAt.ToString('o')
    elapsedSeconds = [math]::Round(($finishedAt - $startedAt).TotalSeconds, 1)
    mode = $Mode
    runs = $Runs
    windowMs = $WindowSeconds * 1000
    corpusVersion = if ($records.Count -gt 0) { $records[0].corpusVersion } else { $null }
    appApkSha256 = (Get-FileHash $appApk -Algorithm SHA256).Hash
    testApkSha256 = (Get-FileHash $testApk -Algorithm SHA256).Hash
    apkLastWriteUtc = (Get-Item $appApk).LastWriteTimeUtc.ToString('o')
    conditions = $conditions
    instrumentation = $instrument
    observedRecords = $records.Count
    expectedRecords = $expected
    verdict = if ($failure) { 'failed' } else { 'passed' }
    failure = $failure
    report = (Split-Path -Leaf $reportLocal)
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -Path $manifestLocal -Encoding utf8

if ($records.Count -gt 0) {
    Write-Host ''
    $records | Select-Object mode, iteration, elapsedMs, terminalReached,
        @{n='firstVisibleMs';e={$_.firstVisibleTopicMs}},
        @{n='firstVisibleWorkMs';e={ $ns = if ($null -ne $_.work_firstVisiblePublishedTopicNanos) { $_.work_firstVisiblePublishedTopicNanos } else { $_.work_firstPublishedTopicNanos }; if ($null -ne $ns) { [math]::Round($ns / 1000000) } }},
        indexedAtEnd, publications, newPublications,
        @{n='proposalNative';e={$_.work_proposalNativeRequest}},
        @{n='modelCacheHit';e={$_.work_proposalModelCacheHit}},
        @{n='proposalAttempts';e={$_.native_proposalAttempts}},
        @{n='proposalOpens';e={$_.native_proposalOpenNanosCount}},
        @{n='supportAttempts';e={$_.native_supportAttempts}} | Format-Table -AutoSize | Out-Host
}
Write-Host "Report:   $reportLocal"
Write-Host "Manifest: $manifestLocal"
if ($failure) { throw $failure }
