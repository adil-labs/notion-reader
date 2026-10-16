#requires -Version 7.0
<# Disables each shipping guard, requires exactly its intended assertion to fail,
   restores original source bytes even on failure, then runs the complete JVM suite. #>
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$cases = @(
    @{
        File = 'app/src/main/java/com/example/notionreader/application/DiscoveryController.kt'
        Old = 'if (latest == null || semanticRevision(latest.document) != revision) return@withLock null'
        New = 'if (false) return@withLock null // proof: source removal guard disabled'
        Class = 'com.example.notionreader.application.DiscoveryControllerTest'
        Test = 'evictionDuringInferenceCannotCommitOrPublishTheRemovedDocument'
    },
    @{
        File = 'app/src/main/java/com/example/notionreader/application/DiscoveryController.kt'
        Old = 'if (generation.get() != epoch || index.isPaused()) return@withLock null'
        New = 'if (false) return@withLock null // proof: reset generation guard disabled'
        Class = 'com.example.notionreader.application.DiscoveryControllerTest'
        Test = 'clearingTheIndexCannotBeUndoneByAnInFlightEmbedding'
    },
    @{
        File = 'app/src/main/java/com/example/notionreader/application/ReaderApp.kt'
        Old = 'discovery?.preparePreview(matches)'
        New = "discovery?.preparePreview(matches)`n        playNote() // proof: destructive playback coupling"
        Class = 'com.example.notionreader.application.DiscoveryPlaybackIsolationTest'
        Test = 'discoveryNavigationQueriesAndPreviewEditsLeavePlayingSessionUntouched'
    }
)
Push-Location $root
try {
    foreach ($case in $cases) {
        $path = Join-Path $root $case.File
        $bytes = [IO.File]::ReadAllBytes($path)
        $text = [Text.Encoding]::UTF8.GetString($bytes)
        if ([regex]::Matches($text, [regex]::Escape($case.Old)).Count -ne 1) { throw "Guard not uniquely found: $($case.Test)" }
        try {
            [IO.File]::WriteAllText($path, $text.Replace($case.Old, $case.New), [Text.UTF8Encoding]::new($false))
            $report = Join-Path $root "app/build/test-results/testDebugUnitTest/TEST-$($case.Class).xml"
            $before = if (Test-Path $report) { (Get-Item $report).LastWriteTimeUtc } else { [DateTime]::MinValue }
            & .\gradlew.bat :app:testDebugUnitTest --tests $case.Class
            if ($LASTEXITCODE -eq 0) { throw "Mutation was not detected: $($case.Test)" }
            if (!(Test-Path $report) -or (Get-Item $report).LastWriteTimeUtc -le $before) { throw 'No fresh assertion report; build failure is not regression evidence' }
            [xml]$xml = Get-Content -LiteralPath $report -Raw
            $failures = @($xml.testsuite.testcase | Where-Object { $_.SelectNodes('failure|error').Count -gt 0 })
            if ($failures.Count -ne 1 -or $failures[0].name -cne $case.Test) {
                throw "Unexpected failing properties: $($failures.name -join ', ')"
            }
            Write-Output "PROVED: only $($case.Test) failed with its shipping guard removed."
        } finally { [IO.File]::WriteAllBytes($path, $bytes) }
    }
    & .\gradlew.bat :app:testDebugUnitTest
    if ($LASTEXITCODE -ne 0) { throw 'Restored production guards did not pass the JVM suite' }
    Write-Output 'All three regression guards failed first for the intended property and the restored suite passed.'
} finally { Pop-Location }
