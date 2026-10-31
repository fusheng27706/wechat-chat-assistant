# 范围查询阻塞的独立复现与0.17回归

0.25新增`ScopeQueryTraceRegression`，同样链接正式编译类运行同包纯Java主类，14项检查默认关闭/数字身份/缺失标记/活Entry与列表副本/有界淘汰/reset迟到/不改lease真实槽和时限。正式Trace默认关闭，只有独立TraceWindows测试start；64条冻结数字并非全量或原子，见 `../../scope-query-timing-test-2026-10-25.md`。Keyboard57通过，Endurance仍失败，不编入Android夹具或冒充真实模型。

0.23新增`OwnWindowIdsRegression`，以正式编译类为classpath、JDK17编译到build/verification的纯Java目录，运行同包主类347212项；覆盖有界登记/淘汰/并发、零/负ID、迟到事件、包名及overlay类型限制、未知ID拒绝/连接清空。多为数值模型枚举，不是相同数量真机情况；不要将Regression.class编入Android夹具。真实Keyboard57通过、Endurance仍失败，见 `../../owned-window-test-2026-10-25.md`。

0.21后续：`ScopeLeaseRegression`新增重叠续期、旧证书不复活、事件/关闭/自身超时/回退等，先在0.20实际失败，最终41780项并以正式编译类重跑通过。修复只清自然到期accepted、不误废弃仍新鲜pending；200ms及真实单槽保留。本机Endurance900和Keyboard仍失败，不把纯逻辑绿灯当稳定性通过；见 `../../scope-renewal-test-2026-10-25.md`。以下36014为旧版计数，不覆盖新失败。

本目录不编入产品。先前复现0.16风险，后续用于0.16/0.17同计数器对照及新纯逻辑回归。测试不申请屏幕共享、不读取图像或微信正文，不改键盘。产品变化/指纹/真实微信回归见 `../../async-scope-test-2026-10-25.md`。

先构建正式APK，再运行本目录 `build.ps1`，安装 `build/verification/scope-metadata/scope-metadata.apk`。运行 `adb shell am instrument -w com.codextemp.wechatcaptureprobe.scopeverify/com.codextemp.wechatcaptureprobe.ScopeDelayProbe`。

正常打开工具主页面/助手，开启本工具范围服务；不点击共享。等 `WAITING_FOR_SCOPE_AND_TRIGGER`、UI自动化结束且范围服务已绑定后，发送 `com.codextemp.wechatcaptureprobe.SCOPE_DELAY_START` 广播。MIUI若询问“微信采集验证器想打开临时窗口元数据验证”，正常点允许，只针对这两个自有应用；不要用关闭安全策略或扩大权限代替。首次安装也须正常确认USB安装提示。

独立`:slow`进程的虚构页面只在一次节点初始化中延迟2400ms，10秒后自动关闭。通过本轮随机nonce广播确认页面实际出现、延迟回调实际执行；缺少握手不能算有效测量。测试每100ms至多挂起一个本工具主线程任务，超过1200ms取本进程主线程类/方法栈；不输出参数/正文。范围服务在夹具期间必须保持连接，虚构页面不得被接受为微信，采集和模型始终关闭。

历史入口队列计数器主PID12248最大排队2435ms，栈指向正式 `ChatGateService.inspect -> getRootInActiveWindow -> AccessibilityInteractionClient.waitForResultTimedLocked`。现保留源码每个主任务明确执行生产 `verify()`，记录投递到执行完成的 `mainTaskP95Ms/MaxMs`，因0.17空闲时不主动查根，不能只采不调用验证的空心跳。增加实际工作线程等待栈取样。

同新版计数器重新安装原0.16：99样本、p95/最大54/2426ms；最终0.17：105样本、3/42ms、`workerWaitSampled=true`，无效来源放行0。首异步候选4/52ms虽小但真实采集失败，不用它代替最终包42ms的回归。各轮 `scopeDelayChecks=7` 仅是复现有效性/边界检查，不是7种性能场景；原emoji实验ANR仍不能确定归因。

准备/系统确认等待也进入队列采样，p95不代表真实微信滚动或通用延迟。首版未做实际夹具握手，曾在启动确认没点时输出23ms，已判为无效，不能拿它当正常控制组。

纯Java回归：`ScopeLeaseRegression` 检查单槽、时钟/事件/关闭/跨实例证书、迟到结果，36014项；`MaskedRgbaRowsRegression` 的注入Source遇到遮蔽、其它窗格或行padding读取即抛错，160组随机视口/重叠掩码等共12350582项、禁止读取0。两者用JDK17、正式编译类目录作classpath编译到各自的 `build/verification/*-regression`，运行同包主类；不要将 `*Regression.class` 编入临时Android夹具。

运行结束卸载 `com.codextemp.wechatcaptureprobe.scopeverify`。最终安装0.17，收尾正常关闭本工具范围服务和所有采集，不按历史默认持续待命。只在明确测试窗口开启，不扩大读取范围或降低保护换取通过。旧失败保留在 `../../scope-metadata-test-2026-10-25.md`；200ms有界证书、主动输入根查询、非原子来源/未知覆盖层、原ANR和完整未验收边界见0.17报告。
