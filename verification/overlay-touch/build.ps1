$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$output = Join-Path $root 'build\verification\overlay-touch'
$android = 'F:\Android\Sdk\platforms\android-34\android.jar'
$production = Join-Path $root 'app\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes'
$sdkTools = 'F:\Android\Sdk\build-tools\34.0.0'
$env:JAVA_HOME = 'D:\tools\jdk-17'
$libraries = @(Get-ChildItem -LiteralPath "$env:USERPROFILE\.gradle\caches\8.10.2\transforms" -Recurse -Filter "*-api.jar" -File | ForEach-Object FullName)
$libraries += @(Get-ChildItem -LiteralPath "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\androidx.lifecycle\lifecycle-common" -Recurse -Filter '*.jar' -File | ForEach-Object FullName)
$classpath = (@($android, $production) + $libraries) -join ';'
New-Item -ItemType Directory -Force "$output\classes", "$output\dex" | Out-Null
$sources = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' | ForEach-Object FullName)
$sources += Join-Path $PSScriptRoot '..\owner-lifecycle\ProcessCpuSnapshot.java'
& "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -source 17 -target 17 -classpath $classpath -d "$output\classes" @sources
if ($LASTEXITCODE -ne 0) { throw 'Overlay touch compilation failed' }
$inputs = @(Get-ChildItem -LiteralPath "$output\classes" -Recurse -Filter '*.class' | ForEach-Object FullName)
$arguments = @('--min-api', '26', '--lib', $android, '--classpath', $production, '--output', "$output\dex")
foreach ($library in $libraries) { $arguments += @('--classpath', $library) }
# 固定样本工具数量增长后避免Windows命令行长度限制；JDK参数文件仍逐项保留路径。
$javaArguments = @('-Xmx1024M', '-cp', "$sdkTools\lib\d8.jar", 'com.android.tools.r8.D8') + $arguments + $inputs
$argumentFile = Join-Path $output 'd8-arguments.txt'
$quotedArguments = $javaArguments | ForEach-Object { '"' + $_.Replace('\', '/').Replace('"', '\"') + '"' }
[IO.File]::WriteAllLines($argumentFile, [string[]]$quotedArguments, [Text.UTF8Encoding]::new($false))
& "$env:JAVA_HOME\bin\java.exe" "@$argumentFile"
if ($LASTEXITCODE -ne 0) { throw 'Overlay touch dex failed' }
& "$sdkTools\aapt.exe" package -f -M "$PSScriptRoot\AndroidManifest.xml" -I $android -F "$output\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Overlay touch packaging failed' }
Push-Location "$output\dex"
try { & "$sdkTools\aapt.exe" add -f "$output\unsigned.apk" classes.dex } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'Overlay touch dex packaging failed' }
& "$sdkTools\apksigner.bat" sign --ks 'C:\Users\wangqun\.android\debug.keystore' --ks-pass pass:android --key-pass pass:android --out "$output\overlay-touch.apk" "$output\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Overlay touch signing failed' }
