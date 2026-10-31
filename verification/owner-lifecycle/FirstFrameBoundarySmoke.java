package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.media.ImageReader;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/** 无投射、无范围服务的拒绝路径；不伪造有效来源，也不声称覆盖成功创建分支。 */
public final class FirstFrameBoundarySmoke extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            ModelSettingsStore settings = new ModelSettingsStore(getTargetContext());
            require(!AssistantCaptureService.running() && !CaptureService.running()
                    && !ChatGateService.enabled() && ProjectionSession.available()
                    && !settings.hasKey() && !settings.cloudAllowed(), "isolated-precondition");
            AtomicReference<Throwable> error = new AtomicReference<>();
            runOnMainSync(() -> {
                AssistantCaptureService fixture = new AssistantCaptureService();
                ImageReader reader = null;
                boolean fixtureStarted = false;
                try {
                    require(AssistantState.messages.isEmpty() && AssistantState.analysis == null,
                            "empty-state-before-fixture");
                    fixtureStarted = true;
                    reader = ImageReader.newInstance(32, 32, PixelFormat.RGBA_8888, 2);
                    set(fixture, "reader", reader);
                    set(fixture, "geometryEnteredAt", 0L);
                    set(fixture, "readerRequestedAt", 0L);
                    set(fixture, "arrivedAt", 0L);
                    set(fixture, "lastOcrAt", 0L);
                    ChatGateService.Snapshot gate = new ChatGateService.Snapshot(false, -1, 20, 0, 0);
                    ScopeFramePreparation quota = (ScopeFramePreparation) get(fixture, "firstFrame");
                    for (boolean busy : new boolean[]{true, false}) {
                        set(fixture, "busy", busy);
                        set(fixture, "processedHash", Long.MIN_VALUE);
                        require(!refresh(fixture, gate), "missing-projection-no-replacement");
                        require(get(fixture, "reader") == reader && reader.getSurface().isValid(), "existing-reader-retained");
                        require((Boolean) get(fixture, "busy") == busy, "sdk-slot-not-released");
                        require(quota.available(20, 1000, 0), "missing-projection-does-not-consume-quota");
                    }
                    quota.consume(20);
                    Method reset = AssistantCaptureService.class.getDeclaredMethod("resetGeometryScope", String.class);
                    reset.setAccessible(true);
                    reset.invoke(fixture, "固定测试：范围已失效");
                    require(!quota.available(20, 1000, 0), "same-generation-reset-does-not-refill");
                    require(quota.available(21, 1000, 0), "new-generation-has-independent-quota");
                    require(get(fixture, "reader") == reader, "reset-does-not-forge-output-replacement");
                    long future = SystemClock.elapsedRealtime() + 60000;
                    set(fixture, "readerRequestedAt", future);
                    require(!refresh(fixture, gate), "future-request-no-replacement");
                    require((Long) get(fixture, "nextTickAt") == future + 1000, "ordinary-retry-deadline-retained");
                    require(get(fixture, "reader") == reader, "waiting-keeps-original-reader");
                    require(AssistantState.messages.isEmpty() && AssistantState.analysis == null,
                            "no-chat-or-suggestion-published");
                } catch (Throwable failure) { error.set(failure); }
                finally {
                    try { ((Handler) get(fixture, "handler")).removeCallbacksAndMessages(null); }
                    catch (Throwable failure) { error.compareAndSet(null, failure); }
                    if (reader != null) reader.close();
                    if (fixtureStarted) AssistantState.clear();
                }
            });
            if (error.get() != null) throw error.get();
            require(!AssistantCaptureService.running() && !CaptureService.running()
                    && !ChatGateService.enabled() && ProjectionSession.available(), "no-service-or-projection-started");
            result.putString("result", "PASS firstFrameBoundaryChecks=" + checks
                    + " isolatedUnattachedService=true positiveCreationCovered=false projectionGrants=0 modelRequests=0 wechatWrites=0 chatFiles=0");
        } catch (Throwable failure) {
            result.putString("result", "FAIL firstFrameBoundaryChecks=" + checks + " type=" + failure.getClass().getSimpleName()
                    + (failure instanceof AssertionError ? " check=" + failure.getMessage() : ""));
        }
        finish(result.getString("result").startsWith("PASS") ? -1 : 0, result);
    }
    private boolean refresh(Object fixture, ChatGateService.Snapshot gate) throws Exception {
        Method method = AssistantCaptureService.class.getDeclaredMethod("refreshMissingFrame", ChatGateService.Snapshot.class);
        method.setAccessible(true); return (Boolean) method.invoke(fixture, gate);
    }
    private Object get(Object target, String name) throws Exception {
        Field field = AssistantCaptureService.class.getDeclaredField(name);
        field.setAccessible(true); return field.get(target);
    }
    private void set(Object target, String name, Object value) throws Exception {
        Field field = AssistantCaptureService.class.getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }
    private void require(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
}
