$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$output = Join-Path $workspace 'build\verification\stable-bubble-bounds'
$source = Join-Path $workspace 'app\src\main\java\com\codextemp\wechatcaptureprobe'
$candidate = Join-Path $PSScriptRoot 'stable-bounds-candidate'
$java = 'D:\tools\jdk-17\bin'
New-Item -ItemType Directory -Force $output | Out-Null
& "$java\javac.exe" -encoding UTF-8 -d $output "$candidate\ChatLayout.java" "$candidate\StableBubbleBounds.java" "$source\ScreenGeometry.java" "$source\ChatViewport.java" "$candidate\GridChatLayout.java" "$candidate\StableBubbleBoundsRegression.java"
if ($LASTEXITCODE -ne 0) { throw 'Stable bounds compilation failed' }
& "$java\java.exe" -cp $output com.codextemp.wechatcaptureprobe.StableBubbleBoundsRegression
if ($LASTEXITCODE -ne 0) { throw 'Stable bounds regression failed' }
