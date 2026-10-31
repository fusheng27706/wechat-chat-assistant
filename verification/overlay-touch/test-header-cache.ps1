$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output=Join-Path $root 'build/verification/header-cache'
New-Item -ItemType Directory -Force $output | Out-Null
$java='D:\tools\jdk-17\bin'
$source=Join-Path $root 'verification/overlay-touch/header-cache-candidate'
& "$java/javac.exe" -encoding UTF-8 -d $output "$source/HeaderRecognitionCache.java" "$source/ConversationSession.java" "$source/HeaderCacheRegression.java"
if($LASTEXITCODE -ne 0){throw 'Header cache compile failed'}
& "$java/java.exe" -cp $output com.codextemp.wechatcaptureprobe.HeaderCacheRegression
if($LASTEXITCODE -ne 0){throw 'Header cache regression failed'}
