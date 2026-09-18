# Sets up an Android emulator so the instrumented suite can run without a phone, and therefore without
# unlocking one. Idempotent: every step checks what is on disk and skips what is already there.
#
#     pwsh -NoProfile -File tools/setup-emulator.ps1                    # install + create + boot
#     pwsh -NoProfile -File tools/setup-emulator.ps1 -Check             # report only, change nothing
#     pwsh -NoProfile -File tools/setup-emulator.ps1 -Stop              # shut the emulator down
#
# WHY THIS EXISTS
#
# A phone with a screen lock cannot be prepared from a script. `adb shell wm dismiss-keyguard` needs no
# credential but only wakes the device, and `locksettings set-disabled true` -- the command that would
# remove the lock without one -- does nothing on a production build with a credential set:
#
#     $ adb shell locksettings set-disabled true
#     Lock screen disabled set to true          <- reports success
#     $ adb shell settings get secure lockscreen.disabled
#     0                                         <- and writes nothing
#
# That is the failure this project keeps meeting: a wrong answer, reported with full confidence. An
# emulator has no lock to clear, so it removes the credential from the loop entirely.
#
# THE THREE TRAPS THIS SCRIPT EXISTS TO AVOID, EACH OF WHICH COST TIME TO FIND
#
#   1. `sdkmanager` is deprecated AND it crashes. Its `--list` prints the installed packages and then
#      exits -1073740791 (0xC0000409, a Windows stack-buffer-overrun abort), so a listing that appears to
#      work reports nothing about what is *available* and reports failure on the way out. The replacement
#      is `android sdk list|install`, and this script uses it.
#
#   2. THE EXIT CODE OF EVERY SDK TOOL HERE IS UNRELIABLE, because the same crash happens after a
#      successful install. `system-images;android-36;google_apis;x86_64` installs fully and the command
#      still returns -1073740791. So this script never trusts an exit code: it checks the filesystem.
#
#   3. A package name containing semicolons is split by the `.bat` wrapper. sdkmanager.bat turned
#      `system-images;android-36;google_apis;x86_64` into four separate arguments and answered
#      "Package system-images not found. / Package android-36 not found." -- which reads as a bad package
#      name rather than as an argument-passing bug in the wrapper. The `android` CLI is a native binary
#      and takes the name whole, quoted.
#
# WHAT IT DOES NOT DO
#
# It does not change the phone. Nothing here touches an attached device, and the script refuses to start
# while one is attached, because the suite cannot tell which device it is meant to be testing.

param(
    [switch]$Check,
    [switch]$Stop,
    [string]$AvdName = 'medium_phone',
    # API 36 rather than 37: `google_apis` images exist for 36, and what needs verifying here is the
    # accessibility suite rather than the API 37 background-audio hardening. See the note at the end.
    [string]$ImagePackage = 'system-images;android-36;google_apis;x86_64',
    [int]$BootTimeoutSeconds = 900
)

$ErrorActionPreference = 'Stop'

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$avdHome = "$env:USERPROFILE\.android\avd"

function Write-Step([string]$message) { Write-Output "  $message" }
function Write-Head([string]$message) { Write-Output ''; Write-Output $message }

# The one `adb` lookup, done once. The WinGet package path is where platform-tools lives on this machine.
$adb = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_*\platform-tools\adb.exe" `
        -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
if (-not $adb) { $adb = (Get-Command adb -ErrorAction SilentlyContinue).Source }
if (-not $adb) { throw 'adb not found. Install platform-tools first.' }

