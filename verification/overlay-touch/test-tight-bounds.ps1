$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$output = Join-Path $workspace 'build\verification\tight-bounds-candidate'
$source = Join-Path $workspace 'app\src\main\java\com\codextemp\wechatcaptureprobe\ChatLayout.java'
$java = 'D:\tools\jdk-17\bin'
New-Item -ItemType Directory -Force $output | Out-Null
$original = [IO.File]::ReadAllText($source)
$needle = '        boxes.sort(Comparator.comparingInt(box -> box.top));'
if (($original.Split($needle).Length - 1) -ne 1) { throw 'Expected one production bubble sort' }
# 只在构建目录组装隔离候选，不修改生产检测或历史失败候选。
$candidate = $original.Replace('ChatLayout', 'TightChatLayout').Replace($needle,
    '        boxes = TightBubbleBounds.refineAll(boxes, step, 0, top, width, bottom, dark, pixels);' + "`n" + $needle)
$kind = @'
    static int bubbleKind(int color, boolean dark) {
        int red=(color>>16)&255,green=(color>>8)&255,blue=color&255;
        if(green>100&&green>red*1.15&&green>blue*1.25)return 2;
        return (dark ? red>32&&red<115&&Math.abs(red-green)<10&&Math.abs(red-blue)<10
                : red>247&&green>247&&blue>247) ? 1 : 0;
    }
'@
$end = $candidate.LastIndexOf('}')
$candidate = $candidate.Insert($end, $kind)
$generated = Join-Path $output 'TightChatLayout.java'
[IO.File]::WriteAllText($generated, $candidate, [Text.UTF8Encoding]::new($false))
& "$java\javac.exe" -encoding UTF-8 -d $output $source $generated (Join-Path $PSScriptRoot 'tight-bounds-candidate\TightBubbleBounds.java') (Join-Path $PSScriptRoot 'tight-bounds-candidate\TightTranslationProbe.java') (Join-Path $PSScriptRoot 'tight-bounds-candidate\TightBoundsRegression.java')
if ($LASTEXITCODE -ne 0) { throw 'Tight bounds compilation failed' }
& "$java\java.exe" -cp $output com.codextemp.wechatcaptureprobe.TightTranslationProbe
if ($LASTEXITCODE -ne 0) { throw 'Tight bounds probe failed' }
& "$java\java.exe" -cp $output com.codextemp.wechatcaptureprobe.TightBoundsRegression
if ($LASTEXITCODE -ne 0) { throw 'Tight bounds rejection regression failed' }
