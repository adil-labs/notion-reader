# Failing-first proofs for the Saved Queue model (docs/decisions.md 23.26).
#
# Each guard is removed on its own, the suite is run, and the failing tests are listed by name. The file is
# restored from a backup rather than re-edited, so the restore is exact. Run with:
#   pwsh -NoProfile -File tools\proof-saved-queue-model.ps1

$ErrorActionPreference = 'Stop'
Set-Location 'd:\repos\notion-reader'

$edits = 'app\src\main\java\com\example\notionreader\domain\queue\SavedQueueEdits.kt'
$double = 'app\src\test\java\com\example\notionreader\domain\queue\InMemorySavedQueueStore.kt'
foreach ($f in @($edits, $double)) { Copy-Item $f "$f.bak" -Force }

function Get-Failures {
    $names = @()
    foreach ($f in Get-ChildItem 'app\build\test-results\testDebugUnitTest\*.xml') {
        $doc = [xml](Get-Content $f.FullName)
        $cls = $doc.testsuite.name.Split('.')[-1]
        foreach ($case in $doc.testsuite.testcase) {
            if ($null -ne $case.failure) { $names += "$cls.$($case.name)" }
        }
    }
    return , @($names | Sort-Object)
}

function Get-Totals {
    $tests = 0; $failed = 0
    foreach ($f in Get-ChildItem 'app\build\test-results\testDebugUnitTest\*.xml') {
        $doc = [xml](Get-Content $f.FullName)
        $tests += [int]$doc.testsuite.tests
        $failed += [int]$doc.testsuite.failures
    }
    return "$tests tests, $failed failures"
}

$report = @()

function Invoke-Proof([string] $label) {
    & .\gradlew.bat :app:testDebugUnitTest 2>&1 | Out-Null
    $lines = @("$label -> $(Get-Totals)")
    foreach ($name in Get-Failures) { $lines += "      $name" }
    return , $lines
}

# --- Proof 1: the no-op decision -------------------------------------------------------------------------
(Get-Content $edits) -replace 'if \(candidate\.queues == to\) \{', 'if (false) {' | Set-Content $edits
$report += Invoke-Proof 'PROOF 1  no-op decision disabled (every operation reports a change)'
Copy-Item "$edits.bak" $edits -Force

# --- Proof 2: de-duplication by identity -----------------------------------------------------------------
(Get-Content $edits) -replace 'if \(present\.add\(note\.pageId\)\) appended \+= note else alreadyPresent\+\+', 'appended += note' | Set-Content $edits
$report += Invoke-Proof 'PROOF 2  add no longer de-duplicates by identity'
Copy-Item "$edits.bak" $edits -Force

# --- Proof 3: the transaction guard, i.e. the defect itself ----------------------------------------------
(Get-Content $double) -replace 'if \(failReads\) return UNAVAILABLE', '' | Set-Content $double
(Get-Content $double) -replace 'val next = decide\(stored\)', 'val next = decide(if (failReads) emptyList() else stored)' | Set-Content $double
$report += Invoke-Proof 'PROOF 3  a failed read is treated as an empty store again (the defect)'
Copy-Item "$double.bak" $double -Force

# --- Restored --------------------------------------------------------------------------------------------
$report += Invoke-Proof 'RESTORED'
foreach ($f in @($edits, $double)) { Remove-Item "$f.bak" -Force }

$report | ForEach-Object { Write-Output $_ }
