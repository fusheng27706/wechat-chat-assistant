package com.codextemp.wechatcaptureprobe;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** 固定几何/可控队列验证所有权；不代表Android窗口、投射或来源验收。 */
public final class FreshGeometryReadRegression {
    private static int checks;
    private static void require(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static final class Fixture {
        final ArrayDeque<Runnable> worker = new ArrayDeque<>(), main = new ArrayDeque<>();
        final long[] now = {100};
        int reads, completions, accepted;
        final ScreenGeometry shape = new ScreenGeometry(1080, 2400, 440, 0, 0, 60, 0, 100, true);
        final FreshGeometryRead read = new FreshGeometryRead(worker::add, main::add, () -> now[0], () -> { reads++; return shape; });
        boolean start() { return read.request(value -> { completions++; if (value != null) accepted++; }); }
    }
    public static void main(String[] args) {
        for (int elapsed : new int[]{199, 200, 201}) {
            Fixture f = new Fixture(); require(f.start(), "admitted");
            f.worker.remove().run(); f.now[0] += elapsed; f.main.remove().run();
            require(f.completions == 1 && f.accepted == (elapsed < 200 ? 1 : 0), "consumption-deadline");
        }
        Fixture active = new Fixture();
        require(active.start() && !active.start(), "single-pending");
        active.worker.remove().run(); Runnable old = active.main.remove(); old.run();
        require(active.start(), "next-after-consumption"); old.run();
        require(active.completions == 1 && active.accepted == 1, "duplicate-does-not-complete-new-operation");
        active.worker.remove().run(); active.main.remove().run();
        require(active.completions == 2, "new-operation-completes-once");
        for (boolean workerFinished : new boolean[]{false, true}) {
            Fixture f = new Fixture(); f.start();
            if (workerFinished) f.worker.remove().run();
            f.read.close(); f.read.close();
            require(f.completions == 1 && f.accepted == 0 && !f.start(), "close-delivers-cancellation-once");
            if (!workerFinished) f.worker.remove().run();
            f.main.remove().run();
            require(f.completions == 1, "late-after-close-ignored");
        }
        Fixture rollback = new Fixture(); rollback.start(); rollback.worker.remove().run();
        rollback.now[0] = 99; rollback.main.remove().run();
        require(rollback.completions == 1 && rollback.accepted == 0, "rollback-rejected");
        int[] cancelled = {0};
        Executor reject = task -> { throw new RejectedExecutionException(); };
        FreshGeometryRead denied = new FreshGeometryRead(reject, Runnable::run, () -> 0, () -> null);
        require(denied.request(value -> { require(value == null, "rejection-null"); cancelled[0]++; }), "rejection-owned");
        denied.close(); require(cancelled[0] == 1, "rejection-cleanup-once");
        FreshGeometryRead failed = new FreshGeometryRead(Runnable::run, Runnable::run, () -> 0,
                () -> { throw new IllegalStateException(); });
        failed.request(value -> { require(value == null, "reader-failure-null"); cancelled[0]++; });
        failed.close(); require(cancelled[0] == 2, "reader-failure-cleanup-once");
        Fixture lost = new Fixture(); lost.start(); lost.worker.remove().run(); lost.main.clear(); lost.read.close();
        require(lost.completions == 1 && lost.accepted == 0, "destroy-can-reclaim-removed-completion");
        Fixture delayed = new Fixture(); delayed.start(); delayed.now[0] = 250; delayed.worker.remove().run();
        delayed.now[0] = 301; delayed.main.remove().run();
        require(delayed.accepted == 0 && delayed.completions == 1, "worker-and-main-queue-both-counted");
        Fixture stages = new Fixture();
        int[] stageCalls = {0, 0};
        stages.read.request(value -> {
            require(value != null, "header-stage-current"); stageCalls[0]++;
            require(stages.read.request(body -> { require(body == null, "body-stage-cancelled"); stageCalls[1]++; }),
                    "continuation-can-request-independent-reading");
        });
        stages.worker.remove().run(); Runnable header = stages.main.remove(); header.run();
        header.run();
        require(stageCalls[0] == 1 && stageCalls[1] == 0, "old-stage-cannot-finish-new-stage");
        stages.read.close(); stages.worker.remove().run(); stages.main.remove().run();
        require(stageCalls[1] == 1, "close-reclaims-current-stage-once");
        System.out.println("PASS freshGeometryReadChecks=" + checks + " syntheticOnly=true");
    }
}
