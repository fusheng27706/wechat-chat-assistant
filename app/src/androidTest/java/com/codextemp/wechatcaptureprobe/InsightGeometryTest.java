package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.os.Bundle;

/**
 * 验证消息级 insight 标注条的定位算法正确性（纯逻辑，不启动投射、不读微信）。
 * 提取自 AssistOverlay.updateInsight 的核心几何约束。
 */
public final class InsightGeometryTest extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void require(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }

    private interface Dp { int apply(int value); }

    /** 复刻 updateInsight 的几何判定与定位，返回标注条目标 y（-1 表示不显示）。 */
    private int computeInsightY(int viewportHeight, int bottomInset, int topInset,
                                int anchorBottom, int inputBarHeightDp, int gapThresholdDp, float density) {
        Dp dp = v -> Math.round(v * density);
        int windowBottom = viewportHeight - bottomInset;
        int inputTop = windowBottom - dp.apply(inputBarHeightDp);
        int screenBottom = topInset + anchorBottom;
        if (inputTop - screenBottom < dp.apply(gapThresholdDp)) {
            return -1;
        }
        int minY = topInset + dp.apply(48);
        int maxY = inputTop - dp.apply(2);
        return Math.max(minY, Math.min(screenBottom + dp.apply(2), maxY));
    }

    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            float density = 3.0f;

            // 间隙 36dp → 显示，落在气泡下方、输入区上方
            int y1 = computeInsightY(800, 0, 24, 500, 56, 16, density);
            int screenBottom1 = 24 + 500;
            int inputTop1 = 800 - Math.round(56 * density);
            require(y1 != -1, "gap-36dp-shows");
            require(y1 >= screenBottom1 + Math.round(2 * density) - 1 && y1 <= inputTop1 - Math.round(2 * density) + 1,
                    "gap-36dp-below-bubble");
            require(y1 < inputTop1, "gap-36dp-above-input");

            // 间隙 < 16dp → 不显示
            require(computeInsightY(800, 0, 24, 600, 56, 16, density) == -1, "gap-small-hidden");

            // 间隙恰好 16dp → 显示（边界）
            require(computeInsightY(800, 0, 24, 560, 56, 16, density) != -1, "gap-exact-16dp-shows");

            // 间隙 0dp → 不显示
            require(computeInsightY(800, 0, 24, 608, 56, 16, density) == -1, "gap-0dp-hidden");

            // bottomInset 存在时输入区顶部相应上移，仍不遮挡输入框
            int y5 = computeInsightY(800, 24, 24, 500, 56, 16, density);
            int inputTop5 = 800 - 24 - Math.round(56 * density);
            require(y5 != -1, "bottom-inset-shows");
            require(y5 <= inputTop5 - Math.round(2 * density) + 1, "bottom-inset-below-input");

            output.putString("result", "PASS insightGeometryChecks=" + checks);
        } catch (Throwable failure) {
            output.putString("result", "FAIL insightGeometryChecks=" + checks
                    + " type=" + failure.getClass().getSimpleName() + " check=" + failure.getMessage());
        }
        finish(output.getString("result").startsWith("PASS") ? -1 : 0, output);
    }
}
