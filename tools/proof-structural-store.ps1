# Prove the shipping structural eligibility and epoch guards detect their own regressions.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$source = Join-Path $repoRoot 'app/src/main/java/com/example/notionreader/data/structure/FileStructuralKnowledgeStore.kt'
$testSource = Join-Path $repoRoot 'app/src/test/java/com/example/notionreader/data/structure/FileStructuralKnowledgeStoreTest.kt'
$result = Join-Path $repoRoot 'app/build/test-results/testDebugUnitTest/TEST-com.example.notionreader.data.structure.FileStructuralKnowledgeStoreTest.xml'
$output = Join-Path $repoRoot 'build/structural-store-proof'
[void][IO.Directory]::CreateDirectory($output)
$original = [IO.File]::ReadAllText($source)
$encoding = [Text.UTF8Encoding]::new($false)
$testClass = 'com.example.notionreader.data.structure.FileStructuralKnowledgeStoreTest'
$previousLocation = Get-Location

function Replace-Guard([string]$text, [string]$needle, [string]$replacement) {
    if (-not $text.Contains($needle)) { throw "Shipping guard changed; review proof: $needle" }
    return $text.Replace($needle, $replacement)
}

function Invoke-ProofStage([string]$name, [string[]]$tests, [string[]]$expectedFailures, [int]$expectedCount) {
    $started = [DateTime]::UtcNow
    $gradleArgs = @(':app:testDebugUnitTest')
    foreach ($test in $tests) { $gradleArgs += @('--tests', "$testClass.$test") }
    if ($tests.Count -eq 0) { $gradleArgs += @('--tests', $testClass) }
    & (Join-Path $repoRoot 'gradlew.bat') @gradleArgs *> (Join-Path $output "$name.log")
    $code = $LASTEXITCODE
    if (-not (Test-Path -LiteralPath $result)) { throw "$name did not produce a report" }
    if ((Get-Item -LiteralPath $result).LastWriteTimeUtc -lt $started) { throw "$name reused stale results" }
    [xml]$xml = [IO.File]::ReadAllText($result)
    $suite = $xml.testsuite
    $failed = @($suite.testcase | Where-Object { $_.failure -or $_.error } | ForEach-Object { [string]$_.name })
    if ([int]$suite.tests -ne $expectedCount -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) {
        throw "$name has incomplete/unexpected test counts"
    }
    if (@(Compare-Object @($expectedFailures | Sort-Object) @($failed | Sort-Object)).Count -ne 0) {
        throw "$name failed for the wrong reason: $($failed -join ', ')"
    }
    foreach ($failure in @($suite.testcase | Where-Object { $_.failure })) {
        if ([string]$failure.failure.type -notmatch 'AssertionError|ComparisonFailure') {
            throw "$name failed before the intended assertion"
        }
    }
    if (($expectedFailures.Count -eq 0 -and $code -ne 0) -or ($expectedFailures.Count -gt 0 -and $code -eq 0)) {
        throw "$name process status disagrees with assertion reports"
    }
    Copy-Item -LiteralPath $result -Destination (Join-Path $output "$name.xml")
    Write-Host "$name`: $expectedCount tests, $($failed.Count) expected assertion failures"
}

try {
    Set-Location -LiteralPath $repoRoot
    $withoutEligibility = Replace-Guard $original 'active.identity.isEmpty() || id in active.removals' 'active.identity.isEmpty()'
    $withoutEligibility = Replace-Guard $withoutEligibility 'dto.sourceIdentity != active.identity ||' ''
    $withoutEligibility = Replace-Guard $withoutEligibility 'dto.page.sourceIdentity != active.identity ||' ''
    $withoutEligibility = Replace-Guard $withoutEligibility 'dto.eligibilityEpoch < active.minimumEpoch || ' ''
    $withoutEligibility = Replace-Guard $withoutEligibility ' || it.provenance.sourceIdentity != active.identity' ''
    [IO.File]::WriteAllText($source, $withoutEligibility, $encoding)
    $privacyTests = @('switchedSourceCannotReadOldContentAfterRestartAndIndexRecovery', 'durableRemovalHidesAPayloadLeftBehindByInterruptedDeletion', 'returningToAnOldSourceDoesNotAdmitItsOrphanedPayload')
    Invoke-ProofStage 'eligibility-removed' $privacyTests $privacyTests 3

    $withoutEpoch = Replace-Guard $original 'active.epoch != sourceEpoch || ' ''
    [IO.File]::WriteAllText($source, $withoutEpoch, $encoding)
    $epochTests = @('aDownloadSuspendedAcrossRemovalCannotPublishButNewWorkCanRecover')
    Invoke-ProofStage 'epoch-removed' $epochTests $epochTests 1

    [IO.File]::WriteAllText($source, $original, $encoding)
    $expected = [regex]::Matches([IO.File]::ReadAllText($testSource), '@Test\b').Count
    Invoke-ProofStage 'restored' @() @() $expected
} finally {
    [IO.File]::WriteAllText($source, $original, $encoding)
    Set-Location -LiteralPath $previousLocation.Path
}
