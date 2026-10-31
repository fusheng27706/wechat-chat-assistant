param([Parameter(Mandatory = $true)][string]$Path)
$ErrorActionPreference = 'Stop'
$samples = [Collections.Generic.List[object]]::new()
$reportedResult = 'INCOMPLETE'
$stream = [IO.File]::Open((Resolve-Path -LiteralPath $Path).Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
$reader = [IO.StreamReader]::new($stream)
try {
while (($line = $reader.ReadLine()) -ne $null) {
    if ($line -match '^INSTRUMENTATION_STATUS: quickEndurance=round=(\d+) elapsedMs=(\d+) readyMs=(\d+) longestUnavailableMs=(\d+) longestObservationMs=(\d+) frames=(\d+)$') {
        $sample = [pscustomobject]@{
            round = [int]$Matches[1]; elapsedMs = [long]$Matches[2]; readyMs = [long]$Matches[3]
            unavailableMs = [long]$Matches[4]; observationMs = [long]$Matches[5]; frames = [long]$Matches[6]
        }
        if ($sample.round -ne $samples.Count + 1 -or ($samples.Count -gt 0 -and $sample.elapsedMs -lt $samples[$samples.Count - 1].elapsedMs)) {
            throw 'Endurance samples are duplicated, missing, or out of order'
        }
        $samples.Add($sample)
    } elseif ($line -match '^INSTRUMENTATION_RESULT: result=(PASS|FAIL)\b') {
        $reportedResult = $Matches[1]
    }
}
} finally { $reader.Dispose() }
if ($samples.Count -eq 0) { throw 'No completed endurance rounds in this artifact' }
function UpperMedian($values) {
    $ordered = @($values | Sort-Object)
    return $ordered[[int][Math]::Floor($ordered.Count / 2)]
}
$sorted = @($samples.readyMs | Sort-Object)
$quarter = [Math]::Max(1, [int][Math]::Floor($samples.Count / 4))
$first = @($samples | Select-Object -First $quarter)
$last = @($samples | Select-Object -Last $quarter)
# 日志中的PASS仅为被测程序报告；外部停止检查、传输退出及测试覆盖仍需单独核对。
[pscustomobject]@{
    reportedResult = $reportedResult
    completedRounds = $samples.Count
    completedElapsedMs = $samples[$samples.Count - 1].elapsedMs
    medianReadyMs = UpperMedian $sorted
    p95ReadyMs = $sorted[[int][Math]::Ceiling($sorted.Count * 0.95) - 1]
    maxReadyMs = $sorted[-1]
    firstQuarterMedianMs = UpperMedian $first.readyMs
    lastQuarterMedianMs = UpperMedian $last.readyMs
    longestUnavailableMs = ($samples.unavailableMs | Measure-Object -Maximum).Maximum
    longestObservationMs = ($samples.observationMs | Measure-Object -Maximum).Maximum
    includesInputDriverAndGesture = $true
} | ConvertTo-Json -Compress
