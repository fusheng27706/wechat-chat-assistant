package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.os.Bundle;
import java.util.List;

/** 固定数据验证发布/复用/撤权的计时归属；不赋予真实来源许可、不启动采集。 */
public final class CaptureTimingSmoke extends Instrumentation {
    private int checks;
    private void require(boolean value, String code) {
        checks++;
        if (!value) throw new AssertionError(code);
    }
    private void publish(AssistantState.CaptureTiming timing, String text) {
        AssistantState.publish(7, "虚构计时", "fixture", List.of(new ChatMessage(false, text, 0)),
                timing.completedAt - timing.startedAt, new MessageTracker.Update(0, MessageTracker.Kind.KNOWN),
                CaptureCoverage.UNOBSTRUCTED, timing);
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            require(!AssistantCaptureService.running() && !CaptureService.running() && !ChatGateService.enabled(), "capture-off");
            runOnMainSync(() -> {
                boolean paused = AssistantState.paused;
                try {
                    AssistantState.clear(); AssistantState.paused = false;
                    AssistantState.CaptureTiming first = new AssistantState.CaptureTiming(100, 450, 650);
                    publish(first, "固定甲");
                    AssistantState.Snapshot snapshot = AssistantState.snapshot();
                    require(snapshot.frame == 1 && snapshot.timing == first, "first-bound");
                    require(AssistantState.analyzed(snapshot, AnalysisResult.localDemo()), "first-analysis");
                    require(AssistantState.analysisFrame == 1, "analysis-origin");
                    AnalysisResult result = AssistantState.analysis;
                    AssistantState.CaptureTiming next = new AssistantState.CaptureTiming(1000, 1350, 1450);
                    publish(next, "固定甲");
                    require(AssistantState.captureTiming == next && AssistantState.frames == 2, "new-frame-bound");
                    require(snapshot.timing == first && snapshot.frame == 1, "snapshot-immutable");
                    require(AssistantState.analysis == result && AssistantState.analysisFrame == 1, "reuse-not-relabelled");
                    require(AssistantState.analyzed(snapshot, AnalysisResult.localDemo()) && AssistantState.analysisFrame == 1,
                            "compatible-old-snapshot-keeps-origin");
                    publish(new AssistantState.CaptureTiming(2000, 2350, 2500), "固定乙");
                    require(AssistantState.analysis == null && AssistantState.analysisFrame == -1, "changed-clears-analysis");
                    require(!AssistantState.analyzed(snapshot, AnalysisResult.localDemo()), "stale-analysis-rejected");
                    AssistantState.invalidate("固定测试");
                    require(AssistantState.captureTiming == null && AssistantState.analysisFrame == -1, "invalid-clears");
                    publish(first, "固定甲"); AssistantState.leave(8);
                    require(AssistantState.captureTiming == null, "leave-clears");
                    publish(first, "固定甲"); AssistantState.clear();
                    require(AssistantState.captureTiming == null && AssistantState.frames == 0, "stop-clears");
                    AssistantState.publish(7, "虚构计时", "fixture", List.of(new ChatMessage(false, "固定甲", 0)),
                            1, new MessageTracker.Update(0, MessageTracker.Kind.BASELINE));
                    require(AssistantState.captureTiming == null, "legacy-unknown");
                } finally { AssistantState.clear(); AssistantState.paused = paused; }
            });
            output.putString("result", "PASS timingChecks=" + checks + " realCapture=false");
        } catch (Throwable failure) {
            output.putString("result", "FAIL timingChecks=" + checks + " type=" + failure.getClass().getSimpleName());
        }
        finish(output.getString("result").startsWith("PASS") ? -1 : 0, output);
    }
}
