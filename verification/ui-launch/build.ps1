$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$output = Join-Path $workspace 'build\verification\ui-launch'
$android = 'F:\Android\Sdk\platforms\android-34\android.jar'
$production = Join-Path $workspace 'app\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes'
$buildTools = 'F:\Android\Sdk\build-tools\34.0.0'
$java = 'D:\tools\jdk-17\bin'
$libraries = @(Get-ChildItem -LiteralPath "$env:USERPROFILE\.gradle\caches\8.10.2\transforms" -Recurse -Filter '*-api.jar' -File | ForEach-Object FullName)
$classpath = (@($android, $production) + $libraries) -join ';'
New-Item -ItemType Directory -Force "$output\classes", "$output\dex" | Out-Null
& "$java\javac.exe" -encoding UTF-8 -source 17 -target 17 -classpath $classpath -d "$output\classes" "$PSScriptRoot\UiFlowSmoke.java"
if ($LASTEXITCODE -ne 0) { throw 'UI verifier compilation failed' }
$inputs = @(Get-ChildItem -LiteralPath "$output\classes" -Recurse -Filter '*.class' | ForEach-Object FullName)
& "$java\java.exe" '-Xmx1024M' -cp "$buildTools\lib\d8.jar" com.android.tools.r8.D8 --min-api 26 --lib $android --classpath $production --output "$output\dex" @inputs
if ($LASTEXITCODE -ne 0) { throw 'UI verifier dex failed' }
& "$buildTools\aapt.exe" package -f -M "$PSScriptRoot\AndroidManifest.xml" -I $android -F "$output\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw 'UI verifier packaging failed' }
Push-Location "$output\dex"
try { & "$buildTools\aapt.exe" add -f "$output\unsigned.apk" classes.dex } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'UI verifier dex packaging failed' }
& "$buildTools\apksigner.bat" sign --ks "$env:USERPROFILE\.android\debug.keystore" --ks-pass pass:android --key-pass pass:android --out "$output\ui-launch.apk" "$output\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw 'UI verifier signing failed' }
