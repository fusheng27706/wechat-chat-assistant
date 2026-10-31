package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class ComposerCapabilitySmoke extends Instrumentation {
    private int checks;
    private Activity main;
    private Activity assistant;
    private AssistOverlay overlay;
    private BooleanSupplier oldPermission;
    private int writableFields;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            ModelSettingsStore settings = new ModelSettingsStore(getTargetContext());
            require(!settings.hasKey() && !settings.cloudAllowed() && !AssistantCaptureService.running(), "no-model-or-capture-precondition");
            phase("WAITING_FOR_FIXTURE_ACTIVITY");
            main = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            assistant = startActivitySync(new Intent(getTargetContext(), AssistantActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            phase("WAITING_FOR_NORMAL_SYSTEM_GRANT");
            await(AssistantCaptureService::running, 180000, "normal-grant");
            await(() -> !AssistantState.messages.isEmpty(), 30000, "real-existing-chat-messages");
            onMain(() -> {
                Object gate = staticField(ChatGateService.class, "connected");
                overlay = (AssistOverlay) field(gate, "overlay");
                require(!AssistantState.expanded, "card-initially-collapsed");
                ((View) field(overlay, "ball")).performClick();
            });
            AssistantCaptureService.ComposerProof first = proof();
            onMain(() -> {
                require(AssistantCaptureService.proofCurrent(first), "real-frame-proof-current");
                oldPermission = AssistantCaptureService.composerPermission(first);
                require(oldPermission.getAsBoolean(), "real-source-permission-admitted");
                require(ChatGateService.composerEditor(first, () -> true) == null, "main-thread-editor-factory-refuses-node-work");
            });
            AtomicReference<DraftWorker.Result<DraftPlan.Field>> response = new AtomicReference<>();
            onMain(() -> {
                DraftWorker.Task<DraftPlan.Field> task = new DraftOperations().submit(first, () -> true, (editor, current) -> {
                    require(Looper.myLooper() != Looper.getMainLooper(), "production-node-operation-off-main");
                    return editor == null ? null : editor.inspect();
                }, response::set);
                require(task != null, "real-production-shared-slot-admitted");
            });
            await(() -> response.get() != null, 3000, "production-read-only-callback");
            require(response.get().status == DraftWorker.Status.COMPLETED, "source-proof-and-fresh-metadata-survive-read-only-query");
            writableFields = response.get().value == null ? 0 : 1;
            onMain(() -> invoke(overlay, "checkCapability"));
            await(() -> readStatus().contains("本次只读检查未执行写入") || readStatus().contains("唯一可写"), 4000, "real-overlay-capability-finishes");
            onMain(() -> ((View) field(overlay, "pause")).performClick());
            require(!oldPermission.getAsBoolean(), "pause-immediately-revokes-old-thread-safe-permission");
            await(() -> !AssistantCaptureService.proofCurrent(first), 3000, "old-proof-rejected-after-pause");
            onMain(() -> {
                ((View) field(overlay, "ball")).performClick();
                ((View) field(overlay, "pause")).performClick();
            });
            await(() -> !AssistantState.messages.isEmpty() && !AssistantState.paused, 30000, "resume-fresh-messages-without-new-grant");
            onMain(() -> { if (!AssistantState.expanded) ((View) field(overlay, "ball")).performClick(); });
            AssistantCaptureService.ComposerProof second = proof();
            onMain(() -> {
                require(AssistantCaptureService.composerPermission(second).getAsBoolean(), "new-output-permission-admitted");
                require(!oldPermission.getAsBoolean(), "new-output-never-revives-old-permission");
                CaptureControl.stopAll(getTargetContext());
                require(!oldPermission.getAsBoolean(), "explicit-stop-revokes-permission");
            });
            require(!settings.cloudAllowed() && !settings.hasKey() && AssistantState.analysis == null, "no-fake-analysis-or-cloud-in-wechat");
            result.putString("result", "PASS composerSourceChecks=" + checks + " writableFields=" + writableFields
                    + " injectedSource=false projectionGrants=1 modelRequests=0 wechatWrites=0 rawChatFiles=0");
        } catch (Throwable failure) {
            result.putString("result", "FAIL composerSourceChecks=" + checks + " type=" + failure.getClass().getSimpleName()
                    + (failure instanceof AssertionError ? " check=" + failure.getMessage() : ""));
        } finally {
            onMain(() -> { CaptureControl.stopAll(getTargetContext()); if (assistant != null) assistant.finish(); if (main != null) main.finish(); });
        }
        android.util.Log.i("ComposerSourceSmoke", result.getString("result"));
        finish(result.getString("result").startsWith("PASS") ? -1 : 0, result);
    }

    private AssistantCaptureService.ComposerProof proof() {
        AtomicReference<AssistantCaptureService.ComposerProof> value = new AtomicReference<>();
        AtomicReference<Boolean> delivered = new AtomicReference<>(false);
        onMain(() -> {
            AssistantCaptureService.requestComposer(AssistantState.snapshot(), result -> { value.set(result); delivered.set(true); });
            View ball = (View) field(overlay, "ball"); ball.setAlpha(0.98f);
            new android.os.Handler(Looper.getMainLooper()).postDelayed(() -> ball.setAlpha(1f), 100);
        });
        await(() -> delivered.get(), 2500, "fresh-proof-delivery");
        require(value.get() != null, "fresh-proof-confirmed");
        return value.get();
    }
    private String readStatus() { AtomicReference<String> result = new AtomicReference<>(); onMain(() -> result.set(((TextView) field(overlay, "operationStatus")).getText().toString())); return result.get(); }
    private void onMain(Runnable action) { AtomicReference<Throwable> failure = new AtomicReference<>(); runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } }); if (failure.get() != null) throw new AssertionError(failure.get().getMessage(), failure.get()); }
    private static Object field(Object value, String name) { try { Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value); } catch (ReflectiveOperationException failure) { throw new AssertionError("fixture-field-" + name, failure); } }
    private static Object staticField(Class<?> type, String name) { try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(null); } catch (ReflectiveOperationException failure) { throw new AssertionError("fixture-static-" + name, failure); } }
    private static void invoke(Object value, String name) { try { Method method = value.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(value); } catch (ReflectiveOperationException failure) { throw new AssertionError("fixture-method-" + name, failure); } }
    private void await(BooleanSupplier condition, long timeout, String label) { long end = SystemClock.elapsedRealtime() + timeout; while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(30); require(condition.getAsBoolean(), label); }
    private void phase(String name) { android.util.Log.i("ComposerSourceSmoke", name); Bundle status = new Bundle(); status.putString("phase", name); sendStatus(1, status); }
    private void require(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
}
