#requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$Device,
    [Parameter(Mandatory)][string]$ModelDirectory,
    [switch]$DryRun,
    [ValidateSet('int8', 'q4')][string]$Precision = 'q4'
)
$ErrorActionPreference = 'Stop'
$manifest = Get-Content (Join-Path $PSScriptRoot 'gemma2-quantization-2026-10-18.json') -Raw | ConvertFrom-Json -AsHashtable
$pack = if ($Precision -eq 'int8') { $manifest.models.int8.identity.modelFiles } else { $manifest.models.q4.files }
$files = @()
foreach ($entry in $pack.GetEnumerator()) {
    $name = [IO.Path]::GetFileName($entry.Key)
    if ($name -notmatch '^[a-zA-Z0-9_.-]+$') { throw 'Unexpected pinned artifact path' }
    $file = Join-Path $ModelDirectory $entry.Key
    if (-not (Test-Path -LiteralPath $file)) { $file = Join-Path $ModelDirectory $name }
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing pinned Gemma file: $name" }
    if ((Get-Item -LiteralPath $file).Length -ne $entry.Value.bytes) { throw "Gemma size mismatch: $name" }
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $entry.Value.sha256) { throw "Gemma checksum mismatch: $name" }
    $files += @{ Path = (Resolve-Path -LiteralPath $file).Path; Name = $name; Hash = $entry.Value.sha256 }
}
if ($files.Count -ne 5) { throw 'Expected the complete five-file text pack' }
Write-Output "Verified $($files.Count) pinned $Precision Gemma files."
if ($DryRun) { exit 0 }
$installed = & adb -s $Device shell pm path com.example.notionreader
if ($LASTEXITCODE -ne 0 -or -not ($installed -match '^package:')) { throw 'Install the debug app before staging public model fixtures' }
$destination = if ($Precision -eq 'int8') { 'cache/gemma-test-model' } else { 'cache/gemma-benchmark-q4' }
$temporary = '/data/local/tmp/notion-reader-gemma-public-pack'
& adb -s $Device shell run-as com.example.notionreader mkdir -p $destination
if ($LASTEXITCODE -ne 0) { throw 'Cannot create the isolated public test-pack directory' }
& adb -s $Device shell mkdir -p $temporary
if ($LASTEXITCODE -ne 0) { throw 'Cannot create public transfer directory' }
foreach ($file in $files) {
    & adb -s $Device push $file.Path "$temporary/$($file.Name)"
    if ($LASTEXITCODE -ne 0) { throw "Cannot stage $($file.Name)" }
    & adb -s $Device shell run-as com.example.notionreader cp "$temporary/$($file.Name)" "$destination/$($file.Name)"
    if ($LASTEXITCODE -ne 0) { throw "Cannot copy the public fixture as the app owner: $($file.Name)" }
    $deviceHash = & adb -s $Device shell run-as com.example.notionreader sha256sum "$destination/$($file.Name)"
    if ($LASTEXITCODE -ne 0 -or (($deviceHash -join '').Split(' ')[0] -cne $file.Hash)) {
        throw "App-owned fixture checksum mismatch: $($file.Name)"
    }
    & adb -s $Device shell rm "$temporary/$($file.Name)"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot remove the completed public transfer file' }
}
Write-Output 'Staged public test fixtures only; ordinary model acquisition still uses Discovery settings.'
