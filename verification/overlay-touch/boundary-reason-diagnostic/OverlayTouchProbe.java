package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.view.View;
import android.view.WindowManager;
import android.widget.ScrollView;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class OverlayTouchProbe extends Instrumentation {
    private static final String DONE = "com.codextemp.wechatcaptureprobe.TOUCH_INPUT_FINISHED";
    private static final String INSPECTED = "com.codextemp.wechatcaptureprobe.TOUCH_INSPECTED";
    private static final String OBSERVE = "com.codextemp.wechatcaptureprobe.TOUCH_OBSERVE";
    private Activity main;
    private Activity assistant;
    private boolean fixtureActive;
    private boolean registered;
    private volatile long completedStep;
    private volatile boolean inspected;
    private long step;
    private int checks;
    private int gestureActions;
    private int outsideChanges;
    private int insideScrolls;
    private int observedMessages;
    private boolean keyboardMode;
    private boolean expandedMode;
    private boolean demoMode;
    private boolean quickMode;
    private boolean verifyQuickClipboard;
    private String expectedClipboard;
    private String clipboardSentinel;
    private boolean clipboardCleanupRequired;
    private boolean quickPerf;
    private boolean quickKeyboard;
    private boolean quickLifecycle;
    private boolean quickDetailRecovery;
    private boolean quickHeaderStop;
    private HeaderStopHold headerStopHold;
    private boolean quickEndurance;
    private boolean quickSwitch;
    private boolean navigationOnly;
    private boolean visualNavigationOnly;
    private boolean visualFixedOpen;
    private boolean enduranceMode;
    private int enduranceSeconds;
    private String enduranceSummary = "";
    private QueueMeter queueMeter;
    private boolean traceWindows;
    private boolean traceBounds;
    private BoundaryReasonJournal boundaryReasons;
    private boolean boundaryReasonsReported;
    private WindowJournal windowJournal;
    private RecoveryJournal recoveryJournal;
    private boolean operateSystemUi;
    private boolean holdFailure;
    private boolean verifyColdState;
    private boolean coldStateVerified;
    private long startupReadyMs;
    private int keyboardShownTop = -1;
    private ProjectionSession owner;
    private SharedPreferences position;
    private Map<String, ?> originalPosition;
    private final BroadcastReceiver observer = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (DONE.equals(intent.getAction())) {
                completedStep = intent.getLongExtra("step", -1);
            } else if (INSPECTED.equals(intent.getAction())) {
                inspected = true;
            } else if (OBSERVE.equals(intent.getAction()) && windowJournal != null) {
                windowJournal.freeze("DRIVER_REFUSAL");
            }
        }
    };

    private static final class Layout {
        final Rect panel;
        final Rect ball;
        final Rect scroll;
        final Rect stop;
        final boolean right;
        final int scrollY;
        final boolean scrollable;
        Layout(Rect panel, Rect ball, Rect scroll, Rect stop, boolean right, int scrollY, boolean scrollable) {
            this.panel = panel;
            this.ball = ball;
            this.scroll = scroll;
            this.stop = stop;
            this.right = right;
            this.scrollY = scrollY;
            this.scrollable = scrollable;
        }
    }

    private static final class QueueMeter implements AutoCloseable {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicBoolean pending = new AtomicBoolean();
        private final List<Long> delays = Collections.synchronizedList(new ArrayList<>());
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "overlay-endurance-heartbeat");
            thread.setDaemon(true);
            return thread;
        });

        QueueMeter() {
            timer.scheduleAtFixedRate(() -> {
                if (!active.get() || !pending.compareAndSet(false, true)) return;
                long posted = SystemClock.elapsedRealtimeNanos();
                handler.post(() -> {
                    if (active.get()) delays.add((SystemClock.elapsedRealtimeNanos() - posted) / 1000000);
                    pending.set(false);
                });
            }, 0, 250, TimeUnit.MILLISECONDS);
        }

        String summary() {
            List<Long> sorted;
            synchronized (delays) { sorted = new ArrayList<>(delays); }
            Collections.sort(sorted);
            return "queueSamples=" + sorted.size() + " queueP50Ms=" + percentile(sorted, 0.50)
                    + " queueP95Ms=" + percentile(sorted, 0.95) + " queueMaxMs=" + percentile(sorted, 1.0);
        }

        private static long percentile(List<Long> values, double fraction) {
            return values.isEmpty() ? -1 : values.get(Math.min(values.size() - 1,
                    Math.max(0, (int) Math.ceil(values.size() * fraction) - 1)));
        }

        @Override public void close() {
            active.set(false);
            timer.shutdownNow();
            handler.removeCallbacksAndMessages(null);
        }
    }

    private final class WindowJournal implements AutoCloseable {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final ArrayDeque<String> records = new ArrayDeque<>();
        private final long started = SystemClock.elapsedRealtime();
        private View watched;
        private volatile boolean frozen;
        private volatile boolean closed;
        private String signature = "";
        private int discarded;
        private int polls;
        private long maximumPollMillis;
        private int querySamples;
        private long maximumSampleMillis;
        private long sampledIdentifier;
        private Object sampledService;
        private final ScopeQueryTrace queryTrace;
        private final List<String> queryRecords = new ArrayList<>();
        private volatile long stageStep;
        private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "scope-deadline-sampler");
            thread.setDaemon(true);
            return thread;
        });
        private final View.OnAttachStateChangeListener listener = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { mark("ATTACH", true); }
            @Override public void onViewDetachedFromWindow(View view) { mark("DETACH", true); }
        };
        private final Runnable refresh = () -> poll();

        WindowJournal() {
            Object service = field(ChatGateService.class, null, "connected");
            queryTrace = (ScopeQueryTrace) field(ChatGateService.class, service, "queryTrace");
            queryTrace.start();
            poll();
            sampler.scheduleAtFixedRate(this::sampleUnavailableQuery, 50, 50, TimeUnit.MILLISECONDS);
        }

        void stage(String name, long identifier) {
            stageStep = identifier;
            long queued = SystemClock.elapsedRealtime();
            handler.post(() -> mark("STAGE_" + name + "_step_" + identifier
                    + "_queueMs_" + (SystemClock.elapsedRealtime() - queued), false));
        }

        private void sampleUnavailableQuery() {
            if (closed || frozen) return;
            try {
                Object service = field(ChatGateService.class, null, "connected");
                if (service == null) return;
                ScopeLease lease = (ScopeLease) field(ChatGateService.class, service, "lease");
                ScopeLease.Request request;
                long epoch;
                long lifetime;
                long began = SystemClock.elapsedRealtime();
                ChatGateService.Snapshot scope = ChatGateService.snapshot();
                boolean scopeUnavailable = !scope.valid();
                synchronized (lease) {
                    request = (ScopeLease.Request) field(ScopeLease.class, lease, "pending");
                    lifetime = ((Number) field(ScopeLease.class, lease, "lifetime")).longValue();
                    epoch = ((Number) field(ScopeLease.class, lease, "epoch")).longValue();
                }
                if (request == null || began - request.startedAt < lifetime
                        && (!scopeUnavailable || began - request.startedAt < Math.min(100, lifetime))
                        || service == sampledService && request.identifier == sampledIdentifier) return;
                ThreadGroup group = Thread.currentThread().getThreadGroup();
                while (group.getParent() != null) group = group.getParent();
                Thread[] threads = new Thread[Math.max(64, Math.min(1024, group.activeCount() * 2 + 8))];
                int count = group.enumerate(threads, true);
                Thread worker = null;
                int candidates = 0;
                for (int index = 0; index < count; index++) {
                    Thread thread = threads[index];
                    if (thread != null && thread.getName().equals("chat-scope-metadata")) {
                        candidates++;
                        worker = thread;
                    }
                }
                Thread mainThread = Looper.getMainLooper().getThread();
                String workerStack = candidates == 1 ? stack(worker) : "AMBIGUOUS";
                String mainStack = stack(mainThread);
                long ended = SystemClock.elapsedRealtime();
                long afterEpoch;
                synchronized (lease) {
                    if (field(ScopeLease.class, lease, "pending") != request) return;
                    afterEpoch = ((Number) field(ScopeLease.class, lease, "epoch")).longValue();
                }
                if (field(ChatGateService.class, null, "connected") != service) return;
                synchronized (this) {
                    if (closed || frozen) return;
                    sampledService = service;
                    sampledIdentifier = request.identifier;
                    querySamples++;
                    maximumSampleMillis = Math.max(maximumSampleMillis, ended - began);
                    append("tMs=" + (ended - started) + " event=QUERY_UNAVAILABLE stageStep=" + stageStep
                            + " pendingId=" + request.identifier + " pendingEpoch=" + request.epoch
                            + " epochBefore=" + epoch + " epochAfter=" + afterEpoch
                            + " pendingAgeMs=" + (began - request.startedAt) + " lifetimeMs=" + lifetime
                            + " scopeUnavailable=" + scopeUnavailable + " scopeGeneration=" + scope.generation
                            + " requestExpired=" + (began - request.startedAt >= lifetime)
                            + " sampleBracketMs=" + (ended - began) + " workerCandidates=" + candidates
                            + " workerState=" + (worker == null ? "ABSENT" : worker.getState())
                            + " workerStack=" + workerStack + " mainState=" + mainThread.getState() + " mainStack=" + mainStack);
                }
            } catch (RuntimeException | AssertionError failure) {
                append("tMs=" + (SystemClock.elapsedRealtime() - started) + " event=SAMPLER_ERROR type=" + failure.getClass().getSimpleName());
            }
        }

        private String stack(Thread thread) {
            StringBuilder value = new StringBuilder();
            int count = 0;
            for (StackTraceElement frame : thread.getStackTrace()) {
                if (count++ == 12) break;
                if (value.length() > 0) value.append('>');
                value.append(frame.getClassName()).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber());
            }
            return value.toString();
        }

        private void poll() {
            if (closed || frozen) return;
            long began = SystemClock.elapsedRealtime();
            try {
                Object overlay = currentOverlay();
                View panel = overlay == null ? null : (View) field(AssistOverlay.class, overlay, "panel");
                if (watched != panel) {
                    if (watched != null) watched.removeOnAttachStateChangeListener(listener);
                    watched = panel;
                    if (watched != null) watched.addOnAttachStateChangeListener(listener);
                    mark("WATCH", false);
                }
                ChatGateService.Snapshot gate = ChatGateService.snapshot();
                int window = overlay == null ? -1 : ((AssistOverlay) overlay).windowId();
                String next = gate.generation + ":" + gate.wechat + ":" + gate.valid() + ":"
                        + System.identityHashCode(panel) + ":" + (panel != null && panel.isAttachedToWindow()) + ":" + window;
                if (!next.equals(signature)) {
                    signature = next;
                    mark("STATE", false);
                }
            } catch (RuntimeException | AssertionError failure) {
                append("tMs=" + (SystemClock.elapsedRealtime() - started) + " event=OBSERVER_ERROR type=" + failure.getClass().getSimpleName());
            }
            polls++;
            maximumPollMillis = Math.max(maximumPollMillis, SystemClock.elapsedRealtime() - began);
            handler.postDelayed(refresh, 100);
        }

        void mark(String event, boolean stack) {
            if (closed || frozen) return;
            try { record(event, stack); }
            catch (RuntimeException | AssertionError failure) {
                append("tMs=" + (SystemClock.elapsedRealtime() - started) + " event=OBSERVER_ERROR type=" + failure.getClass().getSimpleName());
            }
        }

        private void record(String event, boolean stack) {
            long now = SystemClock.elapsedRealtime();
            Object gateService = field(ChatGateService.class, null, "connected");
            Object overlay = gateService == null ? null : field(ChatGateService.class, gateService, "overlay");
            ChatGateService.Snapshot gate = ChatGateService.snapshot();
            String leaseState = " epoch=-1 pendingId=0 acceptedId=0";
            int ownWindow = overlay == null ? -1 : ((AssistOverlay) overlay).windowId();
            OwnWindowIds registered = gateService == null ? null : (OwnWindowIds) field(ChatGateService.class, gateService, "ownedWindows");
            if (gateService != null) {
                ScopeLease lease = (ScopeLease) field(ChatGateService.class, gateService, "lease");
                synchronized (lease) {
                    ScopeLease.Request pending = (ScopeLease.Request) field(ScopeLease.class, lease, "pending");
                    ScopeLease.Certificate accepted = (ScopeLease.Certificate) field(ScopeLease.class, lease, "accepted");
                    leaseState = " epoch=" + field(ScopeLease.class, lease, "epoch")
                            + " pendingId=" + (pending == null ? 0 : pending.identifier)
                            + " pendingEpoch=" + (pending == null ? -1 : pending.epoch)
                            + " pendingAgeMs=" + (pending == null ? -1 : now - pending.startedAt)
                            + " acceptedId=" + (accepted == null ? 0 : accepted.identifier)
                            + " acceptedEpoch=" + (accepted == null ? -1 : accepted.epoch)
                            + " acceptedAgeMs=" + (accepted == null ? -1 : now - accepted.startedAt);
                }
            }
            StringBuilder cause = new StringBuilder();
            if (stack) {
                for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                    String name = frame.getClassName();
                    if (name.startsWith("com.codextemp.wechatcaptureprobe.") && !name.contains("OverlayTouchProbe")) {
                        if (cause.length() > 0) cause.append('>');
                        cause.append(name.substring(name.lastIndexOf('.') + 1)).append('.').append(frame.getMethodName());
                        if (cause.length() > 220) break;
                    }
                }
            }
            append("tMs=" + (now - started) + " event=" + event + " generation=" + gate.generation
                    + " wechat=" + gate.wechat + " gateValid=" + gate.valid() + " checkedAgeMs=" + (now - gate.checkedAt)
                    + " viewAttached=" + (watched != null && watched.isAttachedToWindow())
                    + " overlayAttached=" + (overlay != null && Boolean.TRUE.equals(field(AssistOverlay.class, overlay, "attached")))
                    + " ownWindow=" + ownWindow
                    + " ownKnown=" + (registered != null && registered.contains(ownWindow))
                    + " retainedOwnIds=" + (registered == null ? 0 : registered.size())
                    + " lastOwnedWindow=" + (gateService == null ? -1 : field(ChatGateService.class, gateService, "lastOwnedOverlay"))
                    + " sourceWindow=" + (gateService == null ? -1 : field(ChatGateService.class, gateService, "lastWindow"))
                    + leaseState + (cause.length() == 0 ? "" : " cause=" + cause));
        }

        private synchronized void append(String record) {
            if (closed || frozen) return;
            if (records.size() == 128) { records.removeFirst(); discarded++; }
            records.addLast(record);
        }

        synchronized void freeze(String reason) {
            if (frozen) return;
            mark(reason, false);
            frozen = true;
            handler.removeCallbacks(refresh);
            sampler.shutdownNow();
            long observed = SystemClock.elapsedRealtime();
            List<ScopeQueryTrace.Entry> entries = queryTrace.snapshot();
            queryRecords.add("queryTimings=" + entries.size() + " observedOffsetMs=" + (observed - started)
                    + " partialEntriesPossible=true");
            for (ScopeQueryTrace.Entry entry : entries) {
                queryRecords.add("event=QUERY_TIMING id=" + entry.identifier + " epoch=" + entry.epoch
                        + " startOffsetMs=" + (entry.startedAt - started) + " outcome=" + entry.outcome
                        + " workerQueueMs=" + duration(entry.startedAt, entry.workerStartedAt)
                        + " rootMs=" + duration(entry.rootStartedAt, entry.rootFinishedAt)
                        + " windowsMs=" + duration(entry.rootFinishedAt, entry.windowsFinishedAt)
                        + " occlusionsMs=" + duration(entry.windowsFinishedAt, entry.occlusionsFinishedAt)
                        + " geometryMs=" + duration(entry.geometryStartedAt, entry.geometryFinishedAt)
                        + " acceptGeometryMs=" + duration(entry.acceptStartedAt, entry.acceptGeometryFinishedAt)
                        + " workerMs=" + duration(entry.workerStartedAt, entry.workerFinishedAt)
                        + " mainQueueMs=" + duration(entry.workerFinishedAt, entry.acceptStartedAt)
                        + " validationMs=" + duration(entry.acceptStartedAt, entry.validatedAt)
                        + " validatedAgeMs=" + duration(entry.startedAt, entry.validatedAt)
                        + " sceneMs=" + duration(entry.sceneStartedAt, entry.sceneFinishedAt)
                        + " renderDisposition=" + entry.renderDisposition
                        + " renderQueueMs=" + duration(entry.renderQueuedAt, entry.renderStartedAt)
                        + " renderMs=" + duration(entry.renderStartedAt, entry.renderFinishedAt)
                        + " acceptMs=" + duration(entry.acceptStartedAt, entry.finishedAt)
                        + " totalMs=" + duration(entry.startedAt, entry.finishedAt));
            }
        }

        private long duration(long beginning, long end) {
            return beginning < 0 || end < beginning ? -1 : end - beginning;
        }

        List<String> snapshot(String reason) {
            freeze(reason);
            List<String> result = new ArrayList<>();
            synchronized (this) {
                result.add("journalRecords=" + records.size() + " discarded=" + discarded + " polls=" + polls
                        + " pollMaxMs=" + maximumPollMillis + " querySamples=" + querySamples
                        + " sampleMaxMs=" + maximumSampleMillis + " frozen=" + frozen);
                result.addAll(records);
                result.addAll(queryRecords);
            }
            return result;
        }

        @Override public void close() {
            closed = true;
            queryTrace.reset();
            handler.removeCallbacksAndMessages(null);
            sampler.shutdownNow();
            if (watched != null) watched.removeOnAttachStateChangeListener(listener);
            watched = null;
            synchronized (this) { records.clear(); }
        }
    }

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        verifyQuickClipboard = "quick".equals(arguments.getString("mode", "touch"));
        visualFixedOpen = "visualfixed".equals(arguments.getString("mode", "touch"));
        visualNavigationOnly = visualFixedOpen || "visualnavigation".equals(arguments.getString("mode", "touch"));
        navigationOnly = "navigation".equals(arguments.getString("mode", "touch"));
        demoMode = "demo".equals(arguments.getString("mode", "touch"));
        quickPerf = "quickperf".equals(arguments.getString("mode", "touch"));
        quickKeyboard = "quickkeyboard".equals(arguments.getString("mode", "touch"));
        quickLifecycle = "quicklifecycle".equals(arguments.getString("mode", "touch"));
        quickDetailRecovery = "quickdetailrecovery".equals(arguments.getString("mode", "touch"));
        quickHeaderStop = "quickheaderstop".equals(arguments.getString("mode", "touch"));
        quickEndurance = "quickendurance".equals(arguments.getString("mode", "touch"));
        quickSwitch = "quickswitch".equals(arguments.getString("mode", "touch"));
        quickMode = quickDetailRecovery || quickHeaderStop || quickSwitch || quickEndurance || quickPerf || quickKeyboard || quickLifecycle || "quick".equals(arguments.getString("mode", "touch"));
        expandedMode = quickMode || demoMode || "expanded".equals(arguments.getString("mode", "touch"));
        keyboardMode = "keyboard".equals(arguments.getString("mode", "touch"));
        enduranceMode = "endurance".equals(arguments.getString("mode", "touch"));
        enduranceSeconds = Integer.parseInt(arguments.getString("endurance_seconds", "900"));
        holdFailure = Boolean.parseBoolean(arguments.getString("hold_failure", "false"));
        traceWindows = Boolean.parseBoolean(arguments.getString("trace_windows", "false"));
        traceBounds = Boolean.parseBoolean(arguments.getString("trace_bounds", "false"));
        operateSystemUi = Boolean.parseBoolean(arguments.getString("system_ui", "false"));
        verifyColdState = Boolean.parseBoolean(arguments.getString("verify_cold_state", "false"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        if (visualNavigationOnly) {
            visualNavigationObservation(result);
            return;
        }
        if (navigationOnly) {
            new NormalGrantUi(this, null, ignored -> { }).navigationObservation("read-only",
                    getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES));
            result.putString("result", "NAVIGATION_METADATA_ONLY actions=0 rawText=0");
            finish(-1, result);
            return;
        }
        try {
            ModelSettingsStore settings = new ModelSettingsStore(getTargetContext());
            require(!settings.hasKey() && !settings.cloudAllowed() && !settings.partialAnalysisAllowed()
                    && !AssistantCaptureService.running() && !CaptureService.running(), "no-cloud-or-capture-precondition");
            // 必须在测试清理/页面启动前观察；是否真正更换进程由外部驱动另行证明。
            if (verifyColdState) onMain(() -> {
                require(AssistantState.title.isEmpty() && AssistantState.token.isEmpty()
                        && AssistantState.messages.isEmpty() && AssistantState.frames == 0,
                        "cold-state-has-no-old-recognition");
                require(AssistantState.analysis == null && AssistantState.analysisSource == null
                        && !AnalysisCoordinator.demoEnabled(), "cold-state-has-no-old-suggestion");
                require(AssistantState.context.sessionToken.isEmpty() && AssistantState.context.memory == null
                        && !AssistantState.context.shareMemory && !AssistantState.paused && !AssistantState.expanded,
                        "cold-state-has-no-old-context-or-panel");
                coldStateVerified = true;
                return null;
            });
            if (enduranceMode || quickEndurance) require(enduranceSeconds >= 60 && enduranceSeconds <= 1800, "bounded-endurance-duration");
            fixtureActive = true;
            position = getTargetContext().getSharedPreferences("overlay_layout", 0);
            originalPosition = new HashMap<>(position.getAll());
            IntentFilter filter = new IntentFilter(DONE);
            filter.addAction(INSPECTED);
            filter.addAction(OBSERVE);
            if (Build.VERSION.SDK_INT >= 33) getTargetContext().registerReceiver(observer, filter, Context.RECEIVER_EXPORTED);
            else getTargetContext().registerReceiver(observer, filter);
            registered = true;
            // 先确认外部前台启动完成，再创建测试持有的页面，避免两个CLEAR_TASK互相销毁。
            long startupStep = ++step;
            Bundle startup = new Bundle();
            startup.putString("phase", "WAITING_FOR_FIXTURE_ACTIVITY");
            startup.putLong("step", startupStep);
            startup.putInt("parentPid", android.os.Process.myPid());
            sendStatus(1, startup);
            await(() -> completedStep == startupStep, 15000, "foreground-start-ack-before-fixture");
            long startupStarted = SystemClock.elapsedRealtime();
            assistant = startActivitySync(new Intent(getTargetContext(), AssistantActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            if (verifyQuickClipboard) {
                await(() -> onMain(() -> assistant.hasWindowFocus()), 5000, "clipboard-sentinel-own-page-focused");
                onMain(() -> {
                    require(assistant.hasWindowFocus(), "clipboard-sentinel-focus-current");
                    // 不读取或备份用户原剪贴板；唯一固定标记防止旧示例造成假阳性。
                    android.content.ClipboardManager clipboard = getTargetContext().getSystemService(android.content.ClipboardManager.class);
                    String sentinel = "SYNTHETIC_CLIPBOARD_" + SystemClock.elapsedRealtime();
                    clipboardSentinel = sentinel;
                    clipboardCleanupRequired = true;
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("固定复制校验", sentinel));
                    android.content.ClipData actual = clipboard.getPrimaryClip();
                    require(actual != null && actual.getItemCount() == 1
                            && sentinel.contentEquals(actual.getItemAt(0).getText() == null ? "" : actual.getItemAt(0).getText()),
                            "clipboard-sentinel-write-confirmed");
                    return null;
                });
            }
            if (operateSystemUi) {
                new NormalGrantUi(this, assistant, this::phase).start();
                phase("OPEN_WECHAT_FOR_FIXED_CHAT");
                if (expandedMode) new NormalGrantUi(this, assistant, this::phase).openFixedChatFromList();
            }
            phase("WAITING_FOR_NORMAL_GRANT_AND_FIXED_CHAT");
            await(() -> AssistantCaptureService.running() && (quickMode ? AssistantState.matches(AssistantState.snapshot())
                    && ChatGateService.snapshot().valid() && codePositions().size() >= 2 : demoMode ? AssistantState.matches(AssistantState.snapshot())
                    && ChatGateService.snapshot().valid() : fixedVisible()) && layout() != null,
                    demoMode || quickMode ? 45000 : 240000, demoMode ? "normal-grant-and-real-ocr" : "normal-grant-and-real-fixed-chat");
            startupReadyMs = SystemClock.elapsedRealtime() - startupStarted;
            onMain(() -> {
                observedMessages = AssistantState.messages.size();
                Object service = field(AssistantCaptureService.class, null, "instance");
                owner = (ProjectionSession) field(AssistantCaptureService.class, service, "projectionSession");
                require(owner != null && owner.ready(), "real-projection-owner-ready");
                Object gateService = field(ChatGateService.class, null, "connected");
                OwnWindowIds registered = (OwnWindowIds) field(ChatGateService.class, gateService, "ownedWindows");
                int ownWindow = ((AssistOverlay) currentOverlay()).windowId();
                require(ownWindow >= 0 && registered.contains(ownWindow), "actual-owned-window-registered-before-interactions");
                if (traceWindows) { windowJournal = new WindowJournal(); recoveryJournal = new RecoveryJournal(); }
                return null;
            });
            boolean initialRight = layout().right;
            if (quickDetailRecovery) quickDetailRecoveryInteraction();
            else if (quickHeaderStop) quickHeaderStopInteraction();
            else if (quickSwitch) quickSwitchInteraction();
            else if (quickEndurance) quickEnduranceInteraction();
            else if (quickPerf) quickPerformance();
            else if (quickKeyboard) quickKeyboardInteraction();
            else if (quickLifecycle) quickLifecycleInteraction();
            else if (quickMode) quickInteraction();
            else if (demoMode) demoInteraction();
            else if (expandedMode) expandedReading();
            else if (keyboardMode) keyboardInteraction();
            else if (enduranceMode) endurance(initialRight, settings);
            else {
                round(!initialRight);
                round(initialRight);
            }
            Layout finalLayout = layout();
            if (!quickMode) gesture("OPEN_STOP", finalLayout.ball.centerX(), finalLayout.ball.centerY(), 0, 0, false);
            await(() -> AssistantState.expanded && layout() != null, 3000, "final-card-open");
            Layout stopLayout = layout();
            require(!stopLayout.stop.isEmpty(), "actual-stop-button-visible");
            if (quickHeaderStop) require(onMain(() -> headerStopHold.pendingImage()), "pending-header-at-stop");
            gesture("STOP", stopLayout.stop.centerX(), stopLayout.stop.centerY(), 0, 0, false);
            await(() -> !AssistantCaptureService.running() && ProjectionSession.available(), 10000, "normal-stop-clears-owner");
            if (quickHeaderStop) {
                require(onMain(() -> headerStopHold.destroyedCleanly()), "pending-header-image-reclaimed-by-destroy");
                await(() -> headerStopHold.terminated(), 3000, "header-worker-terminated-without-release");
                enduranceSummary = " headerStopPending=true pendingImageRecycled=true workerTerminated=true blockerInterrupted=true injectedQueueBlock=true";
            }
            onMain(() -> {
                require(((Integer) field(ProjectionSession.class, owner, "stopReason")) == 1
                        && Boolean.TRUE.equals(field(ProjectionSession.class, owner, "resourcesReleased")), "real-stop-callback-and-release");
                require(AssistantState.messages.isEmpty() && AssistantState.token.isEmpty()
                        && AssistantState.analysis == null && !AnalysisCoordinator.demoEnabled() && layout() == null, "private-state-and-overlay-cleared-before-inspection");
                return null;
            });
            phase("HOLD_STOPPED_FOR_EXTERNAL_INSPECTION");
            await(() -> inspected, 60000, "independent-live-parent-cleanup-inspection");
            if (verifyQuickClipboard) {
                long clipboardStep = ++step;
                Bundle clipboardStatus = new Bundle();
                clipboardStatus.putString("phase", "VERIFY_CLIPBOARD_HOME");
                clipboardStatus.putLong("step", clipboardStep);
                sendStatus(1, clipboardStatus);
                await(() -> completedStep == clipboardStep && onMain(() -> assistant.hasWindowFocus()),
                        10000, "clipboard-own-page-focused-after-copy");
                onMain(() -> {
                    require(assistant.hasWindowFocus(), "clipboard-result-focus-current");
                    android.content.ClipboardManager clipboard = getTargetContext().getSystemService(android.content.ClipboardManager.class);
                    android.content.ClipData clip = clipboard.getPrimaryClip();
                    require(expectedClipboard != null && clip != null && clip.getItemCount() == 1
                            && expectedClipboard.contentEquals(clip.getItemAt(0).getText() == null ? "" : clip.getItemAt(0).getText()),
                            "clipboard-matches-selected-fixed-suggestion");
                    require(clip.getDescription().getExtras() != null
                            && clip.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"),
                            "clipboard-sensitive-marker-preserved");
                    clipboard.clearPrimaryClip();
                    require(!clipboard.hasPrimaryClip(), "owned-clipboard-empty-after-clear");
                    clipboardCleanupRequired = false;
                    clipboardSentinel = null;
                    expectedClipboard = null;
                    return null;
                });
                enduranceSummary += " clipboardContentVerified=true ownedTestClipCleared=true";
            }
            require(!settings.hasKey() && !settings.cloudAllowed() && AssistantState.analysis == null, "no-real-or-fake-model");
            journal("COMPLETE");
            result.putString("result", "PASS overlayTouchChecks=" + checks + " gestureActions=" + gestureActions
                    + " outsideChanges=" + outsideChanges + " insideScrolls=" + insideScrolls
                    + " observedVisibleMessages=" + observedMessages + " parentPid=" + android.os.Process.myPid()
                    + " mode=" + (quickMode ? "quick" : demoMode ? "demo" : expandedMode ? "expanded" : keyboardMode ? "keyboard" : enduranceMode ? "endurance" : "touch")
                    + (keyboardMode ? " keyboardShownTop=" + keyboardShownTop : "")
                    + enduranceSummary
                    + " coldStateVerified=" + coldStateVerified + " startupReadyMs=" + startupReadyMs
                    + " observedStopReason=1 resourcesReleased=true modelRequests=0 wechatTextActions=0 rawChatFiles=0");
        } catch (Throwable failure) {
            journal("TEST_FAILURE");
            result.putString("result", "FAIL overlayTouchChecks=" + checks + " gestureActions=" + gestureActions
                    + " outsideChanges=" + outsideChanges + " insideScrolls=" + insideScrolls
                    + " type=" + failure.getClass().getSimpleName() + " check=" + failure.getMessage() + " " + diagnostic());
            if (holdFailure && fixtureActive && AssistantCaptureService.running()) {
                Bundle observation = new Bundle();
                observation.putString("phase", "HOLD_FAILURE_FOR_INSPECTION");
                observation.putInt("parentPid", android.os.Process.myPid());
                observation.putString("diagnostic", diagnostic());
                sendStatus(1, observation);
                long until = SystemClock.elapsedRealtime() + 90000;
                while (!inspected && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
            }
        } finally {
            if (queueMeter != null) queueMeter.close();
            if(boundaryReasons!=null&&!boundaryReasonsReported)reportBoundaryReasons();
            if (clipboardCleanupRequired && assistant != null) {
                String cleanup = "UNCONFIRMED";
                try {
                    onMain(() -> { CaptureControl.stopAll(getTargetContext()); return null; });
                    long cleanupStep = ++step;
                    Bundle request = new Bundle(); request.putString("phase", "VERIFY_CLIPBOARD_HOME");
                    request.putLong("step", cleanupStep); sendStatus(1, request);
                    await(() -> completedStep == cleanupStep && onMain(() -> assistant.hasWindowFocus()),
                            10000, "clipboard-cleanup-own-page-focused");
                    cleanup = onMain(() -> {
                        require(assistant.hasWindowFocus(), "clipboard-cleanup-focus-current");
                        android.content.ClipboardManager clipboard = getTargetContext().getSystemService(android.content.ClipboardManager.class);
                        android.content.ClipData clip = clipboard.getPrimaryClip();
                        CharSequence text = clip != null && clip.getItemCount() == 1 ? clip.getItemAt(0).getText() : null;
                        boolean owned = text != null && (clipboardSentinel != null && clipboardSentinel.contentEquals(text)
                                || expectedClipboard != null && expectedClipboard.contentEquals(text));
                        if (owned) {
                            clipboard.clearPrimaryClip();
                            require(!clipboard.hasPrimaryClip(), "owned-cleanup-clipboard-empty-after-clear");
                        }
                        clipboardCleanupRequired = false; clipboardSentinel = null; expectedClipboard = null;
                        return owned ? "OWNED_CLEARED" : clip == null ? "EMPTY" : "CHANGED_PRESERVED";
                    });
                } catch (Throwable ignored) { /* 不能恢复前台时明确报告，禁止后台盲清剪贴板。 */ }
                Bundle cleanupStatus = new Bundle(); cleanupStatus.putString("clipboardCleanup", cleanup);
                sendStatus(2, cleanupStatus);
            }
            if (registered) getTargetContext().unregisterReceiver(observer);
            onMain(() -> {
                if (windowJournal != null) windowJournal.close();
                if (boundaryReasons != null) boundaryReasons.close();
                if (recoveryJournal != null) recoveryJournal.close();
                if (fixtureActive) CaptureControl.stopAll(getTargetContext());
                if (headerStopHold != null) headerStopHold.close();
                if (assistant != null) assistant.finish();
                if (main != null) main.finish();
                return null;
            });
            if (fixtureActive && position != null && originalPosition != null && !restorePosition()) {
                result.putString("result", "FAIL overlayTouchChecks=" + checks + " check=original-position-not-restored");
            }
        }
        finish(result.getString("result").startsWith("PASS") ? -1 : 0, result);
    }

    /** 标准截图的定位诊断或单次固定样本文字点击；未知来源拒绝，图片仅留内存。 */
    private void visualNavigationObservation(Bundle result) {
        android.graphics.Bitmap bitmap = null;
        android.graphics.Bitmap verificationFrame = null;
        com.google.mlkit.vision.text.TextRecognizer reader = null;
        boolean downSent = false, tapCompleted = false;
        try {
            require(!AssistantCaptureService.running() && !CaptureService.running(), "navigation-no-other-ocr");
            android.app.UiAutomation automation = getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            android.accessibilityservice.AccessibilityServiceInfo automationInfo = automation.getServiceInfo();
            require(automationInfo != null, "navigation-own-automation-info");
            automationInfo.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(automationInfo);
            android.view.accessibility.AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            require(root != null, "navigation-root-present");
            require(android.os.Build.VERSION.SDK_INT >= 30, "navigation-display-metadata-required");
            android.view.Display display = getTargetContext().getSystemService(android.view.WindowManager.class).getDefaultDisplay();
            require(display.getDisplayId() == android.view.Display.DEFAULT_DISPLAY, "navigation-default-display-required");
            android.graphics.Point displaySize = new android.graphics.Point();
            display.getRealSize(displaySize);
            int rotation = display.getRotation();
            int window;
            try {
                require("com.tencent.mm".contentEquals(root.getPackageName() == null ? "" : root.getPackageName()),
                        "navigation-wechat-source");
                window = root.getWindowId();
            } finally { root.recycle(); }
            bitmap = automation.takeScreenshot();
            require(bitmap != null, "navigation-standard-screenshot-unavailable");
            final android.graphics.Bitmap screen = bitmap;
            require(screen.getWidth() == displaySize.x && screen.getHeight() == displaySize.y,
                    "navigation-screenshot-display-coordinates");
            require(!FrameQuality.isMostlyBlack(screen.getWidth(), screen.getHeight(), screen::getPixel),
                    "navigation-protected-or-black-screenshot");
            reader = com.google.mlkit.vision.text.TextRecognition.getClient(
                    new com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build());
            com.google.android.gms.tasks.Task<com.google.mlkit.vision.text.Text> task =
                    reader.process(com.google.mlkit.vision.common.InputImage.fromBitmap(screen, 0));
            com.google.mlkit.vision.text.Text text;
            boolean interrupted = false;
            // 中断不能提前回收仍由真实SDK使用的图片；等待任务结束后恢复中断标记。
            try {
                for (;;) {
                    try { text = com.google.android.gms.tasks.Tasks.await(task); break; }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
            require(!Thread.currentThread().isInterrupted(), "navigation-cancelled-after-ocr");
            root = automation.getRootInActiveWindow();
            require(root != null, "navigation-source-after-ocr");
            try { require(root.getWindowId() == window && "com.tencent.mm".contentEquals(
                    root.getPackageName() == null ? "" : root.getPackageName()), "navigation-same-source-after-ocr"); }
            finally { root.recycle(); }
            int flags = 0;
            String observed = ConversationSession.normalize(text.getText());
            if (observed.contains("通讯录")) flags |= 1;
            if (observed.contains("发现")) flags |= 2;
            if (observed.contains("文件传输助手")) flags |= 4;
            if (ConversationSamples.matchedCodes(observed).contains("T08")) flags |= 8;
            int bottomTabs = 0, fixedMatches = 0, navigationMask = 0;
            int tabMinY = Integer.MAX_VALUE, tabMaxY = Integer.MIN_VALUE, tabHeight = 0;
            Rect fixed = null;
            Rect tabLabels = new Rect();
            for (com.google.mlkit.vision.text.Text.TextBlock block : text.getTextBlocks()) {
                for (com.google.mlkit.vision.text.Text.Line line : block.getLines()) {
                    Rect bounds = line.getBoundingBox();
                    if (bounds == null || bounds.isEmpty() || bounds.left < 0 || bounds.top < 0
                            || bounds.right > screen.getWidth() || bounds.bottom > screen.getHeight()) continue;
                    if (bounds.top >= screen.getHeight() * 85 / 100) {
                        String label = ConversationSession.normalize(line.getText());
                        if (label.contains("通讯录")) { bottomTabs |= 1; tabLabels.union(bounds); }
                        if (label.contains("发现")) { bottomTabs |= 2; tabLabels.union(bounds); }
                        String[] names = {"微信", "通讯录", "发现", "我"};
                        for (int i = 0; i < names.length; i++) {
                            int center = bounds.centerX() * 100 / screen.getWidth();
                            if (label.equals(names[i]) && center >= 5 + i * 25 && center <= 20 + i * 25
                                    && bounds.bottom <= screen.getHeight() * 96 / 100) {
                                navigationMask |= 1 << i;
                                tabMinY = Math.min(tabMinY, bounds.centerY());
                                tabMaxY = Math.max(tabMaxY, bounds.centerY());
                                tabHeight = Math.max(tabHeight, bounds.height());
                                tabLabels.union(bounds);
                            }
                        }
                    }
                    if (bounds.top >= screen.getHeight() * 8 / 100 && bounds.bottom < screen.getHeight() * 80 / 100
                            && ConversationSamples.matchedCodes(line.getText()).contains("T08")) {
                        fixedMatches++;
                        fixed = new Rect(bounds);
                    }
                }
            }
            long verificationStarted = SystemClock.elapsedRealtime();
            verificationFrame = automation.takeScreenshot();
            boolean samePixels = false, sourceWindowFound = false, obstructed = true;
            int windowCount = 0;
            boolean sameGeometry = verificationFrame != null && verificationFrame.getWidth() == screen.getWidth()
                    && verificationFrame.getHeight() == screen.getHeight();
            if (sameGeometry && fixedMatches == 1 && fixed != null) {
                Rect row = new Rect(0, Math.max(0, fixed.top - 24), screen.getWidth(), Math.min(screen.getHeight(), fixed.bottom + 24));
                Rect tabs = new Rect(0, screen.getHeight() * 85 / 100, screen.getWidth(), screen.getHeight());
                samePixels = sameNavigationPixels(screen, verificationFrame, row)
                        && sameNavigationPixels(screen, verificationFrame, tabs);
                java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows = automation.getWindows();
                windowCount = windows == null ? 0 : windows.size();
                obstructed = windows == null || windows.isEmpty();
                if (windows != null) for (android.view.accessibility.AccessibilityWindowInfo info : windows) {
                    try {
                        Rect bounds = new Rect(); info.getBoundsInScreen(bounds);
                        if (info.getId() == window) {
                            sourceWindowFound = info.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
                                    && info.getDisplayId() == android.view.Display.DEFAULT_DISPLAY
                                    && info.isActive() && bounds.contains(fixed) && !tabLabels.isEmpty() && bounds.contains(tabLabels);
                        } else if (bounds.isEmpty() || Rect.intersects(bounds, fixed)
                                || Rect.intersects(bounds, tabLabels)) obstructed = true;
                    } finally { info.recycle(); }
                }
            }
            root = automation.getRootInActiveWindow();
            boolean finalSource = false;
            if (root != null) try { finalSource = root.getWindowId() == window && "com.tencent.mm".contentEquals(
                    root.getPackageName() == null ? "" : root.getPackageName()); }
            finally { root.recycle(); }
            long proofAge = SystemClock.elapsedRealtime() - verificationStarted;
            boolean navigationAligned = Integer.bitCount(navigationMask) >= 3 && tabMaxY - tabMinY <= tabHeight;
            boolean candidate = navigationAligned && fixedMatches == 1 && samePixels
                    && sourceWindowFound && !obstructed && finalSource && proofAge < 200;
            if (visualFixedOpen) {
                require(candidate, "navigation-anchor-not-confirmed");
                require(!AssistantCaptureService.running() && !CaptureService.running(), "navigation-capture-started-before-input");
                android.graphics.Point currentSize = new android.graphics.Point(); display.getRealSize(currentSize);
                require(currentSize.equals(displaySize), "navigation-display-size-changed");
                require(getTargetContext().getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRotation() == rotation,
                        "navigation-rotation-changed");
                require(navigationWindowsClear(automation, window, fixed, tabLabels), "navigation-final-windows-uncertain");
                root = automation.getRootInActiveWindow();
                require(root != null, "navigation-final-root-missing");
                try { require(root.getWindowId() == window && "com.tencent.mm".contentEquals(
                        root.getPackageName() == null ? "" : root.getPackageName()), "navigation-final-source-changed"); }
                finally { root.recycle(); }
                long downTime = SystemClock.uptimeMillis();
                android.view.MotionEvent down = android.view.MotionEvent.obtain(downTime, downTime,
                        android.view.MotionEvent.ACTION_DOWN, fixed.centerX(), fixed.centerY(), 0);
                android.view.MotionEvent up = android.view.MotionEvent.obtain(downTime, downTime + 60,
                        android.view.MotionEvent.ACTION_UP, fixed.centerX(), fixed.centerY(), 0);
                down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                try {
                    require(!Thread.currentThread().isInterrupted(), "navigation-cancelled-before-input");
                    require(SystemClock.elapsedRealtime() - verificationStarted < 200, "navigation-proof-expired-before-input");
                    downSent = automation.injectInputEvent(down, true);
                    require(downSent, "navigation-touch-down-rejected");
                    tapCompleted = automation.injectInputEvent(up, true);
                    require(tapCompleted, "navigation-touch-up-rejected");
                } finally {
                    try {
                        if (downSent && !tapCompleted) {
                            android.view.MotionEvent cancel = android.view.MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                                    android.view.MotionEvent.ACTION_CANCEL, fixed.centerX(), fixed.centerY(), 0);
                            cancel.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                            try { automation.injectInputEvent(cancel, true); } finally { cancel.recycle(); }
                        }
                    } finally { down.recycle(); up.recycle(); }
                }
            }
            result.putString("result", "VISUAL_NAVIGATION_METADATA_ONLY knownLabelFlags=" + flags
                    + " width=" + screen.getWidth() + " height=" + screen.getHeight()
                    + " bottomTabs=" + bottomTabs + " navigationMask=" + navigationMask + " navigationAligned=" + navigationAligned
                    + " fixedMatches=" + fixedMatches + " samePixels=" + samePixels
                    + " sourceWindow=" + sourceWindowFound + " windowCount=" + windowCount
                    + " obstructed=" + obstructed + " proofAgeMs=" + proofAge
                    + " anchorVerified=" + candidate + (fixedMatches == 1 ? " fixedBounds=" + fixed.toShortString() : "")
                    + " touchDown=" + downSent + " touchCompleted=" + tapCompleted + " destinationVerified=false rawText=0 imageFiles=0");
        } catch (Throwable failure) {
            result.putString("result", "VISUAL_NAVIGATION_UNCONFIRMED type=" + failure.getClass().getSimpleName()
                    + " touchDown=" + downSent + " touchCompleted=" + tapCompleted + " destinationVerified=false rawText=0 imageFiles=0");
        } finally {
            if (reader != null) reader.close();
            if (bitmap != null) bitmap.recycle();
            if (verificationFrame != null) verificationFrame.recycle();
        }
        finish(-1, result);
    }

    private boolean navigationWindowsClear(android.app.UiAutomation automation, int source, Rect target, Rect tabs) {
        java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows = automation.getWindows();
        if (windows == null || windows.isEmpty()) return false;
        boolean found = false, blocked = false;
        for (android.view.accessibility.AccessibilityWindowInfo info : windows) {
            try {
                Rect bounds = new Rect(); info.getBoundsInScreen(bounds);
                if (info.getId() == source) found = info.isActive()
                        && info.getDisplayId() == android.view.Display.DEFAULT_DISPLAY
                        && info.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
                        && bounds.contains(target) && bounds.contains(tabs);
                else if (bounds.isEmpty() || Rect.intersects(bounds, target) || Rect.intersects(bounds, tabs)) blocked = true;
            } finally { info.recycle(); }
        }
        return found && !blocked;
    }

    /** 比较本次两张标准截图的目标行及导航栏，绝不把旧图片时间改成新时间。 */
    private boolean sameNavigationPixels(android.graphics.Bitmap before, android.graphics.Bitmap after, Rect region) {
        int[] left = new int[region.width()], right = new int[region.width()];
        for (int y = region.top; y < region.bottom; y++) {
            before.getPixels(left, 0, region.width(), region.left, y, region.width(), 1);
            after.getPixels(right, 0, region.width(), region.left, y, region.width(), 1);
            if (!java.util.Arrays.equals(left, right)) return false;
        }
        return true;
    }

    /** 使用真实既有固定样本；只输出数值，不替换状态、Provider或聊天内容。 */
    private void expandedReading() {
        Layout beforePosition = layout();
        long positionFrames = AssistantState.frames;
        gesture("DRAG_" + (beforePosition.right ? "RIGHT" : "LEFT"), beforePosition.ball.centerX(), beforePosition.ball.centerY(),
                beforePosition.ball.centerX(), ChatGateService.snapshot().viewport.bounds.top + dp(100), true);
        await(() -> !AssistantState.expanded && fixedVisible() && AssistantState.frames > positionFrames,
                20000, "fresh-fixed-chat-after-position-for-visible-controls");
        Layout collapsed = layout();
        long before = AssistantState.frames;
        long opened = SystemClock.elapsedRealtime();
        gesture("EXPANDED_OPEN", collapsed.ball.centerX(), collapsed.ball.centerY(), 0, 0, false);
        await(() -> AssistantState.expanded && layout() != null, 3500, "expanded-real-touch");
        await(() -> AssistantState.expanded && AssistantState.frames > before && !AssistantState.messages.isEmpty()
                && AssistantState.coverage.obscured() && ChatGateService.snapshot().valid(), 20000, "fresh-body-published-while-expanded");
        await(() -> onMain(() -> {
            if (AssistantState.messages.isEmpty() || currentOverlay() == null) return false;
            String text = ((android.widget.TextView) field(AssistOverlay.class, currentOverlay(), "details")).getText().toString();
            String last = AssistantState.messages.get(AssistantState.messages.size()-1).text;
            return text.contains("最近识别") && text.contains(last.substring(0, Math.min(last.length(), 150)));
        }), 2000, "fresh-preview-rendered-after-publish");
        long first = SystemClock.elapsedRealtime() - opened;
        onMain(() -> {
            Object overlay = currentOverlay();
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) field(AssistOverlay.class, overlay, "params");
            require((params.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0, "secure-before-capture");
            String details = ((android.widget.TextView) field(AssistOverlay.class, overlay, "details")).getText().toString();
            require(details.contains("最近识别"), "preview-visible-without-more-settings");
            return null;
        });
        Map<String,Integer> anchors = codePositions();
        require(anchors.size() >= 2, "expanded-fixed-message-anchors");
        Layout openedLayout = layout();
        Rect strip = outsideStrip(ChatGateService.snapshot().viewport, openedLayout.panel, openedLayout.right);
        int start = strip.top + strip.height()/3, end = start + dp(40);
        long frames = AssistantState.frames, scrolled = SystemClock.elapsedRealtime();
        gesture("EXPANDED_OUTSIDE", strip.centerX(), start, strip.centerX(), end, true);
        try {
            await(() -> AssistantState.expanded && AssistantState.frames > frames && anchorsMoved(anchors, codePositions()),
                    20000, "expanded-scroll-updates-real-fixed-anchors");
        } catch (AssertionError failure) {
            Map<String,Integer> after = codePositions();
            int shared=0,moved=0;
            for(Map.Entry<String,Integer> anchor:anchors.entrySet()) {
                Integer y=after.get(anchor.getKey());
                if(y!=null){shared++;if(Math.abs(y-anchor.getValue())>=dp(24))moved++;}
            }
            throw new AssertionError(failure.getMessage()+" beforeFrames="+frames+" afterFrames="+AssistantState.frames
                    +" beforeAnchors="+anchors.size()+" afterAnchors="+after.size()+" shared="+shared+" moved="+moved);
        }
        long update = SystemClock.elapsedRealtime() - scrolled;
        outsideChanges++;
        require(AssistantState.coverage.obscured(), "expanded-remains-partial");
        for (ChatMessage message : AssistantState.messages)
            require(!message.text.contains("聊天助手") && !message.text.contains("最近识别") && !message.text.contains("更多操作"), "own-ui-not-returned-as-chat");
        gesture("EXPANDED_RETURN", strip.centerX(), end, strip.centerX(), start, true);
        Layout closing = layout();
        long beforeClose = AssistantState.frames;
        gesture("EXPANDED_CLOSE", closing.ball.centerX(), closing.ball.centerY(), 0, 0, false);
        await(() -> !AssistantState.expanded && fixedVisible() && AssistantState.frames > beforeClose,
                20000, "collapsed-reading-recovers-without-historic-mask");
        enduranceSummary = " expandedFirstMs=" + first + " expandedScrollUpdateMs=" + update;
    }

    /** 真实固定聊天验证自动建议与明确点击复制；不读原剪贴板，不填入/发送微信。 */
    private Rect visibleQuickControl(View view) {
        Rect result = new Rect();
        if (!view.getGlobalVisibleRect(result)) return new Rect();
        Rect bounds = screenBounds(view);
        if (!bounds.intersect(screenBounds((View)field(AssistOverlay.class,currentOverlay(),"quick")))
                || !bounds.intersect(layout().panel)) bounds.setEmpty();
        return bounds;
    }

    /** 当前轻量建议的滚动/静置长测；不替代旧长测、真实输入和会话切换验收。 */
    private void quickEnduranceInteraction() {
        await(this::quickReady, 20000, "quick-endurance-initial-ready");
        long started = SystemClock.elapsedRealtime();
        long longestReady = 0, longestUnavailable = 0, longestObservation = 0;
        int rounds = 0, idleSamples = 0;
        int worker = workerPid();
        require(worker > 0, "quick-endurance-real-worker");
        Rect initial = layout().panel;
        queueMeter = new QueueMeter();
        while (SystemClock.elapsedRealtime() - started < enduranceSeconds * 1000L) {
            Rect strip = outsideStrip(ChatGateService.snapshot().viewport, initial, layout().right);
            int start = strip.top + strip.height() / 3, end = start + dp(40);
            Map<String, Integer> anchors = codePositions();
            require(anchors.size() >= 2, "quick-endurance-fixed-anchors");
            long frames = AssistantState.frames, begin = SystemClock.elapsedRealtime();
            boolean forward = rounds % 2 == 0;
            gesture(forward ? "EXPANDED_OUTSIDE" : "EXPANDED_RETURN", strip.centerX(),
                    forward ? start : end, strip.centerX(), forward ? end : start, true);
            await(() -> AssistantState.frames > frames && anchorsMoved(anchors, codePositions())
                    && quickReady(), 20000, "quick-endurance-scroll-ready");
            long readyMs = SystemClock.elapsedRealtime() - begin;
            longestReady = Math.max(longestReady, readyMs);
            require(initial.equals(layout().panel), "quick-endurance-panel-stable");
            require(workerPid() == worker && owner.ready(), "quick-endurance-owner-stable");
            rounds++; outsideChanges++;
            long idleUntil = SystemClock.elapsedRealtime() + 5000, unavailableSince = -1;
            long observedAt = SystemClock.elapsedRealtime();
            while (SystemClock.elapsedRealtime() < idleUntil) {
                long now = SystemClock.elapsedRealtime();
                boolean ready = quickReady();
                long readyObservedAt = SystemClock.elapsedRealtime();
                if (!ready && unavailableSince < 0) unavailableSince = now;
                if (unavailableSince >= 0) {
                    long gap = readyObservedAt - unavailableSince;
                    longestUnavailable = Math.max(longestUnavailable, gap);
                    // 这是本项新增静置判据，不覆盖旧长测对复杂拖动/键盘的要求。
                    require(gap <= 2000, "quick-endurance-idle-unavailable-over-2s");
                    if (ready) unavailableSince = -1;
                }
                require(onMain(() -> !AssistantState.expanded && layout() != null
                        && initial.equals(layout().panel)), "quick-endurance-idle-no-expansion-or-jump");
                long completedAt = SystemClock.elapsedRealtime();
                longestObservation = Math.max(longestObservation, completedAt - observedAt);
                require(completedAt - observedAt <= 2000, "quick-endurance-observation-gap-over-2s");
                observedAt = completedAt;
                idleSamples++;
                SystemClock.sleep(100);
            }
            boolean finalReady = quickReady();
            long finalObservedAt = SystemClock.elapsedRealtime();
            longestObservation = Math.max(longestObservation, finalObservedAt - observedAt);
            require(finalObservedAt - observedAt <= 2000, "quick-endurance-final-observation-over-2s");
            if (unavailableSince >= 0) {
                longestUnavailable = Math.max(longestUnavailable, finalObservedAt - unavailableSince);
                require(finalObservedAt - unavailableSince <= 2000,
                        "quick-endurance-final-unavailable-over-2s");
            }
            require(finalReady, "quick-endurance-ready-after-idle");
            Bundle status = new Bundle();
            status.putString("quickEndurance", "round=" + rounds + " elapsedMs="
                    + (SystemClock.elapsedRealtime() - started) + " readyMs=" + readyMs
                    + " longestUnavailableMs=" + longestUnavailable + " longestObservationMs=" + longestObservation + " frames=" + AssistantState.frames);
            sendStatus(2, status);
        }
        queueMeter.close();
        require(rounds >= 2 && SystemClock.elapsedRealtime() - started >= enduranceSeconds * 1000L,
                "quick-endurance-duration-and-rounds");
        enduranceSummary = " quickEndurance=true elapsedMs=" + (SystemClock.elapsedRealtime() - started)
                + " rounds=" + rounds + " idleSamples=" + idleSamples + " longestReadyMs=" + longestReady
                + " longestUnavailableMs=" + longestUnavailable + " longestObservationMs=" + longestObservation + " " + queueMeter.summary();
        quickDetails();
    }

    private void quickHeaderStopInteraction() {
        await(this::quickReady, 20000, "header-stop-initial-suggestion-ready");
        quickDetails();
        await(() -> onMain(() -> AssistantState.matches(AssistantState.snapshot()) && codePositions().size() >= 2
                && Boolean.FALSE.equals(field(AssistantCaptureService.class,
                field(AssistantCaptureService.class, null, "instance"), "busy"))), 20000, "header-stop-expanded-ready");
        headerStopHold = onMain(HeaderStopHold::new);
        await(() -> headerStopHold.holding(), 3000, "header-worker-blocker-started");
        Rect strip = outsideStrip(ChatGateService.snapshot().viewport, layout().panel, layout().right);
        int start = strip.top + strip.height() / 3;
        gesture("EXPANDED_OUTSIDE", strip.centerX(), start, strip.centerX(), start + dp(40), true);
        await(() -> onMain(() -> headerStopHold.pendingImage()), 10000, "real-header-completed-and-geometry-pending");
    }

    private CacheKeySnapshot cacheKeySnapshot() {
        return onMain(() -> {
            Object service = field(AssistantCaptureService.class, null, "instance");
            Object ocr = field(AssistantCaptureService.class, service, "bubbleOcr");
            Object cache = field(BubbleOcr.class, ocr, "cache");
            Map<?, ?> entries = (Map<?, ?>) field(ExactPixelCache.class, cache, "entries");
            return new CacheKeySnapshot((Long) field(BubbleOcr.class, ocr, "cacheEpoch"), entries.keySet());
        });
    }

    private CachePixelSnapshot cachePixelSnapshot() {
        return onMain(() -> {
            Object service = field(AssistantCaptureService.class, null, "instance");
            Object ocr = field(AssistantCaptureService.class, service, "bubbleOcr");
            Object cache = field(BubbleOcr.class, ocr, "cache");
            return new CachePixelSnapshot((Map<?, ?>) field(ExactPixelCache.class, cache, "entries"), true);
        });
    }

    private void quickPerformance() {
        if(traceBounds)onMain(()->{boundaryReasons=new BoundaryReasonJournal();return null;});
        await(() -> onMain(() -> AssistantState.analysis != null && AssistantState.analysis.simulated
                && ((View)field(AssistOverlay.class,currentOverlay(),"quickCopy")).isEnabled()),20000,"perf-initial-ready");
        Rect initial=layout().panel;
        Rect strip=outsideStrip(ChatGateService.snapshot().viewport,initial,layout().right);
        int start=strip.top+strip.height()/3,end=start+dp(40);
        StringBuilder samples=new StringBuilder();
        java.util.List<long[]> demoChecks=new java.util.ArrayList<>();
        int[] droppedChecks={0};
        AnalysisCoordinator timed=onMain(() -> (AnalysisCoordinator)field(AnalysisCoordinator.class,null,"active"));
        onMain(() -> {timed.timingObserver=value -> {if(demoChecks.size()<100)demoChecks.add(value);else droppedChecks[0]++;};return null;});
        for(int round=0;round<6;round++) {
            try (CacheKeySnapshot beforeCache = traceWindows ? cacheKeySnapshot() : null;
                 CachePixelSnapshot beforePixels = traceWindows ? cachePixelSnapshot() : null) {
            onMain(() -> {demoChecks.clear();droppedChecks[0]=0;return null;});
            Map<String,Integer> anchors=codePositions();
            require(anchors.size()>=2,"perf-fixed-anchors");
            long frames=AssistantState.frames,begin=SystemClock.elapsedRealtime();
            boolean forward=round%2==0;
            gesture(forward?"EXPANDED_OUTSIDE":"EXPANDED_RETURN",strip.centerX(),forward?start:end,
                    strip.centerX(),forward?end:start,true);
            await(() -> AssistantState.frames>frames && anchorsMoved(anchors,codePositions())
                    && onMain(() -> currentOverlay()!=null && AssistantState.analysis!=null
                    && ((View)field(AssistOverlay.class,currentOverlay(),"quickCopy")).isEnabled()),
                    20000,"perf-scroll-to-ready-"+round);
            require(initial.equals(layout().panel),"perf-stable-window-"+round);
            long latency=SystemClock.elapsedRealtime()-begin;
            final int sampleRound = round;
            String sample=onMain(() -> {
                        AssistantState.Snapshot snapshot=AssistantState.snapshot();
                        require(AssistantState.matches(snapshot),"perf-current-snapshot-at-sample");
                        AssistantState.CaptureTiming timing=snapshot.timing;
                        require(timing!=null && timing.startedAt>=timing.changedAt
                                && timing.completedAt>=timing.startedAt,"perf-bound-published-frame-timing");
                        require(AssistantState.analysis!=null && AssistantState.analysisFrame>=0
                                && AssistantState.analysisFrame<=snapshot.frame,"perf-analysis-source-frame");
                        long changed=timing.changedAt;
                        long ocrStarted=timing.startedAt;
                        long ocrElapsed=timing.completedAt-ocrStarted;
                        require(ocrElapsed==AssistantState.ocrMs,"perf-published-ocr-duration");
                        long analyzed=AssistantState.analyzedAt;
                        // 同内容的新帧允许复用建议；可用时刻不能早于当前帧完成，也不伪称重新生成。
                        long available=Math.max(analyzed,timing.completedAt);
                        long observed=SystemClock.elapsedRealtime();
                        StringBuilder checks=new StringBuilder();
                        for(long[] value:demoChecks)if(value[0]==AssistantState.frames)
                            checks.append(';').append(value[3]).append('@').append(value[1]-ocrStarted-ocrElapsed)
                                    .append('/').append(value[2]-value[1]).append('/').append(value[4]).append('/').append(value[5]);
                        return "round="+sampleRound+" readyMs="+latency+" ocrMs="+ocrElapsed
                                +" messages="+snapshot.messages.size()+" anchors="+codePositions().size()
                                +" changeFromGestureMs="+(changed-begin)+" preOcrMs="+(ocrStarted-changed)
                                +" postOcrToAvailableMs="+(available-timing.completedAt)
                                +" observedAfterAvailableMs="+(observed-available)
                                +" quiet="+ChatInteraction.quiet(observed)
                                +" codes="+String.join(",",codePositions().keySet())+" demoChecks="+checks+" droppedChecks="+droppedChecks[0]
                                +" timingVersion=2 frame="+snapshot.frame+" analysisFrame="+AssistantState.analysisFrame
                                +" reusedAnalysis="+(AssistantState.analysisFrame!=snapshot.frame);
                    });
            Bundle status=new Bundle();status.putString("performance",sample);sendStatus(2,status);
            if (beforeCache != null) {
                try (CacheKeySnapshot afterCache = cacheKeySnapshot()) {
                    Bundle cacheStatus = new Bundle();
                    cacheStatus.putString("cacheComparison", "round=" + round + " " + beforeCache.compare(afterCache));
                    sendStatus(2, cacheStatus);
                }
            }
            if (beforePixels != null) {
                try (CachePixelSnapshot afterPixels = cachePixelSnapshot()) {
                    Bundle pixelStatus = new Bundle();
                    pixelStatus.putString("pixelComparison", "round=" + round + " " + beforePixels.compare(afterPixels));
                    sendStatus(2, pixelStatus);
                    Bundle fixedStatus = new Bundle();
                    fixedStatus.putString("fixedPixelComparison", "round=" + round + " " + beforePixels.compareFixed(afterPixels));
                    sendStatus(2, fixedStatus);
                }
            }
            samples.append(" [").append(sample).append(']');
            outsideChanges++;
            SystemClock.sleep(500);
            }
        }
        onMain(() -> {timed.timingObserver=null;return null;});
        if(boundaryReasons!=null) {
            List<String> records=reportBoundaryReasons();
            require(records.stream().filter(value->value.contains("diagnosticMs=")).count()>=2,"boundary-at-least-two-frames");
            require(records.stream().noneMatch(value->value.contains("observerError=")),"boundary-observer-no-error");
        }
        Rect more=onMain(() -> {
            android.widget.LinearLayout quick=(android.widget.LinearLayout)field(AssistOverlay.class,currentOverlay(),"quick");
            return visibleQuickControl(((android.widget.LinearLayout)quick.getChildAt(1)).getChildAt(2));
        });
        require(!more.isEmpty(),"perf-more-visible");
        gesture("QUICK_MORE",more.centerX(),more.centerY(),0,0,false);
        await(() -> AssistantState.expanded&&layout()!=null,3000,"perf-details-for-normal-stop");
        enduranceSummary=" quickPerformance="+samples+" clipboardWrites=0";
    }

    private List<String> reportBoundaryReasons() {
        List<String> records=onMain(()->{boundaryReasons.close();return boundaryReasons.snapshot();});
        for(String value:records){Bundle diagnostics=new Bundle();diagnostics.putString("boundaryReason",value);sendStatus(2,diagnostics);}
        boundaryReasonsReported=true;
        return records;
    }

    private void quickInteraction() {
        await(() -> onMain(() -> AssistantState.analysis != null && AssistantState.analysis.simulated
                && ((View)field(AssistOverlay.class,currentOverlay(),"quickCopy")).isEnabled()),
                20000,"automatic-quick-ready-without-generate");
        onMain(() -> {
            require(!AssistantState.expanded,"quick-default-not-details");
            require(((android.widget.TextView)field(AssistOverlay.class,currentOverlay(),"ball")).getText().toString().contains("固定示例"),"quick-marked-fixed-demo");
            return null;
        });
        Rect initial = layout().panel;
        int index = onMain(() -> (Integer)field(AssistOverlay.class,currentOverlay(),"quickIndex"));
        Rect next = onMain(() -> visibleQuickControl((View)field(AssistOverlay.class,currentOverlay(),"quickNext")));
        require(!next.isEmpty(),"quick-next-visible");
        gesture("QUICK_NEXT",next.centerX(),next.centerY(),0,0,false);
        await(() -> onMain(() -> (Integer)field(AssistOverlay.class,currentOverlay(),"quickIndex") != index),3000,"next-reply-one-tap");
        require(initial.equals(layout().panel),"next-keeps-window-geometry");
        Rect copy = onMain(() -> visibleQuickControl((View)field(AssistOverlay.class,currentOverlay(),"quickCopy")));
        require(!copy.isEmpty(),"copy-visible-without-selection");
        if (verifyQuickClipboard) expectedClipboard = onMain(() -> {
            AnalysisResult current = (AnalysisResult)field(AssistOverlay.class,currentOverlay(),"quickAnalysis");
            require(current != null && current.simulated, "clipboard-check-only-fixed-demo");
            return current.replies.get((Integer)field(AssistOverlay.class,currentOverlay(),"quickIndex")).text;
        });
        long copied = SystemClock.elapsedRealtime();
        gesture("QUICK_COPY",copy.centerX(),copy.centerY(),0,0,false);
        await(() -> onMain(() -> !AssistantState.expanded && currentOverlay()!=null
                && ((View)field(AssistOverlay.class,currentOverlay(),"quick")).getVisibility()!=View.VISIBLE
                && field(AssistOverlay.class,currentOverlay(),"selectedReply")==null),3000,"explicit-copy-collapses");
        long copyMs = SystemClock.elapsedRealtime()-copied;
        long until=SystemClock.elapsedRealtime()+4000;
        while(SystemClock.elapsedRealtime()<until) {
            require(onMain(() -> currentOverlay()==null || ((View)field(AssistOverlay.class,currentOverlay(),"quick")).getVisibility()!=View.VISIBLE),"copy-stays-collapsed-without-user-action");
            SystemClock.sleep(100);
        }
        await(() -> layout()!=null,10000,"ball-available-after-copy");
        Layout collapsed=layout();
        gesture("QUICK_OPEN",collapsed.ball.centerX(),collapsed.ball.centerY(),0,0,false);
        await(() -> onMain(() -> currentOverlay()!=null && ((View)field(AssistOverlay.class,currentOverlay(),"quick")).getVisibility()==View.VISIBLE),3000,"manual-reopen-quick");
        Rect more=onMain(() -> {
            android.widget.LinearLayout quick=(android.widget.LinearLayout)field(AssistOverlay.class,currentOverlay(),"quick");
            return visibleQuickControl(((android.widget.LinearLayout)quick.getChildAt(1)).getChildAt(2));
        });
        require(!more.isEmpty(),"more-visible");
        gesture("QUICK_MORE",more.centerX(),more.centerY(),0,0,false);
        await(() -> AssistantState.expanded && layout()!=null,3000,"more-opens-details-controls");
        onMain(() -> {
            require(field(AssistOverlay.class,currentOverlay(),"draftTask")==null
                    && !Boolean.TRUE.equals(field(AssistOverlay.class,currentOverlay(),"writeMayHaveOccurred")),"quick-no-draft-request");
            return null;
        });
        enduranceSummary=" quickCopyCollapseMs="+copyMs+" clipboardOperation=explicitUserTap clipboardCheckDeferred="+verifyQuickClipboard;
    }

    private void demoInteraction() {
        Layout collapsed=layout();
        long before=AssistantState.frames;
        gesture("EXPANDED_OPEN",collapsed.ball.centerX(),collapsed.ball.centerY(),0,0,false);
        await(()->AssistantState.expanded&&AssistantState.frames>before
                &&AssistantState.matches(AssistantState.snapshot()),20000,"new-real-ocr-before-demo");
        selectLocalDemo();
        onMain(()->{
            require(!Boolean.TRUE.equals(field(AssistOverlay.class,currentOverlay(),"writeMayHaveOccurred"))
                    &&field(AssistOverlay.class,currentOverlay(),"draftTask")==null,"selection-no-draft-request");
            return null;
        });
        Layout closing=layout();
        gesture("EXPANDED_CLOSE",closing.ball.centerX(),closing.ball.centerY(),0,0,false);
        await(()->!AssistantState.expanded&&layout()!=null,3000,"demo-card-collapses");
    }

    /** 真实OCR快照触发正式模拟入口；只选择，不调用复制或草稿动作。 */
    private void selectLocalDemo() {
        Rect toggle = onMain(() -> visibleControl((View)field(AssistOverlay.class,currentOverlay(),"demoToggle")));
        require(!toggle.isEmpty(),"demo-toggle-visible");
        gesture("DEMO_ENABLE",toggle.centerX(),toggle.centerY(),0,0,false);
        await(() -> AssistantState.analysis != null && AssistantState.analysis.simulated && AnalysisCoordinator.demoEnabled(),
                5000,"demo-after-real-ocr");
        await(() -> onMain(() -> ((android.widget.LinearLayout)field(AssistOverlay.class,currentOverlay(),"replyChoices")).getChildCount()==3),
                2000,"demo-three-rendered-choices");
        Rect first = onMain(() -> {
            Object overlay=currentOverlay();
            View notice=(View)field(AssistOverlay.class,overlay,"demoNotice");
            require(!visibleControl(notice).isEmpty(),"demo-mark-visible");
            android.widget.LinearLayout choices=(android.widget.LinearLayout)field(AssistOverlay.class,overlay,"replyChoices");
            require(((android.widget.Button)choices.getChildAt(0)).getText().toString().startsWith("模拟"),"choice-marked");
            return visibleControl(choices.getChildAt(0));
        });
        require(!first.isEmpty(),"first-demo-visible");
        java.util.concurrent.atomic.AtomicInteger copies=new java.util.concurrent.atomic.AtomicInteger();
        android.content.ClipboardManager clipboard=getTargetContext().getSystemService(android.content.ClipboardManager.class);
        android.content.ClipboardManager.OnPrimaryClipChangedListener listener=copies::incrementAndGet;
        onMain(()->{clipboard.addPrimaryClipChangedListener(listener);return null;});
        try {
        gesture("DEMO_SELECT",first.centerX(),first.centerY(),0,0,false);
        await(() -> onMain(() -> field(AssistOverlay.class,currentOverlay(),"selectedReply")!=null
                && ((android.widget.TextView)field(AssistOverlay.class,currentOverlay(),"replyPreview")).getText().toString().contains("固定示例")),
                2000,"selected-demo-awaits-confirmation");
            require(copies.get()==0,"selection-does-not-copy");
        } finally { onMain(()->{clipboard.removePrimaryClipChangedListener(listener);return null;}); }
        require(!new ModelSettingsStore(getTargetContext()).cloudAllowed(),"demo-does-not-enable-cloud");
    }

    private void endurance(boolean initialRight, ModelSettingsStore settings) {
        long started = SystemClock.elapsedRealtime();
        long ticksPerSecond = Os.sysconf(OsConstants._SC_CLK_TCK);
        require(ticksPerSecond > 0, "endurance-positive-clock-rate");
        int worker = workerPid();
        require(worker > 0, "endurance-real-projection-worker");
        ProcessCpuSnapshot parentStart = readCpu(Process.myPid());
        ProcessCpuSnapshot workerStart = readCpu(worker);
        queueMeter = new QueueMeter();
        int rounds = 0;
        int samples = 0;
        enduranceSample(started, rounds, worker, ticksPerSecond, parentStart, workerStart, settings);
        samples++;
        while (SystemClock.elapsedRealtime() - started < enduranceSeconds * 1000L) {
            round(!initialRight);
            rounds++;
            round(initialRight);
            rounds++;
            enduranceSample(started, rounds, worker, ticksPerSecond, parentStart, workerStart, settings);
            samples++;
        }
        queueMeter.close();
        require(rounds >= 2 && SystemClock.elapsedRealtime() - started >= enduranceSeconds * 1000L,
                "endurance-requested-duration-and-real-rounds-completed");
        enduranceSummary = " enduranceElapsedMs=" + (SystemClock.elapsedRealtime() - started)
                + " enduranceRounds=" + rounds + " metricSamples=" + samples + " " + queueMeter.summary();
    }

    private void enduranceSample(long started, int rounds, int worker, long ticksPerSecond,
            ProcessCpuSnapshot parentStart, ProcessCpuSnapshot workerStart, ModelSettingsStore settings) {
        require(AssistantCaptureService.running() && workerPid() == worker, "endurance-owner-topology-stable");
        ProcessCpuSnapshot parentNow = readCpu(Process.myPid());
        ProcessCpuSnapshot workerNow = readCpu(worker);
        require(parentNow.startedTicks == parentStart.startedTicks && workerNow.startedTicks == workerStart.startedTicks,
                "endurance-process-start-identities-stable");
        require(!settings.hasKey() && !settings.cloudAllowed() && !settings.partialAnalysisAllowed(), "endurance-no-model-or-cloud");
        require(!AssistantState.expanded && fixedVisible() && layout() != null, "endurance-fixed-chat-restored-between-rounds");
        Debug.MemoryInfo[] memory = getTargetContext().getSystemService(ActivityManager.class)
                .getProcessMemoryInfo(new int[]{Process.myPid(), worker});
        require(memory.length == 2 && memory[0].getTotalPss() > 0 && memory[1].getTotalPss() > 0,
                "endurance-real-both-process-memory");
        long allocated = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        Bundle status = new Bundle();
        status.putString("phase", "ENDURANCE_SAMPLE");
        status.putInt("parentPid", Process.myPid());
        status.putString("metrics", "elapsedMs=" + (SystemClock.elapsedRealtime() - started) + " rounds=" + rounds
                + " workerPid=" + worker + " parentCpuMs=" + cpuElapsed(parentNow, parentStart, ticksPerSecond)
                + " workerCpuMs=" + cpuElapsed(workerNow, workerStart, ticksPerSecond)
                + " parentPssKb=" + memory[0].getTotalPss() + " workerPssKb=" + memory[1].getTotalPss()
                + " javaUsedKb=" + allocated / 1024 + " nativeAllocatedKb=" + Debug.getNativeHeapAllocatedSize() / 1024
                + " processingRounds=" + AssistantState.frames + " " + queueMeter.summary());
        sendStatus(1, status);
    }

    private ProcessCpuSnapshot readCpu(int pid) {
        try { return ProcessCpuSnapshot.read(pid); }
        catch (Exception failure) { throw new AssertionError("endurance-own-process-counter-unavailable"); }
    }

    private long cpuElapsed(ProcessCpuSnapshot current, ProcessCpuSnapshot before, long ticksPerSecond) {
        try { return current.elapsedMillis(before, ticksPerSecond); }
        catch (Exception failure) { throw new AssertionError("endurance-own-process-counter-discontinuity"); }
    }

    private int workerPid() {
        List<ActivityManager.RunningAppProcessInfo> processes = getTargetContext().getSystemService(ActivityManager.class).getRunningAppProcesses();
        if (processes == null) return -1;
        return processes.stream().filter(process -> process.uid == Process.myUid()
                && process.processName.equals(getTargetContext().getPackageName() + ":projection"))
                .mapToInt(process -> process.pid).findFirst().orElse(0);
    }

    private boolean quickReady() {
        return onMain(() -> {
            Object overlay = currentOverlay();
            if (overlay == null) return false;
            AssistantState.Snapshot rendered = (AssistantState.Snapshot) field(AssistOverlay.class, overlay, "quickTarget");
            return ChatGateService.snapshot().valid()
                    && ChatGateService.snapshot().generation == AssistantState.snapshot().generation
                    && AssistantState.matches(AssistantState.snapshot())
                    && rendered != null && AssistantState.matches(rendered)
                    && field(AssistOverlay.class, overlay, "quickAnalysis") == AssistantState.analysis
                    && AssistantState.analysis != null && AssistantState.analysis.simulated
                    && ((View) field(AssistOverlay.class, overlay, "quickCopy")).isEnabled()
                    && ((View) field(AssistOverlay.class, overlay, "quick")).getVisibility() == View.VISIBLE;
        });
    }

    private boolean quickSuggestionWithdrawn() {
        return onMain(() -> {
            Object overlay = currentOverlay();
            return overlay == null || !((View) field(AssistOverlay.class, overlay, "quick")).isShown()
                    || field(AssistOverlay.class, overlay, "quickTarget") == null
                    && field(AssistOverlay.class, overlay, "quickAnalysis") == null
                    && !((View) field(AssistOverlay.class, overlay, "quickCopy")).isEnabled();
        });
    }

    private void quickDetails() {
        Rect more=onMain(()->{
            android.widget.LinearLayout quick=(android.widget.LinearLayout)field(AssistOverlay.class,currentOverlay(),"quick");
            return visibleQuickControl(((android.widget.LinearLayout)quick.getChildAt(1)).getChildAt(2));
        });
        require(!more.isEmpty(),"lifecycle-more-visible");
        gesture("QUICK_MORE",more.centerX(),more.centerY(),0,0,false);
        await(()->AssistantState.expanded&&layout()!=null,3000,"lifecycle-details-open");
    }

    private void quickNavigation(String phaseName) {
        long identifier=++step;Bundle status=new Bundle();status.putString("phase",phaseName);
        status.putLong("step",identifier);sendStatus(1,status);
        await(()->completedStep==identifier,20000,"lifecycle-navigation-finished");gestureActions++;
    }

    /** 两个既有测试聊天间正常导航；只在内存比较身份，绝不输出标题或聊天原文。 */
    private void quickSwitchInteraction() {
        await(this::quickReady, 20000, "switch-initial-ready");
        require(onMain(() -> ChatGateService.snapshot().keyboardTop < 0), "switch-keyboard-initially-hidden");
        AssistantState.Snapshot original = onMain(AssistantState::snapshot);
        require(!ConversationSession.normalize(original.title).equals(ConversationSession.normalize("文件传输助手")),
                "switch-two-distinct-chat-precondition");
        NormalGrantUi navigation = new NormalGrantUi(this, assistant, this::phase);
        navigation.returnToChatList();
        await(() -> onMain(() -> !AssistantState.matches(original) && AssistantState.analysis == null
                && AssistantState.messages.isEmpty()) && quickSuggestionWithdrawn(),
                5000, "switch-list-clears-original");
        long began = SystemClock.elapsedRealtime();
        navigation.openFileTransferFromList();
        await(() -> quickReady() && onMain(() -> ConversationSession.normalize(AssistantState.title)
                .equals(ConversationSession.normalize("文件传输助手"))), 25000, "switch-second-chat-ready");
        long secondMs = SystemClock.elapsedRealtime() - began;
        AssistantState.Snapshot second = onMain(AssistantState::snapshot);
        require(!second.token.equals(original.token) && !onMain(() -> AssistantState.matches(original)),
                "switch-second-chat-rejects-original-token");
        navigation.returnToChatList();
        await(() -> onMain(() -> !AssistantState.matches(second) && AssistantState.analysis == null
                && AssistantState.messages.isEmpty()) && quickSuggestionWithdrawn(),
                5000, "switch-list-clears-second");
        began = SystemClock.elapsedRealtime();
        navigation.openFixedChatFromList();
        await(() -> quickReady() && codePositions().size() >= 2 && onMain(() ->
                ConversationSession.normalize(AssistantState.title).equals(ConversationSession.normalize(original.title))),
                25000, "switch-original-chat-recognized-again");
        long returnMs = SystemClock.elapsedRealtime() - began;
        AssistantState.Snapshot returned = onMain(AssistantState::snapshot);
        require(!returned.token.equals(original.token) && !returned.token.equals(second.token)
                && !onMain(() -> AssistantState.matches(original) || AssistantState.matches(second)),
                "switch-return-does-not-revive-either-old-snapshot");
        quickDetails();
        enduranceSummary = " quickSwitch=true distinctChats=2 secondReadyMs=" + secondMs
                + " returnReadyMs=" + returnMs + " navigationActions=4 textActions=0";
    }

    /** 正常离开/返回微信与暂停/继续；只观察自有状态，不读聊天列表或修改草稿。 */
    private void quickDetailRecoveryInteraction() {
        await(this::quickReady, 20000, "detail-recovery-initial-ready");
        quickDetails();
        await(() -> onMain(() -> AssistantState.matches(AssistantState.snapshot()) && AssistantState.expanded),
                25000, "detail-recovery-initial-recognition");
        AssistantState.Snapshot original = onMain(AssistantState::snapshot);
        quickNavigation("QUICK_HOME");
        await(() -> onMain(() -> !ChatGateService.snapshot().valid() && layout() == null
                && !AssistantState.expanded && AssistantState.expansionRequested()
                && AssistantState.messages.isEmpty() && AssistantState.analysis == null),
                5000, "detail-recovery-home-clears-content-retains-intent");
        require(!onMain(() -> AssistantState.matches(original)), "detail-recovery-old-snapshot-rejected");
        quickNavigation("QUICK_RETURN");
        long returned = SystemClock.elapsedRealtime();
        await(() -> onMain(() -> AssistantState.expanded && layout() != null
                && AssistantState.matches(AssistantState.snapshot()) && codePositions().size() >= 2),
                25000, "detail-recovery-return-restores-recognizing-details");
        long returnMs = SystemClock.elapsedRealtime() - returned;
        require(!onMain(() -> AssistantState.matches(original)), "detail-recovery-does-not-revive-old-snapshot");
        Rect collapse = onMain(() -> rect(((android.widget.LinearLayout)
                field(AssistOverlay.class, currentOverlay(), "actions")).getChildAt(0)));
        require(!collapse.isEmpty(), "detail-recovery-collapse-visible");
        gesture("QUICK_COLLAPSE", collapse.centerX(), collapse.centerY(), 0, 0, false);
        await(() -> !AssistantState.expanded && !AssistantState.expansionRequested(), 3000, "detail-recovery-user-collapse");
        quickNavigation("QUICK_HOME");
        await(() -> layout() == null, 5000, "detail-recovery-second-home-hidden");
        quickNavigation("QUICK_RETURN");
        await(() -> onMain(() -> ChatGateService.snapshot().valid() && AssistantState.matches(AssistantState.snapshot())
                && layout() != null && codePositions().size() >= 2), 25000, "detail-recovery-collapsed-return-ready");
        require(!AssistantState.expanded && !AssistantState.expansionRequested(), "detail-recovery-collapse-not-undone");
        if (!quickReady()) {
            Layout collapsed = layout();
            gesture("QUICK_OPEN", collapsed.ball.centerX(), collapsed.ball.centerY(), 0, 0, false);
            await(this::quickReady, 15000, "detail-recovery-user-reopens-quick");
        }
        quickDetails();
        enduranceSummary = " detailRecovery=true expandedReturnReadyMs=" + returnMs + " oldSnapshotRejected=true explicitCollapsePreserved=true textActions=0";
    }

    private void quickLifecycleInteraction() {
        await(this::quickReady,20000,"lifecycle-initial-ready");
        AssistantState.Snapshot original=onMain(AssistantState::snapshot);
        quickNavigation("QUICK_HOME");
        await(()->onMain(()->!ChatGateService.snapshot().valid()&&AssistantState.analysis==null
                &&layout()==null&&field(AssistantCaptureService.class,field(AssistantCaptureService.class,null,"instance"),"reader")==null),
                5000,"lifecycle-home-clears-and-suspends");
        require(!onMain(()->AssistantState.matches(original)),"lifecycle-home-rejects-old-snapshot");
        quickNavigation("QUICK_RETURN");
        long returned=SystemClock.elapsedRealtime();
        await(()->quickReady()&&codePositions().size()>=2,25000,"lifecycle-return-recognizes-again");
        long returnMs=SystemClock.elapsedRealtime()-returned;
        require(!onMain(()->AssistantState.matches(original)),"lifecycle-return-does-not-revive-old-snapshot");
        quickDetails();
        await(()->onMain(()->ChatGateService.snapshot().valid()&&AssistantState.matches(AssistantState.snapshot())
                &&AssistantState.analysis!=null),25000,"lifecycle-details-current-before-pause");
        AssistantState.Snapshot beforePause=onMain(AssistantState::snapshot);
        Rect pauseControl=onMain(()->rect((View)field(AssistOverlay.class,currentOverlay(),"pause")));
        require(!pauseControl.isEmpty(),"lifecycle-pause-visible");
        gesture("QUICK_PAUSE",pauseControl.centerX(),pauseControl.centerY(),0,0,false);
        await(()->onMain(()->AssistantState.paused&&AssistantState.analysis==null
                &&field(AssistantCaptureService.class,field(AssistantCaptureService.class,null,"instance"),"reader")==null),
                5000,"lifecycle-paused-output-cleared");
        require(!onMain(()->AssistantState.matches(beforePause)),"lifecycle-pause-rejects-old-snapshot");
        Layout paused=layout();require(paused!=null,"lifecycle-paused-controls-available");
        gesture("QUICK_OPEN",paused.ball.centerX(),paused.ball.centerY(),0,0,false);
        await(()->AssistantState.expanded&&layout()!=null,3000,"lifecycle-paused-details-open");
        Rect resume=onMain(()->rect((View)field(AssistOverlay.class,currentOverlay(),"pause")));
        require(!resume.isEmpty(),"lifecycle-resume-visible");
        gesture("QUICK_PAUSE",resume.centerX(),resume.centerY(),0,0,false);
        long resumed=SystemClock.elapsedRealtime();
        await(()->!AssistantState.paused&&quickReady()&&codePositions().size()>=2,25000,"lifecycle-resume-recognizes-again");
        long resumeMs=SystemClock.elapsedRealtime()-resumed;
        require(!onMain(()->AssistantState.matches(beforePause)),"lifecycle-resume-does-not-revive-old-snapshot");
        quickDetails();
        enduranceSummary=" quickLifecycle=true returnReadyMs="+returnMs+" resumeReadyMs="+resumeMs+" textActions=0";
    }

    /** 只聚焦已获新鲜证明的输入区；保持原键盘，不输入或发送任何文字。 */
    private void quickKeyboardInteraction() {
        await(this::quickReady,20000,"quick-keyboard-initial-suggestion");
        onMain(()->{require(ChatGateService.snapshot().valid()&&ChatGateService.snapshot().keyboardTop<0,
                "quick-keyboard-initially-hidden");return null;});
        AtomicReference<AssistantCaptureService.ComposerProof> captured=new AtomicReference<>();
        AtomicBoolean delivered=new AtomicBoolean();
        onMain(() -> {
            AssistantCaptureService.requestComposer(AssistantState.snapshot(),proof->{captured.set(proof);delivered.set(true);});
            View ball=(View)field(AssistOverlay.class,currentOverlay(),"ball");
            float alpha=ball.getAlpha();ball.setAlpha(0.98f);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->ball.setAlpha(alpha),100);
            return null;
        });
        await(delivered::get,2500,"quick-keyboard-fresh-input-proof");
        AssistantCaptureService.ComposerProof proof=captured.get();
        require(proof!=null&&onMain(()->AssistantCaptureService.proofCurrent(proof)),"quick-keyboard-current-input-proof");
        long began=SystemClock.elapsedRealtime();
        gesture("FOCUS_INPUT",proof.bounds.centerX(),proof.bounds.centerY(),0,0,false);
        await(()->ChatGateService.snapshot().valid()&&ChatGateService.snapshot().keyboardTop>=0
                &&quickReady()&&codePositions().size()>=2,25000,"quick-keyboard-new-suggestion");
        long shownMs=SystemClock.elapsedRealtime()-began;
        ChatGateService.Snapshot shown=ChatGateService.snapshot();
        keyboardShownTop=shown.keyboardTop;
        require(shown.generation!=proof.target.generation,"quick-keyboard-new-generation");
        require(!onMain(()->AssistantCaptureService.proofCurrent(proof)),"quick-keyboard-old-proof-rejected");
        require(!onMain(()->AssistantState.matches(proof.target)),"quick-keyboard-old-snapshot-rejected-independent-of-age");
        require(layout().panel.bottom<=shown.keyboardTop-dp(56),"quick-strip-clears-keyboard-and-input");
        require(!AssistantState.expanded,"quick-keyboard-keeps-lightweight-suggestion");
        onMain(()->{
            View panel=(View)field(AssistOverlay.class,currentOverlay(),"panel");
            WindowManager.LayoutParams params=(WindowManager.LayoutParams)field(AssistOverlay.class,currentOverlay(),"params");
            require(!panel.hasWindowFocus()&&(params.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)!=0,"quick-strip-does-not-take-keyboard-focus");
            return null;
        });
        Rect next=onMain(()->visibleQuickControl((View)field(AssistOverlay.class,currentOverlay(),"quickNext")));
        int previousIndex=onMain(()->(Integer)field(AssistOverlay.class,currentOverlay(),"quickIndex"));
        require(!next.isEmpty(),"quick-keyboard-next-visible");
        gesture("QUICK_NEXT",next.centerX(),next.centerY(),0,0,false);
        await(()->onMain(()->currentOverlay()!=null&&(Integer)field(AssistOverlay.class,currentOverlay(),"quickIndex")!=previousIndex),
                3000,"quick-keyboard-next-changes-suggestion");
        require(ChatGateService.snapshot().keyboardTop>=0,"quick-next-retains-keyboard");
        require(Math.abs(layout().panel.height()-dp(156))<=1,"quick-strip-reduced-height");
        long beforeDetails=AssistantState.frames;
        quickDetails();
        await(()->AssistantState.expanded && ChatGateService.snapshot().valid()
                && ChatGateService.snapshot().keyboardTop>=0 && layout()!=null
                && AssistantState.frames>beforeDetails
                && onMain(()->AssistantState.matches(AssistantState.snapshot()))
                && codePositions().size()>=2,25000,"keyboard-details-current-recognition");
        onMain(()->{
            View detailScroll=(View)field(AssistOverlay.class,currentOverlay(),"scroll");
            require(detailScroll.getHeight()<=dp(112)+1 && detailScroll.getHeight()>=dp(80)-1,
                    "keyboard-details-height-limited");
            return null;
        });
        require(layout().panel.bottom<=ChatGateService.snapshot().keyboardTop-dp(56),
                "keyboard-details-clears-input");
        ScreenGeometry detailGeometry=ChatGateService.snapshot().viewport.overlayGeometry();
        require(Math.abs(layout().panel.top-Math.max(dp(88),detailGeometry.topInset+dp(48)))<=1,
                "keyboard-details-docks-above-recent-messages");
        Rect collapse=onMain(()->rect(((android.widget.LinearLayout)field(AssistOverlay.class,currentOverlay(),"actions")).getChildAt(0)));
        require(!collapse.isEmpty(),"keyboard-details-collapse-visible");
        gesture("QUICK_COLLAPSE",collapse.centerX(),collapse.centerY(),0,0,false);
        await(()->!AssistantState.expanded && !AssistantState.expansionRequested() && layout()!=null,
                3000,"keyboard-details-stays-collapsed");
        Rect reopen=layout().ball;
        gesture("QUICK_OPEN",reopen.centerX(),reopen.centerY(),0,0,false);
        await(this::quickReady,25000,"keyboard-details-manual-reopen-ready");
        long identifier=++step;Bundle status=new Bundle();status.putString("phase","CLOSE_KEYBOARD");
        status.putString("gesture","back");status.putLong("step",identifier);sendStatus(1,status);
        await(()->completedStep==identifier,20000,"quick-keyboard-back-finished");gestureActions++;
        long hiddenAt=SystemClock.elapsedRealtime();
        await(()->ChatGateService.snapshot().valid()&&ChatGateService.snapshot().keyboardTop<0
                &&quickReady()&&codePositions().size()>=2,25000,"quick-keyboard-full-height-recovery");
        require(ChatGateService.snapshot().generation!=shown.generation,"quick-keyboard-hide-new-generation");
        Rect more=onMain(()->{
            android.widget.LinearLayout quick=(android.widget.LinearLayout)field(AssistOverlay.class,currentOverlay(),"quick");
            return visibleQuickControl(((android.widget.LinearLayout)quick.getChildAt(1)).getChildAt(2));
        });
        require(!more.isEmpty(),"quick-keyboard-more-visible");
        long recoveredMs=SystemClock.elapsedRealtime()-hiddenAt;
        gesture("QUICK_MORE",more.centerX(),more.centerY(),0,0,false);
        await(()->AssistantState.expanded&&layout()!=null,3000,"quick-keyboard-details-for-stop");
        enduranceSummary=" quickKeyboard=true keyboardShownTop="+keyboardShownTop+" showReadyMs="+shownMs
                +" hideReadyMs="+recoveredMs+" keyboardTextActions=0";
    }

    private void keyboardInteraction() {
        Layout collapsed = layout();
        gesture("OPEN_KEYBOARD_SETUP", collapsed.ball.centerX(), collapsed.ball.centerY(), 0, 0, false);
        await(() -> AssistantState.expanded, 3000, "card-open-before-read-only-input-proof");
        AtomicReference<AssistantCaptureService.ComposerProof> captured = new AtomicReference<>();
        AtomicBoolean delivered = new AtomicBoolean();
        onMain(() -> {
            AssistantCaptureService.requestComposer(AssistantState.snapshot(), proof -> { captured.set(proof); delivered.set(true); });
            View ball = (View) field(AssistOverlay.class, currentOverlay(), "ball");
            float alpha = ball.getAlpha();
            ball.setAlpha(0.98f);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> ball.setAlpha(alpha), 100);
            return null;
        });
        await(delivered::get, 2500, "read-only-input-proof-delivered");
        AssistantCaptureService.ComposerProof proof = captured.get();
        require(proof != null && onMain(() -> AssistantCaptureService.proofCurrent(proof)), "real-current-input-proof-before-focus");
        gesture("FOCUS_INPUT", proof.bounds.centerX(), proof.bounds.centerY(), 0, 0, false);
        await(() -> ChatGateService.snapshot().valid() && ChatGateService.snapshot().keyboardTop >= 0
                && fixedVisible() && layout() != null, 25000, "original-keyboard-visible-and-new-ocr-confirmed");
        keyboardShownTop = ChatGateService.snapshot().keyboardTop;
        require(ChatGateService.snapshot().generation != proof.target.generation, "keyboard-revises-real-viewport-generation");
        require(!onMain(() -> AssistantCaptureService.proofCurrent(proof)), "old-input-proof-revoked-after-keyboard");
        require(!AssistantState.expanded, "changed-viewport-closes-old-card");
        boolean initialRight = layout().right;
        keyboardRound(!initialRight);
        keyboardRound(initialRight);
        require(ChatGateService.snapshot().keyboardTop >= 0, "original-keyboard-remains-open-after-own-touches");
        long shownGeneration = ChatGateService.snapshot().generation;
        long identifier = ++step;
        Bundle status = new Bundle();
        status.putString("phase", "CLOSE_KEYBOARD");
        status.putString("gesture", "back");
        status.putLong("step", identifier);
        sendStatus(1, status);
        await(() -> completedStep == identifier, 20000, "external-back-input-finished");
        gestureActions++;
        await(() -> ChatGateService.snapshot().valid() && ChatGateService.snapshot().keyboardTop < 0
                && fixedVisible() && layout() != null, 25000, "keyboard-hidden-and-full-height-ocr-restored");
        require(ChatGateService.snapshot().generation != shownGeneration, "keyboard-hide-revises-generation-again");
        require(!onMain(() -> AssistantCaptureService.proofCurrent(proof)), "old-input-proof-not-revived-by-height-return");
    }

    private void keyboardRound(boolean right) {
        String side = right ? "RIGHT" : "LEFT";
        Layout before = layout();
        ChatViewport viewport = ChatGateService.snapshot().viewport;
        int targetHorizontal = right ? viewport.bounds.right - before.panel.width() / 2 : viewport.bounds.left + before.panel.width() / 2;
        gesture("KEYBOARD_DRAG_" + side, before.ball.centerX(), before.ball.centerY(), targetHorizontal, before.ball.centerY(), true);
        await(() -> layout() != null && layout().right == right && !AssistantState.expanded
                && Math.abs(layout().panel.left - before.panel.left) > viewport.width() / 2, 3500, "keyboard-cross-screen-drag-" + side);
        await(this::fixedVisible, 15000, "keyboard-real-text-after-drag-" + side);
        require(layout().panel.bottom <= ChatGateService.snapshot().keyboardTop - dp(56), "collapsed-ball-clears-keyboard-and-input-" + side);
        Layout collapsed = layout();
        gesture("KEYBOARD_OPEN_" + side, collapsed.ball.centerX(), collapsed.ball.centerY(), 0, 0, false);
        await(() -> AssistantState.expanded && layout() != null, 3500, "keyboard-card-open-" + side);
        openScrollableDetails(side);
        Layout expanded = layout();
        ChatGateService.Snapshot gate = ChatGateService.snapshot();
        require(gate.valid() && gate.keyboardTop >= 0 && expanded.panel.bottom <= gate.keyboardTop - dp(56)
                && expanded.panel.left >= gate.viewport.bounds.left && expanded.panel.right <= gate.viewport.bounds.right,
                "expanded-card-within-real-keyboard-safe-region-" + side);
        onMain(() -> {
            Object overlay = currentOverlay();
            View panel = (View) field(AssistOverlay.class, overlay, "panel");
            WindowManager.LayoutParams parameters = (WindowManager.LayoutParams) field(AssistOverlay.class, overlay, "params");
            require(!panel.hasWindowFocus() && (parameters.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0,
                    "keyboard-card-never-takes-focus-" + side);
            return null;
        });
        require(expanded.scrollable, "keyboard-card-content-scrollable-" + side);
        long frames = AssistantState.frames;
        gesture("KEYBOARD_INSIDE_" + side, expanded.scroll.centerX(), expanded.scroll.bottom - dp(24),
                expanded.scroll.centerX(), expanded.scroll.top + dp(24), true);
        await(() -> layout() != null && layout().scrollY > expanded.scrollY, 3000, "keyboard-card-scroll-received-" + side);
        insideScrolls++;
        require(AssistantState.expanded && ChatGateService.snapshot().keyboardTop >= 0,
                "own-card-scroll-keeps-keyboard-and-expanded-reading-" + side);
        Layout close = layout();
        gesture("KEYBOARD_CLOSE_" + side, close.ball.centerX(), close.ball.centerY(), 0, 0, false);
        await(() -> !AssistantState.expanded && layout() != null && layout().panel.width() == collapsed.panel.width()
                && ChatGateService.snapshot().keyboardTop >= 0,
                3500, "own-card-collapse-preserves-keyboard-" + side);
        Layout restored = layout();
        require(Math.abs(restored.ball.centerY() - collapsed.ball.centerY()) <= dp(1),
                "keyboard-collapse-preserves-ball-anchor-" + side + " expected=" + collapsed.ball.centerY() + " actual=" + restored.ball.centerY());
    }

    private void round(boolean right) {
        String side = right ? "RIGHT" : "LEFT";
        Layout before = layout();
        ChatViewport viewport = ChatGateService.snapshot().viewport;
        int initialWidth = viewport.width();
        int targetHorizontal = right ? viewport.bounds.right - before.panel.width() / 2 : viewport.bounds.left + before.panel.width() / 2;
        gesture("DRAG_" + side, before.ball.centerX(), before.ball.centerY(), targetHorizontal, before.ball.centerY(), true);
        await(() -> {
            Layout current = layout();
            return current != null && current.right == right && Math.abs(current.panel.left - before.panel.left) > initialWidth / 2;
        }, 3500, "actual-cross-screen-drag-" + side);
        require(!AssistantState.expanded, "drag-does-not-open-card-" + side);
        await(this::fixedVisible, 15000, "real-fixed-chat-after-drag-" + side);
        Map<String, Integer> initialPositions = codePositions();
        require(initialPositions.size() >= 2, "multiple-fixed-message-position-anchors-" + side);
        Layout collapsed = layout();
        gesture("OPEN_" + side, collapsed.ball.centerX(), collapsed.ball.centerY(), 0, 0, false);
        await(() -> AssistantState.expanded && layout() != null && layout().panel.width() > collapsed.panel.width() * 3,
                3500, "real-touch-opens-card-" + side);
        openScrollableDetails(side);
        Layout expanded = layout();
        onMain(() -> {
            Object overlay = currentOverlay();
            WindowManager.LayoutParams parameters = (WindowManager.LayoutParams) field(AssistOverlay.class, overlay, "params");
            View panel = (View) field(AssistOverlay.class, overlay, "panel");
            require((parameters.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0 && !panel.hasWindowFocus(), "card-never-takes-window-focus-" + side);
            require((parameters.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) != 0, "card-not-touch-modal-" + side);
            return null;
        });
        viewport = ChatGateService.snapshot().viewport;
        Rect strip = outsideStrip(viewport, expanded.panel, right);
        long frames = AssistantState.frames;
        int startVertical = strip.top + strip.height() / 3;
        int endVertical = strip.top + strip.height() * 2 / 3;
        gesture("OUTSIDE_" + side, strip.centerX(), startVertical, strip.centerX(), endVertical, true);
        require(AssistantState.expanded && layout() != null && layout().panel.equals(expanded.panel), "outside-scroll-does-not-move-or-close-card-" + side);
        await(() -> AssistantState.expanded && AssistantState.frames > frames, 20000, "body-ocr-updates-while-card-open-" + side);
        Layout scrollable = layout();
        require(scrollable.scrollable && !scrollable.scroll.isEmpty(), "actual-card-content-scrollable-" + side);
        gesture("INSIDE_" + side, scrollable.scroll.centerX(), scrollable.scroll.bottom - dp(24),
                scrollable.scroll.centerX(), scrollable.scroll.top + dp(24), true);
        await(() -> layout() != null && layout().scrollY > scrollable.scrollY, 3000, "actual-card-scroll-received-" + side);
        insideScrolls++;
        require(AssistantState.expanded, "inside-scroll-keeps-card-open-" + side);
        Layout close = layout();
        gesture("CLOSE_" + side, close.ball.centerX(), close.ball.centerY(), 0, 0, false);
        await(() -> !AssistantState.expanded && layout() != null && layout().panel.width() == collapsed.panel.width(), 3000, "real-touch-collapses-card-" + side);
        try {
            await(() -> AssistantState.frames > frames && anchorsMoved(initialPositions, codePositions()),
                    20000, "real-wechat-fixed-message-anchor-moved-after-outside-scroll-" + side);
        } catch (AssertionError failure) {
            Map<String, Integer> finalPositions = codePositions();
            int shared = 0;
            int moved = 0;
            int maximumDelta = 0;
            for (Map.Entry<String, Integer> entry : initialPositions.entrySet()) {
                Integer after = finalPositions.get(entry.getKey());
                if (after == null) continue;
                shared++;
                int delta = Math.abs(after - entry.getValue());
                if (delta >= dp(24)) moved++;
                maximumDelta = Math.max(maximumDelta, delta);
            }
            throw new AssertionError(failure.getMessage() + " beforeFrames=" + frames + " afterFrames=" + AssistantState.frames
                    + " beforeAnchors=" + initialPositions.size() + " afterAnchors=" + finalPositions.size()
                    + " sharedAnchors=" + shared + " movedAnchors=" + moved + " maxAnchorDeltaPx=" + maximumDelta);
        }
        outsideChanges++;
        gesture("RETURN_" + side, strip.centerX(), endVertical, strip.centerX(), startVertical, true);
        await(() -> fixedVisible() && AssistantState.frames > frames, 20000, "real-body-ocr-resumes-on-fixed-chat-" + side);
    }

    /** 新产品默认折叠详情；先正常触摸展开，再沿用原滚动与范围判据。 */
    private void openScrollableDetails(String side) {
        Rect more = onMain(() -> visibleControl((View) field(AssistOverlay.class, currentOverlay(), "managementToggle")));
        require(!more.isEmpty(), "more-control-visible-" + side);
        gesture("MORE_" + side, more.centerX(), more.centerY(), 0, 0, false);
        await(() -> onMain(() -> ((View) field(AssistOverlay.class, currentOverlay(), "management")).isShown()),
                3000, "more-controls-open-" + side);
        Rect source = sourceButton();
        for (int i = 0; (source.isEmpty() || source.height() < dp(32)) && i < 3; i++) {
            Layout current = layout();
            require(current != null && !current.scroll.isEmpty(), "prepare-scroll-present-" + side);
            gesture("PREPARE_" + side, current.scroll.centerX(), current.scroll.bottom - dp(24),
                    current.scroll.centerX(), current.scroll.top + dp(24), true);
            source = sourceButton();
        }
        require(!source.isEmpty() && source.height() >= dp(32), "source-control-visible-" + side);
        gesture("SOURCE_" + side, source.centerX(), source.centerY(), 0, 0, false);
        await(() -> onMain(() -> ((View) field(AssistOverlay.class, currentOverlay(), "sourceDetails")).isShown()),
                3000, "source-details-open-" + side);
    }

    private Rect sourceButton() {
        return onMain(() -> {
            android.view.ViewGroup group = (android.view.ViewGroup) field(AssistOverlay.class, currentOverlay(), "management");
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof android.widget.Button && "查看识别原文 · 请核对".contentEquals(((android.widget.Button) child).getText()))
                    return visibleControl(child);
            }
            return new Rect();
        });
    }

    private Rect visibleControl(View view) {
        Object overlay = currentOverlay();
        Rect result = screenBounds(view);
        Rect scrollBounds = screenBounds((View) field(AssistOverlay.class, overlay, "scroll"));
        Rect panelBounds = rect((View) field(AssistOverlay.class, overlay, "panel"));
        if (!result.intersect(scrollBounds) || !result.intersect(panelBounds)) result.setEmpty();
        return result;
    }

    /** ScrollView本地可见矩形含自身scrollY，触屏坐标必须使用屏幕上的真实视口。 */
    private Rect screenBounds(View view) {
        if (!view.isShown()) return new Rect();
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    private Rect outsideStrip(ChatViewport viewport, Rect panel, boolean right) {
        int left = right ? viewport.bounds.left + dp(8) : panel.right + dp(8);
        int rightEdge = right ? panel.left - dp(8) : viewport.bounds.right - dp(8);
        int top = viewport.bounds.top + dp(80);
        int bottom = viewport.bounds.bottom - dp(120);
        require(rightEdge - left >= dp(32) && bottom - top >= dp(240), "space-outside-real-card");
        Rect best = null;
        for (int horizontal = left + dp(16); horizontal <= rightEdge - dp(16); horizontal += dp(8)) {
            List<Rect> blocked = new ArrayList<>();
            for (ChatViewport.Bounds mask : viewport.masks) {
                int maskLeft = mask.left + viewport.bounds.left - dp(8);
                int maskRight = mask.right + viewport.bounds.left + dp(8);
                if (maskLeft < horizontal + dp(12) && maskRight > horizontal - dp(12)) {
                    blocked.add(new Rect(maskLeft, Math.max(top, mask.top + viewport.bounds.top - dp(8)),
                            maskRight, Math.min(bottom, mask.bottom + viewport.bounds.top + dp(8))));
                }
            }
            blocked.sort(Comparator.comparingInt(value -> value.top));
            int beginning = top;
            for (Rect mask : blocked) {
                if (mask.bottom <= beginning || mask.top >= bottom) continue;
                if (mask.top - beginning >= dp(140) && (best == null || mask.top - beginning > best.height())) {
                    best = new Rect(horizontal - dp(12), beginning, horizontal + dp(12), mask.top);
                }
                beginning = Math.max(beginning, mask.bottom);
            }
            if (bottom - beginning >= dp(140) && (best == null || bottom - beginning > best.height())) {
                best = new Rect(horizontal - dp(12), beginning, horizontal + dp(12), bottom);
            }
        }
        if (best != null) return best;
        throw new AssertionError("no-unobscured-outside-gesture-strip");
    }

    private Map<String, Integer> codePositions() {
        return onMain(() -> {
            Map<String, Integer> positions = new HashMap<>();
            AssistantState.Snapshot target = AssistantState.snapshot();
            if (!AssistantState.matches(target) || !ChatGateService.snapshot().valid()) return positions;
            for (ChatMessage message : target.messages) {
                for (String code : ConversationSamples.matchedCodes(message.text)) positions.put(code, message.top);
            }
            return positions;
        });
    }

    private String diagnostic() {
        try {
            Layout current = layout();
            ChatGateService.Snapshot gate = ChatGateService.snapshot();
            return "gateValid=" + gate.valid() + " keyboardTop=" + gate.keyboardTop + " expanded=" + AssistantState.expanded
                    + (current == null ? " ownLayout=absent" : " dockRight=" + current.right
                    + " panel=" + current.panel.toShortString() + " ball=" + current.ball.toShortString());
        } catch (RuntimeException | AssertionError failure) {
            return "ownLayout=unavailable";
        }
    }

    private boolean anchorsMoved(Map<String, Integer> before, Map<String, Integer> after) {
        int moved = 0;
        for (Map.Entry<String, Integer> entry : before.entrySet()) {
            Integer current = after.get(entry.getKey());
            if (current != null && Math.abs(current - entry.getValue()) >= dp(24)) moved++;
        }
        return moved >= 2;
    }

    private void gesture(String name, int startX, int startY, int endX, int endY, boolean swipe) {
        long identifier = ++step;
        if (windowJournal != null) windowJournal.stage(name, identifier);
        if (recoveryJournal != null) onMain(() -> { recoveryJournal.stage(identifier); return null; });
        Bundle status = new Bundle();
        status.putString("phase", name);
        status.putLong("step", identifier);
        status.putString("gesture", swipe ? "swipe" : "tap");
        status.putInt("startX", startX);
        status.putInt("startY", startY);
        status.putInt("endX", endX);
        status.putInt("endY", endY);
        sendStatus(1, status);
        await(() -> completedStep == identifier, 20000, "external-input-finished-" + name);
        gestureActions++;
        SystemClock.sleep(350);
    }

    private void phase(String name) {
        Bundle status = new Bundle();
        status.putString("phase", name);
        status.putInt("parentPid", android.os.Process.myPid());
        sendStatus(1, status);
    }

    private void journal(String reason) {
        if (recoveryJournal != null) {
            for (String entry : onMain(() -> recoveryJournal.freeze())) {
                Bundle recovery = new Bundle(); recovery.putString("phase", "RECOVERY_JOURNAL");
                recovery.putString("recovery", entry); sendStatus(1, recovery);
            }
        }
        if (windowJournal == null) return;
        List<String> records = onMain(() -> windowJournal.snapshot(reason));
        for (String record : records) {
            Bundle status = new Bundle();
            status.putString("phase", "WINDOW_JOURNAL");
            status.putInt("parentPid", Process.myPid());
            status.putString("journal", record);
            sendStatus(1, status);
        }
    }

    private Layout layout() {
        return onMain(() -> {
            Object overlay = currentOverlay();
            if (overlay == null || !Boolean.TRUE.equals(field(AssistOverlay.class, overlay, "attached"))) return null;
            View panel = (View) field(AssistOverlay.class, overlay, "panel");
            ScrollView scroll = (ScrollView) field(AssistOverlay.class, overlay, "scroll");
            android.widget.LinearLayout actions = (android.widget.LinearLayout) field(AssistOverlay.class, overlay, "actions");
            Rect scrollViewport = screenBounds(scroll);
            if (!scrollViewport.intersect(rect(panel))) scrollViewport.setEmpty();
            return new Layout(rect(panel), rect((View) field(AssistOverlay.class, overlay, "ball")), scrollViewport,
                    rect(actions.getChildAt(2)), Boolean.TRUE.equals(field(AssistOverlay.class, overlay, "dockRight")),
                    scroll.getScrollY(), scroll.getChildAt(0).getHeight() > scroll.getHeight() + dp(8));
        });
    }

    private Object currentOverlay() {
        Object gate = field(ChatGateService.class, null, "connected");
        return gate == null ? null : field(ChatGateService.class, gate, "overlay");
    }

    private Rect rect(View view) {
        Rect rectangle = new Rect();
        if (view.getVisibility() == View.VISIBLE && view.getLocalVisibleRect(rectangle)) {
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            rectangle.offset(location[0], location[1]);
        }
        return rectangle;
    }

    private boolean fixedVisible() {
        if (AssistantState.messages.isEmpty() || AssistantState.token.isEmpty() || !ChatGateService.snapshot().valid()) return false;
        for (ChatMessage message : AssistantState.messages) {
            if (ConversationSamples.matchedCodes(message.text).contains("T08")) return true;
        }
        return false;
    }

    private Object field(Class<?> type, Object instance, String name) {
        try { Field value = type.getDeclaredField(name); value.setAccessible(true); return value.get(instance); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("own-field-" + name, failure); }
    }

    private <T> T onMain(Supplier<T> action) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return action.get();
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { value.set(action.get()); } catch (Throwable problem) { failure.set(problem); } });
        if (failure.get() != null) throw new AssertionError("own-ui-observation-failed", failure.get());
        return value.get();
    }

    private boolean restorePosition() {
        SharedPreferences.Editor editor = position.edit().clear();
        for (Map.Entry<String, ?> entry : originalPosition.entrySet()) {
            if (entry.getValue() instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) entry.getValue());
            else if (entry.getValue() instanceof Float) editor.putFloat(entry.getKey(), (Float) entry.getValue());
        }
        return editor.commit();
    }

    private int dp(int value) { return Math.round(value * getTargetContext().getResources().getDisplayMetrics().density); }

    private void await(BooleanSupplier condition, long timeout, String name) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30);
        require(condition.getAsBoolean(), name);
    }

    private void require(boolean condition, String name) { checks++; if (!condition) throw new AssertionError(name); }
}
