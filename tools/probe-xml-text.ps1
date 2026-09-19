# Focused diagnostic: does `$case.failure.'#text'` actually yield the message from a JUnit-XML failure?
#
# This matters more than it looks. The wrapper classifies a failure as "the harness, not the app" by
# matching the message it reads from `$_.failure.'#text' + $_.error.'#text'`. If that expression is empty,
# **no signature can ever match**, the retry never fires, and the whole mechanism is silently dead while
# appearing to be present - the same shape as a regex that never matches.
#
# Run with: pwsh -NoProfile -File tools\probe-xml-text.ps1

$ErrorActionPreference = 'Stop'

$path = Join-Path $env:TEMP 'xml-text-probe.xml'
$lines = @(
    "<?xml version='1.0' encoding='UTF-8' ?>"
    '<testsuites tests="1" failures="1">'
    '  <testsuite name="com.example.SomeTest" tests="1" failures="1">'
    '    <testcase name="aTeardownFailure" classname="com.example.SomeTest">'
    '      <failure message="attr message">java.lang.AssertionError: from the text'
    '        at some.Stack.Trace(File.java:1)</failure>'
    '    </testcase>'
    '  </testsuite>'
    '</testsuites>'
)
Set-Content -Path $path -Value $lines -Encoding utf8

$doc = [xml](Get-Content -LiteralPath $path -Raw)
$case = $doc.testsuites.testsuite.testcase
$failure = $case.failure

$report = @(
    "failure type        : $($failure.GetType().FullName)"
    "failure.OuterXml    : $($failure.OuterXml)"
    "failure.InnerText   : $($failure.InnerText)"
    "failure.'#text'     : [$($failure.'#text')]"
    "failure.message attr: [$($failure.message)]"
    "childNodes count    : $($failure.ChildNodes.Count)"
    "first child type    : $($failure.ChildNodes[0].NodeType)"
    "first child value   : [$($failure.ChildNodes[0].Value)]"
    ''
    "wrapper expression  : [$([string]($failure.'#text' + $null))]"
    "expression length   : $([string]($failure.'#text' + $null).Length)"
)

Remove-Item $path -Force
$report | ForEach-Object { Write-Output $_ }
