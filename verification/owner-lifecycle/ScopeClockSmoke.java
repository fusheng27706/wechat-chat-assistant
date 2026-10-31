package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class ScopeClockSmoke extends Instrumentation {
    private int checks;
    private long submissionDelay = -1;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        RecordingExecutor worker = new RecordingExecutor();
        try {
            ModelSettingsStore settings = new ModelSettingsStore(getTargetContext());
            require(!settings.hasKey() && !settings.cloudAllowed() && !AssistantCaptureService.running()
                    && !CaptureService.running() && !ChatGateService.enabled() && AssistantState.messages.isEmpty(),
                    "isolated-no-capture-model-or-connected-gate");
            worker.schedule(() -> { }, 0, TimeUnit.MILLISECONDS).get(2, TimeUnit.SECONDS);
            AtomicReference<Throwable> error = new AtomicReference<>();
            runOnMainSync(() -> {
                ChatGateService fixture = new ChatGateService();
                ChatGateService.Snapshot original = ChatGateService.snapshot();
                try {
                    Field connected = accessible(ChatGateService.class, "connected");
                    Field current = accessible(ChatGateService.class, "current");
                    Field active = accessible(CaptureService.class, "active");
                    Field metadata = accessible(ChatGateService.class, "metadataWorker");
                    ((ExecutorService) metadata.get(fixture)).shutdownNow();
                    metadata.set(fixture, worker);
                    Method inspect = ChatGateService.class.getDeclaredMethod("inspect", boolean.class);
                    inspect.setAccessible(true);
                    ScopeLease lease = (ScopeLease) accessible(ChatGateService.class, "lease").get(fixture);
                    connected.set(null, fixture);
                    current.set(null, new ChatGateService.Snapshot(true, -1, 0, 0, 0));
                    active.setBoolean(null, true);
                    long began = SystemClock.elapsedRealtime();
                    accessible(ChatGateService.class, "lastRequestedAt").setLong(fixture, began);
                    inspect.invoke(fixture, false);
                    SystemClock.sleep(250);
                    long submitted = worker.submittedAt.get();
                    submissionDelay = submitted < 0 ? -1 : submitted - began;
                    require(submissionDelay >= 0 && submissionDelay < 200,
                            "renewal-submission-must-not-wait-for-blocked-main-thread");
                    require(submissionDelay >= 60, "renewal-did-not-bypass-normal-request-spacing");
                    require(worker.submissions.get() == 1 && lease.pending(), "one-held-physical-slot-without-real-sdk-execution");
                    require(worker.submissionThread.get() != Looper.getMainLooper().getThread(), "renewal-submitted-off-main");
                    inspect.invoke(fixture, true);
                    inspect.invoke(fixture, false);
                    require(worker.submissions.get() == 1 && lease.pending(), "repeated-verification-does-not-open-second-held-slot");
                    connected.set(null, null);
                    active.setBoolean(null, false);
                    SystemClock.sleep(100);
                    inspect.invoke(fixture, false);
                    require(worker.submissions.get() == 1, "disconnected-idle-fixture-does-not-submit-more-work");
                } catch (Throwable failure) { error.set(failure); }
                finally {
                    try {
                        accessible(CaptureService.class, "active").setBoolean(null, false);
                        accessible(ChatGateService.class, "connected").set(null, null);
                        accessible(ChatGateService.class, "current").set(null, original);
                        ((ScopeLease) accessible(ChatGateService.class, "lease").get(fixture)).close();
                        worker.shutdownNow();
                        ((Handler) accessible(ChatGateService.class, "handler").get(fixture)).removeCallbacksAndMessages(null);
                    } catch (Throwable failure) { error.set(failure); }
                }
            });
            if (error.get() != null) throw error.get();
            require(!settings.hasKey() && !settings.cloudAllowed() && !CaptureService.running()
                    && !AssistantCaptureService.running() && !ChatGateService.enabled(), "fixture-and-model-restored");
            result.putString("result", "PASS scopeClockChecks=" + checks + " submissionDelayMs=" + submissionDelay
                    + " deliberateMainBlockMs=250 isolatedUnattachedService=true injectedExecutor=true simulatedActiveFlag=true scheduledRequests=1 realMetadataQueries=0 projectionGrants=0 modelRequests=0 wechatWrites=0 chatFiles=0");
        } catch (Throwable failure) {
            result.putString("result", "FAIL scopeClockChecks=" + checks + " submissionDelayMs=" + submissionDelay
                    + " type=" + failure.getClass().getSimpleName()
                    + (failure instanceof AssertionError ? " check=" + failure.getMessage() : ""));
        } finally { worker.shutdownNow(); }
        finish(result.getString("result").startsWith("PASS") ? -1 : 0, result);
    }

    private Field accessible(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void require(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }

    private static final class RecordingExecutor extends ScheduledThreadPoolExecutor {
        final AtomicInteger submissions = new AtomicInteger();
        final AtomicLong submittedAt = new AtomicLong(-1);
        final AtomicReference<Thread> submissionThread = new AtomicReference<>();

        RecordingExecutor() {
            super(1, action -> {
                Thread thread = new Thread(action, "scope-clock-fixture");
                thread.setDaemon(true);
                return thread;
            });
        }

        @Override public void execute(Runnable action) {
            submissions.incrementAndGet();
            submittedAt.compareAndSet(-1, SystemClock.elapsedRealtime());
            submissionThread.compareAndSet(null, Thread.currentThread());
        }
    }
}
