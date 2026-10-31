$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output = Join-Path $root 'build/verification/quick-regression'
$source = Join-Path $root 'app/src/main/java/com/codextemp/wechatcaptureprobe'
New-Item -ItemType Directory -Force $output | Out-Null
& 'D:/tools/jdk-17/bin/javac.exe' -encoding UTF-8 -d $output "$source/SuggestionVisibility.java" "$source/ChatInteraction.java" "$PSScriptRoot/QuickSuggestionRegression.java"
if ($LASTEXITCODE -ne 0) { throw 'Quick suggestion compile failed' }
& 'D:/tools/jdk-17/bin/java.exe' -cp $output com.codextemp.wechatcaptureprobe.QuickSuggestionRegression
if ($LASTEXITCODE -ne 0) { throw 'Quick suggestion regression failed' }
