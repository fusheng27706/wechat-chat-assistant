param([string]$Serial = '9bafde2f', [ValidateSet('Touch', 'Keyboard', 'Endurance', 'Expanded', 'Demo', 'Quick', 'QuickExpandedCopy', 'QuickPerf', 'QuickKeyboard', 'QuickLifecycle', 'QuickDetailRecovery', 'QuickEndurance', 'QuickSwitch', 'QuickHeaderStop', 'QuickPreparationStop', 'QuickSnapshotStop', 'QuickIdleStop', 'QuickIdleTimeout')][string]$Variant = 'Touch',
    [switch]$HoldFailure, [switch]$VerifyInputTarget, [switch]$TraceWindows, [switch]$TraceLayout, [switch]$OperateSystemUi, [switch]$VerifyColdState, [switch]$ReversePerf, [switch]$ReverseEndurance, [switch]$IdlePhaseExperiment, [ValidateRange(500,5000)][int]$PerfDwellMs = 500,
    [ValidateRange(60, 1800)][int]$EnduranceSeconds = 900)
$ErrorActionPreference = 'Stop'
if ($IdlePhaseExperiment -and $Variant -ne 'QuickPerf') { throw 'IdlePhaseExperiment requires QuickPerf' }
if ($ReversePerf -and $Variant -ne 'QuickPerf') { throw 'ReversePerf requires QuickPerf' }
if ($ReverseEndurance -and $Variant -ne 'QuickEndurance') { throw 'ReverseEndurance requires QuickEndurance' }
$adb = 'F:\Android\Sdk\platform-tools\adb.exe'
$package = 'com.codextemp.wechatcaptureprobe'
$initialKeyboard = ((& $adb -s $Serial shell settings get secure default_input_method) -join "`n").Trim()
. (Join-Path $PSScriptRoot '..\owner-lifecycle\CaptureDisplayParser.ps1')
. (Join-Path $PSScriptRoot '..\owner-lifecycle\AccessibilityBindingParser.ps1')
. (Join-Path $PSScriptRoot 'InputTargetParser.ps1')
$inputPackage = (& $adb -s $Serial shell dumpsys package $package) -join "`n"
if ($inputPackage -notmatch 'userId=(\d+)') { throw 'Own input UID unavailable' }
$inputOwnerUid = [int]$Matches[1]
$instrumentedPid = 0
$size = (& $adb -s $Serial shell wm size) -join "`n"
if ($size -notmatch 'Physical size:\s*(\d+)x(\d+)') { throw 'Unknown physical display size' }
$width = [int]$Matches[1]
$height = [int]$Matches[2]
$start = [Diagnostics.ProcessStartInfo]::new()
$start.FileName = $adb
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
$start.ArgumentList.Clear()
foreach ($argument in @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-e', 'verify_cold_state', $VerifyColdState.IsPresent.ToString().ToLowerInvariant())) {
    $start.ArgumentList.Add($argument)
}
foreach ($argument in @('-e', 'trace_layout', $TraceLayout.IsPresent.ToString().ToLowerInvariant(), '-e', 'idle_phase', $IdlePhaseExperiment.IsPresent.ToString().ToLowerInvariant(), '-e', 'perf_dwell_ms', $PerfDwellMs.ToString(), '-e', 'endurance_reverse', $ReverseEndurance.IsPresent.ToString().ToLowerInvariant(), '-e', 'perf_reverse', $ReversePerf.IsPresent.ToString().ToLowerInvariant(), '-e', 'mode', $Variant.ToLowerInvariant(), '-e', 'system_ui', $OperateSystemUi.IsPresent.ToString().ToLowerInvariant(), '-e', 'hold_failure', $HoldFailure.IsPresent.ToString().ToLowerInvariant(), '-e', 'trace_windows', $TraceWindows.IsPresent.ToString().ToLowerInvariant(), '-e', 'endurance_seconds', $EnduranceSeconds.ToString(), "$package.overlaytouchverify/$package.OverlayTouchProbe")) {
    # 公共命令前缀已加入，保留下面的模式与其它验证参数。
    $start.ArgumentList.Add($argument)
}
$process = [Diagnostics.Process]::new()
$process.StartInfo = $start
if (-not $process.Start()) { throw 'Instrumentation transport did not start' }
$status = @{}
$driverFailure = ''
$nativePassed = $false
while ($null -ne ($line = $process.StandardOutput.ReadLine())) {
    Write-Output $line
    if ($line -match '^INSTRUMENTATION_RESULT: result=PASS overlayTouchChecks=') { $nativePassed = $true }
    if ($line -match '^INSTRUMENTATION_STATUS: ([A-Za-z]+)=(.*)$') { $status[$Matches[1]] = $Matches[2] }
    if ($line -ne 'INSTRUMENTATION_STATUS_CODE: 1') { continue }
    $timedInputHelperUploaded = $false
    try {
        if ($driverFailure) { continue }
        $phase = [string]$status['phase']
        if ($status.ContainsKey('parentPid')) {
            $observedPid = [int]$status['parentPid']
            if ($observedPid -le 0 -or ($instrumentedPid -gt 0 -and $instrumentedPid -ne $observedPid)) { throw 'Unexpected instrumentation PID change' }
            $instrumentedPid = $observedPid
        }
        if ($phase -eq 'WAITING_FOR_FIXTURE_ACTIVITY') {
            & $adb -s $Serial shell input keyevent KEYCODE_WAKEUP
            & $adb -s $Serial shell input keyevent 82
            # 先带到前台并确认，native收到ACK后才创建自己持有的Activity。
            & $adb -s $Serial shell am start -W -f 0x20000 -n "$package/.AssistantActivity"
            if ($LASTEXITCODE -ne 0) { throw 'Own foreground launch failed' }
            $startupStep = [long]$status['step']
            if ($startupStep -le 0) { throw 'Missing startup handshake identifier' }
            & $adb -s $Serial shell am broadcast -p $package -a "$package.TOUCH_INPUT_FINISHED" --el step $startupStep
        } elseif ($phase -eq 'RETURN_TO_HOME_AFTER_GATE' -and $OperateSystemUi) {
            & $adb -s $Serial shell am start -f 0x20000 -n "$package/.AssistantActivity"
        } elseif ($phase -eq 'OPEN_WECHAT_FOR_FIXED_CHAT' -and $OperateSystemUi) {
            & $adb -s $Serial shell monkey -p com.tencent.mm -c android.intent.category.LAUNCHER 1
        } elseif ($phase -eq 'HOLD_STOPPED_FOR_EXTERNAL_INSPECTION') {
            $parent = [int]$status['parentPid']
            $deadline = [DateTime]::UtcNow.AddSeconds(8)
            $clean = $false
            do {
                $processes = (& $adb -s $Serial shell ps -A) -join "`n"
                $services = (& $adb -s $Serial shell dumpsys activity services $package) -join "`n"
                $display = (& $adb -s $Serial shell dumpsys display) -join "`n"
                $windows = (& $adb -s $Serial shell dumpsys window windows) -join "`n"
                $accessibility = (& $adb -s $Serial shell dumpsys accessibility) -join "`n"
                $packageInfo = (& $adb -s $Serial shell dumpsys package $package) -join "`n"
                if ($packageInfo -notmatch 'userId=(\d+)') { throw 'Own UID unavailable' }
                $ownerUid = [int]$Matches[1]
                $parentAlive = $processes -cmatch "(?m)^\S+\s+$parent\s+[^\r\n]*\scom\.codextemp\.wechatcaptureprobe\s*$"
                $worker = $processes -cmatch 'com\.codextemp\.wechatcaptureprobe:projection\s*$'
                $captureServices = [regex]::Matches($services, 'ServiceRecord\{[^\r\n]*/\.(?:AssistantCaptureService|CaptureService|ProjectionHostService)\}').Count
                $namedDisplay = Test-CaptureDisplayRecord $display
                $overlayCount = @([regex]::Split($windows, '(?=Window #)') | Where-Object {
                    $_ -match "mOwnerUid=$ownerUid\b" -and $_ -match 'ty=(?:2032|ACCESSIBILITY_OVERLAY)\b'
                }).Count
                $bound = Test-CaptureAccessibilityBound -AccessibilityDump $accessibility -ServiceDump $services
                $keyboardUnchanged = ((& $adb -s $Serial shell settings get secure default_input_method) -join "`n").Trim() -ceq $initialKeyboard
                $clean = $parentAlive -and -not $worker -and $captureServices -eq 0 -and -not $namedDisplay -and $overlayCount -eq 0 -and $bound -and $keyboardUnchanged
                if (-not $clean) { Start-Sleep -Milliseconds 300 }
            } while (-not $clean -and [DateTime]::UtcNow -lt $deadline)
            Write-Output ('LIVE_CLEANUP ' + ([ordered]@{parentPid=$parent;parentAlive=$parentAlive;worker=$worker;captureServices=$captureServices;namedDisplay=$namedDisplay;ownOverlay=$overlayCount;scopeBound=$bound;keyboardUnchanged=$keyboardUnchanged} | ConvertTo-Json -Compress))
            if (-not $clean) { throw 'Independent live-parent cleanup did not pass; no acknowledgment sent' }
            & $adb -s $Serial shell am broadcast -a "$package.TOUCH_INSPECTED" -p $package
        } elseif ($phase -eq 'VERIFY_CLIPBOARD_HOME') {
            & $adb -s $Serial shell am start -W -f 0x20000 -n "$package/.AssistantActivity"
            if ($LASTEXITCODE -ne 0) { throw 'Own clipboard verification page failed' }
            & $adb -s $Serial shell am broadcast -a "$package.TOUCH_INPUT_FINISHED" -p $package --el step ([long]$status['step'])
        } elseif ($phase -eq 'QUICK_HOME') {
            $focus = (& $adb -s $Serial shell dumpsys window) -join "`n"
            if ($focus -notmatch 'mCurrentFocus=Window\{[^\r\n]*com\.tencent\.mm/') { throw 'Expected WeChat before Home' }
            & $adb -s $Serial shell input keyevent KEYCODE_HOME
            if ($LASTEXITCODE -ne 0) { throw 'Home command failed' }
            $homeComponent = ((& $adb -s $Serial shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME) -join "`n")
            if ($LASTEXITCODE -ne 0 -or $homeComponent -notmatch '([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+') { throw 'Home component unavailable' }
            $homePackagePattern = [regex]::Escape($Matches[1])
            $homeDeadline = [DateTime]::UtcNow.AddSeconds(3)
            do {
                $homeFocus = (& $adb -s $Serial shell dumpsys window) -join "`n"
                $homeConfirmed = $homeFocus -match ('mCurrentFocus=Window\{[^\r\n]*' + $homePackagePattern + '/')
                if (-not $homeConfirmed) { Start-Sleep -Milliseconds 100 }
            } while (-not $homeConfirmed -and [DateTime]::UtcNow -lt $homeDeadline)
            if (-not $homeConfirmed) { throw 'Home focus not confirmed' }
            Write-Output 'HOME_FOCUS_CONFIRMED'
            & $adb -s $Serial shell am broadcast -a "$package.TOUCH_INPUT_FINISHED" -p $package --el step ([long]$status['step'])
        } elseif ($phase -eq 'QUICK_RETURN') {
            & $adb -s $Serial shell monkey -p com.tencent.mm -c android.intent.category.LAUNCHER 1
            if ($LASTEXITCODE -ne 0) { throw 'Return to WeChat command failed' }
            & $adb -s $Serial shell am broadcast -a "$package.TOUCH_INPUT_FINISHED" -p $package --el step ([long]$status['step'])
        } elseif ($phase -eq 'CLOSE_KEYBOARD') {
            $focus = (& $adb -s $Serial shell dumpsys window) -join "`n"
            $inputState = (& $adb -s $Serial shell dumpsys input_method | Select-String 'mInputShown=') -join "`n"
            if ($focus -notmatch 'mCurrentFocus=Window\{[^\r\n]*com\.tencent\.mm/' -or $inputState -notmatch '\bmInputShown=true\b' -or
                    $status['gesture'] -ne 'back') { throw 'Expected visible keyboard in WeChat before Back' }
            & $adb -s $Serial shell input keyevent 4
            if ($LASTEXITCODE -ne 0) { throw 'Keyboard Back command failed' }
            & $adb -s $Serial shell am broadcast -a "$package.TOUCH_INPUT_FINISHED" -p $package --el step ([long]$status['step'])
        } elseif ($phase -match '^(?:DRAG|OPEN|OUTSIDE|INSIDE|CLOSE|RETURN|MORE|PREPARE|SOURCE)_(?:LEFT|RIGHT)$|^OPEN_STOP$|^STOP$|^OPEN_KEYBOARD_SETUP$|^FOCUS_INPUT$|^KEYBOARD_(?:DRAG|OPEN|INSIDE|CLOSE)_(?:LEFT|RIGHT)$|^EXPANDED_(?:OPEN|OUTSIDE|RETURN|CLOSE)$|^DEMO_(?:ENABLE|SELECT)$|^QUICK_(?:NEXT|COPY|OPEN|MORE|PAUSE|COLLAPSE)$') {
            $timedInput = $Variant -eq 'QuickPerf' -and $phase -match '^EXPANDED_(?:OUTSIDE|RETURN)$'
            $inputFinishedAt = [long]-1
            if ($timedInput) {
                # 上传在目标核验之前；只在本轮滑动使用，失败也从finally清除自有脚本。
                $timedInputHelperUploaded = $true
                & $adb -s $Serial push (Join-Path $PSScriptRoot 'timed-swipe.sh') /data/local/tmp/chat-assistant-timed-swipe.sh | Out-Null
                if ($LASTEXITCODE -ne 0) { throw 'Timed input helper upload failed' }
            }
            $focus = (& $adb -s $Serial shell dumpsys window) -join "`n"
            if ($focus -notmatch 'mCurrentFocus=Window\{[^\r\n]*com\.tencent\.mm/') { throw "Expected WeChat focus for $phase" }
            $startX = [int]$status['startX']
            $startY = [int]$status['startY']
            $endX = [int]$status['endX']
            $endY = [int]$status['endY']
            $step = [long]$status['step']
            if ($startX -lt 0 -or $startX -ge $width -or $startY -lt 0 -or $startY -ge $height -or $step -le 0) { throw 'Invalid observed start coordinates' }
            $swipeExpected = $phase -match '^(?:DRAG|OUTSIDE|INSIDE|RETURN|PREPARE)_|^KEYBOARD_(?:DRAG|INSIDE)_|^EXPANDED_(?:OUTSIDE|RETURN)$'
            if ($VerifyInputTarget) {
                $queryTimer = [Diagnostics.Stopwatch]::StartNew()
                $inputDump = (& $adb -s $Serial shell dumpsys input) -join "`n"
                $queryTimer.Stop()
                $targets = @(Get-OwnOverlayInputTarget -InputDump $inputDump -OwnerUid $inputOwnerUid -OwnerPid $instrumentedPid)
                $hits = @($targets | Where-Object { Test-OwnOverlayInputPoint $_ $startX $startY })
                $ownExpected = $phase -notmatch '^(?:OUTSIDE|RETURN)_|^FOCUS_INPUT$|^EXPANDED_(?:OUTSIDE|RETURN)$'
                $targetRejected = $targets.Count -ne 1 -or -not $targets[0].usable -or ($ownExpected -and $hits.Count -ne 1) -or (-not $ownExpected -and $hits.Count -ne 0)
                # 失败时仅从同一份内存快照补充计数，避免为诊断再读设备或放宽触摸门槛。
                $inputObservation = if ($TraceWindows -or $targetRejected) { Get-OwnOverlayInputObservation -InputDump $inputDump -OwnerUid $inputOwnerUid -OwnerPid $instrumentedPid } else { $null }
                $inputDump = ''
                Write-Output ('INPUT_TARGET ' + ([ordered]@{phase=$phase;step=$step;horizontal=$startX;vertical=$startY;ownExpected=$ownExpected;hitCount=$hits.Count;queryMs=$queryTimer.ElapsedMilliseconds;targets=$targets;observation=$inputObservation} | ConvertTo-Json -Compress -Depth 5))
                if ($targetRejected) { throw 'Observed point does not match current owned input target; input withheld' }
                if ($phase -match '^(?:INSIDE|PREPARE)_|^KEYBOARD_INSIDE_' -and -not (Test-OwnOverlayInputPoint $targets[0] $endX $endY)) { throw 'Inside scroll end is outside current owned input target; input withheld' }
            }
            if ($swipeExpected) {
                if ($status['gesture'] -ne 'swipe' -or $endX -lt 0 -or $endX -ge $width -or $endY -lt 0 -or $endY -ge $height) { throw 'Invalid observed swipe' }
                if ($timedInput) {
                    $timingOutput = @(& $adb -s $Serial shell sh /data/local/tmp/chat-assistant-timed-swipe.sh $startX $startY $endX $endY)
                    if ($LASTEXITCODE -ne 0) { throw 'Timed input command failed' }
                    $times = @($timingOutput | Where-Object { $_ -match '^INPUT_FINISHED_SECONDS=\d+\.\d{2}$' })
                    if ($times.Count -ne 1) { throw 'Timed input clock unavailable' }
                    $seconds = [decimal]::Parse(($times[0] -replace '^INPUT_FINISHED_SECONDS=',''), [Globalization.CultureInfo]::InvariantCulture)
                    $inputFinishedAt = [long]($seconds * 1000)
                } else {
                    & $adb -s $Serial shell input swipe $startX $startY $endX $endY 450
                }
            } else {
                if ($status['gesture'] -ne 'tap') { throw 'Unexpected gesture type' }
                & $adb -s $Serial shell input tap $startX $startY
            }
            if ($LASTEXITCODE -ne 0) { throw 'Input command failed' }
            & $adb -s $Serial shell am broadcast -a "$package.TOUCH_INPUT_FINISHED" -p $package --el step $step --el inputFinishedAt $inputFinishedAt
        }
    } catch {
        $driverFailure = $_.Exception.Message
        Write-Output "DRIVER_FAILURE $driverFailure"
        if ($TraceWindows -and $instrumentedPid -gt 0) {
            & $adb -s $Serial shell am broadcast -a "$package.TOUCH_OBSERVE" -p $package
        }
    } finally {
        if ($timedInputHelperUploaded) {
            & $adb -s $Serial shell rm -f /data/local/tmp/chat-assistant-timed-swipe.sh
            if ($LASTEXITCODE -ne 0) { $driverFailure = 'Timed input helper cleanup failed' }
        }
        $status = @{}
    }
}
$process.WaitForExit()
$errors = $process.StandardError.ReadToEnd()
if ($errors) { Write-Output $errors }
if ($driverFailure -or -not $nativePassed -or $process.ExitCode -ne 0) { exit 1 }

