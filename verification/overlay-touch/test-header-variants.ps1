$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output=Join-Path $root 'build/verification/header-variants'
New-Item -ItemType Directory -Force $output | Out-Null
$java='D:\tools\jdk-17\bin'
$source=Join-Path $root 'app/src/main/java/com/codextemp/wechatcaptureprobe'
& "$java/javac.exe" -encoding UTF-8 -d $output "$source/HeaderRecognitionCache.java" "$source/ConversationSession.java" "$PSScriptRoot/header-variants-candidate/HeaderVariantsRegression.java"
if($LASTEXITCODE -ne 0){throw 'Header cache compile failed'}
& "$java/java.exe" -cp $output com.codextemp.wechatcaptureprobe.HeaderVariantsRegression
if($LASTEXITCODE -ne 0){throw 'Header cache regression failed'}
