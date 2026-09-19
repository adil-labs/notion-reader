# Verifies that the wrapper's harness-signature classification can actually classify.
#
# ## The bug this exists because of
#
# The classifier read `$_.failure.'#text'` and **that expression is empty for an `<failure>` element with no
# attributes** - which is the shape Gradle writes. PowerShell's `[xml]` adapter reduces an attribute-less
# element holding only text to a plain String, and a String has no `'#text'`, so the read was `$null` and no
# signature could ever match. The retry mechanism was dead while appearing present: the array was populated,
# the loop ran, the branch existed.
#
# A check that can never match is indistinguishable from a clean run. That is why this script asserts the
# *reading* as well as the matching.
#
# Run with: pwsh -NoProfile -File tools\check-harness-signatures.ps1

$ErrorActionPreference = 'Stop'
Set-Location 'd:\repos\notion-reader'

$wrapperPath = 'tools\run-accessibility-tests.ps1'
$wrapperLines = Get-Content $wrapperPath
$wrapperText = Get-Content $wrapperPath -Raw

# ------------------------------------------------------------------------------- the real classification
#
# Both pieces are taken out of the wrapper rather than retyped, so this cannot pass against a copy of the
# logic that has drifted from the real one - which would be the same class of mistake all over again.

$start = ($wrapperLines | Select-String -Pattern '^\$HARNESS_FAILURE_PATTERNS = @\(' | Select-Object -First 1).LineNumber
if (-not $start) { throw "Could not find HARNESS_FAILURE_PATTERNS in $wrapperPath" }

$end = 0
for ($i = $start; $i -lt $wrapperLines.Count; $i++) {
    if ($wrapperLines[$i].Trim() -eq ')') { $end = $i + 1; break }
}
if ($end -eq 0) { throw 'Could not find the end of the HARNESS_FAILURE_PATTERNS array.' }

# Comments are stripped *before* the quoted entries are read. Without this, the extraction picks up every
# apostrophe in the prose that explains the entries, which is how the first version of this script found 11
# "patterns" - six of them sentences.
$body = $wrapperLines[$start..($end - 2)] |
    Where-Object { -not $_.TrimStart().StartsWith('#') }

$patterns = @($body |
    Where-Object { $_ -match "^\s*'(.+)',?\s*$" } |
    ForEach-Object { [regex]::Match($_, "^\s*'(.+?)',?\s*$").Groups[1].Value })

if ($patterns.Count -eq 0) { throw 'Extracted no patterns, which means the parse is wrong rather than the list.' }

# The wrapper's own reader, lifted out and made available here. Lazy to the first newline-then-close-brace,
# which is the function's own end because every nested block is written on one line.
$functionText = [regex]::Match($wrapperText, '(?s)function Get-FailureText\(\$node\) \{.*?\n\}').Value
if (-not $functionText) { throw "Could not extract Get-FailureText from $wrapperPath" }
. ([scriptblock]::Create($functionText))

# ----------------------------------------------------------------------------------- both XML shapes
#
# The first is what Gradle writes and is the shape that broke the classifier. The second is the same
# information with an attribute, which is the shape the old expression happened to work on - included so a
# future change cannot fix one by breaking the other.

$message = 'java.lang.AssertionError: Activity never becomes requested state "[DESTROYED]" (last lifecycle transition = "PAUSED") at androidx.test.ext.junit.rules.ActivityScenarioRule.after(ActivityScenarioRule.java:117)'

function Read-FailureText([string] $failureXml) {
    $fixture = Join-Path $env:TEMP 'harness-check.xml'
    $xml = "<?xml version='1.0' encoding='UTF-8' ?><testsuites tests='1' failures='1'><testsuite name='com.example.SomeTest' tests='1' failures='1'><testcase name='aTeardownFailure' classname='com.example.SomeTest'>$failureXml</testcase></testsuite></testsuites>"
    Set-Content -Path $fixture -Value $xml -Encoding utf8

    $doc = [xml](Get-Content -LiteralPath $fixture -Raw)
    $case = $doc.testsuites.testsuite.testcase
    $node = $case.failure
    $shape = $(if ($node -is [string]) { 'String' } else { $node.GetType().Name })

    # The wrapper's own expression, character for character.
    $text = [string](Get-FailureText $node + Get-FailureText $case.error)

    Remove-Item $fixture -Force
    return [pscustomobject]@{ Shape = $shape; Text = $text }
}

$withoutAttribute = Read-FailureText "<failure>$message</failure>"
$withAttribute = Read-FailureText "<failure message='attr'>$message</failure>"

$report = @(
    "patterns extracted   : $($patterns.Count)"
    ''
    'The failure message, as the wrapper reads it:'
    ("  no attribute       : shape={0,-10} chars={1}" -f $withoutAttribute.Shape, $withoutAttribute.Text.Length)
    ("  with attribute     : shape={0,-10} chars={1}" -f $withAttribute.Shape, $withAttribute.Text.Length)
    ''
    'Signature matching against the read message:'
)

foreach ($shape in @(
        @{ Label = 'no attribute'; Text = $withoutAttribute.Text },
        @{ Label = 'with attr   '; Text = $withAttribute.Text }
    )) {
    foreach ($pattern in $patterns) {
        if ($shape.Text -like "*$pattern*") {
            $report += ("  {0}  MATCH  {1}" -f $shape.Label, $pattern)
        }
    }
}

$report += ''
$report += "the teardown signature is in the list : $($patterns -contains 'Activity never becomes requested state')"
$report | ForEach-Object { Write-Output $_ }

# -------------------------------------------------------------------------------------------- the assertions

# 1. The read must not be empty for either shape. An empty read is the bug.
foreach ($shape in @($withoutAttribute, $withAttribute)) {
    if ($shape.Text.Length -eq 0) { throw "The failure text read as empty for a '$($shape.Shape)' failure node." }
}

# 2. The signature added this round must match the message it was added for, on both shapes - because the
#    shape is the variable that broke it, and testing only the convenient one is how it broke in the first place.
foreach ($shape in @($withoutAttribute, $withAttribute)) {
    if (-not ($shape.Text -like '*Activity never becomes requested state*')) {
        throw "The teardown signature does not match a '$($shape.Shape)' failure node."
    }
}

Write-Output ''
Write-Output 'OK: the classifier can read a failure message and match a signature, for both XML shapes.'
