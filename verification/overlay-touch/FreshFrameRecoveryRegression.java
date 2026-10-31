package com.codextemp.wechatcaptureprobe;

/** 虚构时间线的纯逻辑回归，不创建投射、不读取微信；不代表真实产帧成功。 */
public final class FreshFrameRecoveryRegression {
    private static int checks;
    public static void main(String[] args) {
        // 早到帧的到达时间是事实：增加 now 永远不能令它越过既定门槛。
        long arrived = 1100, gateEntered = 1000, geometryEntered = 1000;
        long stable = Math.max(gateEntered + 250, geometryEntered + 350);
        require(arrived < stable, "initial-frame-is-too-early");
        for (long now : new long[]{1350, 2000, 15000, 60000}) {
            require(arrived < stable, "waiting-cannot-refresh-original-arrival");
            require(FreshFrameRecovery.needed(now, stable, 1000, arrived, 0, false, true) == (now >= 2100),
                    "request-fresh-output-after-quiet-interval");
        }
        require(!FreshFrameRecovery.needed(2099, stable, 1000, arrived, 0, false, true), "interval-lower-bound");
        require(FreshFrameRecovery.needed(2100, stable, 1000, arrived, 0, false, true), "interval-inclusive-bound");
        require(!FreshFrameRecovery.needed(4000, 4001, 1000, 1100, 0, false, true), "cannot-skip-stability");
        require(!FreshFrameRecovery.needed(4000, stable, 3100, 1100, 0, false, true), "zero-frame-request-throttled");
        require(!FreshFrameRecovery.needed(4000, stable, 1000, 3100, 0, false, true), "recent-arrival-throttled");
        require(!FreshFrameRecovery.needed(4000, stable, 1000, 1100, 3100, false, true), "recent-ocr-throttled");
        require(!FreshFrameRecovery.needed(4000, stable, 1000, 1100, 0, true, true), "busy-ocr-keeps-reader");
        require(!FreshFrameRecovery.needed(4000, stable, 1000, 1100, 0, false, false), "eligible-frame-or-published-result-no-refresh");
        require(!FreshFrameRecovery.needed(2000, stable, 3000, 1100, 0, false, true), "clock-regression-refused");
        // 静止页面始终零帧时也不能每个80ms轮询重建；成功/失败都按请求时间节流。
        long lastRequest = 1000;
        int refreshes = 0;
        for (long now = 1000; now < 11000; now += 80) {
            if (FreshFrameRecovery.needed(now, stable, lastRequest, 0, 0, false, true)) {
                require(now - lastRequest >= 1000, "retry-spacing");
                lastRequest = now; refreshes++;
            }
        }
        require(refreshes == 9, "bounded-zero-frame-retries");
        require(FreshFrameRecovery.dueAt(1350, 1000, 1100, 0) == 2100, "arrival-deadline");
        require(FreshFrameRecovery.dueAt(5000, 1000, 1100, 0) == 5000, "stability-deadline");
        require(FreshFrameRecovery.dueAt(0, 3100, 1100, 2000) == 4100, "request-deadline");
        require(FreshFrameRecovery.dueAt(0, 1000, 1100, 3100) == 4100, "ocr-deadline");
        require(FreshFrameRecovery.dueAt(0, Long.MAX_VALUE - 10, 0, 0) == Long.MAX_VALUE,
                "overflow-cannot-schedule-immediately");
        for (long offset = 0; offset < 200; offset++) {
            long due = FreshFrameRecovery.dueAt(1350, 1000, 1100 + offset, 0);
            require(!FreshFrameRecovery.needed(due - 1, 1350, 1000, 1100 + offset, 0, false, true),
                    "deadline-does-not-shorten-interval");
            require(FreshFrameRecovery.needed(due, 1350, 1000, 1100 + offset, 0, false, true),
                    "deadline-ready-without-next-periodic-tick");
            require(!FreshFrameRecovery.needed(due, 1350, 1000, 1100 + offset, 0, true, true),
                    "deadline-cannot-release-busy-slot");
        }
        System.out.println("PASS freshFrameRecoveryChecks=" + checks + " simulatedClock=true realImages=0");
    }
    private static void require(boolean value, String name) {
        checks++; if (!value) throw new AssertionError(name);
    }
}
