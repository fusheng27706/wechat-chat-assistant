package com.codextemp.wechatcaptureprobe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class DraftWorkerRegression {
    private static int checks;

    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable action) { tasks.add(action); }
        void drain() { while (!tasks.isEmpty()) tasks.removeFirst().run(); }
    }

    private static final class Fixture {
        final AtomicLong clock = new AtomicLong(1000);
        final AtomicBoolean permission = new AtomicBoolean(true);
        final Queue background = new Queue();
        final Queue foreground = new Queue();
        final Queue deadlines = new Queue();
        final AtomicInteger operations = new AtomicInteger();
        final List<DraftWorker.Result<String>> results = new ArrayList<>();
        final DraftWorker worker = new DraftWorker(background, foreground,
                (action, delay) -> deadlines.execute(action), clock::get, 800);

        DraftWorker.Task<String> submit() {
            return worker.submit(permission::get, current -> {
                operations.incrementAndGet();
                return "FICTIONAL_DRAFT_8195";
            }, results::add);
        }
    }

    public static void main(String[] arguments) {
        for (int trial = 0; trial < 100; trial++) {
            Fixture complete = new Fixture();
            DraftWorker.Task<String> task = complete.submit();
            require(task != null && complete.worker.busy() && complete.submit() == null, "single-actual-worker-slot");
            complete.background.drain();
            require(!complete.worker.busy() && complete.results.isEmpty(), "result-not-delivered-on-worker");
            complete.foreground.drain();
            require(complete.results.size() == 1 && complete.results.get(0).status == DraftWorker.Status.COMPLETED
                    && complete.results.get(0).value.equals("FICTIONAL_DRAFT_8195") && !task.current(), "one-result-and-closed-ticket");
            complete.deadlines.drain();
            require(complete.results.size() == 1, "deadline-does-not-duplicate-result");

            Fixture cancelled = new Fixture();
            DraftWorker.Task<String> cancelledTask = cancelled.submit();
            cancelledTask.cancel();
            require(cancelled.worker.busy() && cancelled.submit() == null, "cancel-does-not-open-second-slot");
            cancelled.background.drain();
            cancelled.foreground.drain();
            cancelled.deadlines.drain();
            require(cancelled.operations.get() == 0 && cancelled.results.isEmpty() && !cancelled.worker.busy(), "cancelled-work-never-starts-or-delivers");

            Fixture expired = new Fixture();
            DraftWorker.Task<String> expiredTask = expired.submit();
            expired.clock.addAndGet(800);
            expired.deadlines.drain();
            require(!expiredTask.current() && expired.worker.busy() && expired.submit() == null, "timeout-preserves-actual-slot");
            expired.background.drain();
            expired.foreground.drain();
            require(expired.operations.get() == 0 && expired.results.size() == 1
                    && expired.results.get(0).status == DraftWorker.Status.EXPIRED && expired.results.get(0).value == null, "expired-task-drops-sensitive-result");

            Fixture deliveryLate = new Fixture();
            deliveryLate.submit();
            deliveryLate.background.drain();
            deliveryLate.clock.addAndGet(800);
            deliveryLate.foreground.drain();
            require(deliveryLate.results.size() == 1 && deliveryLate.results.get(0).status == DraftWorker.Status.EXPIRED
                    && deliveryLate.results.get(0).value == null, "deadline-rechecked-on-main-delivery");

            Fixture revoked = new Fixture();
            revoked.submit();
            revoked.permission.set(false);
            revoked.background.drain();
            revoked.foreground.drain();
            require(revoked.operations.get() == 0 && revoked.results.get(0).status == DraftWorker.Status.EXPIRED, "revoked-before-work");

            Fixture inFlight = new Fixture();
            AtomicInteger writes = new AtomicInteger();
            inFlight.worker.submit(inFlight.permission::get, current -> {
                inFlight.clock.addAndGet(800);
                inFlight.deadlines.drain();
                require(inFlight.worker.busy() && inFlight.submit() == null, "no-thread-churn-during-slow-sdk");
                if (current.getAsBoolean()) writes.incrementAndGet();
                return "LATE_FICTIONAL_DRAFT";
            }, inFlight.results::add);
            inFlight.background.drain();
            inFlight.foreground.drain();
            require(writes.get() == 0 && inFlight.results.size() == 1 && inFlight.results.get(0).value == null, "late-node-response-never-authorizes-write");

            Fixture afterWork = new Fixture();
            DraftWorker.Task<String> pendingDelivery = afterWork.submit();
            afterWork.background.drain();
            pendingDelivery.cancel();
            afterWork.foreground.drain();
            require(afterWork.results.isEmpty(), "cancel-after-query-before-ui-delivery");

            Fixture exception = new Fixture();
            exception.worker.<String>submit(exception.permission::get, current -> { throw new IllegalStateException(); }, exception.results::add);
            exception.background.drain();
            exception.foreground.drain();
            require(!exception.worker.busy() && exception.results.get(0).status == DraftWorker.Status.FAILED, "sdk-exception-does-not-wedge-slot");

            Fixture reverse = new Fixture();
            reverse.submit();
            reverse.clock.set(999);
            reverse.background.drain();
            reverse.foreground.drain();
            require(reverse.operations.get() == 0 && reverse.results.get(0).status == DraftWorker.Status.EXPIRED, "backwards-clock-fails-closed");

            Fixture guardLate = new Fixture();
            guardLate.worker.submit(() -> { guardLate.clock.addAndGet(800); return true; }, current -> {
                guardLate.operations.incrementAndGet();
                return "LATE_GUARD";
            }, guardLate.results::add);
            guardLate.background.drain();
            guardLate.foreground.drain();
            require(guardLate.operations.get() == 0 && guardLate.results.get(0).status == DraftWorker.Status.EXPIRED, "deadline-rechecked-after-permission-guard");
        }
        Queue delivery = new Queue();
        List<DraftWorker.Result<String>> rejected = new ArrayList<>();
        DraftWorker unavailable = new DraftWorker(action -> { throw new java.util.concurrent.RejectedExecutionException(); },
                delivery, (action, delay) -> {}, () -> 1000, 800);
        require(unavailable.submit(() -> true, current -> "NEVER", rejected::add) != null && !unavailable.busy(), "rejected-executor-releases-slot");
        delivery.drain();
        require(rejected.size() == 1 && rejected.get(0).status == DraftWorker.Status.FAILED, "rejected-executor-reports-no-retry");
        System.out.println("PASS draftWorkerChecks=" + checks + " fixtureWrites=0 modelRequests=0 chatFiles=0");
    }

    private static void require(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
