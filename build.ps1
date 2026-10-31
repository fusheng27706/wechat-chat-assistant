param(
    [switch]$Install,
    [string]$DeviceSerial,
    [switch]$DirectNetwork
)

$ErrorActionPreference = 'Stop'
$gradleCommand = Get-Command gradle -ErrorAction SilentlyContinue | Select-Object -First 1
$gradleLauncher = $gradleCommand.Source
if (-not $gradleLauncher) {
    $gradleCache = Join-Path $env:USERPROFILE '.gradle\wrapper\dists\gradle-8.10.2-bin'
    $gradleLauncher = Get-ChildItem -LiteralPath $gradleCache -Filter gradle.bat -File -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $gradleLauncher) {
    throw 'Install Gradle 8.10.2 or provide it in the Gradle wrapper cache.'
}

Push-Location $PSScriptRoot
try {
    $gradleArguments = @('--no-daemon', 'assembleDebug')
    if ($DirectNetwork) {
        $gradleArguments += @('-Dhttp.proxyHost=', '-Dhttps.proxyHost=', '-Dhttp.proxyPort=0', '-Dhttps.proxyPort=0')
    }
    & $gradleLauncher @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
    $apkPath = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
    Write-Output $apkPath
    if ($Install) {
        $androidSdk = $env:ANDROID_HOME
        if (-not $androidSdk) { $androidSdk = $env:ANDROID_SDK_ROOT }
        if (-not $androidSdk) { throw 'Set ANDROID_HOME to the Android SDK directory.' }
        $adbLauncher = Join-Path $androidSdk 'platform-tools\adb.exe'
        $deviceArguments = @()
        if ($DeviceSerial) { $deviceArguments = @('-s', $DeviceSerial) }
        & $adbLauncher @deviceArguments install -r $apkPath
        if ($LASTEXITCODE -ne 0) { throw 'APK installation failed.' }
    }
} finally {
    Pop-Location
}
