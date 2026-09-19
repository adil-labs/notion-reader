# Failing-first proofs for the Saved Queue surface slice (docs/decisions.md 23.28).
#
# Each guard is removed on its own, the JVM suite is run, and the failing tests are listed by name. Files are
# restored from backups rather than re-edited, so every restore is exact.
#
# Run with: pwsh -NoProfile -File tools\proof-saved-queue-surface.ps1

$ErrorActionPreference = 'Stop'
Set-Location 'd:\repos\notion-reader'

$app = 'app\src\main\java\com\example\notionreader\application\ReaderApp.kt'
$card = 'app\src\main\java\com\example\notionreader\application\SavedQueueCard.kt'
foreach ($f in @($app, $card)) { Copy-Item $f "$f.bak" -Force }

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

function Invoke-Proof([string] $label) {
    & .\gradlew.bat :app:testDebugUnitTest 2>&1 | Out-Null
    $lines = @("$label -> $(Get-Totals)")
    foreach ($name in Get-Failures) { $lines += "      $name" }
    return , $lines
}

$report = @()

# --- Proof 1: the publishing rule, i.e. the defect that blanks a visible list ---------------------------------
(Get-Content $app) -replace 'if \(outcome\.changed\) _savedQueues\.value = outcome\.queues', '_savedQueues.value = outcome.queues' | Set-Content $app
$report += Invoke-Proof 'PROOF 1  an outcome is published unconditionally (an unreadable store blanks the list)'
Copy-Item "$app.bak" $app -Force

# --- Proof 2: Play offered on an empty queue ------------------------------------------------------------------
(Get-Content $card) -replace 'val isPlayable: Boolean get\(\) = members\.isNotEmpty\(\)', 'val isPlayable: Boolean get() = true' | Set-Content $card
$report += Invoke-Proof 'PROOF 2  Play is offered on an empty queue'
Copy-Item "$card.bak" $card -Force

# --- Proof 3: the singular member count ----------------------------------------------------------------------
(Get-Content $card) -replace 'get\(\) = if \(memberCount == 1\) "1 note" else "\$memberCount notes"', 'get() = "$$memberCount notes"' | Set-Content $card
$report += Invoke-Proof 'PROOF 3  a one-member queue is spoken as "1 notes"'
Copy-Item "$card.bak" $card -Force

# --- Proof 4: the most-recently-edited-first ordering ---------------------------------------------------------
(Get-Content $card) -replace 'sortedByDescending \{ it\.lastEditedAt \}\.map \{ it\.toCard\(\) \}', 'map { it.toCard() }' | Set-Content $card
$report += Invoke-Proof 'PROOF 4  queues are listed in store order rather than most recently edited first'
Copy-Item "$card.bak" $card -Force

# --- Restored -------------------------------------------------------------------------------------------------
$report += Invoke-Proof 'RESTORED'
foreach ($f in @($app, $card)) { Remove-Item "$f.bak" -Force }

$report | ForEach-Object { Write-Output $_ }
