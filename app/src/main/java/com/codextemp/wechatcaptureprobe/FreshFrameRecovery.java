package com.codextemp.wechatcaptureprobe;

/** 静止页面可能不再产帧；仅决定何时请求新输出，不能赋予旧图新的来源或时间。 */
final class FreshFrameRecovery {
    private static final long RETRY_INTERVAL_MS = 1000;

    static boolean needed(long now, long stableAt, long requestedAt, long arrivedAt, long ocrAt,
                          boolean busy, boolean missingEligibleFrame) {
        if (busy || !missingEligibleFrame || now < stableAt) return false;
        long lastWork = Math.max(requestedAt, Math.max(arrivedAt, ocrAt));
        return now >= lastWork && now - lastWork >= RETRY_INTERVAL_MS;
    }

    /** 原重试间隔的截止时刻；仅预约检查，不赋予新帧或来源许可。 */
    static long dueAt(long stableAt, long requestedAt, long arrivedAt, long ocrAt) {
        long lastWork = Math.max(requestedAt, Math.max(arrivedAt, ocrAt));
        return deadline(stableAt, lastWork, RETRY_INTERVAL_MS);
    }

    /** 仅供尚未消费的一次第二确认帧机会；普通恢复间隔不变，OCR仍独立节流。 */
    static long confirmationDueAt(long stableAt, long requestedAt, long arrivedAt) {
        return deadline(stableAt, Math.max(requestedAt, arrivedAt), 600);
    }

    static boolean confirmationNeeded(long now, long stableAt, long requestedAt, long arrivedAt,
                                      boolean busy, boolean missingEligibleFrame) {
        return !busy && missingEligibleFrame && now >= confirmationDueAt(stableAt, requestedAt, arrivedAt);
    }

    private static long deadline(long stableAt, long lastWork, long interval) {
        long retryAt = lastWork > Long.MAX_VALUE - interval
                ? Long.MAX_VALUE : lastWork + interval;
        return Math.max(stableAt, retryAt);
    }

    private FreshFrameRecovery() { }
}
