# Proves that a failure recorded with NO message and NO trace is (a) seen at all and (b) classified as the
# harness rather than as an app defect.
#
# ## Why this exists
#
# Found from a real run that stopped after 14 of 180 tests. The XML held:
#
#     <testcase name="createIsUnavailableUntilTheNameIsUsable"><failure></failure></testcase>
#
# and the suite element said `failures="1"`. The runner printed the suite line, then the HTML line, then
# `RESULT: FAILED` - **with no failure name, no failure text, and no retry**, because:
#
#   * the filter was `Where-Object { $_.failure -or $_.error }`, and an empty element deserialises to an
#     empty String, which is *falsy* - so the testcase never reached the classifier at all; and
#   * `Test-IsRetryable` gates the retry on `HarnessFailures.Count -eq Failures`, which could not hold, so
#     the only failure in the run was neither reported nor retried.
#
# Every failure this runner had ever successfully handled carried a message. The shape it could not see was
# the one with nothing inside it - which is exactly the shape a device-side death leaves, so the case it was
# blindest to was the case it most needed to recognise. That makes it the third member of a family this file
# keeps recording: a check that cannot see its own subject still reports a number, and the number is
# usually reassuring.
#
# ## What is lifted rather than retyped
#
# The `Where-Object` predicate is **taken out of the wrapper's source by regex** and executed as a
# scriptblock, so this check cannot pass against a copy of the filter that has drifted from the real one.
# That is the same technique the other checks in this directory use.
#
# Run with: pwsh -NoProfile -File tools\check-empty-failure-is-classified.ps1

$ErrorActionPreference = 'Stop'
Set-Location 'd:\repos\notion-reader'

$wrapperPath = 'tools\run-accessibility-tests.ps1'
$wrapperText = Get-Content $wrapperPath -Raw

$failures = @()

function Assert-That($condition, $description) {
    if ($condition) {
        Write-Output "  ok    $description"
    }
    else {
        Write-Output "  FAIL  $description"
        $script:failures += $description
    }
}

# ---------------------------------------------------------------------------------------------------
# The wrapper's own filter, lifted.
# ---------------------------------------------------------------------------------------------------

$filterMatch = [regex]::Match($wrapperText,
    'testcase \| Where-Object \{ (\$null -ne \$_\.failure -or \$null -ne \$_\..+?) \} \| ForEach-Object')

if (-not $filterMatch.Success) {
    throw ("Could not extract the failure filter from $wrapperPath. If the filter was rewritten, this check " +
        'has to be updated deliberately - it must not silently stop testing anything.')
}

$filterExpression = $filterMatch.Groups[1].Value
Write-Output "filter lifted from the wrapper: $filterExpression"
Write-Output ''

# Three testcases, built as raw XML so the shapes are the real ones rather than hand-built objects.
[xml]$doc = @'
<testsuites>
  <testsuite name="s" tests="3" failures="3">
    <testcase name="aDeathWithNoMessage"><failure></failure></testcase>
    <testcase name="aKnownHarnessSignature"><failure message="crash">java.lang.RuntimeException: Process crashed</failure></testcase>
    <testcase name="aRealDefect"><failure message="assert">java.lang.AssertionError: Expected 3 but was 2</failure></testcase>
  </testsuite>
</testsuites>
'@

$cases = @($doc.testsuites.testsuite.testcase)

# Executed as a scriptblock so `$_` binds exactly as it does inside the wrapper's own pipeline. This is the
# difference between testing the filter and testing a description of it.
$predicate = [scriptblock]::Create($filterExpression)
$seen = @($cases | Where-Object $predicate)

Write-Output 'Does the filter see each shape?'
Assert-That ($seen.Count -eq 3) "all three testcases reach the classifier (saw $($seen.Count) of 3)"
Assert-That (@($seen | Where-Object { $_.name -eq 'aDeathWithNoMessage' }).Count -eq 1) `
    'the empty <failure></failure> is NOT dropped'
Assert-That (@($seen | Where-Object { $_.name -eq 'aRealDefect' }).Count -eq 1) `
    'a message-bearing failure is still seen'

# The proof that this check can distinguish anything: the expression it replaced must FAIL the first of those
# assertions. Without this, a filter that accidentally matched everything would also pass.
$oldPredicate = [scriptblock]::Create('$_.failure -or $_.error')
$oldSeen = @($cases | Where-Object $oldPredicate)
Assert-That ($oldSeen.Count -eq 2) "the replaced truthiness filter saw only 2 of 3 (it saw $($oldSeen.Count)) - so the defect is real and this check can see it"

Write-Output ''
Write-Output 'Is an empty record classified as the harness?'
Assert-That ($wrapperText -match '\$isHarness = \[string\]::IsNullOrWhiteSpace\(\$text\)') `
    'the wrapper treats an empty failure as the harness structurally, before the pattern list'

# And the anti-laundering assertion, which matters more than the one above: the change must not make every
# unexplained failure retryable. A message-bearing failure that matches no signature is still a real one.
$harnessPatterns = [regex]::Matches($wrapperText, "(?m)^\s{4}'(.+?)',?\s*$") |
    ForEach-Object { $_.Groups[1].Value }

$realDefectText = 'java.lang.AssertionError: Expected 3 but was 2'
$matchesAnyPattern = $false
foreach ($pattern in $harnessPatterns) { if ($realDefectText -like "*$pattern*") { $matchesAnyPattern = $true } }

Assert-That (-not $matchesAnyPattern) `
    'an unrecognised message-bearing failure still matches no signature, so it stays red and is not retried'
Assert-That (-not [string]::IsNullOrWhiteSpace($realDefectText)) `
    'a real assertion failure is never empty, which is what makes "empty means the harness" safe'

Write-Output ''
if ($failures.Count -gt 0) {
    throw "$($failures.Count) assertion(s) failed in this check."
}

Write-Output 'OK: an empty failure record is seen and classified as the harness, while a message-bearing failure that matches no signature stays a real failure.'
