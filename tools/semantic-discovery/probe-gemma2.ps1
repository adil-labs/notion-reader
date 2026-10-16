#requires -Version 7.0
# Static adoption gate only. Never installs dependencies or models in the reader.
param([string]$OutputDirectory = 'app/build/gemma2-feasibility')
$ErrorActionPreference = 'Stop'
$lockPath = Join-Path $PSScriptRoot 'gemma2-gate-report.json'
$report = Get-Content -Raw $lockPath | ConvertFrom-Json
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
$aar = Join-Path $OutputDirectory 'litertlm-android-0.18.0.aar'
if (!(Test-Path -LiteralPath $aar)) {
    Invoke-WebRequest 'https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/0.18.0/litertlm-android-0.18.0.aar' -OutFile $aar
}
if ((Get-FileHash -LiteralPath $aar -Algorithm SHA256).Hash.ToLowerInvariant() -cne $report.aar.aar_sha256) {
    throw 'Runtime AAR checksum does not match the recorded immutable probe'
}
$inspection = & python (Join-Path $PSScriptRoot 'inspect_aar.py') $aar
if ($LASTEXITCODE -ne 0) { throw 'AAR inspection failed' }
$native = ($inspection -join "`n") | ConvertFrom-Json
$actualAbis = @($native.libraries | ForEach-Object { ($_.name -split '/')[1] } | Sort-Object -Unique)
$missing = @($report.existingSupportedAbis | Where-Object { $_ -cnotin $actualAbis })
$artifact = $report.artifact
$metadata = Invoke-RestMethod "https://huggingface.co/api/models/$($artifact.repository)/revision/$($artifact.revision)?blobs=true"
$file = @($metadata.siblings | Where-Object rfilename -CEQ $artifact.file)
if ($file.Count -ne 1 -or $file[0].size -ne $artifact.bytes -or $file[0].lfs.sha256 -cne $artifact.publisherLfsSha256) {
    throw 'Pinned converted-model metadata does not match the recorded probe'
}
$report.aar = $native
$report.missingAbis = $missing
$report | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $OutputDirectory 'gate-report.json') -Encoding utf8
Write-Host "Measured native ABIs: $($actualAbis -join ', '); missing: $($missing -join ', ')"
Write-Host 'BLOCKED: default migration needs complete ABI coverage and native quality/performance/parity evidence.'
exit 2
