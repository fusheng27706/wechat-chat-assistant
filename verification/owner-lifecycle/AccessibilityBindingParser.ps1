function Test-CaptureAccessibilityBound {
    param([string]$AccessibilityDump, [string]$ServiceDump)
    $service = $ServiceDump -cmatch 'ServiceRecord\{[^\r\n]*com\.codextemp\.wechatcaptureprobe/\.ChatGateService\}'
    # 多服务可换行；只在完整闭合的Bound段内查找，不能借用Enabled/Crashed段的名称。
    $section = [regex]::Match($AccessibilityDump, '(?m)^[ \t]*Bound services:\{([^{}]*)\}')
    $bound = $section.Success -and $section.Groups[1].Value -cmatch 'Service\[label=微信聊天辅助范围服务,'
    return $service -and $bound
}
