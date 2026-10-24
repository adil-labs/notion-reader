#requires -Version 7.0
<# Mutation proofs against shipping request lifecycle guards. Restores exact original bytes. #>
param([string]$Guard = '')
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$output = Join-Path $repo 'build/discovery-search-proof'
New-Item -ItemType Directory -Force $output | Out-Null
$checks = @(
    @{ Name='new-query-results'; Path='app/src/main/java/com/example/notionreader/application/DiscoverySearch.kt'; Old='filters = filters, results = emptyList(), searching = true'; New='filters = filters, searching = true'; Test='aFailedNewSearchCannotShowResultsFromThePreviousQuery' },
    @{ Name='recommendation-reset'; Path='app/src/main/java/com/example/notionreader/application/DiscoveryInterests.kt'; Old='if (epoch == runtime.generation.get() && interestsEpoch == interestGeneration.get() && sourceRevision == cache.revision.value)'; New='if (true)'; Test='clearingDiscoveryRejectsALateRecommendationFailure' },
    @{ Name='search-error-ownership'; Path='app/src/main/java/com/example/notionreader/application/DiscoveryIndexing.kt'; Old='it.copy(message = it.searchFailure)'; New='it.copy(message = null)'; Test='backgroundIndexCheckingCannotEraseASearchFailure' },
    @{ Name='search-completion-ownership'; Path='app/src/main/java/com/example/notionreader/application/DiscoverySearch.kt'; Old='searchFailure = null, searchCompleted = false'; New='searchFailure = null'; Test='cancellingANewSearchCannotClaimThatItsEmptyResultsAreComplete' },
    @{ Name='search-reset-message'; Path='app/src/main/java/com/example/notionreader/application/DiscoveryController.kt'; Old='searchCompleted = false, message = null, bytes = index.bytes()'; New='searchCompleted = false, bytes = index.bytes()'; Test='clearingTheIndexRemovesThePreviousSearchFailure' },
    @{ Name='index-reset-failure'; Path='app/src/main/java/com/example/notionreader/application/DiscoveryIndexing.kt'; Old='if (runtime.generation.get() != epoch) it'; New='if (false) it'; Test='clearingDiscoveryRejectsALateIndexReadFailure' },
    @{ Name='continuity-limit-message'; Path='app/src/main/java/com/example/notionreader/application/TopicCoordinator.kt'; Old='if (result.validationReason == TopicValidationReason.ContinuityBound)'; New='if (false)'; Test='continuityMemoryLimitReportsIncompleteCoverageInsteadOfInvalidData'; Class='com.example.notionreader.application.DiscoveryTopicsIntegrationTest' }
)
$class = 'com.example.notionreader.application.DiscoveryControllerTest'
Push-Location $repo
try {
    if ($Guard) {
        $checks = @($checks | Where-Object { $_.Name -ceq $Guard })
        if ($checks.Count -ne 1) { throw 'Unknown guard selection' }
    }
    foreach ($check in $checks) {
        $testClass = if ($check.Class) { $check.Class } else { $class }
        $path = Join-Path $repo $check.Path
        $original = [IO.File]::ReadAllBytes($path)
        $source = [IO.File]::ReadAllText($path)
        if (!$source.Contains($check.Old)) { throw "Shipping guard not found: $($check.Name)" }
        try {
            [IO.File]::WriteAllText($path, $source.Replace($check.Old, $check.New))
            & .\gradlew.bat :app:testDebugUnitTest --tests "$testClass.$($check.Test)" *> (Join-Path $output "$($check.Name).log")
            if ($LASTEXITCODE -eq 0) { throw "Guard was not observed: $($check.Name)" }
            [xml]$report = Get-Content -LiteralPath "app/build/test-results/testDebugUnitTest/TEST-$testClass.xml" -Raw
            $cases = @($report.testsuite.testcase)
            if ($cases.Count -ne 1 -or $cases[0].name -cne $check.Test -or !$cases[0].failure -or
                $cases[0].failure.type -cne 'java.lang.AssertionError') { throw "Wrong failure evidence for $($check.Name)" }
            Write-Output "PROVED $($check.Name): $($check.Test)"
        } finally { [IO.File]::WriteAllBytes($path, $original) }
    }
    & .\gradlew.bat :app:testDebugUnitTest *> (Join-Path $output 'restored-suite.log')
    if ($LASTEXITCODE -ne 0) { throw 'Restored full JVM suite failed' }
    Write-Output "PASSED: $($checks.Count) selected guard(s) observed; restored full JVM suite passed."
} finally { Pop-Location }
