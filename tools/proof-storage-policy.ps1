#requires -Version 7.0
<# Prove shipping storage guards are observed by their regression properties. #>
param()
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$output = Join-Path $repo 'build/storage-policy-proof'
New-Item -ItemType Directory -Force $output | Out-Null
$checks = @(
    @{ Name='hundred-note-default'; Path='app/src/main/java/com/example/notionreader/data/local/FileDocumentCache.kt'; Old='private val maxBytes: Long = com.example.notionreader.domain.document.StorageDefaults.NOTE_BYTES'; New='private val maxBytes: Long = 50L * 1024 * 1024'; Test='com.example.notionreader.data.local.StorageSizingTest.hundredOneMiBNotesFitTheDefaultCache' },
    @{ Name='protected-write'; Path='app/src/main/java/com/example/notionreader/data/local/FileDocumentCache.kt'; Old='enforceBudgetLocked(protectedPageIds())'; New='enforceBudgetLocked(emptySet())'; ExtraOld='preservePageIds + protectedPageIds()'; ExtraNew='preservePageIds'; Test='com.example.notionreader.data.local.StorageSizingTest.automaticPutPruningProtectsTheActiveQueue' },
    @{ Name='storage-is-not-user-pause'; Path='app/src/main/java/com/example/notionreader/application/DiscoveryIndexing.kt'; Old='// Capacity waiting never persists explicit user pause.'; New='pause()'; Test='com.example.notionreader.application.DiscoveryControllerTest.storageWaitingDoesNotPersistAnExplicitUserPause' },
    @{ Name='reading-without-cache-space'; Path='app/src/main/java/com/example/notionreader/data/document/CachedDocumentRepository.kt'; Old='if (purpose == DocumentLoadPurpose.PREFETCH) throw refused'; New='throw refused'; FailureType='com.example.notionreader.domain.document.StorageRefused'; Test='com.example.notionreader.data.document.CachedDocumentRepositoryTest.storagePressureDoesNotTurnSuccessfulReadingIntoALoadFailure' },
    @{ Name='physical-reserve'; Path='app/src/main/java/com/example/notionreader/application/StorageBudgetCoordinator.kt'; Old='additionalPeakBytes > space.first -> StorageFailure.DEVICE_SPACE'; New='false -> StorageFailure.DEVICE_SPACE'; Test='com.example.notionreader.application.StorageBudgetCoordinatorTest.unknownAndLowSpaceNeverInvokeTheWriter' },
    @{ Name='custom-limit'; Path='app/src/main/java/com/example/notionreader/application/StorageBudgetCoordinator.kt'; Old='category == StorageCategory.DISCOVERY && !prefs.discoveryStorageAutomatic'; New='false && !prefs.discoveryStorageAutomatic'; Test='com.example.notionreader.application.StorageBudgetCoordinatorTest.customLimitCountsOldGenerationWhileReplacementIsStaged' },
    @{ Name='refused-cache-is-read-only'; Path='app/src/main/java/com/example/notionreader/data/local/FileDocumentCache.kt'; Old='admissionRefused = mutations.value == initialGeneration'; New='admissionRefused = false'; Test='com.example.notionreader.data.local.StorageSizingTest.refusingACacheWriteDoesNotMutateItsGenerationOrJournal' },
    @{ Name='atomic-index-admission'; Path='app/src/main/java/com/example/notionreader/data/local/FileSemanticIndexStore.kt'; Old='writeGate.write(com.example.notionreader.domain.document.StorageCategory.DISCOVERY, size)'; New='com.example.notionreader.domain.document.StorageWriteGate.Unrestricted.write(com.example.notionreader.domain.document.StorageCategory.DISCOVERY, size)'; Test='com.example.notionreader.data.local.StorageSizingTest.refusedWritesLeaveThePreviousSemanticGenerationAvailable' },
    @{ Name='record-memory-bound'; Path='app/src/main/java/com/example/notionreader/data/local/FileSemanticIndexStore.kt'; Old='if (metadata.size > MAX_HEADER_BYTES || size > MAX_RECORD_BYTES)'; New='if (false)'; Test='com.example.notionreader.data.local.StorageSizingTest.automaticGrowthDoesNotRemoveThePerRecordMemoryBound' },
    @{ Name='release-played-notes'; Path='app/src/main/java/com/example/notionreader/application/DocumentCacheCoordinator.kt'; Old='pageIds.drop(currentIndex.coerceAtLeast(0)).toSet()'; New='pageIds.toSet()'; Test='com.example.notionreader.application.StorageBudgetCoordinatorTest.playedNotesStopBeingProtectedAsTheQueueAdvances' }
)
Push-Location $repo
try {
    foreach ($check in $checks) {
        $path = Join-Path $repo $check.Path
        $original = [IO.File]::ReadAllBytes($path)
        $source = [IO.File]::ReadAllText($path)
        if (!$source.Contains($check.Old)) { throw "Guard not found: $($check.Name)" }
        try {
            $changed = $source.Replace($check.Old, $check.New)
            if ($check.ExtraOld) {
                if (!$changed.Contains($check.ExtraOld)) { throw 'Protection guard not found' }
                $changed = $changed.Replace($check.ExtraOld, $check.ExtraNew)
            }
            [IO.File]::WriteAllText($path, $changed)
            & .\gradlew.bat :app:testDebugUnitTest --tests $check.Test *> (Join-Path $output "$($check.Name).log")
            if ($LASTEXITCODE -eq 0) { throw "Regression guard was not observed: $($check.Name)" }
            $class = $check.Test.Substring(0, $check.Test.LastIndexOf('.'))
            $method = $check.Test.Substring($check.Test.LastIndexOf('.') + 1)
            [xml]$report = Get-Content -LiteralPath "app/build/test-results/testDebugUnitTest/TEST-$class.xml" -Raw
            $cases = @($report.testsuite.testcase)
            $expectedType = if ($check.FailureType) { $check.FailureType } else { 'java.lang.AssertionError' }
            if ($cases.Count -ne 1 -or $cases[0].name -cne $method -or !$cases[0].failure -or
                $cases[0].failure.type -cne $expectedType) { throw "Wrong failure evidence for $($check.Name)" }
            Write-Output "PROVED $($check.Name): $method failed with $expectedType"
        } finally { [IO.File]::WriteAllBytes($path, $original) }
    }
    & .\gradlew.bat :app:testDebugUnitTest *> (Join-Path $output 'restored-suite.log')
    if ($LASTEXITCODE -ne 0) { throw 'Restored JVM suite failed' }
    Write-Output 'PASSED: all guards observed; restored full JVM suite passed.'
} finally { Pop-Location }
