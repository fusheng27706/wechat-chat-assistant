$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$output = Join-Path $root 'build\verification\analysis-events'
$android = 'F:\Android\Sdk\platforms\android-34\android.jar'
$production = Join-Path $root 'app\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes'
$sdkTools = 'F:\Android\Sdk\build-tools\34.0.0'
$env:JAVA_HOME = 'D:\tools\jdk-17'
$libraries = @(rg --files 'C:\Users\wangqun\.gradle\caches\8.10.2\transforms' -g '*-api.jar')
$classpath = (@($android, $production) + $libraries) -join ';'
New-Item -ItemType Directory -Force "$output\classes", "$output\dex" | Out-Null
$sources = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' | ForEach-Object FullName)
& "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -source 17 -target 17 -classpath $classpath -d "$output\classes" @sources
if ($LASTEXITCODE -ne 0) { throw 'Analysis event compilation failed' }
$inputs = @(Get-ChildItem -LiteralPath "$output\classes" -Recurse -Filter '*.class' | ForEach-Object FullName)
$arguments = @('--min-api', '26', '--lib', $android, '--classpath', $production, '--output', "$output\dex")
foreach ($library in $libraries) { $arguments += @('--classpath', $library) }
& "$sdkTools\d8.bat" @arguments @inputs
if ($LASTEXITCODE -ne 0) { throw 'Analysis event dex failed' }
& "$sdkTools\aapt.exe" package -f -M "$PSScriptRoot\AndroidManifest.xml" -I $android -F "$output\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Analysis event packaging failed' }
Push-Location "$output\dex"
try { & "$sdkTools\aapt.exe" add -f "$output\unsigned.apk" classes.dex } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'Analysis event dex packaging failed' }
& "$sdkTools\apksigner.bat" sign --ks 'C:\Users\wangqun\.android\debug.keystore' --ks-pass pass:android --key-pass pass:android --out "$output\analysis-events.apk" "$output\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Analysis event signing failed' }
