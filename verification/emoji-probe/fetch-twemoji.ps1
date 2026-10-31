$ErrorActionPreference = 'Stop'
$revision = 'b6b55fef1e8636b540a6d016a4729ca8cdf2e60b'
$base = "https://raw.githubusercontent.com/jdecked/twemoji/$revision"
$destination = Join-Path $PSScriptRoot 'assets\twemoji'
$headers = @{'User-Agent' = 'WechatCaptureProbe-Research'}
New-Item -ItemType Directory -Force $destination | Out-Null
$tree = Invoke-RestMethod -Headers $headers -Uri "https://api.github.com/repos/jdecked/twemoji/git/trees/${revision}?recursive=1"
$paths = @('LICENSE-GRAPHICS')
for ($codepoint = 0x1f600; $codepoint -le 0x1f64f; $codepoint++) {
    $paths += 'assets/72x72/{0:x}.png' -f $codepoint
}
foreach ($source in $paths) {
    $entry = @($tree.tree | Where-Object path -EQ $source)
    if ($entry.Count -ne 1 -or $entry[0].size -gt 65536) { throw "Unexpected upstream asset: $source" }
    $target = Join-Path $destination ([IO.Path]::GetFileName($source))
    if (-not (Test-Path -LiteralPath $target)) { Invoke-WebRequest -Headers $headers -Uri "$base/$source" -OutFile $target }
    $bytes = [IO.File]::ReadAllBytes($target)
    $prefix = [Text.Encoding]::UTF8.GetBytes("blob $($bytes.Length)`0")
    $hash = [Security.Cryptography.SHA1]::Create()
    try { $actual = ([BitConverter]::ToString($hash.ComputeHash($prefix + $bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $hash.Dispose() }
    if ($actual -ne $entry[0].sha) { throw "Pinned upstream hash mismatch: $source" }
}
Write-Output "Verified 80 Twemoji images and license at $revision"
