# 正式投射所有者原生回归

先构建正式 APK，再运行此目录 `build.ps1`，安装输出的 `build/verification/owner-lifecycle/owner-lifecycle.apk`。测试必须满足无真实密钥、聊天云端关闭、没有其他采集；不允许为运行测试开启真实上传或注入真实微信假 AI。

## 未完成OCR标记回归

0.28时间一致性入口：`adb shell am instrument -w -e mode expiry com.codextemp.wechatcaptureprobe.ownerverify/com.codextemp.wechatcaptureprobe.ScopeTimeSmoke`，另一模式将expiry换validity。无共享/范围/密钥；自有监视器确认线程BLOCKED、真实elapsed创建虚构pending或等待230ms，不调时钟、不注入真实源。最终同APK99CF7920…在实际27分别第4/5项失败、28通过7/6项；finally恢复自有静态状态/lease/回调/线程。完成接受采用同样锁内取时，但没有单独受控完成等待夹具，不夸为全部并发证明，见 `../../scope-time-test-2026-10-25.md`。

0.27后台提交入口：`adb shell am instrument -w com.codextemp.wechatcaptureprobe.ownerverify/com.codextemp.wechatcaptureprobe.ScopeClockSmoke`。未附着服务/虚构active和快照，RecordingExecutor只记录SDK任务提交、不执行SDK；Main刻意阻塞250ms，同最终APK在26第2项失败、27共8通过，28复跑8/提交80ms。核对单槽及断开/空闲不续、最后恢复，不是实际元数据/OCR速度或SLA。见 `../../scope-clock-test-2026-10-25.md`。0.27起ScopeRenderSmoke关闭自己的隔离fixture worker，避免其虚构230ms渲染测试运行SDK；不关闭正式服务时钟。

0.26异步渲染入口：`adb shell am instrument -w com.codextemp.wechatcaptureprobe.ownerverify/com.codextemp.wechatcaptureprobe.ScopeRenderSmoke`。等WAITING_FOR_OWN_FIXTURE_ACTIVITY时正常打开同一Main即可，不重启等待者；不用共享/范围/密钥。未附着服务+自有ContextWrapper/离屏WindowManager，传虚构metadata/active旗标，直接跑正式acceptMetadata/render路径；最终同APK在0.25第2项失败、0.26共16通过，含执行前失效/230ms慢add跨真实租约后清空/取消重放/旧epoch/停止断开拒绝。无真实系统悬浮或微信源证明，所有虚构状态finally恢复。证据 `../../scope-render-test-2026-10-25.md`。本目录build改直接调用SDK D8 Java入口，避免本次bat命令行过长，不删测试输入。

0.24新增调度隔离入口：`adb shell am instrument -w com.codextemp.wechatcaptureprobe.ownerverify/com.codextemp.wechatcaptureprobe.ScopeCadenceSmoke`。同一APK在0.23第2项失败/0.24共11项通过，只构造未附着服务、同一主线程内设虚构active/快照，检查提前inspect真实排队、原起点截止/不推迟/断开不续，再恢复状态；零元数据查询/共享/模型/微信写入。公开Handler.hasCallbacks用于本机API31测试，产品不新增该API依赖；不是硬实时或所有远端调度证明。见 `../../scope-cadence-test-2026-10-25.md`，真实Endurance/Keyboard仍失败。

0.23新增自有窗口/旧服务回归入口：`adb shell am instrument -w com.codextemp.wechatcaptureprobe.ownerverify/com.codextemp.wechatcaptureprobe.ScopeOwnerSmoke`。无范围服务/共享/密钥前提下只构造未附着实例，用公开无来源AccessibilityEvent验证断开事件不动snapshot/lease、不发查询，再模拟连接登记epoch检查旧回调拒绝/当前72接收/断开73拒绝。旧0.22第2项实际失败，新0.23共9通过；旧失败APK另存scope-owner-baseline022.apk，新包补了登记用例，不称字节相同。不是完整Android迟到事件/遮挡或真实微信注入，见 `../../owned-window-test-2026-10-25.md`。

