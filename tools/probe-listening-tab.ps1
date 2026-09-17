<#
.SYNOPSIS
    Drives the Listening tab end to end on a device, including across a forced process death.

.DESCRIPTION
    The instrumented tests prove the Queues screen's semantics in isolation, and the JVM tests prove which
    sessions each section lists. Neither can prove the thing the Active Queue & Reader Session
    specification actually cares about, because that is a claim about the *application*: that a session
    started in one launch of the app is still there, naming the right Note and the right position, after
    the process has been killed and restarted.

    So this walks the real flow and reads the real accessibility tree:

        launch -> Browse -> Play all -> Player -> force-stop -> relaunch -> Queues -> Listening

    It reads tap targets out of `uiautomator dump` rather than hard-coding coordinates, which is the
    lesson `probe-player-back.ps1` already learned: a screen classifier that matches a title broke once
    already when the app began auto-discovering a different page, and coordinates break on every device
    that is not this one.

.PARAMETER KeepRunning
    Leave the app on the Queues screen instead of returning to Home.
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
    <#
        The accessibility tree, as text. Dumped to the device and pulled rather than streamed, because
        `uiautomator dump /dev/tty` is unreliable across adb versions and returns partial output on this one.
    #>
    & $adb shell uiautomator dump /sdcard/probe.xml | Out-Null
    $local = Join-Path $env:TEMP 'notion-probe.xml'
    & $adb pull /sdcard/probe.xml $local 2>&1 | Out-Null
    return [System.IO.File]::ReadAllText($local)
}

function Get-Texts([string]$Xml) {
    return @([regex]::Matches($Xml, 'text="([^"]*)"') | ForEach-Object { $_.Groups[1].Value } |
        Where-Object { $_ -and $_ -ne 'null' })
}

function Find-Target([string]$Xml, [string]$Label) {
    <#
        The centre of the smallest node whose text or content description contains the label.

        Smallest, because a container's bounds cover its children: matching the outermost node that
        contains a label would tap the middle of the screen instead of the control.
    #>
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
            $best = [pscustomobject]@{
                X    = [int](($left + $right) / 2)
                Y    = [int](($top + $bottom) / 2)
                Area = $area
            }
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
    <#
        Captions and body go to the HOST, and only the texts are returned.

        `Write-Output` inside a function is part of its return value, so the version of this that printed
        the screen with `Write-Output` and then `return $texts` handed the caller one array of prose and
        labels mixed together - and because the caller assigned that to a variable, the whole transcript
        was swallowed instead of shown. The probe still passed its checks, which is what makes this worth
        fixing rather than tolerating: the report it exists to produce was invisible.
    #>
    $texts = Get-Texts (Get-Screen)
    Write-Host ""
    Write-Host "  --- $Caption"
    $texts | Select-Object -First 22 | ForEach-Object { Write-Host "      $_" }
    return $texts
}

$apk = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { throw "APK not found: $apk. Run :app:assembleDebug first." }

Write-Host 'Installing...'
& $adb install -r $apk 2>&1 | Select-String -Pattern 'Success|Failure' | ForEach-Object { Write-Host "  $_" }

# A fresh install keeps the previous history in DataStore, which is what makes the restart half of this
# probe meaningful. `pm clear` would erase the very thing being tested, so it is deliberately not run.

& $adb shell am force-stop $package | Out-Null
& $adb shell am start -n $activity | Out-Null
Start-Sleep -Seconds 4

# $home and $pid are READ-ONLY automatic variables in PowerShell, so the launch screen is not called
# that. Assigning to $home fails at RUNTIME, which is how this was found the first time too - the script
# installed the APK and then died on the next line.
$homeScreen = Show-Screen 'Home, fresh launch'

Write-Host ''
Write-Host 'Browse -> Play all'
if (-not (Tap-Label 'Browse' -Required)) { throw 'Browse is missing from the navigation bar.' }
Start-Sleep -Seconds 3
Show-Screen 'Browse' | Out-Null

if (-not (Tap-Label 'Play all' -Required)) { throw 'Play all is missing from Browse.' }
Start-Sleep -Seconds 5
$playerScreen = Show-Screen 'Player'

# The Player has to be reading a queue, not a single Note, or the queue-position line the Listening tab
# shows would be absent for a reason unrelated to persistence.
$queueLine = $playerScreen | Where-Object { $_ -match 'Note \d+ of \d+' } | Select-Object -First 1
if (-not $queueLine) { Write-Warning 'No "Note N of M" line on the Player, so this is not a multi-Note queue.' }
else { Write-Host "  queue position: $queueLine" }

Write-Host ''
Write-Host 'Forcing process death...'
& $adb shell am force-stop $package | Out-Null
Start-Sleep -Seconds 1
& $adb shell am start -n $activity | Out-Null
Start-Sleep -Seconds 4

$relaunchScreen = Show-Screen 'Home, after force-stop'

Write-Host ''
Write-Host 'Queues -> Listening'
if (-not (Tap-Label 'Queues' -Required)) { throw 'Queues is missing from the navigation bar.' }
Start-Sleep -Seconds 3
$queuesScreen = Show-Screen 'Queues'

# The assertions, stated as facts read off the tree rather than as a verdict from a classifier.
$checks = [ordered]@{
    'the Listening tab is selected'      = ($queuesScreen | Where-Object { $_ -match '^Listening' }).Count -gt 0
    'a session survived the restart'     = ($queuesScreen | Where-Object { $_ -match 'Note \d+ of \d+' }).Count -gt 0
    'the Continue section is present'    = ($queuesScreen | Where-Object { $_ -eq 'Continue listening' }).Count -gt 0
    'a Continue control exists'          = ($queuesScreen | Where-Object { $_ -eq 'Continue' }).Count -gt 0
    'no raw timestamp is shown'          = ($queuesScreen | Where-Object { $_ -match '\b17\d{11}\b' }).Count -eq 0
}

Write-Host ''
Write-Host "  Directly after the forced relaunch, Home offered:"
($relaunchScreen | Select-Object -First 6) | ForEach-Object { Write-Host "      $_" }

Write-Host ''
foreach ($check in $checks.GetEnumerator()) {
    Write-Host ("  [{0}] {1}" -f $(if ($check.Value) { 'PASS' } else { 'FAIL' }), $check.Key)
}

if (-not $KeepRunning) {
    & $adb shell am force-stop $package | Out-Null
}

if ($checks.Values -contains $false) { exit 1 }
exit 0
