$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$output = Join-Path $workspace 'build\verification\graphic-detector'
$java = 'D:\tools\jdk-17\bin'
New-Item -ItemType Directory -Force $output | Out-Null
& "$java\javac.exe" -encoding UTF-8 -d $output "$workspace\app\src\main\java\com\codextemp\wechatcaptureprobe\ChatLayout.java" "$workspace\app\src\main\java\com\codextemp\wechatcaptureprobe\GraphicDetector.java" "$PSScriptRoot\BaselineGraphicDetector.java" "$PSScriptRoot\GraphicDetectorRegression.java"
if ($LASTEXITCODE -ne 0) { throw 'Graphic regression compilation failed' }
& "$java\java.exe" -cp $output com.codextemp.wechatcaptureprobe.GraphicDetectorRegression
if ($LASTEXITCODE -ne 0) { throw 'Graphic regression failed' }
