$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output=Join-Path $root 'build/verification/interaction-clock'
New-Item -ItemType Directory -Force $output | Out-Null
$java='D:\tools\jdk-17\bin'
& "$java/javac.exe" -encoding UTF-8 -d $output "$PSScriptRoot/interaction-clock-candidate/ChatInteraction.java" "$PSScriptRoot/interaction-clock-candidate/ChatInteractionClockRegression.java"
if($LASTEXITCODE -ne 0){throw 'Interaction clock compile failed'}
& "$java/java.exe" -cp $output com.codextemp.wechatcaptureprobe.ChatInteractionClockRegression
if($LASTEXITCODE -ne 0){throw 'Interaction clock regression failed'}