运行 `adb shell am instrument -w com.codextemp.wechatcaptureprobe.ownerverify/com.codextemp.wechatcaptureprobe.FrameRetrySmoke`。只在主线程构造未附着AssistantCaptureService、不执行onCreate，以虚构hash/空2×2 Bitmap调用正式finishImage；没有OCR、共享或微信写入。旧0.21第4项确实失败，新0.22共33通过，核对撤销/保留标记、不复活已清hash、释放busy/图像/flag。已处理状态由测试设置flag，不能充当真实发布/排除/新帧获取或全部恢复验收；真实Endurance仍失败，见 `../../frame-retry-test-2026-10-25.md`。

## 严格停止

运行 `com.codextemp.wechatcaptureprobe.ownerverify/com.codextemp.wechatcaptureprobe.ProjectionStopSmoke`。按阶段正常打开助手、恢复自身范围服务、通过系统共享授权，停在既有固定测试聊天，不输入或发送任何文字。

看到 `SERVICE_STOPPED_HOLD_FOR_EXTERNAL_DISPLAY_INSPECTION` 后，测试主进程仍在等待。电脑必须独立检查原主 PID 存活，工作进程/三个采集服务/虚拟显示/自有辅助悬浮消失，范围服务仍绑定；只有确认后才能发送 `com.codextemp.wechatcaptureprobe.OWNER_DISPLAY_INSPECTED` 广播。不能先结束测试或杀整个包再检查。最终本机结果为22项通过，实际框架停止1次，系统UI撤权0次。

MIUI的Bound行只显示 `Service[label=微信聊天辅助范围服务,...]`，不一定含包名。用 `AccessibilityBindingParser.ps1` 的Test-CaptureAccessibilityBound同时检查该Bound标签和精确ChatGateService的ServiceRecord，不把enabled/crashed/binding当bound；12项虚构解析回归。该联合判断是外部验证辅助，不是生产身份认证。0.18首轮因旧包名匹配误判超时，原始行证实仍绑定，修正重跑22项通过，见新草稿正式接入报告。

## 工作进程故障

运行 `OwnerFailureSmoke`，参数 `mode=worker`。同样正常共享、只读固定聊天；测试取得真实输入区证明后，仅结束已确认同 UID/精确进程名的工作 PID，主进程保持运行。到 `WAITING_FOR_CANCELLED_NEW_GRANT` 后，若系统不允许后台 Activity 弹出，应正常打开助手、点击开启，看见新系统共享提示再取消。17项通过包括旧证明拒绝、不自动恢复及取消不复用旧授权，不把后台启动受限判作产品崩溃。

## 普通诊断

运行 `OwnerFailureSmoke`，参数 `mode=diagnostic`。在工具自己的 MainActivity 点击单帧诊断、正常系统授权并留在该虚构文字页，不能切微信或其他含私人内容的应用。最终11项检查覆盖真实本地 OCR、两端释放、显示清理及主进程存活。

## 合并进程计数与资源对照

`OwnerMetricsProbe` 先验证计数能力：用正常私有服务启动同 UID 工作进程，但不提供共享授权、不创建投射或读取屏幕。核对 `/proc/<自有 PID>/stat` CPU 与公开 `Process.getElapsedCpuTime`，检查两个自有进程 PSS、无虚拟显示和工作进程清理。本机9项通过，正常共享授权0次。

`ProcessCpuSnapshotRegression` 是纯 Java 边界测试，覆盖含空格/括号进程名、错误 PID、字段缺失、负数/溢出、PID 复用、计数倒退与无效时钟；4008项通过。可用 JDK17 将它与 `ProcessCpuSnapshot.java` 编译到独立输出目录，运行 `com.codextemp.wechatcaptureprobe.ProcessCpuSnapshotRegression`，不需要手机或网络。

运行 `adb shell am instrument -w -e variant owner016 com.codextemp.wechatcaptureprobe.ownerverify/com.codextemp.wechatcaptureprobe.OwnerPerformanceSmoke`。对照旧包使用 `variant baseline015`，测试核对安装版本与预期工作进程数。正常开启本工具范围服务、系统共享并只读既有固定聊天，不输入或发送。

8秒预热、静置60秒后等待 `WAITING_SCROLL_TRIGGER`。先重置微信 gfxinfo，发送 `com.codextemp.wechatcaptureprobe.OWNER_PERF_SCROLL`，随后同样30对上下滑动（每次350ms、间隔500ms）。滚动60秒、恢复30秒，等待 `WAITING_OUTSIDE_TRIGGER`；回桌面后发送 `.OWNER_PERF_OUTSIDE`。范围外20秒后等待 `WAITING_RETURN_TRIGGER`，正常打开微信再发送 `.OWNER_PERF_RETURN`，观察真实文字恢复并静置20秒。广播前缀均为 `com.codextemp.wechatcaptureprobe`。两个版本必须分别正常授权，不复用已结束的共享票据；对照结束恢复最新正式包。

