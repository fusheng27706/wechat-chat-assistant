package com.codextemp.wechatcaptureprobe;

final class CaptureCoverage {
    static final CaptureCoverage UNOBSTRUCTED = new CaptureCoverage(0, 0);
    final int maskedWindows;
    final int skippedBubbles;
    final boolean protectedOverlay;

    CaptureCoverage(int maskedWindows, int skippedBubbles) {
        this(maskedWindows, skippedBubbles, false);
    }

    /** 安全浮窗可能在共享帧中置空；当前几何不是原子合成证明，始终按局部快照处理。 */
    CaptureCoverage(int maskedWindows, int skippedBubbles, boolean protectedOverlay) {
        if (maskedWindows < 0 || skippedBubbles < 0 || maskedWindows > 10000 || skippedBubbles > 10000
                || maskedWindows == 0 && skippedBubbles != 0) {
            throw new IllegalArgumentException("Invalid capture coverage");
        }
        this.maskedWindows = maskedWindows;
        this.skippedBubbles = skippedBubbles;
        this.protectedOverlay = protectedOverlay;
    }

    boolean obscured() {
        return maskedWindows > 0 || protectedOverlay;
    }

    String signature() {
        return maskedWindows + ":" + skippedBubbles + (protectedOverlay ? ":partial" : "");
    }

    String warning() {
        return obscured() ? "局部快照：当前已知遮挡已排除；浮窗变化仍可能造成漏字、截断或遗漏关键上下文，请核对原文。不要据此推断完整消息、完整对话或猜补缺失内容。" : "";
    }
}
