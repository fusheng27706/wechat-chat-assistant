package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class OwnerFailureSmoke extends Instrumentation {
    private String mode;
    private int checks;
    private Activity main;
    private Activity assistant;
    private final int parentPid = Process.myPid();

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        mode = arguments == null ? "worker" : arguments.getString("mode", "worker");
        start();
    }

    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            ModelSettingsStore settings = new ModelSettingsStore(getTargetContext());
            require(!settings.hasKey() && !settings.cloudAllowed() && ProjectionSession.available(), "no-real-model-or-owner-precondition");
            phase("WAITING_FOR_FIXTURE_ACTIVITY");
            main = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            if (mode.equals("diagnostic")) {
                diagnostic();
            } else if (mode.equals("worker")) {
                worker();
            } else {
                throw new AssertionError("unknown-mode");
            }
            require(!settings.hasKey() && !settings.cloudAllowed(), "model-remains-off");
            output.putString("result", "PASS ownerChecks=" + checks + " mode=" + mode + " parentPid=" + parentPid
                    + " modelRequests=0 wechatWrites=0 rawChatFiles=0");
        } catch (Throwable failure) {
            output.putString("result", "FAIL ownerChecks=" + checks + " mode=" + mode + " type="
                    + failure.getClass().getSimpleName() + " check=" + failure.getMessage());
        } finally {
            onMain(() -> {
                CaptureControl.stopAll(getTargetContext());
                if (assistant != null) assistant.finish();
                if (main != null) main.finish();
            });
        }
        Log.i("OwnerVerification", output.getString("result"));
        finish(output.getString("result").startsWith("PASS") ? -1 : 0, output);
    }

    private void diagnostic() {
        phase("WAITING_FOR_DIAGNOSTIC_GRANT");
        await(CaptureService::running, 180000, "normal-diagnostic-system-grant");
        await(() -> field(CaptureService.class, null, "instance") != null
                && field(CaptureService.class, field(CaptureService.class, null, "instance"), "projectionSession") != null, 5000, "diagnostic-session-created");
        Object service = field(CaptureService.class, null, "instance");
        ProjectionSession owner = (ProjectionSession) field(CaptureService.class, service, "projectionSession");
        await(owner::ready, 5000, "diagnostic-worker-ready");
        Surface surface = ((ImageReader) field(CaptureService.class, service, "imageReader")).getSurface();
        int hostPid = (int) field(ProjectionSession.class, owner, "hostPid");
        require(hostPid > 0 && hostPid != parentPid && displayPresent(), "diagnostic-separate-owner-and-display");
        phase("WAITING_FOR_OWN_PAGE_OCR");
        await(() -> ProbeState.result() != null, 25000, "diagnostic-own-fictional-page-result");
        require(ProbeState.result().lineCount > 0 && !ProbeState.result().contentUnavailable, "diagnostic-readable-own-page");
        await(() -> !CaptureService.running() && ProjectionSession.available() && !displayPresent(), 5000, "diagnostic-normal-release");
        require(!surface.isValid() && !owner.ready(), "diagnostic-receiver-and-owner-closed");
        require(Process.myPid() == parentPid, "diagnostic-parent-survives");
    }

    private void worker() {
        assistant = startActivitySync(new Intent(getTargetContext(), AssistantActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        phase("WAITING_FOR_ASSISTANT_GRANT");
        await(AssistantCaptureService::running, 180000, "normal-assistant-system-grant");
        await(() -> !AssistantState.messages.isEmpty(), 30000, "actual-existing-test-chat");
        Object service = field(AssistantCaptureService.class, null, "instance");
        ProjectionSession owner = (ProjectionSession) field(AssistantCaptureService.class, service, "projectionSession");
        Surface surface = ((ImageReader) field(AssistantCaptureService.class, service, "reader")).getSurface();
        int hostPid = (int) field(ProjectionSession.class, owner, "hostPid");
        AtomicReference<AssistantCaptureService.ComposerProof> proof = new AtomicReference<>();
        onMain(() -> {
            require(owner.ready() && displayPresent(), "live-owner-before-fault");
            AssistantCaptureService.requestComposer(AssistantState.snapshot(), proof::set);
            Object connected = field(ChatGateService.class, null, "connected");
            View ball = (View) field(AssistOverlay.class, field(ChatGateService.class, connected, "overlay"), "ball");
            ball.setAlpha(0.99f);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> ball.setAlpha(1f), 100);
        });
        await(() -> proof.get() != null, 2500, "actual-fresh-proof-before-fault");
        onMain(() -> {
            require(AssistantCaptureService.proofCurrent(proof.get()), "proof-valid-before-fault");
            require(hostPid != parentPid && getTargetContext().getSystemService(ActivityManager.class).getRunningAppProcesses()
                    .stream().anyMatch(process -> process.pid == hostPid && process.uid == Process.myUid()
                    && process.processName.equals(getTargetContext().getPackageName() + ":projection")), "fault-target-only-owned-worker");
            Log.i("OwnerVerification", "INJECT_WORKER_DEATH parentPid=" + parentPid + " hostPid=" + hostPid);
            Process.killProcess(hostPid);
        });
        await(() -> !AssistantCaptureService.running() && ProjectionSession.available() && !displayPresent(), 5000, "worker-death-cleans-capture-and-display");
        onMain(() -> {
            require(Process.myPid() == parentPid && ChatGateService.enabled(), "parent-and-gate-survive-worker-death");
            require(!surface.isValid() && !owner.ready(), "receiver-closed-after-worker-death");
            require(AssistantState.messages.isEmpty() && AssistantState.token.isEmpty() && AssistantState.analysisSource == null
                    && AssistantState.context.mode == ConversationContext.Mode.UNKNOWN, "private-state-cleared-after-worker-death");
            require(!AssistantCaptureService.proofCurrent(proof.get()) && !AssistantCaptureService.analysisScopeCurrent(proof.get().target), "old-proof-and-analysis-rejected");
            Object connected = field(ChatGateService.class, null, "connected");
            require(Boolean.FALSE.equals(field(AssistOverlay.class, field(ChatGateService.class, connected, "overlay"), "attached")), "overlay-removed-after-worker-death");
        });
        SystemClock.sleep(2500);
        require(!AssistantCaptureService.running() && !displayPresent(), "no-automatic-recapture");
        onMain(() -> assistant.startActivity(new Intent(getTargetContext(), AssistantActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        SystemClock.sleep(700);
        phase("WAITING_FOR_CANCELLED_NEW_GRANT");
        onMain(() -> {
            try {
                java.lang.reflect.Method begin = AssistantActivity.class.getDeclaredMethod("begin");
                begin.setAccessible(true);
                begin.invoke(assistant);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("normal-begin-handler", failure);
            }
        });
        await(() -> AssistantState.status.contains("未授权共享"), 90000, "new-system-grant-cancelled");
        require(!AssistantCaptureService.running() && ProjectionSession.available() && !displayPresent(), "cancel-does-not-reuse-old-grant");
    }

    private boolean displayPresent() {
        return Arrays.stream(getTargetContext().getSystemService(DisplayManager.class).getDisplays())
                .anyMatch(display -> display.isValid() && (display.getName().equals("WechatAssistant") || display.getName().equals("WechatCaptureProbe")));
    }

    private Object field(Class<?> type, Object owner, String name) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("fixture-field-" + name, failure); }
    }

    private void onMain(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError(failure.get().getMessage(), failure.get());
    }

    private void await(BooleanSupplier condition, long timeout, String label) {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(40);
        require(condition.getAsBoolean(), label);
    }

    private void phase(String phase) { Log.i("OwnerVerification", "PHASE " + phase); }
    private void require(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
}
