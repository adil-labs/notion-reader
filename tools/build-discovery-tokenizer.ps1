#requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$OutputDirectory,
    [switch]$HostOnly,
    [string[]]$Abis = @('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86')
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$manifest = Join-Path $repoRoot 'native/discovery-tokenizer/Cargo.toml'
$output = [IO.Path]::GetFullPath($OutputDirectory)
$env:CARGO_TARGET_DIR = Join-Path $output 'cargo'
$env:TOKENIZERS_PARALLELISM = 'false'
if ($HostOnly) {
    & cargo build --locked --release --target x86_64-pc-windows-msvc --manifest-path $manifest
    if ($LASTEXITCODE -ne 0) { throw 'Host tokenizer build failed' }
    $null = New-Item -ItemType Directory -Force -Path (Join-Path $output 'host')
    Copy-Item -LiteralPath (Join-Path $env:CARGO_TARGET_DIR 'x86_64-pc-windows-msvc/release/discovery_tokenizer.dll') -Destination (Join-Path $output 'host/discovery_tokenizer.dll')
    exit 0
}
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
if (-not $sdk) { throw 'Set ANDROID_HOME to the Android SDK directory' }
# NDK r29 links 16 KiB-compatible native libraries; API 26 matches this app's minimum.
$bin = Join-Path $sdk 'ndk/29.0.13846066/toolchains/llvm/prebuilt/windows-x86_64/bin'
if (-not (Test-Path -LiteralPath $bin)) { throw 'Install NDK 29.0.13846066 with android sdk install' }
$targets = @{
    'arm64-v8a' = @('aarch64-linux-android','aarch64-linux-android26-clang.cmd')
    'armeabi-v7a' = @('armv7-linux-androideabi','armv7a-linux-androideabi26-clang.cmd')
    'x86_64' = @('x86_64-linux-android','x86_64-linux-android26-clang.cmd')
    'x86' = @('i686-linux-android','i686-linux-android26-clang.cmd')
}
$installed = @(& rustup target list --installed)
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Rust targets' }
foreach ($abi in $Abis) {
    if (-not $targets.ContainsKey($abi)) { throw "Unsupported ABI: $abi" }
    $target, $clang = $targets[$abi]
    if ($target -cnotin $installed) {
        & rustup target add $target
        if ($LASTEXITCODE -ne 0) { throw "Cannot install Rust target $target" }
    }
    $linkerVariable = 'CARGO_TARGET_' + $target.Replace('-','_').ToUpperInvariant() + '_LINKER'
    [Environment]::SetEnvironmentVariable($linkerVariable, (Join-Path $bin $clang), 'Process')
    & cargo build --locked --release --target $target --manifest-path $manifest
    if ($LASTEXITCODE -ne 0) { throw "Android tokenizer build failed for $abi" }
    $destination = Join-Path $output "jniLibs/$abi"
    $null = New-Item -ItemType Directory -Force -Path $destination
    Copy-Item -LiteralPath (Join-Path $env:CARGO_TARGET_DIR "$target/release/libdiscovery_tokenizer.so") -Destination $destination
}
