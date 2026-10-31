# 不进入产品的emoji字形实验

本目录不编入 `app/`。链接当前装机正式包一致的编译类；旧0.16真实T08结果保留历史，不改成新版证据。先运行 `fetch-twemoji.ps1` 获取固定提交80份公开参考/许可并逐文件核对，再运行 `build.ps1`，安装 `build/verification/emoji-probe/emoji-probe.apk`。仅本机Android12验证；需无真实密钥、云端/局部许可关闭且无采集，不改默认键盘。

## 新增跨样式离线对照

运行 `adb shell am instrument -w -e multi_style true -e structure true -e controls_only true com.codextemp.wechatcaptureprobe.emojiverify/com.codextemp.wechatcaptureprobe.EmojiGlyphProbe`，只做合成/留出样式对照，不开微信、不申请共享或开启范围，不改模型设置。参数默认false，旧真实T08模式仍须单独普通授权，本轮未重跑。

Twemoji图形由Twitter, Inc.和其他贡献者提供，固定提交 `b6b55fef1e8636b540a6d016a4729ca8cdf2e60b`，依据CC BY 4.0保留来源/完整许可/NOTICE，仅测试APK携带原始80 PNG；不是现成截图识别器。字体整体与Twemoji互留出，不能把同源当独立测试。结构候选阈值未校准，留出67接收/22错，拒绝接入；RGB留出全部拒绝。数学1395断言不代表质量，见 `../../emoji-cross-style-test-2026-10-25.md`。

## 旧真实采集模式及边界

运行 `adb shell am instrument -w com.codextemp.wechatcaptureprobe.emojiverify/com.codextemp.wechatcaptureprobe.EmojiGlyphProbe`。先生成系统默认字体/NotoColorEmoji两套参考与合成对照，再正常打开助手、恢复自身范围服务、通过系统共享提示，停在已有T08测试消息。可正常滚动，不输入/发送。MIUI若阻止后台Activity启动，正常打开主页面；不反复重启尚在等待的instrumentation。

测试只读取正式共享会话已有Image的有界图形候选，不使用UiAutomation截图、Root/Hook或新采集权限。沿用窗口/遮挡、几何、排除和会话检查；最终版本还要求候选气泡的方向/顶部坐标与当前OCR中T08对应。帧黑图不重试。原始像素只在内存，不写文件/日志或上传；实际结果只报告固定样本是否排首、距离/名次差，不输出聊天正文或实际候选编码。已知样本只用于选择测试ROI与核对，不能参与参考图排序。

`MEASURED` 仅表示6项实验前置/取样检查完成，**不是emoji功能通过**。本机T08预期字形排名2，第一/第二距离仅差0.00010，实验阈值拒绝，未接入正式BubbleOcr。阈值不是概率，也没有经过真实数据校准。合成1440项中首位正确1252项，766项实验接收、0误接收仅限此同源小数据集；不代表微信准确率。

纯Java17可单独编译/运行 `GlyphFeature.java` 与 `GlyphFeatureRegression.java`，主类 `com.codextemp.wechatcaptureprobe.GlyphFeatureRegression`；502项仅检查数学/边界，不是识别质量验收。构建脚本会一并编译该主类，但不自动运行纯回归。

一轮关联实验发生FocusEvent等待5001ms的ANR，未查明根因。`OwnExitTraceProbe` 通过公开ApplicationExitInfo只读本应用指定PID的历史原因及主线程栈，不输出完整系统trace；运行参数 `-e pid 4609`，本机该记录reason=10、traceStreams=0，不能据此归因。后续单次定向复测有仅测试用主线程心跳/延迟栈采样，最终完成且未报采样延迟，**不等于ANR已修复**。

测试完成卸载 `com.codextemp.wechatcaptureprobe.emojiverify`，正式助手范围/采集保持关闭并独立核对状态，不改键盘或云端设置。instrumentation结束会强停目标包，不能把退出后的空资源列表当作生命周期证明。旧真实数据/失败见 `emoji-glyph-test-2026-10-25.md`，新跨样式仅合成证据见新报告。
