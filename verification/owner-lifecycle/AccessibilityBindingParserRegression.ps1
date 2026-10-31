$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\AccessibilityBindingParser.ps1"
$checks = 0
$record = ' * ServiceRecord{123 u0 com.codextemp.wechatcaptureprobe/.ChatGateService}'
$bound = '     Bound services:{Service[label=微信聊天辅助范围服务, feedbackType[FEEDBACK_GENERIC], capabilities=1]}'
function Require {
    param([bool]$Condition, [string]$Name)
    $script:checks++
    if (-not $Condition) { throw "Accessibility binding parser failed: $Name" }
}
Require (Test-CaptureAccessibilityBound -AccessibilityDump $bound -ServiceDump $record) 'miui-label-only-bound-with-exact-service'
Require (Test-CaptureAccessibilityBound -AccessibilityDump ("User state`r`n$bound`r`nCrashed services:{}") -ServiceDump $record) 'multiline-dump'
Require (Test-CaptureAccessibilityBound -AccessibilityDump $bound -ServiceDump ("Bindings`n$record`napp=own-process")) 'service-record-in-metadata'
Require (-not (Test-CaptureAccessibilityBound -AccessibilityDump $bound -ServiceDump '')) 'label-alone-is-not-proof'
Require (-not (Test-CaptureAccessibilityBound -AccessibilityDump 'Bound services:{}' -ServiceDump $record)) 'service-alone-is-not-proof'
Require (-not (Test-CaptureAccessibilityBound -AccessibilityDump 'Enabled services:{com.codextemp.wechatcaptureprobe/ChatGateService}' -ServiceDump $record)) 'enabled-is-not-bound'
Require (-not (Test-CaptureAccessibilityBound -AccessibilityDump 'Crashed services:{Service[label=微信聊天辅助范围服务, capabilities=1]}' -ServiceDump $record)) 'crashed-is-not-bound'
Require (-not (Test-CaptureAccessibilityBound -AccessibilityDump 'Binding services:{Service[label=微信聊天辅助范围服务, capabilities=1]}' -ServiceDump $record)) 'binding-is-not-bound'
Require (-not (Test-CaptureAccessibilityBound -AccessibilityDump 'Bound services:{Service[label=其他范围服务, capabilities=1]}' -ServiceDump $record)) 'other-label-rejected'
Require (-not (Test-CaptureAccessibilityBound -AccessibilityDump $bound -ServiceDump 'ServiceRecord{123 u0 com.other.app/.ChatGateService}')) 'other-package-rejected'
Require (-not (Test-CaptureAccessibilityBound -AccessibilityDump $bound -ServiceDump 'ServiceRecord{123 u0 com.codextemp.wechatcaptureprobe/.ChatGateServiceOther}')) 'service-prefix-rejected'
Require (-not (Test-CaptureAccessibilityBound -AccessibilityDump '' -ServiceDump '')) 'empty-dumps-rejected'
$wrapped = "     Bound services:{Service[label=其他服务, capabilities=8],`r`n Service[label=微信聊天辅助范围服务, capabilities=1]}"
Require (Test-CaptureAccessibilityBound -AccessibilityDump $wrapped -ServiceDump $record) 'wrapped-bound-record'
Require (-not (Test-CaptureAccessibilityBound -AccessibilityDump "Bound services:{}`nCrashed services:{Service[label=微信聊天辅助范围服务, capabilities=1]}" -ServiceDump $record)) 'wrapped-search-cannot-cross-section'
Require (-not (Test-CaptureAccessibilityBound -AccessibilityDump "Bound services:{Service[label=其他服务, capabilities=8],`nCrashed services:{Service[label=微信聊天辅助范围服务, capabilities=1]}" -ServiceDump $record)) 'unclosed-section-rejected'
Write-Output "PASS accessibilityBindingParserChecks=$checks network=0 deviceWrites=0 chatFiles=0"
