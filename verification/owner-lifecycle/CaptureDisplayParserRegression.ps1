$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\CaptureDisplayParser.ps1"
$checks = 0
foreach ($name in @('WechatAssistant', 'WechatCaptureProbe')) {
    foreach ($record in @("DisplayDeviceInfo{`"${name}`": uniqueId=virtual:sample}",
            "mBaseDisplayInfo=DisplayInfo{`"${name}`", displayId 70}", "mPrimaryDisplayDevice=$name")) {
        foreach ($prefix in @('', '  ')) {
            foreach ($ending in @("`n", "`r`n")) {
                $checks++
                if (-not (Test-CaptureDisplayRecord -DisplayDump ($prefix + $record + $ending))) {
                    throw 'Actual capture display record rejected'
                }
            }
        }
    }
}
foreach ($record in @('mForegroundAppPackageName=com.codextemp.wechatcaptureprobe',
        'mForegroundAppPackageName=com.codextemp.WechatCaptureProbe',
        'DisplayDeviceInfo{"Built-in Screen": owner com.codextemp.wechatcaptureprobe}',
        'DisplayInfo{"OtherDisplay", owner com.codextemp.wechatcaptureprobe}',
        'mPrimaryDisplayDevice=WechatAssistantSuffix', 'mPrimaryDisplayDevice=PrefixWechatCaptureProbe',
        'connectionName="WechatAssistant"', '')) {
    $checks++
    if (Test-CaptureDisplayRecord -DisplayDump $record) {
        throw 'Non-display metadata incorrectly treated as capture display'
    }
}
Write-Output "PASS captureDisplayParserChecks=$checks network=0 deviceWrites=0 chatFiles=0"
