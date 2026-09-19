# Failing-first proofs for edit mode (docs/decisions.md 23.31).
#
# Each guard is removed on its own, the JVM suite is run, and the failing tests are listed by name. Files are
# restored from backups rather than re-edited, so every restore is exact.
#
# ## Two bugs this script had on its first attempt, and the second is the dangerous one
#
#   * `(Get-Content $file)` returns an **array of lines**, and `-replace` applies to each element
#     independently, so a pattern spanning more than one line can NEVER match. The first version's edits were
#     multi-line, so one of them silently did nothing.
#   * The consequence was worse than a no-op. That proof reported "0 failures", which reads exactly like a
#     finding - "this guard is not load-bearing" - when the truth was "the edit was never applied". A proof
#     that silently fails to apply its edit produces a *false negative* that looks like valuable information.
#     Every replacement below is therefore checked against the file's text, and the script throws if the text
#     did not change.
#
# Run with: pwsh -NoProfile -File tools\proof-edit-mode.ps1

$ErrorActionPreference = 'Stop'
Set-Location 'd:\repos\notion-reader'

$edits = 'app\src\main\java\com\example\notionreader\domain\queue\SavedQueueEdits.kt'
$app = 'app\src\main\java\com\example\notionreader\application\ReaderApp.kt'
foreach ($f in @($edits, $app)) { Copy-Item $f "$f.bak" -Force }

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

$script:unapplied = 0

<#
    Applies one replacement to a whole file and **asserts that the text changed**.

    `-Raw` is the point: without it `-replace` runs per line and a multi-line pattern cannot match. The
    assertion is the other half - a proof whose edit did not land reports zero failures, and zero failures
    reads as "this guard is not needed" rather than as "this proof did nothing".
#>
function Set-Broken([string] $file, [string] $pattern, [string] $replacement, [string] $what) {
    $before = Get-Content $file -Raw
    $after = [regex]::Replace($before, $pattern, $replacement)
    if ($after -ceq $before) {
        $script:unapplied++
        Write-Output "  !! EDIT DID NOT APPLY: $what"
        Write-Output "     pattern: $pattern"
        return
    }
    # `-NoNewline` so the file's own line endings survive; a trailing newline added here would be a second,
    # unintended change to the file under test.
    Set-Content -Path $file -Value $after -NoNewline
}

function Invoke-Proof([string] $label) {
    & .\gradlew.bat :app:testDebugUnitTest 2>&1 | Out-Null
    $lines = @("$label -> $(Get-Totals)")
    foreach ($name in Get-Failures) { $lines += "      $name" }
    return , $lines
}

$report = @()

# --- Proof 1: the derived boundary rule ----------------------------------------------------------------------
# Availability becomes "in range", which is what restating the boundary in the screen would eventually drift
# into. The model's availability and the operation's no-op rule must be one rule, not two that agree today.
Set-Broken $edits '(?s)fun isAvailableFor\(notes: List<QueuedNote>, index: Int\): Boolean =\s*\n\s*index in notes\.indices && applyTo\(notes, index\) != notes' 'fun isAvailableFor(notes: List<QueuedNote>, index: Int): Boolean = index in notes.indices' 'isAvailableFor -> in-range only'
$report += Invoke-Proof 'PROOF 1  isAvailableFor reports every in-range move as available'
Copy-Item "$edits.bak" $edits -Force

# --- Proof 2: the silence rule -------------------------------------------------------------------------------
# A no-op move is announced anyway, which is the false statement about the listener's queue that the message
# would make about a reorder that did not happen.
Set-Broken $app 'if \(outcome\.changed && moved\?\.member != null\)' 'if (moved?.member != null)' 'announce unconditionally'
$report += Invoke-Proof 'PROOF 2  a boundary move is announced as though it had moved the member'
Copy-Item "$app.bak" $app -Force

# --- Proof 3: the position in the announcement ---------------------------------------------------------------
# The message reports a position that is not the one the member reached, which is a lie no ordering assertion
# could catch - the reorder itself is still correct.
Set-Broken $app 'to position \$\{moved\.position\} of' 'to position 0 of' 'announce the wrong position'
$report += Invoke-Proof 'PROOF 3  the announced position is not the one the member reached'
Copy-Item "$app.bak" $app -Force

# There is deliberately **no** proof for `removeSavedQueueMember`'s guard, and the reason is a finding rather
# than an omission. One was written: it broke `changed && removed?.member != null` to `removed?.member != null`,
# it applied cleanly, and it produced **zero failures** - correctly, because the guard is redundant there.
# `QueueEdit.Remove` yields a null member for a Note that is not a member, so the member null-check already
# implies `changed`. The identical-looking guard on `moveSavedQueueMember` *is* load-bearing, because a
# boundary move has a non-null member with `changed = false` - which is why proof 2 fires. Two adjacent guards
# that look the same and are not; see `ReaderApp.moveSavedQueueMember`, where that is written down so the next
# person does not "simplify" the move guard by analogy with the remove one.

# --- Restored -------------------------------------------------------------------------------------------------
$report += Invoke-Proof 'RESTORED'
foreach ($f in @($edits, $app)) { Remove-Item "$f.bak" -Force }

$report | ForEach-Object { Write-Output $_ }

if ($script:unapplied -gt 0) {
    throw "$($script:unapplied) proof edit(s) were never applied, so those proofs proved nothing."
}
