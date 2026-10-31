# 逐像素网格隔离筛查（未接入生产）

`../test-pixel-grid.ps1` 从正式 ChatLayout 临时生成 PixelChatLayout 到 build/verification/pixel-grid-candidate，只将扫描 step 改为1。生成类不在 app 或原生 instrumentation 的源码集合内。

GridTranslationProbe 使用固定虚构圆角气泡和三类笔画，覆盖2宽度、2主题、2方向、6×6整数平移，共864例。比较检测框平移稳定性、固定墨点是否被裁掉及读取像素次数。没有手机图像、真实聊天、SDK、遮挡或键盘验证。

2026-10-27首次失败 `fine-fixed-ink-coverage`。仅补充输出后仍失败：逐像素框平移变化0，但贴边闭合笔画288例丢墨点（原粗网格236例）。不能因为平移更稳定就上线，也不能把合成笔画称为已证实的微信漏字。脚本故意保留原质量门槛，当前执行会退出非零；它是被否决候选的筛查证据，不是生产回归测试入口。

报告：docs/测试分析/2026-10-27-逐像素扫描稳定性与墨点覆盖筛查.md。