日志仅 `OwnerPerformance` 标签的数值/阶段/固定样本代码，不写原始聊天或截图。CPU使用真实 `_SC_CLK_TCK`、PID/启动时刻一致性和两端计数之和，百分比为单核等效，不是整机使用率。PSS是公开 API 的阶段端点且可能被系统缓存/限频，不是峰值；ART分配/GC、主线程排队和原生分配仅主进程，不能作为工作进程或整机合计。`processingRounds` 是应用处理轮数，不是SDK实际OCR调用数。返回计时从测试收到广播到发布，不是新消息到达或AI端到端；一轮顺序样本不证明功耗、长时间稳定性或普遍收益。

测试完成卸载 `com.codextemp.wechatcaptureprobe.ownerverify`，按当前保守测试策略正常关闭自有范围和全部采集，不按旧报告恢复持续待命。instrumentation 正常结束会强停目标包，不能把这一步当作工作进程清理证据。详细通过/失败、最终APK指纹及未验证范围见根目录 `projection-owner-test-2026-10-25.md` 和后续0.17报告。

## 外部单PID故障与通知入口停止

`ObserveCaptureExit.ps1` 不需要安装测试APK，也不运行目标包instrumentation。先正常授权并停在固定测试聊天，等待实际读取，确认无密钥/云端/局部许可；脚本会复核自有UID、两个PID、采集/显示/悬浮与工作OPEN记录。

运行 `pwsh -NoProfile -File verification/owner-lifecycle/ObserveCaptureExit.ps1 -Mode ParentCrash`，仅用系统 `am crash --user <用户> <主PID>` 故障注入。原主必须死亡、工作端实际父Binder失联reason=3、外部资源消失且6秒不自动恢复。本机最终17项通过，不等于SIGKILL/低内存全矩阵。不要改成包名崩溃、force-stop或instrumentation结束清空UID。

`-Mode NotificationEntryStop` 不注入停止命令。等脚本WAITING后，正常展开通知栏并等待动画，点本工具通知正文；确认AssistantActivity真实前台后，点击“停止辅助并清空”。观察器从新STOP数值日志开始10秒清理窗口，主PID必须保留、工作端reason=1、外部资源清理并稳定6秒；本机16项通过。这不是直接通知动作按钮或系统UI撤权。

显示断言只用 `CaptureDisplayParser.ps1` 精确匹配真实设备/逻辑显示记录，不能用不区分大小写的包名子串匹配。运行 `pwsh -NoProfile -File verification/owner-lifecycle/CaptureDisplayParserRegression.ps1`，32项通过。失败/假阳性/动画时序与最终独立证据见 `owner-exit-test-2026-10-25.md`；不要以旧观察超时为理由自动重启尚在运行的采集。

## 通知动作直接停止（0.17）

同观察器 `-Mode NotificationActionStop`，须先有固定聊天实际读取、主/工作进程、采集/显示/悬浮，且无活动instrumentation。等 `WAITING_NOTIFICATION_ACTION_STOP` 后，展开通知、等动画并核对本工具卡片。本机普通长按卡片可打开MIUI操作面板，点卡片内“停止辅助”，不点通知正文或改“允许通知”。实际SystemUI窗名为 `NotificationModalWindowManager`，不能只认StatusBar/NotificationShade。

新模式不发停止命令；除原清理检查，还检查初始和观察快照未见自有Activity resumed。快照不是连续追踪，须结合可见按钮/点击及源码服务PendingIntent。两次各18项通过，工作实际reason1/释放true、原主存活、清理后6秒不恢复。未点击动作的150秒超时仍是失败，手势注入成功也不是停止成功。

这不是系统共享撤权，当前该次数仍0；投屏设置未管理本工具共享，不能拿其关闭状态或media_projection的null当清理证据。完整记录和边界见根目录 `notification-action-test-2026-10-25.md`。该模式只需普通UI和外部脚本，不需Android测试包。
