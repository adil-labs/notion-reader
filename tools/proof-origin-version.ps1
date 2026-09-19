# Failing-first proof for the origin change (docs/decisions.md 23.27): revert the session schema version and
# list the tests that notice. Run with: pwsh -NoProfile -File tools\proof-origin-version.ps1

$ErrorActionPreference = 'Stop'
Set-Location 'd:\repos\notion-reader'

$file = 'app\src\main\java\com\example\notionreader\domain\session\StoredSession.kt'
$backup = "$file.bak"

Copy-Item $file $backup -Force

function Get-Failures {
    $names = @()
    foreach ($x in Get-ChildItem 'app\build\test-results\testDebugUnitTest\*.xml') {
        $doc = [xml](Get-Content $x.FullName)
        $cls = $doc.testsuite.name.Split('.')[-1]
        foreach ($case in $doc.testsuite.testcase) {
            if ($null -ne $case.failure) { $names += "$cls.$($case.name)" }
        }
    }
    return , @($names | Sort-Object)
}

function Get-Totals {
    $tests = 0; $failed = 0
    foreach ($x in Get-ChildItem 'app\build\test-results\testDebugUnitTest\*.xml') {
        $doc = [xml](Get-Content $x.FullName)
        $tests += [int]$doc.testsuite.tests
        $failed += [int]$doc.testsuite.failures
    }
    return "$tests tests, $failed failures"
}

(Get-Content $file) -replace 'const val SESSION_SCHEMA_VERSION: Int = 2', 'const val SESSION_SCHEMA_VERSION: Int = 1' | Set-Content $file
& .\gradlew.bat :app:testDebugUnitTest 2>&1 | Out-Null

$lines = @("PROOF  session schema version reverted to 1 -> $(Get-Totals)")
foreach ($name in Get-Failures) { $lines += "      $name" }

Copy-Item $backup $file -Force
Remove-Item $backup -Force

& .\gradlew.bat :app:testDebugUnitTest 2>&1 | Out-Null
$lines += "RESTORED -> $(Get-Totals)"

$lines | ForEach-Object { Write-Output $_ }
