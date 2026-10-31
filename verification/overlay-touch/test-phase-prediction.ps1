$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$output = Join-Path $workspace 'build\verification\phase-prediction'
$production = Join-Path $workspace 'app\src\main\java\com\codextemp\wechatcaptureprobe'
$java = 'D:\tools\jdk-17\bin'
New-Item -ItemType Directory -Force $output | Out-Null
& "$java\javac.exe" -encoding UTF-8 -d $output (Join-Path $production 'ChatLayout.java') (Join-Path $production 'ExactPixelCache.java') (Join-Path $PSScriptRoot 'phase-lookahead\PhasePredictionProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Phase prediction compilation failed' }
& "$java\java.exe" -cp $output com.codextemp.wechatcaptureprobe.PhasePredictionProbe
if ($LASTEXITCODE -ne 0) { throw 'Phase prediction probe failed' }
