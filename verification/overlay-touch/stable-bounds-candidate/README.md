# 稳定裁剪候选（未启用）

当前v2保留于此隔离目录，正式源码与手机已撤回原包。运行verification/overlay-touch/test-stable-bounds.ps1，469断言/87平移案例通过；目录不进入app或常规测试APK。

v1真机全部回退；v2连通性和范围修正后真实生效，但首次键盘测试FAIL11/1，原包对照PASS48/8。缓存命中不能证明OCR完整性或净收益，不能直接复制到正式源码。候选APK保存在build/connected-bounds-baseline-041。

详见docs/测试分析/2026-10-27-连通裁剪真机内容回归与撤回.md。下一步需固定语料验证SDK全文与图形识别等价，并核对遮挡影响；不可放宽原判据。
