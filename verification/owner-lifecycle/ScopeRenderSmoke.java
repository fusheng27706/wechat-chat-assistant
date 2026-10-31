package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.TextView;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

public final class ScopeRenderSmoke extends Instrumentation {
    private int checks;
    private Activity activity;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            ModelSettingsStore settings = new ModelSettingsStore(getTargetContext());
            require(!settings.hasKey() && !settings.cloudAllowed() && !AssistantCaptureService.running()
                    && !CaptureService.running() && !ChatGateService.enabled() && AssistantState.messages.isEmpty(),
                    "isolated-no-capture-model-or-connected-gate");
            Bundle waiting = new Bundle();
            waiting.putString("phase", "WAITING_FOR_OWN_FIXTURE_ACTIVITY");
            sendStatus(1, waiting);
            activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            AtomicReference<Throwable> error = new AtomicReference<>();
            runOnMainSync(() -> {
                try { exercise(); } catch (Throwable failure) { error.set(failure); }
            });
            if (error.get() != null) throw error.get();
            require(!settings.hasKey() && !settings.cloudAllowed() && !ChatGateService.enabled()
                    && !AssistantCaptureService.running() && !CaptureService.running(), "fixture-state-and-model-restored");
            result.putString("result", "PASS scopeRenderChecks=" + checks
                    + " isolatedUnattachedService=true simulatedMetadata=true simulatedActiveFlag=true offscreenWindowManager=true slowAddMs=230 projectionGrants=0 metadataQueries=0 modelRequests=0 wechatWrites=0 chatFiles=0");
        } catch (Throwable failure) {
            Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException ? failure.getCause() : failure;
            result.putString("result", "FAIL scopeRenderChecks=" + checks + " type=" + cause.getClass().getSimpleName()
                    + (cause instanceof AssertionError ? " check=" + cause.getMessage() : ""));
        } finally {
            runOnMainSync(() -> { if (activity != null) activity.finish(); });
        }
        finish(result.getString("result").startsWith("PASS") ? -1 : 0, result);
    }

    private void exercise() throws Exception {
        ChatGateService fixture = new ChatGateService();
        ChatGateService.Snapshot original = ChatGateService.snapshot();
        Field connected = accessible(ChatGateService.class, "connected");
        Field current = accessible(ChatGateService.class, "current");
        Field active = accessible(AssistantCaptureService.class, "active");
        Handler handler = (Handler) accessible(ChatGateService.class, "handler").get(fixture);
        ScopeLease lease = (ScopeLease) accessible(ChatGateService.class, "lease").get(fixture);
        ((ExecutorService) accessible(ChatGateService.class, "metadataWorker").get(fixture)).shutdownNow();
        AssistOverlay overlay = null;
        try {
            CountingWindows windows = new CountingWindows(activity.getWindowManager());
            Context context = new ContextWrapper(activity) {
                @Override public Object getSystemService(String name) {
                    return Context.WINDOW_SERVICE.equals(name) ? windows : super.getSystemService(name);
                }
            };
            Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            attach.setAccessible(true);
            attach.invoke(fixture, context);
            overlay = new AssistOverlay(context, new DraftOperations());
            accessible(ChatGateService.class, "overlay").set(fixture, overlay);
            ScreenGeometry geometry = ScreenGeometryReader.read(fixture);
            ChatViewport.Bounds bounds = new ChatViewport.Bounds(0, 0, geometry.width, geometry.height);
            ChatViewport viewport = ChatViewport.create(geometry, bounds, -1, 0, 0, Collections.emptyList());
            accessible(ChatGateService.class, "lastPackage").set(fixture, "com.tencent.mm");
            accessible(ChatGateService.class, "lastWindow").setInt(fixture, 17);
            accessible(ChatGateService.class, "lastGeometry").set(fixture, geometry);
            accessible(ChatGateService.class, "lastViewport").set(fixture, viewport);
            Class<?> metadataType = Class.forName(ChatGateService.class.getName() + "$WindowMetadata");
            Constructor<?> metadataConstructor = metadataType.getDeclaredConstructors()[0];
            metadataConstructor.setAccessible(true);
            Object metadata = metadataConstructor.newInstance("com.tencent.mm", 17, -1, 0, 0, bounds, Collections.emptyList(), geometry);
            Class<?> verificationType = Class.forName(ChatGateService.class.getName() + "$Verification");
            Constructor<?> verificationConstructor = verificationType.getDeclaredConstructors()[0];
            verificationConstructor.setAccessible(true);
            Method accept = ChatGateService.class.getDeclaredMethod("acceptMetadata", ScopeLease.Request.class, metadataType, ScopeQueryTrace.Entry.class);
            accept.setAccessible(true);
            CompletableFuture<ChatGateService.Snapshot> verification = new CompletableFuture<>();
            connected.set(null, fixture);
            active.setBoolean(null, true);
            long began = SystemClock.elapsedRealtime();
            ScopeLease.Request request = lease.begin(began);
            accessible(ChatGateService.class, "lastRequestedAt").setLong(fixture, began);
            @SuppressWarnings("unchecked")
            List<Object> verifications = (List<Object>) accessible(ChatGateService.class, "verifications").get(fixture);
            verifications.add(verificationConstructor.newInstance(request.identifier, verification));
            ScopeQueryTrace.Entry timing = new ScopeQueryTrace.Entry(request);
            accept.invoke(fixture, request, metadata, timing);
            require(windows.adds == 0, "metadata-acceptance-must-not-synchronously-render-window");
            require(verification.isDone() && verification.getNow(null) == ChatGateService.snapshot(), "verification-resolves-before-render");
            require(!lease.pending(), "fixture-did-not-start-another-metadata-query");
            require(timing.renderStartedAt == -1, "render-clock-not-folded-into-acceptance");
            Runnable render = (Runnable) accessible(ChatGateService.class, "render").get(fixture);
            Method queue = ChatGateService.class.getDeclaredMethod("queueOverlayRefresh", ScopeQueryTrace.Entry.class);
            queue.setAccessible(true);
            require(handler.hasCallbacks(render), "accepted-result-queues-deferred-render");
            queue.invoke(fixture, new ScopeQueryTrace.Entry(request));
            require(handler.hasCallbacks(render), "later-refresh-keeps-pending-render");
            handler.removeCallbacks(render);
            render.run();
            require(windows.adds == 1 && Boolean.TRUE.equals(accessible(AssistOverlay.class, "attached").get(overlay)),
                    "fresh-dispatch-renders-real-overlay-into-offscreen-manager");
            long interaction = ChatInteraction.revision();
            android.view.accessibility.AccessibilityEvent input = android.view.accessibility.AccessibilityEvent.obtain(
                    android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);
            try {
                input.setPackageName("synthetic.other");
                fixture.onAccessibilityEvent(input);
                require(!handler.hasCallbacks(render) && ChatInteraction.revision() == interaction,
                        "foreign-text-event-does-not-change-interaction-or-render");
                input.setPackageName("com.tencent.mm");
                fixture.onAccessibilityEvent(input);
                require(handler.hasCallbacks(render) && ChatInteraction.revision() == interaction + 1
                        && !ChatInteraction.quiet(SystemClock.elapsedRealtime()), "typing-queues-hide-before-next-metadata-result");
                require(!lease.pending() && windows.adds == 1, "typing-does-not-query-nodes-or-render-synchronously");
                input.setEventType(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED);
                fixture.onAccessibilityEvent(input);
                require(handler.hasCallbacks(render) && ChatInteraction.revision() == interaction + 2,
                        "scroll-coalesces-with-pending-interaction-render");
            } finally { input.recycle(); }
            current.set(null, new ChatGateService.Snapshot(false, -1, 0, 0, 0));
            queue.invoke(fixture, timing);
            handler.removeCallbacks(render);
            render.run();
            require(windows.adds == 1 && windows.removes == 1 && !Boolean.TRUE.equals(accessible(AssistOverlay.class, "attached").get(overlay)),
                    "invalidated-before-dispatch-clears-instead-of-rendering");
            fresh(current, lease, viewport);
            ((TextView) accessible(AssistOverlay.class, "details").get(overlay)).setText("FICTIONAL_PRIVATE_VIEW");
            windows.slowAdd = true;
            queue.invoke(fixture, timing);
            handler.removeCallbacks(render);
            render.run();
            require(windows.adds == 2 && windows.removes == 2 && !Boolean.TRUE.equals(accessible(AssistOverlay.class, "attached").get(overlay)),
                    "render-crossing-real-lease-deadline-is-cleared-before-return");
            require(((TextView) accessible(AssistOverlay.class, "details").get(overlay)).getText().length() == 0,
                    "expired-render-clears-fictional-private-view");
            fresh(current, lease, viewport);
            Method cancel = ChatGateService.class.getDeclaredMethod("cancelOverlayRefresh");
            cancel.setAccessible(true);
            queue.invoke(fixture, timing);
            cancel.invoke(fixture);
            render.run();
            require(!handler.hasCallbacks(render) && windows.adds == 2, "cancelled-refresh-cannot-be-replayed");
            queue.invoke(fixture, timing);
            handler.removeCallbacks(render);
            Field connection = accessible(ChatGateService.class, "connectionEpoch");
            connection.setLong(fixture, connection.getLong(fixture) + 1);
            render.run();
            require(windows.adds == 2, "previous-connection-render-cannot-touch-current-window");
            active.setBoolean(null, false);
            queue.invoke(fixture, timing);
            handler.removeCallbacks(render);
            render.run();
            require(windows.adds == 2, "stopped-capture-cannot-render-even-with-fresh-certificate");
            active.setBoolean(null, true);
            queue.invoke(fixture, timing);
            handler.removeCallbacks(render);
            connected.set(null, null);
            render.run();
            require(windows.adds == 2 && windows.removes == 2, "disconnected-pending-render-does-not-touch-window");
            connected.set(null, fixture);
            windows.slowAdd = false;
            fresh(current, lease, viewport);
            AssistantState.requestExpansion(true);
            overlay.refresh(true);
            require(AssistantState.expanded && AssistantState.expansionRequested(), "explicit-detail-preference");
            ((TextView) accessible(AssistOverlay.class, "details").get(overlay)).setText("FICTIONAL_PRIVATE_VIEW");
            long previousOperation = accessible(AssistOverlay.class, "operationRevision").getLong(overlay);
            Method revoke = ChatGateService.class.getDeclaredMethod("revokeCurrent"); revoke.setAccessible(true);
            revoke.invoke(fixture);
            require(!AssistantState.expanded && AssistantState.expansionRequested()
                    && !Boolean.TRUE.equals(accessible(AssistOverlay.class, "attached").get(overlay)), "revoke-hides-but-keeps-preference");
            require(((TextView) accessible(AssistOverlay.class, "details").get(overlay)).getText().length() == 0
                    && accessible(AssistOverlay.class, "operationRevision").getLong(overlay) > previousOperation,
                    "revoke-clears-content-and-old-operations");
            overlay.refresh(true);
            require(!AssistantState.expanded && !Boolean.TRUE.equals(accessible(AssistOverlay.class, "attached").get(overlay)),
                    "caller-show-cannot-override-invalid-source");
            fresh(current, lease, viewport);
            overlay.refresh(true);
            require(AssistantState.expanded && AssistantState.expansionRequested()
                    && !((TextView) accessible(AssistOverlay.class, "details").get(overlay)).getText().toString().contains("FICTIONAL_PRIVATE_VIEW"),
                    "fresh-source-restores-empty-details-only");
            previousOperation = accessible(AssistOverlay.class, "operationRevision").getLong(overlay);
            AssistantState.expanded = false;
            overlay.refresh(true);
            require(AssistantState.expanded && accessible(AssistOverlay.class, "operationRevision").getLong(overlay) > previousOperation,
                    "restore-without-close-still-revokes-old-operations");
            AssistantState.requestExpansion(false);
            overlay.refresh(false);
            fresh(current, lease, viewport);
            overlay.refresh(true);
            require(!AssistantState.expanded && !AssistantState.expansionRequested(), "explicit-collapse-stays-collapsed");
            AssistantState.requestExpansion(true);
            AssistantState.clear();
            require(!AssistantState.expanded && !AssistantState.expansionRequested(), "stop-clears-preference");
        } finally {
            handler.removeCallbacksAndMessages(null);
            active.setBoolean(null, false);
            AssistantState.clear();
            connected.set(null, null);
            current.set(null, original);
            if (overlay != null) overlay.destroy();
            lease.close();
            ((ExecutorService) accessible(ChatGateService.class, "metadataWorker").get(fixture)).shutdownNow();
        }
    }

    private void fresh(Field current, ScopeLease lease, ChatViewport viewport) throws Exception {
        long now = SystemClock.elapsedRealtime();
        ScopeLease.Request request = lease.begin(now);
        ScopeLease.Certificate certificate = lease.complete(request, now, false);
        current.set(null, new ChatGateService.Snapshot(true, -1, 0, 0, now, viewport, lease, certificate));
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

    private static final class CountingWindows implements WindowManager {
        private final WindowManager delegate;
        int adds;
        int removes;
        boolean slowAdd;

        CountingWindows(WindowManager delegate) { this.delegate = delegate; }
        @Override public Display getDefaultDisplay() { return delegate.getDefaultDisplay(); }
        @Override public WindowMetrics getCurrentWindowMetrics() { return delegate.getCurrentWindowMetrics(); }
        @Override public WindowMetrics getMaximumWindowMetrics() { return delegate.getMaximumWindowMetrics(); }
        @Override public void addView(View view, ViewGroup.LayoutParams parameters) {
            adds++;
            if (slowAdd) SystemClock.sleep(230);
        }
        @Override public void updateViewLayout(View view, ViewGroup.LayoutParams parameters) { }
        @Override public void removeView(View view) { removes++; }
        @Override public void removeViewImmediate(View view) { removes++; }
    }
}
