package com.codextemp.wechatcaptureprobe;

/** 虚构时序验证额度，不代替真实Reader回调与微信端到端验收。 */
public final class ScopeFramePreparationRegression {
    private static int checks;
    private static void require(boolean value, String name) {
        checks++; if (!value) throw new AssertionError(name);
    }
    public static void main(String[] args) {
        ScopeFramePreparation preparation = new ScopeFramePreparation();
        require(!preparation.available(-1, 1000, 0), "invalid-generation");
        require(preparation.available(20, Math.max(400, 600), 450), "overlay-stability-dominates-request");
        require(preparation.available(20, 4865, 1469), "old-reader-new-stable-scope");
        require(!preparation.needed(4864, 4865, false, true), "before-stable");
        require(preparation.needed(4865, 4865, false, true), "at-stable");
        require(!preparation.needed(4865, 4865, true, true), "sdk-busy");
        require(!preparation.needed(4865, 4865, false, false), "existing-eligible-frame");
        require(!preparation.available(20, 4865, 4865), "already-requested-at-stability");
        require(!preparation.available(20, 4865, 5000), "newer-or-future-request");
        preparation.consume(20);
        require(!preparation.available(20, 4865, 1469), "failure-cannot-reclaim");
        require(!preparation.available(20, 9000, 1469), "same-generation-geometry-reset-cannot-refill");
        require(!preparation.available(19, 9000, 1469), "old-generation-refused");
        require(preparation.available(21, 9000, 4865), "new-generation-independent");
        preparation.consume(19);
        require(!preparation.available(20, 9000, 1469), "old-callback-cannot-regress-consumption");
        preparation.consume(21);
        require(!preparation.available(21, 9000, 4865), "resume-or-eligible-frame-consumes");
        require(FreshFrameRecovery.dueAt(4865, 4865, 4613, 2508) == 5865,
                "failed-first-request-still-waits-normal-one-second");
        require(!FreshFrameRecovery.needed(5864, 4865, 4865, 4613, 2508, false, true),
                "ordinary-retry-not-shortened");
        require(FreshFrameRecovery.confirmationDueAt(4865, 4865, 4900) == 5500,
                "second-confirmation-keeps-six-hundred");
        System.out.println("PASS scopeFramePreparationChecks=" + checks + " simulatedClock=true realImages=0");
    }
}
