param(
    [ValidateSet('ParentCrash', 'NotificationEntryStop', 'NotificationActionStop')]
    [string]$Mode = 'ParentCrash',
    [string]$Serial = '9bafde2f',
    [string]$Adb = 'F:\Android\Sdk\platform-tools\adb.exe'
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\CaptureDisplayParser.ps1"
$packageName = 'com.codextemp.wechatcaptureprobe'
$checks = 0

function Invoke-Adb {
    param([string[]]$Arguments)
    $output = & $Adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw 'ADB command failed; observation stopped, no automatic restart'
    }
    return ($output -join "`n")
}

function Require {
    param([bool]$Condition, [string]$Name)
    $script:checks++
    if (-not $Condition) {
        throw "Capture-exit check failed: $Name"
    }
}

function Own-Processes {
    $processes = Invoke-Adb -Arguments @('shell', 'ps', '-A', '-o', 'PID,UID,NAME')
    $expression = '^\s*(\d+)\s+(\d+)\s+(' + [regex]::Escape($packageName) + '(?::projection)?)\s*$'
    return @($processes -split "`n" | ForEach-Object {
        if ($_ -match $expression) {
            [pscustomobject]@{ ProcessId = [int]$Matches[1]; OwnerUid = [int]$Matches[2]; Name = $Matches[3] }
        }
    })
}

function Snapshot {
    $processes = @(Own-Processes)
    $services = Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'services', $packageName)
    $displays = Invoke-Adb -Arguments @('shell', 'dumpsys', 'display')
    $windows = Invoke-Adb -Arguments @('shell', 'dumpsys', 'window', 'windows')
    $captureServices = @($services -split "`n" | Where-Object {
        $_ -match 'ServiceRecord\{[^\r\n]*com\.codextemp\.wechatcaptureprobe/\.(AssistantCaptureService|CaptureService|ProjectionHostService)\}'
    })
    $overlayWindows = @([regex]::Split($windows, '(?=  Window #\d+ )') | Where-Object {
        $_ -match ('\bmOwnerUid=' + $script:appUid + '\b') -and $_ -match '\bty=(2032|ACCESSIBILITY_OVERLAY)\b'
    })
    $ownActivityResumed = $false
    if ($Mode -eq 'NotificationActionStop') {
        $activities = Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'activities')
        $ownActivityResumed = $activities -match ('(?m)^\s*mResumedActivity:.*\s' + [regex]::Escape($packageName) + '/')
    }
    return [pscustomobject]@{
        Processes = $processes
        CaptureServices = $captureServices.Count
        CaptureDisplay = Test-CaptureDisplayRecord -DisplayDump $displays
        OwnOverlays = $overlayWindows.Count
        OwnActivityResumed = $ownActivityResumed
    }
}

