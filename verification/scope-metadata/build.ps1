$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$output = Join-Path $workspace 'build\verification\scope-metadata'
$android = 'F:\Android\Sdk\platforms\android-34\android.jar'
$production = Join-Path $workspace 'app\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes'
$tools = 'F:\Android\Sdk\build-tools\34.0.0'
$env:JAVA_HOME = 'D:\tools\jdk-17'
$libraries = @(rg --files 'C:\Users\wangqun\.gradle\caches\8.10.2\transforms' -g '*-api.jar')
$classpath = (@($android, $production) + $libraries) -join ';'
New-Item -ItemType Directory -Force "$output\classes", "$output\dex" | Out-Null
$sources = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' | Where-Object Name -NotLike '*Regression.java' | ForEach-Object FullName)
& "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -source 17 -target 17 -classpath $classpath -d "$output\classes" @sources
if ($LASTEXITCODE -ne 0) { throw 'Scope metadata probe compilation failed' }
$inputs = @(Get-ChildItem -LiteralPath "$output\classes" -Recurse -Filter '*.class' | Where-Object Name -NotLike '*Regression.class' | ForEach-Object FullName)
$arguments = @('--min-api', '26', '--lib', $android, '--classpath', $production, '--output', "$output\dex")
foreach ($library in $libraries) { $arguments += @('--classpath', $library) }
& "$tools\d8.bat" @arguments @inputs
if ($LASTEXITCODE -ne 0) { throw 'Scope metadata probe dex failed' }
& "$tools\aapt.exe" package -f -M "$PSScriptRoot\AndroidManifest.xml" -I $android -F "$output\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Scope metadata probe packaging failed' }
Push-Location "$output\dex"
try { & "$tools\aapt.exe" add -f "$output\unsigned.apk" classes.dex } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'Scope metadata probe dex packaging failed' }
& "$tools\apksigner.bat" sign --ks 'C:\Users\wangqun\.android\debug.keystore' --ks-pass pass:android --key-pass pass:android --out "$output\scope-metadata.apk" "$output\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Scope metadata probe signing failed' }
