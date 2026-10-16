#requires -Version 7.0
param([string]$Device = '')
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$target = Join-Path $repoRoot 'app/src/main/java/com/example/notionreader/ui/ReaderShell.kt'
$original = [IO.File]::ReadAllBytes($target)
$source = [Text.Encoding]::UTF8.GetString($original)
$guard = '                        onExplore = app::openExploreCatalog,'
if ($source.Split($guard).Count -ne 2) { throw 'Explore wiring proof target is missing or ambiguous.' }
Push-Location $repoRoot
try {
    try {
        [IO.File]::WriteAllText($target, $source.Replace($guard, ''), [Text.UTF8Encoding]::new($false))
        & pwsh -NoProfile -File tools/run-accessibility-tests.ps1 -Device $Device -Class com.example.notionreader.ui.DiscoveryNavigationAccessibilityTest
        if ($LASTEXITCODE -eq 0) { throw 'Removing Explore wiring did not fail the navigation check.' }
        $reports = @(Get-ChildItem app/build/outputs/androidTest-results/connected -Recurse -Filter 'TEST-*.xml')
        $suites = @($reports | ForEach-Object { ([xml][IO.File]::ReadAllText($_.FullName)).SelectNodes('//testsuite') } |
            Where-Object { $_.name -eq 'com.example.notionreader.ui.DiscoveryNavigationAccessibilityTest' })
        if ($suites.Count -ne 1 -or [int]$suites[0].tests -ne 3 -or [int]$suites[0].failures -ne 1) {
            throw 'Explore proof failed to observe exactly one failure in the complete three-test class.'
        }
        $failed = @($suites[0].testcase | Where-Object { $null -ne $_.failure })
        if ($failed.Count -ne 1 -or $failed[0].name -ne 'exploreLivesInBrowseAndBackRestoresItsControl') {
            throw 'An unrelated failure cannot prove Explore wiring.'
        }
        Write-Host 'PROVEN: removing Browse Explore wiring fails exactly its navigation test.'
    } finally { [IO.File]::WriteAllBytes($target, $original) }
    & pwsh -NoProfile -File tools/run-accessibility-tests.ps1 -Device $Device -Class com.example.notionreader.ui.DiscoveryNavigationAccessibilityTest
    if ($LASTEXITCODE -ne 0) { throw 'Restored Explore navigation checks failed.' }
} finally {
    [IO.File]::WriteAllBytes($target, $original)
    Pop-Location
}
