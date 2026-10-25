#requires -Version 7.0
<#! Downloads published immutable artifacts, verifies bytes, then stages test-only emulator cache.
No conversion/export, production-model installation or private Note access. #>
param([string]$Device = 'emulator-5554', [string]$Directory = 'build/litert-migration/model')
$ErrorActionPreference = 'Stop'
if ($Device -notmatch '^emulator-\d+$') { throw 'This probe stages emulator cache only.' }
$artifacts = @(
    @{ Repository = 'litert-community/embeddinggemma-2-text-270m-litert-lm'; Revision = '9be6e8b90982095dc05c2bd162e4b954ee4dbac7'; File = 'embeddinggemma-2-text-270m.litertlm'; Bytes = 164626432; Hash = '2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb' }
)
$null = New-Item -ItemType Directory -Force $Directory
foreach ($artifact in $artifacts) {
    & hf download $artifact.Repository $artifact.File --revision $artifact.Revision --local-dir $Directory
    if ($LASTEXITCODE -ne 0) { throw 'Published artifact download failed' }
    $path = Join-Path $Directory $artifact.File
    if ((Get-Item -LiteralPath $path).Length -ne $artifact.Bytes -or
        (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $artifact.Hash) {
        throw "Artifact integrity failed: $($artifact.File)"
    }
}
& adb -s $Device shell run-as com.example.notionreader mkdir -p cache/litert-gemma-test
if ($LASTEXITCODE -ne 0) { throw 'Install the debug APK on the selected emulator first' }
foreach ($artifact in $artifacts) {
    $remote = '/data/local/tmp/litert-test-' + $artifact.File
    & adb -s $Device push (Join-Path $Directory $artifact.File) $remote
    if ($LASTEXITCODE -ne 0) { throw 'Artifact push failed' }
    & adb -s $Device shell run-as com.example.notionreader cp $remote ('cache/litert-gemma-test/' + $artifact.File)
    if ($LASTEXITCODE -ne 0) { throw 'Test cache staging failed' }
}
Write-Host 'Verified published bundle with its embedded SentencePiece tokenizer staged in emulator test cache.'
