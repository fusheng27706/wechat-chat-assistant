# 交互事件计时候选（未启用）

test-interaction-clock.ps1仅编译这里的ChatInteraction与固定36测试。正式依旧收到事件后450ms，不改权限。

DiagnosticChatGateService.java是临时数字日志快照，不可直接加入源集。真机仅观测到2条滚动102/106ms延迟，首次QuickPerf失败10/1，未形成完整对照。迟到事件造成建议过早重新出现的风险尚未验证，不能直接上线。

见docs/测试分析/2026-10-27-交互事件延迟与首次性能失败.md。
