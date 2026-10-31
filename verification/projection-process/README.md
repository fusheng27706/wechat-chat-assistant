# 独立投射进程生命周期实验

这是独立的虚构纯色测试 APK，不属于 `app/` 的产品构建，不打开微信、不做 OCR/模型调用。用于验证本机 Android 12 在投射停止后保留逻辑显示记录时，能否仅结束独立采集进程、保持主进程存活并清理显示。实验成功不等于正式助手已经集成或修复。

## 运行

1. 执行 `verification\projection-process\build.ps1`，输出在忽略目录 `build\verification\projection-process\projection-process.apk`。
2. 正常安装 APK，打开 `com.codextemp.projectionprocessverify/.ProjectionFixtureActivity`，按手机系统提示授权。测试期间只留在这张虚构纯色页面。
3. 第一轮点击开始，正常共享授权，等“已收到实际纯色帧”；点击“停止投射”，等两秒检查显示记录，再点“结束已停止的独立采集进程”。
4. 第二轮重复上述过程，必须再次通过正常共享授权；主进程保持同一 PID，采集子进程/虚拟显示必须换新。
5. 第三轮再次正常授权，收到实际帧后，只点“模拟采集子进程意外退出”。验证真实 Binder 死亡、主进程关闭 Reader 和系统移除显示。
6. 只读取 `ProjectionProcessVerification` 的标量日志；另用 `ps`、`dumpsys display`、`dumpsys activity services` 独立核对。完成后卸载 `com.codextemp.projectionprocessverify`。

本机最终结果为 `PASS isolatedCleanupChecks=54 actualProjectionStops=2 abruptHelperDeaths=1 normalSystemGrants=3`。详见根目录 `projection-process-test-2026-10-25.md`。

## 验证边界

- Surface 在主进程的 ImageReader 上创建，副本经正常 Parcelable 传给 `:projection` 服务；投射与虚拟显示只由采集子进程持有。
- 正常停止先调用真实 `MediaProjection.stop()`，回调后释放显示与 Surface，先保留进程以观察残留，再只结束已经停止的采集子进程；不是提前释放显示来规避复现。
- 主进程持有子进程的真实 Binder 并监听死亡；正常退出和意外退出都必须关闭接收端、确认显示不存在。第三轮故意不走子进程资源释放，验证 OS/失联路径，不是调用普通停止冒充崩溃。
- PID/进程名保护仅允许这个测试包的 `:projection` 子进程退出；服务不导出。主进程、正式助手和其他应用不在结束目标中。
- 只有本机 API 31 证据；退出保护使用 API 28+ 进程名，API 26–27 不会执行进程结束，也没有兼容验收。
- `CRASH` 控制、测试结果接收器、纯色采样以及手动分阶段按钮仅用于实验，不能原样复制成生产控制面。正式集成还需要会话/几何版本化、IPC 失败关闭、通知/互斥、投射回调及全套采集回归。
