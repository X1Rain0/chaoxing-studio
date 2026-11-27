[CmdletBinding()]
param(
    [switch]$Arm64Only
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sdk = $env:ANDROID_HOME
if ([string]::IsNullOrWhiteSpace($sdk)) { $sdk = $env:ANDROID_SDK_ROOT }
if ([string]::IsNullOrWhiteSpace($sdk)) {
    $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
if (-not (Test-Path $sdk)) {
    throw "Android SDK not found at '$sdk'. Install Android SDK Platform 36 and set ANDROID_HOME or ANDROID_SDK_ROOT."
}

$ndk = $env:ANDROID_NDK_HOME
if ([string]::IsNullOrWhiteSpace($ndk) -or -not (Test-Path $ndk)) {
    $ndkRoot = Join-Path $sdk 'ndk'
    if (Test-Path $ndkRoot) {
        $ndk = Get-ChildItem -Path $ndkRoot -Directory |
            Sort-Object { [version]$_.Name } -Descending |
            Select-Object -First 1 -ExpandProperty FullName
    }
}
if ([string]::IsNullOrWhiteSpace($ndk) -or -not (Test-Path $ndk)) {
    throw "Android NDK not found. Install it with Android Studio SDK Manager or set ANDROID_NDK_HOME."
}

$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_NDK_HOME = $ndk
$goBin = (& go env GOBIN).Trim()
if ([string]::IsNullOrWhiteSpace($goBin)) {
    $goBin = Join-Path ((& go env GOPATH).Trim()) 'bin'
}
$env:PATH = "$goBin;$env:PATH"
$target = if ($Arm64Only) { 'android/arm64' } else { 'android' }
$outDir = Join-Path $PSScriptRoot 'app\libs'
$outFile = Join-Path $outDir 'mobilebridge.aar'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Push-Location $repoRoot
try {
    Write-Host "Building Go AAR for $target using SDK '$sdk' and NDK '$ndk'..."
    if (-not (Test-Path (Join-Path $goBin 'gobind.exe'))) {
        go tool gomobile init
        if ($LASTEXITCODE -ne 0) { throw "gomobile init failed with exit code $LASTEXITCODE" }
    }
    go tool gomobile bind -target $target -androidapi 21 -javapkg com.x1rain0.chaoxingstudio.bridge -ldflags '-w -extldflags=-Wl,-z,max-page-size=16384' -o $outFile ./mobilebridge
    if ($LASTEXITCODE -ne 0) { throw "gomobile bind failed with exit code $LASTEXITCODE" }
    Write-Host "Created $outFile"
} finally {
    Pop-Location
}
