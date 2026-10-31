$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output=Join-Path $root 'build/verification/bounded-interaction'
New-Item -ItemType Directory -Force $output | Out-Null
$java='D:\tools\jdk-17\bin'
& "$java/javac.exe" -encoding UTF-8 -d $output "$root/app/src/main/java/com/codextemp/wechatcaptureprobe/ChatInteraction.java" "$PSScriptRoot/BoundedInteractionRegression.java"
if($LASTEXITCODE -ne 0){throw 'Bounded interaction compilation failed'}
& "$java/java.exe" -cp $output com.codextemp.wechatcaptureprobe.BoundedInteractionRegression
if($LASTEXITCODE -ne 0){throw 'Bounded interaction regression failed'}
