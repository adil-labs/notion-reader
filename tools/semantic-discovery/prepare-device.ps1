#requires -Version 7.0
param([Parameter(Mandatory)][string]$Device,[string]$ArtifactDirectory = 'app/build/discovery-feasibility')
$ErrorActionPreference = 'Stop'
$manifest = Get-Content (Join-Path $PSScriptRoot 'artifacts.json') -Raw | ConvertFrom-Json
$files = @{ 'model_qint8_arm64.onnx' = 'onnx/model_qint8_arm64.onnx'; 'vocab.txt' = 'vocab.txt' }
foreach ($name in $files.Keys) {
    $source = Join-Path $ArtifactDirectory $files[$name]
    if (!(Test-Path -LiteralPath $source)) { throw "Missing artifact: $source. Run evaluate.py first." }
    $expected = $manifest.artifacts.($files[$name]).sha256
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -cne $expected) { throw "Checksum mismatch: $source" }
    & adb -s $Device push $source "/data/local/tmp/discovery-$name"
    if ($LASTEXITCODE) { throw 'Artifact push failed' }
}
Write-Host 'Verified public model staged in /data/local/tmp; the test copies and rechecks it after Gradle installs the app.'
Write-Host 'The staging files must remain until instrumentation completes; no production model/download state changed.'
Write-Host "Run: pwsh -NoProfile -File tools/run-accessibility-tests.ps1 -Device $Device -Class com.example.notionreader.discovery.ShippingDiscoveryInferenceTest"
