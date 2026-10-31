package com.codextemp.wechatcaptureprobe;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.function.BooleanSupplier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public final class ChatGateService extends AccessibilityService {
    public static final class Snapshot {
        final boolean wechat;
        final int keyboardTop;
        final long generation;
        final long enteredAt;
        final long checkedAt;
        final ChatViewport viewport;
        private final ScopeLease lease;
        private final ScopeLease.Certificate certificate;

        Snapshot(boolean wechat, int keyboardTop, long generation, long enteredAt, long checkedAt) {
            this(wechat, keyboardTop, generation, enteredAt, checkedAt, null);
        }

        Snapshot(boolean wechat, int keyboardTop, long generation, long enteredAt, long checkedAt, ChatViewport viewport) {
            this(wechat, keyboardTop, generation, enteredAt, checkedAt, viewport, null, null);
        }

        Snapshot(boolean wechat, int keyboardTop, long generation, long enteredAt, long checkedAt, ChatViewport viewport,
                 ScopeLease lease, ScopeLease.Certificate certificate) {
            this.wechat = wechat;
            this.keyboardTop = keyboardTop;
            this.generation = generation;
            this.enteredAt = enteredAt;
            this.checkedAt = checkedAt;
            this.viewport = viewport;
            this.lease = lease;
            this.certificate = certificate;
        }

        boolean valid() {
            if (lease == null) return validAt(SystemClock.elapsedRealtime());
            synchronized (lease) {
                return validAt(SystemClock.elapsedRealtime());
            }
        }

        private boolean validAt(long now) {
            return wechat && viewport != null && now >= checkedAt && now - checkedAt < 1200
                    && (lease == null || lease.valid(certificate, now));
        }
    }

    private static volatile ChatGateService connected;
    private static volatile Snapshot current = new Snapshot(false, -1, 0, 0, 0);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastPackage = "";
    private volatile int lastWindow = -1;
    private long generation;
    private long enteredAt;
    private ScreenGeometry lastGeometry;
    private ChatViewport lastViewport;
    private AssistOverlay overlay;
    private static final long QUERY_LIFETIME = 200;
    private static final long QUERY_INTERVAL = 80;
    private final ScopeLease lease = new ScopeLease(QUERY_LIFETIME);
    private final ScheduledExecutorService metadataWorker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "chat-scope-metadata");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Verification> verifications = new ArrayList<>();
    private final Object admissionLock = new Object();
    private ScheduledFuture<?> queryWake;
    private long queryWakeToken;
    private long nextQueryAt = -1;
    private volatile boolean requestsClosed;
    private volatile boolean verificationWaiting;
    private boolean requestedAgain;
    private long lastRequestedAt = -QUERY_INTERVAL;
    private long nextInspectionAt = -1;
    private int lastOwnedOverlay = -1;
    private final OwnWindowIds ownedWindows = new OwnWindowIds(64);
    private final ScopeQueryTrace queryTrace = new ScopeQueryTrace(64);
    private volatile long connectionEpoch;
    private boolean renderPending;
    private long renderConnectionEpoch;
    private ScopeQueryTrace.Entry pendingRenderTiming;
    private final Runnable render = this::renderOverlay;

    private static final class Verification {
        final long minimumIdentifier;
        final CompletableFuture<Snapshot> result;

        Verification(long minimumIdentifier, CompletableFuture<Snapshot> result) {
            this.minimumIdentifier = minimumIdentifier;
            this.result = result;
        }
    }

    private static final class WindowMetadata {
        final String packageName;
        final int windowId;
        final int keyboardTop;
        final int keyboardLeft;
        final int keyboardRight;
        final ChatViewport.Bounds application;
        final List<WindowOcclusion> occlusions;
        final ScreenGeometry geometry;

        WindowMetadata(String packageName, int windowId, int keyboardTop, int keyboardLeft, int keyboardRight,
                       ChatViewport.Bounds application, List<WindowOcclusion> occlusions,
                       ScreenGeometry geometry) {
            this.packageName = packageName;
            this.windowId = windowId;
            this.keyboardTop = keyboardTop;
            this.keyboardLeft = keyboardLeft;
            this.keyboardRight = keyboardRight;
            this.application = application;
            this.occlusions = occlusions;
            this.geometry = geometry;
        }
    }
    private static final class WindowOcclusion {
        final int windowId;
        final boolean accessibilityOverlay;
        final ChatViewport.Bounds bounds;
        final boolean uncertain;

        WindowOcclusion(int windowId, boolean accessibilityOverlay, ChatViewport.Bounds bounds, boolean uncertain) {
            this.windowId = windowId;
            this.accessibilityOverlay = accessibilityOverlay;
            this.bounds = bounds;
            this.uncertain = uncertain;
        }
    }
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            nextInspectionAt = -1;
            inspect(false);
            scheduleInspection(SystemClock.elapsedRealtime() + QUERY_INTERVAL);
        }
    };

    private void scheduleInspection(long due) {
        if (connected != this || nextInspectionAt >= 0 && nextInspectionAt <= due) return;
        handler.removeCallbacks(refresh);
        nextInspectionAt = due;
        handler.postDelayed(refresh, Math.max(0, due - SystemClock.elapsedRealtime()));
    }

    private void scheduleQueryWakeLocked(long due) {
        if (requestsClosed || connected != this || metadataWorker.isShutdown()) return;
        if (queryWake != null && !queryWake.isDone() && nextQueryAt <= due) return;
        if (queryWake != null) queryWake.cancel(false);
        long token = ++queryWakeToken;
        long connection = connectionEpoch;
        nextQueryAt = due;
        queryWake = metadataWorker.schedule(() -> {
            synchronized (admissionLock) {
                if (token != queryWakeToken || connection != connectionEpoch || requestsClosed || connected != this) return;
                queryWake = null;
                nextQueryAt = -1;
            }
            requestMetadata(connection);
        }, Math.max(0, due - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
    }

    private void resetQuerySchedule(boolean closed) {
        synchronized (admissionLock) {
            requestsClosed = closed;
            queryWakeToken++;
            if (queryWake != null) queryWake.cancel(false);
            queryWake = null;
            nextQueryAt = -1;
        }
    }

    private void queueOverlayRefresh(ScopeQueryTrace.Entry timing) {
        if (connected != this) return;
        if (pendingRenderTiming != null && pendingRenderTiming != timing) {
            pendingRenderTiming.renderDisposition = ScopeQueryTrace.RENDER_SUPERSEDED;
        }
        pendingRenderTiming = timing;
        if (timing != null) {
            timing.renderQueuedAt = SystemClock.elapsedRealtime();
            timing.renderDisposition = ScopeQueryTrace.RENDER_QUEUED;
        }
        if (renderPending) return;
        renderPending = true;
        renderConnectionEpoch = connectionEpoch;
        handler.postDelayed(render, 16);
    }

    private void cancelOverlayRefresh() {
        handler.removeCallbacks(render);
        renderPending = false;
        if (pendingRenderTiming != null) pendingRenderTiming.renderDisposition = ScopeQueryTrace.RENDER_CANCELLED;
        pendingRenderTiming = null;
    }

    private void renderOverlay() {
        if (!renderPending) return;
        renderPending = false;
        ScopeQueryTrace.Entry timing = pendingRenderTiming;
        pendingRenderTiming = null;
        if (connected != this || renderConnectionEpoch != connectionEpoch || overlay == null) {
            if (timing != null) timing.renderDisposition = ScopeQueryTrace.RENDER_CANCELLED;
            return;
        }
        if (timing != null) timing.renderStartedAt = SystemClock.elapsedRealtime();
        try {
            boolean show = current.valid() && AssistantCaptureService.running();
            overlay.refresh(show);
            boolean valid = connected == this && renderConnectionEpoch == connectionEpoch
                    && current.valid() && AssistantCaptureService.running();
            if (show && !valid) overlay.close();
            if (timing != null) timing.renderDisposition = show && valid
                    ? ScopeQueryTrace.RENDER_SHOWN : ScopeQueryTrace.RENDER_CLEARED;
        } finally {
            if (timing != null) timing.renderFinishedAt = SystemClock.elapsedRealtime();
        }
    }

    static boolean enabled() {
        return connected != null;
    }

    static OverlayOcclusion publishedCaptureOcclusion() {
        ChatGateService service = connected;
        return service == null || service.overlay == null ? null : service.overlay.publishedCaptureOcclusion();
    }

    static OverlayOcclusion captureOcclusion() {
        ChatGateService service = connected;
        return service == null || service.overlay == null || Looper.myLooper() != Looper.getMainLooper()
                ? null : service.overlay.captureOcclusion();
    }

    /** 标注条当前全屏包围盒（无显示时 null）；仅供采集端指纹遮蔽自身窗口，不参与遮挡计数。 */
    static ChatViewport.Bounds insightBounds() {
        ChatGateService service = connected;
        return service == null || service.overlay == null ? null : service.overlay.publishedInsightBounds();
    }

    /** 回复建议面板展开态全屏包围盒（无建议时 null）；与标注条同策略，仅供指纹遮蔽。 */
    static ChatViewport.Bounds suggestionBounds() {
        ChatGateService service = connected;
        return service == null || service.overlay == null ? null : service.overlay.publishedSuggestionBounds();
    }

    /** 自有安全窗口实际可见区域（意图卡/建议面板，隐藏即不含）；采集端并入识别遮蔽，
     *  防止面板自身文字被 OCR 当成新聊天消息、造成结果自指清空循环。字段均为 volatile 读。 */
    private static volatile java.util.List<ChatViewport.Bounds> lastSelfWindowBounds = java.util.Collections.emptyList();
    /** 自有安全窗口几何最近一次变化的时间（elapsedRealtime）。窗口出现/消失/移动带系统
     *  动画，动画帧里的面板像素尚未落到最终几何，采集端布局检测会短时失败；这属于助手
     *  自身过渡，不是聊天场景失效，短时间内不应按「不在聊天页」清空会话与结果。 */
    private static volatile long selfWindowsChangedAt;

    static java.util.List<ChatViewport.Bounds> selfWindowBounds() {
        ChatGateService service = connected;
        java.util.List<ChatViewport.Bounds> bounds = service == null || service.overlay == null
                ? java.util.Collections.<ChatViewport.Bounds>emptyList() : service.overlay.publishedSelfWindowBounds();
        java.util.List<ChatViewport.Bounds> previous = lastSelfWindowBounds;
        if (!sameBounds(previous, bounds)) {
            lastSelfWindowBounds = bounds;
            selfWindowsChangedAt = android.os.SystemClock.elapsedRealtime();
        }
        return bounds;
    }

    /** 自有窗口几何最近变化时间；采集端用它豁免窗口动画期的布局检测失败。 */
    static long selfWindowsChangedAt() {
        return selfWindowsChangedAt;
    }

    /** 回复建议面板是否处于展开态（覆盖消息区）。展开期间采集端暂停帧处理：
     *  被面板盖住的气泡会从 OCR 消息集合中消失，若继续发布会把「展示动作」误判为
     *  「聊天内容变化」而清空刚落地的结果，形成自指循环。 */
    private static volatile boolean suggestExpanded;

    static void setSuggestExpanded(boolean expanded) {
        suggestExpanded = expanded;
    }

    static boolean suggestExpanded() {
        return suggestExpanded;
    }

    private static boolean sameBounds(java.util.List<ChatViewport.Bounds> a, java.util.List<ChatViewport.Bounds> b) {
        if (a.size() != b.size()) return false;
        for (int index = 0; index < a.size(); index++) {
            ChatViewport.Bounds first = a.get(index);
            ChatViewport.Bounds second = b.get(index);
            if (first.left != second.left || first.top != second.top
                    || first.right != second.right || first.bottom != second.bottom) return false;
        }
        return true;
    }

    /** 分析结果落地或失效后调用：覆盖层只在无障碍事件/范围查询时重绘，安静聊天里若不主动
     *  通知，刚落地的回复建议面板会一直等到用户下一次滚动或输入才出现。 */
    static void notifyOverlayAnalysisChanged() {
        ChatGateService service = connected;
        if (service == null || service.overlay == null) return;
        service.queueOverlayRefresh(null);
    }

    static Snapshot snapshot() {
        return current;
    }

    static Snapshot verify() {
        ChatGateService service = connected;
        if (service == null) {
            return current;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            service.inspect(true);
            return current;
        }
        CompletableFuture<Snapshot> inspection = new CompletableFuture<>();
        service.handler.post(() -> service.verifyLater(inspection));
        try {
            return inspection.get(350, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException exception) {
        } finally {
            inspection.cancel(false);
        }
        return new Snapshot(false, -1, current.generation, 0, 0);
    }

    static DraftPlan.Editor composerEditor(AssistantCaptureService.ComposerProof proof, BooleanSupplier current) {
        ChatGateService service = connected;
        if (Looper.myLooper() == Looper.getMainLooper() || proof == null || service == null || !current.getAsBoolean()) {
            return null;
        }
        Snapshot gate = verify();
        if (!gate.valid() || gate.generation != proof.target.generation || connected != service || !current.getAsBoolean()) return null;
        return new AccessibilityDraftEditor(service::getRootInActiveWindow, "com.tencent.mm", service.lastWindow,
                proof.bounds, () -> connected == service && current.getAsBoolean());
    }

    @Override
    protected void onServiceConnected() {
        cancelOverlayRefresh();
        connectionEpoch++;
        ownedWindows.clear();
        queryTrace.reset();
        lastOwnedOverlay = -1;
        lease.invalidate();
        connected = this;
        resetQuerySchedule(false);
        generation = current.generation + 1;
        revokeCurrent();
        if (overlay != null) {
            overlay.destroy();
        }
        overlay = new AssistOverlay(this);
        handler.removeCallbacks(refresh);
        nextInspectionAt = -1;
        scheduleInspection(SystemClock.elapsedRealtime());
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (connected != this) return;
        if (event != null && "com.tencent.mm".contentEquals(
                event.getPackageName() == null ? "" : event.getPackageName())) {
            int type = event.getEventType();
            if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                    || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                // 先采uptime再采elapsed，采样间隙只会延长等待，不会提前开放展示。
                long uptime = SystemClock.uptimeMillis();
                if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                    ChatInteraction.observedScroll(SystemClock.elapsedRealtime(), uptime, event.getEventTime());
                } else {
                    ChatInteraction.observedEvent(SystemClock.elapsedRealtime(), uptime, event.getEventTime());
                }
                // 输入/滚动先收起建议，不等待下一轮节点查询；刷新仍复核当前范围和采集状态。
                if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) queueOverlayRefresh(null);
            }
        }
        if (event != null && (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED)) {
            boolean ownPackage = getPackageName().contentEquals(event.getPackageName() == null ? "" : event.getPackageName());
            // 本应用包名的 WINDOWS_CHANGED 必然来自三个自窗（主卡片/意图卡/建议面板）的
            // 出现、消失或变形（助手 Activity 在前台时范围本就失效，不依赖此路径）：
            // 一律忽略，否则自窗变形会被判为外部场景变化 → revokeCurrent 连环清空会话。
            // （旧实现靠 createAccessibilityNodeInfo 取 windowId 与事件匹配，实测与事件流
            // 不同源（34095 vs 34093）且动画期失败返回 -1，点悬浮球必触发 LEAVE 风暴，
            // 2026-10-30 22:55 真机日志。）外部应用切换仍由 WINDOW_STATE_CHANGED 与
            // 周期 query 兜底。
            boolean ownWindowEvent = ownPackage
                    && event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            boolean knownOwnEvent = ownedWindows.ignoreEvent(event.getWindowId(),
                    event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED, ownPackage);
            if (ownWindowEvent || knownOwnEvent) return;
            Log.i("ScopeGate", "INVALIDATE eventType=" + event.getEventType() + " windowId=" + event.getWindowId());
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && "com.tencent.mm".contentEquals(event.getPackageName() == null ? "" : event.getPackageName())) {
                AssistantState.clearContext("微信窗口状态变化，本轮背景已解除，需人工重新确认");
            }
            lease.invalidate();
            revokeCurrent();
            inspect(false);
        }
    }

    void rememberOwnedWindow(int identifier) {
        if (connected != this || identifier < 0) return;
        if (ownedWindows.remember(identifier)) {
            Log.i("ScopeGate", "OWN_WINDOW id=" + identifier + " retained=" + ownedWindows.size());
        }
        lastOwnedOverlay = identifier;
    }

    java.util.function.IntConsumer ownedWindowRegistration() {
        long expected = connectionEpoch;
        return identifier -> {
            if (expected == connectionEpoch) rememberOwnedWindow(identifier);
        };
    }

    private void inspect() { inspect(true); }

    private void verifyLater(CompletableFuture<Snapshot> result) {
        if (connected != this || result.isDone()) {
            result.complete(unavailable());
            return;
        }
        verifications.removeIf(verification -> verification.result.isDone());
        verificationWaiting = !verifications.isEmpty();
        if (verifications.size() >= 16) {
            result.complete(unavailable());
            return;
        }
        Verification verification = new Verification(lease.nextIdentifier(), result);
        verifications.add(verification);
        verificationWaiting = true;
        handler.postDelayed(() -> {
            verification.result.complete(unavailable());
            verifications.remove(verification);
            verificationWaiting = !verifications.isEmpty();
        }, 350);
        inspect(true);
    }

    private Snapshot unavailable() { return new Snapshot(false, -1, generation, 0, 0); }

    private long expireLease() {
        synchronized (lease) {
            long now = SystemClock.elapsedRealtime();
            if (!lease.expire(now)) return -1;
            queryTrace.expired(now);
            return now;
        }
    }

    private void inspect(boolean force) {
        if (connected != this) return;
        long expiredAt = expireLease();
        if (expiredAt >= 0) {
            Log.i("ScopeGate", "EXPIRED checkedAgeMs=" + (expiredAt - current.checkedAt) + " pending=" + lease.pending());
            revokeCurrent();
        }
        synchronized (admissionLock) {
            if (force) requestedAgain = true;
        }
        long due = requestMetadata(connectionEpoch);
        if (due >= 0) scheduleInspection(due);
    }

    private long requestMetadata(long connection) {
        synchronized (admissionLock) {
            if (requestsClosed || connected != this || connection != connectionEpoch || metadataWorker.isShutdown()) return -1;
            boolean active = AssistantCaptureService.running() || CaptureService.running();
            if (!active && !requestedAgain && !verificationWaiting || lease.pending()) return -1;
            boolean explicit = requestedAgain || verificationWaiting;
            long now = SystemClock.elapsedRealtime();
            long interval = current.wechat ? QUERY_INTERVAL : 300;
            if (!verificationWaiting && now - lastRequestedAt < interval) {
                long due = lastRequestedAt + interval;
                scheduleQueryWakeLocked(due);
                return due;
            }
            ScopeLease.Request request = lease.begin(now);
            if (request == null) return -1;
            ScopeQueryTrace.Entry timing = queryTrace.begin(request);
            lastRequestedAt = now;
            requestedAgain = false;
            metadataWorker.execute(() -> {
                long workerNow = SystemClock.elapsedRealtime();
                if (timing != null) timing.workerStartedAt = workerNow;
                WindowMetadata metadata = null;
                try {
                    if (!requestsClosed && connected == this && connection == connectionEpoch
                            && workerNow >= request.startedAt && workerNow - request.startedAt < QUERY_LIFETIME
                            && (AssistantCaptureService.running() || CaptureService.running() || explicit)) {
                        metadata = collectMetadata(timing);
                    }
                } catch (RuntimeException failure) { }
                WindowMetadata result = metadata;
                if (timing != null) timing.workerFinishedAt = SystemClock.elapsedRealtime();
                handler.post(() -> {
                    if (timing != null) timing.acceptStartedAt = SystemClock.elapsedRealtime();
                    try { acceptMetadata(request, result, timing); }
                    finally { if (timing != null) timing.finishedAt = SystemClock.elapsedRealtime(); }
                });
            });
            handler.postDelayed(() -> {
                if (connected == this && expireLease() >= 0) revokeCurrent();
            }, QUERY_LIFETIME);
            long due = now + interval;
            scheduleQueryWakeLocked(due);
            return due;
        }
    }

    private void revokeCurrent() {
        cancelOverlayRefresh();
        enteredAt = SystemClock.elapsedRealtime();
        current = new Snapshot(false, -1, ++generation, enteredAt, enteredAt);
        AssistantState.leave(generation);
        AssistantState.expanded = false;
        if (overlay != null) overlay.close();
    }

    private void resolveVerifications(long identifier, Snapshot result) {
        List<Verification> ready = new ArrayList<>();
        for (Verification verification : verifications) {
            if (verification.result.isDone() || verification.minimumIdentifier <= identifier) ready.add(verification);
        }
        verifications.removeAll(ready);
        verificationWaiting = !verifications.isEmpty();
        for (Verification verification : ready) verification.result.complete(result);
    }

    private WindowMetadata collectMetadata(ScopeQueryTrace.Entry timing) {
        String packageName = "";
        int windowId = -1;
        int keyboardTop = -1;
        int keyboardLeft = 0;
        int keyboardRight = 0;
        ChatViewport.Bounds application = null;
        List<WindowOcclusion> occlusions = new ArrayList<>();
        List<AccessibilityWindowInfo> windows = new ArrayList<>();
        AccessibilityNodeInfo root = null;
        try {
            if (timing != null) timing.rootStartedAt = SystemClock.elapsedRealtime();
            root = getRootInActiveWindow();
            if (timing != null) timing.rootFinishedAt = SystemClock.elapsedRealtime();
            if (root != null) {
                packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
                windowId = root.getWindowId();
            }
            windows = getWindows();
            if (timing != null) timing.windowsFinishedAt = SystemClock.elapsedRealtime();
            int applicationLayer = Integer.MAX_VALUE;
            for (AccessibilityWindowInfo window : windows) {
                if (window.getId() == windowId && window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                        && (android.os.Build.VERSION.SDK_INT < 30 || window.getDisplayId() == android.view.Display.DEFAULT_DISPLAY)) {
                    Rect bounds = new Rect();
                    window.getBoundsInScreen(bounds);
                    application = new ChatViewport.Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom);
                    applicationLayer = window.getLayer();
                }
                if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    Rect bounds = new Rect();
                    window.getBoundsInScreen(bounds);
                    if (!bounds.isEmpty()) {
                        if (keyboardTop < 0) {
                            keyboardTop = bounds.top;
                            keyboardLeft = bounds.left;
                            keyboardRight = bounds.right;
                        } else {
                            keyboardTop = Math.min(keyboardTop, bounds.top);
                            keyboardLeft = Math.min(keyboardLeft, bounds.left);
                            keyboardRight = Math.max(keyboardRight, bounds.right);
                        }
                    }
                }
            }
            for (AccessibilityWindowInfo window : windows) {
                boolean accessibilityOverlay = window.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY;
                if (window.getLayer() <= applicationLayer || ownedWindows.ignoreOcclusion(window.getId(), accessibilityOverlay)
                        || window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                        || android.os.Build.VERSION.SDK_INT >= 30 && window.getDisplayId() != android.view.Display.DEFAULT_DISPLAY) {
                    continue;
                }
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                boolean uncertain = false;
                if (window.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                        || window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    AccessibilityNodeInfo foreignRoot = null;
                    try {
                        foreignRoot = window.getRoot();
                        Rect refreshed = new Rect();
                        if (foreignRoot == null || !foreignRoot.refresh()) {
                            uncertain = true;
                        } else {
                            foreignRoot.getBoundsInScreen(refreshed);
                            if (refreshed.isEmpty()) {
                                uncertain = true;
                            } else {
                                bounds.union(refreshed);
                            }
                        }
                    } catch (RuntimeException failure) {
                        uncertain = true;
                    } finally {
                        if (foreignRoot != null) {
                            foreignRoot.recycle();
                        }
                    }
                }
                occlusions.add(new WindowOcclusion(window.getId(), accessibilityOverlay,
                        new ChatViewport.Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom), uncertain));
            }
            if (timing != null) timing.occlusionsFinishedAt = SystemClock.elapsedRealtime();
        } catch (RuntimeException exception) {
            packageName = "";
            application = null;
        } finally {
            if (root != null) {
                root.recycle();
            }
            for (AccessibilityWindowInfo window : windows) {
                window.recycle();
            }
        }
        if (timing != null) timing.geometryStartedAt = SystemClock.elapsedRealtime();
        ScreenGeometry geometry = ScreenGeometryReader.read(this);
        if (timing != null) timing.geometryFinishedAt = SystemClock.elapsedRealtime();
        return new WindowMetadata(packageName, windowId, keyboardTop, keyboardLeft, keyboardRight, application,
                occlusions, geometry);
    }

    private void acceptMetadata(ScopeLease.Request request, WindowMetadata metadata, ScopeQueryTrace.Entry timing) {
        long now = SystemClock.elapsedRealtime();
        if (connected != this) {
            if (timing != null) timing.outcome = ScopeQueryTrace.DISCONNECTED;
            lease.reject(request);
            return;
        }
        ScreenGeometry geometry = ScreenGeometryReader.read(this);
        if (timing != null) timing.acceptGeometryFinishedAt = SystemClock.elapsedRealtime();
        if (metadata == null || metadata.geometry == null || !metadata.geometry.same(geometry)) {
            if (timing != null) timing.outcome = ScopeQueryTrace.INVALID_METADATA;
            if (lease.reject(request)) revokeCurrent();
            resolveVerifications(request.identifier, unavailable());
            inspect(false);
            return;
        }
        String packageName = metadata.packageName;
        int windowId = metadata.windowId;
        int keyboardTop = metadata.keyboardTop;
        List<ChatViewport.Bounds> occlusions = new ArrayList<>();
        boolean overlayUncertain = false;
        for (WindowOcclusion occlusion : metadata.occlusions) {
            if (ownedWindows.ignoreOcclusion(occlusion.windowId, occlusion.accessibilityOverlay)) continue;
            occlusions.add(occlusion.bounds);
            overlayUncertain |= occlusion.uncertain;
        }
        boolean unlocked = !getSystemService(KeyguardManager.class).isKeyguardLocked()
                && getSystemService(PowerManager.class).isInteractive();
        if (!unlocked) {
            packageName = "";
        }
        ChatViewport viewport = "com.tencent.mm".equals(packageName) && !overlayUncertain
                ? ChatViewport.create(geometry, metadata.application, keyboardTop, metadata.keyboardLeft,
                metadata.keyboardRight, occlusions) : null;
        // 诊断日志（仅拒绝时打一次）：定位面板显示后范围查询判失的原因（包名/遮挡不确定/几何被拒）。
        if ("com.tencent.mm".equals(packageName) && viewport == null) {
            StringBuilder masks = new StringBuilder();
            for (ChatViewport.Bounds occlusion : occlusions) {
                masks.append('[').append(occlusion.left).append(',').append(occlusion.top)
                        .append(',').append(occlusion.right).append(',').append(occlusion.bottom).append(']');
            }
            ChatViewport.Bounds application = metadata.application;
            Log.i("ScopeGate", "QUERY_REJECT overlayUncertain=" + overlayUncertain
                    + " app=" + (application == null ? "null" : application.left + "," + application.top
                    + "," + application.right + "," + application.bottom)
                    + " keyboardTop=" + keyboardTop
                    + " occlusions=" + masks);
        }
        boolean sourceUncertain = "com.tencent.mm".equals(packageName) && viewport == null;
        boolean geometryChanged = lastGeometry == null ? geometry != null : !lastGeometry.same(geometry);
        boolean viewportChanged = lastViewport == null ? viewport != null : !lastViewport.same(viewport);
        boolean sceneChanged = !packageName.equals(lastPackage) || windowId != lastWindow || geometryChanged || viewportChanged;
        ScopeLease.Certificate certificate;
        synchronized (lease) {
            now = SystemClock.elapsedRealtime();
            certificate = lease.complete(request, now, sceneChanged);
        }
        if (timing != null) {
            timing.validatedAt = SystemClock.elapsedRealtime();
            timing.outcome = certificate == null ? ScopeQueryTrace.LEASE_REJECTED : ScopeQueryTrace.ACCEPTED;
        }
        if (certificate == null) {
            Log.i("ScopeGate", "DROP queryAgeMs=" + (now - request.startedAt));
            resolveVerifications(request.identifier, unavailable());
            inspect(false);
            return;
        }
        if (timing != null) timing.sceneStartedAt = SystemClock.elapsedRealtime();
        if (sceneChanged) {
            cancelOverlayRefresh();
            generation++;
            enteredAt = now;
            lastPackage = packageName;
            lastWindow = windowId;
            lastGeometry = geometry;
            lastViewport = viewport;
            AssistantState.leave(generation);
            AssistantState.expanded = false;
            if (overlay != null) {
                if (geometryChanged) {
                    overlay.destroy();
                    overlay = new AssistOverlay(this);
                } else {
                    overlay.close();
                }
            }
        }
        if (timing != null) timing.sceneFinishedAt = SystemClock.elapsedRealtime();
        boolean wechat = viewport != null;
        current = new Snapshot(wechat, keyboardTop, generation, enteredAt, request.startedAt, viewport, lease, certificate);
        if (!wechat) {
            AssistantState.leave(generation);
        }
        if (sourceUncertain && AssistantCaptureService.running()) {
            AssistantState.status = "微信窗口、标题或输入区被遮挡 / 空间不足，已暂停识别与填入";
        }
        if (!wechat || !AssistantCaptureService.running()) {
            cancelOverlayRefresh();
            if (overlay != null) overlay.close();
        }
        resolveVerifications(request.identifier, current);
        inspect(false);
        if (current.valid() && AssistantCaptureService.running()) queueOverlayRefresh(timing);
    }

    @Override
    public void onInterrupt() {
        if (connected != this) return;
        lease.invalidate();
        revokeCurrent();
    }

    @Override
    public void onDestroy() {
        resetQuerySchedule(true);
        cancelOverlayRefresh();
        connectionEpoch++;
        ownedWindows.clear();
        queryTrace.reset();
        lastOwnedOverlay = -1;
        handler.removeCallbacksAndMessages(null);
        nextInspectionAt = -1;
        lease.close();
        metadataWorker.shutdownNow();
        for (Verification verification : verifications) verification.result.complete(unavailable());
        verifications.clear();
        verificationWaiting = false;
        if (connected == this) {
            connected = null;
            current = new Snapshot(false, -1, ++generation, 0, 0);
            AssistantState.leave(generation);
        }
        if (overlay != null) {
            overlay.destroy();
        }
        super.onDestroy();
    }
}
