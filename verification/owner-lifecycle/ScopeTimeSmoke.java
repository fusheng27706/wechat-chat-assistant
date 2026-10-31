package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ScopeTimeSmoke extends Instrumentation {
    private int checks;
    private String mode;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        mode = arguments.getString("mode", "expiry");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            ModelSettingsStore settings = new ModelSettingsStore(getTargetContext());
            require(!settings.hasKey() && !settings.cloudAllowed() && !AssistantCaptureService.running()
                    && !CaptureService.running() && !ChatGateService.enabled() && AssistantState.messages.isEmpty(),
                    "isolated-no-capture-model-or-connected-gate");
            if ("expiry".equals(mode)) expiry();
            else if ("validity".equals(mode)) validity();
            else throw new AssertionError("unknown-fixture-mode");
            require(!settings.hasKey() && !settings.cloudAllowed() && !ChatGateService.enabled()
                    && !AssistantCaptureService.running() && !CaptureService.running(), "fixture-and-model-restored");
            result.putString("result", "PASS scopeTimeChecks=" + checks + " mode=" + mode
                    + " controlledOwnMonitor=true simulatedPendingOrViewport=true realMonotonicClock=true realMetadataQueries=0 projectionGrants=0 modelRequests=0 wechatWrites=0 chatFiles=0");
        } catch (Throwable failure) {
            result.putString("result", "FAIL scopeTimeChecks=" + checks + " mode=" + mode
                    + " type=" + failure.getClass().getSimpleName()
                    + (failure instanceof AssertionError ? " check=" + failure.getMessage() : ""));
        }
        finish(result.getString("result").startsWith("PASS") ? -1 : 0, result);
    }

    private void expiry() throws Exception {
        ChatGateService fixture = new ChatGateService();
        ChatGateService.Snapshot original = ChatGateService.snapshot();
        ChatGateService.Snapshot injected = new ChatGateService.Snapshot(true, -1, 0, 0, 0);
        Map<Field, Object> state = new HashMap<>();
        ScopeLease lease = (ScopeLease) field(ChatGateService.class, "lease").get(fixture);
        Handler handler = (Handler) field(ChatGateService.class, "handler").get(fixture);
        Method inspect = ChatGateService.class.getDeclaredMethod("inspect", boolean.class);
        inspect.setAccessible(true);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        try {
            ((ExecutorService) field(ChatGateService.class, "metadataWorker").get(fixture)).shutdownNow();
            runOnMainSync(() -> {
                try {
                    for (Field value : AssistantState.class.getDeclaredFields()) {
                        if (!Modifier.isStatic(value.getModifiers()) || Modifier.isFinal(value.getModifiers())) continue;
                        value.setAccessible(true);
                        state.put(value, value.get(null));
                    }
                    field(ChatGateService.class, "connected").set(null, fixture);
                    field(ChatGateService.class, "current").set(null, injected);
                } catch (Throwable failure) { error.set(failure); }
            });
            if (error.get() != null) throw new AssertionError("own-fixture-setup", error.get());
            long epoch = field(ScopeLease.class, "epoch").getLong(lease);
            ScopeLease.Request pending;
            synchronized (lease) {
                handler.post(() -> {
                    try { inspect.invoke(fixture, false); } catch (Throwable failure) { error.set(failure); }
                    finally { returned.countDown(); }
                });
                require(blocked(Looper.getMainLooper().getThread(), ChatGateService.class.getName(), "inspect"),
                        "main-inspection-observed-blocked-on-controlled-lease");
                SystemClock.sleep(10);
                pending = lease.begin(SystemClock.elapsedRealtime());
                require(pending != null, "new-pending-created-after-inspection-began-without-clock-rollback");
            }
            if (!returned.await(2, TimeUnit.SECONDS)) throw new AssertionError("own-inspection-did-not-return");
            if (error.get() != null) throw new AssertionError("own-inspection-failed", error.get());
            require(ChatGateService.snapshot() == injected && field(ScopeLease.class, "epoch").getLong(lease) == epoch,
                    "concurrent-new-request-is-not-a-backwards-clock-revocation");
            require(lease.pending() && field(ScopeLease.class, "pending").get(lease) == pending,
                    "controlled-pending-slot-remains-held");
            require(SystemClock.elapsedRealtime() >= pending.startedAt, "fixture-clock-really-remained-monotonic");
        } finally {
            returned.await(2, TimeUnit.SECONDS);
            AtomicReference<Throwable> cleanup = new AtomicReference<>();
            runOnMainSync(() -> {
                try {
                    handler.removeCallbacksAndMessages(null);
                    field(ChatGateService.class, "connected").set(null, null);
                    field(ChatGateService.class, "current").set(null, original);
                    for (Map.Entry<Field, Object> value : state.entrySet()) value.getKey().set(null, value.getValue());
                } catch (Exception failure) { cleanup.set(failure); }
            });
            lease.close();
            if (cleanup.get() != null) throw new AssertionError("own-fixture-restore", cleanup.get());
        }
    }

    private void validity() throws Exception {
        ScopeLease lease = new ScopeLease(200);
        ScreenGeometry geometry = new ScreenGeometry(1080, 2400, 440, 0, 0, 80, 0, 120, true);
        ChatViewport viewport = ChatViewport.create(geometry, new ChatViewport.Bounds(0, 0, 1080, 2400),
                -1, 0, 0, Collections.emptyList());
        long now = SystemClock.elapsedRealtime();
        ScopeLease.Request request = lease.begin(now);
        ScopeLease.Certificate certificate = lease.complete(request, now, false);
        ChatGateService.Snapshot snapshot = new ChatGateService.Snapshot(true, -1, 0, 0, now, viewport, lease, certificate);
        AtomicBoolean valid = new AtomicBoolean();
        Thread observer = new Thread(() -> valid.set(snapshot.valid()), "scope-validity-fixture");
        try {
            require(snapshot.valid(), "controlled-certificate-started-fresh");
            synchronized (lease) {
                observer.start();
                require(blocked(observer, ChatGateService.Snapshot.class.getName(), "valid"),
                        "validity-observer-blocked-on-controlled-lease");
                SystemClock.sleep(230);
            }
            observer.join(2000);
            require(!observer.isAlive(), "own-validity-observer-returned");
            require(!valid.get(), "validity-cannot-use-time-from-before-monitor-wait");
        } finally {
            observer.join(2000);
            lease.close();
        }
    }

    private boolean blocked(Thread thread, String className, String method) {
        long deadline = SystemClock.elapsedRealtime() + 2000;
        do {
            if (thread.getState() == Thread.State.BLOCKED) {
                for (StackTraceElement frame : thread.getStackTrace()) {
                    if (frame.getClassName().equals(className) && frame.getMethodName().equals(method)) return true;
                }
            }
            SystemClock.sleep(1);
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }

    private Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void require(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
