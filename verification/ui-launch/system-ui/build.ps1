$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$output = Join-Path $workspace 'build\verification\ui-launch\installer'
$sdk = 'F:\Android\Sdk'
$java = 'D:\tools\jdk-17\bin'
$android = "$sdk\platforms\android-34\android.jar"
$ui = "$sdk\platforms\android-34\uiautomator.jar"
$base = "$sdk\platforms\android-34\optional\android.test.base.jar"
New-Item -ItemType Directory -Force "$output\classes", "$output\dex" | Out-Null
& "$java\javac.exe" -encoding UTF-8 -source 17 -target 17 -classpath "$android;$ui;$base" -d "$output\classes" "$PSScriptRoot\InstallerUi.java"
if ($LASTEXITCODE -ne 0) { throw 'Installer helper compilation failed' }
$inputs = @(Get-ChildItem -LiteralPath "$output\classes" -Recurse -Filter '*.class' -File | ForEach-Object FullName)
& "$java\java.exe" -cp "$sdk\build-tools\34.0.0\lib\d8.jar" com.android.tools.r8.D8 --min-api 26 --lib $android --classpath $ui --classpath $base --output "$output\dex" @inputs
if ($LASTEXITCODE -ne 0) { throw 'Installer helper dex failed' }
& "$java\jar.exe" cf "$output\installer-ui.jar" -C "$output\dex" classes.dex
if ($LASTEXITCODE -ne 0) { throw 'Installer helper packaging failed' }
