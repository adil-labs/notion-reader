#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'run-accessibility-tests.ps1'), [ref]$tokens, [ref]$errors)
$guard = $ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Test-ModelDeploymentSelection' }, $true)
if ($guard.Count -ne 1 -or $errors.Count) { throw 'Actual deployment guard missing or unparsable' }
. ([scriptblock]::Create($guard[0].Extent.Text))
foreach ($name in @('GemmaDeploymentDeviceTest','GemmaAcquisitionDeviceTest')) {
    $class = "com.example.notionreader.ui.$name"
    if (-not (Test-ModelDeploymentSelection $class $true)) { throw 'Explicit deployment not selected' }
    $refused = $false
    try { Test-ModelDeploymentSelection $class $false | Out-Null } catch { $refused = $true }
    if (-not $refused) { throw 'Deployment permitted without explicit request' }
}
foreach ($class in @('', 'unknown', 'com.example.notionreader.ui.PlayerScreenAccessibilityTest',
    'com.example.notionreader.ui.GemmaDeploymentDeviceTest,com.example.notionreader.ui.GemmaAcquisitionDeviceTest')) {
    $refused = $false
    try { Test-ModelDeploymentSelection $class $true | Out-Null } catch { $refused = $true }
    if (-not $refused) { throw 'Deployment flag accepted unsupported selection' }
}
if (Test-ModelDeploymentSelection '' $false) { throw 'Default suite enabled deployment' }
Write-Output 'Only two exact single deployment classes accept explicit DeployModel; default/full/unknown/mixed selections cannot download.'

$wrapperText = Get-Content (Join-Path $PSScriptRoot 'run-accessibility-tests.ps1') -Raw
$runner = [regex]::Match($wrapperText, 'notClass=([^\x27]+)').Groups[1].Value -split ','
$countFilter = [regex]::Match($wrapperText, 'Where-Object \{ \$_.BaseName -cnotin @\(([^)]+)\) \}').Groups[1].Value
$excluded = @([regex]::Matches($countFilter, "'([^']+)'") | ForEach-Object { $_.Groups[1].Value } | Sort-Object)
$runnerNames = @($runner | ForEach-Object { ($_ -split '\.')[-1] } | Sort-Object)
if (($excluded -join '|') -cne ($runnerNames -join '|') -or $excluded.Count -ne 2) {
    throw 'Default runner exclusions and source completeness exclusions disagree'
}
Write-Output 'Actual notClass runner exclusions agree with the actual source-derived completeness filter.'
