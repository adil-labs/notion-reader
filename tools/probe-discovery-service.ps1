#requires -Version 7.0
<# Installs the current debug APK and observes the real application binding, worker thread and recreation. #>
param([string]$Device = '')
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$package = 'com.example.notionreader'
$adbExecutable = (Get-Command adb -CommandType Application).Source
$devices = @(& $adbExecutable devices | Where-Object { $_ -match '\sdevice$' })
if ($Device) { $devices = @($devices | Where-Object { ($_ -split '\s+')[0] -ceq $Device }) }
if ($devices.Count -ne 1) { throw 'Select exactly one usable Android device with -Device when several are attached' }
$probeDevice = ($devices[0] -split '\s+')[0]
function adb { & $script:adbExecutable -s $script:probeDevice @args }
$artifacts = Join-Path $repo 'build/discovery-service-probe'
$null = New-Item -ItemType Directory -Force $artifacts
function Service-Token([string]$dump) {
    $records = [regex]::Matches($dump, '(?m)^\s*\* ServiceRecord\{(\S+) [^\r\n]*com\.example\.notionreader/\.DiscoveryService(?:\s|\})')
    if ($records.Count -ne 1 -or $dump -notmatch 'hasBound=true') { throw 'Expected one application-bound Discovery service' }
    return $records[0].Groups[1].Value
}
function Get-Service {
    $dump = (adb shell dumpsys activity services $package) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read Android services' }
    $null = Service-Token $dump
    return $dump
}
function Get-Layout([string]$name) {
    $focus = (adb shell dumpsys window) -join "`n"
    if ($focus -notmatch 'mCurrentFocus=.*com\.example\.notionreader/') { throw 'The reader is not focused; refusing to interpret another app or the keyguard' }
    $path = Join-Path $artifacts "$name.json"
    android layout --flat --device=$script:probeDevice --output=$path | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Android layout failed' }
    $windows = Get-Content $path -Raw | ConvertFrom-Json
    $appWindow = @($windows | Where-Object { $_.type -eq 'APPLICATION' -and $_.focused })
    if ($appWindow.Count -ne 1) { throw 'Expected one focused application window' }
    return @($appWindow[0].content)
}
# A missing subject must be rejected; an empty dump is never affirmative evidence.
$rejected = $false
try { $null = Service-Token '' } catch { $rejected = $true }
if (-not $rejected) { throw 'Service probe self-check failed' }

adb install -r (Join-Path $repo 'app/build/outputs/apk/debug/app-debug.apk') | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'APK install failed' }
adb shell am start -W -n "$package/.MainActivity" | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'MainActivity launch failed' }
$before = Get-Service
$token = Service-Token $before
$processId = ((adb shell pidof $package) -join '').Trim()
if ($processId -notmatch '^\d+$') { throw 'Expected one reader process' }
$layout = @(Get-Layout 'before')
$discover = @($layout | Where-Object { $_.text -ceq 'Discover' })
if ($discover.Count -ne 1 -or $discover[0].center -notmatch '^\[(\d+),(\d+)\]$') { throw 'Cannot locate the Discover tab in the actual tree' }
adb shell input tap $Matches[1] $Matches[2]
Start-Sleep -Milliseconds 500
$layout = @(Get-Layout 'discover')
if (-not ($layout | Where-Object { $_.text -match 'Search your cached Notes|Set up Discovery|Discovery model' })) {
    throw 'Discover did not respond with its search/setup surface'
}
$font = ((adb shell settings get system font_scale) -join '').Trim()
$newFont = if ($font -eq '1.05') { '1.0' } else { '1.05' }
$eventsBefore = @((adb logcat -b events -d -s wm_relaunch_resume_activity wm_relaunch_activity) | Where-Object { $_ -match 'com\.example\.notionreader/\.MainActivity' }).Count
try {
    adb shell settings put system font_scale $newFont
    Start-Sleep -Milliseconds 1000
    $layout = @(Get-Layout 'after-recreation')
    $eventsAfter = @((adb logcat -b events -d -s wm_relaunch_resume_activity wm_relaunch_activity) | Where-Object { $_ -match 'com\.example\.notionreader/\.MainActivity' }).Count
    if ($eventsAfter -le $eventsBefore) { throw 'No observed Activity relaunch; recreation claim is unproven' }
    if ((Service-Token (Get-Service)) -cne $token) { throw 'Activity recreation replaced the Discovery service' }
    adb shell input keyevent KEYCODE_HOME
    Start-Sleep -Milliseconds 300
    if ((Service-Token (Get-Service)) -cne $token) { throw 'Backgrounding replaced or lost the application service binding' }
    adb shell am start -W -n "$package/.MainActivity" | Out-Host
    $null = Get-Layout 'returned'
    $workerLog = (adb logcat -d "--pid=$processId" -s DiscoveryWorker) -join "`n"
    if (@([regex]::Matches($workerLog, 'started mainThread=false')).Count -ne 1 -or
        $workerLog -match 'started mainThread=true|DiscoveryWorker: stopped') { throw 'Expected one continuously active worker off the main thread' }
    $before | Set-Content (Join-Path $artifacts 'service-before.txt')
    (Get-Service) | Set-Content (Join-Path $artifacts 'service-after.txt')
    $workerLog | Set-Content (Join-Path $artifacts 'worker.txt')
    Write-Output "PASSED: $($devices[0]); one bound service and off-main worker survived observed Activity relaunch/background/return; Discover responded."
    Write-Output 'Scope: no model-loaded large-library latency, physical playback contention or human listening claim.'
} finally {
    if ($font -eq 'null' -or -not $font) { adb shell settings delete system font_scale | Out-Null }
    else { adb shell settings put system font_scale $font }
}
