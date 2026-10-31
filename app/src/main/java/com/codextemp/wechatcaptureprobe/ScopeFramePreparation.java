package com.codextemp.wechatcaptureprobe;

/** 新范围的首帧请求额度；只决定请求时机，不证明来源、图像或识别结果有效。 */
final class ScopeFramePreparation {
    private long consumedGeneration = -1;

    boolean available(long generation, long stableAt, long requestedAt) {
        return generation > consumedGeneration && requestedAt < stableAt;
    }

    boolean needed(long now, long stableAt, boolean busy, boolean missingEligibleFrame) {
        return !busy && missingEligibleFrame && now >= stableAt;
    }

    /** 创建前消费；失败、零帧及同范围重复重置均不补回，过期代次不能覆盖新代次。 */
    void consume(long generation) {
        consumedGeneration = Math.max(consumedGeneration, generation);
    }
}
