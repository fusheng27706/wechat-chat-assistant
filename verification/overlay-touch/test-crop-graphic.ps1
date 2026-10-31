$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$candidate=Join-Path $PSScriptRoot 'stable-bounds-candidate'
$output=Join-Path $root 'build/verification/crop-graphic'
New-Item -ItemType Directory -Force $output | Out-Null
$java='D:\tools\jdk-17\bin'
& "$java/javac.exe" -encoding UTF-8 -d $output "$candidate/ChatLayout.java" "$candidate/GridChatLayout.java" "$candidate/StableBubbleBounds.java" "$candidate/CropGraphicProbe.java" "$root/app/src/main/java/com/codextemp/wechatcaptureprobe/GraphicDetector.java"
if($LASTEXITCODE -ne 0){throw 'Crop graphic compile failed'}
& "$java/java.exe" -cp $output com.codextemp.wechatcaptureprobe.CropGraphicProbe
if($LASTEXITCODE -ne 0){throw 'Crop graphic probe failed'}
