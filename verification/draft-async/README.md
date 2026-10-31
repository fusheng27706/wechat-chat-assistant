# 草稿工作队列与隔离回归

本目录只构建测试APK。0.18已将DraftWorker移入正式app；纯Java回归须从 `app/src/main/java/com/codextemp/wechatcaptureprobe/DraftWorker.java` 编译，不能再找已移除的本目录候选。原生build脚本排除残留DraftWorker*.class，引用装机产品类，避免测试影子实现。正式接入/指纹/证据见 `../../draft-integration-test-2026-10-25.md`；0.17历史实验仍见原报告。

## 纯Java

JDK17将正式DraftWorker.java和本目录DraftWorkerRegression.java编译到 `build/verification/draft-worker-regression`，运行 `com.codextemp.wechatcaptureprobe.DraftWorkerRegression`。1602项为虚构状态/时钟/取消/异常边界，网络和设备写入0。

## 原生隔离测试

先有与装机正式包一致的编译类，再运行本目录 `build.ps1`，正常安装 `build/verification/draft-async/draft-async.apk`。正式采集、自有范围和云端必须关闭，无真实密钥。测试只显示自有虚构页面，不开微信、不申请共享、不读图像、草稿文字或执行任何设置/发送。

运行 `adb shell am instrument -w -e variant baseline com.codextemp.wechatcaptureprobe.draftasyncverify/com.codextemp.wechatcaptureprobe.DraftDelayProbe`。其它模式为 `candidate` 和 `cancel`。

等WAITING_FOR_FOREGROUND_MAIN后，正常打开工具MainActivity使测试继续；等WAITING_FOR_EXTERNAL_TRIGGER后，发送 `com.codextemp.wechatcaptureprobe.DRAFT_DELAY_START` 广播到正式包。MIUI若询问打开“临时输入节点等待校验”，正常允许这次两个自有应用启动，不关闭安全策略。实际测量期间不做UI层级dump，避免抢占UiAutomation或干扰事件。

焦点稳定的随机nonce LIVE和实际2400ms DELAY必须匹配；通过公开窗口元数据取得实际窗口ID，不能传-1让编辑器提前拒绝。实验调用装机版AccessibilityDraftEditor，检查耗时须至少1200ms并有对应线程SDK等待栈。rootCalls字段目前是inspect次数，不是所有SDK节点调用总数。

baseline刻意按0.17旧调用方式在UI执行同一正式编辑器，不表示0.18仍从UI调用；candidate把检查放正式队列类的独立测试实例，800ms许可到期但实际槽保留；cancel在DELAY后取消票据，无回调/第二查询直至SDK返回。均无可编辑字段，不证明写入事务、真实微信或整个控制器通过。

实验结束卸载临时包、停止全部采集，自有范围保持关闭，不改变默认键盘/其他服务。正式接入已按后述分层回归，但完整写入/来源矩阵仍开放，不能用本目录绿灯替代产品验收或恢复持续待命。

## 0.18正式控制器和来源

运行 `OverlayDraftSmoke`：不申请共享、不打开微信，只在工具自己的虚构输入框执行文本动作；注入来源、控制器离屏，测试选择/保留/追加/替换/改稿/取消/超时/销毁单槽和默认来源拒绝。49项、4次自有文本设置、发送0，不代表真实微信或模型。

运行 `ComposerCapabilitySmoke`：先正常进入助手、恢复自有范围和系统共享，打开既有固定测试聊天。只做新证明、默认来源/后台节点能力、暂停/继续/旧许可撤销及明确停止，不注入AI或请求微信写入。最终23项、可写字段0，仍须手工复制/粘贴；不以其instrumentation退出证明采集释放，严格外部清理由单独ProjectionStopSmoke验证。

candidate/cancel现在测正式DraftWorker类，但保留独立虚构窗口和简单许可，不是整个AssistOverlay/真实微信时延。0.18当前数字59/77ms，旧40ms仅历史；完整矩阵未验收。
