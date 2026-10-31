# 自动分析事件回归

本目录构建独立instrumentation APK，链接正式app编译类，不复制AssistantState或AnalysisCoordinator实现。仅虚构消息和Fake Provider；scope谓词是明确注入，不代表真实微信、账号身份或模型质量验收。不打开微信、不申请共享、不点击或写入微信。

## 前置与运行

正式采集/范围/云端关闭，无真实密钥。先构建并正常安装待验证的app，再运行 `verification/analysis-events/build.ps1`，正常安装 `build/verification/analysis-events/analysis-events.apk`。

运行：

```powershell
& F:\Android\Sdk\platform-tools\adb.exe -s 9bafde2f shell am instrument -w com.codextemp.wechatcaptureprobe.analysiseventverify/com.codextemp.wechatcaptureprobe.AnalysisEventSmoke
```

测试使用ContextWrapper将设置、排除规则和记忆SharedPreferences隔离为 `analysis_event_verify_` 前缀。虚构配置/密钥和开启仅存在于隔离设置，进程URL handler拒绝全部网络，不改变真实模型配置或上传许可。结束正常删除三份隔离偏好、关闭协调器并清理虚构状态。密钥加密仍走实际Android Keystore；测试不删除共享加密别名，也不将别名存在等同真实密钥已配置。

## 证明范围

- 正式发布BASELINE/APPEND后，同内容KNOWN先于协调器poll，不应漏掉自动分析资格；最新增量类别/说明仍为KNOWN，不伪造新收到。
- 换内容、风险标记、会话token或generation不继承资格；明确GAP/AMBIGUOUS/EARLIER即使同内容也撤销，后续KNOWN不能复活。
- 实际MessageTracker纯重复文本歧义不自动猜新增；clear/leave/invalidate撤销未观察事件。
- 短暂注入scope拒绝不发送；恢复同快照可触发；连续变化重开900ms稳定等待，仍只发最终可见快照，实际请求启动间隔至少5秒。
- 完成后同快照不重复；失败仍只显示失败，不自动重试。真实网络/微信动作0。

首轮0.18在第3项复现漏触发，Fake调用0；初0.19候选曾破坏旧GAP回归，保留失败并收紧为仅KNOWN可延续。最终实测和指纹见 `../../analysis-event-test-2026-10-25.md`。本测试不证明可靠新收到、实际OCR时延、真实模型响应或所有取消竞态。

结束后卸载临时包，保留原键盘和关闭的真实云端设置。
