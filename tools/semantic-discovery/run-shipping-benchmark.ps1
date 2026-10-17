#requires -Version 7.0
param([Parameter(Mandatory)][string]$Device, [ValidateRange(0, 120)][int]$QueryDelaySeconds = 0)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'benchmark-verdict.ps1')
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repo
try {
    $qemu = ([string](& adb -s $Device shell getprop ro.kernel.qemu)).Trim()
    if ($LASTEXITCODE) { throw 'Unable to verify selected device' }
    $hardware = ([string](& adb -s $Device shell getprop ro.hardware)).Trim()
    if ($LASTEXITCODE -or -not $hardware -or $qemu -eq '1' -or $hardware -match 'ranchu|goldfish' -or $Device -match '^emulator-') {
        throw 'This acceptance gate requires a physical Android device'
    }
    & ./gradlew.bat -p tools/semantic-discovery/android-benchmark assembleDebug
    if ($LASTEXITCODE) { throw 'Benchmark build failed; no prior APK may be used' }
    $generated = Join-Path $repo 'tools/semantic-discovery/android-benchmark/build/generated/shipping'
    $sources = @(Get-ChildItem $generated -Recurse -File)
    if (-not $sources.Count) { throw 'No shipping sources in benchmark' }
    foreach ($copy in $sources) {
        $relative = [IO.Path]::GetRelativePath($generated, $copy.FullName)
        $source = Join-Path $repo "app/src/main/java/$relative"
        if ((Get-FileHash -LiteralPath $source).Hash -ne (Get-FileHash -LiteralPath $copy.FullName).Hash) {
            throw 'Benchmark generated sources are stale; rebuild before measuring'
        }
    }
    # Only this isolated no-permissions package is installed. Never install/uninstall the reader here.
    $apkDirectory = Join-Path $repo 'tools/semantic-discovery/android-benchmark/build/outputs/apk/debug'
    $apks = @(Get-ChildItem $apkDirectory -Filter '*.apk' -File)
    if ($apks.Count -ne 1) { throw 'Expected exactly one isolated benchmark APK' }
    $apkHash = (Get-FileHash -LiteralPath $apks[0].FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    & adb -s $Device install -r $apks[0].FullName
    if ($LASTEXITCODE) { throw 'Isolated benchmark install failed' }
    $runId = [guid]::NewGuid().ToString()
    & adb -s $Device shell am force-stop com.example.discoverybenchmark
    & adb -s $Device shell am start -n com.example.discoverybenchmark/.BenchmarkActivity --es runId $runId --ei queryDelaySeconds $QueryDelaySeconds
    if ($LASTEXITCODE) { throw 'Isolated benchmark launch failed' }
    $started = [Diagnostics.Stopwatch]::StartNew()
    $report = $null
    $readyPrinted = $false
    while ($started.Elapsed.TotalSeconds -lt 600) {
        if (-not $readyPrinted) {
            $readyRaw = & adb -s $Device exec-out run-as com.example.discoverybenchmark cat no_backup/ready.json 2>$null
            if ($LASTEXITCODE -eq 0) {
                try { $ready = ($readyRaw -join "`n") | ConvertFrom-Json } catch { $ready = $null }
                if ($ready.runId -ceq $runId) {
                    Write-Host "READY: runId=$runId; requested query delay=$($ready.requestedQueryDelaySeconds)s; ready epochMs=$($ready.readyEpochMs); elapsedRealtimeMs=$($ready.readyElapsedRealtimeMs). Correlate the recorded query window with independent reader speech evidence."
                    $readyPrinted = $true
                }
            }
        }
        $raw = & adb -s $Device exec-out run-as com.example.discoverybenchmark cat no_backup/report.json 2>$null
        if ($LASTEXITCODE -eq 0) {
            try { $candidate = ($raw -join "`n") | ConvertFrom-Json } catch { $candidate = $null }
            if ($candidate.runId -ceq $runId) { $report = $candidate; break }
        }
        Start-Sleep -Seconds 2
    }
    if (-not $report) { throw "INCOMPLETE: no current report after 600s; runId=$runId. Inspect the live benchmark before restarting." }
    $report | Add-Member -NotePropertyName apkSha256 -NotePropertyValue $apkHash
    $report | Add-Member -NotePropertyName deviceType -NotePropertyValue 'physical'
    $report | Add-Member -NotePropertyName hardware -NotePropertyValue $hardware
    $output = Join-Path $repo "app/build/discovery-benchmark/$runId.json"
    New-Item -ItemType Directory -Force ([IO.Path]::GetDirectoryName($output)) | Out-Null
    $report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $output
    Write-Host "Public-data report: $output"
    Assert-ShippingBenchmarkReport $report
    Write-Host "PASSED: 20 queries, p50=$($report.queryP50Ms)ms, p95=$($report.queryP95Ms)ms"
} finally { Pop-Location }
