#requires -Version 7.0
<#
.SYNOPSIS
    Reads and drives the app's UI by accessible text, for verifying listening history on a device.

.DESCRIPTION
    A one-liner that dumps the UI, regexes out a node's bounds and taps it is three statements that have to
    survive PowerShell's parser and the persistent terminal, and in practice it does not: the version of
    this that was typed inline produced "Cannot convert value "760" to type "System.Xml.XmlDocument""
    before it reached the device. So it lives in a file, like the other probes.

    Reading the *bounds out of the accessibility dump* rather than hard-coding coordinates is deliberate and
    is what `probe-player-back.ps1` was rewritten to do after hard-coded taps silently landed on the wrong
    control when the layout changed.

    ACTIONS
      dump                 - print every non-empty text in the tree, plus the focused window, in order.
      tap    -Text X       - tap the centre of the first node whose text is exactly X.
      tapall -Text X       - tap it N times, one per invocation of the device command. Used to advance
                             playback; the Player's Next control clamps at the last chunk.
      back                 - press the system Back key.

.EXAMPLE
    pwsh -File tools/probe-history.ps1 -Action dump
    pwsh -File tools/probe-history.ps1 -Action tap -Text 'Browse'
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('dump', 'tap', 'tapall', 'back')]
    [string]$Action,

    [string]$Text = '',

    [int]$Times = 1
)

$ErrorActionPreference = 'Stop'

$adb = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_*\platform-tools\adb.exe" |
    Select-Object -First 1).FullName

if (-not $adb) { throw 'adb not found.' }

$dumpPath = Join-Path $env:TEMP 'notion-reader-ui.xml'

function Get-UiDump {
    & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    & $adb pull /sdcard/ui.xml $dumpPath | Out-Null
    return Get-Content $dumpPath -Raw
}

function Get-NodeBounds([string]$Dump, [string]$Label) {
    # `[^>]*` cannot cross a tag boundary, so this stays inside one node. The escaping is for labels that
    # contain a quote or a character the regex would otherwise read as syntax.
    $pattern = 'text="' + [regex]::Escape($Label) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $match = [regex]::Match($Dump, $pattern)
    if (-not $match.Success) { return $null }

    return @{
        Left   = [int]$match.Groups[1].Value
        Top    = [int]$match.Groups[2].Value
        Right  = [int]$match.Groups[3].Value
        Bottom = [int]$match.Groups[4].Value
    }
}

function Invoke-Tap([hashtable]$Bounds) {
    $x = [int](($Bounds.Left + $Bounds.Right) / 2)
    $y = [int](($Bounds.Top + $Bounds.Bottom) / 2)
    & $adb shell input tap $x $y | Out-Null
    return "$x,$y"
}

switch ($Action) {
    'dump' {
        $dump = Get-UiDump
        $texts = [regex]::Matches($dump, 'text="([^"]+)"') |
            ForEach-Object { $_.Groups[1].Value } |
            Where-Object { $_ -ne '' }

        Write-Output "--- visible text ---"
        $texts | ForEach-Object { Write-Output $_ }
    }

    'tap' {
        if (-not $Text) { throw '-Text is required for -Action tap.' }

        $bounds = Get-NodeBounds (Get-UiDump) $Text
        if (-not $bounds) { throw "No node with text '$Text' is on screen." }

        Write-Output "tapped '$Text' at $(Invoke-Tap $bounds)"
    }

    'tapall' {
        if (-not $Text) { throw '-Text is required for -Action tapall.' }

        for ($i = 1; $i -le $Times; $i++) {
            # Re-dumped each time: a control can move, and on the Player the position line changes, so a
            # stale coordinate would tap something else entirely.
            $bounds = Get-NodeBounds (Get-UiDump) $Text
            if (-not $bounds) {
                Write-Output "stopped after $($i - 1) taps: no node with text '$Text' is on screen."
                break
            }
            [void](Invoke-Tap $bounds)
        }

        Write-Output "tapped '$Text' up to $Times time(s)."
    }

    'back' {
        & $adb shell input keyevent KEYCODE_BACK | Out-Null
        Write-Output 'pressed Back'
    }
}
