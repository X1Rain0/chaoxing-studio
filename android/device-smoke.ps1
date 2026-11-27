[CmdletBinding()]
param(
    [string]$Serial,
    [ValidateRange(1, 30)]
    [int]$WaitSeconds = 4
)

$ErrorActionPreference = 'Stop'
$packageId = 'com.x1rain0.chaoxingstudio'
$sdkRoot = if ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} elseif ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} else {
    Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'

if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw "adb.exe not found at '$adb'. Install Android platform-tools first."
}
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
    throw "Debug APK not found at '$apk'. Build it with .\gradlew.bat :app:assembleDebug first."
}

$deviceOutput = @(& $adb devices -l 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw "adb devices failed: $($deviceOutput -join ' ')"
}
$deviceRows = @($deviceOutput | Select-Object -Skip 1 | Where-Object { $_ -match '^\s*\S+\s+(device|unauthorized|offline)(\s|$)' })
$authorizedRows = @($deviceRows | Where-Object { $_ -match '^\s*\S+\s+device(\s|$)' })

if ($Serial) {
    $escapedSerial = [regex]::Escape($Serial)
    $selectedRows = @($authorizedRows | Where-Object { $_ -match "^\s*$escapedSerial\s+device(\s|$)" })
    if ($selectedRows.Count -ne 1) {
        $seen = if ($deviceRows.Count) { $deviceRows -join '; ' } else { 'none' }
        throw "Device '$Serial' is not authorized/online. adb reports: $seen"
    }
    $device = $Serial
} else {
    if ($authorizedRows.Count -eq 0) {
        $seen = if ($deviceRows.Count) { $deviceRows -join '; ' } else { 'none' }
        throw "No authorized Android device found. Start an Android emulator or connect a phone. For physical phones, enable USB debugging and accept its RSA prompt. adb reports: $seen"
    }
    if ($authorizedRows.Count -gt 1) {
        $seen = @($authorizedRows | ForEach-Object { ($_ -split '\s+')[0] }) -join ', '
        throw "More than one device is connected ($seen). Rerun with -Serial <device-id>."
    }
    $device = ($authorizedRows[0] -split '\s+')[0]
}

$safeSerial = $device -replace '[^A-Za-z0-9._-]', '_'
$outDir = Join-Path $PSScriptRoot 'build\device-smoke'
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
$transcript = Join-Path $outDir "run-$safeSerial.txt"
$screenshot = Join-Path $outDir "screen-$safeSerial.png"
$deviceScreenshot = '/sdcard/Download/chaoxing-studio-device-smoke.png'

"Device: $device" | Set-Content -LiteralPath $transcript -Encoding utf8
"APK: $apk" | Add-Content -LiteralPath $transcript -Encoding utf8
$deviceProperties = @(
    [pscustomobject]@{ Label = 'Manufacturer'; Name = 'ro.product.manufacturer' },
    [pscustomobject]@{ Label = 'Model'; Name = 'ro.product.model' },
    [pscustomobject]@{ Label = 'Device'; Name = 'ro.product.device' },
    [pscustomobject]@{ Label = 'Android release'; Name = 'ro.build.version.release' },
    [pscustomobject]@{ Label = 'API level'; Name = 'ro.build.version.sdk' },
    [pscustomobject]@{ Label = 'Security patch'; Name = 'ro.build.version.security_patch' }
)
foreach ($property in $deviceProperties) {
    $valueOutput = @(& $adb -s $device shell getprop $property.Name 2>&1)
    $propertyExit = $LASTEXITCODE
    $value = if ($propertyExit -eq 0) { $valueOutput -join ' ' } else { "unavailable (adb exit $propertyExit)" }
    "$($property.Label): $value" | Add-Content -LiteralPath $transcript -Encoding utf8
}

$installOutput = @(& $adb -s $device install -r $apk 2>&1)
$installExit = $LASTEXITCODE
$installOutput | Add-Content -LiteralPath $transcript -Encoding utf8
if ($installExit -ne 0) {
    throw "APK installation failed (adb exit $installExit). See '$transcript'."
}

$launchOutput = @(& $adb -s $device shell am start -W -n "$packageId/.MainActivity" 2>&1)
$launchExit = $LASTEXITCODE
$launchOutput | Add-Content -LiteralPath $transcript -Encoding utf8
if ($launchExit -ne 0) {
    throw "App launch failed (adb exit $launchExit). See '$transcript'."
}

$apiOutput = @(& $adb -s $device shell getprop ro.build.version.sdk 2>&1)
$apiLevel = if ($LASTEXITCODE -eq 0 -and ($apiOutput -join '').Trim() -match '^\d+$') { [int](($apiOutput -join '').Trim()) } else { 0 }
if ($apiLevel -lt 33) {
    $permissionStatus = "not required on API $apiLevel"
} else {
    $permissionOutput = @(& $adb -s $device shell dumpsys package $packageId 2>&1)
    $permissionExit = $LASTEXITCODE
    $permissionLine = $permissionOutput | Where-Object { "$_" -match 'android\.permission\.POST_NOTIFICATIONS:\s*granted=(true|false)' } | Select-Object -First 1
    if ($permissionExit -ne 0) {
        $permissionStatus = "unavailable (adb exit $permissionExit): $($permissionOutput -join ' ')"
    } elseif ($permissionLine -match 'granted=true') {
        $permissionStatus = 'granted'
    } elseif ($permissionLine -match 'granted=false') {
        $permissionStatus = 'denied'
    } else {
        $permissionStatus = 'not listed in package manager output'
    }
}
"POST_NOTIFICATIONS: $permissionStatus" | Add-Content -LiteralPath $transcript -Encoding utf8

Start-Sleep -Seconds $WaitSeconds
& $adb -s $device shell screencap -p $deviceScreenshot
if ($LASTEXITCODE -ne 0) {
    throw 'Device screenshot capture failed.'
}
$pullOutput = @(& $adb -s $device pull $deviceScreenshot $screenshot 2>$null)
$pullExit = $LASTEXITCODE
$pullOutput | Add-Content -LiteralPath $transcript -Encoding utf8
if ($pullExit -ne 0) {
    throw "Could not copy the screenshot from the device. See '$transcript'."
}

$pidOutput = @(& $adb -s $device shell pidof $packageId 2>$null)
$appPid = ($pidOutput -join ' ').Trim()
if ($appPid -match '^\d+$') {
    $appLog = @(& $adb -s $device logcat -d -t 2000 --pid=$appPid -v time 2>&1)
    $appLog | Set-Content -LiteralPath (Join-Path $outDir "logcat-$safeSerial.txt") -Encoding utf8
}

Write-Output "Installed and launched $packageId on $device."
Write-Output "Screenshot: $screenshot"
Write-Output "Run transcript: $transcript"
if ($appPid -match '^\d+$') {
    Write-Output "App-only log: $(Join-Path $outDir "logcat-$safeSerial.txt")"
} else {
    Write-Output 'The app process was not visible to pidof after launch; inspect the screenshot and device crash buffer.'
}
