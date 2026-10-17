#requires -Version 7.0
<# Disables only the discovery audit guards, checks the named failures, then restores production. #>
param([ValidateSet('Guards', 'Reconciliation', 'SearchOrder')][string]$Case = 'Guards')
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$controller = Join-Path $repo 'app/src/main/java/com/example/notionreader/application/DiscoveryController.kt'
$coordinator = Join-Path $repo 'app/src/main/java/com/example/notionreader/application/TopicCoordinator.kt'
$originalController = [IO.File]::ReadAllText($controller)
$originalCoordinator = [IO.File]::ReadAllText($coordinator)
function Replace-Required([string]$text, [string]$before, [string]$after) {
    $text = $text.Replace("`r`n", "`n")
    $before = $before.Replace("`r`n", "`n")
    if (-not $text.Contains($before)) { throw "Guard not found: $before" }
    return $text.Replace($before, $after)
}
try {
    if ($Case -eq 'Guards') {
    $changed = Replace-Required $originalController 'withContext(workDispatcher) { searchInBackground(text, filters, request, epoch, started) }' 'searchInBackground(text, filters, request, epoch, started)'
    $changed = Replace-Required $changed @'
fun currentEvidence(match: SemanticMatch): Boolean = currentRecords[match.pageId]?.let { record ->
                record.metadata == match.metadata && record.passages.any { it.passage == match.passage }
            } == true
'@ 'fun currentEvidence(match: SemanticMatch): Boolean = match.pageId in ids'
    [IO.File]::WriteAllText($controller, $changed)
    $changed = Replace-Required $originalCoordinator 'if (!mayWork() || shouldYield() || startEpoch != epoch || cache.revision.value != cacheRevision) throw WorkPaused()' '// Scheduling guard disabled for failing-first proof.'
    $changed = Replace-Required $changed 'fun capped(snapshot: TopicSnapshot) = snapshot.copy(connections = selectDiscoveryConnections(snapshot.connections))' 'fun capped(snapshot: TopicSnapshot) = snapshot'
    $changed = Replace-Required $changed 'val connected = selectDiscoveryConnections((explicit + semantic).distinctBy { it.id })' 'val connected = (explicit + semantic).distinctBy { it.id }'
    [IO.File]::WriteAllText($coordinator, $changed)
    $expected = @('searchInferenceDoesNotRunOnTheCallingUiThread', 'explicitReferencesRespectTheSharedConnectionExposureLimit', 'formationChecksPlaybackEligibilityInsideItsCpuWork', 'formationYieldsToAWaitingForegroundQueryInsideItsCpuWork', 'replacingCachedTextWithdrawsOldSearchAndPreviewEvidence')
    } elseif ($Case -eq 'Reconciliation') {
        $changed = Replace-Required $originalController 'results = if (s.results === displayed.results) s.results.filter(::currentEvidence) else s.results' 'results = s.results.filter(::currentEvidence)'
        [IO.File]::WriteAllText($controller, $changed)
        $expected = @('reconciliationPreservesAConcurrentSearchAndPreview')
    } else {
        $changed = $originalController.Replace("`r`n", "`n")
        $start = $changed.IndexOf('    suspend fun search(text:')
        $end = $changed.IndexOf('    private suspend fun searchInBackground', $start)
        if ($start -lt 0 -or $end -le $start) { throw 'Search ownership wrapper not found' }
        $replacement = @'
    suspend fun search(text: String, filters: BrowseQuery = state.value.filters) {
        val epoch = generation.get()
        val started = System.nanoTime()
        var request: Long? = null
        mutable.update { it.copy(query = text, filters = filters, searching = true, message = null) }
        try { withContext(workDispatcher) {
            val dispatchedRequest = queryGeneration.incrementAndGet()
            request = dispatchedRequest
            searchInBackground(text, filters, dispatchedRequest, epoch, started)
        } } finally { mutable.update {
            if (request == queryGeneration.get() || request == null && it.query == text) it.copy(searching = false) else it
        } }
    }

'@
        $changed = $changed.Substring(0, $start) + $replacement + $changed.Substring($end)
        $changed = Replace-Required $changed 'if (request != queryGeneration.get() || epoch != generation.get()) return' @'
if (request != queryGeneration.get() || epoch != generation.get()) return
        mutable.update { it.copy(query = text, filters = filters, searching = true, message = null) }
'@
        [IO.File]::WriteAllText($controller, $changed)
        $expected = @('theNewestSearchKeepsOwnershipWhenBackgroundDispatchRunsInReverseOrder')
    }
    Push-Location $repo
    try {
        $expectedTests = 0
        foreach ($name in @('DiscoveryControllerTest', 'DiscoveryTopicsIntegrationTest')) {
            $source = [IO.File]::ReadAllText((Join-Path $repo "app/src/test/java/com/example/notionreader/application/$name.kt"))
            $expectedTests += [regex]::Matches($source, '@Test\b').Count
            $reportPath = Join-Path $repo "app/build/test-results/testDebugUnitTest/TEST-com.example.notionreader.application.$name.xml"
            # A compilation failure must never reuse an earlier red test report as proof.
            if (Test-Path -LiteralPath $reportPath) { Remove-Item -LiteralPath $reportPath }
        }
        & .\gradlew.bat :app:testDebugUnitTest --tests '*DiscoveryControllerTest' --tests '*DiscoveryTopicsIntegrationTest'
        if ($LASTEXITCODE -eq 0) { throw 'Guards were disabled but no regression was detected' }
        $failures = @()
        $observedTests = 0
        foreach ($name in @('DiscoveryControllerTest', 'DiscoveryTopicsIntegrationTest')) {
            [xml]$report = Get-Content "app/build/test-results/testDebugUnitTest/TEST-com.example.notionreader.application.$name.xml" -Raw
            if ([int]$report.testsuite.errors -ne 0) { throw 'Unexpected test execution errors' }
            if ([int]$report.testsuite.skipped -ne 0) { throw 'Skipped tests cannot establish the regression proof' }
            $observedTests += [int]$report.testsuite.tests
            $failures += @($report.testsuite.testcase | Where-Object { $_.failure } | ForEach-Object name)
        }
        if ($observedTests -ne $expectedTests) { throw "Incomplete proof: $observedTests tests ran; sources contain $expectedTests" }
        if (@(Compare-Object ($expected | Sort-Object) ($failures | Sort-Object)).Count -ne 0) {
            throw "Unexpected failing set: $($failures -join ', ')"
        }
        Write-Output "PROVED: exactly $($failures.Count) intended regressions failed; other tests in both classes passed."
    } finally { Pop-Location }
} finally {
    [IO.File]::WriteAllText($controller, $originalController)
    [IO.File]::WriteAllText($coordinator, $originalCoordinator)
}
