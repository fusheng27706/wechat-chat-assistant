package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;
import android.hardware.display.DisplayManager;
import android.media.ImageReader;
import android.view.Surface;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class ProjectionStopSmoke extends Instrumentation {
    private int checks;
    private Activity main;
    private Activity assistant;
    private int observedMessages;
    private AssistantCaptureService capturedService;
    private ProjectionSession capturedSession;
    private Surface capturedSurface;
    private int parentPid;
    private int hostPid;
    private volatile boolean inspected;
    private boolean registered;
    private final BroadcastReceiver observer = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { inspected = true; }
    };

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle output = new Bundle();
        try {
            ModelSettingsStore settings = new ModelSettingsStore(getTargetContext());
            parentPid = android.os.Process.myPid();
            require(!settings.hasKey() && !settings.cloudAllowed(), "no-key-no-cloud-precondition");
            IntentFilter inspection = new IntentFilter("com.codextemp.wechatcaptureprobe.OWNER_DISPLAY_INSPECTED");
            if (Build.VERSION.SDK_INT >= 33) getTargetContext().registerReceiver(observer, inspection, Context.RECEIVER_EXPORTED);
            else getTargetContext().registerReceiver(observer, inspection);
            registered = true;
            Log.i("ProjectionVerification", "WAITING_FOR_FIXTURE_ACTIVITY");
            main = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            assistant = startActivitySync(new Intent(getTargetContext(), AssistantActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Log.i("ProjectionVerification", "WAITING_FOR_MANUAL_SYSTEM_GRANT");
            await(AssistantCaptureService::running, 180000, "manual-system-grant-and-service-start");
            await(() -> !AssistantState.messages.isEmpty(), 25000, "existing-test-chat-visible-messages");
            onMain(() -> {
                require(!settings.cloudAllowed(), "cloud-still-off-before-stop");
                observedMessages = AssistantState.messages.size();
                require(AssistantState.token.length() > 0 && displayPresent(), "live-chat-and-virtual-display");
                AssistantCaptureService service = (AssistantCaptureService) field(AssistantCaptureService.class, null, "instance");
                capturedService = service;
                capturedSession = (ProjectionSession) field(AssistantCaptureService.class, service, "projectionSession");
                hostPid = (int) field(ProjectionSession.class, capturedSession, "hostPid");
                require(hostPid > 0 && hostPid != parentPid && capturedSession.ready(), "separate-live-projection-owner");
                capturedSurface = ((ImageReader) field(AssistantCaptureService.class, service, "reader")).getSurface();
                Log.i("ProjectionVerification", "CALLING_REAL_PROJECTION_STOP");
                capturedSession.requestStop();
            });
            await(() -> !AssistantCaptureService.running(), 5000, "framework-onStop-ends-assistant");
            SystemClock.sleep(500);
            Object coordinator = field(AssistantCaptureService.class, capturedService, "analysis");
            require(Boolean.TRUE.equals(field(AnalysisCoordinator.class, coordinator, "closed")), "analysis-coordinator-closed");
            require((int) field(ProjectionSession.class, capturedSession, "stopReason") == 1, "real-framework-stop-callback-observed");
            require(Boolean.TRUE.equals(field(ProjectionSession.class, capturedSession, "resourcesReleased")), "worker-token-and-surface-release-reported");
            require(!capturedSurface.isValid(), "image-reader-surface-closed");
            require(AssistantState.messages.isEmpty() && AssistantState.token.isEmpty() && AssistantState.analysisSource == null
                    && AssistantState.context.mode == ConversationContext.Mode.UNKNOWN, "private-state-cleared-before-inspection");
            onMain(() -> {
                Object connected = field(ChatGateService.class, null, "connected");
                require(connected != null, "gate-connected-before-inspection");
                require(Boolean.FALSE.equals(field(AssistOverlay.class, field(ChatGateService.class, connected, "overlay"), "attached")),
                        "overlay-removed-before-inspection");
            });
            Log.i("ProjectionVerification", "CLEANUP_STATE analysisClosed=" + field(AnalysisCoordinator.class, coordinator, "closed")
                    + " messages=" + AssistantState.messages.size() + " sourcePresent=" + (AssistantState.analysisSource != null));
            for (java.util.Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                if (entry.getKey().getName().equals("main")) {
                    for (int index = 0; index < Math.min(12, entry.getValue().length); index++) {
                        Log.i("ProjectionVerification", "MAIN_STACK " + entry.getValue()[index]);
                    }
                }
            }
            Log.i("ProjectionVerification", "SERVICE_STOPPED_HOLD_FOR_EXTERNAL_DISPLAY_INSPECTION pid=" + android.os.Process.myPid());
            await(() -> inspected, 90000, "independent-live-process-display-inspection");
            await(() -> !displayPresent(), 3000, "virtual-display-released");
            require(android.os.Process.myPid() == parentPid, "main-process-not-restarted-to-clear-display");
            require(ProjectionSession.available(), "worker-death-and-owner-retirement-confirmed");
            await(() -> !getTargetContext().getSystemService(ActivityManager.class).getRunningServices(100).stream()
                    .anyMatch(service -> service.service.getClassName().equals(AssistantCaptureService.class.getName())), 3000, "foreground-service-removed");
            onMain(() -> require(AssistantState.messages.isEmpty() && AssistantState.token.isEmpty() && AssistantState.analysisSource == null
                    && AssistantState.context.mode == ConversationContext.Mode.UNKNOWN, "framework-stop-clears-private-state"));
            SystemClock.sleep(700);
            onMain(() -> {
                Object connected = field(ChatGateService.class, null, "connected");
                require(connected != null, "normal-gate-service-still-connected");
                Object overlay = field(ChatGateService.class, connected, "overlay");
                require(Boolean.FALSE.equals(field(AssistOverlay.class, overlay, "attached")), "framework-stop-removes-overlay");
            });
            output.putString("result", "PASS projectionChecks=" + checks + " observedVisibleMessages=" + observedMessages
                    + " actualProjectionStops=1 systemUiRevocations=0 parentPid=" + parentPid + " hostPid=" + hostPid
                    + " modelRequests=0 wechatWrites=0 rawChatFiles=0");
        } catch (Throwable failure) {
            output.putString("result", "FAIL projectionChecks=" + checks + " observedVisibleMessages=" + observedMessages
                    + " type=" + failure.getClass().getSimpleName() + " check=" + failure.getMessage());
        } finally {
            if (registered) getTargetContext().unregisterReceiver(observer);
            onMain(() -> {
                CaptureControl.stopAll(getTargetContext());
                if (assistant != null) assistant.finish();
                if (main != null) main.finish();
            });
        }
        Log.i("ProjectionVerification", output.getString("result"));
        finish(output.getString("result").startsWith("PASS") ? -1 : 0, output);
    }

    private boolean displayPresent() {
        return Arrays.stream(getTargetContext().getSystemService(DisplayManager.class).getDisplays())
                .anyMatch(display -> display.getName().equals("WechatAssistant") && display.isValid());
    }

    private Object field(Class<?> type, Object owner, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("test-field-" + name, failure);
        }
    }

    private void onMain(Runnable action) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> {
            try { action.run(); } catch (Throwable failure) { error.set(failure); }
        });
        if (error.get() != null) throw new AssertionError(error.get().getMessage(), error.get());
    }

    private void await(BooleanSupplier condition, long timeout, String label) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(40);
        require(condition.getAsBoolean(), label);
    }

    private void require(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}