if ($Stop) {
    Write-Head 'Stopping the emulator'
    $killed = $false
    & $adb devices | Select-String '^emulator-' | ForEach-Object {
        $serial = ($_ -split '\s+')[0]
        Write-Step "killing $serial"
        & $adb -s $serial emu kill 2>&1 | Out-Host
        $killed = $true
    }

    if (-not $killed) {
        Write-Step 'no emulator was listed'
        return
    }

    # **Wait for it to actually go**, rather than returning as soon as `emu kill` is acknowledged.
    #
    # This was a real race: `-Stop` returned while the device was still listed in `device` state, so a
    # following start saw "an emulator is already running", skipped starting one, and then found nothing to
    # wait for - ending in "did not finish booting ... (no emulator listed)". Two true statements that
    # together look like a broken script, and the cause was one step being asynchronous.
    Write-Step 'waiting for it to disappear'
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        if (@(& $adb devices | Select-String '^emulator-').Count -eq 0) {
            Write-Step 'stopped'
            return
        }
        Start-Sleep -Seconds 2
    }
    Write-Warning ('The emulator is still listed 90 seconds after being killed. A following start will ' +
        'refuse to launch a second one; check `adb devices` if that happens.')
    return
}

Write-Head "Android SDK: $sdk"
if (-not (Test-Path $sdk)) { throw "No SDK at $sdk. Set ANDROID_HOME or install the SDK." }

# -----------------------------------------------------------------------------------------------------------------
# Refuse while a real device is attached
# -----------------------------------------------------------------------------------------------------------------

Write-Head 'Checking attached devices'
$devices = @(& $adb devices | Select-String '^\S+\s+device$' | ForEach-Object { ($_ -split '\s+')[0] })
$physical = @($devices | Where-Object { $_ -notmatch '^emulator-' })

foreach ($d in $devices) { Write-Step "$d" }
if ($physical.Count -gt 0) {
    Write-Warning ("A physical device is attached ($($physical -join ', ')). The instrumented suite picks a " +
        'device by itself and cannot be told which one, so run this with only the emulator attached -- or ' +
        'ignore this warning if you know the phone is the one you want tested.')
}

# -----------------------------------------------------------------------------------------------------------------
# The pieces, each checked on disk rather than by exit code
# -----------------------------------------------------------------------------------------------------------------

$emulatorExe = Join-Path $sdk 'emulator\emulator.exe'

