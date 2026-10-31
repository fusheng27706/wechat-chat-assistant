$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$output = Join-Path $workspace 'build\verification\boundary-reasons'
$java = 'D:\tools\jdk-17\bin'
New-Item -ItemType Directory -Force $output | Out-Null
& "$java\javac.exe" -encoding UTF-8 -d $output "$workspace\app\src\main\java\com\codextemp\wechatcaptureprobe\ChatLayout.java" "$PSScriptRoot\BoundaryRefinementReasons.java" "$PSScriptRoot\BoundaryReasonsRegression.java"
if ($LASTEXITCODE -ne 0) { throw 'Boundary reasons compilation failed' }
& "$java\java.exe" -cp $output com.codextemp.wechatcaptureprobe.BoundaryReasonsRegression
if ($LASTEXITCODE -ne 0) { throw 'Boundary reasons regression failed' }
