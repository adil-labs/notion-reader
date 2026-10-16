#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '../..')
$target = Join-Path $repoRoot 'app/src/main/java/com/example/notionreader/application/TopicCoordinator.kt'
$original = [IO.File]::ReadAllBytes($target)
$source = [Text.Encoding]::UTF8.GetString($original)
$proofs = @(
    @{
        Name = 'Later body candidate opportunity'
        Old = 'val candidateRound = if (builtToken == token) latest.groupingPass else 0'
        New = 'val candidateRound = 0'
        Test = 'aSupportedThemeAfterFourPrivatePassagesStillGetsDiscovered'
    },
    @{
        Name = 'Reader chunk relative overlap coordinates'
        File = 'app/src/main/java/com/example/notionreader/domain/discovery/DiscoveredTopics.kt'
        Class = 'com.example.notionreader.domain.discovery.DiscoveredTopicsTest'
        Old = ' && a.readerChunkId == b.readerChunkId'
        New = ''
        Test = 'separateChunksOfOneBlockKeepIndependentThemes'
    },
    @{
        Name = 'Checkpoint candidate budget'
        File = 'app/src/main/java/com/example/notionreader/domain/discovery/DiscoveredTopics.kt'
        Class = 'com.example.notionreader.domain.discovery.DiscoveredTopicsTest'
        Old = 'seeded.putAll(previous!!.candidateSeeds)'
        New = 'seeded.putAll(previous!!.candidateSeeds.filter { false })'
        Test = 'resumingAMidNoteCheckpointPreservesItsCandidateBudget'
    },
    @{
        Name = 'Candidate source rotation'
        Old = '(cursor + 1) % allRecords.size'
        New = '(cursor + minOf(64, allRecords.size)) % allRecords.size'
        Test = 'unchangedCapacityLimitedSourcesStopRescanningAndRotateCandidates'
    },
    @{
        Name = 'Capacity limited work termination'
        Old = 'latest.groupingPass < latest.candidatePassLimit && latest.topics.size < policy.maxAnchors'
        New = 'true'
        Test = 'unchangedCapacityLimitedSourcesStopRescanningAndRotateCandidates'
    },
    @{
        Name = 'Ungrouped connection revision eligibility'
        Old = '(latest.connections + view.snapshot.connections).flatMap { listOf(it.left, it.right) }'
        New = 'emptyList<TopicMembership>()'
        Test = 'changingAnUngroupedConnectionEndpointWithdrawsItsOldPassageWhilePaused'
    },
    @{
        Name = 'Source generation bound to enumeration'
        Old = 'suspend fun build(sources: Map<String, String>, metadataVersion: Int, cacheRevision: Long) {'
        New = "suspend fun build(sources: Map<String, String>, metadataVersion: Int, ignoredCacheRevision: Long) {`n        val cacheRevision = cache.revision.value"
        Test = 'aRemovalObservedAfterEnumerationCannotBeRepublishedFromTheOldSourceMap'
    },
    @{
        Name = 'Header cache exact-byte verification'
        File = 'app/src/main/java/com/example/notionreader/data/local/FileSemanticIndexStore.kt'
        Class = 'com.example.notionreader.data.local.SemanticStoreTest'
        Old = 'headers.entries[headerKey(bytes)]?.takeIf { it.raw.contentEquals(bytes) }'
        New = 'headers.entries[headerKey(bytes)]'
        Test = 'anEqualChecksumCannotHideChangedSourceMetadataInTheReadCache'
    }
)
Push-Location $repoRoot
try {
    foreach ($proof in $proofs) {
        $proofTarget = if ($proof.File) { Join-Path $repoRoot $proof.File } else { $target }
        $proofClass = if ($proof.Class) { $proof.Class } else { 'com.example.notionreader.application.DiscoveryTopicsIntegrationTest' }
        $proofOriginal = [IO.File]::ReadAllBytes($proofTarget)
        $proofSource = [Text.Encoding]::UTF8.GetString($proofOriginal)
        if (($proofSource.Split($proof.Old)).Count -ne 2) { throw "Proof target is missing or ambiguous: $($proof.Name)" }
        try {
        [IO.File]::WriteAllText($proofTarget, $proofSource.Replace($proof.Old, $proof.New), [Text.UTF8Encoding]::new($false))
        & ./gradlew.bat :app:testDebugUnitTest --tests "$proofClass.$($proof.Test)"
        $exitCode = $LASTEXITCODE
        $report = "app/build/test-results/testDebugUnitTest/TEST-$proofClass.xml"
        if (!(Test-Path $report)) { throw 'Proof did not produce a test report' }
        [xml]$xml = Get-Content -Raw $report
        $failures = @($xml.testsuite.testcase | Where-Object { $_.failure })
        if ($exitCode -eq 0 -or [int]$xml.testsuite.tests -ne 1 -or $failures.Count -ne 1 -or $failures[0].name -ne $proof.Test -or $failures[0].failure.type -notmatch 'AssertionError') {
            throw "Proof failed to fail for its claimed assertion: $($proof.Name)"
        }
        Write-Host "PROVEN: $($proof.Name) -> exactly $($proof.Test)"
        } finally { [IO.File]::WriteAllBytes($proofTarget, $proofOriginal) }
    }
} finally {
    [IO.File]::WriteAllBytes($target, $original)
    Pop-Location
}
& (Join-Path $repoRoot 'gradlew.bat') :app:testDebugUnitTest
if ($LASTEXITCODE -ne 0) { throw 'Restored full JVM suite failed' }
