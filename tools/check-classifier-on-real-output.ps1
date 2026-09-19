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

$total = 0
$empties = 0
$classified = @{}
$samples = @()

foreach ($suite in $doc.testsuites.testsuite) {
    foreach ($case in $suite.testcase) {
        if (-not ($case.failure -or $case.error)) { continue }
        $total++

        # `Get-FailureText` is total, so this cannot throw - but it CAN return nothing, and nothing is the
        # failure this check is looking for.
        $text = [string](Get-FailureText $case.failure + Get-FailureText $case.error)
        if ($text.Length -eq 0) { $empties++ }

        $matchedAny = $false
        foreach ($pattern in $patterns) {
            if ($text -like "*$pattern*") {
                $classified[$pattern] = 1 + ($classified[$pattern] -as [int])
                $matchedAny = $true
            }
        }
        if (-not $matchedAny -and $samples.Count -lt 3) {
            # Truncated by *taking* rather than by index arithmetic, because the first line can be shorter
            # than the window and a bad `Substring` here would replace the report with a script error.
            $firstLine = ($text.Split("`n")[0] -replace '^java\.lang\.\w+:\s*', '')
            if ($firstLine.Length -gt 90) { $firstLine = $firstLine.Substring(0, 90) }
            $samples += ("{0}.{1}: {2}" -f $suite.name.Split('.')[-1], $case.name, $firstLine)
        }
    }
}

$report += "failures in this file     : $total"
$report += "read as EMPTY by the reader: $empties   <-- must be 0; a non-zero value means nothing can ever match"
$report += ''
$report += 'Classified as harness signatures:'
if ($classified.Count -eq 0) {
    $report += '  (none)'
} else {
    foreach ($key in $classified.Keys) { $report += ("  {0,5}  {1}" -f $classified[$key], $key) }
}

if ($samples.Count -gt 0) {
    $report += ''
    $report += 'Unclassified (first 3):'
    foreach ($s in $samples) { $report += "  $s" }
}

$report | ForEach-Object { Write-Output $_ }

if ($empties -gt 0) {
    throw "$empties failure(s) read as empty, so no signature could match them."
}
