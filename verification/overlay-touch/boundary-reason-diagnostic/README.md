# 已撤下的发布帧诊断入口

本目录是本轮临时源码快照，不进入正式app或默认临时测试APK。run.ps1已随源码归档，其相对目录依赖原位置，不能直接在此运行。当前正式入口不提供TraceBounds。

临时FrameObserver仅在withFreshGeometry成功并publish后同步借用该帧，BoundaryReasonJournal最多记录8条数值，不保存图像访问器。测试完成后正式service恢复备份，Journal移至本目录，驱动/测试入口删除TraceBounds。

仍可用上级test-boundary-reasons.ps1运行纯逻辑原因码检查。真机结果与包指纹见docs/测试分析/2026-10-27-稳定裁剪回退原因定位.md。
