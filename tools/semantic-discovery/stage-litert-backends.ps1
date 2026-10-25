#requires -Version 7.0
<# Stages verified public artifacts only in isolated test cache on an explicitly selected device.
Does not install a production model, alter settings or access private Notes. #>
param([Parameter(Mandatory)][string]$Device, [switch]$Npu)
$ErrorActionPreference = 'Stop'
$artifacts = @(
    @{ File = 'embeddinggemma-2-text-270m.litertlm'; Directory = 'build/litert-migration/model'; Bytes = 164626432; Hash = '2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb' }
)
if ($Npu) {
    $soc = (& adb -s $Device shell getprop ro.soc.model).Trim()
    if ($LASTEXITCODE -ne 0 -or $soc -cne 'SM8750') { throw 'NPU artifact is for SM8750 only.' }
    $artifacts += @{ File = 'embeddinggemma-2-text-270m_Qualcomm_SM8750.litertlm'; Directory = 'build/litert-migration/npu-model'; Bytes = 187596800; Hash = 'b5615709768db4ca4fb4f04514e7e41b0680ccb12dd5f90371193f7fd12e7c99' }
}
foreach ($artifact in $artifacts) {
    $path = Join-Path $artifact.Directory $artifact.File
    if ((Get-Item -LiteralPath $path).Length -ne $artifact.Bytes -or
        (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $artifact.Hash) { throw 'Published artifact integrity failed.' }
}
& adb -s $Device shell run-as com.example.notionreader mkdir -p cache/litert-gemma-test
if ($LASTEXITCODE -ne 0) { throw 'Install the debug APK on the selected device first.' }
foreach ($artifact in $artifacts) {
    $remote = '/data/local/tmp/litert-test-' + $artifact.File
    & adb -s $Device push (Join-Path $artifact.Directory $artifact.File) $remote
    if ($LASTEXITCODE -ne 0) { throw 'Artifact push failed.' }
    & adb -s $Device shell run-as com.example.notionreader cp $remote ('cache/litert-gemma-test/' + $artifact.File)
    if ($LASTEXITCODE -ne 0) { throw 'Test cache staging failed.' }
}
Write-Host 'Verified public artifacts staged only in isolated test cache.'
