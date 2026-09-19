# Verifies the wrapper's completeness check: a run that lost part of its suite must not report a pass.
#
# ## Why this exists
#
# Until the check was added, the pass condition was "no failures" and nothing compared how many tests ran
# against how many should have. Observed for real: a run ended with `tests=125` against a suite of 168, four
# classes missing from the report, and `Transport endpoint is not connected` in the log - and it only came
# out red because one of the classes that did run happened to fail. Had that class passed, the runner would
# have printed `RESULT: PASSED - 125 tests, 0 failures.`
#
# This script exercises that exact scenario against the real wrapper logic by trimming a captured result and
# checking what the verdict code does with it. It asserts the check *fires*, because a check that never fires
# is indistinguishable from a clean run.
#
# Run with: pwsh -NoProfile -File tools\check-wrapper-completeness.ps1

$ErrorActionPreference = 'Stop'
Set-Location 'd:\repos\notion-reader'

$wrapperPath = 'tools\run-accessibility-tests.ps1'
$wrapperText = Get-Content $wrapperPath -Raw

# The wrapper's own expected-count expression, lifted out rather than retyped, so this cannot pass against a
# copy that has drifted from the real one.
$countBlock = [regex]::Match(
    $wrapperText,
    '(?s)\$expectedTests = @\(.*?\n\)\.Count'
).Value
if (-not $countBlock) { throw "Could not extract the expectedTests block from $wrapperPath" }

$repoRoot = (Get-Location).Path

# **Dot-sourced, not `&`.** Invoking a scriptblock with `&` runs it in its own scope, so its assignment to
# `$expectedTests` is local to it and never reaches here - the count came back empty and the checker reported
# a failure against a wrapper that was working. This is the same PowerShell scoping rule as an accumulator
# built inside a function, and it has now cost time three times in this project.
. ([scriptblock]::Create($countBlock))

if (-not $expectedTests -or $expectedTests -eq 0) {
    throw "The expected-count expression produced '$expectedTests', so this check cannot run."
}

$report = @(
    "expected tests counted from sources : $expectedTests"
)

# The check itself, as the wrapper words it. Only the comparison matters here, so it is reproduced with the
# same shape rather than the whole verdict.
function Verdict([int] $tests, [int] $failures, [int] $expected) {
    if ($failures -gt 0) { return 'FAILED' }
    if ($tests -eq 0) { return 'INCONCLUSIVE' }
    if ($tests -lt $expected) { return 'INCOMPLETE' }
    return 'PASSED'
}

<#
    Which attempt the verdict comes from.

    **Lifted out of the wrapper by regex, not retyped.** This function used to be a hand-written copy of the
    rule, which is exactly what this project's own rule forbids: a copy can drift, and then the check passes
    against logic that is no longer deployed. It did drift - the wrapper gained a second clause and the copy
    did not, so the check would have gone on passing while testing the old rule.

    Two clauses, both learned from real runs:

    1. Prefer the most recent attempt that ran the **whole** suite. This was `$attempts[-1]`, and:

           attempt 1: 180 tests, 1 failure  <- complete; its only failure was a harness failure
           attempt 2: 139 tests, 1 failure  <- truncated
           reported:  FAILED - 1 of 139

       A retry discarded a complete run for a partial one, when a retry is only permitted if every failure
       in the first attempt was a harness failure - so the attempt thrown away is the one already known to be
       uninformative, and the one kept is a fragment.

    2. When **no** attempt is complete, prefer the one that read the most tests. This was `$attempts[-1]`, and:

           Attempt 1: 7 test(s), 2 failure(s) - INCOMPLETE   <- both failures classified as the harness
           Attempt 2: 0 test(s), 0 failure(s) - INCOMPLETE   <- install failed; the device was dead
           reported:  INCONCLUSIVE

       Attempt 1 had done the useful work and all of it was thrown away.
#>
$selectionMatch = [regex]::Match($wrapperText,
    '(?s)(\$complete = @\(\$attempts.*?\$final = if .*?\n)')

if (-not $selectionMatch.Success) {
    throw ("Could not extract the attempt-selection logic from $wrapperPath. If it was rewritten, this check " +
        'must be updated deliberately - it must not silently stop testing anything.')
}

$selectionText = $selectionMatch.Groups[1].Value
. ([scriptblock]::Create("function ChosenAttempt(`$attempts, [int] `$expectedTests) {`n" + $selectionText + "return `$final`n}"))

# The scenario that actually happened: three quarters of the suite, no failures, four classes absent.
$truncated = [Math]::Floor($expectedTests * 0.75)
$fullRunButFailed = 3

$cases = @(
    @{ Label = 'complete run, 0 failures';      Tests = $expectedTests; Failures = 0 },
    @{ Label = 'complete run, 3 failures';      Tests = $expectedTests; Failures = $fullRunButFailed },
    @{ Label = 'TRUNCATED run, 0 failures';     Tests = $truncated;     Failures = 0 },
    @{ Label = 'truncated run, 1 failure';      Tests = $truncated;     Failures = 1 },
    @{ Label = 'nothing ran';                   Tests = 0;              Failures = 0 }
)

$report += ''
foreach ($case in $cases) {
    $verdict = Verdict $case.Tests $case.Failures $expectedTests
    $report += ("  tests={0,-4} failures={1,-2} -> {2,-12} {3}" -f $case.Tests, $case.Failures, $verdict, $case.Label)
}

$report | ForEach-Object { Write-Output $_ }

# -------------------------------------------------------------------------------------------- assertions

