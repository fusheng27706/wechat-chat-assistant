package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

public final class ScopeCadenceSmoke extends Instrumentation {
    private int checks;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            ModelSettingsStore settings = new ModelSettingsStore(getTargetContext());
            require(!settings.hasKey() && !settings.cloudAllowed() && !AssistantCaptureService.running()
                    && !CaptureService.running() && !ChatGateService.enabled() && AssistantState.messages.isEmpty(),
                    "isolated-no-capture-model-or-connected-gate");
            AtomicReference<Throwable> error = new AtomicReference<>();
            runOnMainSync(() -> {
                ChatGateService fixture = new ChatGateService();
                ChatGateService.Snapshot original = ChatGateService.snapshot();
                Field connected = null;
                Field current = null;
                Field active = null;
                try {
                    connected = accessible(ChatGateService.class, "connected");
                    current = accessible(ChatGateService.class, "current");
                    active = accessible(CaptureService.class, "active");
                    Field requested = accessible(ChatGateService.class, "lastRequestedAt");
                    Handler handler = (Handler) accessible(ChatGateService.class, "handler").get(fixture);
                    Runnable refresh = (Runnable) accessible(ChatGateService.class, "refresh").get(fixture);
                    ScopeLease lease = (ScopeLease) accessible(ChatGateService.class, "lease").get(fixture);
                    Method inspect = ChatGateService.class.getDeclaredMethod("inspect", boolean.class);
                    inspect.setAccessible(true);
                    connected.set(null, fixture);
                    active.setBoolean(null, true);
                    current.set(null, new ChatGateService.Snapshot(true, -1, 0, 0, 0));
                    long started = SystemClock.elapsedRealtime();
                    requested.setLong(fixture, started);
                    inspect.invoke(fixture, false);
                    require(handler.hasCallbacks(refresh), "early-completion-must-arm-next-request-deadline");
                    require(SystemClock.elapsedRealtime() - started < 80, "same-main-turn-remained-before-request-deadline");
                    require(!lease.pending() && requested.getLong(fixture) == started, "early-completion-does-not-start-or-fabricate-query");
                    Field scheduled = accessible(ChatGateService.class, "nextInspectionAt");
                    long due = scheduled.getLong(fixture);
                    require(due >= started + 80 && due <= started + 82, "wake-is-based-on-request-start-not-next-poll-phase");
                    inspect.invoke(fixture, false);
                    require(handler.hasCallbacks(refresh) && scheduled.getLong(fixture) == due,
                            "repeated-inspection-does-not-postpone-existing-deadline");
                    current.set(null, new ChatGateService.Snapshot(false, -1, 0, 0, 0));
                    inspect.invoke(fixture, false);
                    require(scheduled.getLong(fixture) == due, "later-outside-poll-does-not-postpone-earlier-wake");
                    handler.removeCallbacks(refresh);
                    connected.set(null, null);
                    refresh.run();
                    require(!handler.hasCallbacks(refresh), "disconnected-refresh-does-not-rearm-itself");
                    require(!lease.pending(), "fixture-never-dispatched-metadata-worker");
                } catch (Throwable failure) { error.set(failure); }
                finally {
                    try {
                        if (active != null) active.setBoolean(null, false);
                        if (connected != null) connected.set(null, null);
                        if (current != null) current.set(null, original);
                        ((ScopeLease) accessible(ChatGateService.class, "lease").get(fixture)).close();
                        ((ExecutorService) accessible(ChatGateService.class, "metadataWorker").get(fixture)).shutdownNow();
                        ((Handler) accessible(ChatGateService.class, "handler").get(fixture)).removeCallbacksAndMessages(null);
                    } catch (Throwable failure) { error.set(failure); }
                }
            });
            if (error.get() != null) throw error.get();
            require(!CaptureService.running() && !AssistantCaptureService.running() && !ChatGateService.enabled(),
                    "simulated-active-flag-and-connection-restored");
            require(!settings.hasKey() && !settings.cloudAllowed(), "model-settings-untouched");
            result.putString("result", "PASS scopeCadenceChecks=" + checks
                    + " isolatedUnattachedService=true simulatedActiveFlag=true projectionGrants=0 metadataQueries=0 modelRequests=0 wechatWrites=0 chatFiles=0");
        } catch (Throwable failure) {
            result.putString("result", "FAIL scopeCadenceChecks=" + checks + " type=" + failure.getClass().getSimpleName()
                    + (failure instanceof AssertionError ? " check=" + failure.getMessage() : ""));
        }
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
}
