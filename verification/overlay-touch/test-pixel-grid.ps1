$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$output = Join-Path $workspace 'build\verification\pixel-grid-candidate'
$source = Join-Path $workspace 'app\src\main\java\com\codextemp\wechatcaptureprobe\ChatLayout.java'
$java = 'D:\tools\jdk-17\bin'
New-Item -ItemType Directory -Force $output | Out-Null
$original = [IO.File]::ReadAllText($source)
$needle = 'int step = Math.max(2, width / 360);'
if (($original.Split($needle).Length - 1) -ne 1) { throw 'Expected exactly one production grid step' }
# 只在构建目录生成隔离变体，生产源码与原APK均不修改。
$candidate = $original.Replace('ChatLayout', 'PixelChatLayout').Replace($needle, 'int step = 1;')
$generated = Join-Path $output 'PixelChatLayout.java'
[IO.File]::WriteAllText($generated, $candidate, [Text.UTF8Encoding]::new($false))
& "$java\javac.exe" -encoding UTF-8 -d $output $source $generated (Join-Path $PSScriptRoot 'pixel-grid-candidate\GridTranslationProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Pixel grid compilation failed' }
& "$java\java.exe" -cp $output com.codextemp.wechatcaptureprobe.GridTranslationProbe
if ($LASTEXITCODE -ne 0) { throw 'Pixel grid probe failed' }