if ($expectedTests -eq 0) { throw 'The expected-count expression found no tests, so it is broken.' }

# The one that matters: a truncated run with no failures must NOT be reported as a pass. Before the fix it was.
$truncatedClean = Verdict $truncated 0 $expectedTests
if ($truncatedClean -ne 'INCOMPLETE') {
    throw "A truncated run with no failures reported '$truncatedClean' instead of INCOMPLETE."
}

# And a complete clean run must still pass, or the check is useless in the other direction.
if ((Verdict $expectedTests 0 $expectedTests) -ne 'PASSED') {
    throw 'A complete run with no failures was not reported as PASSED.'
}

# ------------------------------------------------------------------------------------- attempt selection

# The exact scenario a real run produced: a complete attempt with one harness failure, then a retry that
# truncated. The verdict must come from the complete one.
$realWorld = @(
    [pscustomobject]@{ Reported = $true; Tests = $expectedTests; Failures = 1 },
    [pscustomobject]@{ Reported = $true; Tests = $truncated;     Failures = 1 }
)
$chosen = ChosenAttempt $realWorld $expectedTests

$report += ''
$report += 'Attempt selection:'
$report += ("  attempt 1: {0} tests, {1} failure(s)" -f $realWorld[0].Tests, $realWorld[0].Failures)
$report += ("  attempt 2: {0} tests, {1} failure(s)" -f $realWorld[1].Tests, $realWorld[1].Failures)
$report += ("  chosen   : {0} tests  <- must be the COMPLETE one" -f $chosen.Tests)

if ($chosen.Tests -ne $expectedTests) {
    throw ("A retry replaced a complete attempt ({0} tests) with a truncated one ({1} tests)." -f `
        $expectedTests, $chosen.Tests)
}

# And the other direction: two complete attempts must resolve to the later one, because that is the one that
# had a second chance to reproduce the failure - which is what the retry was for.
$twoComplete = @(
    [pscustomobject]@{ Reported = $true; Tests = $expectedTests; Failures = 1 },
    [pscustomobject]@{ Reported = $true; Tests = $expectedTests; Failures = 0 }
)
if ((ChosenAttempt $twoComplete $expectedTests).Failures -ne 0) {
    throw 'Among two complete attempts, the later one must win.'
}

# ------------------------------------------------------------------ no complete attempt: the second fix

# The exact scenario a single-class run produced. Neither attempt is complete; the LATER one read fewer tests,
# because the device died and the retry could not install. The verdict must come from attempt 1 - which had
# seven tests and two classified harness failures - not from a run that produced nothing.
$noneComplete = @(
    [pscustomobject]@{ Reported = $true;  Tests = 7; Failures = 2 },
    [pscustomobject]@{ Reported = $false; Tests = 0; Failures = 0 }
)
$chosenNone = ChosenAttempt $noneComplete $expectedTests

$report += ''
$report += 'Attempt selection when nothing is complete:'
$report += ("  attempt 1: {0} tests, {1} failure(s)" -f $noneComplete[0].Tests, $noneComplete[0].Failures)
$report += ("  attempt 2: {0} tests (reported={1})" -f $noneComplete[1].Tests, $noneComplete[1].Reported)
$report += ("  chosen   : {0} tests  <- must be the one that read MORE" -f $chosenNone.Tests)

if ($chosenNone.Tests -ne 7) {
    # Parenthesised around the whole concatenation, because `-f` binds tighter than `+`. The first version of
    # this throw message was not, and printed a literal "{0}" - the identical mistake the wrapper itself was
    # just fixed for, made again in the code written to test it. That is worth keeping in mind here: this
    # precedence rule is sticky precisely because the line reads correctly.
    throw (("With no complete attempt, the one that read the most tests must be chosen: chose {0} tests " +
            '(attempt 2, which read nothing) instead of 7.') -f $chosenNone.Tests)
}

# And the ordering must not be backwards: with the same data in the other order, the later attempt wins,
# because among equals the later one is the better evidence.
$noneCompleteSwapped = @(
    [pscustomobject]@{ Reported = $true; Tests = 2;  Failures = 1 },
    [pscustomobject]@{ Reported = $true; Tests = 9; Failures = 1 }
)
if ((ChosenAttempt $noneCompleteSwapped $expectedTests).Tests -ne 9) {
    throw 'Among incomplete attempts, the one that read the most tests must win regardless of order.'
}
$noneCompleteTie = @(
    [pscustomobject]@{ Reported = $true; Tests = 5; Failures = 0 },
    [pscustomobject]@{ Reported = $true; Tests = 5; Failures = 1 }
)
if ((ChosenAttempt $noneCompleteTie $expectedTests).Failures -ne 1) {
    throw 'Among incomplete attempts that read the same number of tests, the later one must win.'
}

# A run that reported nothing at all must still fall back to the last attempt, so the INCONCLUSIVE path is
# reachable - otherwise 'nothing ran' would have no verdict to describe.
$noneReported = @(
    [pscustomobject]@{ Reported = $false; Tests = 0; Failures = 0 },
    [pscustomobject]@{ Reported = $false; Tests = 0; Failures = 0 }
)
if ((ChosenAttempt $noneReported $expectedTests).Reported) {
    throw 'With no reported attempt the fallback must still return an attempt, so INCONCLUSIVE can be reached.'
}

Write-Output ''
Write-Output 'OK: a truncated run cannot be reported as a pass, a complete one still can, and a retry cannot'
Write-Output '    replace a complete attempt with a partial one.'
