$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$output = Join-Path $workspace 'build\verification\idle-phase-queue'
$java = 'D:\tools\jdk-17\bin'
New-Item -ItemType Directory -Force $output | Out-Null
& "$java\javac.exe" -encoding UTF-8 -d $output (Join-Path $workspace 'app\src\main\java\com\codextemp\wechatcaptureprobe\IdlePhaseQueue.java') (Join-Path $PSScriptRoot 'phase-lookahead\IdlePhaseQueueRegression.java')
if ($LASTEXITCODE -ne 0) { throw 'Idle queue compilation failed' }
& "$java\java.exe" -cp $output com.codextemp.wechatcaptureprobe.IdlePhaseQueueRegression
if ($LASTEXITCODE -ne 0) { throw 'Idle queue regression failed' }
