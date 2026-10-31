$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$output = Join-Path $root 'build\verification\tight-ocr'
& (Join-Path $PSScriptRoot 'test-tight-bounds.ps1') | Out-Null
$generated = Join-Path $root 'build\verification\tight-bounds-candidate\TightChatLayout.java'
$stage = Join-Path $output ('compile-' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $stage 'classes'
$dex = Join-Path $stage 'dex'
$android = 'F:\Android\Sdk\platforms\android-34\android.jar'
$production = Join-Path $root 'app\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes'
$sdkTools = 'F:\Android\Sdk\build-tools\34.0.0'
$env:JAVA_HOME = 'D:\tools\jdk-17'
$libraries = @(Get-ChildItem -LiteralPath "$env:USERPROFILE\.gradle\caches\8.10.2\transforms" -Recurse -Filter '*-api.jar' -File | ForEach-Object FullName)
$libraries += @(Get-ChildItem -LiteralPath "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\androidx.lifecycle\lifecycle-common" -Recurse -Filter '*.jar' -File | ForEach-Object FullName)
$classpath = (@($android, $production) + $libraries) -join ';'
New-Item -ItemType Directory -Force $classes, $dex | Out-Null
$sources = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' | ForEach-Object FullName)
$sources += Join-Path $PSScriptRoot '..\owner-lifecycle\ProcessCpuSnapshot.java'
$sources += $generated
$sources += Join-Path $PSScriptRoot 'tight-bounds-candidate\TightBubbleBounds.java'
$sources += Join-Path $PSScriptRoot 'tight-bounds-candidate\TightBoundsOcrSmoke.java'
& "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -source 17 -target 17 -classpath $classpath -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Tight OCR compilation failed' }
$inputs = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | ForEach-Object FullName)
$arguments = @('--min-api', '26', '--lib', $android, '--classpath', $production, '--output', $dex)
foreach ($library in $libraries) { $arguments += @('--classpath', $library) }
# 独立目录路径更长，用JDK参数文件避免Windows命令行长度上限。
$javaArguments = @('-Xmx1024M', '-cp', "$sdkTools\lib\d8.jar", 'com.android.tools.r8.D8') + $arguments + $inputs
$argumentFile = Join-Path $stage 'd8-arguments.txt'
$argumentLines = @($javaArguments | ForEach-Object { '"' + $_.Replace('\', '/').Replace('"', '\"') + '"' })
[IO.File]::WriteAllLines($argumentFile, $argumentLines, [Text.UTF8Encoding]::new($false))
& "$env:JAVA_HOME\bin\java.exe" ("@" + $argumentFile)
if ($LASTEXITCODE -ne 0) { throw 'Tight OCR dex failed' }
# 独立输出与manifest，避免把未接入候选混进默认临时包。
$manifest = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'AndroidManifest.xml')).Replace('</manifest>',
    '<instrumentation android:name="com.codextemp.wechatcaptureprobe.TightBoundsOcrSmoke" android:targetPackage="com.codextemp.wechatcaptureprobe" /></manifest>')
[IO.File]::WriteAllText("$output\AndroidManifest.xml", $manifest, [Text.UTF8Encoding]::new($false))
& "$sdkTools\aapt.exe" package -f -M "$output\AndroidManifest.xml" -I $android -F "$output\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Tight OCR packaging failed' }
Push-Location $dex
try { & "$sdkTools\aapt.exe" add -f "$output\unsigned.apk" classes.dex } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'Tight OCR dex packaging failed' }
& "$sdkTools\apksigner.bat" sign --ks 'C:\Users\wangqun\.android\debug.keystore' --ks-pass pass:android --key-pass pass:android --out "$output\tight-ocr.apk" "$output\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Tight OCR signing failed' }