$settingsNames = Invoke-Adb -Arguments @('shell', 'run-as', $packageName, 'ls', 'shared_prefs')
if (($settingsNames -split "`n") -contains 'model_settings.xml') {
    [xml]$settings = Invoke-Adb -Arguments @('shell', 'run-as', $packageName, 'cat', 'shared_prefs/model_settings.xml')
    Require (-not @($settings.map.ChildNodes | Where-Object { $_.name -in @('key_ciphertext', 'key_iv') }).Count) 'no-real-model-key'
    Require (-not @($settings.map.ChildNodes | Where-Object {
        $_.name -in @('chat_cloud_enabled', 'partial_analysis_allowed') -and $_.value -eq 'true'
    }).Count) 'cloud-and-partial-off'
} else {
    Require $true 'model-settings-absent-defaults-off'
}
$script:appUid = [int](Invoke-Adb -Arguments @('shell', 'run-as', $packageName, 'id', '-u'))
$instrumentation = Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'processes')
Require (-not ($instrumentation -match 'ActiveInstrumentation\{|mActiveInstrumentation=ActiveInstrumentation')) 'no-active-instrumentation'
$before = Snapshot
$parent = @($before.Processes | Where-Object Name -eq $packageName)
$worker = @($before.Processes | Where-Object Name -eq ($packageName + ':projection'))
Require ($parent.Count -eq 1 -and $worker.Count -eq 1) 'one-live-main-and-worker'
Require ($parent[0].OwnerUid -eq $appUid -and $worker[0].OwnerUid -eq $appUid) 'both-pids-belong-to-debug-app-uid'
Require ($parent[0].ProcessId -ne $worker[0].ProcessId) 'only-main-selected'
Require ($before.CaptureServices -eq 2 -and $before.CaptureDisplay -and $before.OwnOverlays -gt 0) 'real-live-capture-before-injection'
if ($Mode -eq 'NotificationActionStop') {
    Require (-not $before.OwnActivityResumed) 'notification-action-starts-outside-own-activity'
}
$hostId = $worker[0].ProcessId
$parentId = $parent[0].ProcessId
$hostBefore = Invoke-Adb -Arguments @('logcat', '-d', "--pid=$hostId", '-v', 'brief', '-s', 'ProjectionHost:I', '*:S')
Require ($hostBefore -match ('OPEN pid=' + $hostId + ' displayId=\d+') -and $hostBefore -notmatch 'CLOSE reason=') 'open-owner-not-already-stopping'
$confirmed = @(Own-Processes)
Require (@($confirmed | Where-Object { $_.ProcessId -eq $parentId -and $_.Name -eq $packageName -and $_.OwnerUid -eq $appUid }).Count -eq 1) 'main-identity-rechecked-before-signal'
Require (@($confirmed | Where-Object { $_.ProcessId -eq $hostId -and $_.Name -eq ($packageName + ':projection') -and $_.OwnerUid -eq $appUid }).Count -eq 1) 'worker-not-signal-target'
if ($Mode -eq 'ParentCrash') {
    $userId = [int](Invoke-Adb -Arguments @('shell', 'am', 'get-current-user'))
    Require ([int][Math]::Floor($appUid / 100000) -eq $userId) 'target-in-current-android-user'
    Write-Output "INJECT method=system-single-pid-crash mainOnlyPid=$parentId workerPid=$hostId uid=$appUid instrumentation=0 forceStop=0"
} else {
    $previousStops = Invoke-Adb -Arguments @('logcat', '-d', "--pid=$parentId", '-v', 'brief', '-s', 'ChatAssistant:I', '*:S')
    $previousStopCount = @($previousStops -split "`n" | Where-Object { $_ -match 'STOP frames=\d+' }).Count
    $entry = if ($Mode -eq 'NotificationActionStop') { 'ACTION' } else { 'ENTRY' }
    Write-Output "WAITING_NOTIFICATION_${entry}_STOP mainPid=$parentId workerPid=$hostId uid=$appUid instrumentation=0 forceStop=0"
}
$watch = [System.Diagnostics.Stopwatch]::StartNew()
$actionObservedAfterMs = 0L
$cleanupWatch = $null
$ownActivityObserved = $before.OwnActivityResumed
if ($Mode -eq 'ParentCrash') {
    $cleanupWatch = $watch
    Invoke-Adb -Arguments @('shell', 'am', 'crash', '--user', [string]$userId, [string]$parentId) | Out-Null
}
do {
    if ($Mode -ne 'ParentCrash' -and $null -eq $cleanupWatch) {
        $currentStops = Invoke-Adb -Arguments @('logcat', '-d', "--pid=$parentId", '-v', 'brief', '-s', 'ChatAssistant:I', '*:S')
        $currentStopCount = @($currentStops -split "`n" | Where-Object { $_ -match 'STOP frames=\d+' }).Count
        if ($currentStopCount -gt $previousStopCount) {
            $actionObservedAfterMs = $watch.ElapsedMilliseconds
            $cleanupWatch = [System.Diagnostics.Stopwatch]::StartNew()
            Write-Output "STOP_ACTION_OBSERVED mainPid=$parentId afterWaitingMs=$actionObservedAfterMs"
        }
    }
    $after = Snapshot
    $ownActivityObserved = $ownActivityObserved -or $after.OwnActivityResumed
    $expectedParentCount = if ($Mode -eq 'ParentCrash') { 0 } else { 1 }
    $complete = $null -ne $cleanupWatch -and
        @($after.Processes | Where-Object ProcessId -eq $parentId).Count -eq $expectedParentCount -and
        @($after.Processes | Where-Object Name -eq ($packageName + ':projection')).Count -eq 0 -and
        $after.CaptureServices -eq 0 -and -not $after.CaptureDisplay -and $after.OwnOverlays -eq 0
    if (-not $complete) { Start-Sleep -Milliseconds 200 }
    $timedOut = if ($null -eq $cleanupWatch) { $watch.ElapsedMilliseconds -ge 150000 } else { $cleanupWatch.ElapsedMilliseconds -ge 10000 }
} while (-not $complete -and -not $timedOut)
$firstCleanMs = if ($null -eq $cleanupWatch) { -1 } else { $cleanupWatch.ElapsedMilliseconds }
Require ($null -ne $cleanupWatch) 'real-action-observed-before-cleanup-acceptance'
Require $complete ("external-state mainCount=" + @($after.Processes | Where-Object ProcessId -eq $parentId).Count +
    " workerCount=" + @($after.Processes | Where-Object Name -eq ($packageName + ':projection')).Count +
    " captureServices=" + $after.CaptureServices + " captureDisplay=" + $after.CaptureDisplay + " ownOverlays=" + $after.OwnOverlays)
$hostAfter = Invoke-Adb -Arguments @('logcat', '-d', "--pid=$hostId", '-v', 'brief', '-s', 'ProjectionHost:I', '*:S')
$expectedReason = if ($Mode -eq 'ParentCrash') { 3 } else { 1 }
Require ($hostAfter -match "CLOSE reason=$expectedReason tokenReleased=true surfaceReleased=true") 'worker-processed-expected-real-exit'
Start-Sleep -Seconds 6
$stable = Snapshot
$ownActivityObserved = $ownActivityObserved -or $stable.OwnActivityResumed
Require (@($stable.Processes | Where-Object ProcessId -eq $parentId).Count -eq $expectedParentCount) 'original-main-still-in-expected-state'
Require (@($stable.Processes | Where-Object Name -eq ($packageName + ':projection')).Count -eq 0) 'no-projection-worker-restart'
Require ($stable.CaptureServices -eq 0 -and -not $stable.CaptureDisplay -and $stable.OwnOverlays -eq 0) 'no-capture-restart-after-six-seconds'
if ($Mode -eq 'NotificationActionStop') {
    Require (-not $ownActivityObserved) 'notification-action-did-not-open-own-activity-in-observed-snapshots'
}
$restartedMain = @($stable.Processes | Where-Object Name -eq $packageName | ForEach-Object ProcessId) -join ','
Write-Output "PASS ownerExitChecks=$checks mode=$Mode originalParentPid=$parentId originalWorkerPid=$hostId actionObservedAfterMs=$actionObservedAfterMs firstObservedCleanMs=$firstCleanMs laterMainPids=$restartedMain waitAfterCleanupSeconds=6 modelRequests=0 wechatWrites=0 rawChatFiles=0"
