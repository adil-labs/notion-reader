# Clean check of the Player Back contract: leaving the Player must not touch playback.
$ErrorActionPreference = 'Stop'
$adb = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_*\platform-tools\adb.exe" | Select-Object -First 1).FullName

function Screen([string]$name) {
    & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    & $adb pull /sdcard/ui.xml "$env:TEMP\$name.xml" | Out-Null
    [xml]$x = Get-Content "$env:TEMP\$name.xml"
    $nodes = $x.SelectNodes('//node') | Where-Object { $_.text }
    # The Note screen shows the whole document; the Player shows a position line. Report which.
    $screen = if ($nodes.text -contains 'Reader Fixture — Block Type Coverage' -and
                   -not ($nodes.text -contains 'Previous section')) { 'NOTE' } else { 'PLAYER' }
    $position = ($nodes | Where-Object {
        $_.text -match 'Section \d|Paragraph \d|^Playing$|^Paused$|^End of note$'
    } | ForEach-Object { $_.text }) -join ' || '
    "$screen $(if ($position) { "| $position" } else { '| (document body)' })"
}

function NewestSpeechPlayer {
    $match = & $adb shell dumpsys audio | Select-String 'new player' | Select-Object -Last 1
    if ($match) { $match.ToString().Trim() } else { 'none' }
}

# Fresh launch so nothing from a previous probe is left over.
& $adb shell am force-stop com.example.notionreader
& $adb shell am start -n com.example.notionreader/.MainActivity | Out-Null
Start-Sleep -Seconds 3
"note screen:   $(Screen 'back-1')"

# Play opens the Player and starts reading from the beginning.
& $adb shell input tap 139 2154
Start-Sleep -Seconds 2
"player:        $(Screen 'back-2')"

# Back leaves the Player for the Note screen; playback must keep going.
& $adb shell input keyevent KEYCODE_BACK
Start-Sleep -Milliseconds 500
"after back:    $(Screen 'back-3')"

"speech before: $(NewestSpeechPlayer)"
Start-Sleep -Seconds 5
"speech after:  $(NewestSpeechPlayer)"
