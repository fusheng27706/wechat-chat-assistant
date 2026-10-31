$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$output = Join-Path $root 'build\verification\model-check'
$android = 'D:\android-sdk-windows\platforms\android-34\android.jar'
$production = Join-Path $root 'app\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes'
$sdkTools = 'D:\android-sdk-windows\build-tools\34.0.0'
$env:JAVA_HOME = 'D:\tools\jdk-17'
if (-not (Test-Path $production)) { throw "Production classes missing: $production" }
New-Item -ItemType Directory -Force "$output\classes", "$output\dex" | Out-Null
Get-ChildItem -LiteralPath "$output\classes", "$output\dex" -File -Recurse -ErrorAction SilentlyContinue | Remove-Item -Force
$sources = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' | ForEach-Object FullName)
$classpath = "$android;$production"
& "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -source 17 -target 17 -classpath $classpath -d "$output\classes" @sources
if ($LASTEXITCODE -ne 0) { throw 'Model connect compilation failed' }
$inputs = @(Get-ChildItem -LiteralPath "$output\classes" -Recurse -Filter '*.class' | ForEach-Object FullName)
$arguments = @('--min-api', '26', '--lib', $android, '--classpath', $production, '--output', "$output\dex") + $inputs
$javaArguments = @('-Xmx1024M', '-cp', "$sdkTools\lib\d8.jar", 'com.android.tools.r8.D8') + $arguments
$argumentFile = Join-Path $output 'd8-arguments.txt'
$quotedArguments = $javaArguments | ForEach-Object { '"' + $_.Replace('\', '/').Replace('"', '\"') + '"' }
[IO.File]::WriteAllLines($argumentFile, [string[]]$quotedArguments, [Text.UTF8Encoding]::new($false))
& "$env:JAVA_HOME\bin\java.exe" "@$argumentFile"
if ($LASTEXITCODE -ne 0) { throw 'Model connect dex failed' }
& "$sdkTools\aapt.exe" package -f -M "$PSScriptRoot\AndroidManifest.xml" -I $android -F "$output\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Model connect packaging failed' }
Push-Location "$output\dex"
try { & "$sdkTools\aapt.exe" add -f "$output\unsigned.apk" classes.dex } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'Model connect dex packaging failed' }
& "$sdkTools\apksigner.bat" sign --ks 'C:\Users\wangqun\.android\debug.keystore' --ks-pass pass:android --key-pass pass:android --out "$output\model-check.apk" "$output\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Model connect signing failed' }
"BUILT $output\model-check.apk"
