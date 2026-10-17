#requires -Version 7.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'benchmark-verdict.ps1')
function Fixture([double]$Offset = 0) {
    [pscustomobject]@{status='passed';notes=100;passages=10000;samples=20;
        querySamplesMs=@(100..119 | ForEach-Object { $_ + $Offset });queryP50Ms=109+$Offset;queryP95Ms=118+$Offset}
}
function Reject($Report, [string]$Expected) {
    $failure = $null
    try { Assert-ShippingBenchmarkReport $Report } catch { $failure = $_.Exception.Message }
    if (-not $failure -or $failure -notmatch $Expected) { throw "Verifier did not reject for $Expected" }
}
Assert-ShippingBenchmarkReport (Fixture)
Reject (Fixture 2000) 'exceeds'
$incomplete = Fixture; $incomplete.querySamplesMs = @(100..118); Reject $incomplete 'INCOMPLETE'
$wrong = Fixture; $wrong.queryP95Ms = 0; Reject $wrong 'percentile'
$invalid = Fixture; $invalid.querySamplesMs[0] = [double]::NaN; Reject $invalid 'Invalid query timing'
$errored = Fixture; $errored.status='error'; $errored | Add-Member errorType 'IOException'; Reject $errored 'Benchmark error'
function WindowFixture {
    $report = Fixture
    $report | Add-Member reportFormatVersion 2
    $report | Add-Member requestedQueryDelaySeconds 3
    $report | Add-Member readyEpochMs 100000
    $report | Add-Member readyElapsedRealtimeMs 10000
    $report | Add-Member queryWindowStartEpochMs 103001
    $report | Add-Member queryWindowEndEpochMs 106001
    $report | Add-Member queryWindowStartElapsedRealtimeMs 13001
    $report | Add-Member queryWindowEndElapsedRealtimeMs 16001
    $report
}
Assert-ShippingBenchmarkReport (WindowFixture)
foreach ($field in @('requestedQueryDelaySeconds', 'readyEpochMs', 'readyElapsedRealtimeMs',
    'queryWindowStartEpochMs', 'queryWindowEndEpochMs', 'queryWindowStartElapsedRealtimeMs', 'queryWindowEndElapsedRealtimeMs')) {
    $missing = WindowFixture; $missing.PSObject.Properties.Remove($field); Reject $missing 'window metadata'
}
$nonfinite = WindowFixture; $nonfinite.readyElapsedRealtimeMs = [double]::NaN; Reject $nonfinite 'window metadata'
$epochBackwards = WindowFixture; $epochBackwards.queryWindowEndEpochMs = 102000; Reject $epochBackwards 'window metadata'
$backwards = WindowFixture; $backwards.queryWindowEndElapsedRealtimeMs = 12000; Reject $backwards 'window metadata'
$tooShort = WindowFixture; $tooShort.queryWindowEndElapsedRealtimeMs = 13002; Reject $tooShort 'window metadata'
$early = WindowFixture; $early.queryWindowStartEpochMs = 101000; Reject $early 'window metadata'
$badDelay = WindowFixture; $badDelay.requestedQueryDelaySeconds = 121; Reject $badDelay 'window metadata'
$partial = Fixture; $partial | Add-Member queryWindowStartEpochMs 1; Reject $partial 'window metadata'
Write-Host 'Benchmark verdict: valid report accepted; slow, incomplete, incorrect percentile, nonfinite and error reports rejected; historical reports accepted; missing, partial and impossible v2 windows rejected.'
