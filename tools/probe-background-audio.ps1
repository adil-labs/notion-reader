# Does the app survive background audio on API 37, and does it have a foreground service?
#
# This tests the prediction recorded in docs/decisions.md 23.20: Media3's MediaSessionService goes foreground
# by posting a media notification, the notification is Phase 4's work, and so on Android 17 the app should
# have its audio suppressed for want of a foreground claim - while behaving perfectly on Android 16, which has
# no such rule.
#
# The evidence is the platform's own audit log, which is the only thing that reports a *silent* suppression:
#
#   AudioHardening background playback muted for <pkg>, level: partial   -> no foreground service at all
#   AudioHardening background playback muted for <pkg>, level: full      -> a service, but without
#                                                                           while-in-use capability
#   no entry for <pkg>                                                   -> compliant
#
# Tap targets are read from the accessibility dump rather than hard-coded, and the device is identified first,
# for the reasons the other probes in this directory state at length.
$ErrorActionPreference = 'Stop'

$adb = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_*\platform-tools\adb.exe" |
    Select-Object -First 1).FullName

$attached = @(& $adb devices | Where-Object { $_ -match '\sdevice$' } | ForEach-Object { ($_ -split '\s+')[0] })
if ($attached.Count -ne 1) { throw "need exactly one device, found $($attached.Count)" }

$model = (& $adb shell getprop ro.product.model).Trim()
$sdk = [int](& $adb shell getprop ro.build.version.sdk).Trim()
Write-Output "device: $model, API $sdk"

if ($sdk -lt 37) {
    Write-Output 'INCONCLUSIVE: background-audio hardening is API 37+. Nothing below can show it.'
}

$package = 'com.example.notionreader'

& $adb logcat -c | Out-Null
& $adb shell am force-stop $package | Out-Null
& $adb shell am start -n "$package/.MainActivity" | Out-Null
Start-Sleep -Seconds 5

function TapLabel([string]$label) {
    & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    & $adb pull /sdcard/ui.xml "$env:TEMP\bga.xml" | Out-Null
    $x = [xml](Get-Content "$env:TEMP\bga.xml" -Raw)
    $node = $x.SelectNodes('//node') | Where-Object { $_.text -eq $label } | Select-Object -First 1
    if (-not $node) { throw "no node labelled '$label'" }
    $n = @([regex]::Matches($node.bounds, '\d+') | ForEach-Object { [int]$_.Value })
    $cx = [int](($n[0] + $n[2]) / 2); $cy = [int](($n[1] + $n[3]) / 2)
    & $adb shell input tap $cx $cy | Out-Null
    Write-Output "  tapped '$label' at ($cx,$cy)"
    Start-Sleep -Seconds 3
}

# Home -> Browse -> Play all -> Player, leaving it reading.
#
# The **tab** is tapped rather than Home's `Browse Notes` button, and that is a correction rather than a
# preference: the first version assumed the app starts on Home, and it does not when a previous session is
# resumable — Home then shows Continue listening and the Player is one Continue away. The first run failed
# with "no node labelled 'Browse Notes'" on a device whose app was on the Player, already playing a restored
# session. The bottom tab exists on every top-level destination, so it is the one navigation step that is
# always available. Same class as the two guards above: an assumption about the screen rather than a reading
# of it.
TapLabel 'Browse'
TapLabel 'Play all'
Start-Sleep -Seconds 6

# 1. Is it reading at all, in the foreground?
& $adb shell dumpsys audio > "$env:TEMP\bga-fore.txt" 2>&1
$playingFore = @(Select-String -Path "$env:TEMP\bga-fore.txt" -Pattern 'new player' |
    Where-Object { $_.Line -match 'package=com\.google\.android\.tts' } | Select-Object -Last 1)
Write-Output "TTS utterance while foregrounded: $(if ($playingFore.Count) { 'yes' } else { 'NO' })"

# 2. The foreground-service question, asked before anything is allowed to die.
& $adb shell dumpsys activity services $package > "$env:TEMP\bga-svc.txt" 2>&1
$svcLines = @(Get-Content "$env:TEMP\bga-svc.txt")
$hasService = @($svcLines | Where-Object { $_ -match 'PlaybackService' }).Count -gt 0
$isForeground = @($svcLines | Where-Object { $_ -match 'isForeground=true' }).Count -gt 0
Write-Output "PlaybackService running: $hasService"
Write-Output "  ...and foreground:      $isForeground"

# The mechanism behind a missing foreground claim, reported alongside it because the two go together.
#
# A Media3 `MediaSessionService` becomes foreground by posting a **media notification**, so an app that
# declares no notification permission cannot hold the claim at all — and on API 37+ that means its audio is
# silently suppressed in the background. Reported rather than asserted: the answer is the point.
& $adb shell dumpsys package $package > "$env:TEMP\bga-pkg.txt" 2>&1
$notifGranted = @(Select-String -Path "$env:TEMP\bga-pkg.txt" -Pattern 'POST_NOTIFICATIONS: granted=true').Count -gt 0
$notifDeclared = @(Select-String -Path "$env:TEMP\bga-pkg.txt" -Pattern 'POST_NOTIFICATIONS').Count -gt 0
$notifInSource = @(Select-String -Path (Join-Path $PSScriptRoot '..\app\src\main\AndroidManifest.xml') -Pattern 'POST_NOTIFICATIONS').Count -gt 0
Write-Output "POST_NOTIFICATIONS declared in the manifest: $notifInSource"
Write-Output "  ...present in the installed package:        $notifDeclared"
Write-Output "  ...and granted:                            $notifGranted"

# 3. Now the actual failing case: the screen goes off, so the app is background.
& $adb shell input keyevent 26 | Out-Null
Write-Output 'screen off; waiting 45s to see whether reading continues'
Start-Sleep -Seconds 45

& $adb shell dumpsys audio > "$env:TEMP\bga-bg.txt" 2>&1
$playingBg = @(Select-String -Path "$env:TEMP\bga-bg.txt" -Pattern 'new player' |
    Where-Object { $_.Line -match 'package=com\.google\.android\.tts' } | Select-Object -Last 1)
Write-Output "TTS utterance since backgrounding: $(if ($playingBg.Count) { 'yes - reading continued' } else { 'NO - reading stopped' })"

# 4. The platform's own verdict, which is what distinguishes "stopped" from "muted".
$hardening = @(Select-String -Path "$env:TEMP\bga-bg.txt" -Pattern 'AudioHardening' |
    Where-Object { $_.Line -match [regex]::Escape($package) })
Write-Output "AudioHardening entries for $package : $($hardening.Count)"
$hardening | ForEach-Object { Write-Output "  $($_.Line.Trim())" }

& $adb shell input keyevent 224 | Out-Null   # wake the screen again
& $adb shell am force-stop $package | Out-Null
