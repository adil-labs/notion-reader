#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$wrapper = Join-Path $PSScriptRoot 'run-accessibility-tests.ps1'
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($wrapper, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Wrapper parse failed' }
$function = $ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Get-KeyguardShowing' }, $true)
if ($function.Count -ne 1) { throw 'Cannot find the actual wrapper keyguard parser' }
. ([scriptblock]::Create($function[0].Extent.Text))
function Assert-State($text, $expected) {
    $actual = Get-KeyguardShowing $text
    if ($actual -cne $expected) { throw "Keyguard parser: expected '$expected', got '$actual'" }
}
Assert-State 'isKeyguardShowing=false inputRestricted=false' 'isKeyguardShowing=false'
Assert-State 'isKeyguardShowing=true inputRestricted=true' 'isKeyguardShowing=true'
Assert-State 'isKeyguardShowing=false isKeyguardShowing=true' 'isKeyguardShowing=true'
Assert-State 'unrecognized output' $null
$legacy = "KeyguardServiceDelegate`n  showing=false`n  showingAndNotOccluded=true`n  inputRestricted=false`n  KeyguardStateMonitor`n    mIsShowing=false`n    mSimSecure=false`n    mInputRestricted=false`n    mTrusted=false`n"
Assert-State $legacy 'isKeyguardShowing=false'
Assert-State ($legacy.Replace('mIsShowing=false','mIsShowing=true')) 'isKeyguardShowing=true'
Assert-State ($legacy.Replace('mInputRestricted=false','mInputRestricted=true')) 'isKeyguardShowing=true'
Assert-State ($legacy.Replace('mInputRestricted=false','mUnknown=false')) $null
Assert-State ("isKeyguardShowing=false`n" + $legacy.Replace('mIsShowing=false','mIsShowing=true')) 'isKeyguardShowing=true'
Assert-State 'mIsShowing=false mInputRestricted=false' $null
Write-Output 'Actual wrapper parser passed 10 modern/legacy/locked/unknown keyguard cases.'
