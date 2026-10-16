#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$proofs = @(
    @{ File='application/TopicCoordinator.kt'; Old='minOf(it, invalidationAcknowledgementLimit())'; New='it'; Test='aRemovalArrivingDuringRefreshCannotBeAcknowledgedBeforeRecentsPersistIt' },
    @{ File='application/RecentNotesController.kt'; Old='mutableMessage.value = if (persisted) null else "Recently opened Notes could not be cleared from storage. Retry clearing history."'; New='mutableMessage.value = null'; Test='failedClearReportsFailureAndCanBeRetriedWithoutClaimingDurableDeletion' },
    @{ File='application/RecentNotesController.kt'; Old='if (expectedGeneration != generation) return@withLock'; New='if (false) return@withLock'; Test='clearingHistoryRejectsAnOpenThatStartedBeforeTheClear' },
    @{ File='application/RecentNotesController.kt'; Old='canonicalSemanticId(it.pageId) == key'; New='false'; Test='opensAreCanonicalDeduplicatedBoundedAndResolvedWithoutPersistedContent' },
    @{ File='application/RecentNotesController.kt'; Old='}).take(20)'; New='}).take(21)'; Test='opensAreCanonicalDeduplicatedBoundedAndResolvedWithoutPersistedContent' },
    @{ File='application/RecentNotesController.kt'; Old='canonicalSemanticId(it.pageId) in removed'; New='false'; Test='evictionHidesContentButPermanentLossAfterEvictionRemovesTheReference' },
    @{ File='application/ReaderApp.kt'; Old='if (navigator.state.value.secondary.lastOrNull() === openedStop)'; New='if (true)'; Test='leavingTheNoteBeforeItsLoadFinishesDoesNotRecordAnOpen' },
    @{ File='application/NoteViewer.kt'; Old='return result.isSuccess'; New='return true'; Test='anUnsuccessfulOpenCannotRecordThePreviouslyDisplayedNote' }
)
Push-Location $repo
try {
    foreach ($proof in $proofs) {
        $path = Join-Path $repo "app/src/main/java/com/example/notionreader/$($proof.File)"
        $original = [IO.File]::ReadAllBytes($path)
        $source = [Text.Encoding]::UTF8.GetString($original)
        if ($source.Split($proof.Old).Count -ne 2) { throw "Missing or ambiguous guard: $($proof.Old)" }
        try {
            [IO.File]::WriteAllText($path, $source.Replace($proof.Old, $proof.New), [Text.UTF8Encoding]::new($false))
            & ./gradlew.bat :app:testDebugUnitTest --tests "com.example.notionreader.application.RecentNotesTest.$($proof.Test)"
            $code = $LASTEXITCODE
            [xml]$xml = Get-Content -Raw app/build/test-results/testDebugUnitTest/TEST-com.example.notionreader.application.RecentNotesTest.xml
            $cases = @($xml.SelectNodes('//testcase[failure]'))
            if ($code -eq 0 -or [int]$xml.testsuite.tests -ne 1 -or $cases.Count -ne 1 -or
                $cases[0].name -ne $proof.Test -or $cases[0].SelectSingleNode('failure').type -notmatch 'AssertionError') {
                throw "The intended regression assertion did not fail: $($proof.Test)"
            }
            Write-Host "PROVEN: $($proof.File) -> $($proof.Test)"
        } finally { [IO.File]::WriteAllBytes($path, $original) }
    }
    & ./gradlew.bat :app:testDebugUnitTest
    if ($LASTEXITCODE -ne 0) { throw 'Restored full suite failed' }
} finally { Pop-Location }
