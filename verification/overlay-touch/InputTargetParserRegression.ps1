$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'InputTargetParser.ps1')
$script:checks = 0
function Assert-Target {
    param([bool]$Condition, [string]$Name)
    $script:checks++
    if (-not $Condition) { throw "Input target regression failed: $Name" }
}
function Wrap-Window {
    param([string]$Record)
    return "Input Dispatcher State:`n    Windows:`n      $Record`n    Connections:`n      historical: $Record"
}
$record = "1: name='fictional-own', id=7, displayId=0, paused=false, focusable=false, visible=true, alpha=1.00, flags=NOT_FOCUSABLE | NOT_TOUCH_MODAL, type=ACCESSIBILITY_OVERLAY, frame=[0,100][100,200], globalScale=1.000000, touchableRegion=[10,110][90,190], ownerPid=70, ownerUid=42, hasToken=true"
$targets = @(Get-OwnOverlayInputTarget (Wrap-Window $record) 42 70)
Assert-Target ($targets.Count -eq 1 -and $targets[0].usable) 'only-current-owned-overlay'
Assert-Target (Test-OwnOverlayInputPoint $targets[0] 10 110) 'inclusive-top-left'
Assert-Target (-not (Test-OwnOverlayInputPoint $targets[0] 90 110)) 'exclusive-right'
Assert-Target (-not (Test-OwnOverlayInputPoint $targets[0] 10 190)) 'exclusive-bottom'
Assert-Target (-not (Test-OwnOverlayInputPoint $targets[0] 5 120)) 'frame-not-enough-without-touchable-region'
Assert-Target (-not (Test-OwnOverlayInputPoint $null 10 110)) 'no-target-refuses'
foreach ($replacement in @('ownerUid=420', 'ownerUid=43')) {
    Assert-Target (@(Get-OwnOverlayInputTarget (Wrap-Window $record.Replace('ownerUid=42', $replacement)) 42 70).Count -eq 0) 'exact-own-uid'
}
foreach ($replacement in @('ownerPid=700', 'ownerPid=71')) {
    Assert-Target (@(Get-OwnOverlayInputTarget (Wrap-Window $record.Replace('ownerPid=70', $replacement)) 42 70).Count -eq 0) 'exact-own-pid'
}
foreach ($replacement in @('type=FIRST_APPLICATION_WINDOW', 'type=ACCESSIBILITY_OVERLAY_EXTRA', 'displayId=1')) {
    $changed = if ($replacement.StartsWith('type=')) { $record.Replace('type=ACCESSIBILITY_OVERLAY', $replacement) } else { $record.Replace('displayId=0', $replacement) }
    Assert-Target (@(Get-OwnOverlayInputTarget (Wrap-Window $changed) 42 70).Count -eq 0) 'only-current-display-overlay-type'
}
foreach ($pair in @(@('visible=true','visible=false'), @('paused=false','paused=true'), @('alpha=1.00','alpha=0.00'),
        @('alpha=1.00','alpha=NaN'), @('alpha=1.00','alpha=2.00'), @('globalScale=1.000000','globalScale=0.5'),
        @('hasToken=true','hasToken=false'), @('NOT_FOCUSABLE | NOT_TOUCH_MODAL','NOT_FOCUSABLE | NOT_TOUCHABLE'),
        @('touchableRegion=[10,110][90,190]','touchableRegion=<unknown>'),
        @('touchableRegion=[10,110][90,190]','touchableRegion=[10,110][90,190][100,200][110,210]'),
        @('frame=[0,100][100,200]','frame=[0,100][0,200]'),
        @('frame=[0,100][100,200]','frame=[0,100][2147483648,200]'))) {
    $changed = @(Get-OwnOverlayInputTarget (Wrap-Window $record.Replace($pair[0], $pair[1])) 42 70)
    Assert-Target ($changed.Count -eq 1 -and -not $changed[0].usable) 'unknown-or-non-touchable-refuses'
}
$historical = "Input Dispatcher State:`n    Windows:`n    Connections:`n      $record"
Assert-Target (@(Get-OwnOverlayInputTarget $historical 42 70).Count -eq 0) 'historical-anr-and-connections-not-live-windows'
$withHistoricalWindows = (Wrap-Window $record) + "`nInput Dispatcher State at time of last ANR:`n    Windows:`n      $record`n    Connections:"
Assert-Target (@(Get-OwnOverlayInputTarget $withHistoricalWindows 42 70).Count -eq 1) 'historical-window-copy-with-reused-pid-not-current'
$threeHistorical = "Input Dispatcher State at time of last three times ANR:`n    Windows:`n      $record`n    Connections:"
Assert-Target (@(Get-OwnOverlayInputTarget $threeHistorical 42 70).Count -eq 0) 'historical-only-dump-refuses'
$beforeCurrent = "Input Reader State:`n    Windows:`n      $record`n" + (Wrap-Window $record)
Assert-Target (@(Get-OwnOverlayInputTarget $beforeCurrent 42 70).Count -eq 1) 'ignore-window-copy-before-current-dispatcher'
$observed = Get-OwnOverlayInputObservation (Wrap-Window $record) 42 70
Assert-Target ($observed.currentDispatcher -and $observed.windowsTable -and $observed.windowRecords -eq 1) 'observation-current-table-recognized'
Assert-Target ($observed.ownedRecords -eq 1 -and $observed.ownedValidIds -eq 1 -and $observed.ownedDefaultDisplay -eq 1 -and $observed.ownedOverlayType -eq 1) 'observation-known-own-record'
$historicalObserved = Get-OwnOverlayInputObservation $withHistoricalWindows 42 70
Assert-Target ($historicalObserved.windowRecords -eq 1 -and $historicalObserved.ownedRecords -eq 1) 'observation-does-not-count-historical-copy'
$noCurrentObserved = Get-OwnOverlayInputObservation $threeHistorical 42 70
Assert-Target (-not $noCurrentObserved.currentDispatcher -and -not $noCurrentObserved.windowsTable -and $noCurrentObserved.windowRecords -eq 0) 'observation-historical-only-unavailable'
$emptyObserved = Get-OwnOverlayInputObservation "Input Dispatcher State:`n    Windows:`n    Connections:" 42 70
Assert-Target ($emptyObserved.currentDispatcher -and $emptyObserved.windowsTable -and $emptyObserved.windowRecords -eq 0) 'observation-empty-current-table-not-parser-header-loss'
$foreignObserved = Get-OwnOverlayInputObservation (Wrap-Window $record) 43 71
Assert-Target ($foreignObserved.windowRecords -eq 1 -and $foreignObserved.ownedRecords -eq 0) 'observation-foreign-window-count-only'
$wrongTypeObserved = Get-OwnOverlayInputObservation (Wrap-Window $record.Replace('type=ACCESSIBILITY_OVERLAY', 'type=APPLICATION')) 42 70
Assert-Target ($wrongTypeObserved.ownedRecords -eq 1 -and $wrongTypeObserved.ownedOverlayType -eq 0) 'observation-own-record-rejected-type'
$wrongDisplayObserved = Get-OwnOverlayInputObservation (Wrap-Window $record.Replace('displayId=0', 'displayId=1')) 42 70
Assert-Target ($wrongDisplayObserved.ownedRecords -eq 1 -and $wrongDisplayObserved.ownedDefaultDisplay -eq 0) 'observation-own-record-rejected-display'
$invalidIdObserved = Get-OwnOverlayInputObservation (Wrap-Window $record.Replace('id=7,', 'id=0,')) 42 70
Assert-Target ($invalidIdObserved.ownedRecords -eq 1 -and $invalidIdObserved.ownedValidIds -eq 0) 'observation-own-record-rejected-id'
Assert-Target ($observed.PSObject.Properties.Name -notcontains 'records') 'observation-does-not-return-raw-records'
Assert-Target (@(Get-OwnOverlayInputTarget '' 42 70).Count -eq 0) 'missing-dump-refuses'
Assert-Target (@(Get-OwnOverlayInputTarget $null 42 70).Count -eq 0) 'null-dump-refuses'
Assert-Target (@(Get-OwnOverlayInputTarget (Wrap-Window $record.Replace('id=7,', 'id=0,')) 42 70).Count -eq 0) 'invalid-window-id-refuses'
Assert-Target (@(Get-OwnOverlayInputTarget (Wrap-Window $record) 0 70).Count -eq 0) 'invalid-uid-refuses'
$moved = @(Get-OwnOverlayInputTarget (Wrap-Window $record.Replace('frame=[0,100][100,200]', 'frame=[0,300][100,400]').Replace('touchableRegion=[10,110][90,190]', 'touchableRegion=[10,310][90,390]')) 42 70)[0]
Assert-Target (-not (Test-OwnOverlayInputPoint $moved 50 150)) 'stale-view-coordinate-refuses'
Assert-Target (Test-OwnOverlayInputPoint $moved 50 350) 'current-dispatcher-coordinate-accepts'
for ($horizontal = -5; $horizontal -le 105; $horizontal += 5) {
    for ($vertical = 95; $vertical -le 205; $vertical += 5) {
        $expected = $horizontal -ge 10 -and $horizontal -lt 90 -and $vertical -ge 110 -and $vertical -lt 190
        Assert-Target ((Test-OwnOverlayInputPoint $targets[0] $horizontal $vertical) -eq $expected) 'rectangle-grid-boundaries'
    }
}
"PASS inputTargetChecks=$script:checks deviceActions=0 networkCalls=0"
