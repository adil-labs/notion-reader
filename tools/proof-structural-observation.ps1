[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$source = Join-Path $repoRoot 'app/src/main/java/com/example/notionreader/data/structure/FileStructuralKnowledgeStore.kt'
$original = [IO.File]::ReadAllText($source)
$encoding = [Text.UTF8Encoding]::new($false)
$output = Join-Path $repoRoot 'build/structural-observation-proof'
[void][IO.Directory]::CreateDirectory($output)
$previousLocation = Get-Location
function Invoke-Stage([string]$stage, [string]$class, [string]$test, [bool]$red) {
    $started = [DateTime]::UtcNow
    & (Join-Path $repoRoot 'gradlew.bat') :app:testDebugUnitTest --tests "$class.$test" *> (Join-Path $output "$stage.log")
    $code = $LASTEXITCODE
    $path = Join-Path $repoRoot "app/build/test-results/testDebugUnitTest/TEST-$class.xml"
    if (-not (Test-Path -LiteralPath $path) -or (Get-Item -LiteralPath $path).LastWriteTimeUtc -lt $started) { throw "$stage missing fresh evidence" }
    [xml]$xml = [IO.File]::ReadAllText($path)
    $suite = $xml.testsuite
    if ([int]$suite.tests -ne 1 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0 -or $suite.testcase.name -ne $test) { throw "$stage wrong test/completeness" }
    if ($red) {
        if ($code -eq 0 -or [int]$suite.failures -ne 1 -or $suite.testcase.failure.type -notmatch 'AssertionError|ComparisonFailure') { throw "$stage did not fail at its assertion" }
    } elseif ($code -ne 0 -or [int]$suite.failures -ne 0) { throw "$stage did not pass" }
    Copy-Item -LiteralPath $path -Destination (Join-Path $output "$stage.xml")
    Write-Host "$stage`: one test, expected assertion failure=$red"
}
try {
    Set-Location -LiteralPath $repoRoot
    $renewal = 'val admittedEpoch = state().epoch'
    $guard = 'if (active.identity != sourceIdentity || active.epoch != sourceEpoch) return@locked false'
    if (-not $original.Contains($renewal) -or -not $original.Contains($guard)) { throw 'Shipping observation guards changed; review proof' }
    [IO.File]::WriteAllText($source, $original.Replace($renewal, 'val admittedEpoch = sourceEpoch'), $encoding)
    Invoke-Stage 'self-invalidation-restored' 'com.example.notionreader.application.StructuralKnowledgeCoordinatorTest' 'oneObservationWithdrawsTargetsAndCommitsItsSourceWithoutCollectingAgain' $true
    [IO.File]::WriteAllText($source, $original.Replace($guard, 'if (active.identity != sourceIdentity) return@locked false'), $encoding)
    Invoke-Stage 'external-epoch-guard-removed' 'com.example.notionreader.data.structure.StructuralObservationAdmissionTest' 'aWithdrawnObservationCannotRenewItsTicketAfterAnExternalClear' $true
    [IO.File]::WriteAllText($source, $original, $encoding)
    Invoke-Stage 'source-commit-restored' 'com.example.notionreader.application.StructuralKnowledgeCoordinatorTest' 'oneObservationWithdrawsTargetsAndCommitsItsSourceWithoutCollectingAgain' $false
    Invoke-Stage 'external-guard-restored' 'com.example.notionreader.data.structure.StructuralObservationAdmissionTest' 'aWithdrawnObservationCannotRenewItsTicketAfterAnExternalClear' $false
} finally {
    [IO.File]::WriteAllText($source, $original, $encoding)
    Set-Location -LiteralPath $previousLocation.Path
}
