[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$source = Join-Path $repoRoot 'app/src/main/java/com/example/notionreader/ReaderApplication.kt'
$original = [IO.File]::ReadAllText($source)
$encoding = [Text.UTF8Encoding]::new($false)
$output = Join-Path $repoRoot 'build/structural-isolation-proof'
[void][IO.Directory]::CreateDirectory($output)
$previousLocation = Get-Location
function Invoke-Stage([string]$name, [bool]$expectFailure) {
    $started = [DateTime]::UtcNow
    & pwsh -NoProfile -File (Join-Path $repoRoot 'tools/run-accessibility-tests.ps1') -Class com.example.notionreader.ui.StructuralPipelineIntegrationTest *> (Join-Path $output "$name.log")
    $code = $LASTEXITCODE
    $reports = @(Get-ChildItem (Join-Path $repoRoot 'app/build/outputs/androidTest-results/connected/debug/TEST-*.xml') | Where-Object LastWriteTimeUtc -ge $started)
    $cases = @()
    foreach ($report in $reports) {
        [xml]$xml = [IO.File]::ReadAllText($report.FullName)
        $suites = if ($xml.testsuites) { @($xml.testsuites.testsuite) } else { @($xml.testsuite) }
        foreach ($s in $suites) {
            $cases += @($s.testcase | Where-Object classname -eq 'com.example.notionreader.ui.StructuralPipelineIntegrationTest')
        }
        Copy-Item -LiteralPath $report.FullName -Destination (Join-Path $output "$name-$($report.Name)")
    }
    if ($cases.Count -ne 1 -or $cases[0].error -or $cases[0].skipped) { throw "$name incomplete or wrong failure type" }
    if ($expectFailure) {
        $failureText = [string]$cases[0].failure
        if ($code -eq 0 -or -not $cases[0].failure -or $failureText -notmatch 'AssertionError|ComparisonFailure') { throw "$name did not fail at the intended assertion" }
    } elseif ($code -ne 0 -or $cases[0].failure) { throw "$name did not pass the repository wrapper" }
    Write-Host "$name`: one test, expected failure=$expectFailure, wrapper exit=$code"
}
try {
    Set-Location -LiteralPath $repoRoot
    $guard = 'catch (_: Exception) { null } // Fail closed for structure without preventing reading or Discovery.'
    if (-not $original.Contains($guard)) { throw 'Shipping isolation guard changed; review proof first' }
    [IO.File]::WriteAllText($source, $original.Replace($guard, 'catch (failure: Exception) { throw failure } // Guard removed by isolation proof.'), $encoding)
    Invoke-Stage 'guard-removed' $true
    [IO.File]::WriteAllText($source, $original, $encoding)
    Invoke-Stage 'restored' $false
} finally {
    [IO.File]::WriteAllText($source, $original, $encoding)
    Set-Location -LiteralPath $previousLocation.Path
}
