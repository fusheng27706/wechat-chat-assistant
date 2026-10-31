param([Parameter(Mandatory = $true)][string]$Path)
$ErrorActionPreference = 'Stop'
$samples = [Collections.Generic.List[object]]::new()
$reportedResult = 'INCOMPLETE'
# 只输出数字；不转发日志中的正文或其它任意字段。一次输入仅允许一次 QuickPerf。
$reader = [IO.File]::OpenText((Resolve-Path -LiteralPath $Path).Path)
try {
while (($line = $reader.ReadLine()) -ne $null) {
    if ($line.StartsWith('INSTRUMENTATION_STATUS: performance=')) {
        if ($line -notmatch '^INSTRUMENTATION_STATUS: performance=round=(\d+) readyMs=(\d+) ocrMs=(\d+) messages=(\d+) anchors=(\d+) changeFromGestureMs=(-?\d+) preOcrMs=(-?\d+) (?:postOcrMs|postOcrToAvailableMs)=(-?\d+) (?:observedAfterAnalysisMs|observedAfterAvailableMs)=(-?\d+)\b') {
            throw 'Malformed performance sample'
        }
        $round = [int]$Matches[1]
        $ready = [long]$Matches[2]
        $ocr = [long]$Matches[3]
        $change = [long]$Matches[6]
        $pre = [long]$Matches[7]
        $post = [long]$Matches[8]
        $observation = [long]$Matches[9]
        $bound = $false
        $frame = $null
        $analysisFrame = $null
        $reused = $null
        if ($line.Contains('postOcrToAvailableMs=') -or $line.Contains('observedAfterAvailableMs=') -or $line.Contains('timingVersion=')) {
            if (-not $line.Contains('postOcrToAvailableMs=') -or -not $line.Contains('observedAfterAvailableMs=') -or
                $line -notmatch ' timingVersion=2 frame=(\d+) analysisFrame=(\d+) reusedAnalysis=(true|false)$') {
                throw 'Incomplete version 2 timing metadata'
            }
            $frame = [long]$Matches[1]; $analysisFrame = [long]$Matches[2]; $reused = $Matches[3] -eq 'true'
            if ($frame -lt 1 -or $analysisFrame -gt $frame -or $reused -ne ($analysisFrame -ne $frame)) {
                throw 'Inconsistent analysis frame metadata'
            }
            $bound = $true
        }
        if ($round -ne $samples.Count -or $change -lt 0 -or $pre -lt 0 -or $post -lt 0 -or $observation -lt 0) {
            throw 'Samples are duplicated, missing, out of order, or have invalid timing'
        }
        # changedAt 是最后一次检测到变化的时间，不是触摸结束或画面首次变化时间。
        # analyzedAt 是分析发布，不是屏幕合成完成；不能把此差值叫可见建议延迟。
        $analysis = $pre + $ocr + $post
        $sampled = $change + $analysis + $observation
        if ($sampled -lt $ready) { throw 'Timing snapshot predates the ready observation' }
        $samples.Add([pscustomobject]@{
            round = $round; readyObservedFromDriverStartMs = $ready
            lastDetectedChangeFromDriverStartMs = $change
            beforeOcrMs = $pre; ocrMs = $ocr; afterOcrToAvailabilityMs = $post
            availabilityFromLastDetectedChangeMs = $analysis
            timingSampleAfterAvailabilityMs = $observation
            timingSampleAfterReadyObservationMs = $sampled - $ready
            sourceFrameIdentityVerified = $bound
            frame = $frame; analysisFrame = $analysisFrame; reusedAnalysis = $reused
        })
    } elseif ($line -match '^INSTRUMENTATION_RESULT: result=(PASS|FAIL)\b') {
        $reportedResult = $Matches[1]
    }
}
} finally { $reader.Dispose() }
if ($samples.Count -eq 0) { throw 'No completed performance rounds' }
[pscustomobject]@{
    reportedResult = $reportedResult
    completedRounds = $samples.Count
    measuresScreenPresentation = $false
    sourceFrameIdentityVerified = @($samples | Where-Object { -not $_.sourceFrameIdentityVerified }).Count -eq 0
    samples = $samples.ToArray()
} | ConvertTo-Json -Depth 4
