$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output=Join-Path $root 'build/verification/late-interaction'
$source=Join-Path $root 'app/src/main/java/com/codextemp/wechatcaptureprobe'
New-Item -ItemType Directory -Force $output | Out-Null
$java='D:\tools\jdk-17\bin'
& "$java/javac.exe" -encoding UTF-8 -d $output "$source/ChatInteraction.java" "$source/SuggestionVisibility.java" "$PSScriptRoot/LateInteractionRegression.java"
if($LASTEXITCODE -ne 0){throw 'Late interaction compilation failed'}
& "$java/java.exe" -cp $output com.codextemp.wechatcaptureprobe.LateInteractionRegression
if($LASTEXITCODE -ne 0){throw 'Late interaction regression failed'}
