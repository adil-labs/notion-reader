#requires -Version 7.0
# Proves the shipping accelerator validation rejects incompatible vectors.
param([switch]$CpuFailureAttribution)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$source = Join-Path $root 'app/src/main/java/com/example/notionreader/data/discovery/LiteRtBackendRuntime.kt'
$original = [IO.File]::ReadAllText($source)
$guard = if ($CpuFailureAttribution) { 'sessionBackend = target; session = open(target)' }
    else { 'if (cosine < .999) throw EmbeddingBackendValidationFailure(cosine)' }
$disabled = if ($CpuFailureAttribution) { 'session = open(target); sessionBackend = target' }
    else { 'if (false) throw EmbeddingBackendValidationFailure(cosine)' }
$expected = if ($CpuFailureAttribution) { 'aLongInputCpuInitializationFailureIsNotRetriedOrBlamedOnGpu' }
    else { 'incompatibleAcceleratorCannotPolluteTheExistingEmbeddingSpace' }
if (($original.Split($guard)).Count -ne 2) { throw 'Expected exactly one validation guard.' }
try {
    [IO.File]::WriteAllText($source, $original.Replace($guard, $disabled))
    Push-Location $root
    try {
        & .\gradlew.bat :app:testDebugUnitTest --tests '*LiteRtBackendRuntimeTest'
        $result = $LASTEXITCODE
        $report = Join-Path $root 'app/build/test-results/testDebugUnitTest/TEST-com.example.notionreader.data.discovery.LiteRtBackendRuntimeTest.xml'
        [xml]$xml = Get-Content -Raw $report
        $failed = @($xml.testsuite.testcase | Where-Object { $_.failure })
        if ($result -eq 0 -or $failed.Count -ne 1 -or
            $failed[0].name -cne $expected) {
            throw 'Proof failed: the intended sole regression was not observed.'
        }
        Write-Host "PROVED: disabling the guard fails exactly $expected."
    } finally { Pop-Location }
} finally { [IO.File]::WriteAllText($source, $original) }
