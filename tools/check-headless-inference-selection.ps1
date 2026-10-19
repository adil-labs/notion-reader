#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$wrapper = Join-Path $PSScriptRoot 'run-accessibility-tests.ps1'
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($wrapper, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Wrapper parse failed' }
$function = $ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Test-HeadlessInferenceSelection' }, $true)
if ($function.Count -ne 1) { throw 'Cannot find actual headless selection guard' }
. ([scriptblock]::Create($function[0].Extent.Text))
foreach ($name in @('GemmaBackendBenchmarkTest','GemmaXnnpackMatchedThreadsBenchmarkTest','GemmaQnnProbeTest','GemmaVulkanProbeTest','GemmaDeploymentDeviceTest')) {
    $class = "com.example.notionreader.ui.$name"
    if (-not (Test-HeadlessInferenceSelection -Class $class -Requested $true)) { throw 'Approved native test refused' }
    if (Test-HeadlessInferenceSelection -Class $class -Requested $false) { throw 'Headless mode enabled without request' }
}
foreach ($class in @('', 'com.example.notionreader.ui.PlayerScreenAccessibilityTest',
    'com.example.notionreader.ui.GemmaQnnProbeTest,com.example.notionreader.ui.PlayerScreenAccessibilityTest',
    'com.example.notionreader.ui.gemmaQnnProbeTest', 'unknown')) {
    $refused = $false
    try { Test-HeadlessInferenceSelection -Class $class -Requested $true | Out-Null } catch { $refused = $true }
    if (-not $refused) { throw "Headless inference guard accepted disallowed selection: $class" }
}
Write-Output 'Shipping headless guard accepts only five explicit native classes; UI/mixed/unknown/default selections remain interactive.'
