# Builds the crypto-core cdylib for the host JVM and (if cargo-ndk is on PATH)
# for Android ABIs. PowerShell-native equivalent of build-crypto.sh.
#
# Usage:
#   .\scripts\build-crypto.ps1            # host + Android (if NDK available)
#   .\scripts\build-crypto.ps1 host       # host only
#   .\scripts\build-crypto.ps1 android    # Android only

[CmdletBinding()]
param(
    [ValidateSet('host', 'android', 'all')]
    [string]$Mode = 'all'
)

$ErrorActionPreference = 'Stop'

$RepoRoot     = Split-Path -Parent $PSScriptRoot
$CryptoDir    = Join-Path $RepoRoot 'crypto-core'
$IssuerNative = Join-Path $RepoRoot 'issuer-backend\build\native'
$JniLibs      = Join-Path $RepoRoot 'holder-android\StudentZK\app\src\main\jniLibs'

function Build-Host {
    Write-Host "==> Building crypto-core for host JVM (Windows)" -ForegroundColor Cyan
    Push-Location $CryptoDir
    try { cargo build --release } finally { Pop-Location }

    New-Item -ItemType Directory -Path $IssuerNative -Force | Out-Null
    $lib = if ($IsMacOS)   { 'libstudentzkp_crypto.dylib' }
           elseif ($IsLinux) { 'libstudentzkp_crypto.so' }
           else              { 'studentzkp_crypto.dll' }
    $src = Join-Path $CryptoDir "target\release\$lib"
    $dst = Join-Path $IssuerNative $lib
    Copy-Item -Path $src -Destination $dst -Force
    Write-Host "    -> $dst"
}

function Build-Android {
    if (-not (Get-Command cargo-ndk -ErrorAction SilentlyContinue)) {
        Write-Warning "cargo-ndk not on PATH - skipping Android build."
        Write-Warning "Install with: cargo install cargo-ndk"
        return
    }
    if (-not ($env:ANDROID_NDK_HOME -or $env:NDK_HOME -or $env:ANDROID_NDK_ROOT)) {
        Write-Warning "ANDROID_NDK_HOME / NDK_HOME / ANDROID_NDK_ROOT not set - skipping Android build."
        return
    }

    # 64-bit ABIs only. The JNA bridge maps size_t to Java long (8 bytes); 32-bit
    # ABIs (armv7, x86) would produce loadable .so files whose BBS+ entry points
    # would corrupt the call frame.
    Write-Host "==> Cross-compiling crypto-core for Android (arm64-v8a, x86_64)" -ForegroundColor Cyan
    New-Item -ItemType Directory -Path $JniLibs -Force | Out-Null
    Push-Location $CryptoDir
    try {
        cargo ndk `
            --target aarch64-linux-android `
            --target x86_64-linux-android `
            --output-dir $JniLibs `
            build --release
    } finally { Pop-Location }

    Write-Host "    -> $JniLibs\{arm64-v8a,x86_64}\libstudentzkp_crypto.so"
}

switch ($Mode) {
    'host'    { Build-Host }
    'android' { Build-Android }
    'all'     { Build-Host; Build-Android }
}

Write-Host "Done." -ForegroundColor Green