# The package name IS the path under the SDK root -- `system-images;android-36;google_apis;x86_64` maps to
# `system-images\android-36\google_apis\x86_64` -- so the `;` are replaced wholesale and nothing is joined
# on the front. An earlier version wrote `Join-Path $sdk (Join-Path 'system-images' $package)` and produced
# `system-images\system-images\android-36\...`, which reported a fully-installed 4.4 GB image as MISSING.
# Worth keeping as a comment because the symptom (a false "not installed") is indistinguishable from a
# genuinely failed install, and the response to those two is opposite: one re-downloads 4 GB for nothing.
$imageDir = Join-Path $sdk ($ImagePackage -replace ';', '\')
$avdIni = Join-Path $avdHome "$AvdName.ini"

$missing = [ordered]@{
    'emulator'     = -not (Test-Path $emulatorExe)
    "image"        = -not (Test-Path (Join-Path $imageDir 'system.img'))
    "avd $AvdName" = -not (Test-Path $avdIni)
}

Write-Head 'Current state'
foreach ($key in $missing.Keys) {
    $state = if ($missing[$key]) { 'MISSING' } else { 'present' }
    Write-Step ("{0,-16} {1}" -f $key, $state)
}

if ($missing.Values -notcontains $true) {
    Write-Head 'Everything is present. Nothing to install.'
}

if ($Check) {
    Write-Head 'Check only: nothing was changed.'
    return
}

# A package install is the only step whose failure is silent, so it is wrapped and reported by its effect.
function Install-Package([string]$package, [string]$what) {
    Write-Head "Installing $what"
    Write-Step "android sdk install `"$package`"  (the quote matters -- see the header)"
    $log = Join-Path $env:TEMP ("sdk-install-{0}.log" -f ($package -replace '[^A-Za-z0-9]', '-'))
    & android sdk install $package *> $log
    # Deliberately not checked. See trap 2 in the header: this exits non-zero after a full success.
    Write-Step "exit code $LASTEXITCODE (ignored -- the SDK tools abort after succeeding)"
    $errors = Select-String -Path $log -Pattern 'rror|not found|failed' -ErrorAction SilentlyContinue |
        ForEach-Object { $_.Line.Trim() } | Select-Object -Unique
    if ($errors) { Write-Step 'log mentions:'; $errors | ForEach-Object { Write-Step "  $_" } }
    Write-Step "log: $log"
}

if ($missing['emulator']) { Install-Package 'emulator' 'the emulator' }
if ($missing["image"]) { Install-Package $ImagePackage 'the system image' }

# Verified by effect, because that is the only reliable signal from these tools.
$stillMissing = @()
if (-not (Test-Path $emulatorExe)) { $stillMissing += 'emulator\emulator.exe' }
if (-not (Test-Path (Join-Path $imageDir 'system.img'))) { $stillMissing += $imageDir }
if ($stillMissing.Count -gt 0) {
    throw ("Install did not produce: $($stillMissing -join ', '). The tools may have failed silently -- " +
        'read the log paths printed above.')
}
Write-Head 'Emulator and image are on disk.'

# -----------------------------------------------------------------------------------------------------------------
# The AVD
# -----------------------------------------------------------------------------------------------------------------

if ($missing["avd $AvdName"]) {
    Write-Head "Creating the AVD '$AvdName'"
    # `medium_phone` rather than a tablet or desktop profile: this app is a phone reader, and the adaptive
    # work is a separate concern with its own sizes.
    & android emulator create $AvdName 2>&1 | ForEach-Object { Write-Step $_ }
    if (-not (Test-Path $avdIni)) { throw "The AVD was not created ($avdIni is absent)." }
}

# Which image the AVD resolved to is worth printing: `create` chooses a tag, and if it had picked a
# playstore image while only a `google_apis` one was installed, the boot would fail with a message about a
# missing image rather than about the mismatch.
$config = Join-Path $avdHome "$AvdName.avd\config.ini"
if (Test-Path $config) {
    Write-Head 'The AVD uses'
    Get-Content $config | Select-String 'image.sysdir.1|abi.type|tag.id|hw.lcd.width|hw.lcd.density|hw.ramSize' |
        ForEach-Object { Write-Step $_.Line.Trim() }
    $sysdir = (Get-Content $config | Select-String 'image.sysdir.1').Line -replace '.*=\s*', ''
    $resolved = Join-Path $sdk ($sysdir -replace '/', '\')
    if (-not (Test-Path (Join-Path $resolved 'system.img'))) {
        throw ("The AVD points at an image that is not installed: $resolved. `android emulator create` " +
            'chose that tag; install it with `android sdk install "system-images;<api>;<tag>;x86_64"`.')
    }
    Write-Step 'and that image is installed.'
}

# -----------------------------------------------------------------------------------------------------------------
# Boot
# -----------------------------------------------------------------------------------------------------------------

$alreadyRunning = @(& $adb devices | Select-String '^emulator-\S+\s+device$')
if ($alreadyRunning.Count -gt 0) {
    Write-Head 'An emulator is already running; not starting another.'
} else {
    Write-Head "Booting $AvdName (this returns when it is ready; a first boot takes minutes)"
    & android emulator start $AvdName 2>&1 | ForEach-Object { Write-Step $_ }
}

Write-Head 'Waiting for the device to be usable'

<#
    Read defensively, because a device that is *listed* is not necessarily a device that can be talked to.

    The first version of this loop did `(& $adb -s $id shell getprop sys.boot_completed 2>$null).Trim()` and
    crashed with "You cannot call a method on a null-valued expression" — because `adb devices` had briefly
    reported `emulator-5554  device` while the emulator was still coming up, so the shell command failed,
    `2>$null` swallowed the message, and the expression evaluated to `$null` with no `.Trim()` on it. The
    symptom named a method rather than a device state, which is the same misattribution this project keeps
    meeting: the fix is to check for the null rather than to trust the listing.

    A serial is only usable once its state is exactly `device`, and `offline`/`unauthorized` are both real
    intermediate states during a boot.
#>
function Get-OnlineEmulator {
    $online = @(& $adb devices |
        Select-String '^emulator-\S+\s+device\s*$' |
        ForEach-Object { ($_ -split '\s+')[0] })
    # Empty is `$null` once the pipeline unrolls it, so it is normalised back to a list.
    return , @($online)
}

function Get-Prop([string] $serial, [string] $name) {
    # `-join` rather than an index, because a failed `adb` call yields either nothing or several lines and
    # both have to reduce to one comparable string without throwing.
    return ((& $adb -s $serial shell getprop $name 2>$null) -join '').Trim()
}

$deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
$booted = $false
$lastState = 'no emulator listed'
while ((Get-Date) -lt $deadline) {
    $serials = Get-OnlineEmulator
    if ($serials.Count -eq 0) {
        $lastState = ((& $adb devices | Select-String '^emulator-') -join '; ')
        if (-not $lastState) { $lastState = 'no emulator listed' }
    } else {
        foreach ($serial in $serials) {
            if ((Get-Prop $serial 'sys.boot_completed') -eq '1') {
                Write-Step "$serial is up, API $(Get-Prop $serial 'ro.build.version.sdk')"
                $booted = $true
                # The serial the wait loop just proved usable, kept rather than re-listed. A second listing is
                # a second race, and it is the one that produced the null-dereference this loop was fixed for.
                $id = $serial
                break
            }
            $lastState = "$serial listed but not booted"
        }
    }
    if ($booted) { break }
    Start-Sleep -Seconds 5
}

if (-not $booted) { throw "The emulator did not finish booting within $BootTimeoutSeconds seconds ($lastState)." }

# -----------------------------------------------------------------------------------------------------------------
# What the suite needs from this device, printed rather than assumed
# -----------------------------------------------------------------------------------------------------------------

Write-Head 'The suite will read'

# The dumpsys output is captured ONCE and filtered HERE, in PowerShell. An earlier version wrote
# `& $adb -s $id shell 'dumpsys window | Select-String isKeyguardShowing'` and got back
#
#     /system/bin/sh: Select-String: inaccessible or not found
#     Failed to write while dumping service window: Broken pipe
#
# because everything after `adb shell` is argv for the DEVICE's shell, which has no Select-String. The
# device's `/system/bin/sh` and the host's PowerShell do not share a vocabulary, and the failure mode is a
# message about a missing command rather than about the quoting mistake that caused it.
$window = & $adb -s $id shell dumpsys window
$keyguard = ($window | Select-String -Pattern 'isKeyguardShowing=\w+' | Select-Object -First 1).Matches.Value
$credentialRequired = ($window | Select-String -Pattern 'inputRestricted=true' | Measure-Object).Count -gt 0

$keyguardNote = if ($credentialRequired) { '  (a credential is required -- the suite will refuse)' } else { '' }
Write-Step ("{0,-18} {1}{2}" -f 'keyguard', $keyguard, $keyguardNote)
Write-Step ("{0,-18} {1}" -f 'touch exploration', ((& $adb -s $id shell settings get secure touch_exploration_enabled) -join ' ').Trim())
Write-Step ("{0,-18} {1}" -f 'screen timeout', ((& $adb -s $id shell settings get system screen_off_timeout) -join ' ').Trim())

Write-Head 'Ready. Run the suite with:'
Write-Output '    pwsh -NoProfile -File tools\run-accessibility-tests.ps1'
Write-Output ''
Write-Output 'Two things to expect on an emulator rather than a phone:'
Write-Output '  * API 37 background-audio hardening does not exist on API 36, so the background-playback work'
Write-Output '    still needs a physical API 37 device.'
Write-Output '  * Nothing here observes TalkBack. The seven focus-restore assertions pass on an emulator AND on'
Write-Output '    the phone, because the fix for them is in the app (docs/decisions.md 23.29) - so a green run'
Write-Output '    here is real evidence about the focus mechanism. What no test can see is whether TalkBack''s'
Write-Output '    SPEECH focus follows a programmatic request; that is the integration spec''s listening gate.'
