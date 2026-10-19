#requires -Version 7.0
param([Parameter(Mandatory)][string]$Device)
$ErrorActionPreference = 'Stop'
if ($Device -notmatch '^emulator-\d+$') { throw 'Use only the owned emulator; never terminate a physical-device app for this probe' }
$package = 'com.example.notionreader'
$wrapper = Join-Path $PSScriptRoot 'run-accessibility-tests.ps1'
$reportDirectory = Join-Path ([IO.Path]::GetTempPath()) 'notion-reader-automatic-recovery'
$null = New-Item -ItemType Directory -Path $reportDirectory -Force
$verified = $false
try {
    & adb -s $Device shell run-as $package touch files/automatic-recovery-prepare
    if ($LASTEXITCODE -ne 0) { throw 'Install the debug app and pinned model before the probe' }
    & pwsh -NoProfile -File $wrapper -Device $Device -Class com.example.notionreader.ui.AutomaticDiscoveryRecoveryDeviceTest *> (Join-Path $reportDirectory 'prepare.log')
    if ($LASTEXITCODE -ne 0) { throw 'Preparation failed; inspect prepare.log' }
    $stateText = (& adb -s $Device shell run-as $package cat files/automatic-recovery-state.json) -join "`n"
    $state = $stateText | ConvertFrom-Json
    & adb -s $Device shell run-as $package rm files/automatic-recovery-prepare
    $runningPid = ((& adb -s $Device shell pidof $package) -join '').Trim()
    if ($runningPid) {
        if ($runningPid -notmatch '^\d+$') { throw 'Refusing an ambiguous process target' }
        & adb -s $Device shell run-as $package kill -9 $runningPid
        if ($LASTEXITCODE -ne 0) { throw 'Could not terminate the owned app process' }
    }
    & adb -s $Device shell dumpsys battery set level 100
    & adb -s $Device shell dumpsys battery set usb 1
    # No activity launch, instrumentation or forced JobScheduler command during recovery.
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $receiptText = $null
    while ($clock.Elapsed.TotalSeconds -lt 180) {
        try {
            $candidateText = (& adb -s $Device shell run-as $package cat cache/discovery-window-receipt.json 2>$null) -join "`n"
            $candidate = $candidateText | ConvertFrom-Json
            $snapshotText = (& adb -s $Device shell run-as $package cat no_backup/discovery-topics/topics.json 2>$null) -join "`n"
            $snapshot = ($snapshotText | ConvertFrom-Json).snapshot
            $members = @($snapshot.topics | ForEach-Object { $_.members | ForEach-Object pageId })
            $missing = @($state.ids | Where-Object { $_ -cnotin $members })
            if ($candidate.result -ceq 'COMPLETE' -and $candidate.processId -ne $state.oldProcessId -and
                $candidate.startedInteractive -eq $false -and $snapshot.complete -and $snapshot.connectionPassComplete -and $missing.Count -eq 0) {
                $receiptText = $candidateText; break
            }
        } catch { } # An incomplete write or unstarted process is not completion evidence.
        Start-Sleep -Milliseconds 500
    }
    if (-not $receiptText) { throw 'No headless completion observed within 180 seconds; recovery remains unverified' }
    $receiptPath = Join-Path $reportDirectory 'headless-receipt.json'
    [IO.File]::WriteAllText($receiptPath, $receiptText)
    & adb -s $Device push $receiptPath /data/local/tmp/notion-reader-recovery-receipt.json
    if ($LASTEXITCODE -ne 0) { throw 'Cannot stage captured receipt' }
    & adb -s $Device shell run-as $package cp /data/local/tmp/notion-reader-recovery-receipt.json files/automatic-recovery-receipt.json
    if ($LASTEXITCODE -ne 0) { throw 'Cannot preserve pre-instrumentation receipt' }
    & adb -s $Device shell rm /data/local/tmp/notion-reader-recovery-receipt.json
    & pwsh -NoProfile -File $wrapper -Device $Device -Class com.example.notionreader.ui.AutomaticDiscoveryRecoveryDeviceTest *> (Join-Path $reportDirectory 'verify.log')
    if ($LASTEXITCODE -ne 0) { throw 'Recovery verification failed; inspect verify.log' }
    $verified = $true
    Write-Output "Verified durable passage recovery in a new headless process. Reports: $reportDirectory"
} finally {
    & adb -s $Device shell dumpsys battery reset
    & adb -s $Device shell input keyevent KEYCODE_WAKEUP
    & adb -s $Device shell wm dismiss-keyguard
    & adb -s $Device shell run-as $package rm -f files/automatic-recovery-prepare
    if (-not $verified) {
        # Its finally removes only its fixture IDs/restores settings, even on receipt failure.
        & adb -s $Device shell run-as $package test -f files/automatic-recovery-state.json
        if ($LASTEXITCODE -eq 0) {
            & pwsh -NoProfile -File $wrapper -Device $Device -Class com.example.notionreader.ui.AutomaticDiscoveryRecoveryDeviceTest *> (Join-Path $reportDirectory 'cleanup.log')
        }
    }
}
