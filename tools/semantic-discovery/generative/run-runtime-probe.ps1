#requires -Version 7.0
<# Public-input developer probe only. Never touches Notion Reader data or settings. #>
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9:._-]+$')][string]$Device,
    [ValidateSet('CPU','GPU')][string]$Backend = 'CPU',
    [ValidatePattern('^[a-z0-9-]+$')][string]$Case = 'different-wording',
    [string]$Model = 'build/generative-discovery/model/gemma-4-E2B-it.litertlm',
    [string]$OutputDirectory = 'build/generative-discovery/results',
    [switch]$SkipStage,
    [ValidateSet(512,1024,2048)][int]$ContextTokens = 2048,
    [ValidateRange(64,512)][int]$OutputTokens = 512,
    [ValidateRange(0,10000)][int]$CancelAfterMillis = 0,
    [ValidateSet(2,4,6)][int]$CpuThreads = 2,
    [ValidateSet(3,4,5,6)][int]$PromptVersion = 3,
    [ValidateRange(30,900)][int]$TimeoutSeconds = 300
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$package = 'com.example.generativediscoveryprobe'
$remoteDirectory = "/sdcard/Android/data/$package/files"
$hash = '181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c'
function Invoke-ProbeAdb([string[]]$Arguments) {
    $result = & adb -s $Device @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Arguments -join ' ')`n$($result -join "`n")" }
    return $result
}
$null = Invoke-ProbeAdb -Arguments @('get-state')
if (-not $SkipStage) {
    $localModel = Join-Path $repo $Model
    if ((Get-Item -LiteralPath $localModel).Length -ne 2588147712L -or
        (Get-FileHash -LiteralPath $localModel -Algorithm SHA256).Hash.ToLowerInvariant() -cne $hash) {
        throw 'Host model integrity check failed'
    }
    $apk = Join-Path $PSScriptRoot 'runtime-probe/build/outputs/apk/debug/GenerativeDiscoveryProbe-debug.apk'
    $null = Invoke-ProbeAdb -Arguments @('install','-r',$apk)
    $null = Invoke-ProbeAdb -Arguments @('shell','mkdir','-p',$remoteDirectory)
    $null = Invoke-ProbeAdb -Arguments @('shell','am','force-stop',$package)
    $null = Invoke-ProbeAdb -Arguments @('push',$localModel,"$remoteDirectory/gemma-4-E2B-it.litertlm")
}
$deviceHash = Invoke-ProbeAdb -Arguments @('shell','sha256sum',"$remoteDirectory/gemma-4-E2B-it.litertlm")
if (($deviceHash -join ' ') -notmatch "^$hash\s") { throw 'Device model integrity check failed' }
$remoteReport = "$remoteDirectory/probe-$Backend-$Case.json"
$null = Invoke-ProbeAdb -Arguments @('shell','am','force-stop',$package)
$null = Invoke-ProbeAdb -Arguments @('shell','rm','-f',$remoteReport)
$null = Invoke-ProbeAdb -Arguments @('shell','am','start','-n',"$package/.ProbeActivity",'--es','backend',$Backend,'--es','case',$Case,
    '--ei','contextTokens',"$ContextTokens",'--ei','outputTokens',"$OutputTokens",'--ei','cancelAfterMillis',"$CancelAfterMillis",'--ei','cpuThreads',"$CpuThreads",'--ei','promptVersion',"$PromptVersion")
$started = [DateTime]::UtcNow
while (([DateTime]::UtcNow - $started).TotalSeconds -lt $TimeoutSeconds) {
    Start-Sleep -Seconds 2
    $lines = & adb -s $Device shell cat $remoteReport 2>$null
    $report = $null
    if ($LASTEXITCODE -eq 0) {
        try { $report = ($lines -join "`n") | ConvertFrom-Json } catch { }
    }
    if ($report -and $report.status -in @('completed','failed')) {
        $directory = Join-Path $repo $OutputDirectory
        $null = New-Item -ItemType Directory -Force -Path $directory
        $destination = Join-Path $directory "probe-$Backend-$Case-c$ContextTokens-o$OutputTokens-cancel$CancelAfterMillis.json"
        $null = Invoke-ProbeAdb -Arguments @('pull',$remoteReport,$destination)
        if ($report.status -ne 'completed') { throw "Probe failed; inspect $destination" }
        Write-Output "Completed public-input probe: $destination"
        return
    }
    $null = & adb -s $Device shell pidof $package
    if ($LASTEXITCODE -ne 0) { throw 'Probe process exited before a terminal report; inspect device crash logs' }
}
throw 'Probe observation deadline reached. Inspect the existing process/report before stopping or restarting it.'
