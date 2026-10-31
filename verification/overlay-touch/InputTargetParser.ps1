function Get-CurrentDispatcherWindowTable {
    param([string]$InputDump)
    $inside = $false
    $currentDispatcher = $false
    $indentation = -1
    $records = @()
    foreach ($line in [regex]::Split($InputDump, '\r?\n')) {
        if (-not $currentDispatcher) {
            if ($line -cmatch '^Input Dispatcher State:\s*$') { $currentDispatcher = $true }
            continue
        }
        if ($line.Length -gt 0 -and -not [char]::IsWhiteSpace($line[0])) { break }
        if ($line -cmatch '^\s*Windows:\s*$') {
            $inside = $true
            $indentation = $line.Length - $line.TrimStart().Length
            continue
        }
        if (-not $inside -or -not $line.Trim()) { continue }
        if ($line.Length - $line.TrimStart().Length -le $indentation) { break }
        $records += $line
    }
    [pscustomobject]@{currentDispatcher=$currentDispatcher;windowsTable=$inside;records=$records}
}

function Get-OwnOverlayInputObservation {
    param([string]$InputDump, [int]$OwnerUid, [int]$OwnerPid)
    $table = Get-CurrentDispatcherWindowTable $InputDump
    $owned = @($table.records | Where-Object {
        $OwnerUid -gt 0 -and $OwnerPid -gt 0 -and
            $_ -cmatch "(?<![A-Za-z])ownerUid=$OwnerUid(?=,|\s|$)" -and
            $_ -cmatch "(?<![A-Za-z])ownerPid=$OwnerPid(?=,|\s|$)"
    })
    $validIds = @($owned | Where-Object {
        $identifier = [regex]::Match($_, '(?<![A-Za-z])id=(\d+)(?=,|\s|$)')
        $value = 0
        $identifier.Success -and [int]::TryParse($identifier.Groups[1].Value, [ref]$value) -and $value -gt 0
    }).Count
    [pscustomobject]@{currentDispatcher=$table.currentDispatcher;windowsTable=$table.windowsTable;
        windowRecords=$table.records.Count;ownedRecords=$owned.Count;ownedValidIds=$validIds;
        ownedDefaultDisplay=@($owned | Where-Object { $_ -cmatch '(?<![A-Za-z])displayId=0(?=,|\s|$)' }).Count;
        ownedOverlayType=@($owned | Where-Object { $_ -cmatch '(?:^|,\s*)type=(?:ACCESSIBILITY_OVERLAY|2032)(?=,|\s|$)' }).Count}
}

function Get-OwnOverlayInputTarget {
    param([string]$InputDump, [int]$OwnerUid, [int]$OwnerPid)
    if ($OwnerUid -le 0 -or $OwnerPid -le 0 -or [string]::IsNullOrEmpty($InputDump)) { return }
    foreach ($line in (Get-CurrentDispatcherWindowTable $InputDump).records) {
        $fields = @{}
        foreach ($name in @('id', 'displayId', 'ownerUid', 'ownerPid')) {
            $matched = [regex]::Match($line, "(?<![A-Za-z])$name=(\d+)(?=,|\s|$)")
            $value = 0
            if ($matched.Success -and [int]::TryParse($matched.Groups[1].Value, [ref]$value)) { $fields[$name] = $value }
        }
        if ($fields.Count -ne 4 -or $fields.id -le 0 -or $fields.ownerUid -ne $OwnerUid -or $fields.ownerPid -ne $OwnerPid -or $fields.displayId -ne 0) { continue }
        if ($line -cnotmatch '(?:^|,\s*)type=(?:ACCESSIBILITY_OVERLAY|2032)(?=,|\s|$)') { continue }
        $rectangles = @{}
        foreach ($name in @('frame', 'touchableRegion')) {
            $matched = [regex]::Match($line, "(?:^|,\s*)$name=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\](?=,|$)")
            if (-not $matched.Success) { continue }
            $coordinates = @()
            foreach ($index in 1..4) {
                $value = 0
                if ([int]::TryParse($matched.Groups[$index].Value, [ref]$value)) { $coordinates += $value }
            }
            if ($coordinates.Count -eq 4 -and $coordinates[2] -gt $coordinates[0] -and $coordinates[3] -gt $coordinates[1]) {
                $rectangles[$name] = [pscustomobject]@{left=$coordinates[0];top=$coordinates[1];right=$coordinates[2];bottom=$coordinates[3]}
            }
        }
        $alphaMatch = [regex]::Match($line, '(?:^|,\s*)alpha=([^,\s]+)')
        $alpha = [double]::NaN
        $alphaValid = $alphaMatch.Success -and [double]::TryParse($alphaMatch.Groups[1].Value,
            [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$alpha)
        $flagsMatch = [regex]::Match($line, '(?:^|,\s*)flags=([^,]+)(?=,)')
        $usable = $rectangles.Count -eq 2 -and $alphaValid -and $alpha -gt 0 -and $alpha -le 1 -and $flagsMatch.Success -and
            $flagsMatch.Groups[1].Value -cnotmatch '\bNOT_TOUCHABLE\b' -and
            $line -cmatch '(?:^|,\s*)visible=true(?=,|\s|$)' -and
            $line -cmatch '(?:^|,\s*)paused=false(?=,|\s|$)' -and
            $line -cmatch '(?:^|,\s*)hasToken=true(?=,|\s|$)' -and
            $line -cmatch '(?:^|,\s*)globalScale=1(?:\.0+)?(?=,|\s|$)'
        [pscustomobject]@{windowId=$fields.id;ownerPid=$fields.ownerPid;ownerUid=$fields.ownerUid;
            frame=$rectangles.frame;touchable=$rectangles.touchableRegion;usable=[bool]$usable}
    }
}

function Test-OwnOverlayInputPoint {
    param($Target, [int]$Horizontal, [int]$Vertical)
    if ($null -eq $Target -or -not $Target.usable) { return $false }
    foreach ($rectangle in @($Target.frame, $Target.touchable)) {
        if ($Horizontal -lt $rectangle.left -or $Horizontal -ge $rectangle.right -or
            $Vertical -lt $rectangle.top -or $Vertical -ge $rectangle.bottom) { return $false }
    }
    return $true
}
