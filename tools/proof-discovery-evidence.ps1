#requires -Version 7.0
<#
.SYNOPSIS
Proves the shipping strongest-passage and relationship-removal guards can fail.
.DESCRIPTION
Temporarily mutates exactly one guard, runs its entire test class, checks the
precise failing-test set, then restores original source bytes in a finally block.
Runs the full JVM suite after restoring both guards. No fixture is changed.
#>
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$cases = @(
    @{
        Path = 'app/src/main/java/com/example/notionreader/domain/discovery/TopicDiscoveryEngine.kt'
        Pattern = 'old == null \|\| score > old.score \|\| score == old.score && e.embedded.passage.id < old.passage.id'
        Replacement = 'old == null'
        Matches = 2
        Class = 'com.example.notionreader.domain.discovery.TopicDiscoveryEngineTest'
        Test = 'membershipKeepsTheStrongestBodyPassageAcrossSectionsAndIgnoresHeadingOnlyEvidence'
    },
    @{
        Path = 'app/src/main/java/com/example/notionreader/domain/discovery/TopicEvidence.kt'
        Pattern = 'subjectRelationships = validatedSubjectRelationships\(publishableDiscoveryTopics\(topics\),\s*snapshot.subjectRelationships.filter \{ available\(it.evidence.pageId\) \}, revisions\)'
        Replacement = 'subjectRelationships = snapshot.subjectRelationships'
        Matches = 1
        Class = 'com.example.notionreader.domain.discovery.SubjectHierarchyTest'
        Test = 'completedDiscoveryPublishesRelationshipsAndSourceRemovalWithdrawsThem'
    }
)
Push-Location $repoRoot
try {
    foreach ($case in $cases) {
        $path = Join-Path $repoRoot $case.Path
        $original = [IO.File]::ReadAllBytes($path)
        $text = [Text.Encoding]::UTF8.GetString($original)
        $pattern = [regex]::new($case.Pattern)
        if ($pattern.Matches($text).Count -ne $case.Matches) { throw "Guard shape changed: $($case.Path)" }
        $reportPath = Join-Path $repoRoot "app/build/test-results/testDebugUnitTest/TEST-$($case.Class).xml"
        if (Test-Path -LiteralPath $reportPath) { Remove-Item -LiteralPath $reportPath }
        try {
            [IO.File]::WriteAllText($path, $pattern.Replace($text, $case.Replacement), [Text.UTF8Encoding]::new($false))
            & .\gradlew.bat :app:testDebugUnitTest --rerun --tests $case.Class
            if ($LASTEXITCODE -eq 0) { throw "Mutation escaped observation: $($case.Test)" }
            if (!(Test-Path -LiteralPath $reportPath)) { throw 'Mutation did not produce a fresh test report' }
            [xml]$report = Get-Content -LiteralPath $reportPath -Raw
            $failed = @($report.testsuite.testcase | Where-Object { $_.failure -or $_.error } | ForEach-Object name)
            if ($failed.Count -ne 1 -or $failed[0] -cne $case.Test) {
                throw "Unexpected failing tests for $($case.Path): $($failed -join ', ')"
            }
            Write-Host "PROVED: disabling $($case.Path) failed only $($case.Test)"
        } finally {
            [IO.File]::WriteAllBytes($path, $original)
        }
    }
    & .\gradlew.bat :app:testDebugUnitTest --rerun
    if ($LASTEXITCODE -ne 0) { throw 'Restored JVM suite is red' }
    Write-Host 'PROVED: both shipping guards restored; full JVM suite passed.'
} finally { Pop-Location }
