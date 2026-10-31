function Test-CaptureDisplayRecord {
    param([string]$DisplayDump)
    return $DisplayDump -cmatch '(?m)(?:DisplayDeviceInfo\{|DisplayInfo\{)"(?:WechatAssistant|WechatCaptureProbe)"[:,]|^\s*mPrimaryDisplayDevice=(?:WechatAssistant|WechatCaptureProbe)\s*$'
}
