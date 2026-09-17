# Clean check of the Player Back contract: leaving the Player must not touch playback.
#
# The app now starts on Home with a navigation bar, so this walks the real flow rather than tapping a
# hard-coded coordinate on the Note screen: Home -> Browse -> Play all -> Player -> Back -> Browse. That
# is the UI contract's own worked example ("Browse -> Play all -> Player -> Back -> Browse. Playback
# continues."), and it covers the mini-player too, which only exists while a resumable session does.
#
# Target coordinates are read from the accessibility dump rather than hard-coded. The previous version
# hard-coded Play at (139,2154), which silently became a tap on empty space the moment the shell changed
# what was on screen — a probe that misses reports a wrong answer with full confidence.
$ErrorActionPreference = 'Stop'
$adb = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_*\platform-tools\adb.exe" | Select-Object -First 1).FullName

function Dump([string]$name) {
    & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    & $adb pull /sdcard/ui.xml "$env:TEMP\$name.xml" | Out-Null
    [xml](Get-Content "$env:TEMP\$name.xml" -Raw)
}

function Texts($x) {
    return @($x.SelectNodes('//node') | Where-Object { $_.text } | ForEach-Object { $_.text })
}

function CentreOf($x, [string]$label) {
    $node = $x.SelectNodes('//node') | Where-Object { $_.text -eq $label } | Select-Object -First 1
    if (-not $node) { throw "no node labelled '$label' on screen" }

    # "[x1,y1][x2,y2]" -> midpoint. Bounds are parsed rather than assumed, so a layout change moves the
    # tap with the control instead of quietly missing it.
    $n = @([regex]::Matches($node.bounds, '\d+') | ForEach-Object { [int]$_.Value })
    return "$([int](($n[0] + $n[2]) / 2)) $([int](($n[1] + $n[3]) / 2))"
}

function Screen($x) {
    $texts = Texts $x

    # Classified by the controls each surface owns, not by a document title. The title check this used to
    # do assumed one specific fixture page, and reported the wrong screen as soon as the app began opening
    # a different one by default.
    $screen = if ($texts -contains 'Previous section') { 'PLAYER' }
    elseif ($texts -contains 'Search notes') { 'BROWSE' }
    elseif (($texts -contains 'Browse Notes') -or ($texts -contains 'Continue listening')) { 'HOME' }
    else { 'OTHER' }

    # The mini-player is the session described outside the Player: a queue position and a state. It must be
    # present on Browse and absent on the Player, since the full surface already owns those controls.
    $mini = ($texts | Where-Object { $_ -match '^Note \d+ of \d+ · ' }) -join ','
    $position = ($texts | Where-Object {
            $_ -match 'Section \d|Paragraph \d|^Playing$|^Paused$|^End of note$|^End of queue$'
        }) -join ' || '

    $parts = @()
    if ($position) { $parts += $position }
    $parts += if ($mini) { "mini-player: $mini" } else { 'mini-player: absent' }
    "$screen | $($parts -join ' | ')"
}

function NewestSpeechPlayer {
    # TalkBack's own earcons are SoundPool players too, and TalkBack is enabled on this device, so the
    # newest "new player" line is often TalkBack's rather than the reader's. Without this exclusion the
    # check reported a TalkBack timestamp and looked like evidence that the reader was still speaking.
    $match = & $adb shell dumpsys audio |
        Select-String 'new player' |
        Where-Object { $_ -notmatch 'marvin\.talkback' } |
        Select-Object -Last 1
    if ($match) { $match.ToString().Trim() } else { 'none' }
}

# Fresh launch so nothing from a previous probe is left over.
& $adb shell am force-stop com.example.notionreader
& $adb shell am start -n com.example.notionreader/.MainActivity | Out-Null
Start-Sleep -Seconds 4

# Named `$homeScreen` rather than `$home`: PowerShell's `$HOME` is a read-only automatic variable, and
# assigning to it fails at runtime rather than at parse time.
$homeScreen = Dump 'shell-home'
"home:        $(Screen $homeScreen)"

# Home -> Browse.
& $adb shell input tap (CentreOf $homeScreen 'Browse')
Start-Sleep -Seconds 3
$browseScreen = Dump 'shell-browse'
"browse:      $(Screen $browseScreen)"

# Play all snapshots the visible result set and opens the Player.
& $adb shell input tap (CentreOf $browseScreen 'Play all')
Start-Sleep -Seconds 4
$player = Dump 'shell-player'
"player:      $(Screen $player)"

"speech before back: $(NewestSpeechPlayer)"
Start-Sleep -Seconds 5

# Back leaves the Player for Browse, and playback must keep going.
& $adb shell input keyevent KEYCODE_BACK
Start-Sleep -Seconds 2
$afterBack = Dump 'shell-after-back'
"after back:  $(Screen $afterBack)"

Start-Sleep -Seconds 6
"speech after back:  $(NewestSpeechPlayer)"
