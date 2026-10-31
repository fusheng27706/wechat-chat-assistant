package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Handler;
import android.view.accessibility.AccessibilityEvent;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

public final class ScopeOwnerSmoke extends Instrumentation {
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
                ChatGateService.Snapshot before = ChatGateService.snapshot();
                AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
                try {
                    ScopeLease lease = (ScopeLease) field(fixture, "lease");
                    long epoch = ((Number) leaseField(lease, "epoch")).longValue();
                    fixture.onAccessibilityEvent(event);
                    require(ChatGateService.snapshot() == before, "disconnected-service-event-cannot-replace-current-snapshot");
                    require(((Number) leaseField(lease, "epoch")).longValue() == epoch,
                            "disconnected-event-does-not-mutate-service-lease");
                    require(!lease.pending(), "disconnected-event-does-not-start-metadata-query");
                    Field connected = ChatGateService.class.getDeclaredField("connected");
                    connected.setAccessible(true);
                    connected.set(null, fixture);
                    IntConsumer oldRegistration = fixture.ownedWindowRegistration();
                    Field connection = ChatGateService.class.getDeclaredField("connectionEpoch");
                    connection.setAccessible(true);
                    connection.setLong(fixture, connection.getLong(fixture) + 1);
                    oldRegistration.accept(71);
                    OwnWindowIds ids = (OwnWindowIds) field(fixture, "ownedWindows");
                    require(!ids.contains(71), "previous-connection-registration-rejected");
                    IntConsumer currentRegistration = fixture.ownedWindowRegistration();
                    currentRegistration.accept(72);
                    require(ids.contains(72) && ((Number) field(fixture, "lastOwnedOverlay")).intValue() == 72,
                            "current-connection-registration-recorded");
                    connected.set(null, null);
                    currentRegistration.accept(73);
                    require(!ids.contains(73), "disconnected-registration-rejected");
                } catch (Throwable failure) { error.set(failure); }
                finally {
                    event.recycle();
                    try {
                        Field connected = ChatGateService.class.getDeclaredField("connected");
                        connected.setAccessible(true);
                        connected.set(null, null);
                        Field current = ChatGateService.class.getDeclaredField("current");
                        current.setAccessible(true);
                        current.set(null, before);
                        ((ScopeLease) field(fixture, "lease")).close();
                        ((ExecutorService) field(fixture, "metadataWorker")).shutdownNow();
                        ((Handler) field(fixture, "handler")).removeCallbacksAndMessages(null);
                    } catch (Throwable failure) { error.set(failure); }
                }
            });
            if (error.get() != null) throw error.get();
            require(!ChatGateService.enabled() && !AssistantCaptureService.running() && !CaptureService.running(),
                    "unattached-fixture-does-not-start-services");
            require(!settings.hasKey() && !settings.cloudAllowed(), "model-settings-untouched");
            result.putString("result", "PASS scopeOwnerChecks=" + checks
                    + " isolatedUnattachedService=true simulatedEvent=true simulatedRegistration=true projectionGrants=0 modelRequests=0 wechatWrites=0 chatFiles=0");
        } catch (Throwable failure) {
            result.putString("result", "FAIL scopeOwnerChecks=" + checks + " type=" + failure.getClass().getSimpleName()
                    + (failure instanceof AssertionError ? " check=" + failure.getMessage() : ""));
        }
        finish(result.getString("result").startsWith("PASS") ? -1 : 0, result);
    }

    private Object field(Object target, String name) throws Exception {
        Field field = ChatGateService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private Object leaseField(ScopeLease target, String name) throws Exception {
        Field field = ScopeLease.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private void require(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
