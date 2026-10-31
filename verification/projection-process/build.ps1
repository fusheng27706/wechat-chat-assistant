$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$output = Join-Path $workspace 'build\verification\projection-process'
$android = 'F:\Android\Sdk\platforms\android-34\android.jar'
$buildTools = 'F:\Android\Sdk\build-tools\34.0.0'
$env:JAVA_HOME = 'D:\tools\jdk-17'
New-Item -ItemType Directory -Force "$output\classes", "$output\dex" | Out-Null
$sources = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' | ForEach-Object FullName)
& "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -source 17 -target 17 -classpath $android -d "$output\classes" @sources
if ($LASTEXITCODE -ne 0) { throw 'Fixture compilation failed' }
$inputs = @(Get-ChildItem -LiteralPath "$output\classes" -Recurse -Filter '*.class' | ForEach-Object FullName)
& "$buildTools\d8.bat" --min-api 26 --lib $android --output "$output\dex" @inputs
if ($LASTEXITCODE -ne 0) { throw 'Fixture dex failed' }
& "$buildTools\aapt.exe" package -f -M "$PSScriptRoot\AndroidManifest.xml" -I $android -F "$output\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Fixture packaging failed' }
Push-Location "$output\dex"
try {
    & "$buildTools\aapt.exe" add -f "$output\unsigned.apk" classes.dex
    if ($LASTEXITCODE -ne 0) { throw 'Fixture dex packaging failed' }
} finally { Pop-Location }
& "$buildTools\apksigner.bat" sign --ks 'C:\Users\wangqun\.android\debug.keystore' --ks-pass pass:android --key-pass pass:android --out "$output\projection-process.apk" "$output\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Fixture signing failed' }
Write-Output "$output\projection-process.apk"
