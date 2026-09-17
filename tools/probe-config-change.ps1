<#
.SYNOPSIS
    Drives a real configuration change under a live listening session, and reads what survived.

.DESCRIPTION
    This exists for exactly one claim, and for the claim's *kind*: the reader's session has to outlive the
    screen it was started from, and a configuration change is the case where "the screen" is destroyed by
    the system without the user going anywhere.

    Nothing else in this repository can check it. The JVM tests build a ReaderApp directly, so they never
    observe an Activity lifecycle; the instrumented suite calls `setContent` with a single screen and never
    runs MainActivity at all. So the boundary that decides whether a session survives a rebuild has, until
    now, been asserted only in prose.

    The interesting failure is silent, which is why the probe measures rather than looks. Before the session's
    ownership moved out of the Activity, a configuration change:
      - shut the speech engine down, ending playback mid-sentence;
      - discarded the live session, so the queue, its position and its identity were gone;
      - rebuilt a fresh session on the demo document;
      - re-fetched the Browse index.

    None of those is an error, a crash, or even a visible glitch - the app looks fine, sitting on Home with
    a document nobody asked for. So the checks are chosen to be *discriminating* rather than reassuring:

        the Player is still the screen on top           (a rebuilt app starts on Home)
        the queue line still says the same "Note N of M" (a rebuilt app has no queue)
        the paragraph position did not reset to 1        (a rebuilt session is on paragraph 1)
        the state still says Playing                     (a rebuilt engine is not playing)
        Next section still moves this session's position (the same live engine is still driving it)

    The configuration change is the system font scale, because it is the least invasive one that still
    rebuilds the Activity: it needs no reboot, disturbs no rotation setting, and is restored in a `finally`.
    Reading the value and putting it back is deliberate - a probe that leaves the device's accessibility
    settings changed is a probe nobody will run twice.

.PARAMETER KeepRunning
    Leave the app running at the end instead of stopping it.
#>
[CmdletBinding()]
param(
    [switch]$KeepRunning
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$adb = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_*\platform-tools\adb.exe" |
    Select-Object -First 1).FullName
if (-not $adb) { throw 'adb not found.' }

$package = 'com.example.notionreader'
$activity = "$package/.MainActivity"

function Get-Screen {
    <# The accessibility tree, as text. Dumped to the device and pulled; see probe-listening-tab.ps1. #>
    & $adb shell uiautomator dump /sdcard/probe.xml | Out-Null
    $local = Join-Path $env:TEMP 'notion-config-probe.xml'
    & $adb pull /sdcard/probe.xml $local 2>&1 | Out-Null
    return [System.IO.File]::ReadAllText($local)
}

function Get-Texts([string]$Xml) {
    return @([regex]::Matches($Xml, 'text="([^"]*)"') | ForEach-Object { $_.Groups[1].Value } |
        Where-Object { $_ -and $_ -ne 'null' })
}

function Find-Target([string]$Xml, [string]$Label) {
    <# The centre of the smallest node whose text or content description contains the label. #>
    $pattern = '<node[^>]*?(?:text|content-desc)="[^"]*' + [regex]::Escape($Label) + '[^"]*"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $matches = [regex]::Matches($Xml, $pattern)
    if ($matches.Count -eq 0) { return $null }

    $best = $null
    foreach ($match in $matches) {
        $left = [int]$match.Groups[1].Value
        $top = [int]$match.Groups[2].Value
        $right = [int]$match.Groups[3].Value
        $bottom = [int]$match.Groups[4].Value
        $area = ($right - $left) * ($bottom - $top)

        if ($null -eq $best -or $area -lt $best.Area) {
            $best = [pscustomobject]@{ X = [int](($left + $right) / 2); Y = [int](($top + $bottom) / 2) }
        }
    }
    return $best
}

function Tap-Label([string]$Label, [switch]$Required) {
    $screen = Get-Screen
    $target = Find-Target $screen $Label
    if ($null -eq $target) {
        if ($Required) {
            throw "Could not find a control labelled '$Label'. On screen: $((Get-Texts $screen) -join ' / ')"
        }
        Write-Host "  (no '$Label' on screen)"
        return $false
    }
    & $adb shell input tap $target.X $target.Y | Out-Null
    return $true
}

function Show-Screen([string]$Caption) {
    <# Captions go to the HOST and only the texts are returned; a returning function's Write-Output is part
       of its return value, which swallowed a whole transcript once already. #>
    $texts = Get-Texts (Get-Screen)
    Write-Host ""
    Write-Host "  --- $Caption"
    $texts | Select-Object -First 20 | ForEach-Object { Write-Host "      $_" }
    return $texts
}

function Get-FirstMatch($Texts, [string]$Pattern) {
    return ($Texts | Where-Object { $_ -match $Pattern } | Select-Object -First 1)
}

$apk = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { throw "APK not found: $apk. Run :app:assembleDebug first." }

