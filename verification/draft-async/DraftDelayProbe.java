package com.codextemp.wechatcaptureprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class DraftDelayProbe extends Instrumentation {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String nonce = UUID.randomUUID().toString();
    private final AtomicBoolean trigger = new AtomicBoolean();
    private final AtomicBoolean fixtureLive = new AtomicBoolean();
    private final AtomicBoolean fixtureDelay = new AtomicBoolean();
    private final AtomicInteger rootCalls = new AtomicInteger();
    private final AtomicInteger callbacks = new AtomicInteger();
    private final AtomicInteger fields = new AtomicInteger();
    private final AtomicLong queryMs = new AtomicLong(-1);
    private final AtomicLong callbackMs = new AtomicLong(-1);
    private final AtomicBoolean busyAtTimeout = new AtomicBoolean();
    private final AtomicBoolean extraAccepted = new AtomicBoolean();
    private final AtomicReference<DraftWorker.Status> resultStatus = new AtomicReference<>();
    private final AtomicReference<DraftWorker.Task<DraftPlan.Field>> liveTask = new AtomicReference<>();
    private final AtomicLong cancelledAtMs = new AtomicLong(-1);
    private final AtomicBoolean busyAtCancellation = new AtomicBoolean();
    private String variant;
    private int checks;
    private Activity main;
    private UiAutomation automation;
    private ExecutorService executor;
    private DraftWorker queue;
    private int windowId = -1;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if ("com.codextemp.wechatcaptureprobe.DRAFT_DELAY_START".equals(intent.getAction())) trigger.set(true);
            else if (nonce.equals(intent.getStringExtra("nonce"))) {
                if ("com.codextemp.wechatcaptureprobe.DRAFT_FIXTURE_LIVE".equals(intent.getAction())) fixtureLive.set(true);
                if ("com.codextemp.wechatcaptureprobe.DRAFT_FIXTURE_DELAY".equals(intent.getAction())) fixtureDelay.set(true);
            }
        }
    };

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        variant = args.getString("variant", "candidate");
        start();
    }

    @Override public void onStart() {
        Bundle output = new Bundle();
        boolean registered = false;
        try {
            require(variant.equals("baseline") || variant.equals("candidate") || variant.equals("cancel"), "known-mode");
            ModelSettingsStore settings = new ModelSettingsStore(getTargetContext());
            require(!settings.hasKey() && !settings.cloudAllowed() && !AssistantCaptureService.running()
                    && !CaptureService.running() && !ChatGateService.enabled(), "no-model-capture-or-own-gate");
            IntentFilter filter = new IntentFilter("com.codextemp.wechatcaptureprobe.DRAFT_DELAY_START");
            filter.addAction("com.codextemp.wechatcaptureprobe.DRAFT_FIXTURE_LIVE");
            filter.addAction("com.codextemp.wechatcaptureprobe.DRAFT_FIXTURE_DELAY");
            if (Build.VERSION.SDK_INT >= 33) getTargetContext().registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else getTargetContext().registerReceiver(receiver, filter);
            registered = true;
            phase("WAITING_FOR_FOREGROUND_MAIN");
            main = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo serviceInfo = automation.getServiceInfo();
            serviceInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(serviceInfo);
            phase("WAITING_FOR_EXTERNAL_TRIGGER");
            await(trigger::get, 180000, "external-start");
            runOnMainSync(() -> main.startActivity(new Intent().setClassName(getContext().getPackageName(),
                    DraftDelayActivity.class.getName()).putExtra("nonce", nonce).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            phase("WAITING_FOR_ACTUAL_FIXTURE_FOCUS");
            await(fixtureLive::get, 120000, "focused-fixture-nonce-handshake");
            List<AccessibilityWindowInfo> windows = automation.getWindows();
            try {
                for (AccessibilityWindowInfo window : windows) {
                    if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && window.isActive()) windowId = window.getId();
                }
            } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
            require(windowId >= 0, "actual-fixture-application-window");
            List<Long> tasks = Collections.synchronizedList(new ArrayList<>());
            AtomicBoolean pending = new AtomicBoolean();
            AtomicLong postedAt = new AtomicLong();
            long started = SystemClock.elapsedRealtime();
            if (!variant.equals("baseline")) {
                executor = Executors.newSingleThreadExecutor(action -> {
                    Thread thread = new Thread(action, "draft-probe-nodes");
                    thread.setDaemon(true);
                    return thread;
                });
                queue = new DraftWorker(executor, action -> handler.post(action),
                        (action, delay) -> handler.postDelayed(action, delay), SystemClock::elapsedRealtime, 800);
                runOnMainSync(() -> {
                    DraftWorker.Task<DraftPlan.Field> task = queue.submit(() -> true, this::inspect, result -> {
                        resultStatus.set(result.status);
                        if (result.value != null) fields.incrementAndGet();
                        callbacks.incrementAndGet();
                        callbackMs.set(SystemClock.elapsedRealtime() - started);
                        busyAtTimeout.set(queue.busy());
                        if (queue.busy()) extraAccepted.set(queue.submit(() -> true, current -> null, ignored -> {}) != null);
                    });
                    liveTask.set(task);
                    require(task != null, "candidate-slot-admitted");
                });
            } else {
                handler.post(() -> {
                    if (inspect(() -> true) != null) fields.incrementAndGet();
                    callbacks.incrementAndGet();
                    callbackMs.set(SystemClock.elapsedRealtime() - started);
                });
            }
            phase("MEASURING_REMOTE_DRAFT_QUERY");
            boolean mainWait = false;
            boolean workerWait = false;
            while (SystemClock.elapsedRealtime() - started < 11000) {
                if (pending.compareAndSet(false, true)) {
                    long posted = SystemClock.elapsedRealtime();
                    postedAt.set(posted);
                    handler.post(() -> { tasks.add(SystemClock.elapsedRealtime() - posted); pending.set(false); });
                }
                if (!mainWait && pending.get() && SystemClock.elapsedRealtime() - postedAt.get() >= 1200) {
                    mainWait = stack("main", "OWN_MAIN_STACK");
                }
                if (!workerWait && fixtureDelay.get()) workerWait = stack("draft-probe-nodes", "OWN_WORKER_STACK");
                if (variant.equals("cancel") && fixtureDelay.get() && cancelledAtMs.get() < 0) {
                    runOnMainSync(() -> {
                        require(queue.busy(), "sdk-query-still-running-at-cancel");
                        liveTask.get().cancel();
                        cancelledAtMs.set(SystemClock.elapsedRealtime() - started);
                        busyAtCancellation.set(queue.busy());
                        extraAccepted.set(queue.submit(() -> true, current -> null, ignored -> {}) != null);
                    });
                }
                SystemClock.sleep(50);
            }
            runOnMainSync(() -> {});
            List<Long> sorted;
            synchronized (tasks) { sorted = new ArrayList<>(tasks); }
            Collections.sort(sorted);
            require(fixtureLive.get() && fixtureDelay.get(), "actual-remote-delay-observed");
            require(rootCalls.get() == 1 && callbacks.get() == (variant.equals("cancel") ? 0 : 1)
                    && fields.get() == 0, "one-read-only-query-no-input-field");
            Log.i("DraftDelayProbe", "MEASURED_QUERY variant=" + variant + " actualQueryMs=" + queryMs.get()
                    + " callbackMs=" + callbackMs.get() + " mainWait=" + mainWait + " workerWait=" + workerWait);
            require(!sorted.isEmpty() && queryMs.get() >= 1200, "actual-sdk-wait-not-cached-success");
            long maximum = sorted.get(sorted.size() - 1);
            if (variant.equals("candidate")) {
                require(resultStatus.get() == DraftWorker.Status.EXPIRED && callbackMs.get() < 1200, "bounded-ui-timeout");
                require(busyAtTimeout.get() && !extraAccepted.get() && !queue.busy(), "no-second-slot-until-sdk-return");
                require(workerWait && !mainWait && maximum < 1200, "remote-wait-not-on-main");
            } else if (variant.equals("cancel")) {
                require(cancelledAtMs.get() >= 0 && cancelledAtMs.get() < 800 && resultStatus.get() == null, "explicit-cancel-before-timeout-no-callback");
                require(busyAtCancellation.get() && !extraAccepted.get() && !queue.busy(), "cancel-does-not-open-second-sdk-slot");
                require(workerWait && !mainWait && maximum < 1200, "cancelled-remote-wait-stays-off-main");
            } else require(mainWait && maximum >= 1200, "production-editor-ui-wait-reproduced");
            require(!settings.cloudAllowed() && !settings.hasKey() && !AssistantCaptureService.running()
                    && !CaptureService.running(), "no-capture-or-model-throughout");
            long percentile = sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95) - 1));
            output.putString("result", "PASS draftDelayChecks=" + checks + " variant=" + variant
                    + " samples=" + sorted.size() + " mainQueueP95Ms=" + percentile + " mainQueueMaxMs=" + maximum
                    + " actualQueryMs=" + queryMs.get() + " callbackMs=" + callbackMs.get() + " mainWaitStack=" + mainWait
                    + " workerWaitStack=" + workerWait + " busyAtTimeout=" + busyAtTimeout.get() + " extraAccepted=" + extraAccepted.get()
                    + " cancelledAtMs=" + cancelledAtMs.get() + " busyAtCancellation=" + busyAtCancellation.get()
                    + " rootCalls=" + rootCalls.get() + " screenGrants=0 imageReads=0 fixtureWrites=0 wechatWrites=0 modelRequests=0 chatFiles=0");
        } catch (Throwable failure) {
            output.putString("result", "FAIL draftDelayChecks=" + checks + " variant=" + variant
                    + " type=" + failure.getClass().getSimpleName()
                    + (failure instanceof AssertionError ? " check=" + failure.getMessage() : ""));
        } finally {
            if (registered) getTargetContext().unregisterReceiver(receiver);
            if (executor != null) executor.shutdownNow();
            runOnMainSync(() -> { if (main != null) main.finish(); });
        }
        Log.i("DraftDelayProbe", output.getString("result"));
        finish(output.getString("result").startsWith("PASS") ? -1 : 0, output);
    }

    private DraftPlan.Field inspect(BooleanSupplier current) {
        long started = SystemClock.elapsedRealtime();
        rootCalls.incrementAndGet();
        try {
            return new AccessibilityDraftEditor(automation::getRootInActiveWindow, getContext().getPackageName(),
                    windowId, new Rect(0, 0, 1080, 2400), current).inspect();
        } finally { queryMs.set(SystemClock.elapsedRealtime() - started); }
    }

    private boolean stack(String name, String tag) {
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            if (!entry.getKey().getName().equals(name)
                    || java.util.Arrays.stream(entry.getValue()).noneMatch(frame -> frame.getClassName().contains("AccessibilityInteractionClient"))) continue;
            for (int index = 0; index < Math.min(18, entry.getValue().length); index++) Log.i("DraftDelayProbe", tag + " " + entry.getValue()[index]);
            return true;
        }
        return false;
    }

    private void phase(String name) {
        Log.i("DraftDelayProbe", "PHASE " + name);
        Bundle status = new Bundle(); status.putString("phase", name); sendStatus(1, status);
    }

    private void await(BooleanSupplier condition, long timeout, String name) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        require(condition.getAsBoolean(), name);
    }

    private void require(boolean condition, String name) { checks++; if (!condition) throw new AssertionError(name); }
}
