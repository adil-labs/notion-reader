#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$proofs = @(
    @{ File='application/TopicCoordinator.kt'; Class='com.example.notionreader.application.DiscoveryTopicsIntegrationTest'; Old='val keep = selectDiscoveryConnections(pairs.values)'; New='val keep = pairs.values.sortedWith(compareByDescending<NoteConnection> { it.score }.thenBy { it.id }).take(20)'; Test='boundedConnectionPruningRetainsSupportedPairsOutsideTheHub' },
    @{ File='domain/discovery/DiscoveredTopics.kt'; Old='sourceBlockId != sectionId && substantiveDiscoveryEvidence(this)'; New='sourceBlockId != sectionId && excerpt.isNotBlank()'; Test='referencesCannotManufactureAGroupEvenWithIdenticalVectors' },
    @{ File='domain/discovery/DiscoveredTopics.kt'; Old='nearDuplicateDiscoveryPassages(a, b)'; New='false'; Test='nearDuplicatePassagesInDifferentBlocksDoNotCreateDuplicateNeighborhoods' },
    @{ File='domain/discovery/DiscoveredTopics.kt'; Old='previous.policyVersion == policy.version && '; New=''; Test='oldPolicyCheckpointsReprocessEvidenceWithoutReusingCompletionFlags' },
    @{ File='domain/discovery/DiscoveryEvidencePolicy.kt'; Old='(count[a] ?: 0) >= perNote || (count[b] ?: 0) >= perNote'; New='false'; Test='repeatedHubNotesDoNotCrowdOtherSupportedConnectionsOutOfTheCollection' }
)
Push-Location $repo
try {
    foreach ($proof in $proofs) {
        $class = if ($proof.Class) { $proof.Class } else { "com.example.notionreader.domain.discovery.DiscoveryEvidencePolicyTest" }
        $path = Join-Path $repo "app/src/main/java/com/example/notionreader/$($proof.File)"
        $original = [IO.File]::ReadAllBytes($path)
        $source = [Text.Encoding]::UTF8.GetString($original)
        if ($source.Split($proof.Old).Count -ne 2) { throw "Missing or ambiguous guard: $($proof.Old)" }
        try {
            [IO.File]::WriteAllText($path, $source.Replace($proof.Old, $proof.New), [Text.UTF8Encoding]::new($false))
            & ./gradlew.bat :app:testDebugUnitTest --tests "$class.$($proof.Test)"
            $code = $LASTEXITCODE
            [xml]$xml = Get-Content -Raw "app/build/test-results/testDebugUnitTest/TEST-$class.xml"
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