Write-Host 'Installing...'
& $adb install -r $apk 2>&1 | Select-String -Pattern 'Success|Failure' | ForEach-Object { Write-Host "  $_" }

$originalFontScale = (& $adb shell settings get system font_scale).Trim()
Write-Host "font_scale before: $originalFontScale"

try {
    & $adb shell am force-stop $package | Out-Null
    & $adb shell am start -n $activity | Out-Null
    Start-Sleep -Seconds 4

    Write-Host ''
    Write-Host 'Browse -> Play all -> Player'
    if (-not (Tap-Label 'Browse' -Required)) { throw 'Browse is missing from the navigation bar.' }
    Start-Sleep -Seconds 3
    if (-not (Tap-Label 'Play all' -Required)) { throw 'Play all is missing from Browse.' }
    Start-Sleep -Seconds 5

    # Move the session somewhere that a rebuild could not plausibly reproduce by accident: a fresh session
    # on the demo document sits at "Section 1" and "Paragraph 1", so a position that is neither is evidence
    # that this is the *same* session rather than a new one that happens to look similar.
    for ($i = 0; $i -lt 2; $i++) {
        if (-not (Tap-Label 'Next section')) { break }
        Start-Sleep -Seconds 2
    }

    $before = Show-Screen 'Player, before the configuration change'
    $queueBefore = Get-FirstMatch $before 'Note \d+ of \d+'
    $sectionBefore = Get-FirstMatch $before 'Section \d+ of \d+'
    $paragraphBefore = Get-FirstMatch $before 'Paragraph \d+ of \d+'

    Write-Host ''
    Write-Host "  queue=$queueBefore section=$sectionBefore paragraph=$paragraphBefore"

    # A *different* value is a configuration change; the same value would be a no-op and would prove nothing.
    $changedFontScale = if ($originalFontScale -eq '1.15') { '1.3' } else { '1.15' }
    Write-Host ''
    Write-Host "Changing the system font scale to $changedFontScale (this rebuilds the Activity)..."
    & $adb shell settings put system font_scale $changedFontScale | Out-Null
    Start-Sleep -Seconds 5

    $after = Show-Screen 'After the configuration change'
    $queueAfter = Get-FirstMatch $after 'Note \d+ of \d+'
    $paragraphAfter = Get-FirstMatch $after 'Paragraph \d+ of \d+'

    # Read a number out of "Paragraph 12 of 263", for the comparison only.
    function Get-IndexOf([string]$Line) {
        if (-not $Line) { return -1 }
        $match = [regex]::Match($Line, 'Paragraph (\d+) of')
        if ($match.Success) { return [int]$match.Groups[1].Value }
        return -1
    }

    $paragraphIndexBefore = Get-IndexOf $paragraphBefore
    $paragraphIndexAfter = Get-IndexOf $paragraphAfter

    # Is the same live session still driving the same engine? Move it once more and watch this session's
    # own position change. A rebuilt app would be on Home, so this also cannot pass by accident.
    $movedAfterTheChange = $false
    if (Tap-Label 'Next section') {
        Start-Sleep -Seconds 3
        $advanced = Show-Screen 'Player, after the configuration change and one more Next section'
        $sectionAdvanced = Get-FirstMatch $advanced 'Section \d+ of \d+'
        $movedAfterTheChange = ($null -ne $sectionAdvanced) -and ($sectionAdvanced -ne $sectionBefore)
        Write-Host "  section before=$sectionBefore after=$sectionAdvanced"
    }

    Write-Host ''
    Write-Host '  Checks'
    $checks = [ordered]@{
        'the Player is still the screen on top'          = ($null -ne $queueAfter)
        'the Active Queue is the same one'               = ($null -ne $queueAfter) -and ($queueAfter -eq $queueBefore)
        'the playback position did not reset'            = ($paragraphIndexBefore -ge 0) -and ($paragraphIndexAfter -ge $paragraphIndexBefore)
        'the session is still playing'                   = ($after | Where-Object { $_ -eq 'Playing' }).Count -gt 0
        'the same live engine still responds'            = $movedAfterTheChange
    }

    foreach ($check in $checks.GetEnumerator()) {
        Write-Host ("  [{0}] {1}" -f $(if ($check.Value) { 'PASS' } else { 'FAIL' }), $check.Key)
    }

    if ($checks.Values -contains $false) { $failed = $true }
}
finally {
    # Restored unconditionally: a probe that leaves the device's settings changed is a probe that should not
    # be run twice, and font scale is a setting the user can see.
    & $adb shell settings put system font_scale $originalFontScale | Out-Null
    Write-Host ''
    Write-Host "Restored font_scale to $originalFontScale"

    if (-not $KeepRunning) {
        & $adb shell am force-stop $package | Out-Null
    }
}

if ($failed) { exit 1 }
exit 0
