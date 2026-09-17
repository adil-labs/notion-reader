# Verifies section navigation on the connected device, one press at a time and with playback paused.
$ErrorActionPreference = 'Stop'
$adb = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_*\platform-tools\adb.exe" | Select-Object -First 1).FullName

function Dump([string]$name) {
    & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    & $adb pull /sdcard/ui.xml "$env:TEMP\$name.xml" | Out-Null
    [xml]$x = Get-Content "$env:TEMP\$name.xml"
    ($x.SelectNodes('//node') |
        Where-Object { $_.text -match 'Section \d|Paragraph \d|^Playing$|^Paused$|^End of note$|Intro paragraph|Primary Heading|Secondary Heading|Tertiary Heading|Body under' } |
        ForEach-Object { $_.text }) -join ' || '
}

function Tap([int]$x, [int]$y, [string]$label) {
    & $adb shell input tap $x $y
    Start-Sleep -Milliseconds 900
    "$label -> $(Dump 'probe')"
}

# Pause first so auto-advance cannot be mistaken for navigation.
Tap 504 2127 'pause       '
Tap 813 2127 'next section'
Tap 813 2127 'next section'
Tap 195 2127 'prev section'
Tap 195 2127 'prev section'
