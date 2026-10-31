$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$output = Join-Path $workspace 'build\verification\fresh-frame-regression'
$java = 'D:\tools\jdk-17\bin'
New-Item -ItemType Directory -Force $output | Out-Null
& "$java\javac.exe" -encoding UTF-8 -d $output "$workspace\app\src\main\java\com\codextemp\wechatcaptureprobe\FreshFrameRecovery.java" "$PSScriptRoot\FreshFrameRecoveryRegression.java"
if ($LASTEXITCODE -ne 0) { throw 'Recovery regression compilation failed' }
& "$java\java.exe" -cp $output com.codextemp.wechatcaptureprobe.FreshFrameRecoveryRegression
if ($LASTEXITCODE -ne 0) { throw 'Recovery regression failed' }
