# Runs the wrapper's own failure-text reader against the REAL results file, rather than against a fixture.
#
# ## Why this exists
#
# `tools/check-harness-signatures.ps1` proves the reader handles both XML shapes, but it proves it against
# a fixture this project wrote. A fixture cannot reveal that the *real* Gradle output differs from what the
# fixture assumes - and the whole reason that check exists is that the previous reader silently matched
# nothing on real output while appearing correct.
#
# So this one reads an actual run's XML, picks the failures the runner failed to classify, and reports what
# the reader made of them. It is the difference between "the classifier can classify in principle" and "the
# classifier classified this run".
#
# Run with: pwsh -NoProfile -File tools\check-classifier-on-real-output.ps1

$ErrorActionPreference = 'Stop'
Set-Location 'd:\repos\notion-reader'

$wrapperPath = 'tools\run-accessibility-tests.ps1'
$wrapperText = Get-Content $wrapperPath -Raw

# The wrapper's own reader and pattern list, lifted rather than retyped - the same technique the other checks
# use, and for the same reason: a copy can drift and then the check passes against something else.
$lines = Get-Content $wrapperPath
$start = ($lines | Select-String -Pattern '^\$HARNESS_FAILURE_PATTERNS = @\(' | Select-Object -First 1).LineNumber
$end = 0
for ($i = $start; $i -lt $lines.Count; $i++) { if ($lines[$i].Trim() -eq ')') { $end = $i + 1; break } }
$body = $lines[$start..($end - 2)] | Where-Object { -not $_.TrimStart().StartsWith('#') }
$patterns = @($body | Where-Object { $_ -match "^\s*'(.+)',?\s*$" } |
    ForEach-Object { [regex]::Match($_, "^\s*'(.+?)',?\s*$").Groups[1].Value })

$functionText = [regex]::Match($wrapperText, '(?s)function Get-FailureText\(\$node\) \{.*?\n\}').Value
if (-not $functionText) { throw "Could not extract Get-FailureText from $wrapperPath" }
. ([scriptblock]::Create($functionText))

# ------------------------------------------------------------------------------------ a real run's XML

$resultDir = 'app\build\outputs\androidTest-results\connected\debug'
$file = Get-ChildItem $resultDir -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $file) { throw "No results XML under $resultDir. Run the instrumented suite first." }

$doc = [xml](Get-Content -LiteralPath $file.FullName -Raw)

$report = @("reading: $($file.Name)", '')

# ---------------------------------------------------------------------------------------------------
# Three counts, and the check is that they agree.
#
# This file used to have exactly the defect it exists to detect: its own loop skipped a testcase with
# `if (-not ($case.failure -or $case.error)) { continue }`, and an empty `<failure></failure>` is falsy, so
# it reported `failures in this file : 0` while reading a file that contained one. A check that cannot see
# the case in front of it reports the most reassuring number available, which is zero.
#
# So the filter is presence-based here too, AND the totals are compared. The comparison is the part that
# matters: it is what makes a *future* drop loud, whatever shape it takes, because a dropped failure moves
# `seen` away from `declared` instead of quietly deflating both.
# ---------------------------------------------------------------------------------------------------

$declared = 0
foreach ($suite in $doc.testsuites.testsuite) {
    $declared += [int]$suite.failures + [int]$suite.errors
}

$seen = 0
$empties = 0
$patternCases = 0
$patternHits = 0
$classified = @{}
$samples = @()

foreach ($suite in $doc.testsuites.testsuite) {
    foreach ($case in $suite.testcase) {
        # Presence, not truthiness. See the note above; this is the fix, not a style preference.
        if ($null -eq $case.failure -and $null -eq $case.error) { continue }
        $seen++

        # `Get-FailureText` is total, so this cannot throw - but it CAN return nothing, and nothing is
        # meaningful rather than broken: it is the record a device-side death leaves.
        $text = [string](Get-FailureText $case.failure + Get-FailureText $case.error)
        if ($text.Length -eq 0) { $empties++ }

        # The wrapper's rule, in the wrapper's order: empty is the harness structurally, and only a
        # non-empty text is offered to the pattern list.
        $matchedAny = [string]::IsNullOrWhiteSpace($text)
        foreach ($pattern in $patterns) {
            if ($text -like "*$pattern*") {
                $classified[$pattern] = 1 + ($classified[$pattern] -as [int])
                $matchedAny = $true
                $patternHits++
            }
        }
        # Counted per *testcase*, not per pattern: one failure can legitimately match two signatures (a
        # crash trace containing both "Process crashed" and "Instrumentation run failed" is normal), and
        # counting the matches instead of the cases would make the unclassified total go negative on a
        # completely healthy run - a check that fails when nothing is wrong is worse than no check.
        if ($matchedAny -and $text.Length -gt 0) { $patternCases++ }
        if (-not $matchedAny -and $samples.Count -lt 3) {
            # Truncated by *taking* rather than by index arithmetic, because the first line can be shorter
            # than the window and a bad `Substring` here would replace the report with a script error.
            $firstLine = ($text.Split("`n")[0] -replace '^java\.lang\.\w+:\s*', '')
            if ($firstLine.Length -gt 90) { $firstLine = $firstLine.Substring(0, 90) }
            $samples += ("{0}.{1}: {2}" -f $suite.name.Split('.')[-1], $case.name, $firstLine)
        }
    }
}

$unclassified = $seen - $empties - $patternCases

$report += "failures declared by the suites : $declared"
$report += "failures with a per-test record : $seen   <-- must equal the line above"
$report += "  ... empty, so the harness     : $empties"
$report += "  ... matched a pattern         : $patternCases"
$report += "  ... UNCLASSIFIED              : $unclassified   <-- must be 0"
$report += ''
$report += 'Patterns that matched:'
if ($classified.Count -eq 0) {
    # Nothing matching is a legitimate outcome for a clean run, so it is stated as such rather than as a
    # failure - but note that it is only legitimate when there were no failures to match either.
    $report += '  (none - no pattern was needed on this run)'
} else {
    foreach ($key in $classified.Keys) { $report += ("  {0,5}  {1}" -f $classified[$key], $key) }
}

if ($samples.Count -gt 0) {
    $report += ''
    $report += 'Unclassified (first 3):'
    foreach ($s in $samples) { $report += "  $s" }
}

$report | ForEach-Object { Write-Output $_ }

# ---------------------------------------------------------------------------------------------------
# The verdict. Each condition is thrown by name, because the failure mode of a check like this is to look
# like a clean result - so which condition fired has to be readable without re-deriving it.
# ---------------------------------------------------------------------------------------------------

if ($seen -ne $declared) {
    throw ("The file declares $declared failure(s) but only $seen produced a per-test record. Something is " +
        'being dropped between the XML and the reader - which is the defect this check was written to catch.')
}

if ($unclassified -ne 0) {
    throw "$unclassified failure(s) matched no signature and were not empty records, so nothing could classify them."
}

Write-Output ''
Write-Output ("OK: every failure the run declared was read and classified ({0} declared, {1} read, {2} empty-as-harness, {3} by pattern)." -f `
        $declared, $seen, $empties, $patternCases)
