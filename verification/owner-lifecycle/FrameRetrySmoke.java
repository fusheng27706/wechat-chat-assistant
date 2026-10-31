package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.Bundle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

public final class FrameRetrySmoke extends Instrumentation {
    private int checks;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            ModelSettingsStore settings = new ModelSettingsStore(getTargetContext());
            require(!settings.hasKey() && !settings.cloudAllowed() && !AssistantCaptureService.running()
                    && !CaptureService.running() && AssistantState.messages.isEmpty(), "isolated-no-capture-or-model");
            AtomicReference<Throwable> error = new AtomicReference<>();
            runOnMainSync(() -> {
                try {
                    AssistantCaptureService fixture = new AssistantCaptureService();
                    finish(fixture, 101, 101, false);
                    finish(fixture, 202, Long.MIN_VALUE, false);
                    finish(fixture, 303, 303, true);
                    finish(fixture, 404, 404, false);
                    finish(fixture, 505, Long.MIN_VALUE, true);
                    finish(fixture, 0, 0, false);
                    require(!AssistantCaptureService.running() && !CaptureService.running()
                            && AssistantState.messages.isEmpty(), "unattached-fixture-does-not-start-or-publish");
                } catch (Throwable failure) { error.set(failure); }
            });
            if (error.get() != null) throw error.get();
            require(!settings.hasKey() && !settings.cloudAllowed(), "model-settings-untouched");
            result.putString("result", "PASS frameRetryChecks=" + checks
                    + " isolatedUnattachedService=true simulatedCompletion=true realOcrRequests=0 projectionGrants=0 modelRequests=0 wechatWrites=0 chatFiles=0");
        } catch (Throwable failure) {
            result.putString("result", "FAIL frameRetryChecks=" + checks + " type=" + failure.getClass().getSimpleName()
                    + (failure instanceof AssertionError ? " check=" + failure.getMessage() : ""));
        }
        finish(result.getString("result").startsWith("PASS") ? -1 : 0, result);
    }

    private void finish(AssistantCaptureService fixture, long candidate, long processed, boolean handled) throws Exception {
        set(fixture, "candidateHash", candidate);
        set(fixture, "processedHash", processed);
        set(fixture, "busy", true);
        if (handled) set(fixture, "frameHandled", true);
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        Method method = AssistantCaptureService.class.getDeclaredMethod("finishImage", Bitmap.class);
        method.setAccessible(true);
        method.invoke(fixture, bitmap);
        require(bitmap.isRecycled(), "attempt-bitmap-released");
        require(Boolean.FALSE.equals(get(fixture, "busy")), "attempt-inflight-slot-released");
        long actual = (long) get(fixture, "processedHash");
        require(actual == (handled ? processed : Long.MIN_VALUE), handled
                ? "handled-or-excluded-frame-retains-existing-decision-without-resurrecting-invalidated-hash"
                : "stale-attempt-must-not-suppress-identical-fresh-frame");
        require(Boolean.FALSE.equals(get(fixture, "frameHandled")), "completion-clears-attempt-handled-flag");
        require((long) get(fixture, "candidateHash") == candidate, "completion-does-not-forge-new-frame-hash");
    }

    private Object get(Object target, String name) throws Exception {
        Field field = AssistantCaptureService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = AssistantCaptureService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private void require(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
